package com.aria.ai.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aria.ai.core.ai.ProviderStatus
import com.aria.ai.ui.theme.AriaError
import com.aria.ai.ui.theme.AriaSuccess
import com.aria.ai.ui.theme.DeepSpace
import com.aria.ai.ui.theme.LineGray
import com.aria.ai.ui.theme.LineSoft
import com.aria.ai.ui.theme.MistDim
import com.aria.ai.ui.theme.MistWhite
import com.aria.ai.ui.theme.NeonCyan

/**
 * One provider row in Settings → AI Providers: identity, stored-key fingerprint,
 * key entry (cloud) or model path (on-device), test + activate actions, and the
 * outcome of the last connection test.
 */
@Composable
fun ProviderCard(
    status: ProviderStatus,
    keyDraft: String,
    modelDraft: String,
    testResult: String?,
    busy: Boolean,
    onKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onSaveKey: () -> Unit,
    onSaveModel: () -> Unit,
    onClearKey: () -> Unit,
    onTest: () -> Unit,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        borderColor = if (status.isActive) NeonCyan.copy(alpha = 0.55f) else LineSoft
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MistWhite
                )
                Text(
                    text = buildString {
                        append(
                            if (status.requiresKey) {
                                "key ${status.fingerprint}"
                            } else {
                                "on-device · no key required"
                            }
                        )
                        if (status.supportsVision) append(" · vision")
                        append(" · ").append(status.activeModel)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MistDim
                )
            }

            if (status.isActive) {
                Surface(color = NeonCyan.copy(alpha = 0.16f), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        text = "ACTIVE",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (status.requiresKey) {
            MaskedKeyField(
                value = keyDraft,
                onValueChange = onKeyChange,
                label = "API key",
                enabled = !busy
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = onSaveKey,
                    enabled = !busy && keyDraft.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = DeepSpace
                    )
                ) { Text("Save key") }

                Spacer(Modifier.width(8.dp))

                OutlinedButton(onClick = onClearKey, enabled = !busy && status.hasKey) {
                    Text("Clear key", color = MistWhite)
                }
            }
        } else {
            OutlinedTextField(
                value = modelDraft,
                onValueChange = onModelChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Model file path (.bin / .task)", color = MistDim) },
                placeholder = { Text("/sdcard/Download/gemma-2b-it.bin", color = MistDim) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = LineGray,
                    focusedTextColor = MistWhite,
                    unfocusedTextColor = MistWhite,
                    cursorColor = NeonCyan
                )
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSaveModel,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = DeepSpace
                )
            ) { Text("Save path") }
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onTest, enabled = !busy) {
                Text("Test connection", color = MistWhite)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onActivate, enabled = !busy && !status.isActive) {
                Text("Set active", color = NeonCyan)
            }
        }

        testResult?.let { result ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.startsWith("OK")) AriaSuccess else AriaError
            )
        }
    }
}