package com.aria.ai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.aria.ai.ui.theme.NeonCyan
import com.aria.ai.ui.theme.NeonPurple
import kotlin.math.abs
import kotlin.math.sin

/**
 * Live microphone waveform.
 *
 * Bar heights combine the real RMS [amplitude] reported by the recorder with a
 * travelling sine envelope, so the visualiser moves even during quiet speech and
 * flattens when [active] is false.
 */
@Composable
fun WaveformVisualizer(
    amplitude: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 28
) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val energy = amplitude.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        val slot = size.width / barCount
        val barWidth = slot * 0.45f
        val gap = (slot - barWidth) / 2f
        val centerY = size.height / 2f

        for (index in 0 until barCount) {
            val position = index.toFloat() / barCount
            val wave = sin((position * 6.0 + phase * 6.283).toFloat())
            val envelope = 1f - abs(position - 0.5f) * 1.4f
            val factor = if (active) {
                (0.18f + energy * 0.9f) * (0.45f + abs(wave) * 0.85f) * envelope
            } else {
                0.06f * envelope
            }
            val barHeight = (size.height * factor).coerceAtLeast(size.height * 0.04f)
            val x = index * slot + gap

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(NeonCyan, NeonPurple),
                    startY = centerY - barHeight / 2f,
                    endY = centerY + barHeight / 2f
                ),
                topLeft = Offset(x, centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}