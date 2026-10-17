package com.example.offlinevault.session

import android.content.SharedPreferences
import com.example.offlinevault.security.Argon2idWrappedDek
import com.example.offlinevault.security.Bip39WrappedDek
import com.example.offlinevault.security.StrongBoxWrappedDek
import com.example.offlinevault.security.WrappedVaultKeys
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The persisted part of [WrappedVaultKeys]. It deliberately has no DEK field:
 * the DEK exists only while an unlocked session is alive.
 */
public class PersistedWrappedVaultKeys(
    public val masterPassword: Argon2idWrappedDek,
    public val biometric: StrongBoxWrappedDek,
    public val recoveryPhrase: Bip39WrappedDek,
) : AutoCloseable {
    override fun close() {
        masterPassword.salt.fill(0)
        masterPassword.nonce.fill(0)
        masterPassword.ciphertext.fill(0)
        biometric.nonce.fill(0)
        biometric.ciphertext.fill(0)
        recoveryPhrase.nonce.fill(0)
        recoveryPhrase.ciphertext.fill(0)
    }
}

/** Durable store for the encrypted, independently wrapped DEK representations. */
public interface WrappedVaultKeyStore {
    /** Returns null only before first-launch setup has completed. */
    public fun read(): PersistedWrappedVaultKeys?

    /** Atomically replaces the serialized wrapped-key record. */
    public fun write(keys: WrappedVaultKeys)

    /** Removes setup state and all wrapped-key ciphertext. */
    public fun clear()
}

/**
 * SharedPreferences is supplied by the session Hilt module as an
 * EncryptedSharedPreferences instance. A single versioned binary payload keeps
 * setup state and all wrapped forms consistent if the process is interrupted.
 */
