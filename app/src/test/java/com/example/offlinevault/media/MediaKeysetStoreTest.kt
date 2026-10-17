package com.example.offlinevault.media

import com.example.offlinevault.security.DataEncryptionKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class MediaKeysetStoreTest {
    @Test
    fun keysetIsEncryptedAndReopensStreamingAead() = runBlocking {
        val backend = MemoryBackend()
        val store = MediaKeysetStore(backend)
        val keyBytes = ByteArray(32) { (it + 1).toByte() }
        val plaintext = ByteArray(11_111) { (it * 31).toByte() }
        val dek = DataEncryptionKey(keyBytes)
        keyBytes.fill(0)
        try {
            val first = store.open(dek)
            val encrypted = ByteArrayOutputStream()
            MediaFileEncryptor(first.primitive()).encrypt(
                input = ByteArrayInputStream(plaintext),
                output = encrypted,
                associatedData = "media-test".encodeToByteArray(),
                totalBytes = plaintext.size.toLong(),
                onProgress = {},
            )
            first.close()

            assertFalse(backend.value.isNullOrEmpty())
            val reopened = store.open(dek)
            val decrypted = ByteArrayOutputStream()
            MediaFileEncryptor(reopened.primitive()).decrypt(
                input = ByteArrayInputStream(encrypted.toByteArray()),
                output = decrypted,
                associatedData = "media-test".encodeToByteArray(),
            )
            assertContentEquals(plaintext, decrypted.toByteArray())
            reopened.close()
        } finally {
            dek.close()
            plaintext.fill(0)
        }
    }

    private class MemoryBackend : MediaKeysetBackend {
        var value: String? = null
        override fun read(): String? = value
        override fun write(encoded: String): Boolean {
            value = encoded
            return true
        }
        override fun clear(): Boolean {
            value = null
            return true
        }
    }
}
