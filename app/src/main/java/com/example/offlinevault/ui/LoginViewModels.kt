package com.example.offlinevault.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinevault.login.HealthReport
import com.example.offlinevault.login.LoginDetail
import com.example.offlinevault.login.LoginRepository
import com.example.offlinevault.login.LoginSecrets
import com.example.offlinevault.login.LoginSummary
import com.example.offlinevault.login.PasswordGenerator
import com.example.offlinevault.login.PasswordGeneratorOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public data class LoginListUiState(
    public val loading: Boolean = true,
    public val logins: List<LoginSummary> = emptyList(),
    public val error: String? = null,
)

public class LoginListViewModel(
    private val repository: LoginRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LoginListUiState())
    public val state: StateFlow<LoginListUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                repository.observeLogins().collect { logins ->
                    mutableState.value = LoginListUiState(loading = false, logins = logins)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = LoginListUiState(loading = false, error = error.message ?: "Unable to load logins")
            }
        }
    }
}

public data class LoginDetailUiState(
    public val loading: Boolean = true,
    public val detail: LoginDetail? = null,
    public val secrets: LoginSecrets? = null,
    public val error: String? = null,
)

public class LoginDetailViewModel(
    private val repository: LoginRepository,
    private val entryUuid: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LoginDetailUiState())
    public val state: StateFlow<LoginDetailUiState> = mutableState.asStateFlow()
    private var revealJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                mutableState.value = LoginDetailUiState(false, repository.getLogin(entryUuid))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = LoginDetailUiState(false, error = error.message ?: "Unable to load login")
            }
        }
    }

    public fun revealSecrets() {
        revealJob?.cancel()
        revealJob = viewModelScope.launch {
            try {
                val secrets = repository.revealSecrets(entryUuid)
                maskSecrets()
                mutableState.value = mutableState.value.copy(secrets = secrets)
                delay(SECRETS_VISIBLE_MILLIS)
                maskSecrets()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(error = error.message ?: "Unable to reveal secrets")
            }
        }
    }

    public fun maskSecrets() {
        mutableState.value.secrets?.close()
        mutableState.value = mutableState.value.copy(secrets = null)
    }

    public fun onPause() {
        revealJob?.cancel()
        maskSecrets()
    }

    override fun onCleared() {
        revealJob?.cancel()
        maskSecrets()
        super.onCleared()
    }

    private companion object {
        const val SECRETS_VISIBLE_MILLIS = 20_000L
    }
}

public data class GeneratorUiState(
    public val password: CharArray? = null,
    public val options: PasswordGeneratorOptions = PasswordGeneratorOptions(),
) {
    override fun equals(other: Any?): Boolean =
        other is GeneratorUiState && password.contentEquals(other.password)

    override fun hashCode(): Int = password?.contentHashCode() ?: 0
}

public class GeneratorViewModel(
    private val generator: PasswordGenerator = PasswordGenerator(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(GeneratorUiState())
    public val state: StateFlow<GeneratorUiState> = mutableState.asStateFlow()

    public fun generate(options: PasswordGeneratorOptions = mutableState.value.options) {
        mutableState.value.password?.fill('\u0000')
        mutableState.value = GeneratorUiState(generator.generate(options), options)
    }

    public fun clear() {
        mutableState.value.password?.fill('\u0000')
        mutableState.value = GeneratorUiState()
    }

    override fun onCleared() {
        clear()
        super.onCleared()
    }
}

public data class HealthReportUiState(
    public val loading: Boolean = true,
    public val report: HealthReport? = null,
    public val error: String? = null,
)

public class HealthReportViewModel(
    private val repository: LoginRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HealthReportUiState())
    public val state: StateFlow<HealthReportUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                mutableState.value = HealthReportUiState(false, repository.healthReport())
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = HealthReportUiState(false, error = error.message ?: "Unable to analyze passwords")
            }
        }
    }
}

/** Thin factory for hosts that create ViewModels without Hilt's saved-state factory. */
public class LoginViewModelFactory<T : ViewModel>(
    private val creator: () -> T,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = creator() as VM
}
