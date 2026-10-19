package com.studentcommandcenter.server

import kotlinx.serialization.Serializable

// ---------- enums ----------

enum class UserRole { student, registrar, system_admin }
enum class UserStatus { active, suspended }
enum class CredentialStatus { active, revoked, superseded }
enum class AnchorStatus { pending, anchored, failed, local_only }
enum class VerificationRequestStatus { pending, approved, rejected }
enum class ContainerKind { semester, event }
enum class ResourceType { note, file }
enum class FriendRequestStatus { pending, accepted, declined }
enum class ShareStatus { pending, accepted, declined }

// ---------- auth / user DTOs ----------

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String? = null,
    val gender: String? = null,
    val universityId: Long? = null,
    val registrationNumber: String? = null
)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(val user: UserDto, val token: String)

@Serializable
data class UserDto(
    val id: Long,
    val email: String,
    val displayName: String?,
    val gender: String?,
    val role: String,
    val universityId: Long?,
    val universityName: String?,
    val registrationNumber: String?,
    val status: String,
    val publicId: String,
    val createdAt: Long
)

@Serializable
data class UpdateProfileRequest(val displayName: String?, val gender: String?)

// ---------- directory ----------

@Serializable
data class UniversityDto(val id: Long, val name: String, val tagline: String?, val details: String?, val logoUrl: String?, val schoolCount: Int, val createdAt: Long)

@Serializable
data class SchoolDto(val id: Long, val universityId: Long, val name: String, val prefix: String)

@Serializable
data class CreateUniversityRequest(val name: String, val tagline: String? = null, val details: String? = null, val logoUrl: String? = null)

@Serializable
data class CreateSchoolRequest(val name: String, val prefix: String)

// ---------- credential workspace ----------

@Serializable
data class ImportResult(val accepted: Int, val skipped: Int, val committed: Boolean)

@Serializable
data class IssueCredentialRequest(
    val studentRecordId: Long,
    val title: String,
    val credentialType: String = "AcademicCredential",
    val awardDate: String? = null,
    val classification: String? = null,
    val additionalClaims: Map<String, String> = emptyMap()
)

@Serializable
data class CredentialDto(
    val id: Long,
    val credentialId: String,
    val universityId: Long,
    val universityName: String,
    val studentRecordId: Long?,
    val studentName: String?,
    val studentEmail: String?,
    val registrationNumber: String?,
    val programme: String?,
    val title: String,
    val credentialType: String,
    val awardDate: String?,
    val classification: String?,
    val claims: Map<String, String>,
    val digest: String,
    val status: String,
    val ipfsCid: String?,
    val anchorStatus: String,
    val anchorTxHash: String?,
    val issuedAt: Long,
    val revokedAt: Long?,
    val revokeReason: String?,
    val supersededByCredentialId: String?,
    val issuerName: String,
    val issuerDid: String,
    val keyId: String?
)

@Serializable
data class CredentialEventDto(
    val id: Long,
    val credentialId: Long,
    val type: String,
    val detail: String,
    val createdAt: Long
)

@Serializable
data class CredentialReissueResponse(val id: Long, val credential: CredentialDto)

/** Public, non-sensitive status payload for verifiers. */
@Serializable
data class CredentialStatusDto(
    val credentialId: String,
    val issuerName: String,
    val issuerDid: String,
    val title: String,
    val credentialType: String,
    val digest: String,
    val status: String,
    val issuedAt: Long,
    val revokedAt: Long?,
    val revokeReason: String?,
    val supersededByCredentialId: String?,
    val keyId: String?,
    val issuerPublicKeyPem: String?,
    val ipfsCid: String?,
    val ipfsGatewayUrl: String?,
    val onChain: OnChainInfo?
)

@Serializable
data class OnChainInfo(
    val network: String,
    val contractAddress: String,
    val txHash: String?,
    val anchoredAt: Long?,
    val chainStatus: String
)

@Serializable
data class RevokeRequest(val reason: String)

@Serializable
data class ReissueRequest(
    val title: String? = null,
    val credentialType: String? = null,
    val awardDate: String? = null,
    val classification: String? = null,
    val additionalClaims: Map<String, String>? = null
)

@Serializable
data class ImportStudentsRequest(val csvText: String, val sourceName: String? = null, val commit: Boolean = false)

@Serializable
data class ImportPreview(val rows: Int, val sample: List<Map<String, String>>)

@Serializable
data class RegisterExternalKeyRequest(val keyId: String, val publicKeyPem: String, val externalKeyRef: String? = null)

@Serializable
data class UniversityCredentialWorkspace(
    val university: UniversityDto,
    val importedStudents: List<ImportedStudentDto>,
    val credentials: List<CredentialDto>,
    val keys: List<SigningKeyDto>
)

@Serializable
data class ImportedStudentDto(
    val id: Long,
    val universityId: Long,
    val externalId: String?,
    val registrationNumber: String?,
    val name: String,
    val email: String,
    val programme: String?,
    val importedAt: Long
)

