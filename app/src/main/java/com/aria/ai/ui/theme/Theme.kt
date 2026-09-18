package com.aria.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** Aria is dark-first: the quantum HUD is tuned for OLED-black backgrounds. */
private val AriaDarkScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = DeepSpace,
    primaryContainer = CyanDeep,
    onPrimaryContainer = MistWhite,
    secondary = NeonPurple,
    onSecondary = DeepSpace,
    secondaryContainer = PurpleDeep,
    onSecondaryContainer = MistWhite,
    tertiary = NeonBlue,
    onTertiary = DeepSpace,
    background = DeepSpace,
    onBackground = MistWhite,
    surface = SurfaceDark,
    onSurface = MistWhite,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = MistDim,
    outline = LineGray,
    error = AriaError,
    onError = DeepSpace
)

@Composable
fun AriaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AriaDarkScheme,
        typography = AriaTypography,
        content = content
    )
}