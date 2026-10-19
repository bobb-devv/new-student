package com.studentcommandcenter.server

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

/**
 * SQLite schema for SCC Anchored. Tables are grouped by domain.
 * All student data is owner-scoped; credential payloads and signing
 * keys are stored AES-256-GCM encrypted (see Security.kt).
 */
object Database {
    fun connect() {
        if (Config.databaseUrl.startsWith("jdbc:sqlite:")) {
            val path = Config.databaseUrl.removePrefix("jdbc:sqlite:")
            File(path).absoluteFile.parentFile?.mkdirs()
        }
        org.jetbrains.exposed.sql.Database.connect(
            url = Config.databaseUrl,
            driver = "org.sqlite.JDBC"
        )
        transaction {
            SchemaUtils.create(
                Users, Universities, Schools, Sessions, UniversityKeys,
                ImportedStudents, Credentials, CredentialEvents,
                AcademicYears, Semesters, Units, Folders, Containers,
                Notes, ContainerFiles, Files, Tasks, ExamSessions, Exams,
                TimetableEntries, PerformanceRecords, StudyPlans,
                FriendRequests, Shares, VerificationRequests, VerificationGrants,
                ChainAnchors, IpfsPins, AuditLogs, Settings
            )
            seedSystemAdmin()
        }
    }

    private fun seedSystemAdmin() {
        val existing = transaction { Users.selectAll().where { Users.role eq UserRole.system_admin.name }.count() }
        if (existing == 0L) {
            val (hash, salt) = Security.hashPassword(Config.adminPassword)
            transaction {
                Users.insert {
                    it[Users.email] = Config.adminEmail.lowercase()
                    it[Users.passwordHash] = hash
                    it[Users.passwordSalt] = salt
                    it[Users.role] = UserRole.system_admin.name
                    it[Users.status] = UserStatus.active.name
                    it[Users.publicId] = "SCC-" + Security.randomId().uppercase()
                    it[Users.createdAt] = System.currentTimeMillis()
                }
            }
        }
    }
}

object Users : Table("users") {
    val id = long("id").autoIncrement()
    val email = varchar("email", 320).uniqueIndex()
    val passwordHash = varchar("password_hash", 256)
    val passwordSalt = varchar("password_salt", 64)
    val displayName = varchar("display_name", 120).nullable()
    val gender = varchar("gender", 32).nullable()
    val role = varchar("role", 32)
    val universityId = long("university_id").nullable()
    val registrationNumber = varchar("registration_number", 64).nullable()
    val status = varchar("status", 32)
    val publicId = varchar("public_id", 64).uniqueIndex()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Sessions : Table("sessions") {
    val tokenHash = varchar("token_hash", 128)
    override val primaryKey = PrimaryKey(tokenHash)
    val userId = long("user_id").references(Users.id)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
}

object Universities : Table("universities") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 200)
    val tagline = varchar("tagline", 300).nullable()
    val details = text("details").nullable()
    val logoUrl = varchar("logo_url", 500).nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Schools : Table("schools") {
    val id = long("id").autoIncrement()
    val universityId = long("university_id").references(Universities.id)
    val name = varchar("name", 200)
    val prefix = varchar("prefix", 12)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
    init { index("university_prefix", true, universityId, prefix) }
}

