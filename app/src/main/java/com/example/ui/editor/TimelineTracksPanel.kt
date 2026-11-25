package com.example.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.R
import com.example.core.ai.api.DeviceProfile
import com.example.core.ergonomics.InputDeviceMode
import com.example.core.ergonomics.TimelineErgonomicsHelper
import com.example.core.ergonomics.TimelineViewportErgonomics
import com.example.core.media.audio.AudioTrackMixer
import com.example.core.media.audio.AudioWaveformCache
import com.example.core.media.playback.ThumbnailLoadResult
import com.example.core.media.playback.VideoThumbnailCache
import com.example.core.model.ClipKind
import com.example.core.model.DuckingWindow
import com.example.core.model.MarkerColor
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineMarker
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.ui.theme.LocalTrackColors
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val BASE_PX_PER_SECOND_DP = 56.dp
private val TRACK_HEADER_WIDTH = 128.dp
private val TRACK_ROW_HEIGHT = 64.dp
private val RULER_HEIGHT = 42.dp

@Composable
fun MultitrackTimelineDeck(
  uiState: TimelineSkeletonUiState,
  onSelectClip: (String) -> Unit,
  onSelectMarker: (Long) -> Unit,
  onBeginScrub: () -> Unit,
  onScrubPlayhead: (Long) -> Unit,
  onEndScrub: () -> Unit,
  onToggleTrackMute: (String) -> Unit,
  onToggleTrackSolo: (String) -> Unit,
  onToggleTrackLocked: (String) -> Unit,
  onToggleTrackVisible: (String) -> Unit,
  onBeginClipTrim: () -> Unit,
  onTrimClipEdge: (clipId: String, isStartEdge: Boolean, targetMs: Long) -> Unit,
  onEndClipTrim: () -> Unit,
  onPointerInputDetected: (InputDeviceMode) -> Unit,
  onHoverTimelineMsChanged: (Long?) -> Unit,
  onZoomBy: (Float) -> Unit,
  onEraserTapClip: (String) -> Unit,
  loadClipThumbnail: suspend (String?, Long) -> ThumbnailLoadResult?,
  loadClipWaveform: suspend (String?, Long, Long, Int) -> FloatArray? = { _, _, _, _ -> null },
  ergonomics: TimelineViewportErgonomics = uiState.ergonomics,
  modifier: Modifier = Modifier,
) {
  val trackColors = LocalTrackColors.current
  val density = LocalDensity.current
  val timeline = uiState.timeline
  val audibilityMap = AudioTrackMixer.resolveTrackAudibility(timeline.tracks)
  val duckingWindows = AudioTrackMixer.computeDuckingWindows(timeline)
  val horizontalScroll = rememberScrollState()
  val verticalScroll = rememberScrollState()
  val pxPerSecondDp = BASE_PX_PER_SECOND_DP * ergonomics.zoomScale
  val pxPerSecond = with(density) { pxPerSecondDp.toPx() }
  val totalTimelineMs = (timeline.totalDurationMs + 2_000L).coerceAtLeast(10_000L)
  val canvasWidthDp = msToDp(totalTimelineMs, pxPerSecondDp)
  val playheadOffsetDp = msToDp(timeline.playheadMs, pxPerSecondDp)
  val latestPlayheadMs by rememberUpdatedState(timeline.playheadMs)
  val latestInputMode by rememberUpdatedState(ergonomics.inputMode)
  val hoverColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
        .pointerInput(Unit) {
          awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
              val event = awaitPointerEvent()
              event.changes.firstOrNull()?.type?.let { type ->
                onPointerInputDetected(InputDeviceMode.fromPointerType(type))
              }
              if (event.changes.count { it.pressed } >= 2) {
                val zoomChange = event.calculateZoom()
                if (zoomChange != 1f) {
                  onZoomBy(zoomChange)
                  event.changes.forEach { it.consume() }
                }
              }
            } while (event.changes.any { it.pressed })
          }
        },
  ) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
      Row(modifier = Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
        Box(
          modifier =
            Modifier
              .width(TRACK_HEADER_WIDTH)
              .fillMaxHeight()
              .background(MaterialTheme.colorScheme.surfaceVariant),
          contentAlignment = Alignment.CenterStart,
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            Icon(
              imageVector = Icons.Default.Schedule,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.secondary,
              modifier = Modifier.size(14.dp),
            )
            Text(
              text =
                if (timeline.markers.isNotEmpty()) {
                  "${stringResource(R.string.label_markers)} (${timeline.markers.size})"
                } else {
                  stringResource(R.string.label_markers)
                },
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Box(
            modifier =
              Modifier
                .align(Alignment.CenterEnd)
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
          )
        }

        Box(
          modifier =
            Modifier
              .weight(1f)
              .fillMaxHeight()
              .horizontalScroll(horizontalScroll)
              .background(MaterialTheme.colorScheme.surfaceContainerLowest)
              .pointerInput(pxPerSecond) {
                awaitPointerEventScope {
                  while (true) {
                    val event = awaitPointerEvent()
                    val first = event.changes.firstOrNull()
                    if (first != null) {
                      onPointerInputDetected(InputDeviceMode.fromPointerType(first.type))
                      when (event.type) {
                        PointerEventType.Move -> {
                          val hoverMs =
                            ((first.position.x / pxPerSecond) * 1000f)
                              .roundToLong()
                              .coerceAtLeast(0L)
                          onHoverTimelineMsChanged(hoverMs)
                        }
                        PointerEventType.Exit -> onHoverTimelineMsChanged(null)
                        else -> Unit
                      }
                    }
                  }
                }
              }
              .pointerInput(pxPerSecond) {
                var accumMs = 0f
                detectHorizontalDragGestures(
                  onDragStart = { offset ->
                    onBeginScrub()
                    accumMs = ((offset.x / pxPerSecond) * 1000f).coerceAtLeast(0f)
                    onScrubPlayhead(accumMs.roundToLong())
                  },
                  onDragEnd = {
                    accumMs = latestPlayheadMs.toFloat()
                    onEndScrub()
                  },
                  onDragCancel = {
                    onEndScrub()
                  },
                  onHorizontalDrag = { change, dragAmount ->
                    onPointerInputDetected(InputDeviceMode.fromPointerType(change.type))
                    change.consume()
                    val deltaMs =
                      TimelineErgonomicsHelper.pointerDeltaPxToTimelineMs(
                        deltaPx = dragAmount,
                        pxPerSecond = pxPerSecond,
                        inputMode = latestInputMode,
                      )
                    accumMs = (accumMs + deltaMs).coerceAtLeast(0f)
                    onScrubPlayhead(accumMs.roundToLong())
                  },
                )
              }
              .pointerInput(pxPerSecond) {
                detectTapGestures { offset ->
                  onBeginScrub()
                  val tapMs = ((offset.x / pxPerSecond) * 1000f).roundToLong().coerceAtLeast(0L)
                  onScrubPlayhead(tapMs)
                  onEndScrub()
                }
              },
        ) {
          Box(modifier = Modifier.width(canvasWidthDp).fillMaxHeight()) {
            TimeRulerCanvas(
              totalMs = totalTimelineMs,
              playheadMs = timeline.playheadMs,
              hoverMs = ergonomics.hoverTimelineMs,
              playheadColor = trackColors.playhead,
              hoverColor = hoverColor,
              majorTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
              minorTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f),
              labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.fillMaxSize(),
            )

            timeline.markers.forEach { marker ->
              MarkerPin(
                marker = marker,
                color = markerColorFor(marker.color),
                offsetX = msToDp(marker.positionMs, pxPerSecondDp),
                onClick = { onSelectMarker(marker.positionMs) },
              )
            }

            PlayheadRulerHandle(
              playheadOffsetDp = playheadOffsetDp,
              playheadMs = timeline.playheadMs,
              pxPerSecond = pxPerSecond,
              inputMode = latestInputMode,
              playheadColor = trackColors.playhead,
              outlineColor = MaterialTheme.colorScheme.surfaceContainerLowest,
              onBeginScrub = onBeginScrub,
              onScrubPlayhead = onScrubPlayhead,
              onEndScrub = onEndScrub,
              onPointerInputDetected = onPointerInputDetected,
            )
          }
        }
      }

      HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)

      timeline.tracksSorted().forEach { track ->
        val trackClips = timeline.clipsForTrack(track.trackId)
        val isAudioBearing = track.type == TrackType.VIDEO || track.type == TrackType.AUDIO
        val isTrackAudible = !isAudioBearing || audibilityMap[track.trackId] == true
        val laneAlpha =
          when {
            !track.isVisible -> 0.36f
            !isTrackAudible -> 0.48f
            else -> 1f
          }

        Row(
          modifier = Modifier.fillMaxWidth().height(TRACK_ROW_HEIGHT),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TrackHeaderCell(
            track = track,
            onToggleMute = { onToggleTrackMute(track.trackId) },
            onToggleSolo = { onToggleTrackSolo(track.trackId) },
            onToggleLocked = { onToggleTrackLocked(track.trackId) },
            onToggleVisible = { onToggleTrackVisible(track.trackId) },
          )

          Box(
            modifier =
              Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(horizontalScroll)
                .background(
                  if (track.isLocked) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                  } else {
                    MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.88f)
                  },
                )
                .alpha(laneAlpha),
          ) {
            Box(modifier = Modifier.width(canvasWidthDp).fillMaxHeight()) {
              trackClips.forEach { clip ->
                val isSelected = clip.clipId == uiState.selectedClipId
                val startDp = msToDp(clip.timelineStartMs, pxPerSecondDp)
                val widthDp = msToDp(clip.durationMs, pxPerSecondDp).coerceAtLeast(30.dp)
                val clipDuckingWindows =
                  if (timeline.duckingConfig.targetTrackId == track.trackId) {
                    AudioTrackMixer.duckingWindowsForClip(clip, duckingWindows)
                  } else {
                    emptyList()
                  }

                ClipBlock(
                  clip = clip,
                  isSelected = isSelected,
                  isTrackLocked = track.isLocked,
                  isTrackAudible = isTrackAudible,
                  trackVolume = track.volume,
                  duckingWindows = clipDuckingWindows,
                  startDp = startDp,
                  widthDp = widthDp,
                  pxPerSecondDp = pxPerSecondDp,
                  accentColor = clipAccentColor(clip.kind),
                  deviceProfile = uiState.deviceProfile,
                  loadClipThumbnail = loadClipThumbnail,
                  loadClipWaveform = loadClipWaveform,
                  onClick = { onSelectClip(clip.clipId) },
                  onEraserTap = { onEraserTapClip(clip.clipId) },
                )

                if (isSelected && !track.isLocked && !clip.isLocked) {
                  ClipTrimHandle(
                    clipId = clip.clipId,
                    isStartEdge = true,
                    edgeAnchorMs = clip.timelineStartMs,
                    edgeOffsetDp = startDp,
                    pxPerSecond = pxPerSecond,
                    inputMode = latestInputMode,
                    onBeginTrim = onBeginClipTrim,
                    onTrimEdge = { targetMs -> onTrimClipEdge(clip.clipId, true, targetMs) },
                    onEndTrim = onEndClipTrim,
                    onPointerInputDetected = onPointerInputDetected,
                  )
                  ClipTrimHandle(
                    clipId = clip.clipId,
                    isStartEdge = false,
                    edgeAnchorMs = clip.timelineEndMs,
                    edgeOffsetDp = startDp + widthDp,
                    pxPerSecond = pxPerSecond,
                    inputMode = latestInputMode,
                    onBeginTrim = onBeginClipTrim,
                    onTrimEdge = { targetMs -> onTrimClipEdge(clip.clipId, false, targetMs) },
                    onEndTrim = onEndClipTrim,
                    onPointerInputDetected = onPointerInputDetected,
                  )
                }
              }

              Box(
                modifier =
                  Modifier
                    .offset(x = playheadOffsetDp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .zIndex(1f)
                    .background(trackColors.playhead),
              )
            }
          }
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
      }
    }
  }
}

