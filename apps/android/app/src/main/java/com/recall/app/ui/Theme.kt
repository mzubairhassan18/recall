package com.recall.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RecallColors = darkColorScheme(
    primary = Color(0xFF6EA8FE),
    onPrimary = Color(0xFF0B1020),
    primaryContainer = Color(0xFF1D3A6E),
    onPrimaryContainer = Color(0xFFD7E5FF),
    secondary = Color(0xFF9BB7E8),
    onSecondary = Color(0xFF0B1020),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE7E9EE),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFE7E9EE),
    surfaceVariant = Color(0xFF1E2330),
    onSurfaceVariant = Color(0xFFB9C0CC),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0A0A),
    outline = Color(0xFF3A4150),
)

@Composable
fun RecallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RecallColors, content = content)
}
