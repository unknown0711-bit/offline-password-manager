package com.example.offlinevault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
public fun LoginCreateScreen(
    viewModel: LoginEditorViewModel,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.savedEntryUuid) {
        state.savedEntryUuid?.let(onSaved)
    }
    val error = state.error
    OfflineVaultTheme {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
            TerminalButton("BACK", onBack)
            Text("NEW LOGIN", style = MaterialTheme.typography.titleLarge)
            TerminalTextField(state.title, viewModel::setTitle, "TITLE")
            TerminalTextField(state.username, viewModel::setUsername, "USERNAME")
            TerminalTextField(state.url, viewModel::setUrl, "URL")
            TerminalTextField(state.tags, viewModel::setTags, "TAGS (COMMA SEPARATED)")
            BasicSecureSecretField(
                label = "PASSWORD",
                onValueChange = viewModel::setPassword,
                observeChanges = true,
            )
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            TerminalButton("SAVE LOGIN", onClick = viewModel::save)
        }
    }
}
