package com.studentcommandcenter.server

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

private data class ReceivedUpload(val fileName: String, val bytes: ByteArray, val mime: String, val fields: Map<String, String>)

fun Application.configureApiRoutes(
    auth: AuthService,
    credentials: CredentialService,
    containers: ContainersService,
    verification: VerificationService,
    chain: BlockchainAnchor,
    academic: AcademicService
) {
    routing {
        get("/api/health") {
            call.respond(buildJsonObject {
                put("status", "ok")
                put("database", if (Config.databaseUrl.startsWith("jdbc:sqlite:")) "sqlite" else "configured")
                put("ipfsConfigured", Config.ipfsEnabled)
                put("polygonConfigured", chain.configured)
                put("network", Config.chainNetworkName)
                put("time", System.currentTimeMillis())
            })
        }
        get("/api/v1") {
            call.respond(mapOf(
                "name" to "SCC Anchored API",
                "version" to "1.0",
                "openApi" to "GET /api/v1/openapi",
                "adminWeb" to "/admin",
                "employerPortal" to "/verify"
            ))
        }
        get("/api/v1/openapi") {
            call.respond(buildJsonObject {
                put("title", "SCC Anchored API")
                put("version", "1.0.0")
                put("authentication", "Bearer token from POST /api/v1/auth/login")
                put("routes", kotlinx.serialization.json.buildJsonArray {
                    listOf(
                        "POST /api/v1/auth/register", "POST /api/v1/auth/login", "GET /api/v1/me",
                        "GET/POST /api/v1/containers", "POST /api/v1/containers/{id}/files", "POST /api/v1/containers/{id}/anchor",
                        "GET/POST /api/v1/academic-years", "POST /api/v1/academic-years/{id}/semesters",
                        "GET /api/v1/me/credentials", "GET /api/v1/public/credentials/{id}/status",
                        "POST /api/v1/public/verification-requests", "GET /api/v1/public/verification-requests/{trackingCode}",
                        "GET/POST /api/v1/admin/universities", "POST /api/v1/admin/universities/{id}/students/import",
                        "POST /api/v1/admin/universities/{id}/credentials", "GET /api/v1/admin/verification-requests"
                    ).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
            })
        }

        route("/api/v1") {
            // ---------------- auth / profile ----------------
            route("/auth") {
                post("/register") {
                    val body = call.receive<RegisterRequest>()
                    call.respond(HttpStatusCode.Created, auth.registerStudent(body))
                }
                post("/login") {
                    val body = call.receive<LoginRequest>()
                    call.respond(auth.login(body.email, body.password))
                }
                post("/logout") {
                    auth.logout(call.bearerToken())
                    call.respond(OkResponse())
                }
            }
            get("/me") {
                val user = call.requireUser(auth)
                call.respond(auth.toDto(user))
            }
            patch("/me") {
                val user = call.requireUser(auth)
                val body = call.receive<UpdateProfileRequest>()
                call.respond(auth.updateProfile(user, body.displayName, body.gender))
            }
            get("/me/credentials") {
                val user = call.requireRole(auth, UserRole.student)
                call.respond(credentials.walletCredentialDetails(user))
            }

            // ---------------- public directory / credentials ----------------
            route("/public") {
                get("/universities") { call.respond(universityList()) }
                get("/universities/{id}/schools") {
                    val universityId = call.longPath("id")
                    call.respond(transaction {
                        Schools.selectAll().where { Schools.universityId eq universityId }
                            .orderBy(Schools.name to org.jetbrains.exposed.sql.SortOrder.ASC).map {
                                SchoolDto(it[Schools.id], it[Schools.universityId], it[Schools.name], it[Schools.prefix])
                            }
                    })
                }
                get("/credentials/{credentialId}/status") {
                    val credentialId = call.parameters["credentialId"] ?: error(400, "credentialId is required")
                    call.respond(credentials.publicStatus(credentialId))
                }
                get("/chain-status") {
                    call.respond(ChainStatusDto(
                        configured = chain.configured,
                        network = Config.chainNetworkName,
                        contractAddress = Config.anchorContractAddress,
                        ipfsConfigured = Config.ipfsEnabled,
                        ipfsApiUrl = "configured privately",
                        ipfsGatewayUrl = Config.ipfsGatewayUrl
                    ))
                }
                post("/verification-requests") {
                    val body = call.receive<SubmitVerificationRequest>()
                    val request = verification.submitRequest(
                        body.employerName, body.employerOrganisation, body.employerEmail,
                        body.credentialKey, body.universityId
                    )
                    call.respond(HttpStatusCode.Created, request)
                }
                get("/verification-requests/{trackingCode}") {
                    val code = call.parameters["trackingCode"] ?: error(400, "tracking code is required")
                    val result = verification.resultForTrackingCode(code) ?: error(404, "request not found or expired")
                    call.respond(result)
                }
            }

            // ---------------- university and registrar administration ----------------
            route("/admin") {
                get("/overview") {
                    call.requireRole(auth, UserRole.system_admin)
                    call.respond(adminOverview(chain))
                }
                get("/universities") {
                    val user = call.requireAdminOrRegistrar(auth)
                    val list = if (user.role == UserRole.registrar && user.universityId != null) {
                        listOfNotNull(universityById(user.universityId))
                    } else universityList()
                    call.respond(list)
                }
                post("/universities") {
                    val actor = call.requireRole(auth, UserRole.system_admin)
                    val body = call.receive<CreateUniversityRequest>()
                    require(body.name.isNotBlank() && body.name.length <= 200) { "university name is required" }
                    val id = transaction {
                        Universities.insert {
                            it[Universities.name] = body.name.trim()
                            it[Universities.tagline] = body.tagline?.take(300)
                            it[Universities.details] = body.details?.take(5000)
                            it[Universities.logoUrl] = body.logoUrl?.take(500)
                            it[Universities.createdAt] = System.currentTimeMillis()
                        }[Universities.id]
                    }
                    Audit.log(actorId = actor.id, actorRole = actor.role.name, actorEmail = actor.email,
                        action = "admin.university.created", entityType = "university", entityId = id.toString())
                    call.respond(HttpStatusCode.Created, universityById(id)!!)
                }
                post("/universities/{universityId}/schools") {
                    val actor = call.requireAdminOrRegistrar(auth)
                    val universityId = call.longPath("universityId")
                    call.requireUniversityAccess(auth, universityId)
                    val body = call.receive<CreateSchoolRequest>()
                    require(body.name.isNotBlank() && body.name.length <= 200) { "school name is required" }
                    require(body.prefix.matches(Regex("^[A-Za-z0-9]{1,12}$"))) { "prefix must be 1-12 letters or digits" }
                    val id = transaction {
                        require(Universities.selectAll().where { Universities.id eq universityId }.count() > 0L) { "university not found" }
                        val duplicate = Schools.selectAll().where {
                            (Schools.universityId eq universityId) and (Schools.prefix eq body.prefix.uppercase())
                        }.count() > 0L
                        require(!duplicate) { "school prefix already exists in this university" }
                        Schools.insert {
                            it[Schools.universityId] = universityId
                            it[Schools.name] = body.name.trim()
                            it[Schools.prefix] = body.prefix.uppercase()
                            it[Schools.createdAt] = System.currentTimeMillis()
                        }[Schools.id]
                    }
                    Audit.log(actorId = actor.id, actorRole = actor.role.name, actorEmail = actor.email,
                        action = "admin.school.created", entityType = "school", entityId = id.toString())
                    call.respond(HttpStatusCode.Created, SchoolDto(id, universityId, body.name.trim(), body.prefix.uppercase()))
                }
                post("/universities/{universityId}/registrars") {
                    val admin = call.requireRole(auth, UserRole.system_admin)
                    val universityId = call.longPath("universityId")
                    val body = call.receive<CreateRegistrarRequest>()
                    call.respond(HttpStatusCode.Created, auth.createRegistrar(admin, body.email, body.password, body.displayName, universityId))
                }
                get("/universities/{universityId}/credentials") {
                    val actor = call.requireAdminOrRegistrar(auth)
                    val universityId = call.longPath("universityId")
                    call.requireUniversityAccess(auth, universityId)
                    call.respond(credentials.universityWorkspace(universityId))
                }
                post("/universities/{universityId}/students/import") {
                    val actor = call.requireAdminOrRegistrar(auth)
                    val universityId = call.longPath("universityId")
                    call.requireUniversityAccess(auth, universityId)
                    val body = call.receive<ImportStudentsRequest>()
                    val result = credentials.importStudents(actor.id, universityId, body.csvText, body.sourceName, body.commit)
                    call.respond(if (body.commit) HttpStatusCode.Created else HttpStatusCode.OK, result)
                }
                post("/universities/{universityId}/credential-keys/external") {
                    val actor = call.requireAdminOrRegistrar(auth)
                    val universityId = call.longPath("universityId")
                    call.requireUniversityAccess(auth, universityId)
                    val body = call.receive<RegisterExternalKeyRequest>()
                    val id = credentials.registerExternalKey(universityId, body.keyId, body.publicKeyPem, body.externalKeyRef)
                    Audit.log(actorId = actor.id, actorRole = actor.role.name, actorEmail = actor.email,
                        action = "credential.externalKey.registered", entityType = "credentialKey", entityId = id.toString())
                    call.respond(HttpStatusCode.Created, mapOf("id" to id))
                }
                post("/universities/{universityId}/credentials") {
                    val actor = call.requireAdminOrRegistrar(auth)
                    val universityId = call.longPath("universityId")
                    call.requireUniversityAccess(auth, universityId)
                    val body = call.receive<IssueCredentialRequest>()
                    val id = credentials.issueCredential(
                        actor.id, universityId, body.studentRecordId, body.title, body.credentialType,
                        body.awardDate, body.classification, body.additionalClaims
                    )
                    call.respond(HttpStatusCode.Created, credentials.credentialDto(id))
                }
                post("/credentials/{id}/revoke") {
                    val actor = call.requireAdminOrRegistrar(auth)
                    val id = call.longPath("id")
                    val row = credentials.credentialRowById(id)
                    call.requireUniversityAccess(auth, row[Credentials.universityId])
                    val body = call.receive<RevokeRequest>()
                    credentials.revokeCredential(actor.id, id, body.reason)
                    call.respond(OkResponse())
                }
                post("/credentials/{id}/reissue") {
                    val actor = call.requireAdminOrRegistrar(auth)
                    val id = call.longPath("id")
                    val row = credentials.credentialRowById(id)
                    call.requireUniversityAccess(auth, row[Credentials.universityId])
                    val body = call.receive<ReissueRequest>()
                    val newId = credentials.reissueCredential(actor.id, id, body.title, body.credentialType,
                        body.awardDate, body.classification, body.additionalClaims)
                    call.respond(HttpStatusCode.Created, CredentialReissueResponse(newId, credentials.credentialDto(newId)))
                }
                get("/credentials/{id}/history") {
                    val actor = call.requireAdminOrRegistrar(auth)
                    val id = call.longPath("id")
                    val row = credentials.credentialRowById(id)
                    call.requireUniversityAccess(auth, row[Credentials.universityId])
                    call.respond(credentials.credentialEvents(id))
                }
                get("/verification-requests") {
                    val user = call.requireAdminOrRegistrar(auth)
                    val universityId = if (user.role == UserRole.registrar) user.universityId!!
                    else call.request.queryParameters["universityId"]?.toLongOrNull()
                        ?: error(400, "system admin must specify universityId")
                    call.requireUniversityAccess(auth, universityId)
                    call.respond(verification.requestsForRegistrar(universityId))
                }
                post("/verification-requests/{id}/decision") {
                    val registrar = call.requireAdminOrRegistrar(auth)
                    val id = call.longPath("id")
                    val body = call.receive<DecideVerificationRequest>()
                    val updated = verification.decide(registrar, id, body.approve, body.releasedFields, body.note)
                    call.respond(updated)
                }
                get("/accounts") {
                    call.requireRole(auth, UserRole.system_admin)
                    val q = call.request.queryParameters["q"]?.trim()?.lowercase().orEmpty()
                    val users = transaction {
                        Users.selectAll().orderBy(Users.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC).limit(500).map { row ->
                            UserRecord(row[Users.id], row[Users.email], row[Users.displayName], row[Users.gender],
                                UserRole.valueOf(row[Users.role]), row[Users.universityId], row[Users.registrationNumber],
                                UserStatus.valueOf(row[Users.status]), row[Users.publicId], row[Users.createdAt])
                        }
                    }.filter { q.isBlank() || it.email.contains(q, true) || (it.displayName?.contains(q, true) == true) }
                        .map { auth.toDto(it) }
                    call.respond(users)
                }
                patch("/accounts/{id}/status") {
                    val admin = call.requireRole(auth, UserRole.system_admin)
                    val targetId = call.longPath("id")
                    val body = call.receive<JsonObject>()
                    val status = body.string("status") ?: error(400, "status is required")
                    call.respond(auth.setAccountStatus(admin, targetId, status))
                }
                get("/audit-logs") {
                    call.requireRole(auth, UserRole.system_admin)
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
                    val rows = transaction {
                        AuditLogs.selectAll().orderBy(AuditLogs.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                            .limit(limit).map {
                                AuditEventDto(it[AuditLogs.id], it[AuditLogs.actorEmail], it[AuditLogs.actorRole],
                                    it[AuditLogs.action], it[AuditLogs.entityType], it[AuditLogs.entityId], it[AuditLogs.detail],
                                    it[AuditLogs.status], it[AuditLogs.errorMessage], it[AuditLogs.createdAt])
                            }
                    }
                    call.respond(rows)
                }
                get("/uploads") {
                    call.requireRole(auth, UserRole.system_admin)
                    val rows = transaction {
                        ContainerFiles.selectAll().orderBy(ContainerFiles.uploadedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                            .limit(500).map { file ->
                                val container = Containers.selectAll().where { Containers.id eq file[ContainerFiles.containerId] }.limit(1).singleOrNull()
                                val owner = container?.let { row -> Users.selectAll().where { Users.id eq row[Containers.userId] }.limit(1).singleOrNull() }
                                UploadMetadataDto(file[ContainerFiles.id], owner?.get(Users.email), file[ContainerFiles.name],
                                    file[ContainerFiles.size], file[ContainerFiles.mime], file[ContainerFiles.ipfsCid], file[ContainerFiles.uploadedAt])
                            }
                    }
                    call.respond(rows)
                }
                get("/chain-status") {
                    call.requireRole(auth, UserRole.system_admin)
                    call.respond(ChainStatusDto(chain.configured, Config.chainNetworkName, Config.anchorContractAddress,
                        Config.ipfsEnabled, Config.ipfsApiUrl, Config.ipfsGatewayUrl))
                }
            }

            // ---------------- academic year / semester / unit workspace ----------------
            get("/dashboard") {
                val user = call.requireRole(auth, UserRole.student)
                call.respond(studentDashboard(user))
            }
            get("/academic-years") {
                val user = call.requireRole(auth, UserRole.student)
                call.respond(academic.years(user.id))
            }
            post("/academic-years") {
                val user = call.requireRole(auth, UserRole.student)
                val body = call.receive<JsonObject>()
                call.respond(HttpStatusCode.Created, academic.createYear(user.id,
                    body.string("name") ?: error(400, "name is required"), body.string("startDate"), body.string("endDate")))
            }
            post("/academic-years/{yearId}/semesters") {
                val user = call.requireRole(auth, UserRole.student)
                val yearId = call.longPath("yearId")
                val body = call.receive<JsonObject>()
                call.respond(HttpStatusCode.Created, academic.createSemester(user.id, yearId,
                    body.string("name") ?: error(400, "name is required"), body.string("startDate"), body.string("endDate")))
            }
            post("/semesters/{id}/current") {
                val user = call.requireRole(auth, UserRole.student)
                call.respond(academic.setCurrent(user.id, call.longPath("id")))
            }
            get("/units") {
                val user = call.requireRole(auth, UserRole.student)
                call.respond(academic.units(user.id, call.request.queryParameters["semesterId"]?.toLongOrNull()))
            }
            post("/semesters/{semesterId}/units") {
                val user = call.requireRole(auth, UserRole.student)
                val semesterId = call.longPath("semesterId")
                val body = call.receive<JsonObject>()
                call.respond(HttpStatusCode.Created, academic.createUnit(user.id, semesterId,
                    body.string("name") ?: error(400, "name is required"), body.string("code"), body.string("lecturer"),
                    body.string("credits"), body.string("description")))
            }

            // ---------------- student semester/event containers ----------------
            route("/containers") {
                get {
                    val user = call.requireRole(auth, UserRole.student)
                    call.respond(containers.listContainers(user.id, call.request.queryParameters["kind"]))
                }
                post {
                    val user = call.requireRole(auth, UserRole.student)
                    val body = call.receive<CreateContainerRequest>()
                    call.respond(HttpStatusCode.Created, containers.createContainer(user.id, body.kind, body.name,
                        body.description, body.startDate, body.endDate, body.semesterId))
                }
                get("/{id}") {
                    val user = call.requireRole(auth, UserRole.student)
                    call.respond(containers.getContainer(user.id, call.longPath("id")))
                }
                patch("/{id}") {
                    val user = call.requireRole(auth, UserRole.student)
                    val id = call.longPath("id")
                    val body = call.receive<JsonObject>()
                    call.respond(containers.updateContainer(user.id, id, body.string("name"), body.string("description"),
                        body.string("startDate"), body.string("endDate")))
                }
                delete("/{id}") {
                    val user = call.requireRole(auth, UserRole.student)
                    containers.deleteContainer(user.id, call.longPath("id"))
                    call.respond(OkResponse())
                }
                get("/{id}/files") {
                    val user = call.requireRole(auth, UserRole.student)
                    call.respond(containers.listContainerFiles(user.id, call.longPath("id")))
                }
                post("/{id}/files") {
                    val user = call.requireRole(auth, UserRole.student)
                    val id = call.longPath("id")
                    val multipart = call.receiveMultipart()
                    var fileName: String? = null
                    var mime = "application/octet-stream"
                    var bytes: ByteArray? = null
                    var note: String? = null
                    var pinToIpfs = true
                    multipart.forEachPart { part ->
                        try {
                            when (part) {
                                is PartData.FormItem -> when (part.name) {
                                    "note" -> note = part.value
                                    "pinToIpfs" -> pinToIpfs = part.value.toBooleanStrictOrNull() ?: true
                                }
                                is PartData.FileItem -> {
                                    fileName = part.originalFileName
                                    mime = part.headers[HttpHeaders.ContentType] ?: mime
                                    bytes = part.streamProvider().use { it.readBytes() }
                                }
                                else -> Unit
                            }
                        } finally { part.dispose() }
                    }
                    val content = bytes ?: error(400, "file field is required")
                    if (content.size.toLong() > Config.maxUploadBytes) error(413, "file exceeds upload limit")
                    val result = containers.importFile(user.id, id, fileName ?: "upload.bin", content, mime, note, pinToIpfs)
                    call.respond(HttpStatusCode.Created, result)
                }
                get("/{id}/files/{fileId}/download") {
                    val user = call.requireRole(auth, UserRole.student)
                    val containerId = call.longPath("id")
                    val fileId = call.longPath("fileId")
                    val (name, bytes) = containers.storagePathFor(user.id, containerId, fileId)
                    val safe = name.replace("\"", "_").replace("\\", "_").replace("\r", "_").replace("\n", "_")
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$safe\"")
                    call.respondBytes(bytes, ContentType.Application.OctetStream)
                }
                delete("/{id}/files/{fileId}") {
                    val user = call.requireRole(auth, UserRole.student)
                    containers.deleteContainerFile(user.id, call.longPath("id"), call.longPath("fileId"))
                    call.respond(OkResponse())
                }
                post("/{id}/anchor") {
                    val user = call.requireRole(auth, UserRole.student)
                    call.respond(containers.anchorContainer(user.id, call.longPath("id")))
                }
            }
        }
    }
}

private suspend fun ApplicationCall.requireUser(auth: AuthService): UserRecord {
    val token = bearerToken() ?: request.cookies["SCC_SESSION"]
    return auth.authenticate(token) ?: error(401, "authentication required")
}

private suspend fun ApplicationCall.requireRole(auth: AuthService, vararg roles: UserRole): UserRecord {
    val user = requireUser(auth)
    if (user.role !in roles) error(403, "insufficient role")
    return user
}

private suspend fun ApplicationCall.requireAdminOrRegistrar(auth: AuthService): UserRecord =
    requireRole(auth, UserRole.system_admin, UserRole.registrar)

private suspend fun ApplicationCall.requireUniversityAccess(auth: AuthService, universityId: Long) {
    val user = requireUser(auth)
    if (user.role == UserRole.system_admin) return
    if (user.role == UserRole.registrar && user.universityId == universityId) return
    error(403, "this dashboard is not assigned to that university")
}

private fun ApplicationCall.bearerToken(): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", true) }?.substringAfter(' ')?.trim()

private fun ApplicationCall.longPath(name: String): Long =
    parameters[name]?.toLongOrNull() ?: error(400, "$name must be a positive integer")

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.bool(name: String, default: Boolean = false): Boolean = this[name]?.jsonPrimitive?.booleanOrNull ?: default

private fun universityList(): List<UniversityDto> = transaction {
    Universities.selectAll().orderBy(Universities.name to org.jetbrains.exposed.sql.SortOrder.ASC).map { row ->
        val id = row[Universities.id]
        val schools = Schools.selectAll().where { Schools.universityId eq id }.count().toInt()
        UniversityDto(id, row[Universities.name], row[Universities.tagline], row[Universities.details], row[Universities.logoUrl], schools, row[Universities.createdAt])
    }
}

private fun universityById(id: Long): UniversityDto? = transaction {
    Universities.selectAll().where { Universities.id eq id }.limit(1).singleOrNull()?.let { row ->
        UniversityDto(row[Universities.id], row[Universities.name], row[Universities.tagline], row[Universities.details], row[Universities.logoUrl],
            Schools.selectAll().where { Schools.universityId eq id }.count().toInt(), row[Universities.createdAt])
    }
}

internal fun adminOverview(chain: BlockchainAnchor): AdminOverviewDto = transaction {
    AdminOverviewDto(
        students = Users.selectAll().where { Users.role eq UserRole.student.name }.count().toInt(),
        registrars = Users.selectAll().where { Users.role eq UserRole.registrar.name }.count().toInt(),
        universities = Universities.selectAll().count().toInt(), schools = Schools.selectAll().count().toInt(),
        credentials = Credentials.selectAll().count().toInt(),
        activeCredentials = Credentials.selectAll().where { Credentials.status eq CredentialStatus.active.name }.count().toInt(),
        importedStudents = ImportedStudents.selectAll().count().toInt(),
        verificationRequests = VerificationRequests.selectAll().count().toInt(),
        pendingVerificationRequests = VerificationRequests.selectAll().where { VerificationRequests.status eq VerificationRequestStatus.pending.name }.count().toInt(),
        files = ContainerFiles.selectAll().count().toInt(), containers = Containers.selectAll().count().toInt(),
        chainConfigured = chain.configured, ipfsConfigured = Config.ipfsEnabled, chainNetwork = Config.chainNetworkName
    )
}

private fun studentDashboard(user: UserRecord): DashboardDto = transaction {
    val userId = user.id
    val current = Semesters.selectAll().where { (Semesters.userId eq userId) and (Semesters.isCurrent eq true) }
        .limit(1).singleOrNull()?.let { SemesterDto(it[Semesters.id], it[Semesters.academicYearId], it[Semesters.name], it[Semesters.startDate], it[Semesters.endDate], true, it[Semesters.archived]) }
    val countYears = AcademicYears.selectAll().where { AcademicYears.userId eq userId }.count().toInt()
    val countSemesters = Semesters.selectAll().where { Semesters.userId eq userId }.count().toInt()
    val countUnits = Units.selectAll().where { Units.userId eq userId }.count().toInt()
    val countNotes = Notes.selectAll().where { Notes.userId eq userId }.count().toInt()
    val countTasks = Tasks.selectAll().where { Tasks.userId eq userId }.count().toInt()
    val openTasks = Tasks.selectAll().where { (Tasks.userId eq userId) and (Tasks.completed eq false) }.count().toInt()
    val countExams = Exams.selectAll().where { Exams.userId eq userId }.count().toInt()
    val containerIds = Containers.selectAll().where { Containers.userId eq userId }.map { it[Containers.id] }
    val countFiles = if (containerIds.isEmpty()) 0 else containerIds.sumOf { containerId ->
        ContainerFiles.selectAll().where { ContainerFiles.containerId eq containerId }.count().toInt()
    }
    val countContainers = containerIds.size
    val credentialsCount = Credentials.selectAll().where { Credentials.status eq CredentialStatus.active.name }.mapNotNull { row ->
        row[Credentials.studentRecordId]?.let { studentId ->
            ImportedStudents.selectAll().where { ImportedStudents.id eq studentId }.limit(1).singleOrNull()
        }
    }.count { row -> user.email.equals(row[ImportedStudents.email], true) ||
        (user.registrationNumber != null && user.registrationNumber == row[ImportedStudents.registrationNumber]) }
    DashboardDto(userId, user.displayName, current, countYears, countSemesters, countUnits, countNotes, countTasks, openTasks, countExams, countFiles, countContainers, credentialsCount, 0)
}
