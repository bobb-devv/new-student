package com.studentcommandcenter.server

/**
 * Central configuration. Everything is environment-driven so no secret
 * ever lands in source control. See README.md for the full variable list.
 */
object Config {
    private fun env(name: String, default: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    private fun envOrNull(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }

    // --- server ---
    val port: Int = env("PORT", "4180").toIntOrNull() ?: 4180
    val dataDir: String = env("SCC_DATA_DIR", "data")
    val storageDir: String = env("SCC_STORAGE_DIR", "storage")
    val databaseUrl: String = env("DATABASE_URL", "jdbc:sqlite:$dataDir/scc-anchored.db")

    // --- auth ---
    val adminEmail: String = env("SCC_ADMIN_EMAIL", "admin@example.local")
    val adminPassword: String = env("SCC_ADMIN_PASSWORD", "change-me-now")
    val sessionTtlDays: Long = 7L
    val environment: String = env("SCC_ENV", "development")

    // --- credential security ---
    /** 64 hex chars (32 bytes) AES-256-GCM master key for keys/payloads at rest. */
    val credentialMasterKeyHex: String? = envOrNull("SCC_CREDENTIAL_MASTER_KEY")

    // --- IPFS (self-hosted Kubo node) ---
    val ipfsApiUrl: String = env("SCC_IPFS_API_URL", "http://127.0.0.1:5001")
    val ipfsGatewayUrl: String = env("SCC_IPFS_GATEWAY_URL", "http://127.0.0.1:8080")

    // --- Polygon anchoring ---
    val polygonRpcUrl: String? = envOrNull("SCC_POLYGON_RPC_URL")
    val anchorPrivateKey: String? = envOrNull("SCC_ANCHOR_PRIVATE_KEY")
    val anchorContractAddress: String? = envOrNull("SCC_ANCHOR_CONTRACT_ADDRESS")
    val chainNetworkName: String = env("SCC_CHAIN_NETWORK", "Polygon Amoy")

    // --- limits ---
    val maxUploadBytes: Long =
        env("SCC_MAX_UPLOAD_BYTES", (50L * 1024 * 1024).toString()).toLongOrNull()
            ?: (50L * 1024 * 1024)
    val csvMaxBytes: Int = 2 * 1024 * 1024
    val csvMaxRows: Int = 2000

    // --- W3C VC identity ---
    /** Base host used to build did:web issuer identifiers. */
    val issuerDidWebHost: String = env("SCC_ISSUER_DID_HOST", "scc.example.edu")
    val baseUrl: String = env("SCC_BASE_URL", "http://localhost:4180")

    /** True only when a full on-chain anchoring setup is present. */
    val chainAnchoringEnabled: Boolean
        get() = polygonRpcUrl != null && anchorPrivateKey != null && anchorContractAddress != null

    val ipfsEnabled: Boolean get() = ipfsApiUrl.isNotBlank()
}
