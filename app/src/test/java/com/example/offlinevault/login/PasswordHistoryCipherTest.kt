package com.example.offlinevault.login

import com.example.offlinevault.security.DataEncryptionKey
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PasswordHistoryCipherTest {
    @Test
    fun history_round_trip_uses_exact_aad_and_wipes_owned_arrays() {
        val keyBytes = ByteArray(32) { it.toByte() }
        val password = "old-password-123!".toCharArray()
        val dek = DataEncryptionKey(keyBytes)
        keyBytes.fill(0)
        try {
            val cipher = PasswordHistoryCipher()
            assertContentEquals("history|entry-1|7".encodeToByteArray(), cipher.aad("entry-1", 7))
            val encrypted = cipher.encrypt("entry-1", 7, password, 1234L, dek)
            val item = cipher.decrypt("entry-1", encrypted, dek)
            try {
                assertEquals(7, item.index)
                assertEquals(1234L, item.changedAt)
                assertContentEquals("old-password-123!".toCharArray(), item.password)
            } finally {
                item.close()
            }
            assertFailsWith<GeneralSecurityException> {
                cipher.decrypt("entry-1", EncryptedPasswordHistory(8, 1234L, encrypted.nonce, encrypted.ciphertext), dek)
            }
        } finally {
            password.fill('\u0000')
            dek.close()
        }
    }
}
