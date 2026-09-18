package com.aria.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aria.ai.ui.theme.LineSoft
import com.aria.ai.ui.theme.SurfaceGlass
import com.aria.ai.ui.theme.SurfaceGlassDeep

/**
 * Frosted "glass" surface used across Aria: a subtle vertical gradient with a
 * hairline neon border. Implemented with Compose draw modifiers only (no blur
 * effects, so it stays cheap on low-end devices).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    borderColor: Color = LineSoft,
    cornerRadius: Dp = 20.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(SurfaceGlass, SurfaceGlassDeep)))
            .border(1.dp, borderColor, shape)
            .padding(contentPadding),
        content = content
    )
}