@Composable
private fun TimeRulerCanvas(
  totalMs: Long,
  playheadMs: Long,
  hoverMs: Long?,
  playheadColor: Color,
  hoverColor: Color,
  majorTickColor: Color,
  minorTickColor: Color,
  labelColor: Color,
  modifier: Modifier = Modifier,
) {
  val textMeasurer = rememberTextMeasurer()
  val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = labelColor)

  Canvas(modifier = modifier) {
    val totalSeconds = (totalMs / 1_000L).toInt()
    val pxPerSecond = size.width / (totalMs / 1000f)

    val majorIntervalSec =
      when {
        pxPerSecond >= 95f -> 1
        pxPerSecond >= 45f -> 2
        else -> 5
      }
    val subDivisionsPerSecond =
      when {
        pxPerSecond >= 160f -> 10
        pxPerSecond >= 75f -> 5
        else -> 2
      }

    for (sec in 0..totalSeconds) {
      val secX = sec * pxPerSecond
      val isMajor = sec % majorIntervalSec == 0
      val tickTopY = if (isMajor) size.height * 0.44f else size.height * 0.62f

      drawLine(
        color = if (isMajor) majorTickColor else minorTickColor,
        start = Offset(secX, tickTopY),
        end = Offset(secX, size.height),
        strokeWidth = if (isMajor) 2f else 1.2f,
      )

      if (isMajor) {
        val labelText = formatRulerSecondsLabel(sec)
        val measured = textMeasurer.measure(labelText, style = labelStyle)
        val drawX = (secX + 4f).coerceAtMost((size.width - measured.size.width).coerceAtLeast(0f))
        drawText(
          textLayoutResult = measured,
          topLeft = Offset(drawX, 3f),
        )
      }

      if (sec < totalSeconds) {
        for (sub in 1 until subDivisionsPerSecond) {
          val subX = secX + (pxPerSecond * sub / subDivisionsPerSecond.toFloat())
          drawLine(
            color = minorTickColor,
            start = Offset(subX, size.height * 0.78f),
            end = Offset(subX, size.height),
            strokeWidth = 1f,
          )
        }
      }
    }

    if (hoverMs != null) {
      val hoverX = (hoverMs / 1000f) * pxPerSecond
      drawLine(
        color = hoverColor,
        start = Offset(hoverX, 0f),
        end = Offset(hoverX, size.height),
        strokeWidth = 2f,
      )
    }

    val playheadX = (playheadMs / 1000f) * pxPerSecond
    drawLine(
      color = playheadColor,
      start = Offset(playheadX, 0f),
      end = Offset(playheadX, size.height),
      strokeWidth = 2.5f,
    )
  }
}

