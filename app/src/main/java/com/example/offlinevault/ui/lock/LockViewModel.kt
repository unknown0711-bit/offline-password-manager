package com.example.offlinevault.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State owner for the normal lock screen. The refresh loop touches only this
 * ViewModel; no application-wide ticker or persisted UI state is introduced.
 */
public class LockViewModel(
    private val lockoutStateSource: LockoutStateSource = LockoutStateSource { LockoutSnapshot() },
    private val passwordAuthenticator: PasswordAuthenticator = PasswordAuthenticator {
        AuthenticationResult.Failure("Password authentication is not configured")
    },
    private val biometricAuthenticator: BiometricAuthenticator = BiometricAuthenticator {
        AuthenticationResult.Failure("Biometric authentication is not configured")
    },
    private val refreshIntervalMillis: Long = LOCK_COUNTDOWN_REFRESH_MILLIS,
) : ViewModel() {
    public constructor(
        coordinator: VaultUiCoordinator,
        lockoutStateSource: LockoutStateSource = LockoutStateSource { LockoutSnapshot() },
        refreshIntervalMillis: Long = LOCK_COUNTDOWN_REFRESH_MILLIS,
    ) : this(
        lockoutStateSource = lockoutStateSource,
        passwordAuthenticator = PasswordAuthenticator { password -> coordinator.password(password) },
        biometricAuthenticator = BiometricAuthenticator { coordinator.biometric() },
        refreshIntervalMillis = refreshIntervalMillis,
    )

    private val mutableState = MutableStateFlow(LockUiState())
    public val state: StateFlow<LockUiState> = mutableState.asStateFlow()

    private var refreshJob: Job? = null
    private var authenticationJob: Job? = null
    private var countdownTotalMillis = 30_000L

    init {
        refreshLockState()
        startCountdownRefresh()
    }

    public fun setBiometricAvailable(available: Boolean) {
        mutableState.value = mutableState.value.copy(biometricAvailable = available)
    }

    /** Public for hosts that learn biometric availability after composition. */
    public fun refreshLockState() {
        try {
            val lockout = lockoutStateSource.current()
            countdownTotalMillis = nextCountdownTotal(lockout)
            mutableState.value = mutableState.value.copy(
                loading = false,
                lockout = lockout,
                countdownTotalMillis = countdownTotalMillis,
                error = null,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = error.message ?: "Unable to read lock state",
            )
        }
    }

    /** Submits a transient password and wipes the caller-owned chars on return. */
    public fun submitPassword(password: CharArray) {
        if (mutableState.value.isLocked || mutableState.value.authenticating) {
            password.fill('\u0000')
            return
        }
        authenticationJob?.cancel()
        mutableState.value = mutableState.value.copy(
            authenticating = true,
            error = null,
            unlocked = false,
        )
        val transientPassword = password.copyOf()
        password.fill('\u0000')
        authenticationJob = viewModelScope.launch {
            val result = try {
                passwordAuthenticator.authenticate(transientPassword)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AuthenticationResult.Failure(error.message ?: "Unable to unlock vault")
            } finally {
                transientPassword.fill('\u0000')
            }
            handleAuthenticationResult(result)
        }
    }

    public fun unlock(password: CharArray): Unit = submitPassword(password)

    public fun authenticateBiometric() {
        val current = mutableState.value
        if (!current.biometricAvailable || current.isLocked || current.authenticating) return
        authenticationJob?.cancel()
        mutableState.value = current.copy(authenticating = true, error = null, unlocked = false)
        authenticationJob = viewModelScope.launch {
            val result = try {
                biometricAuthenticator.authenticate()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AuthenticationResult.Failure(error.message ?: "Unable to authenticate biometrically")
            }
            handleAuthenticationResult(result)
        }
    }

    public fun biometric(): Unit = authenticateBiometric()

    public fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    override fun onCleared() {
        refreshJob?.cancel()
        authenticationJob?.cancel()
        super.onCleared()
    }

    private fun startCountdownRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                delay(refreshIntervalMillis.coerceAtLeast(100L))
                refreshLockState()
            }
        }
    }

    private fun handleAuthenticationResult(result: AuthenticationResult) {
        when (result) {
            AuthenticationResult.Success -> {
                refreshLockState()
                mutableState.value = mutableState.value.copy(
                    authenticating = false,
                    error = null,
                    unlocked = true,
                )
            }
            is AuthenticationResult.Failure -> {
                refreshLockState()
                mutableState.value = mutableState.value.copy(
                    authenticating = false,
                    error = result.message,
                    unlocked = false,
                )
            }
        }
    }

    private fun nextCountdownTotal(lockout: LockoutSnapshot): Long {
        if (!lockout.isLocked || lockout.remainingLockoutMillis <= 0L) return 30_000L
        return maxOf(30_000L, countdownTotalMillis, lockout.remainingLockoutMillis)
    }
}
