package com.example.offlinevault.security

import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.content.Context

public data class LockoutThreshold(
    public val failureCount: Int,
    public val durationMillis: Long,
) {
    init {
        require(failureCount > 0) { "failureCount must be positive" }
        require(durationMillis > 0) { "durationMillis must be positive" }
    }
}

public data class BruteForceState(
    public val failureCount: Int,
    public val lockoutUntilElapsedRealtime: Long,
    public val lockoutBootCount: Long,
    public val isLocked: Boolean,
    public val remainingLockoutMillis: Long,
)

/** Monotonic elapsed time plus Android's persistent boot counter. */
public interface BootClock {
    public fun elapsedRealtimeMillis(): Long
    public fun bootCount(): Long
}

public class AndroidBootClock(private val context: Context) : BootClock {
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()

    override fun bootCount(): Long = runCatching {
        Settings.Global.getLong(
            context.contentResolver,
            Settings.Global.BOOT_COUNT,
        ).toLong()
    }.getOrElse {
        // A reboot counter is a security input. Silently treating an unavailable
        // counter as zero could weaken a pending lockout, so fail closed.
        throw IllegalStateException("Android BOOT_COUNT is unavailable", it)
    }
}

/**
 * Persistent lockout state for password attempts.
 *
 * The persisted deadline is deliberately a pair of values:
 * [KEY_LOCKOUT_UNTIL_ELAPSED] plus [KEY_LOCKOUT_BOOT_COUNT].  elapsedRealtime
 * cannot be compared across reboots, so a reboot re-applies the complete tier
 * duration that was active before shutdown. This never shortens an outstanding
 * lockout because a device was rebooted.
 *
 * The SharedPreferences instance supplied by production DI is an
 * EncryptedSharedPreferences instance. Tests may supply an in-memory fake.
 */
