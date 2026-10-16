package com.example.offlinevault.security

import java.security.SecureRandom

/**
 * An in-memory data-encryption key. The material is copied on entry and can be
 * explicitly cleared when the caller is finished with it.
 */
public class DataEncryptionKey(material: ByteArray) : AutoCloseable {
    private var value: ByteArray?

    init {
        require(material.size == AES_256_KEY_BYTES) {
            "A DEK must be exactly 32 bytes (AES-256)"
        }
        value = material.copyOf()
    }

    public val isClosed: Boolean
        get() = value == null

    /** Returns a copy. The caller owns and should clear the returned array. */
    public fun copyBytes(): ByteArray =
        value?.copyOf() ?: throw IllegalStateException("The DEK has been closed")

    override fun close() {
        value?.fill(0)
        value = null
    }
}

public interface DekGenerator {
    public fun generate(): DataEncryptionKey
}

public class SecureRandomDekGenerator(
    private val random: SecureRandom = SecureRandom(),
) : DekGenerator {
    override fun generate(): DataEncryptionKey {
        val material = ByteArray(AES_256_KEY_BYTES)
        random.nextBytes(material)
        return try {
            DataEncryptionKey(material)
        } finally {
            material.fill(0)
        }
    }
}

/** A wrapper that does not need a user secret in addition to the wrapped DEK. */
public interface DekWrapper<Wrapped> {
    public fun wrap(dek: DataEncryptionKey): Wrapped

    public fun unwrap(wrapped: Wrapped): DataEncryptionKey
}

/** A wrapper whose unwrapping secret is supplied as a transient character array. */
public interface PasswordDekWrapper<Wrapped> {
    public fun wrap(dek: DataEncryptionKey, password: CharArray): Wrapped

    public fun unwrap(wrapped: Wrapped, password: CharArray): DataEncryptionKey
}

internal const val AES_256_KEY_BYTES: Int = 32
internal const val AES_GCM_NONCE_BYTES: Int = 12
internal const val AES_GCM_TAG_BITS: Int = 128