@Serializable
data class SigningKeyDto(val id: Long, val universityId: Long, val keyId: String, val publicKeyPem: String, val isInternal: Boolean, val externalRef: String?, val createdAt: Long)

// ---------- employer verification workflow ----------

@Serializable
data class SubmitVerificationRequest(
    val employerName: String,
    val employerOrganisation: String,
    val employerEmail: String,
    val credentialKey: String,
    val universityId: Long
)

@Serializable
data class VerificationRequestDto(
    val id: Long,
    val employerName: String,
    val employerOrganisation: String,
    val employerEmail: String,
    val credentialKey: String,
    val universityId: Long,
    val universityName: String,
    val matchedCredentialId: String?,
    val matchedStudentName: String?,
    val status: String,
    val createdAt: Long,
    val decidedAt: Long?,
    val decidedBy: Long?,
    val decisionNote: String?,
    val trackingCode: String? = null
)

@Serializable
data class DecideVerificationRequest(val approve: Boolean, val releasedFields: List<String>? = null, val note: String? = null)

@Serializable
data class VerificationResultDto(
    val requestId: Long,
    val status: String,
    val employerName: String,
    val employerOrganisation: String,
    val decidedAt: Long?,
    val released: ReleasedRecordDto?
)

@Serializable
data class ReleasedRecordDto(
    val credentialId: String,
    val issuerName: String,
    val issuerDid: String,
    val title: String,
    val credentialType: String,
    val awardDate: String?,
    val classification: String?,
    val studentName: String?,
    val registrationNumber: String?,
    val programme: String?,
    val issuedAt: Long,
    val status: String,
    val digest: String,
    val ipfsCid: String?,
    val ipfsGatewayUrl: String?,
    val onChain: OnChainInfo?
)

// ---------- student academic workspace ----------

@Serializable
data class AcademicYearDto(val id: Long, val name: String, val startDate: String?, val endDate: String?, val archived: Boolean, val semesters: List<SemesterDto>)

@Serializable
data class SemesterDto(val id: Long, val academicYearId: Long, val name: String, val startDate: String?, val endDate: String?, val isCurrent: Boolean, val archived: Boolean)

@Serializable
data class UnitDto(val id: Long, val semesterId: Long, val name: String, val code: String?, val lecturer: String?, val credits: String?, val description: String?, val remarks: String?)

@Serializable
data class NoteDto(val id: Long, val semesterId: Long?, val folderId: Long?, val unitId: Long?, val title: String, val content: String, val scopeType: String, val format: String, val tags: List<String>, val pinned: Boolean, val createdAt: Long, val updatedAt: Long)

@Serializable
data class FolderDto(val id: Long, val semesterId: Long, val parentFolderId: Long?, val unitId: Long?, val name: String, val description: String?)

@Serializable
data class FileDto(
    val id: Long,
    val name: String,
    val size: Long,
    val mime: String,
    val sha256: String,
    val ipfsCid: String?,
    val ipfsGatewayUrl: String?,
    val semesterId: Long?,
    val unitId: Long?,
    val folderId: Long?,
    val containerId: Long?,
    val clientUploadId: String?,
    val uploadedAt: Long
)

@Serializable
data class TaskDto(val id: Long, val semesterId: Long, val title: String, val description: String?, val unitId: Long?, val createdDate: String?, val deadline: String?, val priority: String?, val status: String?, val completed: Boolean, val category: String?, val remarks: String?)

@Serializable
data class ExamSessionDto(val id: Long, val semesterId: Long, val name: String, val description: String?, val remarks: String?)

@Serializable
data class ExamDto(val id: Long, val semesterId: Long, val name: String, val examSessionId: Long?, val unitId: Long?, val venue: String?, val examDate: String?, val startTime: String?, val endTime: String?, val durationMinutes: Int?, val status: String?, val instructions: String?, val remarks: String?)

@Serializable
data class TimetableEntryDto(val id: Long, val semesterId: Long, val dayOfWeek: String, val unitId: Long?, val startTime: String?, val endTime: String?, val venue: String?, val lecturer: String?, val recurrence: String?, val remarks: String?)

@Serializable
data class PerformanceRecordDto(val id: Long, val semesterId: Long, val unitId: Long?, val assessmentName: String, val assessmentType: String?, val marks: String?, val maximumMarks: String?, val expectedMarks: String?, val targetGrade: String?, val assessmentDate: String?, val lecturerComment: String?, val studentComment: String?, val remarks: String?)

@Serializable
data class StudyPlanDto(val id: Long, val semesterId: Long, val title: String, val topic: String?, val plannedDate: String?, val startTime: String?, val endTime: String?, val durationMinutes: Int?, val status: String?, val unitId: Long?, val notes: String?)

// ---------- semester / event container module ----------

@Serializable
data class CreateContainerRequest(
    val kind: String, // "semester" | "event"
    val name: String,
    val description: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val semesterId: Long? = null
)

