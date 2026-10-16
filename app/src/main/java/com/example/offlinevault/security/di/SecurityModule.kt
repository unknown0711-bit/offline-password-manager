package com.example.offlinevault.security.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.offlinevault.security.AesGcmFieldEncryptor
import com.example.offlinevault.security.Argon2idPasswordWrapper
import com.example.offlinevault.security.Bip39MnemonicValidator
import com.example.offlinevault.security.Bip39RecoveryPhraseGenerator
import com.example.offlinevault.security.Bip39RecoveryPhraseWrapper
import com.example.offlinevault.security.Bip39WordListValidator
import com.example.offlinevault.security.BiometricStrongBoxKeyWrapper
import com.example.offlinevault.security.BootClock
import com.example.offlinevault.security.BouncyCastleArgon2idDeriver
import com.example.offlinevault.security.BruteForceGuard
import com.example.offlinevault.security.DataEncryptionKey
import com.example.offlinevault.security.DekGenerator
import com.example.offlinevault.security.SecureRandomDekGenerator
import com.example.offlinevault.security.SqlCipherKeyDeriver
import com.example.offlinevault.security.TinkInitializer
import com.example.offlinevault.security.VaultKeyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.SecureRandom
import javax.inject.Named
import javax.inject.Singleton

/** Application-scoped security primitives. No key is persisted by this module. */
@Module
@InstallIn(SingletonComponent::class)
public object SecurityModule {
    public const val BRUTE_FORCE_PREFERENCES: String = "offline_vault_brute_force"
    public const val BIOMETRIC_KEY_ALIAS: String = "offline_vault_dek_biometric_v1"

    @Provides
    @Singleton
    public fun provideTinkRegistration(): TinkRegistration {
        TinkInitializer.initialize()
        return TinkRegistration
    }

    @Provides
    @Singleton
    public fun provideSecureRandom(): SecureRandom = SecureRandom()

    @Provides
    @Singleton
    public fun provideDekGenerator(
        @Suppress("UNUSED_PARAMETER") tink: TinkRegistration,
        random: SecureRandom,
    ): DekGenerator = SecureRandomDekGenerator(random)

    @Provides
    @Singleton
    public fun provideArgon2idWrapper(
        @Suppress("UNUSED_PARAMETER") tink: TinkRegistration,
        random: SecureRandom,
    ): Argon2idPasswordWrapper = Argon2idPasswordWrapper(
        deriver = BouncyCastleArgon2idDeriver(),
        random = random,
    )

    @Provides
    @Singleton
    public fun provideBip39WordList(@ApplicationContext context: Context): List<String> =
        context.assets.open("bip39_english.txt").bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }

    @Provides
    @Singleton
    public fun provideBip39Validator(wordList: List<String>): Bip39MnemonicValidator =
        Bip39WordListValidator(wordList)

    @Provides
    @Singleton
    public fun provideRecoveryPhraseWrapper(
        validator: Bip39MnemonicValidator,
        random: SecureRandom,
    ): Bip39RecoveryPhraseWrapper = Bip39RecoveryPhraseWrapper(validator, random)

    @Provides
    @Singleton
    public fun provideRecoveryPhraseGenerator(
        wordList: List<String>,
        random: SecureRandom,
    ): Bip39RecoveryPhraseGenerator = Bip39RecoveryPhraseGenerator(wordList, random)

    @Provides
    @Singleton
    public fun provideBiometricWrapper(
        @ApplicationContext context: Context,
    ): BiometricStrongBoxKeyWrapper = BiometricStrongBoxKeyWrapper(
        context = context,
        keyAlias = BIOMETRIC_KEY_ALIAS,
    )

    @Provides
    @Singleton
    public fun provideVaultKeyManager(
        dekGenerator: DekGenerator,
        passwordWrapper: Argon2idPasswordWrapper,
        recoveryWrapper: Bip39RecoveryPhraseWrapper,
        biometricWrapper: BiometricStrongBoxKeyWrapper,
        recoveryPhraseGenerator: Bip39RecoveryPhraseGenerator,
    ): VaultKeyManager = VaultKeyManager(
        dekGenerator = dekGenerator,
        passwordWrapper = passwordWrapper,
        recoveryWrapper = recoveryWrapper,
        biometricWrapper = biometricWrapper,
        recoveryPhraseGenerator = recoveryPhraseGenerator,
    )

    @Provides
    @Singleton
    public fun provideFieldEncryptor(random: SecureRandom): AesGcmFieldEncryptor =
        AesGcmFieldEncryptor(random)

    @Provides
    @Singleton
    public fun provideSqlCipherKeyDeriver(): SqlCipherKeyDeriver = SqlCipherKeyDeriver()

    @Provides
    @Singleton
    @Named(BRUTE_FORCE_PREFERENCES)
    public fun provideEncryptedBruteForcePreferences(
        @ApplicationContext context: Context,
    ): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            BRUTE_FORCE_PREFERENCES,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Provides
    @Singleton
    public fun provideBootClock(@ApplicationContext context: Context): BootClock =
        com.example.offlinevault.security.AndroidBootClock(context)

    @Provides
    @Singleton
    public fun provideBruteForceGuard(
        @Named(BRUTE_FORCE_PREFERENCES) preferences: SharedPreferences,
        clock: BootClock,
    ): BruteForceGuard = BruteForceGuard(preferences, clock = clock)

    /** A typed Hilt dependency makes Tink initialization an explicit graph edge. */
    public object TinkRegistration
}
