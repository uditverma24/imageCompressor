package com.imagecompressor.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E5FF),
    onPrimaryContainer = Color(0xFF1B1B5E),
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F2EE),
    onSecondaryContainer = Color(0xFF053B36),
    tertiary = Color(0xFFB45309),
    tertiaryContainer = Color(0xFFFFEBD0),
    onTertiaryContainer = Color(0xFF4A2300),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF12141C),
    surface = Color.White,
    onSurface = Color(0xFF12141C),
    surfaceVariant = Color(0xFFEDEFF6),
    onSurfaceVariant = Color(0xFF5B6070),
    outline = Color(0xFFB8BDCC),
    outlineVariant = Color(0xFFDDE0EA),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4BBFF),
    onPrimary = Color(0xFF1B1B5E),
    primaryContainer = Color(0xFF34307F),
    onPrimaryContainer = Color(0xFFE3E5FF),
    secondary = Color(0xFF7FD6CC),
    onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF0B4F49),
    onSecondaryContainer = Color(0xFFD5F2EE),
    tertiary = Color(0xFFFFB86B),
    tertiaryContainer = Color(0xFF5C3300),
    onTertiaryContainer = Color(0xFFFFEBD0),
    background = Color(0xFF0E1016),
    onBackground = Color(0xFFE4E6EF),
    surface = Color(0xFF171A22),
    onSurface = Color(0xFFE4E6EF),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFA6ABBB),
    outline = Color(0xFF5F6474),
    outlineVariant = Color(0xFF2E3341),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5F1412),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun ImageCompressorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        content = content
    )
}