object UniversityKeys : Table("university_keys") {
    val id = long("id").autoIncrement()
    val universityId = long("university_id").references(Universities.id)
    val keyId = varchar("key_id", 120)
    val publicKeyPem = text("public_key_pem")
    val encryptedPrivateKeyPem = text("encrypted_private_key_pem").nullable()
    val isInternal = bool("is_internal")
    val externalRef = varchar("external_ref", 300).nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ImportedStudents : Table("imported_students") {
    val id = long("id").autoIncrement()
    val universityId = long("university_id").references(Universities.id)
    val schoolId = long("school_id").references(Schools.id).nullable()
    val externalId = varchar("external_id", 120).nullable()
    val registrationNumber = varchar("registration_number", 64).nullable()
    val name = varchar("name", 200)
    val email = varchar("email", 320)
    val programme = varchar("programme", 200).nullable()
    val rawJson = text("raw_json").nullable()
    val importedAt = long("imported_at")
    override val primaryKey = PrimaryKey(id)
    init { index("idx_imported_uni_email", false, universityId, email) }
}

object Credentials : Table("credentials") {
    val id = long("id").autoIncrement()
    val credentialId = varchar("credential_id", 64).uniqueIndex()
    val universityId = long("university_id").references(Universities.id)
    val studentRecordId = long("student_record_id").references(ImportedStudents.id).nullable()
    val userId = long("user_id").references(Users.id).nullable()
    val title = varchar("title", 300)
    val credentialType = varchar("credential_type", 120)
    val awardDate = varchar("award_date", 40).nullable()
    val classification = varchar("classification", 120).nullable()
    val claimsJson = text("claims_json")
    val encryptedSignedJwt = text("encrypted_signed_jwt")
    val encryptedPayloadJson = text("encrypted_payload_json")
    val digest = varchar("digest", 64).uniqueIndex()
    val status = varchar("status", 32)
    // Linked in service code; avoid self-referencing Table initialization.
    val previousCredentialId = long("previous_credential_id").nullable()
    val supersededByCredentialId = varchar("superseded_by_credential_id", 64).nullable()
    val revokedAt = long("revoked_at").nullable()
    val revokeReason = varchar("revoke_reason", 500).nullable()
    val ipfsCid = varchar("ipfs_cid", 120).nullable()
    val anchorStatus = varchar("anchor_status", 32)
    val anchorTxHash = varchar("anchor_tx_hash", 120).nullable()
    val issuedAt = long("issued_at")
    override val primaryKey = PrimaryKey(id)
}

object CredentialEvents : Table("credential_events") {
    val id = long("id").autoIncrement()
    val credentialId = long("credential_id").references(Credentials.id)
    val type = varchar("type", 60)
    val detail = text("detail").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object AcademicYears : Table("academic_years") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val name = varchar("name", 120)
    val startDate = varchar("start_date", 40).nullable()
    val endDate = varchar("end_date", 40).nullable()
    val archived = bool("archived")
    override val primaryKey = PrimaryKey(id)
}

object Semesters : Table("semesters") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val academicYearId = long("academic_year_id").references(AcademicYears.id)
    val name = varchar("name", 120)
    val startDate = varchar("start_date", 40).nullable()
    val endDate = varchar("end_date", 40).nullable()
    val isCurrent = bool("is_current")
    val archived = bool("archived")
    override val primaryKey = PrimaryKey(id)
}

object Units : Table("units") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val semesterId = long("semester_id").references(Semesters.id)
    val name = varchar("name", 200)
    val code = varchar("code", 40).nullable()
    val lecturer = varchar("lecturer", 200).nullable()
    val credits = varchar("credits", 20).nullable()
    val description = text("description").nullable()
    val remarks = text("remarks").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Notes : Table("notes") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val semesterId = long("semester_id").references(Semesters.id).nullable()
    val folderId = long("folder_id").references(Folders.id).nullable()
    val unitId = long("unit_id").references(Units.id).nullable()
    val title = varchar("title", 300)
    val content = text("content")
    val scopeType = varchar("scope_type", 20)
    val format = varchar("format", 20)
    val tagsJson = text("tags_json")
    val pinned = bool("pinned")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object Folders : Table("folders") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val semesterId = long("semester_id").references(Semesters.id)
    // Ownership/reference checks are enforced in the service; nullable self-reference avoids DDL init-order cycles.
    val parentFolderId = long("parent_folder_id").nullable()
    val unitId = long("unit_id").references(Units.id).nullable()
    val name = varchar("name", 200)
    val description = text("description").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Files : Table("files") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val semesterId = long("semester_id").references(Semesters.id).nullable()
    val unitId = long("unit_id").references(Units.id).nullable()
    val folderId = long("folder_id").references(Folders.id).nullable()
    val containerId = long("container_id").references(Containers.id).nullable()
    val name = varchar("name", 300)
    val size = long("size")
    val mime = varchar("mime", 120)
    val storagePath = varchar("storage_path", 500)
    val sha256 = varchar("sha256", 64)
    val ipfsCid = varchar("ipfs_cid", 120).nullable()
    val clientUploadId = varchar("client_upload_id", 80).nullable()
    val uploadedAt = long("uploaded_at")
    override val primaryKey = PrimaryKey(id)
}

/** NEW: student-owned "semester" or "event" record containers. */
object Containers : Table("containers") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val kind = varchar("kind", 20) // semester | event
    val name = varchar("name", 200)
    val description = text("description").nullable()
    val startDate = varchar("start_date", 40).nullable()
    val endDate = varchar("end_date", 40).nullable()
    val semesterId = long("semester_id").references(Semesters.id).nullable()
    val manifestCid = varchar("manifest_cid", 120).nullable()
    val manifestDigest = varchar("manifest_digest", 64).nullable()
    val anchored = bool("anchored")
    val anchorTxHash = varchar("anchor_tx_hash", 120).nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ContainerFiles : Table("container_files") {
    val id = long("id").autoIncrement()
    val containerId = long("container_id").references(Containers.id)
    val name = varchar("name", 300)
    val size = long("size")
    val mime = varchar("mime", 120)
    val storagePath = varchar("storage_path", 500)
    val sha256 = varchar("sha256", 64)
    val ipfsCid = varchar("ipfs_cid", 120).nullable()
    val note = text("note").nullable()
    val uploadedAt = long("uploaded_at")
    override val primaryKey = PrimaryKey(id)
}

