package com.example.offlinevault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.offlinevault.login.LoginSummary

@Composable
public fun LoginListScreen(
    viewModel: LoginListViewModel,
    onOpen: (String) -> Unit,
    onNewLogin: () -> Unit,
    onHealth: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LoginListScreen(state, onOpen, onNewLogin, onHealth)
}

@Composable
public fun LoginListScreen(
    state: LoginListUiState,
    onOpen: (String) -> Unit,
    onNewLogin: () -> Unit,
    onHealth: () -> Unit,
) {
    OfflineVaultTheme {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text("OFFLINE VAULT / LOGINS", style = MaterialTheme.typography.titleLarge)
            Text("Secrets stay locked until you ask to reveal them.", color = MaterialTheme.colorScheme.secondary)
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                TerminalButton("NEW LOGIN", onNewLogin)
                TerminalButton("PASSWORD HEALTH", onHealth)
            }
            when {
                state.loading -> Text("[ LOADING ]", color = MaterialTheme.colorScheme.primary)
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                state.logins.isEmpty() -> Text("No login records yet.", color = MaterialTheme.colorScheme.tertiary)
                else -> LoginItems(state.logins, onOpen)
            }
        }
    }
}

@Composable
private fun LoginItems(logins: List<LoginSummary>, onOpen: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(logins, key = { it.entryUuid }) { login ->
            TerminalCard {
                Text("${initialBox(login.title)} ${login.title}", style = MaterialTheme.typography.titleMedium)
                Text(login.username.ifBlank { "(no username)" })
                val labels = buildList {
                    if (login.hasTotp) add("[2FA]")
                    addAll(login.tags.map { "#$it" })
                }
                if (labels.isNotEmpty()) {
                    Text(labels.joinToString("  "), color = MaterialTheme.colorScheme.secondary)
                }
                TerminalButton("OPEN", { onOpen(login.entryUuid) })
            }
        }
    }
}

private fun initialBox(title: String): String {
    val letters = title.filter(Char::isLetterOrDigit).uppercase()
    val acronym = title.filter(Char::isUpperCase).take(2)
    return when {
        letters.isEmpty() -> "[--]"
        acronym.length == 2 -> "[$acronym]"
        letters.length == 1 -> "[${letters[0]}-]"
        else -> "[${letters[0]}${letters[1]}]"
    }
}
