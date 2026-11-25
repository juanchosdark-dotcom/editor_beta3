package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class TrackSemanticColors(
  val videoTrack: Color,
  val audioTrack: Color,
  val subtitleTrack: Color,
  val adjustmentTrack: Color,
  val playhead: Color,
  val markerAmber: Color,
  val markerCyan: Color,
  val markerCoral: Color,
  val markerEmerald: Color,
  val markerViolet: Color,
)

val LocalTrackColors =
  staticCompositionLocalOf {
    TrackSemanticColors(
      videoTrack = StudioCyan,
      audioTrack = StudioEmerald,
      subtitleTrack = StudioAmber,
      adjustmentTrack = StudioViolet,
      playhead = StudioCoral,
      markerAmber = StudioAmber,
      markerCyan = StudioCyan,
      markerCoral = StudioRose,
      markerEmerald = StudioEmerald,
      markerViolet = StudioViolet,
    )
  }

private val StudioDarkColorScheme =
  darkColorScheme(
    primary = StudioCoral,
    onPrimary = StudioOnCoral,
    secondary = StudioCyan,
    onSecondary = StudioObsidian,
    tertiary = StudioViolet,
    onTertiary = StudioObsidian,
    background = StudioObsidian,
    onBackground = StudioTextPrimary,
    surface = StudioSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioSurfaceVariant,
    onSurfaceVariant = StudioTextSecondary,
    surfaceContainerLowest = StudioMatteBlack,
    surfaceContainerHigh = StudioSurfaceHighest,
    outline = StudioOutline,
    outlineVariant = StudioBorderSubtle,
    error = StudioRose,
    onError = StudioOnCoral,
  )

private val StudioLightColorScheme =
  lightColorScheme(
    primary = StudioCoral,
    onPrimary = StudioOnCoral,
    secondary = StudioCyan,
    onSecondary = StudioLightTextPrimary,
    tertiary = StudioViolet,
    background = StudioLightBackground,
    onBackground = StudioLightTextPrimary,
    surface = StudioLightSurface,
    onSurface = StudioLightTextPrimary,
    surfaceVariant = StudioLightSurfaceVariant,
    onSurfaceVariant = StudioLightTextSecondary,
    error = StudioRose,
    onError = StudioOnCoral,
  )

@Composable
fun FrameLineTheme(
  darkTheme: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) StudioDarkColorScheme else StudioLightColorScheme
  val trackColors =
    TrackSemanticColors(
      videoTrack = StudioCyan,
      audioTrack = StudioEmerald,
      subtitleTrack = StudioAmber,
      adjustmentTrack = StudioViolet,
      playhead = StudioCoral,
      markerAmber = StudioAmber,
      markerCyan = StudioCyan,
      markerCoral = StudioRose,
      markerEmerald = StudioEmerald,
      markerViolet = StudioViolet,
    )

  CompositionLocalProvider(LocalTrackColors provides trackColors) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content,
    )
  }
}
