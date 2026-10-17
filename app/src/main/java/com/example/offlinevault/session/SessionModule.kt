package com.example.offlinevault.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.offlinevault.data.VaultDatabaseFactory
import com.example.offlinevault.media.MediaKeysetStore
import com.example.offlinevault.media.MediaRepositoryFactory
import com.example.offlinevault.media.SharedPreferencesMediaKeysetBackend
import com.example.offlinevault.security.AesGcmFieldEncryptor
import com.example.offlinevault.security.BiometricStrongBoxKeyWrapper
import com.example.offlinevault.security.BootClock
import com.example.offlinevault.security.BruteForceGuard
import com.example.offlinevault.security.VaultKeyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import javax.inject.Named
import javax.inject.Singleton

/** Hilt bindings for the process-local vault session. */
@Module
@InstallIn(SingletonComponent::class)
public object SessionModule {
    public const val SESSION_PREFERENCES: String = "offline_vault_session"

    @Provides
    @Singleton
    @Named(SESSION_PREFERENCES)
    public fun provideEncryptedSessionPreferences(
        @ApplicationContext context: Context,
    ): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SESSION_PREFERENCES,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Provides
    @Singleton
    public fun provideWrappedVaultKeyStore(
        @Named(SESSION_PREFERENCES) preferences: SharedPreferences,
    ): WrappedVaultKeyStore = EncryptedWrappedVaultKeyStore(preferences)

    @Provides
    @Singleton
    public fun provideMediaKeysetStore(
        @Named(SESSION_PREFERENCES) preferences: SharedPreferences,
    ): MediaKeysetStore = MediaKeysetStore(SharedPreferencesMediaKeysetBackend(preferences))

    @Provides
    @Singleton
    public fun provideMediaRepositoryFactory(
        @ApplicationContext context: Context,
        fieldEncryptor: AesGcmFieldEncryptor,
        keysetStore: MediaKeysetStore,
    ): MediaRepositoryFactory = MediaRepositoryFactory(context, fieldEncryptor, keysetStore)

    @Provides
    @Singleton
    public fun provideMasterPasswordBootGate(
        @Named(SESSION_PREFERENCES) preferences: SharedPreferences,
        clock: BootClock,
    ): MasterPasswordBootGate = MasterPasswordBootGate(preferences, clock)

    @Provides
    @Singleton
    public fun provideVaultSessionCoordinator(
        @ApplicationContext context: Context,
        keyManager: VaultKeyManager,
        wrappedKeyStore: WrappedVaultKeyStore,
        bootGate: MasterPasswordBootGate,
        bruteForceGuard: BruteForceGuard,
        databaseFactory: VaultDatabaseFactory,
        fieldEncryptor: AesGcmFieldEncryptor,
        biometricWrapper: BiometricStrongBoxKeyWrapper,
        mediaRepositoryFactory: MediaRepositoryFactory,
    ): VaultSessionCoordinator = VaultSessionCoordinator(
        context = context,
        keyManager = keyManager,
        wrappedKeyStore = wrappedKeyStore,
        bootGate = bootGate,
        bruteForceGuard = bruteForceGuard,
        databaseFactory = databaseFactory,
        fieldEncryptor = fieldEncryptor,
        biometricWrapper = biometricWrapper,
        mediaRepositoryFactory = mediaRepositoryFactory,
    )
}

/** Application entry point for a parent Activity that is not constructor-injected. */
@EntryPoint
@InstallIn(SingletonComponent::class)
public interface SessionEntryPoint {
    public fun vaultSessionCoordinator(): VaultSessionCoordinator
    public fun bruteForceGuard(): BruteForceGuard
}

/** Convenience accessor for Activity code using the generated Hilt component. */
public fun sessionCoordinator(context: Context): VaultSessionCoordinator =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        SessionEntryPoint::class.java,
    ).vaultSessionCoordinator()
