package com.example.offlinevault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.offlinevault.login.LoginDetail
import com.example.offlinevault.login.LoginSecrets

@Composable
public fun LoginDetailScreen(
    viewModel: LoginDetailViewModel,
    onBack: () -> Unit,
    onCopySecret: (CharArray) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.onPause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onPause()
        }
    }
    LoginDetailScreen(state, viewModel::revealSecrets, onBack, onCopySecret)
}

@Composable
public fun LoginDetailScreen(
    state: LoginDetailUiState,
    onReveal: () -> Unit,
    onBack: () -> Unit,
    onCopySecret: (CharArray) -> Unit,
) {
    OfflineVaultTheme {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
            TerminalButton("BACK", onBack)
            when {
                state.loading -> Text("Loading login…")
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                state.detail != null -> LoginDetailBody(state.detail, state.secrets, onReveal, onCopySecret)
            }
        }
    }
}

@Composable
private fun LoginDetailBody(
    detail: LoginDetail,
    secrets: LoginSecrets?,
    onReveal: () -> Unit,
    onCopySecret: (CharArray) -> Unit,
) {
    Text(detail.title, style = MaterialTheme.typography.titleLarge)
    Text(
        text = detail.username.ifBlank { "(no username)" },
        modifier = Modifier.clickable {
            if (detail.username.isNotBlank()) onCopySecret(detail.username.toCharArray())
        },
    )
    if (detail.url.isNotBlank()) Text(detail.url, color = MaterialTheme.colorScheme.secondary)
    if (detail.tags.isNotEmpty()) Text(detail.tags.joinToString("  ") { "#$it" })
    if (detail.notes.isNotBlank()) {
        Text("NOTES", style = MaterialTheme.typography.labelMedium)
        Text(detail.notes)
    }
    TerminalButton(
        if (secrets == null) "REVEAL SECRETS (20S)" else "MASK SECRETS",
        onClick = if (secrets == null) onReveal else ({}),
    )
    if (secrets != null) {
        val password = secrets.password
        Text("PASSWORD", style = MaterialTheme.typography.labelMedium)
        RevealedPassword(
            password = password,
            onClick = { onCopySecret(secrets.password) },
        )
        password.fill('\u0000')
        TerminalButton("COPY PASSWORD", onClick = {
            val copy = secrets.password
            onCopySecret(copy)
        })
        val totp = secrets.totpSecret
        if (totp != null) {
            TotpBlock(
                secret = totp,
                algorithm = secrets.totpAlgorithm,
                digits = secrets.totpDigits,
                periodSeconds = secrets.totpPeriodSeconds,
                onCopy = onCopySecret,
            )
            totp.fill('\u0000')
        }
    }
}

@Composable
private fun RevealedPassword(password: CharArray, onClick: () -> Unit) {
    val builder = AnnotatedString.Builder()
    password.forEach { character ->
        val color = when {
            character.isDigit() -> Color(0xFFFFB000)
            !character.isLetterOrDigit() -> Color(0xFFFF2A3D)
            else -> Color(0xFF00E05A)
        }
        builder.withStyle(SpanStyle(color = color)) {
            append(character.toString())
        }
    }
    Text(
        text = builder.toAnnotatedString(),
        modifier = Modifier.clickable(onClick = onClick),
        style = MaterialTheme.typography.headlineSmall,
    )
}
