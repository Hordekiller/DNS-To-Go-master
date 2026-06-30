package com.hololo.app.dnschanger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00FFC2),
    onPrimary = Color(0xFF040B13),
    primaryContainer = Color(0xFF0B1724),
    onPrimaryContainer = Color(0xFF00FFC2),
    secondary = Color(0xFF00A3FF),
    onSecondary = Color(0xFF040B13),
    tertiary = Color(0xFFB026FF),
    background = Color(0xFF040B13),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0B1724),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1A2A3A),
    onSurfaceVariant = Color(0xFF888888),
    error = Color(0xFFFF3131),
    onError = Color(0xFFFFFFFF),
    outline = Color(0x33333333),
    outlineVariant = Color(0x15FFFFFF),
)

@Composable
fun DnsChangerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
