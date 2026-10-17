package com.example.offlinevault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.offlinevault.login.Base32
import com.example.offlinevault.login.TotpAlgorithm
import com.example.offlinevault.login.TotpCode
import com.example.offlinevault.login.TotpEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The only composable that owns the TOTP ticking loop. It updates on exact
 * wall-clock second boundaries and uses no animation or shared timer state.
 */
@Composable
public fun TotpBlock(
    secret: CharArray,
    modifier: Modifier = Modifier,
    algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    digits: Int = 6,
    periodSeconds: Long = 30L,
    onCopy: ((CharArray) -> Unit)? = null,
) {
    val secretBytes = remember(secret.contentHashCode()) {
        runCatching { Base32.decode(secret.concatToString()) }.getOrNull()
    }
    if (secretBytes == null) {
        TerminalCard { Text("Invalid TOTP secret", color = MaterialTheme.colorScheme.error) }
        return
    }
    DisposableEffect(secretBytes) {
        onDispose { secretBytes.fill(0) }
    }
    var code by remember(secretBytes, algorithm, digits, periodSeconds) { mutableStateOf<TotpCode?>(null) }
    LaunchedEffect(secretBytes, algorithm, digits, periodSeconds) {
        while (isActive) {
            val epochSeconds = System.currentTimeMillis() / 1_000L
            code = TotpEngine.generate(secretBytes, epochSeconds, algorithm, digits, periodSeconds)
            val nowMillis = System.currentTimeMillis()
            val millisToNextSecond = (1_000L - (nowMillis % 1_000L)).coerceIn(1L, 1_000L)
            delay(millisToNextSecond)
        }
    }
    TerminalCard {
        Column(modifier = modifier) {
            Text("Authenticator code", style = MaterialTheme.typography.labelMedium)
            Row {
                Text(
                    text = code?.value ?: "------",
                    modifier = Modifier.clickable {
                        code?.value?.let { onCopy?.invoke(it.toCharArray()) }
                    },
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = VaultSecretFontFamily,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                Spacer(Modifier.width(12.dp))
                Text("${code?.remainingSeconds ?: periodSeconds}s", color = MaterialTheme.colorScheme.tertiary)
            }
            if (onCopy != null && code != null) {
                 TerminalButton("COPY CODE", onClick = { onCopy(code!!.value.toCharArray()) })
            }
        }
    }
}
