package com.example.offlinevault.session

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricPrompt
import com.example.offlinevault.autofill.AutofillAuthentication
import com.example.offlinevault.autofill.AutofillAuthenticationHandler
import com.example.offlinevault.autofill.AutofillClientTrustVerifier
import com.example.offlinevault.autofill.AutofillDependencies
import com.example.offlinevault.autofill.AutofillDependencyProvider
import com.example.offlinevault.autofill.AutofillAuthActivity
import com.example.offlinevault.autofill.ParsedAutofillRequest
import com.example.offlinevault.autofill.PackageSignatureTrustPolicy
import com.example.offlinevault.autofill.PublicSuffixList
import com.example.offlinevault.autofill.RoomVaultAutofillStore
import com.example.offlinevault.autofill.VaultAutofillStore
import com.example.offlinevault.data.VaultDatabase
import com.example.offlinevault.data.VaultDatabaseFactory
import com.example.offlinevault.login.LoginRepository
import com.example.offlinevault.media.MediaRepository
import com.example.offlinevault.media.MediaRepositoryFactory
import com.example.offlinevault.media.MediaSession
import com.example.offlinevault.security.AesGcmFieldEncryptor
import com.example.offlinevault.security.BiometricStrongBoxKeyWrapper
import com.example.offlinevault.security.BruteForceGuard
import com.example.offlinevault.security.DataEncryptionKey
import com.example.offlinevault.security.VaultKeyManager
import com.example.offlinevault.ui.LoginUiSession
import java.security.InvalidKeyException
import java.security.GeneralSecurityException
import javax.crypto.Cipher

/** Result returned by every session operation that can be initiated by a host. */
public sealed interface VaultSessionResult {
    public data object Success : VaultSessionResult

    public data class Failure(public val message: String) : VaultSessionResult
}

public class BiometricNotAllowedException(message: String) : GeneralSecurityException(message)

/** A StrongBox key that can no longer be used after biometric enrollment changed. */
public class BiometricKeyInvalidatedException(
    message: String,
    cause: Throwable? = null,
) : GeneralSecurityException(message, cause)

/** Which operation owns the cipher currently waiting for BiometricPrompt. */
private enum class PendingBiometricOperation {
    SETUP,
    UNLOCK,
}

private data class PendingBiometric(
    val operation: PendingBiometricOperation,
    val cipher: Cipher,
)

/**
 * Owns the process-local unlocked vault session.
 *
 * Setup and biometric methods intentionally split cipher preparation from
 * completion. The parent Activity passes the returned CryptoObject to
 * BiometricPrompt.authenticate(), then passes result.cryptoObject back to the
 * matching complete method. Password and recovery material is consumed and
 * cleared by this class before the method returns.
 */
