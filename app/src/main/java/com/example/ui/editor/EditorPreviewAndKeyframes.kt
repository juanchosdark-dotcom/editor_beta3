package com.example.ui.editor

import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.core.ai.api.DeviceProfile
import com.example.core.media.playback.PreviewPlayerController
import com.example.core.media.playback.PreviewPlayerState
import com.example.core.model.ClipKind
import com.example.core.model.ColorGradingEngine
import com.example.core.model.KeyframeInterpolation
import com.example.core.model.KeyframeValidator
import com.example.core.model.TimelineClip
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
internal fun Media3PreviewPlayerCard(
  previewController: PreviewPlayerController?,
  previewState: PreviewPlayerState,
  deviceProfile: DeviceProfile,
  activeVideoClip: TimelineClip?,
  activeAdjustmentClip: TimelineClip?,
  activeSubtitleText: String?,
  playheadMs: Long,
  onRetryPreview: () -> Unit,
  onDismissError: () -> Unit,
  onOpenMediaPicker: () -> Unit,
  modifier: Modifier = Modifier,
  fillAvailableHeight: Boolean = false,
  previewHeight: Dp = 180.dp,
  compactDiagnostics: Boolean = false,
  outerHorizontalPadding: Dp = 12.dp,
  outerVerticalPadding: Dp = 4.dp,
) {
  val isInspection = LocalInspectionMode.current
  val lifecycleOwner = LocalLifecycleOwner.current

  // Pause immediately on route change or ON_PAUSE/ON_STOP and restore cleanly after rotation.
  DisposableEffect(lifecycleOwner, previewController) {
    val observer =
      LifecycleEventObserver { _, event ->
        when (event) {
          Lifecycle.Event.ON_PAUSE,
          Lifecycle.Event.ON_STOP, -> previewController?.pausePlayback()
          else -> Unit
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    previewController?.restoreIfNeeded(playheadMs)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      previewController?.pausePlayback()
    }
  }

  val videoCfg = activeVideoClip?.videoConfig
  val adjCfg = activeAdjustmentClip?.adjustmentConfig
  val offsetInClip =
    if (activeVideoClip != null) {
      (playheadMs - activeVideoClip.timelineStartMs).coerceIn(0L, activeVideoClip.durationMs)
    } else {
      0L
    }
  val evaluated = activeVideoClip?.evaluatePropertiesAt(offsetInClip)
  val colorPipeline =
    ColorGradingEngine.buildPipeline(
      videoCfg = videoCfg,
      adjustmentCfg = adjCfg,
      evaluatedClipOpacity = evaluated?.opacity,
    )
  val shadowSample =
    ColorGradingEngine.evaluatePixelThroughPipeline(0.14f, 0.16f, 0.20f, colorPipeline)
  val midtoneSample =
    ColorGradingEngine.evaluatePixelThroughPipeline(0.32f, 0.38f, 0.48f, colorPipeline)
  val highlightSample =
    ColorGradingEngine.evaluatePixelThroughPipeline(0.68f, 0.72f, 0.78f, colorPipeline)
  val activeLutTint = colorPipeline.combinedGrade.activeLut

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .let { if (fillAvailableHeight) it.fillMaxHeight() else it }
        .padding(horizontal = outerHorizontalPadding, vertical = outerVerticalPadding),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
      shape = RoundedCornerShape(10.dp),
      border =
        BorderStroke(
          width = 1.dp,
          color =
            if (previewState.uiError != null) {
              MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
            } else {
              MaterialTheme.colorScheme.outline
            },
        ),
      modifier =
        Modifier
          .fillMaxWidth()
          .let { if (fillAvailableHeight) it.weight(1f) else it },
    ) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .let { if (fillAvailableHeight) it.fillMaxHeight() else it.height(previewHeight) }
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
      ) {
        if (!isInspection && previewController != null && previewState.hasPlayableMedia) {
          AndroidView(
            factory = { context ->
              PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setKeepContentOnPlayerReset(true)
                player = previewController.getOrCreatePlayer(deviceProfile)
              }
            },
            update = { view ->
              val targetPlayer = previewController.getOrCreatePlayer(deviceProfile)
              if (view.player !== targetPlayer) {
                view.player = targetPlayer
              }
            },
            onRelease = { view -> runCatching { view.player = null } },
            modifier = Modifier.fillMaxSize().testTag("media3_player_view"),
          )
        } else {
          // Precision offline/standby viewer monitor preserving transform (X/Y, scale, rotation, opacity) & color preview
          val demoScale = (evaluated?.scale ?: 1f).coerceIn(KeyframeValidator.MIN_SCALE, KeyframeValidator.MAX_SCALE)
          val demoRotation = evaluated?.rotationDegrees ?: 0f
          val demoAlpha = (evaluated?.opacity ?: 1f).coerceIn(0.05f, 1.0f)
          val demoOffsetX = (evaluated?.offsetX ?: 0f).coerceIn(-1.5f, 1.5f)
          val demoOffsetY = (evaluated?.offsetY ?: 0f).coerceIn(-1.5f, 1.5f)
          val shadowStopColor =
            Color(
              red = shadowSample[0].coerceIn(0f, 1f),
              green = shadowSample[1].coerceIn(0f, 1f),
              blue = shadowSample[2].coerceIn(0f, 1f),
              alpha = 0.92f,
            )
          val midtoneStopColor =
            if (activeLutTint != null) {
              Color(
                red = ((midtoneSample[0] + Color(activeLutTint.midtoneColorArgb).red) * 0.5f).coerceIn(0f, 1f),
                green = ((midtoneSample[1] + Color(activeLutTint.midtoneColorArgb).green) * 0.5f).coerceIn(0f, 1f),
                blue = ((midtoneSample[2] + Color(activeLutTint.midtoneColorArgb).blue) * 0.5f).coerceIn(0f, 1f),
                alpha = 0.85f,
              )
            } else {
              Color(
                red = midtoneSample[0].coerceIn(0f, 1f),
                green = midtoneSample[1].coerceIn(0f, 1f),
                blue = midtoneSample[2].coerceIn(0f, 1f),
                alpha = 0.85f,
              )
            }
          val highlightStopColor =
            Color(
              red = highlightSample[0].coerceIn(0f, 1f),
              green = highlightSample[1].coerceIn(0f, 1f),
              blue = highlightSample[2].coerceIn(0f, 1f),
              alpha = 0.78f,
            )

          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .padding(8.dp)
                .graphicsLayer {
                  translationX = demoOffsetX * (size.width * 0.5f)
                  translationY = demoOffsetY * (size.height * 0.5f)
                  scaleX = demoScale
                  scaleY = demoScale
                  rotationZ = demoRotation
                  alpha = demoAlpha
                }
                .clip(RoundedCornerShape(6.dp))
                .background(
                  Brush.linearGradient(
                    listOf(
                      shadowStopColor,
                      midtoneStopColor,
                      highlightStopColor,
                    ),
                  ),
                ),
            contentAlignment = Alignment.Center,
          ) {
            Surface(
              color = Color.Transparent,
              shape = RoundedCornerShape(6.dp),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier.fillMaxSize(),
            ) {
              BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isVeryShort = maxHeight < 132.dp
                if (!isVeryShort) {
                  Text(
                    text = stringResource(R.string.label_viewer_no_signal),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 8.dp, vertical = 6.dp),
                  )
                }

                Column(
                  modifier =
                    Modifier
                      .align(Alignment.Center)
                      .verticalScroll(rememberScrollState())
                      .padding(horizontal = 12.dp, vertical = if (isVeryShort) 4.dp else 8.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(if (isVeryShort) 2.dp else 6.dp),
                ) {
                  if (!isVeryShort) {
                    Icon(
                      imageVector = Icons.Default.Videocam,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.secondary,
                      modifier = Modifier.size(22.dp),
                    )
                  }
                  Text(
                    text = previewState.statusNote ?: stringResource(R.string.label_no_preview_media),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = if (isVeryShort) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                  AssistChip(
                    onClick = onOpenMediaPicker,
                    leadingIcon = {
                      Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                    },
                    label = { Text(stringResource(R.string.nav_media)) },
                    modifier = Modifier.testTag("preview_open_media_chip"),
                  )
                }
              }
            }
          }
        }

        if (!activeSubtitleText.isNullOrBlank()) {
          Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.84f),
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier =
              Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp, start = 16.dp, end = 16.dp),
          ) {
            Text(
              text = activeSubtitleText,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
        }
      }
    }

    if (!compactDiagnostics) {
      previewState.diagnosticNotice?.let { notice ->
        Surface(
          color = MaterialTheme.colorScheme.surface,
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth().testTag("preview_diagnostic_notice"),
        ) {
          Text(
            text = notice.message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          )
        }
      }
    }

    previewState.uiError?.let { uiError ->
      Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth().testTag("preview_error_banner"),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = uiError.title,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.error,
            )
            Text(
              text = uiError.detail,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (uiError.isRecoverable) {
            TextButton(onClick = onRetryPreview) {
              Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(2.dp))
              Text("Reintentar")
            }
          }
          IconButton(onClick = onDismissError, modifier = Modifier.minimumInteractiveComponentSize()) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Ocultar error", modifier = Modifier.size(16.dp))
          }
        }
      }
    }
  }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyframeInspectorStrip(
  selectedClip: TimelineClip,
  playheadMs: Long,
  onAddKeyframe: () -> Unit,
  onSeekToMs: (Long) -> Unit,
  onJumpPreviousKeyframe: () -> Unit,
  onJumpNextKeyframe: () -> Unit,
  onMoveKeyframe: (keyframeId: String, newOffsetMs: Long) -> Unit,
  onUpdateInterpolation: (keyframeId: String, interpolation: KeyframeInterpolation) -> Unit,
  onRemoveKeyframe: (String) -> Unit,
  onClearKeyframes: () -> Unit,
  onBeginEdit: () -> Unit,
  onUpdateKeyframeProperties: (
    scale: Float?,
    opacity: Float?,
    offsetX: Float?,
    offsetY: Float?,
    rotationDegrees: Float?,
    volume: Float?,
    fromSliderDrag: Boolean,
  ) -> Unit,
  onEndEdit: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  fillAvailableHeight: Boolean = false,
  maxContentHeight: Dp = 280.dp,
  outerHorizontalPadding: Dp = 12.dp,
  outerVerticalPadding: Dp = 4.dp,
) {
  if (selectedClip.kind != ClipKind.VIDEO && selectedClip.kind != ClipKind.AUDIO) return
  val isVideo = selectedClip.kind == ClipKind.VIDEO
  val keyframes = selectedClip.keyframes()
  val offsetMs = (playheadMs - selectedClip.timelineStartMs).coerceIn(0L, selectedClip.durationMs)
  val evaluated = selectedClip.evaluatePropertiesAt(offsetMs)
  val activeKf = keyframes.firstOrNull { abs(it.offsetMs - offsetMs) <= 40L }
  val headerTitle =
    if (isVideo) {
      stringResource(R.string.editor_keyframes_title)
    } else {
      stringResource(R.string.label_audio_keyframes_title)
    }
  val readoutSummary =
    if (isVideo) {
      "${"%.2f".format(evaluated.scale)}x · ${evaluated.rotationDegrees.roundToInt()}° · ${(evaluated.opacity * 100f).roundToInt()}% · Vol ${(evaluated.volume * 100f).roundToInt()}%"
    } else {
      "Vol ${(evaluated.volume * 100f).roundToInt()}% · ${keyframes.size} kf"
    }

  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    modifier =
      modifier
        .fillMaxWidth()
        .let { if (fillAvailableHeight) it.fillMaxHeight() else it }
        .padding(horizontal = outerHorizontalPadding, vertical = outerVerticalPadding)
        .testTag("keyframes_subpanel"),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .let { if (fillAvailableHeight) it.fillMaxHeight() else it.heightIn(max = maxContentHeight) }
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = headerTitle,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "${formatTimecode(offsetMs)} · $readoutSummary",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(
          onClick = onJumpPreviousKeyframe,
          enabled = keyframes.isNotEmpty(),
          modifier = Modifier.minimumInteractiveComponentSize().testTag("prev_keyframe_button"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.cd_prev_keyframe),
          )
        }
        FilledTonalButton(
          onClick = onAddKeyframe,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("add_keyframe_button"),
        ) {
          Icon(imageVector = Icons.Default.Diamond, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text(stringResource(R.string.action_add_keyframe))
        }
        IconButton(
          onClick = onJumpNextKeyframe,
          enabled = keyframes.isNotEmpty(),
          modifier = Modifier.minimumInteractiveComponentSize().testTag("next_keyframe_button"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.cd_next_keyframe),
          )
        }
        IconButton(
          onClick = onClose,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("close_subpanel_button"),
        ) {
          Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_subpanel))
        }
      }

      if (keyframes.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          keyframes.forEach { kf ->
            val chipSummary =
              if (isVideo) {
                "${formatTimecode(kf.offsetMs)} (${"%.1f".format(kf.scale)}x)"
              } else {
                "${formatTimecode(kf.offsetMs)} (${((kf.volume ?: evaluated.volume) * 100f).roundToInt()}%)"
              }
            InputChip(
              selected = activeKf?.keyframeId == kf.keyframeId,
              onClick = { onSeekToMs(selectedClip.timelineStartMs + kf.offsetMs) },
              label = { Text(chipSummary) },
              trailingIcon = {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = stringResource(R.string.cd_remove_keyframe),
                  modifier =
                    Modifier
                      .size(18.dp)
                      .clickable { onRemoveKeyframe(kf.keyframeId) }
                      .testTag("remove_kf_${kf.keyframeId}"),
                )
              },
              modifier = Modifier.testTag("keyframe_chip_${kf.keyframeId}"),
            )
          }
        }

        activeKf?.let { selectedKf ->
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.label_interpolation),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyframeInterpolation.entries.forEach { curve ->
              FilterChip(
                selected = selectedKf.interpolation == curve,
                onClick = { onUpdateInterpolation(selectedKf.keyframeId, curve) },
                label = { Text(curve.displayLabel) },
                modifier = Modifier.testTag("kf_interp_${curve.name.lowercase()}"),
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedButton(
              onClick = {
                val target = (selectedKf.offsetMs - 100L).coerceAtLeast(0L)
                onMoveKeyframe(selectedKf.keyframeId, target)
                onSeekToMs(selectedClip.timelineStartMs + target)
              },
              enabled = selectedKf.offsetMs > 0L,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("nudge_keyframe_back_button"),
            ) {
              Text(stringResource(R.string.action_nudge_back_100ms))
            }
            OutlinedButton(
              onClick = {
                val target = (selectedKf.offsetMs + 100L).coerceAtMost(selectedClip.durationMs)
                onMoveKeyframe(selectedKf.keyframeId, target)
                onSeekToMs(selectedClip.timelineStartMs + target)
              },
              enabled = selectedKf.offsetMs < selectedClip.durationMs,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("nudge_keyframe_fwd_button"),
            ) {
              Text(stringResource(R.string.action_nudge_fwd_100ms))
            }
            AssistChip(
              onClick = { onRemoveKeyframe(selectedKf.keyframeId) },
              label = { Text(stringResource(R.string.action_delete_keyframe_at, formatTimecode(selectedKf.offsetMs))) },
              modifier = Modifier.testTag("delete_active_keyframe_chip"),
            )
            AssistChip(
              onClick = onClearKeyframes,
              leadingIcon = {
                Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
              },
              label = { Text(stringResource(R.string.action_clear_keyframes)) },
              modifier = Modifier.testTag("clear_all_keyframes_chip"),
            )
          }
        }
      }

      if (isVideo) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_scale),
            valueText = "${"%.2f".format(evaluated.scale)}x",
            value = evaluated.scale.coerceIn(0.25f, 3.0f),
            valueRange = 0.25f..3.0f,
            defaultValue = 1.0f,
            onValueChange = { nextScale ->
              onBeginEdit()
              onUpdateKeyframeProperties(nextScale, null, null, null, null, null, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(1.0f, null, null, null, null, null, false)
            },
            testTag = "keyframe_scale_slider",
            modifier = Modifier.weight(1f),
          )
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_opacity),
            valueText = "${(evaluated.opacity * 100f).roundToInt()}%",
            value = evaluated.opacity.coerceIn(0.0f, 1.0f),
            valueRange = 0.0f..1.0f,
            defaultValue = 1.0f,
            onValueChange = { nextOpacity ->
              onBeginEdit()
              onUpdateKeyframeProperties(null, nextOpacity, null, null, null, null, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(null, 1.0f, null, null, null, null, false)
            },
            testTag = "keyframe_opacity_slider",
            modifier = Modifier.weight(1f),
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_position_x),
            valueText = "%+.2f".format(evaluated.offsetX),
            value = evaluated.offsetX.coerceIn(-1.5f, 1.5f),
            valueRange = -1.5f..1.5f,
            defaultValue = 0.0f,
            bipolarOrigin = true,
            onValueChange = { nextX ->
              onBeginEdit()
              onUpdateKeyframeProperties(null, null, nextX, null, null, null, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(null, null, 0.0f, null, null, null, false)
            },
            testTag = "keyframe_pos_x_slider",
            modifier = Modifier.weight(1f),
          )
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_position_y),
            valueText = "%+.2f".format(evaluated.offsetY),
            value = evaluated.offsetY.coerceIn(-1.5f, 1.5f),
            valueRange = -1.5f..1.5f,
            defaultValue = 0.0f,
            bipolarOrigin = true,
            onValueChange = { nextY ->
              onBeginEdit()
              onUpdateKeyframeProperties(null, null, null, nextY, null, null, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(null, null, null, 0.0f, null, null, false)
            },
            testTag = "keyframe_pos_y_slider",
            modifier = Modifier.weight(1f),
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_rotation),
            valueText = "${evaluated.rotationDegrees.roundToInt()}°",
            value = evaluated.rotationDegrees.coerceIn(-180f, 180f),
            valueRange = -180f..180f,
            defaultValue = 0.0f,
            bipolarOrigin = true,
            onValueChange = { nextRot ->
              onBeginEdit()
              onUpdateKeyframeProperties(null, null, null, null, nextRot, null, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(null, null, null, null, 0.0f, null, false)
            },
            testTag = "keyframe_rotation_slider",
            modifier = Modifier.weight(1f),
          )
          PrecisionConsoleSlider(
            label = stringResource(R.string.label_audio_volume),
            valueText = "${(evaluated.volume * 100f).roundToInt()}%",
            value = evaluated.volume.coerceIn(0.0f, 2.0f),
            valueRange = 0.0f..2.0f,
            defaultValue = 1.0f,
            onValueChange = { nextVol ->
              onBeginEdit()
              onUpdateKeyframeProperties(null, null, null, null, null, nextVol, true)
            },
            onValueChangeFinished = onEndEdit,
            onReset = {
              onUpdateKeyframeProperties(null, null, null, null, null, 1.0f, false)
            },
            testTag = "keyframe_volume_slider",
            modifier = Modifier.weight(1f),
          )
        }
      } else {
        PrecisionConsoleSlider(
          label = stringResource(R.string.label_audio_volume),
          valueText = "${(evaluated.volume * 100f).roundToInt()}%",
          value = evaluated.volume.coerceIn(0.0f, 2.0f),
          valueRange = 0.0f..2.0f,
          defaultValue = 1.0f,
          onValueChange = { nextVol ->
            onBeginEdit()
            onUpdateKeyframeProperties(null, null, null, null, null, nextVol, true)
          },
          onValueChangeFinished = onEndEdit,
          onReset = {
            onUpdateKeyframeProperties(null, null, null, null, null, 1.0f, false)
          },
          testTag = "keyframe_volume_slider",
        )
      }
    }
  }
}
