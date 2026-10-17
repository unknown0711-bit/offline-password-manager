package com.example.offlinevault.media

import android.content.SharedPreferences
import com.example.offlinevault.security.DataEncryptionKey
import com.example.offlinevault.security.TinkInitializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKeyManager
import com.google.crypto.tink.subtle.AesGcmJce
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.util.Base64

public interface MediaKeysetBackend {
    public fun read(): String?
    public fun write(encoded: String): Boolean
    public fun clear(): Boolean
}

public class SharedPreferencesMediaKeysetBackend(
    private val preferences: SharedPreferences,
    private val preferenceKey: String = MediaKeysetStore.DEFAULT_PREFERENCE_KEY,
) : MediaKeysetBackend {
    override fun read(): String? = preferences.getString(preferenceKey, null)
    override fun write(encoded: String): Boolean = preferences.edit().putString(preferenceKey, encoded).commit()
    override fun clear(): Boolean = preferences.edit().remove(preferenceKey).commit()
}

public class MediaStreamingKeyset internal constructor(
    private var handle: KeysetHandle?,
    private var primitive: StreamingAead?,
) : AutoCloseable {
    public fun primitive(): StreamingAead = primitive
        ?: error("The media keyset is closed")

    override fun close() {
        primitive = null
        handle = null
    }
}

/**
 * Stores only a Tink EncryptedKeyset. The unlocked vault DEK wraps the
 * keyset, and the encrypted value is then protected by EncryptedSharedPreferences.
 */
public class MediaKeysetStore(private val backend: MediaKeysetBackend) {
    @Synchronized
    @Throws(GeneralSecurityException::class)
    public fun open(dek: DataEncryptionKey): MediaStreamingKeyset {
        TinkInitializer.initialize()
        val dekBytes = dek.copyBytes()
        return try {
            val wrappingAead: Aead = AesGcmJce(dekBytes)
            val stored = backend.read()
            val handle = if (stored == null) {
                val generated = KeysetHandle.generateNew(
                    AesGcmHkdfStreamingKeyManager.aes256GcmHkdf4KBTemplate(),
                )
                try {
                    persist(generated, wrappingAead)
                } catch (error: Throwable) {
                    throw error
                }
                generated
            } else {
                val encrypted = runCatching { Base64.getDecoder().decode(stored) }
                    .getOrElse { throw GeneralSecurityException("Invalid media keyset encoding", it) }
                try {
                    KeysetHandle.readWithAssociatedData(
                        BinaryKeysetReader.withBytes(encrypted),
                        wrappingAead,
                        ASSOCIATED_DATA,
                    )
                } finally {
                    encrypted.fill(0)
                }
            }
            MediaStreamingKeyset(handle, handle.getPrimitive(StreamingAead::class.java))
        } finally {
            dekBytes.fill(0)
        }
    }

    @Synchronized
    public fun clear() {
        check(backend.clear()) { "Unable to clear media keyset" }
    }

    private fun persist(handle: KeysetHandle, wrappingAead: Aead) {
        val output = ByteArrayOutputStream()
        handle.writeWithAssociatedData(
            BinaryKeysetWriter.withOutputStream(output),
            wrappingAead,
            ASSOCIATED_DATA,
        )
        val encrypted = output.toByteArray()
        try {
            val encoded = Base64.getEncoder().encodeToString(encrypted)
            check(backend.write(encoded)) { "Unable to persist media keyset" }
        } finally {
            encrypted.fill(0)
            output.reset()
        }
    }

    public companion object {
        public const val DEFAULT_PREFERENCE_KEY: String = "media_streaming_keyset_v1"
        private val ASSOCIATED_DATA = "offline-vault/media-keyset/v1".encodeToByteArray()
    }
}
