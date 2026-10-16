package com.example.offlinevault.security

import java.security.GeneralSecurityException
import java.security.SecureRandom

public data class Argon2idParameters(
    public val memoryKiB: Int = 64 * 1024,
    public val iterations: Int = 3,
    public val parallelism: Int = 1,
) {
    init {
        require(memoryKiB >= 8) { "Argon2id memory must be at least 8 KiB" }
        require(iterations >= 1) { "Argon2id iterations must be positive" }
        require(parallelism >= 1) { "Argon2id parallelism must be positive" }
    }
}

/**
 * Injection point for the Argon2id library selected by the application.
 * Android/JCA does not provide Argon2id. An implementation should derive
 * exactly [outputLengthBytes] bytes and must not retain [password] or [salt].
 */
public fun interface Argon2idDeriver {
    public fun derive(
        password: CharArray,
        salt: ByteArray,
        parameters: Argon2idParameters,
        outputLengthBytes: Int,
    ): ByteArray
}

public class Argon2idWrappedDek(
    salt: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
    public val parameters: Argon2idParameters,
) {
    public val salt: ByteArray = salt.copyOf()
    public val nonce: ByteArray = nonce.copyOf()
    public val ciphertext: ByteArray = ciphertext.copyOf()

    init {
        require(this.salt.size == ARGON2_SALT_BYTES) { "Argon2id salt must be 16 bytes" }
        require(this.nonce.size == AES_GCM_NONCE_BYTES) { "AES-GCM nonce must be 12 bytes" }
    }
}

/** Wraps a DEK with an Argon2id-derived AES-256-GCM key. */
public class Argon2idPasswordWrapper(
    private val deriver: Argon2idDeriver,
    private val parameters: Argon2idParameters = Argon2idParameters(),
    private val random: SecureRandom = SecureRandom(),
) : PasswordDekWrapper<Argon2idWrappedDek> {
    override fun wrap(dek: DataEncryptionKey, password: CharArray): Argon2idWrappedDek {
        require(password.isNotEmpty()) { "The password must not be empty" }
        val salt = ByteArray(ARGON2_SALT_BYTES)
        random.nextBytes(salt)
        val passwordCopy = password.copyOf()
        return try {
            val kek = derive(passwordCopy, salt, parameters)
            try {
                val plaintext = dek.copyBytes()
                try {
                    val sealed = AesGcm.encrypt(
                        key = kek,
                        plaintext = plaintext,
                        associatedData = associatedData(parameters),
                        random = random,
                    )
                    Argon2idWrappedDek(salt, sealed.nonce, sealed.ciphertext, parameters)
                } finally {
                    plaintext.fill(0)
                }
            } finally {
                kek.fill(0)
            }
        } finally {
            passwordCopy.fill('\u0000')
            salt.fill(0)
        }
    }

    @Throws(GeneralSecurityException::class)
    override fun unwrap(wrapped: Argon2idWrappedDek, password: CharArray): DataEncryptionKey {
        require(password.isNotEmpty()) { "The password must not be empty" }
        val passwordCopy = password.copyOf()
        return try {
            val kek = derive(passwordCopy, wrapped.salt, wrapped.parameters)
            try {
                val plaintext = AesGcm.decrypt(
                    key = kek,
                    nonce = wrapped.nonce,
                    ciphertext = wrapped.ciphertext,
                    associatedData = associatedData(wrapped.parameters),
                )
                try {
                    DataEncryptionKey(plaintext)
                } finally {
                    plaintext.fill(0)
                }
            } finally {
                kek.fill(0)
            }
        } finally {
            passwordCopy.fill('\u0000')
        }
    }

    private fun derive(
        password: CharArray,
        salt: ByteArray,
        parameters: Argon2idParameters,
    ): ByteArray {
        val saltCopy = salt.copyOf()
        val result = try {
            deriver.derive(password, saltCopy, parameters, AES_256_KEY_BYTES)
        } finally {
            saltCopy.fill(0)
        }
        require(result.size == AES_256_KEY_BYTES) {
            "The Argon2id implementation must return 32 bytes"
        }
        return result
    }

    private fun associatedData(parameters: Argon2idParameters): ByteArray =
        "offlinevault|argon2id-dek|1|${parameters.memoryKiB}|${parameters.iterations}|${parameters.parallelism}"
            .encodeToByteArray()
}

private const val ARGON2_SALT_BYTES: Int = 16
