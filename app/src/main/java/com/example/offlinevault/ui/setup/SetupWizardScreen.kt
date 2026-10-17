package com.example.offlinevault.ui.setup

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.offlinevault.ui.OfflineVaultTheme
import com.example.offlinevault.ui.TerminalButton
import com.example.offlinevault.ui.TerminalCard
import com.example.offlinevault.ui.VaultFieldShape
import com.example.offlinevault.ui.VaultSecretFontFamily
import com.example.offlinevault.ui.clearText
import com.example.offlinevault.ui.lock.VaultUiCoordinator
import kotlinx.coroutines.flow.collectLatest

/**
 * First-launch setup. A host normally supplies [onSetupComplete] to hand the
 * transient arrays to its vault/session coordinator and then navigates to the
 * login list through [onNavigateToLoginList].
 */
@Composable
public fun SetupWizardScreen(
    viewModel: SetupViewModel,
    onSetupComplete: (masterPassword: CharArray, recoveryPhrase: CharArray) -> Unit,
    onNavigateToLoginList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    OfflineVaultTheme {
        Surface(
            modifier = modifier.fillMaxSize().systemBarsPadding(),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (state.step) {
                SetupStep.PASSWORD -> PasswordStep(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                )
                SetupStep.RECOVERY -> RecoveryStep(
                    state = state,
                    viewModel = viewModel,
                    onSetupComplete = onSetupComplete,
                    onNavigateToLoginList = onNavigateToLoginList,
                    modifier = Modifier.fillMaxSize(),
                )
                SetupStep.COMPLETE -> CompleteStep(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Coordinator overload for hosts that want one stable navigation/auth contract. */
@Composable
public fun SetupWizardScreen(
    viewModel: SetupViewModel,
    coordinator: VaultUiCoordinator,
    modifier: Modifier = Modifier,
) {
    SetupWizardScreen(
        viewModel = viewModel,
        onSetupComplete = coordinator::setup,
        onNavigateToLoginList = coordinator::navigateToLoginList,
        modifier = modifier,
    )
}

@Composable
private fun PasswordStep(
    state: SetupUiState,
    viewModel: SetupViewModel,
    modifier: Modifier,
) {
    val passwordFieldState = remember { TextFieldState("") }
    val confirmationFieldState = remember { TextFieldState("") }
    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Set up your vault",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Create a master password to protect your offline vault.",
            color = MaterialTheme.colorScheme.onBackground,
        )
        TerminalCard {
            Text("Master password", color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))
            SecureInput(
                fieldState = passwordFieldState,
                label = "password",
                onTextChanged = viewModel::previewMasterPassword,
                onSubmit = viewModel::submitMasterPassword,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "strength: ${state.passwordStrength.strength.label}",
                color = when (state.passwordStrength.strength) {
                    PasswordStrength.WEAK -> MaterialTheme.colorScheme.error
                    PasswordStrength.FAIR -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            Text(state.passwordStrength.message, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(12.dp))
            SecureInput(
                fieldState = confirmationFieldState,
                label = "repeat password",
                onTextChanged = viewModel::previewPasswordConfirmation,
                onSubmit = viewModel::submitPasswordConfirmation,
            )
            Text(
                text = if (state.passwordsMatch) "Passwords match" else "Passwords not confirmed",
                color = if (state.passwordsMatch) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
            Text(
                text = "Press DONE after each field. Nothing is saved by this screen.",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall,
            )
            state.passwordError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(4.dp))
            TerminalButton(
                "Show recovery phrase",
                onClick = {
                    submitSecureField(passwordFieldState, viewModel::submitMasterPassword)
                    submitSecureField(confirmationFieldState, viewModel::submitPasswordConfirmation)
                    viewModel.continueToRecovery()
                },
            )
        }
    }
}

@Composable
private fun RecoveryStep(
    state: SetupUiState,
    viewModel: SetupViewModel,
    onSetupComplete: (CharArray, CharArray) -> Unit,
    onNavigateToLoginList: () -> Unit,
    modifier: Modifier,
) {
    var answers by remember(state.confirmationIndexes) {
        mutableStateOf(List(state.confirmationIndexes.size) { "" })
    }
    val phraseText = remember(state.recoveryPhrase) {
        state.recoveryPhrase.joinToString(" ") { it.concatToString() }
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                "Your recovery phrase",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Write these 24 words down offline, in order. Never screenshot, copy, or share them.",
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "This phrase is shown once. If it is lost, the vault cannot be recovered.",
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        item {
            TerminalCard {
                Text(
                    phraseText,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = VaultSecretFontFamily,
                    ),
                )
            }
        }
        item {
            Text("Confirm three words", color = MaterialTheme.colorScheme.secondary)
            Text(
                "Enter the requested words exactly as displayed. The phrase leaves memory after confirmation.",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        itemsIndexed(state.confirmationIndexes) { slot, wordIndex ->
            OutlinedTextField(
                value = answers.getOrElse(slot) { "" },
                onValueChange = { value ->
                    answers = answers.toMutableList().also { it[slot] = value }
                },
                label = { Text("word ${wordIndex + 1}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = VaultFieldShape,
            )
        }
        item {
            state.recoveryError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            TerminalButton(
                label = "Verify and open vault",
                onClick = {
                    val transientAnswers = answers.map { it.toCharArray() }
                    val confirmed = viewModel.confirmRecoveryWords(transientAnswers)
                    if (confirmed && viewModel.completeSetup(onSetupComplete)) {
                        answers = List(answers.size) { "" }
                        onNavigateToLoginList()
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CompleteStep(modifier: Modifier) {
    Column(modifier = modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
         Text("Vault ready", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text("Continue to the login list.", color = MaterialTheme.colorScheme.secondary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SecureInput(
    fieldState: TextFieldState,
    label: String,
    onTextChanged: (CharArray) -> Unit,
    onSubmit: (CharArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(fieldState) {
        onDispose { fieldState.clearText() }
    }
    LaunchedEffect(fieldState) {
        snapshotFlow { fieldState.text.toString() }.collectLatest { text ->
            val transient = text.toCharArray()
            try {
                onTextChanged(transient)
            } finally {
                transient.fill('\u0000')
            }
        }
    }
    val submitHandler = remember(fieldState, onSubmit) {
        object : KeyboardActionHandler {
            override fun onKeyboardAction(defaultAction: () -> Unit) {
                val transient = fieldState.text.toString().toCharArray()
                try {
                    onSubmit(transient)
                } finally {
                    transient.fill('\u0000')
                    fieldState.clearText()
                }
            }
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.secondary)
        BasicTextField(
            value = fieldState.text.toString(),
            onValueChange = { value -> fieldState.edit { replace(0, length, value) } },
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, VaultFieldShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, VaultFieldShape)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submitHandler.onKeyboardAction {} }),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = VaultSecretFontFamily,
            ),
            visualTransformation = PasswordVisualTransformation(),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

private fun submitSecureField(fieldState: TextFieldState, onSubmit: (CharArray) -> Unit) {
    val transient = fieldState.text.toString().toCharArray()
    try {
        onSubmit(transient)
    } finally {
        transient.fill('\u0000')
        fieldState.clearText()
    }
}