object Tasks : Table("tasks") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val semesterId = long("semester_id").references(Semesters.id)
    val title = varchar("title", 300)
    val description = text("description").nullable()
    val unitId = long("unit_id").references(Units.id).nullable()
    val createdDate = varchar("created_date", 40).nullable()
    val deadline = varchar("deadline", 40).nullable()
    val priority = varchar("priority", 30).nullable()
    val status = varchar("status", 30).nullable()
    val completed = bool("completed")
    val category = varchar("category", 60).nullable()
    val remarks = text("remarks").nullable()
    override val primaryKey = PrimaryKey(id)
}

object ExamSessions : Table("exam_sessions") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val semesterId = long("semester_id").references(Semesters.id)
    val name = varchar("name", 200)
    val description = text("description").nullable()
    val remarks = text("remarks").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Exams : Table("exams") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val semesterId = long("semester_id").references(Semesters.id)
    val name = varchar("name", 300)
    val examSessionId = long("exam_session_id").references(ExamSessions.id).nullable()
    val unitId = long("unit_id").references(Units.id).nullable()
    val venue = varchar("venue", 200).nullable()
    val examDate = varchar("exam_date", 40).nullable()
    val startTime = varchar("start_time", 20).nullable()
    val endTime = varchar("end_time", 20).nullable()
    val durationMinutes = integer("duration_minutes").nullable()
    val status = varchar("status", 40).nullable()
    val instructions = text("instructions").nullable()
    val remarks = text("remarks").nullable()
    override val primaryKey = PrimaryKey(id)
}

object TimetableEntries : Table("timetable_entries") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val semesterId = long("semester_id").references(Semesters.id)
    val dayOfWeek = varchar("day_of_week", 20)
    val unitId = long("unit_id").references(Units.id).nullable()
    val startTime = varchar("start_time", 20).nullable()
    val endTime = varchar("end_time", 20).nullable()
    val venue = varchar("venue", 200).nullable()
    val lecturer = varchar("lecturer", 200).nullable()
    val recurrence = varchar("recurrence", 40).nullable()
    val remarks = text("remarks").nullable()
    override val primaryKey = PrimaryKey(id)
}

object PerformanceRecords : Table("performance_records") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val semesterId = long("semester_id").references(Semesters.id)
    val unitId = long("unit_id").references(Units.id).nullable()
    val assessmentName = varchar("assessment_name", 300)
    val assessmentType = varchar("assessment_type", 60).nullable()
    val marks = varchar("marks", 40).nullable()
    val maximumMarks = varchar("maximum_marks", 40).nullable()
    val expectedMarks = varchar("expected_marks", 40).nullable()
    val targetGrade = varchar("target_grade", 20).nullable()
    val assessmentDate = varchar("assessment_date", 40).nullable()
    val lecturerComment = text("lecturer_comment").nullable()
    val studentComment = text("student_comment").nullable()
    val remarks = text("remarks").nullable()
    override val primaryKey = PrimaryKey(id)
}

object StudyPlans : Table("study_plans") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val semesterId = long("semester_id").references(Semesters.id)
    val title = varchar("title", 300)
    val topic = varchar("topic", 300).nullable()
    val plannedDate = varchar("planned_date", 40).nullable()
    val startTime = varchar("start_time", 20).nullable()
    val endTime = varchar("end_time", 20).nullable()
    val durationMinutes = integer("duration_minutes").nullable()
    val status = varchar("status", 40).nullable()
    val unitId = long("unit_id").references(Units.id).nullable()
    val notes = text("notes").nullable()
    override val primaryKey = PrimaryKey(id)
}

object FriendRequests : Table("friend_requests") {
    val id = long("id").autoIncrement()
    val senderId = long("sender_id").references(Users.id)
    val recipientId = long("recipient_id").references(Users.id)
    val status = varchar("status", 20)
    val createdAt = long("created_at")
    val decidedAt = long("decided_at").nullable()
    override val primaryKey = PrimaryKey(id)
    init { index("idx_fr_recipient", false, recipientId, status) }
}

object Shares : Table("shares") {
    val id = long("id").autoIncrement()
    val senderId = long("sender_id").references(Users.id)
    val recipientId = long("recipient_id").references(Users.id)
    val resourceType = varchar("resource_type", 20)
    val resourceId = long("resource_id")
    val status = varchar("status", 20)
    val destinationType = varchar("destination_type", 20).nullable()
    val destinationUnitId = long("destination_unit_id").nullable()
    val createdAt = long("created_at")
    val decidedAt = long("decided_at").nullable()
    override val primaryKey = PrimaryKey(id)
    init { index("idx_shares_recipient", false, recipientId, status) }
}

