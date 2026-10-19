package com.studentcommandcenter.server

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.File

/** Student-owned semester/event folders with encrypted file storage and optional IPFS pinning. */
class ContainersService(
    private val ipfs: IpfsClient,
    private val chain: BlockchainAnchor,
    private val storageRoot: String
) {
    private val allowedExtensions = setOf("pdf", "ppt", "pptx", "doc", "docx", "xls", "xlsx", "csv", "txt", "jpg", "jpeg", "png", "md")

    fun listContainers(userId: Long, kind: String?): List<ContainerDto> = transaction {
        val query = if (kind == null) Containers.selectAll().where { Containers.userId eq userId }
        else Containers.selectAll().where { (Containers.userId eq userId) and (Containers.kind eq kind) }
        query.orderBy(Containers.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC).toList().map { toDto(it.snapshot(Containers), userId) }
    }

    fun createContainer(
        userId: Long, kind: String, name: String, description: String?,
        startDate: String?, endDate: String?, semesterId: Long?
    ): ContainerDto {
        require(kind.equals("event", true) || kind.equals("semester", true)) { "kind must be semester or event" }
        require(name.isNotBlank() && name.length <= 200) { "container name is required (max 200 characters)" }
        val id = transaction {
            semesterId?.let { sid ->
                require(Semesters.selectAll().where { (Semesters.id eq sid) and (Semesters.userId eq userId) }.count() > 0L) {
                    "semester not found"
                }
            }
            Containers.insert {
                it[Containers.userId] = userId
                it[Containers.kind] = kind.lowercase()
                it[Containers.name] = name.trim()
                it[Containers.description] = description?.take(2000)
                it[Containers.startDate] = startDate
                it[Containers.endDate] = endDate
                it[Containers.semesterId] = semesterId
                it[Containers.anchored] = false
                it[Containers.createdAt] = System.currentTimeMillis()
            }[Containers.id]
        }
        Audit.log(actorId = userId, actorRole = "student", action = "container.created", entityType = "container", entityId = id.toString())
        return getContainer(userId, id)
    }

    fun getContainer(userId: Long, containerId: Long): ContainerDto = transaction {
        Containers.selectAll().where { (Containers.id eq containerId) and (Containers.userId eq userId) }
            .limit(1).singleOrNull()?.snapshot(Containers)
    }?.let { toDto(it, userId) } ?: error(404, "container not found")

    fun updateContainer(userId: Long, containerId: Long, name: String?, description: String?, startDate: String?, endDate: String?): ContainerDto {
        getContainer(userId, containerId)
        transaction {
            Containers.update({ (Containers.id eq containerId) and (Containers.userId eq userId) }) {
                name?.let { value -> it[Containers.name] = value.trim().take(200) }
                description?.let { value -> it[Containers.description] = value.take(2000) }
                startDate?.let { value -> it[Containers.startDate] = value }
                endDate?.let { value -> it[Containers.endDate] = value }
            }
        }
        return getContainer(userId, containerId)
    }

    fun deleteContainer(userId: Long, containerId: Long) {
        getContainer(userId, containerId)
        transaction {
            ContainerFiles.selectAll().where { ContainerFiles.containerId eq containerId }.forEach { row ->
                File(row[ContainerFiles.storagePath]).delete()
            }
            ContainerFiles.deleteWhere { ContainerFiles.containerId eq containerId }
            Containers.deleteWhere { (Containers.id eq containerId) and (Containers.userId eq userId) }
        }
        Audit.log(actorId = userId, actorRole = "student", action = "container.deleted", entityType = "container", entityId = containerId.toString())
    }

    fun listContainerFiles(userId: Long, containerId: Long): List<ContainerFileDto> {
        getContainer(userId, containerId)
        return transaction {
            ContainerFiles.selectAll().where { ContainerFiles.containerId eq containerId }
                .orderBy(ContainerFiles.uploadedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                .map { toFileDto(it.snapshot(ContainerFiles)) }
        }
    }

    /** Stores encrypted bytes locally and, if requested, pins encrypted bytes to IPFS. */
    suspend fun importFile(
        userId: Long, containerId: Long, fileName: String, content: ByteArray,
        mime: String, note: String?, pinToIpfs: Boolean
    ): ContainerFileDto {
        getContainer(userId, containerId)
        require(content.isNotEmpty()) { "file is empty" }
        require(content.size.toLong() <= Config.maxUploadBytes) {
            "file exceeds the ${Config.maxUploadBytes / (1024 * 1024)} MB limit"
        }
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._() -]"), "_").take(240)
        require(safeName.isNotBlank()) { "file name is invalid" }
        val ext = safeName.substringAfterLast('.', "").lowercase()
        require(ext in allowedExtensions) { "file type .$ext is not accepted" }
        val sha = Security.sha256Hex(content)
        val encryptedBytes = Security.encryptBytes(content)
        val storagePath = "$storageRoot/containers/$containerId/${System.currentTimeMillis()}-$safeName.sccenc"
        File(storagePath).parentFile?.mkdirs()
        File(storagePath).writeBytes(encryptedBytes)

        var cid: String? = null
        if (pinToIpfs && Config.ipfsEnabled) {
            try {
                cid = ipfs.addAndPin("$safeName.sccenc", encryptedBytes, "application/octet-stream")
            } catch (e: Exception) {
                Audit.log(actorId = userId, actorRole = "student", action = "container.ipfs.failed", entityType = "containerFile",
                    entityId = safeName, status = "error", errorMessage = e.message)
            }
        }
        val id = transaction {
            ContainerFiles.insert {
                it[ContainerFiles.containerId] = containerId
                it[ContainerFiles.name] = safeName
                it[ContainerFiles.size] = content.size.toLong()
                it[ContainerFiles.mime] = mime.take(120)
                it[ContainerFiles.storagePath] = storagePath
                it[ContainerFiles.sha256] = sha
                it[ContainerFiles.ipfsCid] = cid
                it[ContainerFiles.note] = note?.take(1000)
                it[ContainerFiles.uploadedAt] = System.currentTimeMillis()
            }[ContainerFiles.id]
        }
        Audit.log(actorId = userId, actorRole = "student", action = "container.file.imported", entityType = "containerFile",
            entityId = id.toString(), detail = Json.encodeToString(mapOf("name" to safeName, "sha256" to sha, "ipfsCid" to cid)))
        return getFileDto(userId, containerId, id)
    }

    fun getFileDto(userId: Long, containerId: Long, fileId: Long): ContainerFileDto {
        getContainer(userId, containerId)
        return transaction {
            ContainerFiles.selectAll().where { (ContainerFiles.id eq fileId) and (ContainerFiles.containerId eq containerId) }
                .limit(1).singleOrNull()?.snapshot(ContainerFiles)
        }?.let { toFileDto(it) } ?: error(404, "file not found")
    }

    fun deleteContainerFile(userId: Long, containerId: Long, fileId: Long) {
        getContainer(userId, containerId)
        transaction {
            ContainerFiles.selectAll().where { (ContainerFiles.id eq fileId) and (ContainerFiles.containerId eq containerId) }
                .limit(1).singleOrNull()?.let { File(it[ContainerFiles.storagePath]).delete() }
            ContainerFiles.deleteWhere { (ContainerFiles.id eq fileId) and (ContainerFiles.containerId eq containerId) }
        }
    }

    fun storagePathFor(userId: Long, containerId: Long, fileId: Long): Pair<String, ByteArray> {
        val dto = getFileDto(userId, containerId, fileId)
        val path = transaction { ContainerFiles.selectAll().where { ContainerFiles.id eq fileId }.limit(1).singleOrNull()?.get(ContainerFiles.storagePath) }
            ?: error(404, "file not found")
        return dto.name to Security.decryptBytes(File(path).readBytes())
    }

    /** Encrypted IPFS manifest + compact on-chain hash/CID commitment. */
    suspend fun anchorContainer(userId: Long, containerId: Long): ContainerAnchorResult {
        val container = getContainer(userId, containerId)
        val files = listContainerFiles(userId, containerId)
        val manifest = buildJsonObject {
            put("containerId", containerId)
            put("owner", userId.toString())
            put("kind", container.kind)
            put("name", container.name)
            container.description?.let { put("description", it) }
            put("fileCount", files.size)
            put("files", buildJsonArray {
                files.forEach { f -> add(buildJsonObject {
                    put("name", f.name); put("size", f.size); put("mime", f.mime); put("sha256", f.sha256)
                    f.ipfsCid?.let { put("ipfsCid", it) }
                }) }
            })
            put("createdAt", container.createdAt)
            put("anchoredAt", java.time.Instant.now().toString())
        }
        val manifestJson = Json.encodeToString(manifest)
        val manifestDigest = Security.sha256Hex(manifestJson)
        val encryptedManifest = Security.encryptPlaintext(manifestJson).toByteArray()
        val manifestCid = try {
            if (Config.ipfsEnabled) ipfs.addAndPin("container-$containerId-manifest.enc.json", encryptedManifest, "application/octet-stream") else null
        } catch (e: Exception) {
            Audit.log(actorId = userId, actorRole = "student", action = "container.manifest.ipfs.failed", entityType = "container",
                entityId = containerId.toString(), status = "error", errorMessage = e.message)
            null
        }

        var txHash: String? = null
        var anchorStatus = AnchorStatus.local_only.name
        if (chain.configured && manifestCid != null) {
            try {
                txHash = chain.anchorCredential(manifestDigest, manifestCid)
                anchorStatus = AnchorStatus.anchored.name
            } catch (e: Exception) {
                anchorStatus = AnchorStatus.failed.name
                Audit.log(actorId = userId, actorRole = "student", action = "container.anchor.failed", entityType = "container",
                    entityId = containerId.toString(), status = "error", errorMessage = e.message)
            }
        }
        val manifestCidValue = manifestCid
        transaction {
            Containers.update({ (Containers.id eq containerId) and (Containers.userId eq userId) }) {
                it[Containers.manifestCid] = manifestCidValue
                it[Containers.manifestDigest] = manifestDigest
                it[Containers.anchored] = txHash != null
                it[Containers.anchorTxHash] = txHash
            }
            if (txHash != null) {
                ChainAnchors.insert {
                    it[ChainAnchors.digest] = manifestDigest
                    it[ChainAnchors.txHash] = txHash!!
                    it[ChainAnchors.blockNumber] = null
                    it[ChainAnchors.anchorType] = "container"
                    it[ChainAnchors.network] = Config.chainNetworkName
                    it[ChainAnchors.createdAt] = System.currentTimeMillis()
                }
            }
        }
        Audit.log(actorId = userId, actorRole = "student", action = "container.anchored", entityType = "container",
            entityId = containerId.toString(), detail = Json.encodeToString(mapOf("digest" to manifestDigest, "status" to anchorStatus)))
        return ContainerAnchorResult(containerId, manifestCid ?: "local-only", manifestDigest, txHash, anchorStatus)
    }

    private fun toDto(row: RowSnapshot, userId: Long): ContainerDto {
        val (fileCount, totalBytes) = transaction {
            val files = ContainerFiles.selectAll().where { ContainerFiles.containerId eq row[Containers.id] }.toList()
            files.size to files.sumOf { it[ContainerFiles.size] }
        }
        return ContainerDto(
            id = row[Containers.id], userId = userId, kind = row[Containers.kind], name = row[Containers.name],
            description = row[Containers.description], startDate = row[Containers.startDate], endDate = row[Containers.endDate],
            semesterId = row[Containers.semesterId], fileCount = fileCount, totalBytes = totalBytes,
            manifestCid = row[Containers.manifestCid], manifestDigest = row[Containers.manifestDigest], anchored = row[Containers.anchored],
            anchorTxHash = row[Containers.anchorTxHash], ipfsGatewayUrl = row[Containers.manifestCid]?.let(ipfs::gatewayUrl),
            createdAt = row[Containers.createdAt]
        )
    }

    private fun toFileDto(row: RowSnapshot) = ContainerFileDto(
        id = row[ContainerFiles.id], containerId = row[ContainerFiles.containerId], name = row[ContainerFiles.name],
        size = row[ContainerFiles.size], mime = row[ContainerFiles.mime], sha256 = row[ContainerFiles.sha256],
        ipfsCid = row[ContainerFiles.ipfsCid], ipfsGatewayUrl = row[ContainerFiles.ipfsCid]?.let(ipfs::gatewayUrl),
        note = row[ContainerFiles.note], uploadedAt = row[ContainerFiles.uploadedAt]
    )
}
