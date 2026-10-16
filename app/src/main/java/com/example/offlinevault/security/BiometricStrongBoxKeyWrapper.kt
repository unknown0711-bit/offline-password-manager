package com.example.offlinevault.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.SecretKey
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

public class StrongBoxUnavailableException(message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)

public class StrongBoxWrappedDek(
    nonce: ByteArray,
    ciphertext: ByteArray,
    public val keyAlias: String,
) {
    public val nonce: ByteArray = nonce.copyOf()
    public val ciphertext: ByteArray = ciphertext.copyOf()

    init {
        require(this.nonce.size == AES_GCM_NONCE_BYTES) { "AES-GCM nonce must be 12 bytes" }
        require(keyAlias.isNotBlank()) { "keyAlias must not be blank" }
    }
}

/**
 * AES-GCM DEK wrapping backed by an Android Keystore StrongBox key. The key is
 * configured for per-use authentication when [authenticationValiditySeconds]
 * is zero. Call [newEncryptionCipher] or [newDecryptionCipher], put that
 * Cipher in BiometricPrompt.CryptoObject, and pass the authenticated Cipher to
 * [wrapWithCipher] or [unwrapWithCipher] when the biometric callback succeeds.
 */
public class BiometricStrongBoxKeyWrapper(
    context: Context,
    public val keyAlias: String,
    private val authenticationValiditySeconds: Int = 0,
) : DekWrapper<StrongBoxWrappedDek> {
    private val appContext: Context = context.applicationContext

    init {
        require(keyAlias.isNotBlank()) { "keyAlias must not be blank" }
        require(authenticationValiditySeconds >= 0) {
            "authenticationValiditySeconds must not be negative"
        }
    }

    override fun wrap(dek: DataEncryptionKey): StrongBoxWrappedDek {
        val cipher = newEncryptionCipher()
        return wrapWithCipher(dek, cipher)
    }

    /** Uses a Cipher that has already been authenticated by BiometricPrompt. */
    public fun wrapWithCipher(dek: DataEncryptionKey, authenticatedCipher: Cipher): StrongBoxWrappedDek {
        require(authenticatedCipher.algorithm == AES_GCM_TRANSFORMATION) {
            "The Cipher must use AES/GCM/NoPadding"
        }
        require(authenticatedCipher.iv.size == AES_GCM_NONCE_BYTES) {
            "The authenticated Cipher must use a 12-byte GCM nonce"
        }
        val bytes = dek.copyBytes()
        return try {
            val ciphertext = authenticatedCipher.doFinal(bytes)
            StrongBoxWrappedDek(authenticatedCipher.iv, ciphertext, keyAlias)
        } finally {
            bytes.fill(0)
        }
    }

    override fun unwrap(wrapped: StrongBoxWrappedDek): DataEncryptionKey {
        require(wrapped.keyAlias == keyAlias) { "The wrapped DEK belongs to another key alias" }
        val cipher = newDecryptionCipher(wrapped)
        return unwrapWithCipher(wrapped, cipher)
    }

    /** Uses a Cipher that has already been authenticated by BiometricPrompt. */
    public fun unwrapWithCipher(
        wrapped: StrongBoxWrappedDek,
        authenticatedCipher: Cipher,
    ): DataEncryptionKey {
        require(wrapped.keyAlias == keyAlias) { "The wrapped DEK belongs to another key alias" }
        require(authenticatedCipher.algorithm == AES_GCM_TRANSFORMATION) {
            "The Cipher must use AES/GCM/NoPadding"
        }
        require(authenticatedCipher.iv.contentEquals(wrapped.nonce)) {
            "The authenticated Cipher nonce does not match the wrapped DEK"
        }
        val ciphertext = wrapped.ciphertext.copyOf()
        val plaintext = try {
            authenticatedCipher.doFinal(ciphertext)
        } finally {
            ciphertext.fill(0)
        }
        return try {
            DataEncryptionKey(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    /** Creates the Cipher to hand to BiometricPrompt.CryptoObject. */
    public fun newEncryptionCipher(): Cipher {
        ensureKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        return cipher
    }

    /** Creates the Cipher to hand to BiometricPrompt.CryptoObject. */
    public fun newDecryptionCipher(wrapped: StrongBoxWrappedDek): Cipher {
        require(wrapped.keyAlias == keyAlias) { "The wrapped DEK belongs to another key alias" }
        ensureKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getKey(),
            GCMParameterSpec(AES_GCM_TAG_BITS, wrapped.nonce.copyOf()),
        )
        return cipher
    }

    private fun ensureKey() {
        // AUTH_BIOMETRIC_STRONG is only available from API 30. Refusing older
        // devices is intentional: a weaker compatibility mode would violate
        // the vault's authentication contract.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw StrongBoxUnavailableException(
                "StrongBox biometric wrapping requires Android API 30 or newer",
            )
        }
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
            throw StrongBoxUnavailableException("This device does not advertise a StrongBox keystore")
        }
        val keyStore = keyStore()
        if (keyStore.containsAlias(keyAlias)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val keyInfo = SecretKeyFactory.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE,
                )
                    .getKeySpec(getKey(), KeyInfo::class.java) as KeyInfo
                val isStrongBox = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
                } else {
                    // API 30 has no public security-level accessor. The alias
                    // was created by this class with setIsStrongBoxBacked(true);
                    // still reject a key that is not hardware-backed.
                    @Suppress("DEPRECATION")
                    keyInfo.isInsideSecureHardware
                }
                if (!isStrongBox) {
                    throw StrongBoxUnavailableException(
                        "The existing keystore alias is not backed by StrongBox",
                    )
                }
            }
            return
        }

        try {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )
            val builder = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_256_KEY_BYTES * 8)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .setIsStrongBoxBacked(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    authenticationValiditySeconds,
                    KeyProperties.AUTH_BIOMETRIC_STRONG,
                )
            } else {
                @Suppress("DEPRECATION")
                val ignored = builder.setUserAuthenticationValidityDurationSeconds(
                    authenticationValiditySeconds,
                )
            }
            generator.init(builder.build())
            generator.generateKey()
        } catch (error: Exception) {
            throw StrongBoxUnavailableException(
                "Unable to create the required StrongBox biometric key; no software fallback is used",
                error,
            )
        }
    }

    private fun getKey(): SecretKey {
        val key = keyStore().getKey(keyAlias, null)
        require(key is SecretKey) { "Keystore alias does not contain an AES key" }
        return key
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
}

private const val ANDROID_KEYSTORE: String = "AndroidKeyStore"
private const val AES_GCM_TRANSFORMATION: String = "AES/GCM/NoPadding"
