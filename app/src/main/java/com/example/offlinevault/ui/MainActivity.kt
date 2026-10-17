package com.example.offlinevault.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.offlinevault.login.LoginRepository
import com.example.offlinevault.session.VaultSessionCoordinator
import com.example.offlinevault.session.VaultSessionResult
import com.example.offlinevault.session.sessionCoordinator
import com.example.offlinevault.ui.lock.AuthenticationResult
import com.example.offlinevault.ui.lock.BiometricAuthenticator
import com.example.offlinevault.ui.lock.BruteForceGuardLockoutSource
import com.example.offlinevault.ui.lock.LockScreen
import com.example.offlinevault.ui.lock.LockViewModel
import com.example.offlinevault.ui.lock.PasswordAuthenticator
import com.example.offlinevault.ui.setup.RecoveryPhraseSource
import com.example.offlinevault.ui.setup.SetupViewModel
import com.example.offlinevault.ui.setup.SetupWizardScreen
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Hosts the setup, lock, and unlocked login states without an animated
 * navigation layer. The session coordinator owns the only unlocked provider
 * and installs the login/autofill seams before the LOGIN route is selected.
 */
public class MainActivity : FragmentActivity() {
    private lateinit var coordinator: VaultSessionCoordinator
    private lateinit var hostRoute: MutableState<HostRoute>
    private var pendingSetupPassword: CharArray? = null
    private var pendingSetupPhrase: CharArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setStatusBarColor(android.graphics.Color.BLACK)
        window.setNavigationBarColor(android.graphics.Color.BLACK)
        coordinator = sessionCoordinator(this)
        hostRoute = mutableStateOf(
            when {
                coordinator.isSetupRequired() -> HostRoute.SETUP
                coordinator.isUnlocked() -> HostRoute.LOGIN
                else -> HostRoute.LOCK
            },
        )
        setContent {
            OfflineVaultApp(
                activity = this@MainActivity,
                coordinator = coordinator,
                route = hostRoute.value,
                onRouteChanged = { hostRoute.value = it },
            )
        }
    }

    internal fun requestSetupBiometric(
        password: CharArray,
        phrase: CharArray,
        retry: () -> Unit,
    ) {
        wipePendingSetup()
        pendingSetupPassword = password.copyOf()
        pendingSetupPhrase = phrase.copyOf()
        password.fill('\u0000')
        phrase.fill('\u0000')
        try {
            val crypto = coordinator.prepareSetupBiometric()
            showBiometricPrompt(
                cryptoObject = crypto,
                title = "Create vault biometric unlock",
                onSuccess = { result ->
                    val pendingPassword = pendingSetupPassword
                    val pendingPhrase = pendingSetupPhrase
                    pendingSetupPassword = null
                    pendingSetupPhrase = null
                    if (pendingPassword == null || pendingPhrase == null) {
                        retry()
                        return@showBiometricPrompt
                    }
                    try {
                        when (coordinator.completeSetup(
                            pendingPassword,
                            pendingPhrase,
                            result.cryptoObject,
                        )) {
                            VaultSessionResult.Success -> hostRoute.value = HostRoute.LOGIN
                            is VaultSessionResult.Failure -> retry()
                        }
                    } finally {
                        pendingPassword.fill('\u0000')
                        pendingPhrase.fill('\u0000')
                    }
                },
                onFailure = {
                    wipePendingSetup()
                    retry()
                },
            )
        } catch (_: Throwable) {
            wipePendingSetup()
            retry()
        }
    }

    private fun showBiometricPrompt(
        cryptoObject: BiometricPrompt.CryptoObject,
        title: String,
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        onFailure: () -> Unit,
    ) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess(result)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure()
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Strong biometric authentication is required")
            .setNegativeButtonText("CANCEL")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(promptInfo, cryptoObject)
    }

    internal fun biometricAuthentication(): suspend () -> AuthenticationResult = {
        suspendCancellableCoroutine { continuation ->
            val crypto = try {
                coordinator.prepareBiometricUnlock()
            } catch (error: Throwable) {
                continuation.resume(
                    AuthenticationResult.Failure(error.message ?: "Biometric unlock unavailable"),
                )
                return@suspendCancellableCoroutine
            }
            showBiometricPrompt(
                cryptoObject = crypto,
                title = "Unlock Offline Vault",
                onSuccess = { result ->
                    if (continuation.isActive) {
                        continuation.resume(
                            coordinator.completeBiometricUnlock(result.cryptoObject).toAuthenticationResult(),
                        )
                    }
                },
                onFailure = {
                    if (continuation.isActive) {
                        continuation.resume(
                            AuthenticationResult.Failure("Biometric authentication cancelled"),
                        )
                    }
                },
            )
        }
    }

    private fun wipePendingSetup() {
        pendingSetupPassword?.fill('\u0000')
        pendingSetupPhrase?.fill('\u0000')
        pendingSetupPassword = null
        pendingSetupPhrase = null
    }

    override fun onDestroy() {
        wipePendingSetup()
        super.onDestroy()
    }
}

