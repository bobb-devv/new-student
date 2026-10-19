package com.studentcommandcenter.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint64
import org.web3j.abi.datatypes.generated.Uint8
import org.web3j.crypto.Credentials
import org.web3j.crypto.Hash
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import org.web3j.utils.Numeric
import java.io.File
import java.math.BigInteger

/** Polygon transaction adapter. The private key is a server-side relayer key. */
class BlockchainAnchor {
    private val web3j: Web3j? = Config.polygonRpcUrl?.let { Web3j.build(HttpService(it)) }
    private val credentials: Credentials? = Config.anchorPrivateKey?.let { Credentials.create(it) }

    val configured: Boolean
        get() = Config.chainAnchoringEnabled && web3j != null && credentials != null &&
            !Config.anchorContractAddress.isNullOrBlank()

    /** keccak256 over the lower-case 64-char SHA-256 hex digest. */
    fun anchorKey(sha256HexDigest: String): ByteArray =
        Hash.sha3(sha256HexDigest.lowercase().toByteArray(Charsets.UTF_8))

    /** The chain stores only a hash of the CID string; absent CID is bytes32(0). */
    fun cidCommitment(cid: String?): ByteArray =
        if (cid.isNullOrBlank()) ByteArray(32) else Hash.sha3(cid.toByteArray(Charsets.UTF_8))

    suspend fun anchorCredential(sha256DigestHex: String, cid: String?): String = withContext(Dispatchers.IO) {
        check(configured) { "Polygon anchoring is not configured" }
        sendTransaction(
            Function(
                "anchorCredential",
                listOf(Bytes32(anchorKey(sha256DigestHex)), Bytes32(cidCommitment(cid))),
                emptyList()
            )
        )
    }

    suspend fun anchorBatch(entries: List<Pair<String, String?>>): String = withContext(Dispatchers.IO) {
        check(configured) { "Polygon anchoring is not configured" }
        require(entries.isNotEmpty() && entries.size <= 100) { "batch size must be 1..100" }
        sendTransaction(
            Function(
                "anchorBatch",
                listOf(
                    DynamicArray(Bytes32::class.java, entries.map { Bytes32(anchorKey(it.first)) }),
                    DynamicArray(Bytes32::class.java, entries.map { Bytes32(cidCommitment(it.second)) })
                ),
                emptyList()
            )
        )
    }

    suspend fun revokeOnChain(sha256DigestHex: String): String = withContext(Dispatchers.IO) {
        check(configured) { "Polygon anchoring is not configured" }
        sendTransaction(Function("revoke", listOf(Bytes32(anchorKey(sha256DigestHex))), emptyList()))
    }

    suspend fun markSupersededOnChain(oldDigest: String, newDigest: String): String = withContext(Dispatchers.IO) {
        check(configured) { "Polygon anchoring is not configured" }
        sendTransaction(
            Function(
                "markSuperseded",
                listOf(Bytes32(anchorKey(oldDigest)), Bytes32(anchorKey(newDigest))),
                emptyList()
            )
        )
    }

    data class OnChainAnchor(val cidCommitment: ByteArray, val issuer: String, val issuedAt: Long, val status: Long) {
        fun statusLabel(): String = when (status) {
            1L -> "active"
            2L -> "revoked"
            3L -> "superseded"
            else -> "unknown"
        }
    }

    suspend fun readAnchor(sha256DigestHex: String): OnChainAnchor? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val fn = Function(
            "getAnchor",
            listOf(Bytes32(anchorKey(sha256DigestHex))),
            listOf(
                object : TypeReference<Bytes32>() {},
                object : TypeReference<Address>() {},
                object : TypeReference<Uint64>() {},
                object : TypeReference<Uint8>() {}
            )
        )
        val response = web3j!!.ethCall(
            Transaction.createEthCallTransaction(credentials!!.address, Config.anchorContractAddress, FunctionEncoder.encode(fn)),
            DefaultBlockParameterName.LATEST
        ).send()
        if (response.hasError() || response.value.isNullOrEmpty()) return@withContext null
        val decoded = FunctionReturnDecoder.decode(response.value, fn.outputParameters)
        if (decoded.size < 4) return@withContext null
        val timestamp = (decoded[2] as Uint64).value.longValueExact()
        if (timestamp == 0L) return@withContext null
        OnChainAnchor(
            cidCommitment = (decoded[0] as Bytes32).value,
            issuer = (decoded[1] as Address).value,
            issuedAt = timestamp,
            status = (decoded[3] as Uint8).value.longValueExact()
        )
    }

    suspend fun deployContract(binPath: String = "contracts/build/CredentialAnchorRegistry.bin"): String =
        withContext(Dispatchers.IO) {
            check(configured) { "Polygon RPC and relayer key must be configured before deploy" }
            val bin = File(binPath).readText().trim().removePrefix("0x")
            require(bin.matches(Regex("[0-9a-fA-F]+"))) { "contract bytecode is missing or invalid: $binPath" }
            val nonce = web3j!!.ethGetTransactionCount(credentials!!.address, DefaultBlockParameterName.PENDING)
                .send().transactionCount
            val raw = RawTransaction.createContractTransaction(
                nonce,
                DefaultGasProvider.GAS_PRICE,
                BigInteger.valueOf(6_000_000),
                BigInteger.ZERO,
                "0x$bin"
            )
            val signed = TransactionEncoder.signMessage(raw, credentials)
            val response = web3j!!.ethSendRawTransaction(Numeric.toHexString(signed)).send()
            if (response.hasError()) error("contract deployment failed: ${response.error.message}")
            val receipt = PollingTransactionReceiptProcessor(web3j, 1_000, 60)
                .waitForTransactionReceipt(response.transactionHash)
            check(receipt.isStatusOK) { "contract deployment reverted: ${receipt.transactionHash}" }
            receipt.contractAddress
        }

    private fun sendTransaction(function: Function): String {
        val sender = credentials ?: error("SCC_ANCHOR_PRIVATE_KEY is missing")
        val rpc = web3j ?: error("SCC_POLYGON_RPC_URL is missing")
        val encoded = FunctionEncoder.encode(function)
        val nonce = rpc.ethGetTransactionCount(sender.address, DefaultBlockParameterName.PENDING).send().transactionCount
        val raw = RawTransaction.createTransaction(
            nonce,
            DefaultGasProvider.GAS_PRICE,
            BigInteger.valueOf(1_000_000),
            Config.anchorContractAddress,
            encoded
        )
        val signed = TransactionEncoder.signMessage(raw, sender)
        val response = rpc.ethSendRawTransaction(Numeric.toHexString(signed)).send()
        if (response.hasError()) throw IllegalStateException("Polygon transaction failed: ${response.error.message}")
        val receipt = PollingTransactionReceiptProcessor(rpc, 1_000, 60)
            .waitForTransactionReceipt(response.transactionHash)
        check(receipt.isStatusOK) { "Polygon transaction reverted: ${receipt.transactionHash}" }
        return response.transactionHash
    }

    suspend fun latestBlock(): Long? = try {
        withContext(Dispatchers.IO) { web3j?.ethBlockNumber()?.send()?.blockNumber?.toLong() }
    } catch (_: Exception) {
        null
    }
}
