package com.example.offlinevault.security

import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class Argon2idPasswordWrapperTest {
    @Test
    fun wrapsAndUnwrapsTheDekAndRejectsAnotherPassword() {
        val password = charArrayOf('c', 'o', 'r', 'r', 'e', 'c', 't')
        val wrongPassword = charArrayOf('w', 'r', 'o', 'n', 'g')
        val dekBytes = ByteArray(32) { (it + 1).toByte() }
        val dek = DataEncryptionKey(dekBytes)
        val wrapper = Argon2idPasswordWrapper(
            deriver = BouncyCastleArgon2idDeriver(),
            parameters = Argon2idParameters(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1),
        )
        try {
            val wrapped = wrapper.wrap(dek, password)
            val recovered = wrapper.unwrap(wrapped, password)
            try {
                assertContentEquals(dekBytes, recovered.copyBytes())
            } finally {
                recovered.close()
            }
            assertFailsWith<GeneralSecurityException> {
                wrapper.unwrap(wrapped, wrongPassword).close()
            }
        } finally {
            password.fill('\u0000')
            wrongPassword.fill('\u0000')
            dekBytes.fill(0)
            dek.close()
        }
    }
}
