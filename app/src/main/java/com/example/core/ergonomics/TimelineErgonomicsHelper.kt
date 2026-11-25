package com.example.core.ergonomics

import com.example.core.model.TimelineState
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Pure ergonomic math helpers for magnetic snapping, stylus fine-scrubbing sensitivity,
 * and clamped pinch/button timeline zoom.
 */
object TimelineErgonomicsHelper {

  /**
   * Snaps [rawPositionMs] to the closest marker or clip boundary within [thresholdMs]
   * when [snapEnabled] is true.
   */
  fun applySnapIfEnabled(
    rawPositionMs: Long,
    state: TimelineState,
    thresholdMs: Long,
    snapEnabled: Boolean,
  ): Long {
    val safeRaw = rawPositionMs.coerceAtLeast(0L)
    if (!snapEnabled || thresholdMs <= 0L) return safeRaw

    val snapTargets = buildList {
      add(0L)
      state.markers.forEach { add(it.positionMs) }
      state.clips.forEach {
        add(it.timelineStartMs)
        add(it.timelineEndMs)
      }
    }

    var closestTarget = safeRaw
    var closestDistance = Long.MAX_VALUE
    for (target in snapTargets) {
      val distance = abs(target - safeRaw)
      if (distance <= thresholdMs && distance < closestDistance) {
        closestDistance = distance
        closestTarget = target
      }
    }
    return closestTarget
  }

  /**
   * Converts horizontal pointer drag distance in pixels into timeline milliseconds,
   * scaling down automatically when using a Stylus for frame-accurate positioning.
   */
  fun pointerDeltaPxToTimelineMs(
    deltaPx: Float,
    pxPerSecond: Float,
    inputMode: InputDeviceMode,
  ): Long {
    if (pxPerSecond <= 0f) return 0L
    val rawMs = (deltaPx / pxPerSecond) * 1000f
    return (rawMs * inputMode.scrubSensitivity).roundToLong()
  }

  fun clampZoom(currentScale: Float, factor: Float): Float =
    (currentScale * factor).coerceIn(
      TimelineViewportErgonomics.MIN_ZOOM,
      TimelineViewportErgonomics.MAX_ZOOM,
    )
}
