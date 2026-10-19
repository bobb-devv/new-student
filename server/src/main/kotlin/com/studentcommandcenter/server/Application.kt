package com.studentcommandcenter.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

private val log = org.slf4j.LoggerFactory.getLogger("SccAnchored")

fun main() {
    if (Config.environment.equals("production", ignoreCase = true)) {
        require(Config.adminPassword != "change-me-now" && Config.adminPassword.length >= 12) {
            "Set SCC_ADMIN_PASSWORD to a strong value before using SCC_ENV=production"
        }
        require(Config.credentialMasterKeyHex?.matches(Regex("^[0-9a-fA-F]{64}$")) == true) {
            "SCC_CREDENTIAL_MASTER_KEY must be 32 random bytes (64 hex chars) in production"
        }
    }
    embeddedServer(Netty, host = "0.0.0.0", port = Config.port) { module() }.start(wait = true)
}

fun Application.module() {
    Database.connect()
    val auth = AuthService()
    val ipfs = IpfsClient()
    val chain = BlockchainAnchor()
    val credentials = CredentialService(ipfs, chain)
    val containers = ContainersService(ipfs, chain, Config.storageDir)
    val verification = VerificationService(credentials)
    val academic = AcademicService()

    install(CallLogging) { level = Level.INFO }
    install(ContentNegotiation) {
        json(Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        })
    }
    install(StatusPages) {
        exception<AppError> { call, cause ->
            call.respond(HttpStatusCode.fromValue(cause.status), ErrorResponse(cause.message ?: "request failed"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "invalid request"))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "invalid request body"))
        }
        exception<Throwable> { call, cause ->
            log.error("Unhandled request failure", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal server error"))
        }
    }

    configureApiRoutes(auth, credentials, containers, verification, chain, academic)
    configureAdminWeb(auth, credentials, verification, chain)
}
