package com.aria.ai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aria.ai.ui.theme.NeonBlue
import com.aria.ai.ui.theme.NeonCyan
import com.aria.ai.ui.theme.NeonPurple
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Aria "quantum core": a pulsing orb wrapped in two counter-rotating arcs,
 * an amplitude-reactive halo and orbiting particles. Drawn entirely with Canvas —
 * no images, no vector assets, no native rendering.
 */
@Composable
fun AriaQuantumHUD(
    amplitude: Float,
    listening: Boolean,
    thinking: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = 200.dp
) {
    val transition = rememberInfiniteTransition(label = "aria-core")

    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val counterRotation by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "counter-rotation"
    )

    val pulse by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val energy = amplitude.coerceIn(0f, 1f)
    val coreScale = pulse * (1f + energy * 0.35f)
    val halo = when {
        thinking -> NeonPurple
        listening -> NeonCyan
        else -> NeonBlue
    }

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(diameter)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val base = size.minDimension / 2f

            val haloRadius = base * (0.62f + energy * 0.3f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(halo.copy(alpha = 0.24f + energy * 0.3f), halo.copy(alpha = 0f)),
                    center = center,
                    radius = haloRadius
                ),
                radius = haloRadius,
                center = center
            )

            drawArc(
                color = NeonCyan.copy(alpha = 0.9f),
                startAngle = rotation,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(center.x - base * 0.78f, center.y - base * 0.78f),
                size = Size(base * 1.56f, base * 1.56f),
                style = Stroke(width = base * 0.045f)
            )

            drawArc(
                color = NeonPurple.copy(alpha = 0.85f),
                startAngle = counterRotation,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(center.x - base * 0.60f, center.y - base * 0.60f),
                size = Size(base * 1.20f, base * 1.20f),
                style = Stroke(width = base * 0.035f)
            )

            val coreRadius = base * 0.34f * coreScale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        NeonCyan,
                        NeonPurple.copy(alpha = 0.9f),
                        NeonBlue.copy(alpha = 0.15f)
                    ),
                    center = center,
                    radius = coreRadius
                ),
                radius = coreRadius,
                center = center
            )

            for (index in 0 until 3) {
                val angle = Math.toRadians((rotation + index * 120f).toDouble())
                val orbit = base * 0.78f
                drawCircle(
                    color = NeonCyan.copy(alpha = 0.9f),
                    radius = base * 0.032f,
                    center = Offset(
                        center.x + (cos(angle) * orbit).toFloat(),
                        center.y + (sin(angle) * orbit).toFloat()
                    )
                )
            }
        }
    }
}