@Composable
private fun OfflineVaultApp(
    activity: MainActivity,
    coordinator: VaultSessionCoordinator,
    route: HostRoute,
    onRouteChanged: (HostRoute) -> Unit,
) {
    OfflineVaultTheme {
        when (route) {
            HostRoute.SETUP -> {
                val source = remember(coordinator) {
                    RecoveryPhraseSource { coordinator.generateRecoveryPhrase() }
                }
                val viewModel = remember(source) { SetupViewModel(source) }
                SetupWizardScreen(
                    viewModel = viewModel,
                    onSetupComplete = { password, phrase ->
                        activity.requestSetupBiometric(password, phrase, viewModel::reset)
                    },
                    // The biometric callback selects LOGIN only after the
                    // coordinator has persisted and opened the vault.
                    onNavigateToLoginList = {},
                )
            }
            HostRoute.LOCK -> {
                val entryPoint = remember(activity) {
                    EntryPointAccessors.fromApplication(
                        activity.applicationContext,
                        com.example.offlinevault.session.SessionEntryPoint::class.java,
                    )
                }
                val biometricAvailable = remember(activity) {
                    BiometricManager.from(activity).canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG,
                    ) == BiometricManager.BIOMETRIC_SUCCESS
                }
                val lockoutSource = remember(entryPoint) {
                    BruteForceGuardLockoutSource(entryPoint.bruteForceGuard())
                }
                val passwordAuthenticator = remember(coordinator) {
                    PasswordAuthenticator { password ->
                        coordinator.unlockWithMasterPassword(password).toAuthenticationResult()
                    }
                }
                val biometricAuthenticator = remember(coordinator) {
                    BiometricAuthenticator { activity.biometricAuthentication()() }
                }
                val viewModel = remember(coordinator) {
                    LockViewModel(
                        lockoutStateSource = lockoutSource,
                        passwordAuthenticator = passwordAuthenticator,
                        biometricAuthenticator = biometricAuthenticator,
                    )
                }
                LaunchedEffect(biometricAvailable) {
                    viewModel.setBiometricAvailable(biometricAvailable)
                }
                LockScreen(
                    viewModel = viewModel,
                    onUnlocked = { onRouteChanged(HostRoute.LOGIN) },
                    onBiometricRequested = viewModel::authenticateBiometric,
                )
            }
            HostRoute.LOGIN -> {
                val repository = coordinator.currentLoginRepository()
                if (repository == null) {
                    LaunchedEffect(Unit) { onRouteChanged(HostRoute.LOCK) }
                } else {
                    LoginFeatureNavigation(repository)
                }
            }
        }
    }
}

private enum class HostRoute { SETUP, LOCK, LOGIN }

private fun VaultSessionResult.toAuthenticationResult(): AuthenticationResult = when (this) {
    VaultSessionResult.Success -> AuthenticationResult.Success
    is VaultSessionResult.Failure -> AuthenticationResult.Failure(message)
}

private enum class LoginRoute { LIST, CREATE, DETAIL, GENERATOR, HEALTH }

@Composable
private fun LoginFeatureNavigation(repository: LoginRepository) {
    var route by remember { mutableStateOf(LoginRoute.LIST) }
    var selectedEntry by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = remember(context) { SensitiveClipboard(context) }
    when (route) {
        LoginRoute.LIST -> {
            val viewModel = remember(repository) { LoginListViewModel(repository) }
            LoginListScreen(
                viewModel = viewModel,
                onOpen = { selectedEntry = it; route = LoginRoute.DETAIL },
                onNewLogin = { route = LoginRoute.CREATE },
                onHealth = { route = LoginRoute.HEALTH },
            )
        }
        LoginRoute.CREATE -> {
            val viewModel = remember(repository) { LoginEditorViewModel(repository) }
            LoginCreateScreen(
                viewModel = viewModel,
                onSaved = { selectedEntry = it; route = LoginRoute.DETAIL },
                onBack = { route = LoginRoute.LIST },
            )
        }
        LoginRoute.DETAIL -> {
            val entryId = selectedEntry
            if (entryId == null) {
                androidx.compose.material3.Text("New login creation requires the Phase 2 form coordinator.")
                TerminalButton("BACK", onClick = { route = LoginRoute.LIST })
            } else {
                val viewModel = remember(repository, entryId) {
                    LoginDetailViewModel(repository, entryId)
                }
                LoginDetailScreen(
                    viewModel = viewModel,
                    onBack = { route = LoginRoute.LIST },
                    onCopySecret = clipboard::copy,
                )
            }
        }
        LoginRoute.GENERATOR -> {
            val viewModel = remember { GeneratorViewModel() }
            GeneratorScreen(viewModel, clipboard::copy) { route = LoginRoute.LIST }
        }
        LoginRoute.HEALTH -> {
            val viewModel = remember(repository) { HealthReportViewModel(repository) }
            HealthReportScreen(viewModel) { route = LoginRoute.LIST }
        }
    }
}