public class EncryptedWrappedVaultKeyStore(
    private val preferences: SharedPreferences,
) : WrappedVaultKeyStore {
    @Synchronized
    override fun read(): PersistedWrappedVaultKeys? {
        val encoded = preferences.getString(KEY_WRAPPED_KEYS, null) ?: return null
        return try {
            val serialized = Base64.getDecoder().decode(encoded)
            try {
                decode(serialized)
            } finally {
                serialized.fill(0)
            }
        } catch (error: Throwable) {
            throw StoredWrappedKeysCorruptException("The persisted vault key record is invalid", error)
        }
    }

    @Synchronized
    override fun write(keys: WrappedVaultKeys) {
        val serialized = encode(keys)
        try {
            val encoded = Base64.getEncoder().encodeToString(serialized)
            check(preferences.edit().putString(KEY_WRAPPED_KEYS, encoded).commit()) {
                "Unable to persist the wrapped vault keys"
            }
        } finally {
            serialized.fill(0)
        }
    }

    @Synchronized
    override fun clear() {
        check(preferences.edit().remove(KEY_WRAPPED_KEYS).commit()) {
            "Unable to clear the wrapped vault keys"
        }
    }

    private fun encode(keys: WrappedVaultKeys): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(FORMAT_VERSION)

            data.writeInt(keys.masterPassword.parameters.memoryKiB)
            data.writeInt(keys.masterPassword.parameters.iterations)
            data.writeInt(keys.masterPassword.parameters.parallelism)
            writeBytes(data, keys.masterPassword.salt)
            writeBytes(data, keys.masterPassword.nonce)
            writeBytes(data, keys.masterPassword.ciphertext)

            writeString(data, keys.biometric.keyAlias)
            writeBytes(data, keys.biometric.nonce)
            writeBytes(data, keys.biometric.ciphertext)

            writeBytes(data, keys.recoveryPhrase.nonce)
            writeBytes(data, keys.recoveryPhrase.ciphertext)
        }
        return output.toByteArray()
    }

    private fun decode(encoded: ByteArray): PersistedWrappedVaultKeys {
        require(encoded.size <= MAX_RECORD_BYTES) { "The persisted key record is too large" }
        DataInputStream(ByteArrayInputStream(encoded)).use { data ->
            require(data.readInt() == MAGIC) { "Unknown wrapped-key record" }
            require(data.readInt() == FORMAT_VERSION) { "Unsupported wrapped-key record version" }

            val parameters = com.example.offlinevault.security.Argon2idParameters(
                memoryKiB = data.readInt(),
                iterations = data.readInt(),
                parallelism = data.readInt(),
            )
            val masterSalt = readBytes(data, MAX_COMPONENT_BYTES)
            val masterNonce = readBytes(data, MAX_COMPONENT_BYTES)
            val masterCiphertext = readBytes(data, MAX_COMPONENT_BYTES)

            val keyAlias = readString(data)
            val biometricNonce = readBytes(data, MAX_COMPONENT_BYTES)
            val biometricCiphertext = readBytes(data, MAX_COMPONENT_BYTES)

            val recoveryNonce = readBytes(data, MAX_COMPONENT_BYTES)
            val recoveryCiphertext = readBytes(data, MAX_COMPONENT_BYTES)
            require(data.available() == 0) { "Trailing wrapped-key record data" }

            return try {
                PersistedWrappedVaultKeys(
                    masterPassword = Argon2idWrappedDek(
                        salt = masterSalt,
                        nonce = masterNonce,
                        ciphertext = masterCiphertext,
                        parameters = parameters,
                    ),
                    biometric = StrongBoxWrappedDek(
                        nonce = biometricNonce,
                        ciphertext = biometricCiphertext,
                        keyAlias = keyAlias,
                    ),
                    recoveryPhrase = Bip39WrappedDek(
                        nonce = recoveryNonce,
                        ciphertext = recoveryCiphertext,
                    ),
                )
            } finally {
                masterSalt.fill(0)
                masterNonce.fill(0)
                masterCiphertext.fill(0)
                biometricNonce.fill(0)
                biometricCiphertext.fill(0)
                recoveryNonce.fill(0)
                recoveryCiphertext.fill(0)
            }
        }
    }

    private fun writeBytes(output: DataOutputStream, value: ByteArray) {
        require(value.size <= MAX_COMPONENT_BYTES) { "Wrapped-key component is too large" }
        output.writeInt(value.size)
        output.write(value)
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        try {
            writeBytes(output, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun readBytes(input: DataInputStream, maximum: Int): ByteArray {
        val size = input.readInt()
        require(size in 0..maximum) { "Invalid wrapped-key component length" }
        return ByteArray(size).also(input::readFully)
    }

    private fun readString(input: DataInputStream): String {
        val bytes = readBytes(input, MAX_ALIAS_BYTES)
        return try {
            String(bytes, StandardCharsets.UTF_8).also {
                require(it.isNotBlank()) { "The StrongBox key alias is blank" }
            }
        } finally {
            bytes.fill(0)
        }
    }

    public companion object {
        public const val KEY_WRAPPED_KEYS: String = "wrapped_vault_keys_v1"

        private const val MAGIC: Int = 0x4f56534b // OVSK
        private const val FORMAT_VERSION: Int = 1
        private const val MAX_ALIAS_BYTES: Int = 512
        private const val MAX_COMPONENT_BYTES: Int = 1 shl 20
        private const val MAX_RECORD_BYTES: Int = 4 shl 20
    }
}

public class StoredWrappedKeysCorruptException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Reboot-bound marker used to gate all biometric unlock attempts. */
public class MasterPasswordBootGate(
    private val preferences: SharedPreferences,
    private val clock: com.example.offlinevault.security.BootClock,
) {
    @Synchronized
    public fun markMasterPasswordEntered() {
        val boot = clock.bootCount().coerceAtLeast(0L)
        check(preferences.edit().putLong(KEY_MASTER_PASSWORD_BOOT, boot).commit()) {
            "Unable to persist the master-password boot marker"
        }
    }

    @Synchronized
    public fun hasMasterPasswordSinceCurrentBoot(): Boolean {
        if (!preferences.contains(KEY_MASTER_PASSWORD_BOOT)) return false
        return preferences.getLong(KEY_MASTER_PASSWORD_BOOT, Long.MIN_VALUE) ==
            clock.bootCount().coerceAtLeast(0L)
    }

    @Synchronized
    public fun clear() {
        check(preferences.edit().remove(KEY_MASTER_PASSWORD_BOOT).commit()) {
            "Unable to clear the master-password boot marker"
        }
    }

    public companion object {
        public const val KEY_MASTER_PASSWORD_BOOT: String = "master_password_boot_count"
    }
}
