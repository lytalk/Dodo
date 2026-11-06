package com.lytalk.dodo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFF8FDBB2),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF1C3D32),
    onPrimaryContainer = Color(0xFFB8F0D2),
    secondary = Color(0xFFE7C27A),
    onSecondary = Color(0xFF3D2E0A),
    background = Color(0xFF121714),
    onBackground = Color(0xFFE3E8E4),
    surface = Color(0xFF1C2420),
    onSurface = Color(0xFFE3E8E4),
    surfaceVariant = Color(0xFF2A3330),
    onSurfaceVariant = Color(0xFFC5D0CB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun DodoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