@Composable
private fun PlayheadRulerHandle(
  playheadOffsetDp: Dp,
  playheadMs: Long,
  pxPerSecond: Float,
  inputMode: InputDeviceMode,
  playheadColor: Color,
  outlineColor: Color,
  onBeginScrub: () -> Unit,
  onScrubPlayhead: (Long) -> Unit,
  onEndScrub: () -> Unit,
  onPointerInputDetected: (InputDeviceMode) -> Unit,
) {
  val latestPlayheadMs by rememberUpdatedState(playheadMs)
  val latestInputMode by rememberUpdatedState(inputMode)
  var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
  var isDragging by remember { mutableStateOf(false) }
  val handleDesc = stringResource(R.string.cd_playhead_handle)

  Box(
    modifier =
      Modifier
        .offset(x = (playheadOffsetDp - 24.dp).coerceAtLeast(0.dp))
        .width(48.dp)
        .fillMaxHeight()
        .zIndex(3f)
        .onGloballyPositioned { handleCoords = it }
        .semantics { contentDescription = handleDesc }
        .pointerInput(pxPerSecond) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            onPointerInputDetected(InputDeviceMode.fromPointerType(down.type))
            val coordsAtStart = handleCoords
            val startRootX =
              if (coordsAtStart != null && coordsAtStart.isAttached) {
                coordsAtStart.positionInRoot().x + down.position.x
              } else {
                down.position.x
              }
            val startMs = latestPlayheadMs
            var didActivateDrag = false

            val afterSlop =
              awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                didActivateDrag = true
                isDragging = true
                onBeginScrub()
                change.consume()
              }

            if (afterSlop != null && didActivateDrag) {
              val dragCompleted =
                horizontalDrag(afterSlop.id) { change ->
                  onPointerInputDetected(InputDeviceMode.fromPointerType(change.type))
                  change.consume()
                  val currentCoords = handleCoords
                  val currentRootX =
                    if (currentCoords != null && currentCoords.isAttached) {
                      currentCoords.positionInRoot().x + change.position.x
                    } else {
                      change.position.x
                    }
                  val deltaPx = currentRootX - startRootX
                  val deltaMs =
                    TimelineErgonomicsHelper.pointerDeltaPxToTimelineMs(
                      deltaPx = deltaPx,
                      pxPerSecond = pxPerSecond,
                      inputMode = latestInputMode,
                    )
                  onScrubPlayhead((startMs + deltaMs).coerceAtLeast(0L))
                }
              if (dragCompleted || !dragCompleted) {
                isDragging = false
                onEndScrub()
              }
            } else {
              isDragging = false
            }
          }
        }
        .testTag("timeline_playhead_handle"),
    contentAlignment = Alignment.TopCenter,
  ) {
    Canvas(modifier = Modifier.padding(top = 2.dp).size(width = 24.dp, height = 22.dp)) {
      val w = size.width
      val h = size.height
      val capBottom = h * 0.65f
      val shieldPath =
        Path().apply {
          moveTo(2f, 0f)
          lineTo(w - 2f, 0f)
          lineTo(w - 2f, capBottom)
          lineTo(w * 0.5f, h)
          lineTo(2f, capBottom)
          close()
        }
      drawPath(path = shieldPath, color = playheadColor)
      drawPath(
        path = shieldPath,
        color = outlineColor,
        style = Stroke(width = if (isDragging) 3f else 2f),
      )
      drawLine(
        color = outlineColor,
        start = Offset(w * 0.5f, 4f),
        end = Offset(w * 0.5f, capBottom - 2f),
        strokeWidth = 2f,
      )
    }
  }
}

