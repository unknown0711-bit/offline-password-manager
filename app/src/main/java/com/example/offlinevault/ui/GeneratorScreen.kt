package com.example.offlinevault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
public fun GeneratorScreen(
    viewModel: GeneratorViewModel,
    onCopy: (CharArray) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    OfflineVaultTheme {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
            TerminalButton("BACK", onBack)
            Text("PASSWORD GENERATOR", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Text("Cryptographically random characters; generated values are transient.")
            TerminalButton("GENERATE", { viewModel.generate() })
            state.password?.let { generated ->
                BasicSecureSecretField(
                    initialValue = generated,
                    label = "GENERATED PASSWORD",
                    onValueChange = { value -> value.fill('\u0000') },
                )
                TerminalButton("COPY PASSWORD", onClick = { onCopy(generated.copyOf()) })
            }
        }
    }
}
