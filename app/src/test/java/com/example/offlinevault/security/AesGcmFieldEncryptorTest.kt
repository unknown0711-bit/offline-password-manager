package com.example.offlinevault.security

import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AesGcmFieldEncryptorTest {
    @Test
    fun encryptsAndDecryptsWithFreshNoncesAndExactAad() {
        val keyBytes = ByteArray(32) { it.toByte() }
        val plaintext = byteArrayOf(1, 2, 3, 4)
        val dek = DataEncryptionKey(keyBytes)
        keyBytes.fill(0)
        try {
            val encryptor = AesGcmFieldEncryptor()
            val first = encryptor.encrypt("entry_fields", "entry-uuid", "password", 1, plaintext, dek)
            val second = encryptor.encrypt("entry_fields", "entry-uuid", "password", 1, plaintext, dek)

            assertFalse(first.nonce.contentEquals(second.nonce))
            assertContentEquals(
                plaintext,
                encryptor.decrypt("entry_fields", "entry-uuid", "password", first, dek),
            )
            assertFailsWith<GeneralSecurityException> {
                encryptor.decrypt("entry_fields", "entry-uuid", "url", first, dek)
            }
        } finally {
            dek.close()
            plaintext.fill(0)
        }
    }
}
