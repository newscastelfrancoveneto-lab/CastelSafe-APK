package io.github.andreadp271.castelsafe.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.andreadp271.castelsafe.data.Level

/** Bordeaux CastelSafe (lo stesso della PWA) con neutri leggermente caldi. */
private val Light = lightColorScheme(
    primary = Color(0xFF8D021F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE8EC),
    onPrimaryContainer = Color(0xFF5A0014),
    secondary = Color(0xFF6B5A5D),
    secondaryContainer = Color(0xFFF2E4E6),
    onSecondaryContainer = Color(0xFF2A1B1E),
    background = Color(0xFFF4F2F3),
    surface = Color(0xFFF4F2F3),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0EBEC),
    surfaceContainerHigh = Color(0xFFEAE4E5),
    surfaceContainerHighest = Color(0xFFE3DCDE),
    onSurface = Color(0xFF1F1A1B),
    onSurfaceVariant = Color(0xFF5E5557),
    outline = Color(0xFF8E8486),
    outlineVariant = Color(0xFFDDD3D5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB2BE),
    onPrimary = Color(0xFF5A0014),
    primaryContainer = Color(0xFF7A0A24),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFFD9C1C4),
    secondaryContainer = Color(0xFF3E3033),
    onSecondaryContainer = Color(0xFFF2DDE0),
    background = Color(0xFF17181C),
    surface = Color(0xFF17181C),
    surfaceContainerLowest = Color(0xFF111215),
    surfaceContainerLow = Color(0xFF1E1F23),
    surfaceContainer = Color(0xFF232428),
    surfaceContainerHigh = Color(0xFF2B2C31),
    surfaceContainerHighest = Color(0xFF35363B),
    onSurface = Color(0xFFE8E1E2),
    onSurfaceVariant = Color(0xFFC2B8BA),
    outline = Color(0xFF8C8284),
    outlineVariant = Color(0xFF45393C),
)

@Composable
fun CastelSafeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Colori dei livelli di allerta (scala ufficiale verde → rosso). */
fun Level.color(): Color = when (this) {
    Level.VERDE -> Color(0xFF16A34A)
    Level.GIALLO -> Color(0xFFEAB308)
    Level.ARANCIONE -> Color(0xFFF97316)
    Level.ROSSO -> Color(0xFFDC2626)
    Level.NESSUNO -> Color(0xFF94A3B8)
}

/** Testo leggibile sopra il colore del livello. */
fun Level.onColor(): Color = if (this == Level.GIALLO) Color(0xFF3B2F00) else Color.White
