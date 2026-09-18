package com.aria.ai.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aria.ai.ui.theme.CyanDeep
import com.aria.ai.ui.theme.LineGray
import com.aria.ai.ui.theme.MistDim
import com.aria.ai.ui.theme.MistWhite
import com.aria.ai.ui.theme.NeonCyan
import com.aria.ai.ui.theme.NeonPurple

/**
 * Bottom command dock: text entry, send, and the push-to-talk microphone.
 * Icon glyphs are text so Aria needs no icon assets beyond the core Compose set.
 */
@Composable
fun AriaDock(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    micActive: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    GlassCard(modifier = modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                maxLines = 3,
                shape = RoundedCornerShape(16.dp),
                placeholder = {
                    Text(
                        text = if (busy) "Thinking…" else "Ask Aria or tap the mic…",
                        color = MistDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = LineGray,
                    focusedTextColor = MistWhite,
                    unfocusedTextColor = MistWhite,
                    cursorColor = NeonCyan
                )
            )

            Spacer(Modifier.width(8.dp))

            IconButton(
                onClick = onSend,
                enabled = enabled && text.isNotBlank() && !busy
            ) {
                Text(
                    text = "➤",
                    color = if (text.isNotBlank() && !busy) NeonCyan else MistDim,
                    fontSize = 20.sp
                )
            }

            Spacer(Modifier.width(4.dp))

            FilledIconButton(
                onClick = onMic,
                enabled = enabled,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (micActive) NeonPurple else CyanDeep,
                    contentColor = MistWhite
                )
            ) {
                Text(text = if (micActive) "■" else "●", fontSize = 16.sp, color = Color.White)
            }
        }
    }
}