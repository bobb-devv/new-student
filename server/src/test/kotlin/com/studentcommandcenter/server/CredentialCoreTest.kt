package com.studentcommandcenter.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CredentialCoreTest {
    private val credentialService = CredentialService(IpfsClient(), BlockchainAnchor())

    @Test
    fun `CSV parser supports quoted commas and escaped quotes`() {
        val parsed = credentialService.parseCsv(
            "full_name,email,programme\r\n\"Otieno, A.\",a@example.edu,\"BSc \"\"Data Science\"\"\"\r\n"
        )
        assertEquals(2, parsed.size)
        assertEquals("Otieno, A.", parsed[1][0])
        assertEquals("BSc \"Data Science\"", parsed[1][2])
    }

    @Test
    fun `CSV parser ignores blank rows and keeps simple row values`() {
        val parsed = credentialService.parseCsv("name,email\nA,a@example.edu\n\nB,b@example.edu\n")
        assertEquals(3, parsed.size)
        assertEquals(listOf("B", "b@example.edu"), parsed.last())
    }

    @Test
    fun `SHA-256 digest is lowercase fixed-length hex`() {
        val digest = Security.sha256Hex("scc-test")
        assertEquals(64, digest.length)
        assertTrue(digest.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(digest, Security.sha256Hex("scc-test"))
        assertFalse(digest == Security.sha256Hex("different"))
    }

    @Test
    fun `Ed25519 signing and verification round trips`() {
        val pair = Security.generateEd25519KeyPair()
        val message = "credential payload".toByteArray()
        val signature = Security.ed25519Sign(pair.private, message)
        assertTrue(Security.ed25519Verify(pair.public, message, signature))
        assertFalse(Security.ed25519Verify(pair.public, "tampered".toByteArray(), signature))
    }

    @Test
    fun `on-chain anchor key is bytes32 and deterministic`() {
        val chain = BlockchainAnchor()
        val digest = Security.sha256Hex("credential")
        val key = chain.anchorKey(digest)
        assertEquals(32, key.size)
        assertTrue(key.contentEquals(chain.anchorKey(digest)))
        assertFalse(key.contentEquals(chain.anchorKey(Security.sha256Hex("other"))))
    }

    @Test
    fun `IPFS CID commitment is deterministic and does not equal CID bytes`() {
        val chain = BlockchainAnchor()
        val cid = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf5x3k3is6ay2nhgm6e7i2lcu5e"
        val commitment = chain.cidCommitment(cid)
        assertEquals(32, commitment.size)
        assertTrue(commitment.contentEquals(chain.cidCommitment(cid)))
        assertFalse(commitment.contentEquals(cid.toByteArray()))
    }
}
