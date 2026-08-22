package com.povwheel.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Палитра та же, что на веб-странице: два интерфейса не должны выглядеть как
// два разных продукта.
val Accent = Color(0xFF3B82F6)
val Ok = Color(0xFF22C55E)
val Warn = Color(0xFFF59E0B)
val Danger = Color(0xFFEF4444)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF64748B),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF141A22),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1D2531),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Danger
)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF475569),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE8EDF4),
    onSurfaceVariant = Color(0xFF52627A),
    error = Danger
)

@Composable
fun PovTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
