package com.example.offlinevault.security

import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * An encrypted field. Ciphertext contains the 16-byte GCM authentication tag.
 * The nonce is intentionally stored separately so it can be indexed or encoded
 * without parsing the ciphertext.
 */
public class EncryptedField(
    nonce: ByteArray,
    ciphertext: ByteArray,
    public val schemaVersion: Int,
) {
    public val nonce: ByteArray = nonce.copyOf()
    public val ciphertext: ByteArray = ciphertext.copyOf()

    init {
        require(nonce.size == AES_GCM_NONCE_BYTES) { "AES-GCM requires a 12-byte nonce" }
        require(schemaVersion >= 0) { "schemaVersion must not be negative" }
    }
}

/** AES-256-GCM field encryption with a fresh 96-bit nonce for every field. */
public class AesGcmFieldEncryptor(
    private val random: SecureRandom = SecureRandom(),
) {
    public fun encrypt(
        table: String,
        entryUuid: String,
        field: String,
        schemaVersion: Int,
        plaintext: ByteArray,
        dek: DataEncryptionKey,
    ): EncryptedField {
        val key = dek.copyBytes()
        return try {
            val sealed = AesGcm.encrypt(
                key = key,
                plaintext = plaintext,
                associatedData = associatedData(table, entryUuid, field, schemaVersion),
                random = random,
            )
            EncryptedField(sealed.nonce, sealed.ciphertext, schemaVersion)
        } finally {
            key.fill(0)
        }
    }

    @Throws(GeneralSecurityException::class)
    public fun decrypt(
        table: String,
        entryUuid: String,
        field: String,
        encrypted: EncryptedField,
        dek: DataEncryptionKey,
    ): ByteArray {
        val key = dek.copyBytes()
        return try {
            AesGcm.decrypt(
                key = key,
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
                associatedData = associatedData(table, entryUuid, field, encrypted.schemaVersion),
            )
        } finally {
            key.fill(0)
        }
    }

    /**
     * Exact wire AAD format: table|entryUuid|field|schemaVersion.
     * Separators are rejected in components to prevent ambiguous encodings.
     */
    public fun associatedData(
        table: String,
        entryUuid: String,
        field: String,
        schemaVersion: Int,
    ): ByteArray {
        require(schemaVersion >= 0) { "schemaVersion must not be negative" }
        require(table.isNotEmpty() && entryUuid.isNotEmpty() && field.isNotEmpty()) {
            "AAD components must not be empty"
        }
        require('|' !in table && '|' !in entryUuid && '|' !in field) {
            "AAD components must not contain |"
        }
        return "$table|$entryUuid|$field|$schemaVersion".encodeToByteArray()
    }
}
