package com.example.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.model.BuiltInLutPresets
import com.example.core.model.ClipKind
import com.example.core.model.SubtitleAlignment
import com.example.core.model.TimelineClip
import com.example.ui.theme.LocalTrackColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun ClipInspectorPanel(
  selectedClip: TimelineClip?,
  activeTab: InspectorTab,
  onSelectTab: (InspectorTab) -> Unit,
  onBeginEdit: () -> Unit,
  onEndEdit: () -> Unit,
  onUpdateTransform: (
    scale: Float?,
    rotationDegrees: Float?,
    offsetX: Float?,
    offsetY: Float?,
    opacity: Float?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onRotate90: () -> Unit,
  onUpdateColor: (
    brightness: Float?,
    contrast: Float?,
    saturation: Float?,
    exposure: Float?,
    temperature: Float?,
    vignette: Float?,
    lutPresetId: String?,
    clearLutPreset: Boolean,
    lutStrength: Float?,
    tint: Float?,
    shadows: Float?,
    midtones: Float?,
    highlights: Float?,
    blendOpacity: Float?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onSelectLutPreset: (presetId: String) -> Unit,
  onUpdateAudio: (
    volume: Float?,
    pan: Float?,
    fadeInMs: Long?,
    fadeOutMs: Long?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onUpdateSpeed: (speed: Float, fromSliderDrag: Boolean) -> Unit,
  onUpdateSubtitle: (
    text: String?,
    fontSizeSp: Float?,
    alignment: SubtitleAlignment?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onResetTab: (InspectorTab) -> Unit,
  onResetAll: () -> Unit,
  onOpenAudioSheet: () -> Unit,
  onOpenSubtitlesSheet: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val haptic = LocalHapticFeedback.current
  val trackColors = LocalTrackColors.current

  val kindBadgeText =
    when (selectedClip?.kind) {
      ClipKind.VIDEO -> "V1"
      ClipKind.ADJUSTMENT -> "LUT"
      ClipKind.AUDIO -> "A1"
      ClipKind.SUBTITLE -> "SUB"
      null -> "--"
    }
  val kindBadgeColor =
    when (selectedClip?.kind) {
      ClipKind.VIDEO -> trackColors.videoTrack
      ClipKind.ADJUSTMENT -> trackColors.adjustmentTrack
      ClipKind.AUDIO -> trackColors.audioTrack
      ClipKind.SUBTITLE -> trackColors.subtitleTrack
      null -> MaterialTheme.colorScheme.outline
    }

  val hasTransformMod =
    selectedClip?.videoConfig?.let {
      abs(it.scale - 1f) > 0.01f ||
        abs(it.rotationDegrees) > 0.5f ||
        abs(it.offsetX) > 0.01f ||
        abs(it.offsetY) > 0.01f ||
        abs(it.opacity - 1f) > 0.01f
    } == true

  val hasColorMod =
    when (selectedClip?.kind) {
      ClipKind.VIDEO -> selectedClip.videoConfig?.let { BuiltInLutPresets.resolveEffectiveColor(it).hasColorModifications } == true
      ClipKind.ADJUSTMENT ->
        selectedClip.adjustmentConfig?.let {
          BuiltInLutPresets.resolveEffectiveColor(it).hasColorModifications || it.blendOpacity < 0.99f
        } == true
      else -> false
    }

  val hasAudioMod =
    when (selectedClip?.kind) {
      ClipKind.VIDEO -> selectedClip.videoConfig?.let { abs(it.volume - 1f) > 0.01f || it.fadeInMs > 0L || it.fadeOutMs > 0L } == true
      ClipKind.AUDIO -> selectedClip.audioConfig?.let { abs(it.volume - 1f) > 0.01f || abs(it.pan) > 0.01f || it.fadeInMs > 0L || it.fadeOutMs > 0L } == true
      else -> false
    }

  val hasSpeedOrSubMod =
    selectedClip?.let { clip ->
      abs(clip.speed - 1f) > 0.01f || clip.kind == ClipKind.SUBTITLE
    } == true

  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 10.dp, bottomEnd = 10.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    tonalElevation = 3.dp,
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 4.dp)
        .testTag("adjustments_subpanel"),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // Pinned Active Clip Context Header (always stays visible while scrolling controls)
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Surface(
          color = kindBadgeColor.copy(alpha = 0.2f),
          shape = RoundedCornerShape(5.dp),
          border = BorderStroke(1.dp, kindBadgeColor.copy(alpha = 0.8f)),
        ) {
          Text(
            text = kindBadgeText,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            fontWeight = FontWeight.Bold,
            color = kindBadgeColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = selectedClip?.label ?: stringResource(R.string.title_inspector_console),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (selectedClip != null) {
            val activeLutName =
              when (selectedClip.kind) {
                ClipKind.VIDEO -> BuiltInLutPresets.findById(selectedClip.videoConfig?.lutPresetId)?.name
                ClipKind.ADJUSTMENT -> BuiltInLutPresets.findById(selectedClip.adjustmentConfig?.lutPresetId)?.name
                else -> null
              }
            Text(
              text =
                buildString {
                  append(formatTimecode(selectedClip.durationMs))
                  if (abs(selectedClip.speed - 1f) > 0.01f) {
                    append(" · ${"%.2f".format(selectedClip.speed)}x")
                  }
                  if (activeLutName != null) {
                    append(" · LUT: $activeLutName")
                  }
                },
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        if (selectedClip != null) {
          IconButton(
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onResetTab(activeTab)
            },
            modifier =
              Modifier
                .minimumInteractiveComponentSize()
                .testTag("inspector_reset_tab_button"),
          ) {
            Icon(
              imageVector = Icons.Default.RestartAlt,
              contentDescription = stringResource(R.string.action_inspector_reset_tab),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
            )
          }
        }

        IconButton(
          onClick = onClose,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("close_subpanel_button"),
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.cd_close_subpanel),
            modifier = Modifier.size(18.dp),
          )
        }
      }

      HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

      // Compact Category Tabs Row (always pinned above parameter viewport)
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        InspectorCategoryChip(
          label = stringResource(R.string.inspector_tab_transform),
          icon = Icons.Default.CropFree,
          isSelected = activeTab == InspectorTab.TRANSFORM,
          hasModified = hasTransformMod,
          onClick = { onSelectTab(InspectorTab.TRANSFORM) },
          testTag = "inspector_tab_TRANSFORM",
        )
        InspectorCategoryChip(
          label = stringResource(R.string.inspector_tab_color_lut),
          icon = Icons.Default.Palette,
          isSelected = activeTab == InspectorTab.COLOR_LUT,
          hasModified = hasColorMod,
          onClick = { onSelectTab(InspectorTab.COLOR_LUT) },
          testTag = "inspector_tab_COLOR_LUT",
        )
        InspectorCategoryChip(
          label = stringResource(R.string.inspector_tab_audio),
          icon = Icons.Default.GraphicEq,
          isSelected = activeTab == InspectorTab.AUDIO,
          hasModified = hasAudioMod,
          onClick = { onSelectTab(InspectorTab.AUDIO) },
          testTag = "inspector_tab_AUDIO",
        )
        val fourthLabel =
          when (selectedClip?.kind) {
            ClipKind.SUBTITLE -> stringResource(R.string.inspector_tab_subtitles)
            ClipKind.VIDEO, ClipKind.AUDIO -> stringResource(R.string.inspector_tab_speed)
            else -> stringResource(R.string.inspector_tab_speed_subtitles)
          }
        val fourthIcon =
          if (selectedClip?.kind == ClipKind.SUBTITLE) Icons.Default.Subtitles else Icons.Default.Speed
        InspectorCategoryChip(
          label = fourthLabel,
          icon = fourthIcon,
          isSelected = activeTab == InspectorTab.SPEED_SUBTITLES,
          hasModified = hasSpeedOrSubMod,
          onClick = { onSelectTab(InspectorTab.SPEED_SUBTITLES) },
          testTag = "inspector_tab_SPEED_SUBTITLES",
        )
      }

      HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

      if (selectedClip == null) {
        Text(
          text = stringResource(R.string.msg_inspector_select_clip),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
        )
      } else {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(max = 208.dp)
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          when (activeTab) {
            InspectorTab.TRANSFORM ->
              TransformInspectorTab(
                clip = selectedClip,
                onBeginEdit = onBeginEdit,
                onEndEdit = onEndEdit,
                onUpdateTransform = onUpdateTransform,
                onRotate90 = onRotate90,
                onResetAll = onResetAll,
              )

            InspectorTab.COLOR_LUT ->
              ColorLutInspectorTab(
                clip = selectedClip,
                onBeginEdit = onBeginEdit,
                onEndEdit = onEndEdit,
                onUpdateColor = onUpdateColor,
                onSelectLutPreset = onSelectLutPreset,
                onResetAll = onResetAll,
              )

            InspectorTab.AUDIO ->
              AudioInspectorTab(
                clip = selectedClip,
                onBeginEdit = onBeginEdit,
                onEndEdit = onEndEdit,
                onUpdateAudio = onUpdateAudio,
                onOpenAudioSheet = onOpenAudioSheet,
              )

            InspectorTab.SPEED_SUBTITLES ->
              SpeedAndSubtitlesInspectorTab(
                clip = selectedClip,
                onBeginEdit = onBeginEdit,
                onEndEdit = onEndEdit,
                onUpdateSpeed = onUpdateSpeed,
                onUpdateSubtitle = onUpdateSubtitle,
                onOpenSubtitlesSheet = onOpenSubtitlesSheet,
              )
          }
        }
      }
    }
  }
}

