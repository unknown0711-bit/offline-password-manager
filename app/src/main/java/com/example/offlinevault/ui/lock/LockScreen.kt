package com.example.offlinevault.ui.lock

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.offlinevault.ui.OfflineVaultTheme
import com.example.offlinevault.ui.TerminalButton
import com.example.offlinevault.ui.TerminalCard
import com.example.offlinevault.ui.clearText

/** Normal lock screen with transient password input and local countdown refresh. */
@Composable
public fun LockScreen(
    viewModel: LockViewModel,
    onUnlocked: () -> Unit,
    onBiometricRequested: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.unlocked) {
        if (state.unlocked) onUnlocked()
    }

    OfflineVaultTheme {
        Surface(
            modifier = modifier.fillMaxSize().systemBarsPadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "OFFLINE VAULT / LOCKED",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Unlock locally. Secrets never appear in this screen's state.",
                    color = MaterialTheme.colorScheme.secondary,
                )
                when {
                    state.loading -> Text("[ LOADING LOCK STATE ]", color = MaterialTheme.colorScheme.secondary)
                    state.isLocked -> LockoutPanel(state)
                    else -> PasswordPanel(state, viewModel, onBiometricRequested)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** Coordinator overload: the ViewModel is normally constructed with the same coordinator. */
@Composable
public fun LockScreen(
    viewModel: LockViewModel,
    coordinator: VaultUiCoordinator,
    modifier: Modifier = Modifier,
) {
    LockScreen(
        viewModel = viewModel,
        onUnlocked = coordinator::navigateToLoginList,
        onBiometricRequested = viewModel::authenticateBiometric,
        modifier = modifier,
    )
}

@Composable
private fun LockoutPanel(state: LockUiState) {
    TerminalCard {
        Text("BRUTE-FORCE DELAY", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text(state.countdownLabel, color = MaterialTheme.colorScheme.tertiary)
        Text(
            "Password entry is disabled until the timer reaches zero.",
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun PasswordPanel(
    state: LockUiState,
    viewModel: LockViewModel,
    onBiometricRequested: (() -> Unit)?,
) {
    val fieldState = remember { TextFieldState("") }
    DisposableEffect(fieldState) {
        onDispose { fieldState.clearText() }
    }
    val submit: (CharArray) -> Unit = remember(fieldState, viewModel) {
        { password ->
            try {
                viewModel.submitPassword(password)
            } finally {
                password.fill('\u0000')
                fieldState.clearText()
            }
        }
    }
    val keyboardAction = remember(fieldState, submit) {
        object : KeyboardActionHandler {
            override fun onKeyboardAction(defaultAction: () -> Unit) {
                val transient = fieldState.text.toString().toCharArray()
                submit(transient)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SecureLockField(fieldState, keyboardAction)
        Text(
            if (state.authenticating) "[ CHECKING PASSWORD ]" else "Enter your master password",
            color = MaterialTheme.colorScheme.secondary,
        )
        TerminalButton(
            label = if (state.authenticating) "CHECKING..." else "UNLOCK",
            onClick = {
                val transient = fieldState.text.toString().toCharArray()
                submit(transient)
            },
        )
        if (state.biometricAvailable) {
            TerminalButton(
                label = "USE BIOMETRICS",
                onClick = {
                    fieldState.clearText()
                    onBiometricRequested?.invoke() ?: viewModel.authenticateBiometric()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SecureLockField(
    fieldState: TextFieldState,
    keyboardAction: KeyboardActionHandler,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("master password", color = MaterialTheme.colorScheme.secondary)
    BasicTextField(
        value = fieldState.text.toString(),
        onValueChange = { value -> fieldState.edit { replace(0, length, value) } },
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboardAction.onKeyboardAction {} }),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        visualTransformation = PasswordVisualTransformation(),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    )
    }
}