@Composable
private fun MarkerPin(
  marker: TimelineMarker,
  color: Color,
  offsetX: Dp,
  onClick: () -> Unit,
) {
  Box(
    modifier =
      Modifier
        .offset(x = (offsetX - 8.dp).coerceAtLeast(0.dp), y = 22.dp)
        .size(16.dp)
        .zIndex(2f)
        .clip(CircleShape)
        .background(color)
        .border(1.5.dp, MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape)
        .clickable(onClick = onClick)
        .testTag("marker_pin_${marker.markerId}"),
  )
}

@Composable
private fun TrackHeaderCell(
  track: Track,
  onToggleMute: () -> Unit,
  onToggleSolo: () -> Unit,
  onToggleLocked: () -> Unit,
  onToggleVisible: () -> Unit,
) {
  val accent = trackAccentColor(track.type)
  val icon =
    when (track.type) {
      TrackType.VIDEO -> Icons.Default.Videocam
      TrackType.AUDIO -> Icons.Default.MusicNote
      TrackType.SUBTITLE -> Icons.Default.Subtitles
      TrackType.ADJUSTMENT -> Icons.Default.Tune
    }
  val hasAudioControls = track.type == TrackType.VIDEO || track.type == TrackType.AUDIO
  val hasVisibilityControl =
    track.type == TrackType.VIDEO ||
      track.type == TrackType.SUBTITLE ||
      track.type == TrackType.ADJUSTMENT

  Row(
    modifier =
      Modifier
        .width(TRACK_HEADER_WIDTH)
        .fillMaxHeight()
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier
          .width(3.dp)
          .fillMaxHeight()
          .background(accent),
    )

    Column(
      modifier = Modifier.weight(1f).padding(horizontal = 7.dp, vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = track.name,
          tint = accent,
          modifier = Modifier.size(14.dp),
        )
        Text(
          text = track.name,
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        if (hasAudioControls) {
          val gainLabel =
            if (track.isMuted) {
              stringResource(R.string.label_track_muted_badge)
            } else {
              "${(track.volume.coerceIn(0f, 2f) * 100f).roundToInt()}%"
            }
          Text(
            text = gainLabel,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            fontWeight = FontWeight.Medium,
            color =
              if (track.isMuted) {
                MaterialTheme.colorScheme.error
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
            maxLines = 1,
          )
        }
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TrackHeaderIconToggle(
          icon = if (track.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
          contentDescription = stringResource(R.string.cd_track_lock),
          active = track.isLocked,
          activeColor = LocalTrackColors.current.markerAmber,
          onClick = onToggleLocked,
          tag = "lock_${track.trackId}",
        )

        if (hasVisibilityControl) {
          TrackHeaderIconToggle(
            icon = if (track.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = stringResource(R.string.cd_track_visibility),
            active = !track.isVisible,
            activeColor = MaterialTheme.colorScheme.error,
            onClick = onToggleVisible,
            tag = "visibility_${track.trackId}",
          )
        }

        if (hasAudioControls) {
          TrackHeaderIconToggle(
            icon =
              if (track.isMuted) {
                Icons.AutoMirrored.Filled.VolumeOff
              } else {
                Icons.AutoMirrored.Filled.VolumeUp
              },
            contentDescription = stringResource(R.string.cd_track_mute),
            active = track.isMuted,
            activeColor = MaterialTheme.colorScheme.error,
            onClick = onToggleMute,
            tag = "mute_${track.trackId}",
          )
          TrackHeaderTextToggle(
            label = "S",
            contentDescription = stringResource(R.string.cd_track_solo),
            active = track.isSolo,
            activeColor = LocalTrackColors.current.subtitleTrack,
            onClick = onToggleSolo,
            tag = "solo_${track.trackId}",
          )
        }
      }
    }

    Box(
      modifier =
        Modifier
          .width(1.dp)
          .fillMaxHeight()
          .background(MaterialTheme.colorScheme.outlineVariant),
    )
  }
}

@Composable
private fun TrackHeaderIconToggle(
  icon: ImageVector,
  contentDescription: String,
  active: Boolean,
  activeColor: Color,
  onClick: () -> Unit,
  tag: String,
) {
  Surface(
    color =
      if (active) {
        activeColor.copy(alpha = 0.22f)
      } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
      },
    shape = RoundedCornerShape(4.dp),
    border =
      BorderStroke(
        width = 1.dp,
        color = if (active) activeColor else MaterialTheme.colorScheme.outlineVariant,
      ),
    modifier =
      Modifier
        .size(25.dp)
        .clip(RoundedCornerShape(4.dp))
        .clickable(onClick = onClick)
        .testTag(tag),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(14.dp),
      )
    }
  }
}