/** Employer verification requests routed to the university registrar. */
object VerificationRequests : Table("verification_requests") {
    val id = long("id").autoIncrement()
    val employerName = varchar("employer_name", 200)
    val employerOrganisation = varchar("employer_organisation", 300)
    val employerEmail = varchar("employer_email", 320)
    val credentialKey = varchar("credential_key", 300)
    val universityId = long("university_id").references(Universities.id)
    val credentialIdFk = long("credential_id_fk").references(Credentials.id).nullable()
    val requestToken = varchar("request_token", 64).uniqueIndex()
    val status = varchar("status", 20)
    val releasedFieldsJson = text("released_fields_json").nullable()
    val decidedAt = long("decided_at").nullable()
    val decidedBy = long("decided_by").references(Users.id).nullable()
    val decisionNote = text("decision_note").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
    init { index("idx_vr_uni_status", false, universityId, status) }
}

object VerificationGrants : Table("verification_grants") {
    val id = long("id").autoIncrement()
    val requestId = long("request_id").references(VerificationRequests.id)
    val tokenHash = varchar("token_hash", 128).uniqueIndex()
    val expiresAt = long("expires_at")
    val revokedAt = long("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object ChainAnchors : Table("chain_anchors") {
    val id = long("id").autoIncrement()
    val digest = varchar("digest", 64).uniqueIndex()
    val txHash = varchar("tx_hash", 120)
    val blockNumber = long("block_number").nullable()
    val anchorType = varchar("anchor_type", 20)
    val network = varchar("network", 60)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object IpfsPins : Table("ipfs_pins") {
    val id = long("id").autoIncrement()
    val cid = varchar("cid", 120).uniqueIndex()
    val name = varchar("name", 300)
    val size = long("size")
    val kind = varchar("kind", 40)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object AuditLogs : Table("audit_logs") {
    val id = long("id").autoIncrement()
    val actorId = long("actor_id").nullable()
    val actorRole = varchar("actor_role", 32)
    val actorEmail = varchar("actor_email", 320).nullable()
    val action = varchar("action", 120)
    val entityType = varchar("entity_type", 60).nullable()
    val entityId = varchar("entity_id", 64).nullable()
    val detail = text("detail").nullable()
    val status = varchar("status", 20)
    val errorMessage = text("error_message").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Detached, transaction-safe snapshot of an Exposed row for service boundaries. */
class RowSnapshot internal constructor(private val values: Map<String, Any?>) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(column: Column<T>): T = values[column.name] as T

    @Suppress("UNCHECKED_CAST")
    fun <T> getOrNull(column: Column<T>): T? = values[column.name] as T?
}

/** Materialize every selected column while the Exposed transaction is active. */
fun ResultRow.snapshot(table: Table): RowSnapshot =
    RowSnapshot(table.columns.associate { column -> column.name to this.getOrNull(column) })

object Settings : Table("settings") {
    val id = long("id").autoIncrement()
    val key = varchar("key", 80).uniqueIndex()
    val value = text("value")
    override val primaryKey = PrimaryKey(id)
}

/** Simple key/value settings accessors. */
object SettingsStore {
    fun get(key: String, default: String = ""): String = transaction {
        Settings.selectAll().where { Settings.key eq key }.limit(1).singleOrNull()
            ?.get(Settings.value) ?: default
    }

    fun set(key: String, value: String) = transaction {
        val row = Settings.selectAll().where { Settings.key eq key }.limit(1).singleOrNull()
        if (row == null) {
            Settings.insert { it[Settings.key] = key; it[Settings.value] = value }
        } else {
            Settings.update({ Settings.key eq key }) { it[Settings.value] = value }
        }
    }
}

/** Append-only audit trail for administrative and credential actions. */
object Audit {
    fun log(
        actorId: Long? = null,
        actorRole: String = "system",
        actorEmail: String? = null,
        action: String,
        entityType: String? = null,
        entityId: String? = null,
        detail: String? = null,
        status: String = "ok",
        errorMessage: String? = null
    ) = transaction {
        AuditLogs.insert {
            it[AuditLogs.actorId] = actorId
            it[AuditLogs.actorRole] = actorRole
            it[AuditLogs.actorEmail] = actorEmail
            it[AuditLogs.action] = action
            it[AuditLogs.entityType] = entityType
            it[AuditLogs.entityId] = entityId
            it[AuditLogs.detail] = detail
            it[AuditLogs.status] = status
            it[AuditLogs.errorMessage] = errorMessage
            it[AuditLogs.createdAt] = System.currentTimeMillis()
        }
    }
}
