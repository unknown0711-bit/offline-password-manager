package com.example.offlinevault.ui.lock

import com.example.offlinevault.security.BruteForceGuard

/** Read-only lockout data needed by the lock screen. */
public data class LockoutSnapshot(
    public val isLocked: Boolean = false,
    public val remainingLockoutMillis: Long = 0L,
)

public fun interface LockoutStateSource {
    public fun current(): LockoutSnapshot
}

/** Small UI adapter; lockout persistence remains owned by BruteForceGuard. */
public class BruteForceGuardLockoutSource(
    private val guard: BruteForceGuard,
) : LockoutStateSource {
    override fun current(): LockoutSnapshot = guard.state().let { state ->
        LockoutSnapshot(
            isLocked = state.isLocked,
            remainingLockoutMillis = state.remainingLockoutMillis,
        )
    }
}

public sealed interface AuthenticationResult {
    public data object Success : AuthenticationResult

    public data class Failure(
        public val message: String = "Authentication failed",
    ) : AuthenticationResult
}

public fun interface PasswordAuthenticator {
    public suspend fun authenticate(password: CharArray): AuthenticationResult
}

public fun interface BiometricAuthenticator {
    public suspend fun authenticate(): AuthenticationResult
}

public data class LockUiState(
    public val loading: Boolean = true,
    public val authenticating: Boolean = false,
    public val lockout: LockoutSnapshot = LockoutSnapshot(),
    public val countdownTotalMillis: Long = 30_000L,
    public val biometricAvailable: Boolean = false,
    public val error: String? = null,
    public val unlocked: Boolean = false,
) {
    public val isLocked: Boolean get() = lockout.isLocked
    public val remainingLockoutMillis: Long get() = lockout.remainingLockoutMillis
    public val countdownLabel: String
        get() = LockCountdownFormatter.format(remainingLockoutMillis, countdownTotalMillis)

}

/**
 * Formats lockout time as a fixed-width terminal progress bar. A 24-second
 * remainder in the default 30-second tier is exactly [████████░░] 24s.
 */
public object LockCountdownFormatter {
    public fun format(
        remainingMillis: Long,
        totalMillis: Long = DEFAULT_TOTAL_MILLIS,
        segments: Int = DEFAULT_SEGMENTS,
    ): String {
        require(segments > 0) { "segments must be positive" }
        val remaining = remainingMillis.coerceAtLeast(0L)
        val total = totalMillis.coerceAtLeast(1L)
        val filled = when {
            remaining == 0L -> 0
            else -> kotlin.math.ceil(
                (remaining.toDouble() / total.toDouble()).coerceIn(0.0, 1.0) * segments,
            ).toInt().coerceIn(1, segments)
        }
        val bar = "█".repeat(filled) + "░".repeat(segments - filled)
        val seconds = (remaining + 999L) / 1_000L
        return "[$bar] ${seconds}s"
    }

    private const val DEFAULT_TOTAL_MILLIS: Long = 30_000L
    private const val DEFAULT_SEGMENTS: Int = 10
}

public fun formatLockCountdown(
    remainingMillis: Long,
    totalMillis: Long = 30_000L,
    segments: Int = 10,
): String = LockCountdownFormatter.format(remainingMillis, totalMillis, segments)

/**
 * Host contract shared by setup and lock screens. Password and biometric
 * operations return an explicit result so the ViewModel can render loading and
 * error states without retaining credentials.
 */
public interface VaultUiCoordinator {
    public fun setup(masterPassword: CharArray, recoveryPhrase: CharArray)

    public suspend fun unlock(password: CharArray): AuthenticationResult

    public suspend fun password(password: CharArray): AuthenticationResult = unlock(password)

    public suspend fun biometric(): AuthenticationResult

    public fun navigateToLoginList()
}

public const val LOCK_COUNTDOWN_REFRESH_MILLIS: Long = 1_000L
