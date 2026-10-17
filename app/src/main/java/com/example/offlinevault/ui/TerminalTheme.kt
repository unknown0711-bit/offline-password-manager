package com.example.offlinevault.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.offlinevault.R
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.flow.collectLatest

private val TerminalColors = darkColorScheme(
    primary = Color(0xFF00E05A),
    onPrimary = Color.Black,
    secondary = Color(0xFF00B8FF),
    onSecondary = Color.Black,
    tertiary = Color(0xFFFFB000),
    error = Color(0xFFFF2A3D),
    background = Color.Black,
    onBackground = Color(0xFFE7FFE7),
    surface = Color.Black,
    onSurface = Color(0xFFE7FFE7),
)

private val TerminalMono = FontFamily(Font(R.font.jetbrains_mono))
private val TerminalTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = TerminalMono),
        displayMedium = displayMedium.copy(fontFamily = TerminalMono),
        displaySmall = displaySmall.copy(fontFamily = TerminalMono),
        headlineLarge = headlineLarge.copy(fontFamily = TerminalMono),
        headlineMedium = headlineMedium.copy(fontFamily = TerminalMono),
        headlineSmall = headlineSmall.copy(fontFamily = TerminalMono),
        titleLarge = titleLarge.copy(fontFamily = TerminalMono, fontSize = 22.sp),
        titleMedium = titleMedium.copy(fontFamily = TerminalMono),
        titleSmall = titleSmall.copy(fontFamily = TerminalMono),
        bodyLarge = bodyLarge.copy(fontFamily = TerminalMono, fontSize = 16.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontFamily = TerminalMono, fontSize = 14.sp, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontFamily = TerminalMono),
        labelLarge = labelLarge.copy(fontFamily = TerminalMono),
        labelMedium = labelMedium.copy(fontFamily = TerminalMono),
        labelSmall = labelSmall.copy(fontFamily = TerminalMono),
    )
}

@Composable
public fun OfflineVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TerminalColors, typography = TerminalTypography, content = content)
}

@Composable
public fun TerminalCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = TerminalColors.surface),
        border = BorderStroke(1.dp, TerminalColors.primary.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
public fun TerminalButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Text(
        text = "[ $label ]",
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, TerminalColors.primary)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        color = TerminalColors.primary,
    )
}

@Composable
public fun TerminalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
    )
}

/**
 * Foundation's secure field deliberately owns its TextFieldState in memory,
 * never persisted state. The caller receives transient copies as CharArray.
 */
@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
public fun BasicSecureSecretField(
    label: String,
    onValueChange: (CharArray) -> Unit,
    modifier: Modifier = Modifier,
    initialValue: CharArray = CharArray(0),
    onSubmit: ((CharArray) -> Unit)? = null,
    observeChanges: Boolean = false,
) {
    val state = remember(initialValue.contentHashCode()) { TextFieldState(initialValue.concatToString()) }
    if (observeChanges) {
        LaunchedEffect(state) {
            snapshotFlow { state.text.toString() }.collectLatest { value ->
                val chars = value.toCharArray()
                try {
                    onValueChange(chars)
                } finally {
                    chars.fill('\u0000')
                }
            }
        }
    }
    DisposableEffect(state) {
        onDispose { state.clearText() }
    }
    val submitHandler = object : KeyboardActionHandler {
        override fun onKeyboardAction(defaultAction: () -> Unit) {
            val submitted = state.text.toString().toCharArray()
            try {
                (onSubmit ?: onValueChange)(submitted)
            } finally {
                submitted.fill('\u0000')
                state.clearText()
            }
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        BasicSecureTextField(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = submitHandler,
            textStyle = MaterialTheme.typography.bodyLarge,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Clears the in-memory TextFieldState immediately after a submit action. */
public fun TextFieldState.clearText() {
    edit { replace(0, length, "") }
}
