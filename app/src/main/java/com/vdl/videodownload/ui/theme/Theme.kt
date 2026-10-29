package com.vdl.videodownload.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private val BrandBlue = Color(0xFF3B5BFE)
private val BrandBlueDark = Color(0xFF9DB0FF)
private val BrandPink = Color(0xFFFF4D8D)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF0B1B5E),
    secondary = BrandPink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E4),
    onSecondaryContainer = Color(0xFF3B0720),
    tertiary = Color(0xFF00897B),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF13151A),
    surface = Color.White,
    onSurface = Color(0xFF13151A),
    surfaceVariant = Color(0xFFEFF1F6),
    onSurfaceVariant = Color(0xFF4A4F5C),
    outline = Color(0xFFC9CDD8),
    outlineVariant = Color(0xFFE2E5EE),
    error = Color(0xFFD3302F),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceContainer = Color(0xFFF2F4F9)
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF06207A),
    primaryContainer = Color(0xFF2743C9),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFFFB1C8),
    onSecondary = Color(0xFF5E1130),
    secondaryContainer = Color(0xFF8A1F49),
    onSecondaryContainer = Color(0xFFFFD9E4),
    tertiary = Color(0xFF4DD0C4),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6E8EF),
    surface = Color(0xFF171B22),
    onSurface = Color(0xFFE6E8EF),
    surfaceVariant = Color(0xFF232833),
    onSurfaceVariant = Color(0xFFB6BCCB),
    outline = Color(0xFF3A4150),
    outlineVariant = Color(0xFF2A303B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainer = Color(0xFF1C2129)
)

@Composable
fun VideoDownloadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
