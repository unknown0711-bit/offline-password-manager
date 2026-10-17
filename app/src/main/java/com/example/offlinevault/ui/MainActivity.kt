package com.example.offlinevault.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.offlinevault.login.LoginRepository

/** Minimal no-animation host. Unlock/session wiring is installed by Phase 1. */
public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setStatusBarColor(android.graphics.Color.BLACK)
        window.setNavigationBarColor(android.graphics.Color.BLACK)
        setContent { LoginFeatureHost() }
    }
}

@androidx.compose.runtime.Composable
private fun LoginFeatureHost() {
    OfflineVaultTheme {
        val repository = LoginUiSession.repository(androidx.compose.ui.platform.LocalContext.current)
        if (repository == null) {
            LockedHost()
        } else {
            LoginFeatureNavigation(repository)
        }
    }
}

@androidx.compose.runtime.Composable
private fun LockedHost() {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("> VAULT LOCKED", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        Text("Unlock through the Phase 1 session coordinator.")
    }
}

private enum class LoginRoute { LIST, CREATE, DETAIL, GENERATOR, HEALTH }

@androidx.compose.runtime.Composable
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
                Text("New login creation requires the Phase 2 form coordinator.")
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
