package com.studentcommandcenter.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Small Kubo HTTP API client. Keep this API port on a private network. */
class IpfsClient {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }
    private val api: String get() = Config.ipfsApiUrl.trimEnd('/')

    suspend fun isReachable(): Boolean = try {
        client.post("$api/api/v0/version").status.value in 200..299
    } catch (_: Exception) { false }

    /** Add and pin bytes; Kubo returns newline-delimited JSON with the CID. */
    suspend fun addAndPin(fileName: String, content: ByteArray, mime: String = "application/octet-stream"): String {
        require(fileName.isNotBlank() && !fileName.contains("/") && !fileName.contains("\\")) { "invalid IPFS filename" }
        val responseText = client.post("$api/api/v0/add") {
            parameter("pin", "true")
            parameter("cid-version", "1")
            parameter("wrap-with-directory", "false")
            setBody(MultiPartFormDataContent(formData {
                append("file", content, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    append(HttpHeaders.ContentType, mime)
                })
            }))
        }.bodyAsText()
        val line = responseText.lineSequence().lastOrNull { it.contains("\"Hash\"") }
            ?: throw IllegalStateException("IPFS add response did not include a CID")
        val json = Json.parseToJsonElement(line).jsonObject
        return json["Hash"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("IPFS add response had no Hash")
    }

    suspend fun pin(cid: String): Boolean = try {
        client.post("$api/api/v0/pin/add") { parameter("arg", cid) }.status.value in 200..299
    } catch (_: Exception) { false }

    fun gatewayUrl(cid: String): String = "${Config.ipfsGatewayUrl.trimEnd('/')}/ipfs/$cid"
}
