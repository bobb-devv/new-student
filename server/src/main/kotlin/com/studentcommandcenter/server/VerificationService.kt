package com.studentcommandcenter.server

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Employer-to-registrar consent/release workflow. */
class VerificationService(private val credentialService: CredentialService) {
    private data class StudentBrief(val name: String, val registrationNumber: String?, val programme: String?)

    /** Resolve by SCC certificate number, exact SHA-256 digest, or registration number within a university. */
    private fun resolveKey(credentialKey: String, universityId: Long): RowSnapshot? {
        val key = credentialKey.trim()
        if (key.isEmpty()) return null
        return transaction {
            val found = when {
                key.matches(Regex("^[0-9a-fA-F]{64}$")) -> Credentials.selectAll()
                    .where { Credentials.digest eq key.lowercase() }.limit(1).singleOrNull()
                key.startsWith("SCC-VC-") -> Credentials.selectAll()
                    .where { Credentials.credentialId eq key }.limit(1).singleOrNull()
                else -> {
                    val student = ImportedStudents.selectAll().where {
                        (ImportedStudents.universityId eq universityId) and (ImportedStudents.registrationNumber eq key)
                    }.limit(1).singleOrNull()
                    student?.let { s -> Credentials.selectAll()
                        .where { (Credentials.studentRecordId eq s[ImportedStudents.id]) and (Credentials.universityId eq universityId) }
                        .orderBy(Credentials.issuedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                        .limit(1).singleOrNull() }
                }
            }
            found?.snapshot(Credentials)
        }
    }

    fun submitRequest(
        employerName: String, employerOrganisation: String, employerEmail: String,
        credentialKey: String, universityId: Long
    ): VerificationRequestDto {
        require(employerName.isNotBlank() && employerName.length <= 200) { "employer name is required" }
        require(employerOrganisation.isNotBlank() && employerOrganisation.length <= 300) { "employer organisation is required" }
        require(employerEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "enter a valid employer email" }
        require(credentialKey.isNotBlank() && credentialKey.length <= 300) { "credential number/key is required" }
        require(transaction { Universities.selectAll().where { Universities.id eq universityId }.count() > 0L }) { "university not found" }

        val matched = resolveKey(credentialKey, universityId)
            ?: error(404, "no certificate matched that key at the selected university")
        if (matched[Credentials.universityId] != universityId) error(409, "the certificate key and selected university do not match")
        if (matched[Credentials.status] != CredentialStatus.active.name) {
            error(409, "this certificate is ${matched[Credentials.status]} and cannot be released as active")
        }

        val trackingCode = Security.randomHex(32)
        val id = transaction {
            VerificationRequests.insert {
                it[VerificationRequests.employerName] = employerName.trim()
                it[VerificationRequests.employerOrganisation] = employerOrganisation.trim()
                it[VerificationRequests.employerEmail] = employerEmail.trim().lowercase()
                it[VerificationRequests.credentialKey] = credentialKey.trim()
                it[VerificationRequests.universityId] = universityId
                it[VerificationRequests.credentialIdFk] = matched[Credentials.id]
                it[VerificationRequests.requestToken] = trackingCode
                it[VerificationRequests.status] = VerificationRequestStatus.pending.name
                it[VerificationRequests.releasedFieldsJson] = null
                it[VerificationRequests.decidedAt] = null
                it[VerificationRequests.decidedBy] = null
                it[VerificationRequests.decisionNote] = null
                it[VerificationRequests.createdAt] = System.currentTimeMillis()
            }[VerificationRequests.id]
        }
        Audit.log(action = "verification.requested", entityType = "verificationRequest", entityId = id.toString(),
            detail = Json.encodeToString(mapOf("employerOrganisation" to employerOrganisation, "universityId" to universityId.toString())))
        val row = transaction {
            VerificationRequests.selectAll().where { VerificationRequests.id eq id }.limit(1).single().snapshot(VerificationRequests)
        }
        return toDto(row, includeTrackingCode = true)
    }

    fun requestsForRegistrar(universityId: Long): List<VerificationRequestDto> = transaction {
        VerificationRequests.selectAll().where { VerificationRequests.universityId eq universityId }
            .orderBy(VerificationRequests.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC)
            .map { toDto(it.snapshot(VerificationRequests), includeTrackingCode = false) }
    }

    fun decide(registrar: UserRecord, requestId: Long, approve: Boolean, releasedFields: List<String>?, note: String?): VerificationRequestDto {
        require(registrar.role == UserRole.registrar && registrar.universityId != null) { "registrar access required" }
        val row = transaction {
            VerificationRequests.selectAll().where { VerificationRequests.id eq requestId }
                .limit(1).singleOrNull()?.snapshot(VerificationRequests)
        } ?: error(404, "verification request not found")
        require(row[VerificationRequests.universityId] == registrar.universityId) { "request belongs to another university" }
        require(row[VerificationRequests.status] == VerificationRequestStatus.pending.name) { "request has already been decided" }
        if (approve) {
            val credentialId = row[VerificationRequests.credentialIdFk] ?: error(400, "request has no matched certificate")
            val credential = credentialService.credentialRowById(credentialId)
            require(credential[Credentials.status] == CredentialStatus.active.name) { "certificate is no longer active" }
        }
        val allowedFields = setOf("identity", "credential", "chain", "programme")
        val fields = (releasedFields ?: listOf("identity", "credential", "chain", "programme"))
            .filter { it in allowedFields }.distinct()
        val now = System.currentTimeMillis()
        transaction {
            VerificationRequests.update({ VerificationRequests.id eq requestId }) {
                it[VerificationRequests.status] = if (approve) VerificationRequestStatus.approved.name else VerificationRequestStatus.rejected.name
                it[VerificationRequests.releasedFieldsJson] = if (approve) Json.encodeToString(fields) else null
                it[VerificationRequests.decidedAt] = now
                it[VerificationRequests.decidedBy] = registrar.id
                it[VerificationRequests.decisionNote] = note?.take(1000)
            }
        }
        Audit.log(actorId = registrar.id, actorRole = registrar.role.name, actorEmail = registrar.email,
            action = if (approve) "verification.approved" else "verification.rejected",
            entityType = "verificationRequest", entityId = requestId.toString(), detail = note)
        val updated = transaction {
            VerificationRequests.selectAll().where { VerificationRequests.id eq requestId }.limit(1).single().snapshot(VerificationRequests)
        }
        return toDto(updated, includeTrackingCode = false)
    }

    /** Employer polls by unguessable tracking code; approval reveals only registrar-selected fields. */
    fun resultForTrackingCode(trackingCode: String): VerificationResultDto? {
        if (!trackingCode.matches(Regex("^[0-9a-f]{64}$"))) return null
        val request = transaction {
            VerificationRequests.selectAll().where { VerificationRequests.requestToken eq trackingCode }
                .limit(1).singleOrNull()?.snapshot(VerificationRequests)
        } ?: return null
        if (request[VerificationRequests.createdAt] < System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000) return null
        val released = if (request[VerificationRequests.status] == VerificationRequestStatus.approved.name) {
            val credential = request[VerificationRequests.credentialIdFk]?.let { credentialService.credentialRowById(it) }
            credential?.let { buildReleased(it, releasedFieldsOf(request)) }
        } else null
        return VerificationResultDto(
            requestId = request[VerificationRequests.id], status = request[VerificationRequests.status],
            employerName = request[VerificationRequests.employerName], employerOrganisation = request[VerificationRequests.employerOrganisation],
            decidedAt = request[VerificationRequests.decidedAt], released = released
        )
    }

    private fun releasedFieldsOf(request: RowSnapshot): Set<String> {
        val raw = request[VerificationRequests.releasedFieldsJson] ?: return emptySet()
        return runCatching { Json.decodeFromString<List<String>>(raw).toSet() }.getOrDefault(emptySet())
    }

    private fun buildReleased(credential: RowSnapshot, fields: Set<String>): ReleasedRecordDto {
        val student = credential[Credentials.studentRecordId]?.let { sid -> transaction {
            ImportedStudents.selectAll().where { ImportedStudents.id eq sid }.limit(1).singleOrNull()?.let {
                StudentBrief(it[ImportedStudents.name], it[ImportedStudents.registrationNumber], it[ImportedStudents.programme])
            }
        } }
        val universityName = transaction {
            Universities.selectAll().where { Universities.id eq credential[Credentials.universityId] }.limit(1).singleOrNull()?.get(Universities.name)
        } ?: "unknown"
        val includeIdentity = "identity" in fields
        val includeProgramme = "programme" in fields
        val includeCredential = "credential" in fields
        val includeChain = "chain" in fields
        val onChain = if (includeChain) OnChainInfo(
            network = Config.chainNetworkName, contractAddress = Config.anchorContractAddress ?: "",
            txHash = credential[Credentials.anchorTxHash], anchoredAt = null, chainStatus = credential[Credentials.anchorStatus]
        ) else null
        return ReleasedRecordDto(
            credentialId = credential[Credentials.credentialId], issuerName = universityName,
            issuerDid = "did:web:${Config.issuerDidWebHost}:uni:${credential[Credentials.universityId]}",
            title = if (includeCredential) credential[Credentials.title] else "Academic credential",
            credentialType = if (includeCredential) credential[Credentials.credentialType] else "VerifiableCredential",
            awardDate = if (includeCredential) credential[Credentials.awardDate] else null,
            classification = if (includeCredential) credential[Credentials.classification] else null,
            studentName = if (includeIdentity) student?.name else null,
            registrationNumber = if (includeIdentity) student?.registrationNumber else null,
            programme = if (includeProgramme) student?.programme else null,
            issuedAt = credential[Credentials.issuedAt], status = credential[Credentials.status], digest = credential[Credentials.digest],
            ipfsCid = null, // never disclose CID for encrypted private content through employer result
            ipfsGatewayUrl = null, onChain = onChain
        )
    }

    private fun toDto(row: RowSnapshot, includeTrackingCode: Boolean): VerificationRequestDto {
        val credential = row[VerificationRequests.credentialIdFk]?.let { id -> transaction {
            Credentials.selectAll().where { Credentials.id eq id }.limit(1).singleOrNull()?.snapshot(Credentials)
        } }
        val universityName = transaction {
            Universities.selectAll().where { Universities.id eq row[VerificationRequests.universityId] }.limit(1).singleOrNull()?.get(Universities.name)
        } ?: "unknown"
        val studentName = credential?.get(Credentials.studentRecordId)?.let { id -> transaction {
            ImportedStudents.selectAll().where { ImportedStudents.id eq id }.limit(1).singleOrNull()?.get(ImportedStudents.name)
        } }
        return VerificationRequestDto(
            id = row[VerificationRequests.id], employerName = row[VerificationRequests.employerName],
            employerOrganisation = row[VerificationRequests.employerOrganisation], employerEmail = row[VerificationRequests.employerEmail],
            credentialKey = row[VerificationRequests.credentialKey], universityId = row[VerificationRequests.universityId],
            universityName = universityName, matchedCredentialId = credential?.get(Credentials.credentialId), matchedStudentName = studentName,
            status = row[VerificationRequests.status], createdAt = row[VerificationRequests.createdAt],
            decidedAt = row[VerificationRequests.decidedAt], decidedBy = row[VerificationRequests.decidedBy],
            decisionNote = row[VerificationRequests.decisionNote],
            trackingCode = if (includeTrackingCode) row[VerificationRequests.requestToken] else null
        )
    }
}
