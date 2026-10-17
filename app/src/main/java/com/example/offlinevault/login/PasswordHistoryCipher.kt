package com.example.offlinevault.login

import com.example.offlinevault.security.DataEncryptionKey
import com.example.offlinevault.security.Utf8Codec
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-GCM history record with the version-independent, exact history AAD contract. */
public class PasswordHistoryCipher(
    private val random: SecureRandom = SecureRandom(),
) {
    public fun encrypt(
        entryUuid: String,
        index: Int,
        password: CharArray,
        changedAt: Long,
        dek: DataEncryptionKey,
    ): EncryptedPasswordHistory {
        val passwordBytes = Utf8Codec.encode(password)
        val plaintext = ByteBuffer.allocate(Long.SIZE_BYTES + passwordBytes.size)
            .putLong(changedAt)
            .put(passwordBytes)
            .array()
        passwordBytes.fill(0)
        return try {
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val key = dek.copyBytes()
            try {
                val ciphertext = crypt(Cipher.ENCRYPT_MODE, key, nonce, plaintext, aad(entryUuid, index))
                return EncryptedPasswordHistory(index, changedAt, nonce, ciphertext)
            } finally {
                key.fill(0)
                nonce.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    @Throws(GeneralSecurityException::class)
    public fun decrypt(
        entryUuid: String,
        encrypted: EncryptedPasswordHistory,
        dek: DataEncryptionKey,
    ): PasswordHistoryItem {
        val key = dek.copyBytes()
        val plaintext = try {
            crypt(
                Cipher.DECRYPT_MODE,
                key,
                encrypted.nonce,
                encrypted.ciphertext,
                aad(entryUuid, encrypted.index),
            )
        } finally {
            key.fill(0)
        }
        return try {
            require(plaintext.size >= Long.SIZE_BYTES) { "History payload is truncated" }
            val buffer = ByteBuffer.wrap(plaintext)
            val changedAt = buffer.long
            val passwordBytes = ByteArray(buffer.remaining())
            buffer.get(passwordBytes)
            try {
                PasswordHistoryItem(encrypted.index, changedAt, Utf8Codec.decode(passwordBytes))
            } finally {
                passwordBytes.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    /** Exact required AAD: history|&lt;entryUuid&gt;|&lt;index&gt;. */
    public fun aad(entryUuid: String, index: Int): ByteArray {
        require(entryUuid.isNotEmpty() && '|' !in entryUuid) { "Invalid history entry UUID" }
        require(index >= 0) { "History index must not be negative" }
        return "history|$entryUuid|$index".encodeToByteArray()
    }

    private fun crypt(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == KEY_BYTES) { "History encryption requires AES-256" }
        require(nonce.size == NONCE_BYTES) { "History encryption requires a 12-byte nonce" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }

    public companion object {
        public const val NONCE_BYTES: Int = 12
        public const val TAG_BITS: Int = 128
        private const val KEY_BYTES: Int = 32
    }
}

public class EncryptedPasswordHistory(
    public val index: Int,
    public val changedAt: Long,
    nonce: ByteArray,
    ciphertext: ByteArray,
) {
    public val nonce: ByteArray = nonce.copyOf()
    public val ciphertext: ByteArray = ciphertext.copyOf()

    init {
        require(index >= 0) { "History index must not be negative" }
        require(this.nonce.size == PasswordHistoryCipher.NONCE_BYTES) { "Invalid history nonce" }
    }
}