public class VaultSessionCoordinator(
    context: Context,
    private val keyManager: VaultKeyManager,
    private val wrappedKeyStore: WrappedVaultKeyStore,
    private val bootGate: MasterPasswordBootGate,
    private val bruteForceGuard: BruteForceGuard,
    private val databaseFactory: VaultDatabaseFactory,
    private val fieldEncryptor: AesGcmFieldEncryptor,
    private val biometricWrapper: BiometricStrongBoxKeyWrapper,
    private val mediaRepositoryFactory: MediaRepositoryFactory,
) : AutoCloseable {
    private val appContext: Context = context.applicationContext

    private var database: VaultDatabase? = null
    private var dek: DataEncryptionKey? = null
    private var loginRepository: LoginRepository? = null
    private var mediaRepository: MediaRepository? = null
    private var pendingBiometric: PendingBiometric? = null
    private var autofillAuthenticationHandler: AutofillAuthenticationHandler? = null

    /** True until one complete wrapped-key record has been persisted. */
    @Synchronized
    public fun isSetupRequired(): Boolean {
        val stored = wrappedKeyStore.read() ?: return true
        stored.close()
        return false
    }

    @Synchronized
    public fun isUnlocked(): Boolean = loginRepository != null

    /** The repository is null while locked or before setup. */
    @Synchronized
    public fun currentLoginRepository(): LoginRepository? = loginRepository

    /** Alias for hosts that prefer a getter-shaped API. */
    @Synchronized
    public fun getCurrentLoginRepository(): LoginRepository? = loginRepository

    @Synchronized
    public fun currentMediaRepository(): MediaRepository? = mediaRepository

    /** Creates the one-time recovery phrase without retaining it. */
    public fun generateRecoveryPhrase(): CharArray = keyManager.generateRecoveryPhrase()

    /**
     * Prepares the StrongBox encryption cipher for first-launch setup. The
     * returned object must be authenticated by BiometricPrompt before
     * [completeSetup] is called.
     */
    @Synchronized
    public fun prepareSetupBiometric(): BiometricPrompt.CryptoObject {
        check(isSetupRequired()) { "Vault setup has already completed" }
        return prepareCipher(PendingBiometricOperation.SETUP) { keyManager.newBiometricEncryptionCipher() }
    }

    /** More explicit alias for Activity code. */
    public fun prepareBiometricSetup(): BiometricPrompt.CryptoObject = prepareSetupBiometric()

    /**
     * Provisions and opens the vault after setup's CryptoObject has been
     * authenticated. All three wrapped forms are persisted before the session
     * providers are installed.
     */
    @Synchronized
    public fun completeSetup(
        masterPassword: CharArray,
        recoveryPhrase: CharArray,
        authenticatedCryptoObject: BiometricPrompt.CryptoObject?,
    ): VaultSessionResult {
        val pending = takePending(PendingBiometricOperation.SETUP)
        return try {
            if (pending == null || authenticatedCryptoObject?.cipher !== pending?.cipher) {
                VaultSessionResult.Failure("Setup biometric authentication is missing or stale")
            } else if (!isSetupRequired()) {
                VaultSessionResult.Failure("Vault setup has already completed")
            } else {
                completeSetupWithCipher(masterPassword, recoveryPhrase, pending.cipher)
            }
        } finally {
            masterPassword.fill('\u0000')
            recoveryPhrase.fill('\u0000')
        }
    }

    public fun completeSetup(
        masterPassword: CharArray,
        recoveryPhrase: CharArray,
        authenticatedCryptoObject: BiometricPrompt.CryptoObject?,
        callback: (VaultSessionResult) -> Unit,
    ): VaultSessionResult = completeSetup(
        masterPassword,
        recoveryPhrase,
        authenticatedCryptoObject,
    ).also(callback)

    /** Short alias for Activity hosts that call the setup action directly. */
    public fun setup(
        masterPassword: CharArray,
        recoveryPhrase: CharArray,
        authenticatedCryptoObject: BiometricPrompt.CryptoObject?,
    ): VaultSessionResult = completeSetup(
        masterPassword,
        recoveryPhrase,
        authenticatedCryptoObject,
    )

    /**
     * Prepares a StrongBox decryption cipher for the persisted biometric wrap.
     * A master-password success from the current Android boot is required.
     */
    @Synchronized
    public fun prepareBiometricUnlock(): BiometricPrompt.CryptoObject {
        check(!isSetupRequired()) { "Vault setup is required" }
        check(!isUnlocked()) { "The vault is already unlocked" }
        check(bootGate.hasMasterPasswordSinceCurrentBoot()) {
            "Enter the master password once after reboot before using biometrics"
        }
        check(bruteForceGuard.canAttempt()) { "Password authentication is temporarily locked" }

        val stored = wrappedKeyStore.read() ?: error("Vault setup is required")
        return try {
            prepareCipher(PendingBiometricOperation.UNLOCK) {
                keyManagerBiometricDecryptionCipher(stored)
            }
        } finally {
            stored.close()
        }
    }

    /**
     * Completes a biometric unlock using the exact authenticated Cipher
     * prepared by [prepareBiometricUnlock]. Invalidated StrongBox keys are
     * rejected and never replaced implicitly.
     */
    @Synchronized
    public fun completeBiometricUnlock(
        authenticatedCryptoObject: BiometricPrompt.CryptoObject?,
    ): VaultSessionResult {
        val pending = takePending(PendingBiometricOperation.UNLOCK)
        if (pending == null || authenticatedCryptoObject?.cipher !== pending.cipher) {
            return VaultSessionResult.Failure("Biometric authentication is missing or stale")
        }
        if (!bootGate.hasMasterPasswordSinceCurrentBoot()) {
            return VaultSessionResult.Failure(
                "Enter the master password once after reboot before using biometrics",
            )
        }
        if (!bruteForceGuard.canAttempt()) {
            return VaultSessionResult.Failure("Password authentication is temporarily locked")
        }

        var candidate: DataEncryptionKey? = null
        var attempted = false
        return try {
            val stored = wrappedKeyStore.read() ?: return VaultSessionResult.Failure("Vault setup is required")
            try {
                attempted = true
                candidate = keyManager.unwrapWithBiometric(stored.biometric, pending.cipher)
            } finally {
                stored.close()
            }
            installUnlocked(candidate!!)
            bruteForceGuard.recordSuccess()
            VaultSessionResult.Success
        } catch (error: Throwable) {
            if (loginRepository != null) lock() else candidate?.close()
            if (attempted) recordFailureSafely()
            VaultSessionResult.Failure("Unable to unlock the vault biometrically")
        }
    }

    public fun completeBiometricUnlock(
        authenticatedCryptoObject: BiometricPrompt.CryptoObject?,
        callback: (VaultSessionResult) -> Unit,
    ): VaultSessionResult = completeBiometricUnlock(authenticatedCryptoObject).also(callback)

    /** Short alias for the parent Activity's biometric callback. */
    public fun completeBiometric(
        authenticatedCryptoObject: BiometricPrompt.CryptoObject?,
    ): VaultSessionResult = completeBiometricUnlock(authenticatedCryptoObject)

    /** Short alias for preparing the persisted biometric unlock cipher. */
    public fun prepareBiometric(): BiometricPrompt.CryptoObject = prepareBiometricUnlock()

    /**
     * Unwraps the DEK with the master password, applies the persistent
     * BruteForceGuard, opens SQLCipher Room, and installs both process-local
     * consumers. The supplied password is wiped before return.
     */
    @Synchronized
    public fun unlockWithMasterPassword(password: CharArray): VaultSessionResult {
        try {
            if (isSetupRequired()) return VaultSessionResult.Failure("Vault setup is required")
            if (isUnlocked()) return VaultSessionResult.Failure("The vault is already unlocked")
            if (!bruteForceGuard.canAttempt()) {
                return VaultSessionResult.Failure("Password authentication is temporarily locked")
            }

            var candidate: DataEncryptionKey? = null
            var attempted = false
            return try {
                val stored = wrappedKeyStore.read() ?: return VaultSessionResult.Failure("Vault setup is required")
                try {
                    attempted = true
                    candidate = keyManager.unwrapWithMasterPassword(stored.masterPassword, password)
                } finally {
                    stored.close()
                }
                installUnlocked(candidate!!)
                bruteForceGuard.recordSuccess()
                bootGate.markMasterPasswordEntered()
                VaultSessionResult.Success
            } catch (error: Throwable) {
                if (loginRepository != null) lock() else candidate?.close()
                if (attempted) recordFailureSafely()
                VaultSessionResult.Failure("Invalid master password or unavailable vault")
            }
        } finally {
            password.fill('\u0000')
        }
    }

    public fun unlockWithMasterPassword(
        password: CharArray,
        callback: (VaultSessionResult) -> Unit,
    ): VaultSessionResult = unlockWithMasterPassword(password).also(callback)

    /** Short alias for the password action. */
    public fun unlock(password: CharArray): VaultSessionResult = unlockWithMasterPassword(password)

    /**
     * Installs the parent-provided autofill authentication handoff. The
     * callback is cleared when the session locks; an absent callback remains
     * fail-closed through AutofillAuthentication's default.
     */
    @Synchronized
    public fun installAutofillAuthentication(handler: AutofillAuthenticationHandler?) {
        autofillAuthenticationHandler = handler
        if (isUnlocked()) {
            if (handler == null) AutofillAuthentication.reset()
            else AutofillAuthentication.install(handler)
        }
    }

    /** Locks, clears process-local providers, closes Room, and wipes the DEK. */
    @Synchronized
    public fun lock() {
        pendingBiometric = null
        LoginUiSession.lock()
        MediaSession.clear()
        AutofillDependencies.reset()
        AutofillAuthentication.reset()
        autofillAuthenticationHandler = null
        loginRepository = null
        mediaRepository = null
        val openDatabase = database
        val openDek = dek
        database = null
        dek = null
        try {
            openDatabase?.close()
        } finally {
            openDek?.close()
        }
    }

    override fun close() {
        lock()
    }

    private fun completeSetupWithCipher(
        masterPassword: CharArray,
        recoveryPhrase: CharArray,
        authenticatedCipher: Cipher,
    ): VaultSessionResult {
        var keys: com.example.offlinevault.security.WrappedVaultKeys? = null
        var sessionDek: DataEncryptionKey? = null
        return try {
            keys = keyManager.provision(masterPassword, recoveryPhrase, authenticatedCipher)
            wrappedKeyStore.write(keys)

            val material = keys.dek.copyBytes()
            sessionDek = DataEncryptionKey(material)
            material.fill(0)
            installUnlocked(sessionDek!!)
            sessionDek = null // ownership moved to the unlocked session
            bootGate.markMasterPasswordEntered()
            VaultSessionResult.Success
        } catch (error: Throwable) {
            sessionDek?.close()
            if (loginRepository != null) lock()
            VaultSessionResult.Failure(error.message ?: "Unable to complete vault setup")
        } finally {
            keys?.close()
        }
    }

    private fun prepareCipher(
        operation: PendingBiometricOperation,
        factory: () -> Cipher,
    ): BiometricPrompt.CryptoObject {
        pendingBiometric = null
        val cipher = try {
            factory()
        } catch (error: Throwable) {
            throw error.asBiometricInvalidationIfNeeded()
        }
        pendingBiometric = PendingBiometric(operation, cipher)
        return BiometricPrompt.CryptoObject(cipher)
    }

    private fun keyManagerBiometricDecryptionCipher(
        stored: PersistedWrappedVaultKeys,
    ): Cipher = try {
        require(stored.biometric.keyAlias == biometricWrapper.keyAlias) {
            "The persisted biometric key alias does not match the configured StrongBox key"
        }
        biometricWrapper.newDecryptionCipher(stored.biometric)
    } catch (error: Throwable) {
        throw error.asBiometricInvalidationIfNeeded()
    }

    private fun takePending(operation: PendingBiometricOperation): PendingBiometric? {
        val current = pendingBiometric
        pendingBiometric = null
        return current?.takeIf { it.operation == operation }
    }

    private fun installUnlocked(unlockedDek: DataEncryptionKey) {
        check(loginRepository == null) { "The vault is already unlocked" }
        var openedDatabase: VaultDatabase? = null
        var openedMediaRepository: MediaRepository? = null
        try {
            openedDatabase = databaseFactory.open(unlockedDek)
            val repository = LoginRepository(openedDatabase, fieldEncryptor, unlockedDek)
            openedMediaRepository = mediaRepositoryFactory.open(openedDatabase, unlockedDek)
            val suffixList = PublicSuffixList.fromAssets(appContext.assets)
            val autofillStore = RoomVaultAutofillStore(
                database = openedDatabase,
                fieldEncryptor = fieldEncryptor,
                dek = unlockedDek,
                publicSuffixList = suffixList,
            )
            database = openedDatabase
            dek = unlockedDek
            loginRepository = repository
            mediaRepository = openedMediaRepository
            MediaSession.install(openedMediaRepository)
            LoginUiSession.install { repository }
            AutofillDependencies.install(UnlockedAutofillDependencyProvider(autofillStore))
            autofillAuthenticationHandler?.let(AutofillAuthentication::install)
                ?: AutofillAuthentication.reset()
            openedMediaRepository = null
        } catch (error: Throwable) {
            try {
                MediaSession.clear()
                openedMediaRepository?.close()
                openedDatabase?.close()
            } finally {
                unlockedDek.close()
            }
            throw error
        }
    }

    private fun recordFailureSafely() {
        runCatching { bruteForceGuard.recordFailure() }
    }

    private fun Throwable.asBiometricInvalidationIfNeeded(): Throwable {
        if (generateSequence(this) { it.cause }.any { cause ->
                cause is KeyPermanentlyInvalidatedException ||
                    (cause is InvalidKeyException && cause.message?.contains("invalidat", ignoreCase = true) == true)
            }
        ) {
            return BiometricKeyInvalidatedException(
                "The StrongBox biometric key was invalidated; setup is required again",
                this,
            )
        }
        return this
    }

    private class UnlockedAutofillDependencyProvider(
        private val store: VaultAutofillStore,
    ) : AutofillDependencyProvider {
        override fun vaultStore(context: Context): VaultAutofillStore = store

        override fun clientTrust(context: Context): AutofillClientTrustVerifier =
            PackageSignatureTrustPolicy(context.packageManager)

        override fun authenticationIntentSender(
            context: Context,
            request: ParsedAutofillRequest,
        ): android.content.IntentSender = AutofillAuthActivity.createIntentSender(
            context = context,
            action = AutofillAuthActivity.ACTION_UNLOCK,
        )
    }
}
