package com.studentcommandcenter.server

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives used across the server:
 *  - PBKDF2-HMAC-SHA256 password hashing (310,000 iterations)
 *  - AES-256-GCM envelope encryption for credential payloads / private keys at rest
 *  - Ed25519 signing for W3C VC-JWTs
 *  - SHA-256 digests and compact JWT helpers
 */
object Security {

    private val random = SecureRandom()

    // ---------- generic helpers ----------

    fun randomHex(byteCount: Int): String =
        java.util.HexFormat.of().formatHex(ByteArray(byteCount).also { random.nextBytes(it) })

    fun randomId(): String = randomHex(16)

    fun sha256Hex(input: String): String =
        java.util.HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        )

    fun sha256Hex(bytes: ByteArray): String =
        java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "odd-length hex string" }
        return java.util.HexFormat.of().parseHex(this)
    }

    // ---------- passwords ----------

    fun hashPassword(password: String): Pair<String, String> { // hashHex to saltHex
        val salt = ByteArray(16)
        random.nextBytes(salt)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 310_000, 256))
            .encoded
        return key.toHex() to salt.toHex()
    }

    fun verifyPassword(password: String, saltHex: String, hashHex: String): Boolean {
        val salt = saltHex.decodeHex()
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 310_000, 256))
            .encoded
        return try {
            MessageDigest.isEqual(key, hashHex.decodeHex())
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    // ---------- AES-256-GCM envelope encryption ----------

    private fun masterKey(): SecretKeySpec {
        val hex = Config.credentialMasterKeyHex
            ?: throw IllegalStateException("SCC_CREDENTIAL_MASTER_KEY is not configured (64 hex chars required)")
        require(hex.length == 64) { "SCC_CREDENTIAL_MASTER_KEY must be 64 hex characters (32 bytes)" }
        return SecretKeySpec(hex.decodeHex(), "AES")
    }

    /** Encrypts arbitrary bytes as iv || ciphertext || GCM tag. */
    fun encryptBytes(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plaintext)
    }

    fun decryptBytes(payload: ByteArray): ByteArray {
        require(payload.size >= 28) { "encrypted payload is truncated" }
        val iv = payload.copyOfRange(0, 12)
        val ct = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    /** Returns base64(iv || ciphertext||tag), suitable for a TEXT database column. */
    fun encryptPlaintext(plaintext: String): String =
        Base64.getEncoder().encodeToString(encryptBytes(plaintext.toByteArray(StandardCharsets.UTF_8)))

    fun decryptCiphertext(payload: String): String =
        String(decryptBytes(Base64.getDecoder().decode(payload)), StandardCharsets.UTF_8)

    // ---------- Ed25519 signing (W3C VC JWT) ----------

    fun generateEd25519KeyPair(): java.security.KeyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun ed25519Sign(privateKey: PrivateKey, message: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(privateKey)
            update(message)
            sign()
        }

    fun ed25519Verify(publicKey: PublicKey, message: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(message)
            verify(signature)
        }

    fun ed25519PublicPem(publicKey: PublicKey): String =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(publicKey.encoded) +
            "\n-----END PUBLIC KEY-----"

    fun parseEd25519PublicKeyPem(pem: String): PublicKey {
        val b64 = pem.trim()
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        val spec = X509EncodedKeySpec(Base64.getDecoder().decode(b64))
        return KeyFactory.getInstance("Ed25519").generatePublic(spec)
    }

    fun ed25519PrivatePem(privateKey: PrivateKey): String =
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(privateKey.encoded) +
            "\n-----END PRIVATE KEY-----"

    fun parseEd25519PrivatePem(pem: String): PrivateKey {
        val b64 = pem.trim()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
        val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64))
        return KeyFactory.getInstance("Ed25519").generatePrivate(spec)
    }

    // ---------- compact JWT (EdDSA) ----------

    fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun base64UrlDecode(segment: String): ByteArray = Base64.getUrlDecoder().decode(segment)

    /** Builds a compact EdDSA JWT: base64url(header).base64url(payload).base64url(signature). */
    fun compactEdDsaJwt(headerJson: String, payloadJson: String, privateKey: PrivateKey): String {
        val signingInput = base64Url(headerJson.toByteArray(StandardCharsets.UTF_8)) + "." +
            base64Url(payloadJson.toByteArray(StandardCharsets.UTF_8))
        val signature = ed25519Sign(privateKey, signingInput.toByteArray(StandardCharsets.UTF_8))
        return signingInput + "." + base64Url(signature)
    }

    fun splitCompactJwt(jwt: String): Triple<String, String, String> {
        val parts = jwt.split(".")
        require(parts.size == 3) { "malformed compact JWT" }
        return Triple(parts[0], parts[1], parts[2])
    }

    private fun ByteArray.toHex(): String = java.util.HexFormat.of().formatHex(this)
}
