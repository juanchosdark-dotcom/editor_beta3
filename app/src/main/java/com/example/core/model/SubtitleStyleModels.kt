package com.example.core.model

import com.squareup.moshi.JsonClass

/**
 * Reusable saved subtitle style preset that can be applied across subtitle blocks in batch.
 */
@JsonClass(generateAdapter = true)
data class SubtitleStylePreset(
  val styleId: String,
  val name: String,
  val fontFamily: String = "sans-serif",
  val fontSizeSp: Float = 20f,
  val textColorHex: String = "#FFFFFF",
  val backgroundColorHex: String = "#80000000",
  val alignment: SubtitleAlignment = SubtitleAlignment.BOTTOM_CENTER,
  val isBuiltIn: Boolean = false,
) {
  fun toSubtitleConfig(text: String): SubtitleClipConfig =
    SubtitleClipConfig(
      text = text,
      fontFamily = fontFamily,
      fontSizeSp = fontSizeSp,
      textColorHex = textColorHex,
      backgroundColorHex = backgroundColorHex,
      alignment = alignment,
    )
}

object BuiltInSubtitleStyles {
  val CLASSIC_CINEMA =
    SubtitleStylePreset(
      styleId = "style_classic",
      name = "Cine Clásico",
      fontFamily = "sans-serif",
      fontSizeSp = 20f,
      textColorHex = "#FFFFFF",
      backgroundColorHex = "#99000000",
      alignment = SubtitleAlignment.BOTTOM_CENTER,
      isBuiltIn = true,
    )

  val HIGH_CONTRAST_AMBER =
    SubtitleStylePreset(
      styleId = "style_amber",
      name = "Impacto Ámbar",
      fontFamily = "sans-serif-medium",
      fontSizeSp = 24f,
      textColorHex = "#FBBF24",
      backgroundColorHex = "#CC0B0D12",
      alignment = SubtitleAlignment.BOTTOM_CENTER,
      isBuiltIn = true,
    )

  val MINIMAL_CLEAN =
    SubtitleStylePreset(
      styleId = "style_minimal",
      name = "Minimalista",
      fontFamily = "sans-serif-light",
      fontSizeSp = 18f,
      textColorHex = "#F1F5F9",
      backgroundColorHex = "#00000000",
      alignment = SubtitleAlignment.BOTTOM_CENTER,
      isBuiltIn = true,
    )

  val TOP_HEADER_CAPTION =
    SubtitleStylePreset(
      styleId = "style_top",
      name = "Título Superior",
      fontFamily = "sans-serif-condensed",
      fontSizeSp = 22f,
      textColorHex = "#0B0D12",
      backgroundColorHex = "#E638BDF8",
      alignment = SubtitleAlignment.TOP_CENTER,
      isBuiltIn = true,
    )

  val ALL: List<SubtitleStylePreset> =
    listOf(CLASSIC_CINEMA, HIGH_CONTRAST_AMBER, MINIMAL_CLEAN, TOP_HEADER_CAPTION)
}
