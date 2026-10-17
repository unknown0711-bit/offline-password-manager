package com.example.offlinevault.ui.setup

import com.example.offlinevault.security.Bip39RecoveryPhraseGenerator

/** The screens in the first-launch setup flow. */
public enum class SetupStep {
    PASSWORD,
    RECOVERY,
    COMPLETE,
}

/** A deliberately small, local password-strength estimate for immediate UI feedback. */
public enum class PasswordStrength(
    public val label: String,
    public val score: Int,
) {
    EMPTY("EMPTY", 0),
    WEAK("WEAK", 1),
    FAIR("FAIR", 2),
    GOOD("GOOD", 3),
    STRONG("STRONG", 4),
}

public data class PasswordStrengthEstimate(
    public val strength: PasswordStrength,
    public val message: String,
) {
    public val score: Int get() = strength.score
}

/**
 * Estimates strength without retaining the supplied password. This is feedback,
 * not a password policy or a replacement for the security package's KDF policy.
 */
public object MasterPasswordStrengthEstimator {
    public fun estimate(password: CharArray): PasswordStrengthEstimate {
        if (password.isEmpty()) {
            return PasswordStrengthEstimate(PasswordStrength.EMPTY, "Enter a master password")
        }

        var score = 0
        if (password.size >= 8) score++
        if (password.size >= MIN_RECOMMENDED_LENGTH) score++
        if (password.any(Char::isLowerCase)) score++
        if (password.any(Char::isUpperCase)) score++
        if (password.any(Char::isDigit)) score++
        if (password.any { !it.isLetterOrDigit() }) score++

        val strength = when {
            score <= 1 -> PasswordStrength.WEAK
            score <= 3 -> PasswordStrength.FAIR
            score == 4 -> PasswordStrength.GOOD
            else -> PasswordStrength.STRONG
        }
        val message = when (strength) {
            PasswordStrength.WEAK -> "Use 12+ characters with more variety"
            PasswordStrength.FAIR -> "Add length and character variety"
            PasswordStrength.GOOD -> "Good foundation; longer is better"
            PasswordStrength.STRONG -> "Strong master password"
            PasswordStrength.EMPTY -> "Enter a master password"
        }
        return PasswordStrengthEstimate(strength, message)
    }

    public const val MIN_RECOMMENDED_LENGTH: Int = 12
}

/** Supplies one freshly allocated, space-separated 24-word BIP39 phrase. */
public fun interface RecoveryPhraseSource {
    public fun generate(): CharArray
}

/** Adapter for the security package's BIP39 generator. */
public class Bip39RecoveryPhraseSource(
    private val generator: Bip39RecoveryPhraseGenerator,
) : RecoveryPhraseSource {
    override fun generate(): CharArray = generator.generate()
}

/** Useful for wiring a known phrase in tests; the source still returns a copy. */
public class FixedRecoveryPhraseSource(
    phrase: CharArray,
) : RecoveryPhraseSource {
    private val phraseCopy: CharArray = phrase.copyOf()

    override fun generate(): CharArray = phraseCopy.copyOf()
}

/**
 * UI-only state. Passwords are intentionally absent. Recovery words are held as
 * character arrays until the one-time setup callback consumes and wipes them.
 */
public data class SetupUiState(
    public val step: SetupStep = SetupStep.PASSWORD,
    public val passwordStrength: PasswordStrengthEstimate =
        PasswordStrengthEstimate(PasswordStrength.EMPTY, "Enter a master password"),
    public val passwordsMatch: Boolean = false,
    public val passwordError: String? = null,
    public val recoveryPhrase: List<CharArray> = emptyList(),
    public val confirmationIndexes: List<Int> = emptyList(),
    public val confirmedSlots: Set<Int> = emptySet(),
    public val recoveryConfirmed: Boolean = false,
    public val recoveryError: String? = null,
    public val loading: Boolean = false,
    public val error: String? = null,
)
