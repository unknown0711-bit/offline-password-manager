package com.example.offlinevault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.offlinevault.login.HealthReport
import com.example.offlinevault.login.HealthSeverity

@Composable
public fun HealthReportScreen(viewModel: HealthReportViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    OfflineVaultTheme {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
            TerminalButton("BACK", onBack)
            Text("PASSWORD HEALTH", style = MaterialTheme.typography.titleLarge)
            val error = state.error
            val report = state.report
            when {
                state.loading -> Text("[ ANALYZING ]", color = MaterialTheme.colorScheme.primary)
                error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                report != null -> HealthReportBody(report)
            }
        }
    }
}

@Composable
private fun HealthReportBody(report: HealthReport) {
    Text("SCORE ${report.score}/100", style = MaterialTheme.typography.headlineMedium)
    Text("${report.evaluatedCount} logins evaluated · ${report.reusedCount} reused pairs")
    if (report.findings.isEmpty()) {
        Text("No issues found.", color = MaterialTheme.colorScheme.primary)
    } else {
        LazyColumn {
            items(report.findings) { finding ->
                TerminalCard {
                    Text(finding.title, style = MaterialTheme.typography.titleMedium)
                    Text(finding.message, color = when (finding.severity) {
                        HealthSeverity.CRITICAL -> MaterialTheme.colorScheme.error
                        HealthSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                        HealthSeverity.INFO -> MaterialTheme.colorScheme.secondary
                    })
                }
            }
        }
    }
}
