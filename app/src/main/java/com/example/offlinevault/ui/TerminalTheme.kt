package com.example.offlinevault.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.offlinevault.R
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.flow.collectLatest

private val VaultColors = darkColorScheme(
    primary = Color(0xFF8A72FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF31245F),
    onPrimaryContainer = Color(0xFFE8E1FF),
    secondary = Color(0xFFB7A8F7),
    onSecondary = Color(0xFF19152A),
    tertiary = Color(0xFFFFC875),
    error = Color(0xFFFF6B81),
    background = Color(0xFF0F0F13),
    onBackground = Color(0xFFF3F1FA),
    surface = Color(0xFF17171F),
    onSurface = Color(0xFFF3F1FA),
    surfaceVariant = Color(0xFF23232D),
    onSurfaceVariant = Color(0xFFC8C3D6),
    outline = Color(0xFF4E4A5D),
    outlineVariant = Color(0xFF373442),
)

/** JetBrains Mono is reserved for secret values, not general UI chrome. */
public val VaultSecretFontFamily: FontFamily = FontFamily(Font(R.font.jetbrains_mono))
public val VaultFieldShape: RoundedCornerShape = RoundedCornerShape(12.dp)

@Composable
public fun OfflineVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VaultColors,
        typography = Typography(),
        shapes = androidx.compose.material3.Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}

@Composable
public fun TerminalCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = VaultColors.surface),
        border = BorderStroke(1.dp, VaultColors.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(18.dp), content = content)
    }
}

@Composable
public fun TerminalButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Text(
        text = label,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, VaultColors.primary.copy(alpha = 0.7f), VaultFieldShape)
            .background(VaultColors.primary, VaultFieldShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        color = VaultColors.onPrimary,
        style = MaterialTheme.typography.labelLarge,
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
        shape = VaultFieldShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = VaultColors.primary,
            unfocusedBorderColor = VaultColors.outline,
            focusedLabelColor = VaultColors.primary,
            unfocusedLabelColor = VaultColors.onSurfaceVariant,
            cursorColor = VaultColors.primary,
        ),
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
        BasicTextField(
            value = state.text.toString(),
            onValueChange = { value ->
                state.edit { replace(0, length, value) }
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { submitHandler.onKeyboardAction {} },
            ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = VaultSecretFontFamily,
            ),
            visualTransformation = PasswordVisualTransformation(),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Clears the in-memory TextFieldState immediately after a submit action. */
public fun TextFieldState.clearText() {
    edit { replace(0, length, "") }
}
