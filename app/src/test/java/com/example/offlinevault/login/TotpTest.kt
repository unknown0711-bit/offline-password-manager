package com.example.offlinevault.login

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TotpTest {
    @Test
    fun rfc6238_sha1_vector_matches() {
        val secret = "12345678901234567890".encodeToByteArray()
        try {
            assertEquals("94287082", TotpEngine.generate(secret, 59, TotpAlgorithm.SHA1, 8).value)
            assertEquals("07081804", TotpEngine.generate(secret, 1_111_111_109, TotpAlgorithm.SHA1, 8).value)
        } finally {
            secret.fill(0)
        }
    }

    @Test
    fun rfc6238_sha256_and_sha512_vectors_match() {
        val sha256 = "12345678901234567890123456789012".encodeToByteArray()
        val sha512 = "1234567890123456789012345678901234567890123456789012345678901234".encodeToByteArray()
        try {
            assertEquals("46119246", TotpEngine.generate(sha256, 59, TotpAlgorithm.SHA256, 8).value)
            assertEquals("90693936", TotpEngine.generate(sha512, 59, TotpAlgorithm.SHA512, 8).value)
        } finally {
            sha256.fill(0)
            sha512.fill(0)
        }
    }

    @Test
    fun uri_parser_decodes_secret_and_rejects_mismatched_issuer() {
        val payload = TotpUriParser.parse(
            "otpauth://totp/Example%3Aalice%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example",
        )
        try {
            assertEquals("Example", payload.issuer)
            assertEquals("alice@example.com", payload.label.substringAfter(':'))
            assertEquals(10, payload.secret.size)
        } finally {
            payload.close()
        }

        assertFailsWith<IllegalArgumentException> {
            TotpUriParser.parse(
                "otpauth://totp/Example%3Aalice?secret=JBSWY3DPEHPK3PXP&issuer=Other",
            )
        }
    }
}