@Composable
private fun TrackHeaderTextToggle(
  label: String,
  contentDescription: String,
  active: Boolean,
  activeColor: Color,
  onClick: () -> Unit,
  tag: String,
) {
  Surface(
    color =
      if (active) {
        activeColor.copy(alpha = 0.22f)
      } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
      },
    shape = RoundedCornerShape(4.dp),
    border =
      BorderStroke(
        width = 1.dp,
        color = if (active) activeColor else MaterialTheme.colorScheme.outlineVariant,
      ),
    modifier =
      Modifier
        .size(25.dp)
        .clip(RoundedCornerShape(4.dp))
        .semantics { this.contentDescription = contentDescription }
        .clickable(onClick = onClick)
        .testTag(tag),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ClipBlock(
  clip: TimelineClip,
  isSelected: Boolean,
  isTrackLocked: Boolean,
  isTrackAudible: Boolean,
  trackVolume: Float,
  duckingWindows: List<DuckingWindow>,
  startDp: Dp,
  widthDp: Dp,
  pxPerSecondDp: Dp,
  accentColor: Color,
  deviceProfile: DeviceProfile,
  loadClipThumbnail: suspend (String?, Long) -> ThumbnailLoadResult?,
  loadClipWaveform: suspend (String?, Long, Long, Int) -> FloatArray?,
  onClick: () -> Unit,
  onEraserTap: () -> Unit,
) {
  val containerAlpha = if (clip.isEnabled) 1f else 0.42f
  val borderColor =
    if (isSelected) MaterialTheme.colorScheme.onSurface else accentColor.copy(alpha = 0.72f)
  val borderWidth = if (isSelected) 2.dp else 1.dp

  val maxProfileFrames = VideoThumbnailCache.maxThumbnailsPerClip(deviceProfile)
  val targetFrameCount =
    when {
      maxProfileFrames <= 0 || clip.kind != ClipKind.VIDEO || clip.assetId == null -> 0
      widthDp < 84.dp -> 1
      else -> (widthDp / 52.dp).toInt().coerceIn(1, maxProfileFrames)
    }

  val thumbSamples by produceState<List<ThumbnailLoadResult>>(
    initialValue = emptyList(),
    key1 = clip.assetId,
    key2 = clip.sourceInMs,
    key3 = "${clip.sourceOutMs}_${targetFrameCount}_${deviceProfile.tier}_${deviceProfile.allowFrameSampling}",
  ) {
    value =
      if (targetFrameCount <= 0 || clip.kind != ClipKind.VIDEO || clip.assetId == null) {
        emptyList()
      } else {
        val sourceSpanMs = (clip.sourceOutMs - clip.sourceInMs).coerceAtLeast(1L)
        (0 until targetFrameCount).mapNotNull { idx ->
          val ratio = if (targetFrameCount == 1) 0f else idx.toFloat() / targetFrameCount.toFloat()
          val sampleMs = clip.sourceInMs + (sourceSpanMs * ratio).toLong()
          loadClipThumbnail(clip.assetId, sampleMs)
        }
      }
  }

  val supportsWaveform = clip.kind == ClipKind.AUDIO || clip.kind == ClipKind.VIDEO
  val targetWaveformBuckets =
    if (supportsWaveform) {
      (widthDp.value / 4f).toInt().coerceIn(12, AudioWaveformCache.DEFAULT_SOURCE_BUCKETS)
    } else {
      0
    }
  val waveformPeaks by produceState<FloatArray?>(
    initialValue = null,
    key1 = clip.assetId,
    key2 = "${clip.sourceInMs}_${clip.sourceOutMs}",
    key3 = targetWaveformBuckets,
  ) {
    value =
      if (!supportsWaveform || clip.assetId == null || targetWaveformBuckets <= 0) {
        null
      } else {
        loadClipWaveform(clip.assetId, clip.sourceInMs, clip.sourceOutMs, targetWaveformBuckets)
      }
  }

  Box(
    modifier =
      Modifier
        .offset(x = startDp, y = 6.dp)
        .width(widthDp)
        .height(TRACK_ROW_HEIGHT - 12.dp)
        .alpha(containerAlpha)
        .clip(RoundedCornerShape(8.dp))
        .background(
          Brush.horizontalGradient(
            if (isSelected) {
              listOf(accentColor.copy(alpha = 0.48f), accentColor.copy(alpha = 0.26f))
            } else {
              listOf(accentColor.copy(alpha = 0.30f), accentColor.copy(alpha = 0.16f))
            },
          ),
        )
        .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
        .pointerInput(clip.clipId) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type == PointerType.Eraser) {
              down.consume()
              onEraserTap()
            }
          }
        }
        .clickable(onClick = onClick)
        .testTag("clip_block_${clip.clipId}"),
    contentAlignment = Alignment.CenterStart,
  ) {
    val readyBitmaps =
      thumbSamples.mapNotNull { (it as? ThumbnailLoadResult.Ready)?.bitmap?.takeIf { bmp -> !bmp.isRecycled } }
    if (readyBitmaps.isNotEmpty()) {
      Row(modifier = Modifier.fillMaxSize()) {
        readyBitmaps.forEach { bmp ->
          Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.weight(1f).fillMaxHeight().alpha(0.42f),
          )
        }
      }
    }

    if (supportsWaveform) {
      val clipBaseVol =
        when (clip.kind) {
          ClipKind.AUDIO -> clip.audioConfig?.volume ?: 1f
          ClipKind.VIDEO -> clip.videoConfig?.volume ?: 1f
          else -> 1f
        }
      val clipFadeInMs =
        when (clip.kind) {
          ClipKind.AUDIO -> clip.audioConfig?.fadeInMs ?: 0L
          ClipKind.VIDEO -> clip.videoConfig?.fadeInMs ?: 0L
          else -> 0L
        }
      val clipFadeOutMs =
        when (clip.kind) {
          ClipKind.AUDIO -> clip.audioConfig?.fadeOutMs ?: 0L
          ClipKind.VIDEO -> clip.videoConfig?.fadeOutMs ?: 0L
          else -> 0L
        }
      val effectiveTrackVol = if (isTrackAudible) trackVolume.coerceIn(0f, 2f) else 0f
      val peaks = waveformPeaks
      val hasWaveformOrAudioEnvelope =
        peaks != null ||
          clip.kind == ClipKind.AUDIO ||
          clipFadeInMs > 0L ||
          clipFadeOutMs > 0L ||
          clipBaseVol != 1f ||
          clip.keyframes().any { it.volume != null }

      if (hasWaveformOrAudioEnvelope) {
        val waveColor =
          if (clip.kind == ClipKind.AUDIO) {
            accentColor.copy(alpha = 0.85f)
          } else {
            accentColor.copy(alpha = 0.68f)
          }
        val envelopeStrokeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        Canvas(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
          val w = size.width
          val h = size.height
          if (w <= 2f || h <= 2f) return@Canvas

          val isAudioClip = clip.kind == ClipKind.AUDIO
          val centerY = if (isAudioClip) h * 0.55f else h * 0.80f
          val maxAmpHalf = if (isAudioClip) h * 0.40f else h * 0.18f

          if (peaks != null && peaks.isNotEmpty()) {
            val count = peaks.size
            val stepX = w / count.toFloat()
            val barWidth = (stepX * 0.62f).coerceIn(1.5f, 4.5f)
            for (i in 0 until count) {
              val offsetMs = ((i + 0.5f) / count.toFloat() * clip.durationMs).toLong()
              val gain =
                AudioTrackMixer.computeClipEnvelopeGainAt(
                  clipStartOnTimelineMs = clip.timelineStartMs,
                  clipOffsetMs = offsetMs,
                  clipDurationMs = clip.durationMs,
                  baseVolume = clipBaseVol,
                  fadeInMs = clipFadeInMs,
                  fadeOutMs = clipFadeOutMs,
                  duckingWindows = duckingWindows,
                  volumeKeyframes = clip.keyframes(),
                  trackVolume = effectiveTrackVol,
                ).coerceIn(0f, 2f)
              val normalizedAmp = (peaks[i] * gain).coerceIn(0.05f, 1.0f)
              val x = (i + 0.5f) * stepX
              val halfBar = (normalizedAmp * maxAmpHalf).coerceAtLeast(1.5f)
              val topY = (centerY - halfBar).coerceAtLeast(0f)
              val bottomY = if (isAudioClip) (centerY + halfBar).coerceAtMost(h) else h
              drawLine(
                color = waveColor,
                start = Offset(x, topY),
                end = Offset(x, bottomY),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
              )
            }
          } else if (isAudioClip) {
            // Subtle baseline while decoding or for unlinked demo audio clip
            drawLine(
              color = waveColor.copy(alpha = 0.35f),
              start = Offset(0f, centerY),
              end = Offset(w, centerY),
              strokeWidth = 1.5f,
            )
          }

          if (clipFadeInMs > 0L || clipFadeOutMs > 0L || clip.keyframes().any { it.volume != null } || clipBaseVol != 1f || effectiveTrackVol != 1f) {
            val envelopePoints = 24
            val path = Path()
            for (step in 0..envelopePoints) {
              val frac = step.toFloat() / envelopePoints.toFloat()
              val offsetMs = (frac * clip.durationMs).toLong()
              val gain =
                AudioTrackMixer.computeClipEnvelopeGainAt(
                  clipStartOnTimelineMs = clip.timelineStartMs,
                  clipOffsetMs = offsetMs,
                  clipDurationMs = clip.durationMs,
                  baseVolume = clipBaseVol,
                  fadeInMs = clipFadeInMs,
                  fadeOutMs = clipFadeOutMs,
                  duckingWindows = duckingWindows,
                  volumeKeyframes = clip.keyframes(),
                  trackVolume = effectiveTrackVol,
                )
              val normY = h - (gain.coerceIn(0f, 1.5f) / 1.5f) * (h * 0.72f) - 2f
              val x = frac * w
              if (step == 0) path.moveTo(x, normY) else path.lineTo(x, normY)
            }
            drawPath(
              path = path,
              color = envelopeStrokeColor,
              style = Stroke(width = 1.5f),
            )
          }
        }
      }
    }

    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = if (isSelected) 10.dp else 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      if (!clip.isEnabled) {
        Text(
          text = stringResource(R.string.label_disabled_badge),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.error,
        )
      }
      if (clip.isLocked || isTrackLocked) {
        Icon(
          imageVector = Icons.Default.Lock,
          contentDescription = stringResource(R.string.label_track_locked_badge),
          tint = LocalTrackColors.current.markerAmber,
          modifier = Modifier.size(12.dp),
        )
      }
      Text(
        text = clip.label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      if (widthDp >= 78.dp) {
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.78f),
          shape = RoundedCornerShape(4.dp),
        ) {
          Text(
            text = formatClipDurationBadge(clip.durationMs),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color =
              if (isSelected) {
                MaterialTheme.colorScheme.secondary
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
          )
        }
      }
    }

    val keyframes = clip.keyframes()
    if (keyframes.isNotEmpty()) {
      keyframes.forEach { kf ->
        val kfOffsetDp =
          msToDp(kf.offsetMs, pxPerSecondDp).coerceIn(0.dp, (widthDp - 12.dp).coerceAtLeast(0.dp))
        Box(
          modifier =
            Modifier
              .align(Alignment.BottomStart)
              .offset(x = kfOffsetDp)
              .size(7.dp)
              .clip(CircleShape)
              .background(LocalTrackColors.current.markerAmber)
              .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
        )
      }
    }
  }
}