public class BruteForceGuard(
    private val preferences: SharedPreferences,
    thresholds: List<LockoutThreshold> = DEFAULT_THRESHOLDS,
    private val clock: BootClock,
) {
    private val thresholds: List<LockoutThreshold> = thresholds
        .sortedBy { it.failureCount }
        .also { sorted ->
            require(sorted.isNotEmpty()) { "At least one lockout threshold is required" }
            require(sorted.map { it.failureCount }.toSet().size == sorted.size) {
                "Lockout thresholds must have unique failure counts"
            }
        }

    @Synchronized
    public fun state(): BruteForceState {
        val now = clock.elapsedRealtimeMillis().coerceAtLeast(0L)
        val currentBoot = clock.bootCount().coerceAtLeast(0L)
        val stored = readState()

        if (stored.lockoutUntilElapsedRealtime <= 0L || stored.lockoutBootCount == currentBoot) {
            return toState(stored, now, currentBoot)
        }

        // A pending lockout was written on an earlier boot. Re-apply the full
        // duration for the highest tier represented by the persisted failure count.
        val threshold = thresholds.lastOrNull { it.failureCount <= stored.failureCount }
        if (threshold == null) {
            return toState(stored, now, currentBoot)
        }
        val reappliedUntil = safeAdd(now, threshold.durationMillis)
        val reapplied = stored.copy(
            lockoutUntilElapsedRealtime = reappliedUntil,
            lockoutBootCount = currentBoot,
            lockoutDurationMillis = threshold.durationMillis,
        )
        persist(reapplied)
        return toState(reapplied, now, currentBoot)
    }

    @Synchronized
    public fun canAttempt(): Boolean = !state().isLocked

    /** Records one failed attempt, or leaves state unchanged while locked. */
    @Synchronized
    public fun recordFailure(): BruteForceState {
        val current = state()
        if (current.isLocked) return current

        val count = if (current.failureCount == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            current.failureCount + 1
        }
        val threshold = thresholds.lastOrNull { it.failureCount <= count }
        val now = clock.elapsedRealtimeMillis().coerceAtLeast(0L)
        val boot = clock.bootCount().coerceAtLeast(0L)
        val next = PersistedState(
            failureCount = count,
            lockoutUntilElapsedRealtime = threshold?.let { safeAdd(now, it.durationMillis) } ?: 0L,
            lockoutBootCount = boot,
            lockoutDurationMillis = threshold?.durationMillis ?: 0L,
        )
        persist(next)
        return toState(next, now, boot)
    }

    /** Clears failures after a successful password or biometric authentication. */
    @Synchronized
    public fun recordSuccess() {
        check(preferences.edit()
            .remove(KEY_FAILURE_COUNT)
            .remove(KEY_LOCKOUT_UNTIL_ELAPSED)
            .remove(KEY_LOCKOUT_BOOT_COUNT)
            .remove(KEY_LOCKOUT_DURATION)
            .commit()) { "Unable to persist brute-force reset" }
    }

    private fun readState(): PersistedState = PersistedState(
        failureCount = preferences.getInt(KEY_FAILURE_COUNT, 0).coerceAtLeast(0),
        lockoutUntilElapsedRealtime = preferences
            .getLong(KEY_LOCKOUT_UNTIL_ELAPSED, 0L)
            .coerceAtLeast(0L),
        lockoutBootCount = preferences.getLong(KEY_LOCKOUT_BOOT_COUNT, 0L).coerceAtLeast(0L),
        lockoutDurationMillis = preferences.getLong(KEY_LOCKOUT_DURATION, 0L).coerceAtLeast(0L),
    )

    private fun persist(state: PersistedState) {
        check(preferences.edit()
            .putInt(KEY_FAILURE_COUNT, state.failureCount)
            .putLong(KEY_LOCKOUT_UNTIL_ELAPSED, state.lockoutUntilElapsedRealtime)
            .putLong(KEY_LOCKOUT_BOOT_COUNT, state.lockoutBootCount)
            .putLong(KEY_LOCKOUT_DURATION, state.lockoutDurationMillis)
            .commit()) { "Unable to persist brute-force state" }
    }

    private fun toState(
        state: PersistedState,
        now: Long,
        boot: Long,
    ): BruteForceState {
        val sameBoot = state.lockoutBootCount == boot
        val remaining = if (sameBoot) {
            (state.lockoutUntilElapsedRealtime - now).coerceAtLeast(0L)
        } else {
            0L
        }
        return BruteForceState(
            failureCount = state.failureCount,
            lockoutUntilElapsedRealtime = state.lockoutUntilElapsedRealtime,
            lockoutBootCount = state.lockoutBootCount,
            isLocked = remaining > 0L,
            remainingLockoutMillis = remaining,
        )
    }

    private fun safeAdd(now: Long, duration: Long): Long =
        if (Long.MAX_VALUE - now < duration) Long.MAX_VALUE else now + duration

    private data class PersistedState(
        val failureCount: Int,
        val lockoutUntilElapsedRealtime: Long,
        val lockoutBootCount: Long,
        val lockoutDurationMillis: Long,
    )

    public companion object {
        public const val DEFAULT_PREFERENCE_NAME: String = "offline_vault_brute_force"

        /** 5=30s, 6=1m, 7=5m, 8-9=15m, 10+=1h. */
        public val DEFAULT_THRESHOLDS: List<LockoutThreshold> = listOf(
            LockoutThreshold(failureCount = 5, durationMillis = 30_000L),
            LockoutThreshold(failureCount = 6, durationMillis = 60_000L),
            LockoutThreshold(failureCount = 7, durationMillis = 5 * 60_000L),
            LockoutThreshold(failureCount = 8, durationMillis = 15 * 60_000L),
            LockoutThreshold(failureCount = 10, durationMillis = 60 * 60_000L),
        )

        private const val KEY_FAILURE_COUNT: String = "failure_count"
        private const val KEY_LOCKOUT_UNTIL_ELAPSED: String = "lockout_until_elapsed_realtime"
        private const val KEY_LOCKOUT_BOOT_COUNT: String = "lockout_boot_count"
        private const val KEY_LOCKOUT_DURATION: String = "lockout_duration_millis"
    }
}
