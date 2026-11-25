package com.example.core.export

import com.example.core.model.ClipKeyframeEvaluator
import com.example.core.model.ClipKind
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineMarker
import com.example.core.model.TimelineState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Pure domain helpers for slicing a multitrack [TimelineState] by time ranges or markers
 * before compiling it for local export.
 */
object RangeSliceHelper {

  /**
   * Returns a normalized [TimelineState] containing only the segments overlapping
   * `[rangeStartMs, rangeEndMs]`, shifted so `rangeStartMs` maps to `0L`.
   */
  fun sliceTimelineForRange(
    timeline: TimelineState,
    rangeStartMs: Long,
    rangeEndMs: Long,
  ): TimelineState {
    require(rangeStartMs >= 0L) { "rangeStartMs must be >= 0" }
    require(rangeEndMs > rangeStartMs) { "rangeEndMs must be > rangeStartMs" }

    val slicedClips =
      timeline.clips.mapNotNull { clip ->
        sliceClip(clip, rangeStartMs, rangeEndMs)
      }

    val slicedMarkers =
      timeline.markers
        .filter { it.positionMs in rangeStartMs..rangeEndMs }
        .map { it.copy(positionMs = it.positionMs - rangeStartMs) }

    return timeline.copy(
      playheadMs = 0L,
      clips = slicedClips.sortedBy { it.timelineStartMs },
      markers = slicedMarkers,
    )
  }

  private fun sliceClip(
    clip: TimelineClip,
    rangeStartMs: Long,
    rangeEndMs: Long,
  ): TimelineClip? {
    if (!clip.isEnabled) return null
    val overlapStart = max(clip.timelineStartMs, rangeStartMs)
    val overlapEnd = min(clip.timelineEndMs, rangeEndMs)
    if (overlapEnd <= overlapStart) return null

    val hasMediaSource = clip.kind == ClipKind.VIDEO || clip.kind == ClipKind.AUDIO
    val trimLeftTimelineMs = overlapStart - clip.timelineStartMs
    val trimRightTimelineMs = clip.timelineEndMs - overlapEnd

    val nextSourceIn =
      if (hasMediaSource) {
        clip.sourceInMs + (trimLeftTimelineMs * clip.speed).roundToLong()
      } else {
        clip.sourceInMs
      }

    val nextSourceOut =
      if (hasMediaSource) {
        (clip.sourceOutMs - (trimRightTimelineMs * clip.speed).roundToLong())
          .coerceAtLeast(nextSourceIn + 1L)
      } else {
        clip.sourceOutMs
      }

    val sliceRelStartMs = (overlapStart - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
    val sliceRelEndMs = (overlapEnd - clip.timelineStartMs).coerceIn(sliceRelStartMs, clip.durationMs)

    val slicedVideoConfig =
      clip.videoConfig?.let { cfg ->
        if (cfg.keyframes.isEmpty()) {
          cfg
        } else {
          cfg.copy(
            keyframes =
              ClipKeyframeEvaluator.sliceKeyframesForRange(
                keyframes = cfg.keyframes,
                rangeOffsetStartMs = sliceRelStartMs,
                rangeOffsetEndMs = sliceRelEndMs,
                baseScale = cfg.scale,
                baseOpacity = cfg.opacity,
                baseOffsetX = cfg.offsetX,
                baseOffsetY = cfg.offsetY,
                baseRotationDegrees = cfg.rotationDegrees,
                baseVolume = cfg.volume,
                idPrefix = "${clip.clipId}_range",
              ),
          )
        }
      }

    val slicedAudioConfig =
      clip.audioConfig?.let { cfg ->
        if (cfg.keyframes.isEmpty()) {
          cfg
        } else {
          cfg.copy(
            keyframes =
              ClipKeyframeEvaluator.sliceKeyframesForRange(
                keyframes = cfg.keyframes,
                rangeOffsetStartMs = sliceRelStartMs,
                rangeOffsetEndMs = sliceRelEndMs,
                baseVolume = cfg.volume,
                idPrefix = "${clip.clipId}_arange",
              ),
          )
        }
      }

    return clip.copy(
      timelineStartMs = overlapStart - rangeStartMs,
      timelineEndMs = overlapEnd - rangeStartMs,
      sourceInMs = nextSourceIn,
      sourceOutMs = nextSourceOut,
      videoConfig = slicedVideoConfig,
      audioConfig = slicedAudioConfig,
    )
  }

  /**
   * Builds consecutive chapter spans delimited by sorted [markers] across `[0, totalDurationMs]`.
   * Example: markers at 2.5s and 5.0s on a 9.5s timeline produce:
   * - `0.0s .. 2.5s` (Inicio -> Golpe de ritmo)
   * - `2.5s .. 5.0s` (Golpe de ritmo -> Cambio de escena)
   * - `5.0s .. 9.5s` (Cambio de escena -> Final)
   */
  fun buildMarkerChapterSpans(
    markers: List<TimelineMarker>,
    totalDurationMs: Long,
  ): List<ExportRange.MarkerSpan> {
    if (totalDurationMs <= 0L) return emptyList()
    val sorted =
      markers
        .filter { it.positionMs in 1L until totalDurationMs }
        .sortedBy { it.positionMs }
        .distinctBy { it.positionMs }

    if (sorted.isEmpty()) {
      return listOf(
        ExportRange.MarkerSpan(
          startMarkerId = null,
          endMarkerId = null,
          startMs = 0L,
          endMs = totalDurationMs,
          label = "Clip completo",
        ),
      )
    }

    val spans = mutableListOf<ExportRange.MarkerSpan>()
    var prevMs = 0L
    var prevMarker: TimelineMarker? = null

    for (marker in sorted) {
      if (marker.positionMs > prevMs) {
        spans.add(
          ExportRange.MarkerSpan(
            startMarkerId = prevMarker?.markerId,
            endMarkerId = marker.markerId,
            startMs = prevMs,
            endMs = marker.positionMs,
            label = "${prevMarker?.label ?: "Inicio"} → ${marker.label}",
          ),
        )
      }
      prevMs = marker.positionMs
      prevMarker = marker
    }

    if (totalDurationMs > prevMs && prevMarker != null) {
      spans.add(
        ExportRange.MarkerSpan(
          startMarkerId = prevMarker.markerId,
          endMarkerId = null,
          startMs = prevMs,
          endMs = totalDurationMs,
          label = "${prevMarker.label} → Final",
        ),
      )
    }

    return spans
  }
}