@Composable
private fun ClipTrimHandle(
  clipId: String,
  isStartEdge: Boolean,
  edgeAnchorMs: Long,
  edgeOffsetDp: Dp,
  pxPerSecond: Float,
  inputMode: InputDeviceMode,
  onBeginTrim: () -> Unit,
  onTrimEdge: (Long) -> Unit,
  onEndTrim: () -> Unit,
  onPointerInputDetected: (InputDeviceMode) -> Unit,
) {
  val latestEdgeAnchorMs by rememberUpdatedState(edgeAnchorMs)
  val latestInputMode by rememberUpdatedState(inputMode)
  var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
  var isDragging by remember { mutableStateOf(false) }

  val handleDesc =
    stringResource(
      if (isStartEdge) R.string.cd_trim_start_handle else R.string.cd_trim_end_handle,
    )
  val pillColor =
    if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
  val gripColor =
    if (isDragging) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surfaceContainerLowest

  Box(
    modifier =
      Modifier
        .offset(x = (edgeOffsetDp - 14.dp).coerceAtLeast(0.dp), y = 4.dp)
        .width(28.dp)
        .height(TRACK_ROW_HEIGHT - 8.dp)
        .zIndex(2f)
        .onGloballyPositioned { handleCoords = it }
        .semantics { contentDescription = handleDesc }
        .pointerInput(clipId, isStartEdge, pxPerSecond) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            onPointerInputDetected(InputDeviceMode.fromPointerType(down.type))
            val coordsAtStart = handleCoords
            val startRootX =
              if (coordsAtStart != null && coordsAtStart.isAttached) {
                coordsAtStart.positionInRoot().x + down.position.x
              } else {
                down.position.x
              }
            val initialEdgeMs = latestEdgeAnchorMs
            var didActivate = false

            val afterSlop =
              awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                didActivate = true
                isDragging = true
                onBeginTrim()
                change.consume()
              }

            if (afterSlop != null && didActivate) {
              horizontalDrag(afterSlop.id) { change ->
                onPointerInputDetected(InputDeviceMode.fromPointerType(change.type))
                change.consume()
                val currentCoords = handleCoords
                val currentRootX =
                  if (currentCoords != null && currentCoords.isAttached) {
                    currentCoords.positionInRoot().x + change.position.x
                  } else {
                    change.position.x
                  }
                val deltaPx = currentRootX - startRootX
                val deltaMs =
                  TimelineErgonomicsHelper.pointerDeltaPxToTimelineMs(
                    deltaPx = deltaPx,
                    pxPerSecond = pxPerSecond,
                    inputMode = latestInputMode,
                  )
                onTrimEdge((initialEdgeMs + deltaMs).coerceAtLeast(0L))
              }
              isDragging = false
              onEndTrim()
            } else {
              isDragging = false
            }
          }
        }
        .testTag(if (isStartEdge) "trim_handle_start_$clipId" else "trim_handle_end_$clipId"),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      color = pillColor,
      shape = RoundedCornerShape(4.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainerLowest),
      modifier = Modifier.width(if (isDragging) 14.dp else 12.dp).height(TRACK_ROW_HEIGHT - 12.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Box(
          modifier =
            Modifier
              .width(2.dp)
              .height(18.dp)
              .clip(RoundedCornerShape(1.dp))
              .background(gripColor),
        )
      }
    }
  }
}

