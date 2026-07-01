package com.hololo.app.dnschanger.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
    onSurfaceVariant = Color(0xFFB0B0B0),
    error = Color(0xFFFF3131),
    onError = Color(0xFFFFFFFF),
    outline = Color(0x33333333),
    outlineVariant = Color(0x15FFFFFF),
    surfaceTint = Color(0xFF00FFC2),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00A87E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCEF5E8),
    onPrimaryContainer = Color(0xFF002119),
    secondary = Color(0xFF0080C8),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF8B2FC0),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFF44474F),
    error = Color(0xFFD32F2F),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFFC4C7CD),
    outlineVariant = Color(0xFFDADCE0),
    surfaceTint = Color(0xFF00A87E),
)

@Composable
fun DnsChangerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
