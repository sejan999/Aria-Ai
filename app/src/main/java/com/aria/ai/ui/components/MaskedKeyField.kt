package com.aria.ai.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import com.aria.ai.ui.theme.LineGray
import com.aria.ai.ui.theme.MistDim
import com.aria.ai.ui.theme.MistWhite
import com.aria.ai.ui.theme.NeonCyan
import com.aria.ai.ui.theme.AriaError

/**
 * Secret-entry field for API keys.
 *
 * The value is masked by default with an explicit reveal toggle, and the field is
 * marked as a password field so the platform keyboard never offers to memorise or
 * autocorrect it.
 */
@Composable
fun MaskedKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var revealed by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(label, color = MistDim) },
        placeholder = { Text("Paste key and tap Save", color = MistDim) },
        visualTransformation = if (revealed) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = { revealed = !revealed }) {
                Text(text = if (revealed) "Hide" else "Show", color = NeonCyan)
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NeonCyan,
            unfocusedBorderColor = LineGray,
            focusedTextColor = MistWhite,
            unfocusedTextColor = MistWhite,
            cursorColor = NeonCyan,
            errorBorderColor = AriaError
        )
    )
}