private fun formatRulerSecondsLabel(sec: Int): String {
  val minutes = sec / 60
  val seconds = sec % 60
  return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun formatClipDurationBadge(durationMs: Long): String {
  val safeSeconds = durationMs.coerceAtLeast(0L) / 1000.0
  return String.format(Locale.US, "%.1fs", safeSeconds)
}

@Composable
private fun trackAccentColor(type: TrackType): Color {
  val colors = LocalTrackColors.current
  return when (type) {
    TrackType.VIDEO -> colors.videoTrack
    TrackType.AUDIO -> colors.audioTrack
    TrackType.SUBTITLE -> colors.subtitleTrack
    TrackType.ADJUSTMENT -> colors.adjustmentTrack
  }
}

@Composable
private fun clipAccentColor(kind: ClipKind): Color {
  val colors = LocalTrackColors.current
  return when (kind) {
    ClipKind.VIDEO -> colors.videoTrack
    ClipKind.AUDIO -> colors.audioTrack
    ClipKind.SUBTITLE -> colors.subtitleTrack
    ClipKind.ADJUSTMENT -> colors.adjustmentTrack
  }
}

@Composable
private fun markerColorFor(markerColor: MarkerColor): Color {
  val colors = LocalTrackColors.current
  return when (markerColor) {
    MarkerColor.AMBER -> colors.markerAmber
    MarkerColor.CYAN -> colors.markerCyan
    MarkerColor.CORAL -> colors.markerCoral
    MarkerColor.EMERALD -> colors.markerEmerald
    MarkerColor.VIOLET -> colors.markerViolet
  }
}

private fun msToDp(ms: Long, pxPerSecondDp: Dp): Dp = pxPerSecondDp * (ms / 1000f)