@Serializable
data class ContainerDto(
    val id: Long,
    val userId: Long,
    val kind: String,
    val name: String,
    val description: String?,
    val startDate: String?,
    val endDate: String?,
    val semesterId: Long?,
    val fileCount: Int,
    val totalBytes: Long,
    val manifestCid: String?,
    val manifestDigest: String?,
    val anchored: Boolean,
    val anchorTxHash: String?,
    val ipfsGatewayUrl: String?,
    val createdAt: Long
)

@Serializable
data class ContainerFileDto(
    val id: Long,
    val containerId: Long,
    val name: String,
    val size: Long,
    val mime: String,
    val sha256: String,
    val ipfsCid: String?,
    val ipfsGatewayUrl: String?,
    val note: String?,
    val uploadedAt: Long
)

@Serializable
data class ContainerAnchorResult(
    val containerId: Long,
    val manifestCid: String,
    val manifestDigest: String,
    val anchorTxHash: String?,
    val anchorStatus: String
)

// ---------- friends / shares ----------

@Serializable
data class FriendDto(val id: Long, val email: String, val displayName: String?, val publicId: String, val universityName: String?)

@Serializable
data class FriendRequestDto(val id: Long, val senderId: Long, val senderName: String, val senderPublicId: String, val recipientId: Long, val status: String, val createdAt: Long, val decidedAt: Long?)

@Serializable
data class ShareDto(
    val id: Long,
    val senderId: Long,
    val senderName: String,
    val recipientId: Long,
    val resourceType: String,
    val resourceId: Long,
    val resourceTitle: String,
    val status: String,
    val createdAt: Long,
    val decidedAt: Long?
)

// ---------- admin ----------

@Serializable
data class AdminOverviewDto(
    val students: Int,
    val registrars: Int,
    val universities: Int,
    val schools: Int,
    val credentials: Int,
    val activeCredentials: Int,
    val importedStudents: Int,
    val verificationRequests: Int,
    val pendingVerificationRequests: Int,
    val files: Int,
    val containers: Int,
    val chainConfigured: Boolean,
    val ipfsConfigured: Boolean,
    val chainNetwork: String
)

@Serializable
data class AuditEventDto(
    val id: Long,
    val actorEmail: String?,
    val actorRole: String,
    val action: String,
    val entityType: String?,
    val entityId: String?,
    val detail: String?,
    val status: String,
    val errorMessage: String?,
    val createdAt: Long
)

@Serializable
data class UploadMetadataDto(
    val id: Long,
    val ownerEmail: String?,
    val name: String,
    val size: Long,
    val mime: String,
    val ipfsCid: String?,
    val uploadedAt: Long
)

@Serializable
data class SettingsDto(val logoUrl: String?, val signInTheme: String, val adminTheme: String, val headerTitle: String)

@Serializable
data class UpdateSettingsRequest(val logoUrl: String?, val signInTheme: String?, val adminTheme: String?, val headerTitle: String?)

@Serializable
data class CreateRegistrarRequest(val email: String, val password: String, val displayName: String?, val universityId: Long)

@Serializable
data class ChainStatusDto(
    val configured: Boolean,
    val network: String,
    val contractAddress: String?,
    val ipfsConfigured: Boolean,
    val ipfsApiUrl: String,
    val ipfsGatewayUrl: String
)

@Serializable
data class AnchorResultDto(val digest: String, val txHash: String?, val status: String)

@Serializable
data class WalletCredentialDto(
    val credentialId: String,
    val issuerName: String,
    val issuerDid: String,
    val title: String,
    val credentialType: String,
    val awardDate: String?,
    val classification: String?,
    val issuedAt: Long,
    val status: String,
    val digest: String,
    val ipfsCid: String?,
    val ipfsGatewayUrl: String?,
    val anchorStatus: String,
    val anchorTxHash: String?,
    val signedJwt: String
)

@Serializable
data class DashboardDto(
    val userId: Long,
    val displayName: String?,
    val currentSemester: SemesterDto?,
    val yearCount: Int,
    val semesterCount: Int,
    val unitCount: Int,
    val noteCount: Int,
    val taskCount: Int,
    val openTaskCount: Int,
    val examCount: Int,
    val fileCount: Int,
    val containerCount: Int,
    val credentialCount: Int,
    val pendingOutbox: Int
)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class OkResponse(val ok: Boolean = true)

/** Domain error carrying an HTTP status for the API layer. */
class AppError(val status: Int, message: String) : RuntimeException(message)

fun error(status: Int, message: String): Nothing = throw AppError(status, message)

/** Authenticated account (DB row projection shared by services). */
data class UserRecord(
    val id: Long,
    val email: String,
    val displayName: String?,
    val gender: String?,
    val role: UserRole,
    val universityId: Long?,
    val registrationNumber: String?,
    val status: UserStatus,
    val publicId: String,
    val createdAt: Long
)
