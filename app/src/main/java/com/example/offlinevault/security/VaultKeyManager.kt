package com.example.offlinevault.security

import javax.crypto.Cipher

/**
 * The in-memory result of provisioning a vault. Call [close] as soon as the
 * current unlock operation and database session are finished.
 */
public class WrappedVaultKeys(
    public val dek: DataEncryptionKey,
    public val masterPassword: Argon2idWrappedDek,
    public val biometric: StrongBoxWrappedDek,
    public val recoveryPhrase: Bip39WrappedDek,
) : AutoCloseable {
    override fun close() {
        dek.close()
        masterPassword.salt.fill(0)
        masterPassword.nonce.fill(0)
        masterPassword.ciphertext.fill(0)
        biometric.nonce.fill(0)
        biometric.ciphertext.fill(0)
        recoveryPhrase.nonce.fill(0)
        recoveryPhrase.ciphertext.fill(0)
    }
}

/**
 * Coordinates the three independent DEK wrapping mechanisms. Biometric
 * wrapping is deliberately split into cipher preparation and completion so a
 * future UI can pass the cipher through BiometricPrompt.CryptoObject without
 * moving a password or DEK through a String.
 */
public class VaultKeyManager(
    private val dekGenerator: DekGenerator,
    private val passwordWrapper: Argon2idPasswordWrapper,
    private val recoveryWrapper: Bip39RecoveryPhraseWrapper,
    private val biometricWrapper: BiometricStrongBoxKeyWrapper,
    private val recoveryPhraseGenerator: Bip39RecoveryPhraseGenerator,
) {
    public fun generateRecoveryPhrase(): CharArray = recoveryPhraseGenerator.generate()

    /** Create an authenticated StrongBox cipher to give to BiometricPrompt. */
    public fun newBiometricEncryptionCipher(): Cipher = biometricWrapper.newEncryptionCipher()

    /**
     * Completes provisioning after BiometricPrompt has authenticated the cipher.
     * The caller owns [masterPassword] and [recoveryPhrase]; this method never
     * stores either input.
     */
    public fun provision(
        masterPassword: CharArray,
        recoveryPhrase: CharArray,
        authenticatedBiometricCipher: Cipher,
    ): WrappedVaultKeys {
        val dek = dekGenerator.generate()
        return try {
            val passwordWrapped = passwordWrapper.wrap(dek, masterPassword)
            val recoveryWrapped = recoveryWrapper.wrap(dek, recoveryPhrase, CharArray(0))
            val biometricWrapped = biometricWrapper.wrapWithCipher(dek, authenticatedBiometricCipher)
            WrappedVaultKeys(
                dek = dek,
                masterPassword = passwordWrapped,
                biometric = biometricWrapped,
                recoveryPhrase = recoveryWrapped,
            )
        } catch (error: Throwable) {
            dek.close()
            throw error
        }
    }

    /** Unwrap helpers used by the future unlock flow. */
    public fun unwrapWithMasterPassword(
        wrapped: Argon2idWrappedDek,
        masterPassword: CharArray,
    ): DataEncryptionKey = passwordWrapper.unwrap(wrapped, masterPassword)

    public fun unwrapWithRecoveryPhrase(
        wrapped: Bip39WrappedDek,
        recoveryPhrase: CharArray,
    ): DataEncryptionKey = recoveryWrapper.unwrap(wrapped, recoveryPhrase, CharArray(0))

    public fun unwrapWithBiometric(
        wrapped: StrongBoxWrappedDek,
        authenticatedCipher: Cipher,
    ): DataEncryptionKey = biometricWrapper.unwrapWithCipher(wrapped, authenticatedCipher)

}
