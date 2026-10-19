package com.studentcommandcenter.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/** W3C-VC issuance and lifecycle service. */
class CredentialService(private val ipfs: IpfsClient, private val chain: BlockchainAnchor) {
    @Serializable
    data class ImportOutcome(val accepted: Int, val skipped: Int, val committed: Boolean, val totalRows: Int)

    /** CSV import is additive; duplicate email/registration/external IDs are skipped. */
    fun importStudents(adminUserId: Long, universityId: Long, csvText: String, sourceName: String?, commit: Boolean): ImportOutcome {
        require(csvText.toByteArray().size <= Config.csvMaxBytes) { "CSV exceeds the 2 MB limit" }
        require(transaction { Universities.selectAll().where { Universities.id eq universityId }.count() > 0L }) { "university not found" }
        val rows = parseCsv(csvText)
        require(rows.size >= 2) { "CSV must contain a header row plus at least one data row" }
        val header = rows.first().map { it.trim().lowercase() }
        fun col(vararg names: String): Int? = names.map { header.indexOf(it) }.firstOrNull { it >= 0 }
        val nameIdx = col("full_name", "name") ?: error(400, "CSV header must contain full_name or name")
        val emailIdx = col("email") ?: error(400, "CSV header must contain email")
        val externalIdx = col("external_id")
        val regIdx = col("registration_number", "registration", "student_id")
        val programmeIdx = col("programme", "program")
        val schoolIdx = col("school_name", "school")
        val dataRows = rows.drop(1)
        require(dataRows.size <= Config.csvMaxRows) { "CSV exceeds the 2,000-row limit" }

        var accepted = 0
        var skipped = 0
        val seen = mutableSetOf<String>()
        transaction {
            dataRows.forEach { row ->
                fun cell(idx: Int?) = idx?.let { row.getOrNull(it)?.trim()?.takeIf(String::isNotBlank) }
                val name = cell(nameIdx)
                val email = cell(emailIdx)?.lowercase()
                if (name == null || email == null || !email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) {
                    skipped++
                    return@forEach
                }
                val externalId = cell(externalIdx)
                val regNo = cell(regIdx)
                val duplicateKeys = listOfNotNull("email:$email", regNo?.let { "reg:$it" }, externalId?.let { "ext:$it" })
                val emailExists = ImportedStudents.selectAll()
                    .where { (ImportedStudents.universityId eq universityId) and (ImportedStudents.email eq email) }.count() > 0L
                val regExists = regNo?.let { value -> ImportedStudents.selectAll()
                    .where { (ImportedStudents.universityId eq universityId) and (ImportedStudents.registrationNumber eq value) }.count() > 0L } ?: false
                val extExists = externalId?.let { value -> ImportedStudents.selectAll()
                    .where { (ImportedStudents.universityId eq universityId) and (ImportedStudents.externalId eq value) }.count() > 0L } ?: false
                if (emailExists || regExists || extExists || duplicateKeys.any { it in seen }) {
                    skipped++
                    return@forEach
                }
                seen.addAll(duplicateKeys)
                if (commit) {
                    val schoolRow = cell(schoolIdx)?.let { nameValue ->
                        Schools.selectAll().where {
                            (Schools.universityId eq universityId) and (Schools.name eq nameValue)
                        }.limit(1).singleOrNull()
                    }
                    ImportedStudents.insert {
                        it[ImportedStudents.universityId] = universityId
                        it[ImportedStudents.schoolId] = schoolRow?.get(Schools.id)
                        it[ImportedStudents.externalId] = externalId
                        it[ImportedStudents.registrationNumber] = regNo
                        it[ImportedStudents.name] = name
                        it[ImportedStudents.email] = email
                        it[ImportedStudents.programme] = cell(programmeIdx)
                        it[ImportedStudents.rawJson] = Json.encodeToString(row)
                        it[ImportedStudents.importedAt] = System.currentTimeMillis()
                    }
                }
                accepted++
            }
        }
        Audit.log(actorId = adminUserId, actorRole = "system_admin", action = "credential.import.csv",
            entityType = "university", entityId = universityId.toString(),
            detail = Json.encodeToString(mapOf("source" to (sourceName ?: "paste"), "accepted" to accepted.toString(), "skipped" to skipped.toString(), "committed" to commit.toString())))
        return ImportOutcome(accepted, skipped, commit, dataRows.size)
    }

    fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var field = StringBuilder()
        var row = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' -> if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                c == '"' -> inQuotes = true
                c == ',' && !inQuotes -> { row.add(field.toString()); field.setLength(0) }
                (c == '\n' || c == '\r') && !inQuotes -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(field.toString()); field.setLength(0)
                    if (row.any(String::isNotBlank)) rows.add(row.toList())
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any(String::isNotBlank)) rows.add(row.toList())
        }
        return rows
    }

    /** Returns a per-university signing key id and public PEM, creating an Ed25519 key if absent. */
    fun ensureInternalKey(universityId: Long): Pair<String, String> = transaction {
        val existing = UniversityKeys.selectAll()
            .where { (UniversityKeys.universityId eq universityId) and (UniversityKeys.isInternal eq true) }
            .limit(1).singleOrNull()
        if (existing != null) existing[UniversityKeys.keyId] to existing[UniversityKeys.publicKeyPem]
        else {
            val pair = Security.generateEd25519KeyPair()
            val keyId = "key-${Security.randomId()}"
            val publicPem = Security.ed25519PublicPem(pair.public)
            UniversityKeys.insert {
                it[UniversityKeys.universityId] = universityId
                it[UniversityKeys.keyId] = keyId
                it[UniversityKeys.publicKeyPem] = publicPem
                it[UniversityKeys.encryptedPrivateKeyPem] = Security.encryptPlaintext(Security.ed25519PrivatePem(pair.private))
                it[UniversityKeys.isInternal] = true
                it[UniversityKeys.externalRef] = null
                it[UniversityKeys.createdAt] = System.currentTimeMillis()
            }
            Audit.log(action = "credential.key.created", entityType = "university", entityId = universityId.toString())
            keyId to publicPem
        }
    }

    private fun loadInternalPrivateKey(universityId: Long): java.security.PrivateKey {
        val encryptedKey = transaction {
            UniversityKeys.selectAll().where {
                (UniversityKeys.universityId eq universityId) and (UniversityKeys.isInternal eq true)
            }.limit(1).singleOrNull()?.get(UniversityKeys.encryptedPrivateKeyPem)
        } ?: error(400, "university has no internal signing key")
        return Security.parseEd25519PrivatePem(Security.decryptCiphertext(encryptedKey))
    }

    /** Issues a W3C VC 2.0-shaped VC-JWT; content pinned to IPFS is encrypted. */
    suspend fun issueCredential(
        adminUserId: Long,
        universityId: Long,
        studentRecordId: Long,
        title: String,
        credentialType: String? = null,
        awardDate: String? = null,
        classification: String? = null,
        additionalClaims: Map<String, String> = emptyMap(),
        previousCredentialId: Long? = null
    ): Long {
        require(title.isNotBlank() && title.length <= 300) { "title is required and must be under 300 characters" }
        val university = transaction {
            Universities.selectAll().where { Universities.id eq universityId }.limit(1).singleOrNull()?.let { row ->
                val schoolCount = Schools.selectAll().where { Schools.universityId eq universityId }.count().toInt()
                UniversityDto(row[Universities.id], row[Universities.name], row[Universities.tagline], row[Universities.details],
                    row[Universities.logoUrl], schoolCount, row[Universities.createdAt])
            }
        } ?: error(404, "unknown university")
        val student = transaction {
            ImportedStudents.selectAll().where { ImportedStudents.id eq studentRecordId }.limit(1).singleOrNull()?.let { row ->
                ImportedStudentDto(row[ImportedStudents.id], row[ImportedStudents.universityId], row[ImportedStudents.externalId],
                    row[ImportedStudents.registrationNumber], row[ImportedStudents.name], row[ImportedStudents.email],
                    row[ImportedStudents.programme], row[ImportedStudents.importedAt])
            }
        } ?: error(404, "unknown imported student")
        require(student.universityId == universityId) { "student record does not belong to this university" }

        val (keyId, publicPem) = ensureInternalKey(universityId)
        val privateKey = loadInternalPrivateKey(universityId)
        val issuerDid = "did:web:${Config.issuerDidWebHost}:uni:$universityId"
        val now = Instant.now()
        val credentialId = "SCC-VC-${Security.randomId().uppercase()}"

        val claims = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive("urn:scc:student:${student.id}"),
            "name" to JsonPrimitive(student.name)
        )
        student.registrationNumber?.let { claims["registrationNumber"] = JsonPrimitive(it) }
        student.programme?.let { claims["programme"] = JsonPrimitive(it) }
        additionalClaims.forEach { (key, value) ->
            require(key.matches(Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$"))) { "invalid additional claim name: $key" }
            claims[key] = JsonPrimitive(value.take(1000))
        }

        val vcObject = buildJsonObject {
            put("@context", JsonArray(listOf(JsonPrimitive("https://www.w3.org/ns/credentials/v2"))))
            put("type", JsonArray(listOf(JsonPrimitive("VerifiableCredential"), JsonPrimitive(credentialType ?: "AcademicCredential"))))
            put("issuer", buildJsonObject { put("id", issuerDid); put("name", university.name) })
            put("validFrom", now.toString())
            put("credentialSubject", JsonObject(claims))
            put("credentialStatus", buildJsonObject {
                put("id", "${Config.baseUrl}/api/v1/public/credentials/$credentialId/status")
                put("type", "SccCredentialStatus")
            })
            put("name", title)
            awardDate?.let { put("awardDate", it) }
            classification?.let { put("classification", it) }
        }
        // VC-JWT envelope uses registered JWT claims plus the VC data-model object.
        val subjectId = (claims["id"] as? JsonPrimitive)?.content ?: "urn:scc:student:unknown"
        val jwtPayload = buildJsonObject {
            put("iss", issuerDid)
            put("sub", subjectId)
            put("nbf", now.epochSecond)
            put("iat", now.epochSecond)
            put("jti", credentialId)
            put("vc", vcObject)
        }
        val headerJson = """{"alg":"EdDSA","typ":"JWT","kid":"$issuerDid#$keyId"}"""
        val signedJwt = Security.compactEdDsaJwt(headerJson, Json.encodeToString(jwtPayload), privateKey)
        val digest = Security.sha256Hex(signedJwt)

        // Both local DB and IPFS store only AES-256-GCM ciphertext for sensitive VC content.
        val encryptedPayload = Security.encryptPlaintext(Json.encodeToString(jwtPayload))
        val encryptedJwt = Security.encryptPlaintext(signedJwt)
        val ipfsEnvelope = buildJsonObject {
            put("schema", "scc-encrypted-vc-envelope/v1")
            put("credentialId", credentialId)
            put("issuerDid", issuerDid)
            put("issuerKeyId", keyId)
            put("issuerPublicKeyPem", publicPem)
            put("digest", digest)
            put("issuedAt", now.toString())
            put("cipher", "AES-256-GCM")
            put("encryptedCredentialPayload", encryptedPayload)
            put("encryptedSignedCredential", encryptedJwt)
        }
        val cid = try {
            ipfs.addAndPin("$credentialId.enc.json", Json.encodeToString(ipfsEnvelope).toByteArray(), "application/json")
        } catch (e: Exception) {
            Audit.log(actorId = adminUserId, action = "credential.ipfs.failed", entityType = "credential",
                entityId = credentialId, status = "error", errorMessage = e.message)
            null
        }

        val credentialDbId = transaction {
            Credentials.insert {
                it[Credentials.credentialId] = credentialId
                it[Credentials.universityId] = universityId
                it[Credentials.studentRecordId] = studentRecordId
                it[Credentials.userId] = null
                it[Credentials.title] = title
                it[Credentials.credentialType] = credentialType ?: "AcademicCredential"
                it[Credentials.awardDate] = awardDate
                it[Credentials.classification] = classification
                it[Credentials.claimsJson] = Json.encodeToString(additionalClaims)
                it[Credentials.encryptedSignedJwt] = encryptedJwt
                it[Credentials.encryptedPayloadJson] = encryptedPayload
                it[Credentials.digest] = digest
                it[Credentials.status] = CredentialStatus.active.name
                it[Credentials.previousCredentialId] = previousCredentialId
                it[Credentials.supersededByCredentialId] = null
                it[Credentials.revokedAt] = null
                it[Credentials.revokeReason] = null
                it[Credentials.ipfsCid] = cid
                it[Credentials.anchorStatus] = AnchorStatus.pending.name
                it[Credentials.anchorTxHash] = null
                it[Credentials.issuedAt] = now.toEpochMilli()
            }[Credentials.id]
        }

        var txHash: String? = null
        var anchorStatus = AnchorStatus.local_only.name
        if (chain.configured) {
            try {
                txHash = chain.anchorCredential(digest, cid)
                anchorStatus = AnchorStatus.anchored.name
                transaction {
                    Credentials.update({ Credentials.id eq credentialDbId }) {
                        it[Credentials.anchorStatus] = anchorStatus
                        it[Credentials.anchorTxHash] = txHash
                    }
                    ChainAnchors.insert {
                        it[ChainAnchors.digest] = digest
                        it[ChainAnchors.txHash] = txHash!!
                        it[ChainAnchors.blockNumber] = null
                        it[ChainAnchors.anchorType] = "credential"
                        it[ChainAnchors.network] = Config.chainNetworkName
                        it[ChainAnchors.createdAt] = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                anchorStatus = AnchorStatus.failed.name
                Audit.log(actorId = adminUserId, action = "credential.anchor.failed", entityType = "credential",
                    entityId = credentialId, status = "error", errorMessage = e.message)
            }
        }
        transaction {
            Credentials.update({ Credentials.id eq credentialDbId }) { it[Credentials.anchorStatus] = anchorStatus }
            CredentialEvents.insert {
                it[CredentialEvents.credentialId] = credentialDbId
                it[CredentialEvents.type] = "issued"
                it[CredentialEvents.detail] = Json.encodeToString(mapOf("cid" to cid, "anchorStatus" to anchorStatus, "txHash" to txHash))
                it[CredentialEvents.createdAt] = System.currentTimeMillis()
            }
        }
        Audit.log(actorId = adminUserId, action = "credential.issued", entityType = "credential", entityId = credentialId,
            detail = "anchorStatus=$anchorStatus")
        return credentialDbId
    }

    suspend fun revokeCredential(adminUserId: Long, credentialDbId: Long, reason: String) {
        require(reason.isNotBlank()) { "revocation reason is required" }
        val row = credentialRowById(credentialDbId)
        require(row[Credentials.status] == CredentialStatus.active.name) { "only active credentials can be revoked" }
        val now = System.currentTimeMillis()
        transaction {
            Credentials.update({ Credentials.id eq credentialDbId }) {
                it[Credentials.status] = CredentialStatus.revoked.name
                it[Credentials.revokedAt] = now
                it[Credentials.revokeReason] = reason.take(500)
            }
            CredentialEvents.insert {
                it[CredentialEvents.credentialId] = credentialDbId
                it[CredentialEvents.type] = "revoked"
                it[CredentialEvents.detail] = reason.take(500)
                it[CredentialEvents.createdAt] = now
            }
        }
        if (chain.configured && row[Credentials.anchorStatus] == AnchorStatus.anchored.name) {
            try {
                chain.revokeOnChain(row[Credentials.digest])
            } catch (e: Exception) {
                Audit.log(actorId = adminUserId, action = "credential.revoke.chain.failed", entityType = "credential",
                    entityId = row[Credentials.credentialId], status = "error", errorMessage = e.message)
            }
        }
        Audit.log(actorId = adminUserId, action = "credential.revoked", entityType = "credential",
            entityId = row[Credentials.credentialId], detail = "reason recorded off-chain")
    }

    suspend fun reissueCredential(
        adminUserId: Long,
        credentialDbId: Long,
        title: String?,
        credentialType: String?,
        awardDate: String?,
        classification: String?,
        additionalClaims: Map<String, String>?
    ): Long {
        val previous = credentialRowById(credentialDbId)
        require(previous[Credentials.status] == CredentialStatus.active.name) { "only active credentials can be reissued" }
        val newId = issueCredential(
            adminUserId = adminUserId,
            universityId = previous[Credentials.universityId],
            studentRecordId = previous[Credentials.studentRecordId] ?: error(400, "credential has no student record"),
            title = title ?: previous[Credentials.title],
            credentialType = credentialType ?: previous[Credentials.credentialType],
            awardDate = awardDate ?: previous[Credentials.awardDate],
            classification = classification ?: previous[Credentials.classification],
            additionalClaims = additionalClaims ?: runCatching { Json.decodeFromString<Map<String, String>>(previous[Credentials.claimsJson]) }.getOrDefault(emptyMap()),
            previousCredentialId = credentialDbId
        )
        val newCredential = credentialRowById(newId)
        val now = System.currentTimeMillis()
        transaction {
            Credentials.update({ Credentials.id eq credentialDbId }) {
                it[Credentials.status] = CredentialStatus.superseded.name
                it[Credentials.supersededByCredentialId] = newCredential[Credentials.credentialId]
            }
            CredentialEvents.insert {
                it[CredentialEvents.credentialId] = credentialDbId
                it[CredentialEvents.type] = "superseded"
                it[CredentialEvents.detail] = "superseded by ${newCredential[Credentials.credentialId]}"
                it[CredentialEvents.createdAt] = now
            }
        }
        if (chain.configured && previous[Credentials.anchorStatus] == AnchorStatus.anchored.name &&
            newCredential[Credentials.anchorStatus] == AnchorStatus.anchored.name) {
            try {
                chain.markSupersededOnChain(previous[Credentials.digest], newCredential[Credentials.digest])
            } catch (e: Exception) {
                Audit.log(actorId = adminUserId, action = "credential.supersede.chain.failed", entityType = "credential",
                    entityId = previous[Credentials.credentialId], status = "error", errorMessage = e.message)
            }
        }
        return newId
    }

    fun walletCredentialsFor(user: UserRecord): List<CredentialDto> = transaction {
        Credentials.selectAll().where { Credentials.status eq CredentialStatus.active.name }
            .orderBy(Credentials.issuedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
            .mapNotNull { row ->
                val student = row[Credentials.studentRecordId]?.let { sid ->
                    ImportedStudents.selectAll().where { ImportedStudents.id eq sid }.limit(1).singleOrNull()
                } ?: return@mapNotNull null
                val match = user.email.equals(student[ImportedStudents.email], true) ||
                    (user.registrationNumber != null && user.registrationNumber == student[ImportedStudents.registrationNumber])
                if (!match) null else toDto(row.snapshot(Credentials), student[ImportedStudents.name], student[ImportedStudents.email])
            }
    }

    fun walletCredentialDetails(user: UserRecord): List<WalletCredentialDto> =
        walletCredentialsFor(user).map { dto ->
            WalletCredentialDto(
                credentialId = dto.credentialId,
                issuerName = dto.issuerName,
                issuerDid = dto.issuerDid,
                title = dto.title,
                credentialType = dto.credentialType,
                awardDate = dto.awardDate,
                classification = dto.classification,
                issuedAt = dto.issuedAt,
                status = dto.status,
                digest = dto.digest,
                ipfsCid = dto.ipfsCid,
                ipfsGatewayUrl = dto.ipfsCid?.let(ipfs::gatewayUrl),
                anchorStatus = dto.anchorStatus,
                anchorTxHash = dto.anchorTxHash,
                signedJwt = signedJwtFor(user, dto.credentialId)
            )
        }

    fun signedJwtFor(user: UserRecord, credentialId: String): String {
        val row = credentialRow(credentialId)
        require(row[Credentials.status] == CredentialStatus.active.name) { "credential is not active" }
        val matches = row[Credentials.studentRecordId]?.let { sid -> transaction {
            val student = ImportedStudents.selectAll().where { ImportedStudents.id eq sid }.limit(1).singleOrNull()
            student != null && (user.email.equals(student[ImportedStudents.email], true) ||
                (user.registrationNumber != null && user.registrationNumber == student[ImportedStudents.registrationNumber]))
        } } ?: false
        require(matches) { "credential does not belong to this wallet" }
        return Security.decryptCiphertext(row[Credentials.encryptedSignedJwt])
    }

    fun credentialRow(credentialId: String): RowSnapshot = transaction {
        Credentials.selectAll().where { Credentials.credentialId eq credentialId }.limit(1).singleOrNull()?.snapshot(Credentials)
    } ?: error(404, "unknown credential")

    fun credentialRowById(credentialDbId: Long): RowSnapshot = transaction {
        Credentials.selectAll().where { Credentials.id eq credentialDbId }.limit(1).singleOrNull()?.snapshot(Credentials)
    } ?: error(404, "unknown credential")

    fun credentialDto(credentialDbId: Long): CredentialDto = toDto(credentialRowById(credentialDbId))

    suspend fun publicStatus(credentialId: String): CredentialStatusDto {
        val row = credentialRow(credentialId)
        val universityName = transaction {
            Universities.selectAll().where { Universities.id eq row[Credentials.universityId] }.limit(1).singleOrNull()?.get(Universities.name)
        } ?: "unknown"
        val key = transaction {
            UniversityKeys.selectAll().where {
                (UniversityKeys.universityId eq row[Credentials.universityId]) and (UniversityKeys.isInternal eq true)
            }.limit(1).singleOrNull()?.let { it[UniversityKeys.keyId] to it[UniversityKeys.publicKeyPem] }
        }
        val onChain = if (chain.configured) chain.readAnchor(row[Credentials.digest])?.let { anchor ->
            OnChainInfo(Config.chainNetworkName, Config.anchorContractAddress!!, row[Credentials.anchorTxHash], anchor.issuedAt * 1000L, anchor.statusLabel())
        } else null
        return CredentialStatusDto(
            credentialId = row[Credentials.credentialId],
            issuerName = universityName,
            issuerDid = "did:web:${Config.issuerDidWebHost}:uni:${row[Credentials.universityId]}",
            title = row[Credentials.title],
            credentialType = row[Credentials.credentialType],
            digest = row[Credentials.digest],
            status = row[Credentials.status],
            issuedAt = row[Credentials.issuedAt],
            revokedAt = row[Credentials.revokedAt],
            revokeReason = row[Credentials.revokeReason],
            supersededByCredentialId = row[Credentials.supersededByCredentialId],
            keyId = key?.first,
            issuerPublicKeyPem = key?.second,
            ipfsCid = row[Credentials.ipfsCid],
            ipfsGatewayUrl = row[Credentials.ipfsCid]?.let { ipfs.gatewayUrl(it) },
            onChain = onChain
        )
    }

    fun universityWorkspace(universityId: Long): UniversityCredentialWorkspace {
        val university = transaction {
            Universities.selectAll().where { Universities.id eq universityId }.limit(1).singleOrNull()?.let { row ->
                val schoolCount = Schools.selectAll().where { Schools.universityId eq universityId }.count().toInt()
                UniversityDto(row[Universities.id], row[Universities.name], row[Universities.tagline], row[Universities.details],
                    row[Universities.logoUrl], schoolCount, row[Universities.createdAt])
            }
        } ?: error(404, "unknown university")
        val students = transaction {
            ImportedStudents.selectAll().where { ImportedStudents.universityId eq universityId }
                .orderBy(ImportedStudents.importedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                .map { ImportedStudentDto(it[ImportedStudents.id], it[ImportedStudents.universityId], it[ImportedStudents.externalId],
                    it[ImportedStudents.registrationNumber], it[ImportedStudents.name], it[ImportedStudents.email],
                    it[ImportedStudents.programme], it[ImportedStudents.importedAt]) }
        }
        val credentials = transaction {
            Credentials.selectAll().where { Credentials.universityId eq universityId }
                .orderBy(Credentials.issuedAt to org.jetbrains.exposed.sql.SortOrder.DESC).map { toDto(it.snapshot(Credentials)) }
        }
        val keys = transaction {
            UniversityKeys.selectAll().where { UniversityKeys.universityId eq universityId }.map {
                SigningKeyDto(it[UniversityKeys.id], it[UniversityKeys.universityId], it[UniversityKeys.keyId],
                    it[UniversityKeys.publicKeyPem], it[UniversityKeys.isInternal], it[UniversityKeys.externalRef], it[UniversityKeys.createdAt])
            }
        }
        return UniversityCredentialWorkspace(university, students, credentials, keys)
    }

    fun registerExternalKey(universityId: Long, keyId: String, publicKeyPem: String, externalRef: String?): Long {
        Security.parseEd25519PublicKeyPem(publicKeyPem)
        val id = transaction {
            UniversityKeys.insert {
                it[UniversityKeys.universityId] = universityId
                it[UniversityKeys.keyId] = keyId
                it[UniversityKeys.publicKeyPem] = publicKeyPem
                it[UniversityKeys.encryptedPrivateKeyPem] = null
                it[UniversityKeys.isInternal] = false
                it[UniversityKeys.externalRef] = externalRef
                it[UniversityKeys.createdAt] = System.currentTimeMillis()
            }[UniversityKeys.id]
        }
        Audit.log(action = "credential.externalKey.registered", entityType = "university", entityId = universityId.toString())
        return id
    }

    fun credentialEvents(credentialDbId: Long): List<CredentialEventDto> = transaction {
        CredentialEvents.selectAll().where { CredentialEvents.credentialId eq credentialDbId }
            .orderBy(CredentialEvents.createdAt to org.jetbrains.exposed.sql.SortOrder.ASC)
            .map { CredentialEventDto(it[CredentialEvents.id], it[CredentialEvents.credentialId], it[CredentialEvents.type], it[CredentialEvents.detail] ?: "", it[CredentialEvents.createdAt]) }
    }

    private data class StudentBrief(val name: String, val email: String, val registrationNumber: String?, val programme: String?)

    private fun toDto(row: RowSnapshot, studentName: String? = null, studentEmail: String? = null): CredentialDto {
        val universityName = transaction { Universities.selectAll().where { Universities.id eq row[Credentials.universityId] }.limit(1).singleOrNull()?.get(Universities.name) } ?: "unknown"
        val student = row[Credentials.studentRecordId]?.let { sid -> transaction {
            ImportedStudents.selectAll().where { ImportedStudents.id eq sid }.limit(1).singleOrNull()?.let {
                StudentBrief(it[ImportedStudents.name], it[ImportedStudents.email], it[ImportedStudents.registrationNumber], it[ImportedStudents.programme])
            }
        } }
        return CredentialDto(
            id = row[Credentials.id], credentialId = row[Credentials.credentialId], universityId = row[Credentials.universityId],
            universityName = universityName, studentRecordId = row[Credentials.studentRecordId],
            studentName = studentName ?: student?.name, studentEmail = studentEmail ?: student?.email,
            registrationNumber = student?.registrationNumber, programme = student?.programme,
            title = row[Credentials.title], credentialType = row[Credentials.credentialType], awardDate = row[Credentials.awardDate],
            classification = row[Credentials.classification],
            claims = runCatching { Json.decodeFromString<Map<String, String>>(row[Credentials.claimsJson]) }.getOrDefault(emptyMap()),
            digest = row[Credentials.digest], status = row[Credentials.status], ipfsCid = row[Credentials.ipfsCid],
            anchorStatus = row[Credentials.anchorStatus], anchorTxHash = row[Credentials.anchorTxHash], issuedAt = row[Credentials.issuedAt],
            revokedAt = row[Credentials.revokedAt], revokeReason = row[Credentials.revokeReason],
            supersededByCredentialId = row[Credentials.supersededByCredentialId],
            issuerName = universityName,
            issuerDid = "did:web:${Config.issuerDidWebHost}:uni:${row[Credentials.universityId]}", keyId = null
        )
    }
}