@Composable
private fun InspectorCategoryChip(
  label: String,
  icon: ImageVector,
  isSelected: Boolean,
  hasModified: Boolean,
  onClick: () -> Unit,
  testTag: String,
) {
  Surface(
    color =
      if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
      } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
      },
    shape = RoundedCornerShape(8.dp),
    border =
      BorderStroke(
        width = 1.dp,
        color =
          if (isSelected) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.outlineVariant
          },
      ),
    modifier =
      Modifier
        .heightIn(min = 34.dp)
        .clip(RoundedCornerShape(8.dp))
        .clickable(onClick = onClick)
        .testTag(testTag),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint =
          if (isSelected) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        modifier = Modifier.size(14.dp),
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
        color =
          if (isSelected) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        maxLines = 1,
      )
      if (hasModified) {
        Box(
          modifier =
            Modifier
              .size(5.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.secondary),
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransformInspectorTab(
  clip: TimelineClip,
  onBeginEdit: () -> Unit,
  onEndEdit: () -> Unit,
  onUpdateTransform: (
    scale: Float?,
    rotationDegrees: Float?,
    offsetX: Float?,
    offsetY: Float?,
    opacity: Float?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onRotate90: () -> Unit,
  onResetAll: () -> Unit,
) {
  if (clip.kind != ClipKind.VIDEO) {
    InspectorUnsupportedNotice(stringResource(R.string.msg_inspector_no_transform))
    return
  }
  val cfg = clip.videoConfig ?: return

  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    AssistChip(
      onClick = onRotate90,
      leadingIcon = {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.RotateRight,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      },
      label = { Text(stringResource(R.string.action_rotate_90)) },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("adjust_rotate_90_button"),
    )
    AssistChip(
      onClick = onResetAll,
      leadingIcon = {
        Icon(
          imageVector = Icons.Default.RestartAlt,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      },
      label = { Text(stringResource(R.string.action_reset_adjustments)) },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("adjust_reset_button"),
    )
  }

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_scale),
    valueText = "${"%.2f".format(cfg.scale)}x",
    value = cfg.scale,
    valueRange = 0.5f..2.0f,
    defaultValue = 1.0f,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateTransform(next, null, null, null, null, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateTransform(1.0f, null, null, null, null, false) },
    testTag = "adjust_scale_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_rotation),
    valueText = "${"%.1f".format(cfg.rotationDegrees)}°",
    value = cfg.rotationDegrees,
    valueRange = -180f..180f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateTransform(null, next, null, null, null, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateTransform(null, 0.0f, null, null, null, false) },
    testTag = "adjust_rotation_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_position_x),
    valueText = "${if (cfg.offsetX >= 0f) "+" else ""}${"%.2f".format(cfg.offsetX)}",
    value = cfg.offsetX,
    valueRange = -0.5f..0.5f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateTransform(null, null, next, null, null, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateTransform(null, null, 0.0f, null, null, false) },
    testTag = "adjust_offset_x_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_position_y),
    valueText = "${if (cfg.offsetY >= 0f) "+" else ""}${"%.2f".format(cfg.offsetY)}",
    value = cfg.offsetY,
    valueRange = -0.5f..0.5f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateTransform(null, null, null, next, null, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateTransform(null, null, null, 0.0f, null, false) },
    testTag = "adjust_offset_y_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_opacity),
    valueText = "${(cfg.opacity * 100f).roundToInt()}%",
    value = cfg.opacity,
    valueRange = 0.1f..1.0f,
    defaultValue = 1.0f,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateTransform(null, null, null, null, next, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateTransform(null, null, null, null, 1.0f, false) },
    testTag = "adjust_opacity_slider",
  )
}

@Composable
private fun ColorLutInspectorTab(
  clip: TimelineClip,
  onBeginEdit: () -> Unit,
  onEndEdit: () -> Unit,
  onUpdateColor: (
    brightness: Float?,
    contrast: Float?,
    saturation: Float?,
    exposure: Float?,
    temperature: Float?,
    vignette: Float?,
    lutPresetId: String?,
    clearLutPreset: Boolean,
    lutStrength: Float?,
    tint: Float?,
    shadows: Float?,
    midtones: Float?,
    highlights: Float?,
    blendOpacity: Float?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onSelectLutPreset: (presetId: String) -> Unit,
  onResetAll: () -> Unit,
) {
  val brightness: Float
  val contrast: Float
  val saturation: Float
  val exposure: Float
  val temperature: Float
  val tint: Float
  val shadows: Float
  val midtones: Float
  val highlights: Float
  val vignette: Float
  val lutPresetId: String?
  val lutStrength: Float
  val blendOpacity: Float

  when (clip.kind) {
    ClipKind.VIDEO -> {
      val cfg = clip.videoConfig ?: return
      brightness = cfg.brightness
      contrast = cfg.contrast
      saturation = cfg.saturation
      exposure = cfg.exposure
      temperature = cfg.temperature
      tint = cfg.tint
      shadows = cfg.shadows
      midtones = cfg.midtones
      highlights = cfg.highlights
      vignette = cfg.vignette
      lutPresetId = cfg.lutPresetId
      lutStrength = cfg.lutStrength
      blendOpacity = 1.0f
    }
    ClipKind.ADJUSTMENT -> {
      val cfg = clip.adjustmentConfig ?: return
      brightness = cfg.brightness
      contrast = cfg.contrast
      saturation = cfg.saturation
      exposure = cfg.exposure
      temperature = cfg.temperature
      tint = cfg.tint
      shadows = cfg.shadows
      midtones = cfg.midtones
      highlights = cfg.highlights
      vignette = cfg.vignette
      lutPresetId = cfg.lutPresetId
      lutStrength = cfg.lutStrength
      blendOpacity = cfg.blendOpacity
    }
    else -> {
      InspectorUnsupportedNotice(stringResource(R.string.msg_inspector_no_color))
      return
    }
  }

  fun updateColor(
    brightness: Float? = null,
    contrast: Float? = null,
    saturation: Float? = null,
    exposure: Float? = null,
    temperature: Float? = null,
    vignette: Float? = null,
    lutStrength: Float? = null,
    tint: Float? = null,
    shadows: Float? = null,
    midtones: Float? = null,
    highlights: Float? = null,
    blendOpacity: Float? = null,
    fromSliderDrag: Boolean = false,
  ) {
    onUpdateColor(
      brightness,
      contrast,
      saturation,
      exposure,
      temperature,
      vignette,
      null,
      false,
      lutStrength,
      tint,
      shadows,
      midtones,
      highlights,
      blendOpacity,
      fromSliderDrag,
    )
  }

  LutPresetThumbnailStrip(
    selectedLutId = lutPresetId,
    lutStrength = lutStrength,
    onSelectLut = onSelectLutPreset,
    onBeginDragStrength = onBeginEdit,
    onStrengthChange = { next -> updateColor(lutStrength = next, fromSliderDrag = true) },
    onEndDragStrength = onEndEdit,
    onResetStrength = { updateColor(lutStrength = 1.0f, fromSliderDrag = false) },
  )

  HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_exposure),
    valueText = "${if (exposure >= 0f) "+" else ""}${"%.2f".format(exposure)} EV",
    value = exposure,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(exposure = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(exposure = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_exposure_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_brightness),
    valueText = "${if (brightness >= 0f) "+" else ""}${(brightness * 100f).roundToInt()}",
    value = brightness,
    valueRange = -0.5f..0.5f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(brightness = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(brightness = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_brightness_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_contrast),
    valueText = "${(contrast * 100f).roundToInt()}%",
    value = contrast,
    valueRange = 0.5f..1.8f,
    defaultValue = 1.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(contrast = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(contrast = 1.0f, fromSliderDrag = false) },
    testTag = "adjust_contrast_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_saturation),
    valueText = "${(saturation * 100f).roundToInt()}%",
    value = saturation,
    valueRange = 0.0f..2.0f,
    defaultValue = 1.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(saturation = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(saturation = 1.0f, fromSliderDrag = false) },
    testTag = "adjust_saturation_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_temperature),
    valueText = "${if (temperature >= 0f) "+" else ""}${(temperature * 100f).roundToInt()}",
    value = temperature,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(temperature = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(temperature = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_temperature_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_tint),
    valueText = "${if (tint >= 0f) "+" else ""}${(tint * 100f).roundToInt()}",
    value = tint,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(tint = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(tint = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_tint_slider",
  )

  HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

  Text(
    text = stringResource(R.string.label_tonal_balance_section),
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.secondary,
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_shadows_lift),
    valueText = "${if (shadows >= 0f) "+" else ""}${(shadows * 100f).roundToInt()}",
    value = shadows,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(shadows = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(shadows = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_shadows_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_midtones_gamma),
    valueText = "${if (midtones >= 0f) "+" else ""}${(midtones * 100f).roundToInt()}",
    value = midtones,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(midtones = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(midtones = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_midtones_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_highlights_gain),
    valueText = "${if (highlights >= 0f) "+" else ""}${(highlights * 100f).roundToInt()}",
    value = highlights,
    valueRange = -1.0f..1.0f,
    defaultValue = 0.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(highlights = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(highlights = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_highlights_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_vignette),
    valueText = "${(vignette * 100f).roundToInt()}%",
    value = vignette,
    valueRange = 0.0f..1.0f,
    defaultValue = 0.0f,
    onValueChange = { next ->
      onBeginEdit()
      updateColor(vignette = next, fromSliderDrag = true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { updateColor(vignette = 0.0f, fromSliderDrag = false) },
    testTag = "adjust_vignette_slider",
  )

  if (clip.kind == ClipKind.ADJUSTMENT) {
    PrecisionConsoleSlider(
      label = stringResource(R.string.label_adjustment_blend_opacity),
      valueText = "${(blendOpacity * 100f).roundToInt()}%",
      value = blendOpacity,
      valueRange = 0.0f..1.0f,
      defaultValue = 1.0f,
      onValueChange = { next ->
        onBeginEdit()
        updateColor(blendOpacity = next, fromSliderDrag = true)
      },
      onValueChangeFinished = onEndEdit,
      onReset = { updateColor(blendOpacity = 1.0f, fromSliderDrag = false) },
      testTag = "adjust_layer_blend_slider",
    )
  }

  AssistChip(
    onClick = onResetAll,
    leadingIcon = {
      Icon(
        imageVector = Icons.Default.RestartAlt,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
      )
    },
    label = { Text(stringResource(R.string.action_reset_adjustments)) },
    modifier = Modifier.minimumInteractiveComponentSize().testTag("adjust_reset_button"),
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioInspectorTab(
  clip: TimelineClip,
  onBeginEdit: () -> Unit,
  onEndEdit: () -> Unit,
  onUpdateAudio: (
    volume: Float?,
    pan: Float?,
    fadeInMs: Long?,
    fadeOutMs: Long?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onOpenAudioSheet: () -> Unit,
) {
  val volume: Float
  val pan: Float
  val fadeInMs: Long
  val fadeOutMs: Long
  val supportsPan: Boolean

  when (clip.kind) {
    ClipKind.VIDEO -> {
      val cfg = clip.videoConfig ?: return
      volume = cfg.volume
      pan = 0.0f
      fadeInMs = cfg.fadeInMs
      fadeOutMs = cfg.fadeOutMs
      supportsPan = false
    }
    ClipKind.AUDIO -> {
      val cfg = clip.audioConfig ?: return
      volume = cfg.volume
      pan = cfg.pan
      fadeInMs = cfg.fadeInMs
      fadeOutMs = cfg.fadeOutMs
      supportsPan = true
    }
    else -> {
      InspectorUnsupportedNotice(stringResource(R.string.msg_inspector_no_audio))
      return
    }
  }

  val maxFadeMs = clip.durationMs.coerceIn(500L, 4_000L).toFloat()

  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    AssistChip(
      onClick = { onUpdateAudio(0.0f, null, null, null, false) },
      leadingIcon = {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.VolumeOff,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      },
      label = { Text("0%") },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("audio_quick_mute_chip"),
    )
    AssistChip(
      onClick = { onUpdateAudio(1.0f, null, null, null, false) },
      leadingIcon = {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.VolumeUp,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      },
      label = { Text("100%") },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("audio_quick_unity_chip"),
    )
    AssistChip(
      onClick = onOpenAudioSheet,
      leadingIcon = {
        Icon(
          imageVector = Icons.Default.Tune,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      },
      label = { Text(stringResource(R.string.action_open_audio_sheet)) },
      modifier = Modifier.minimumInteractiveComponentSize().testTag("inspector_open_audio_sheet"),
    )
  }

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_audio_volume),
    valueText = "${(volume * 100f).roundToInt()}%",
    value = volume,
    valueRange = 0f..2.0f,
    defaultValue = 1.0f,
    bipolarOrigin = true,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateAudio(next, null, null, null, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateAudio(1.0f, null, null, null, false) },
    testTag = "inspector_audio_volume_slider",
  )

  if (supportsPan) {
    val panText =
      when {
        abs(pan) < 0.03f -> "C"
        pan < 0f -> "L ${(-pan * 100f).roundToInt()}"
        else -> "R ${(pan * 100f).roundToInt()}"
      }
    PrecisionConsoleSlider(
      label = stringResource(R.string.label_audio_pan),
      valueText = panText,
      value = pan,
      valueRange = -1.0f..1.0f,
      defaultValue = 0.0f,
      bipolarOrigin = true,
      onValueChange = { next ->
        onBeginEdit()
        onUpdateAudio(null, next, null, null, true)
      },
      onValueChangeFinished = onEndEdit,
      onReset = { onUpdateAudio(null, 0.0f, null, null, false) },
      testTag = "inspector_audio_pan_slider",
    )
  }

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_audio_fade_in),
    valueText = "$fadeInMs ms",
    value = fadeInMs.toFloat(),
    valueRange = 0f..maxFadeMs,
    defaultValue = 0f,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateAudio(null, null, next.roundToLong(), null, true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateAudio(null, null, 0L, null, false) },
    testTag = "inspector_audio_fade_in_slider",
  )

  PrecisionConsoleSlider(
    label = stringResource(R.string.label_audio_fade_out),
    valueText = "$fadeOutMs ms",
    value = fadeOutMs.toFloat(),
    valueRange = 0f..maxFadeMs,
    defaultValue = 0f,
    onValueChange = { next ->
      onBeginEdit()
      onUpdateAudio(null, null, null, next.roundToLong(), true)
    },
    onValueChangeFinished = onEndEdit,
    onReset = { onUpdateAudio(null, null, null, 0L, false) },
    testTag = "inspector_audio_fade_out_slider",
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedAndSubtitlesInspectorTab(
  clip: TimelineClip,
  onBeginEdit: () -> Unit,
  onEndEdit: () -> Unit,
  onUpdateSpeed: (speed: Float, fromSliderDrag: Boolean) -> Unit,
  onUpdateSubtitle: (
    text: String?,
    fontSizeSp: Float?,
    alignment: SubtitleAlignment?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onOpenSubtitlesSheet: () -> Unit,
) {
  when (clip.kind) {
    ClipKind.VIDEO, ClipKind.AUDIO -> {
      val speedPresets = listOf(0.5f, 1.0f, 1.5f, 2.0f)
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        speedPresets.forEach { presetSpeed ->
          FilterChip(
            selected = abs(clip.speed - presetSpeed) < 0.03f,
            onClick = { onUpdateSpeed(presetSpeed, false) },
            label = { Text("${presetSpeed}x") },
            modifier = Modifier.testTag("speed_preset_${presetSpeed}x"),
          )
        }
      }

      PrecisionConsoleSlider(
        label = stringResource(R.string.label_clip_speed),
        valueText = "${"%.2f".format(clip.speed)}x",
        value = clip.speed,
        valueRange = 0.25f..4.0f,
        defaultValue = 1.0f,
        bipolarOrigin = true,
        onValueChange = { next ->
          onBeginEdit()
          onUpdateSpeed(next, true)
        },
        onValueChangeFinished = onEndEdit,
        onReset = { onUpdateSpeed(1.0f, false) },
        testTag = "inspector_speed_slider",
      )
    }

    ClipKind.SUBTITLE -> {
      val subCfg = clip.subtitleConfig ?: return
      OutlinedTextField(
        value = subCfg.text,
        onValueChange = { nextText ->
          onBeginEdit()
          onUpdateSubtitle(nextText, null, null, true)
        },
        label = { Text(stringResource(R.string.label_subtitle_text)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("inspector_subtitle_text_field"),
      )

      PrecisionConsoleSlider(
        label = stringResource(R.string.label_subtitle_size),
        valueText = "${subCfg.fontSizeSp.roundToInt()} sp",
        value = subCfg.fontSizeSp,
        valueRange = 12f..40f,
        defaultValue = 20f,
        onValueChange = { nextSize ->
          onBeginEdit()
          onUpdateSubtitle(null, nextSize, null, true)
        },
        onValueChangeFinished = onEndEdit,
        onReset = { onUpdateSubtitle(null, 20f, null, false) },
        testTag = "inspector_subtitle_size_slider",
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.label_subtitle_align),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SubtitleAlignment.entries.forEach { alignment ->
          val shortName =
            when (alignment) {
              SubtitleAlignment.TOP_CENTER -> "Sup"
              SubtitleAlignment.MIDDLE_CENTER -> "Centro"
              SubtitleAlignment.BOTTOM_CENTER -> "Inf"
            }
          FilterChip(
            selected = subCfg.alignment == alignment,
            onClick = { onUpdateSubtitle(null, null, alignment, false) },
            label = { Text(shortName) },
            modifier = Modifier.testTag("subtitle_align_${alignment.name}"),
          )
        }
        Spacer(modifier = Modifier.weight(1f))
        AssistChip(
          onClick = onOpenSubtitlesSheet,
          label = { Text(stringResource(R.string.action_open_subtitles_sheet)) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("inspector_open_subtitles_sheet"),
        )
      }
    }

    ClipKind.ADJUSTMENT -> {
      InspectorUnsupportedNotice(stringResource(R.string.msg_inspector_no_speed))
    }
  }
}

@Composable
private fun InspectorUnsupportedNotice(message: String) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      text = message,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
    )
  }
}
