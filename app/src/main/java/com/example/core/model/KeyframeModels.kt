package com.example.core.model

import kotlin.math.abs

/**
 * Interpolation curve governing transitions from a keyframe to the next keyframe.
 */
enum class KeyframeInterpolation(val displayLabel: String) {
  LINEAR("Lineal"),
  EASE_IN("Ease In"),
  EASE_OUT("Ease Out"),
  EASE_IN_OUT("Ease In-Out"),
}

/**
 * Deterministically evaluated animation state at a specific clip-relative timestamp.
 */
data class EvaluatedClipProperties(
  val offsetMs: Long,
  val scale: Float,
  val opacity: Float,
  val offsetX: Float,
  val offsetY: Float,
  val rotationDegrees: Float,
  val volume: Float,
  val interpolation: KeyframeInterpolation = KeyframeInterpolation.LINEAR,
) {
  fun toClipKeyframe(
    keyframeId: String,
    includeVolume: Boolean = true,
  ): ClipKeyframe =
    ClipKeyframe(
      keyframeId = keyframeId,
      offsetMs = offsetMs,
      scale = scale,
      opacity = opacity,
      offsetX = offsetX,
      offsetY = offsetY,
      rotationDegrees = rotationDegrees,
      volume = if (includeVolume) volume else null,
      interpolation = interpolation,
    )
}

enum class KeyframeValidationErrorCode {
  NEGATIVE_OFFSET,
  OFFSET_EXCEEDS_CLIP_DURATION,
  NON_FINITE_VALUE,
  SCALE_OUT_OF_RANGE,
  OPACITY_OUT_OF_RANGE,
  POSITION_OUT_OF_RANGE,
  ROTATION_OUT_OF_RANGE,
  VOLUME_OUT_OF_RANGE,
  DUPLICATE_KEYFRAME_ID,
}

data class KeyframeValidationIssue(
  val keyframeId: String,
  val code: KeyframeValidationErrorCode,
  val message: String,
)

data class KeyframeValidationResult(
  val issues: List<KeyframeValidationIssue> = emptyList(),
) {
  val isValid: Boolean
    get() = issues.isEmpty()
}

/**
 * Validates and sanitizes per-clip keyframes against domain and bounds invariants.
 */
object KeyframeValidator {
  const val MIN_SCALE = 0.1f
  const val MAX_SCALE = 4.0f
  const val MIN_OPACITY = 0.0f
  const val MAX_OPACITY = 1.0f
  const val MIN_OFFSET = -2.0f
  const val MAX_OFFSET = 2.0f
  const val MIN_ROTATION_DEGREES = -360.0f
  const val MAX_ROTATION_DEGREES = 360.0f
  const val MIN_VOLUME = 0.0f
  const val MAX_VOLUME = 2.0f

  fun validateKeyframe(
    keyframe: ClipKeyframe,
    clipDurationMs: Long? = null,
  ): KeyframeValidationResult {
    val issues = mutableListOf<KeyframeValidationIssue>()
    val id = keyframe.keyframeId

    if (keyframe.offsetMs < 0L) {
      issues +=
        KeyframeValidationIssue(
          keyframeId = id,
          code = KeyframeValidationErrorCode.NEGATIVE_OFFSET,
          message = "El tiempo del keyframe (${keyframe.offsetMs} ms) no puede ser negativo.",
        )
    }
    if (clipDurationMs != null && clipDurationMs >= 0L && keyframe.offsetMs > clipDurationMs) {
      issues +=
        KeyframeValidationIssue(
          keyframeId = id,
          code = KeyframeValidationErrorCode.OFFSET_EXCEEDS_CLIP_DURATION,
          message = "El tiempo (${keyframe.offsetMs} ms) excede la duración del clip ($clipDurationMs ms).",
        )
    }

    val numericFields =
      listOfNotNull(
        keyframe.scale,
        keyframe.opacity,
        keyframe.offsetX,
        keyframe.offsetY,
        keyframe.rotationDegrees,
        keyframe.volume,
      )
    if (numericFields.any { !it.isFinite() }) {
      issues +=
        KeyframeValidationIssue(
          keyframeId = id,
          code = KeyframeValidationErrorCode.NON_FINITE_VALUE,
          message = "Los valores numéricos del keyframe deben ser finitos.",
        )
    } else {
      if (keyframe.scale !in MIN_SCALE..MAX_SCALE) {
        issues +=
          KeyframeValidationIssue(
            keyframeId = id,
            code = KeyframeValidationErrorCode.SCALE_OUT_OF_RANGE,
            message = "Escala ${keyframe.scale} fuera de rango [$MIN_SCALE, $MAX_SCALE].",
          )
      }
      if (keyframe.opacity !in MIN_OPACITY..MAX_OPACITY) {
        issues +=
          KeyframeValidationIssue(
            keyframeId = id,
            code = KeyframeValidationErrorCode.OPACITY_OUT_OF_RANGE,
            message = "Opacidad ${keyframe.opacity} fuera de rango [$MIN_OPACITY, $MAX_OPACITY].",
          )
      }
      if (keyframe.offsetX !in MIN_OFFSET..MAX_OFFSET || keyframe.offsetY !in MIN_OFFSET..MAX_OFFSET) {
        issues +=
          KeyframeValidationIssue(
            keyframeId = id,
            code = KeyframeValidationErrorCode.POSITION_OUT_OF_RANGE,
            message = "Posición (${keyframe.offsetX}, ${keyframe.offsetY}) fuera de rango [$MIN_OFFSET, $MAX_OFFSET].",
          )
      }
      if (keyframe.rotationDegrees !in MIN_ROTATION_DEGREES..MAX_ROTATION_DEGREES) {
        issues +=
          KeyframeValidationIssue(
            keyframeId = id,
            code = KeyframeValidationErrorCode.ROTATION_OUT_OF_RANGE,
            message = "Rotación ${keyframe.rotationDegrees}° fuera de rango [$MIN_ROTATION_DEGREES, $MAX_ROTATION_DEGREES].",
          )
      }
      val vol = keyframe.volume
      if (vol != null && vol !in MIN_VOLUME..MAX_VOLUME) {
        issues +=
          KeyframeValidationIssue(
            keyframeId = id,
            code = KeyframeValidationErrorCode.VOLUME_OUT_OF_RANGE,
            message = "Volumen $vol fuera de rango [$MIN_VOLUME, $MAX_VOLUME].",
          )
      }
    }

    return KeyframeValidationResult(issues)
  }

  fun validateKeyframes(
    keyframes: List<ClipKeyframe>,
    clipDurationMs: Long? = null,
  ): KeyframeValidationResult {
    val issues = mutableListOf<KeyframeValidationIssue>()
    val seenIds = mutableSetOf<String>()
    for (kf in keyframes) {
      if (!seenIds.add(kf.keyframeId)) {
        issues +=
          KeyframeValidationIssue(
            keyframeId = kf.keyframeId,
            code = KeyframeValidationErrorCode.DUPLICATE_KEYFRAME_ID,
            message = "Keyframe duplicado con id '${kf.keyframeId}'.",
          )
      }
      issues += validateKeyframe(kf, clipDurationMs).issues
    }
    return KeyframeValidationResult(issues)
  }

  fun sanitizeKeyframe(
    keyframe: ClipKeyframe,
    clipDurationMs: Long? = null,
  ): ClipKeyframe {
    val maxOffset = clipDurationMs?.coerceAtLeast(0L) ?: Long.MAX_VALUE
    return keyframe.copy(
      offsetMs = keyframe.offsetMs.coerceIn(0L, maxOffset),
      scale = (if (keyframe.scale.isFinite()) keyframe.scale else 1.0f).coerceIn(MIN_SCALE, MAX_SCALE),
      opacity = (if (keyframe.opacity.isFinite()) keyframe.opacity else 1.0f).coerceIn(MIN_OPACITY, MAX_OPACITY),
      offsetX = (if (keyframe.offsetX.isFinite()) keyframe.offsetX else 0.0f).coerceIn(MIN_OFFSET, MAX_OFFSET),
      offsetY = (if (keyframe.offsetY.isFinite()) keyframe.offsetY else 0.0f).coerceIn(MIN_OFFSET, MAX_OFFSET),
      rotationDegrees =
        (if (keyframe.rotationDegrees.isFinite()) keyframe.rotationDegrees else 0.0f)
          .coerceIn(MIN_ROTATION_DEGREES, MAX_ROTATION_DEGREES),
      volume =
        keyframe.volume?.let { v ->
          (if (v.isFinite()) v else 1.0f).coerceIn(MIN_VOLUME, MAX_VOLUME)
        },
    )
  }

  fun normalizeKeyframes(
    keyframes: List<ClipKeyframe>,
    clipDurationMs: Long? = null,
  ): List<ClipKeyframe> {
    if (keyframes.isEmpty()) return emptyList()
    val byOffset = LinkedHashMap<Long, ClipKeyframe>(keyframes.size)
    for (kf in keyframes) {
      val clean = sanitizeKeyframe(kf, clipDurationMs)
      byOffset[clean.offsetMs] = clean
    }
    return byOffset.values.sortedBy { it.offsetMs }
  }
}

/**
 * Deterministic keyframe interpolator shared by live preview, timeline scrubbing,
 * range slicing, and Media3 Transformer export.
 */
object ClipKeyframeEvaluator {

  /**
   * Computes eased progress in `[0.0, 1.0]` for normalized linear time `rawT`
   * using the outgoing [interpolation] curve of the left keyframe.
   */
  fun applyEasing(
    rawT: Double,
    interpolation: KeyframeInterpolation,
  ): Double {
    val t = rawT.coerceIn(0.0, 1.0)
    return when (interpolation) {
      KeyframeInterpolation.LINEAR -> t
      KeyframeInterpolation.EASE_IN -> t * t * t
      KeyframeInterpolation.EASE_OUT -> {
        val inv = 1.0 - t
        1.0 - (inv * inv * inv)
      }
      KeyframeInterpolation.EASE_IN_OUT -> {
        if (t < 0.5) {
          4.0 * t * t * t
        } else {
          val u = -2.0 * t + 2.0
          1.0 - ((u * u * u) / 2.0)
        }
      }
    }
  }

  fun evaluateAt(
    offsetMs: Long,
    keyframes: List<ClipKeyframe>,
    baseScale: Float = 1.0f,
    baseOpacity: Float = 1.0f,
    baseOffsetX: Float = 0.0f,
    baseOffsetY: Float = 0.0f,
    baseRotationDegrees: Float = 0.0f,
    baseVolume: Float = 1.0f,
  ): EvaluatedClipProperties {
    val safeOffsetMs = offsetMs.coerceAtLeast(0L)
    if (keyframes.isEmpty()) {
      return EvaluatedClipProperties(
        offsetMs = safeOffsetMs,
        scale = baseScale,
        opacity = baseOpacity,
        offsetX = baseOffsetX,
        offsetY = baseOffsetY,
        rotationDegrees = baseRotationDegrees,
        volume = baseVolume,
        interpolation = KeyframeInterpolation.LINEAR,
      )
    }

    val sorted = keyframes.sortedBy { it.offsetMs }
    val first = sorted.first()
    if (safeOffsetMs <= first.offsetMs) {
      return EvaluatedClipProperties(
        offsetMs = safeOffsetMs,
        scale = first.scale,
        opacity = first.opacity,
        offsetX = first.offsetX,
        offsetY = first.offsetY,
        rotationDegrees = first.rotationDegrees,
        volume = first.volume ?: baseVolume,
        interpolation = first.interpolation,
      )
    }

    val last = sorted.last()
    if (safeOffsetMs >= last.offsetMs) {
      return EvaluatedClipProperties(
        offsetMs = safeOffsetMs,
        scale = last.scale,
        opacity = last.opacity,
        offsetX = last.offsetX,
        offsetY = last.offsetY,
        rotationDegrees = last.rotationDegrees,
        volume = last.volume ?: baseVolume,
        interpolation = last.interpolation,
      )
    }

    // If duplicate offsets exist, choose the bracket bracketing safeOffsetMs cleanly.
    var leftIndex = 0
    for (i in 0 until sorted.lastIndex) {
      if (sorted[i].offsetMs <= safeOffsetMs && sorted[i + 1].offsetMs >= safeOffsetMs) {
        leftIndex = i
        if (sorted[i].offsetMs < sorted[i + 1].offsetMs) {
          break
        }
      }
    }
    val left = sorted[leftIndex]
    val right = sorted[leftIndex + 1]

    if (safeOffsetMs == right.offsetMs) {
      return EvaluatedClipProperties(
        offsetMs = safeOffsetMs,
        scale = right.scale,
        opacity = right.opacity,
        offsetX = right.offsetX,
        offsetY = right.offsetY,
        rotationDegrees = right.rotationDegrees,
        volume = right.volume ?: baseVolume,
        interpolation = right.interpolation,
      )
    }
    if (safeOffsetMs == left.offsetMs || right.offsetMs <= left.offsetMs) {
      return EvaluatedClipProperties(
        offsetMs = safeOffsetMs,
        scale = left.scale,
        opacity = left.opacity,
        offsetX = left.offsetX,
        offsetY = left.offsetY,
        rotationDegrees = left.rotationDegrees,
        volume = left.volume ?: baseVolume,
        interpolation = left.interpolation,
      )
    }

    val span = (right.offsetMs - left.offsetMs).toDouble()
    val rawT = ((safeOffsetMs - left.offsetMs).toDouble() / span).coerceIn(0.0, 1.0)
    val eased = applyEasing(rawT, left.interpolation)

    val leftVol = left.volume ?: baseVolume
    val rightVol = right.volume ?: baseVolume

    return EvaluatedClipProperties(
      offsetMs = safeOffsetMs,
      scale = lerpFloat(left.scale, right.scale, eased),
      opacity = lerpFloat(left.opacity, right.opacity, eased),
      offsetX = lerpFloat(left.offsetX, right.offsetX, eased),
      offsetY = lerpFloat(left.offsetY, right.offsetY, eased),
      rotationDegrees = lerpFloat(left.rotationDegrees, right.rotationDegrees, eased),
      volume = lerpFloat(leftVol, rightVol, eased),
      interpolation = left.interpolation,
    )
  }

  /**
   * Slices and shifts keyframes to match a sub-range `[rangeOffsetStartMs, rangeOffsetEndMs]`
   * within a clip, inserting deterministic boundary keyframes when keyframes span an edge.
   */
  fun sliceKeyframesForRange(
    keyframes: List<ClipKeyframe>,
    rangeOffsetStartMs: Long,
    rangeOffsetEndMs: Long,
    baseScale: Float = 1.0f,
    baseOpacity: Float = 1.0f,
    baseOffsetX: Float = 0.0f,
    baseOffsetY: Float = 0.0f,
    baseRotationDegrees: Float = 0.0f,
    baseVolume: Float = 1.0f,
    idPrefix: String = "kf_slice",
  ): List<ClipKeyframe> {
    if (keyframes.isEmpty() || rangeOffsetEndMs <= rangeOffsetStartMs) return emptyList()
    val sorted = keyframes.sortedBy { it.offsetMs }
    val hasVolumeKeyframes = sorted.any { it.volume != null }

    val inside =
      sorted
        .filter { it.offsetMs in rangeOffsetStartMs..rangeOffsetEndMs }
        .map { it.copy(offsetMs = it.offsetMs - rangeOffsetStartMs) }
        .toMutableList()

    // Ensure animation state is preserved when the sub-range starts mid-transition.
    if (inside.none { it.offsetMs == 0L }) {
      val needsStartAnchor = sorted.any { it.offsetMs < rangeOffsetStartMs } && sorted.any { it.offsetMs > rangeOffsetStartMs }
      if (needsStartAnchor) {
        val startEval =
          evaluateAt(
            offsetMs = rangeOffsetStartMs,
            keyframes = sorted,
            baseScale = baseScale,
            baseOpacity = baseOpacity,
            baseOffsetX = baseOffsetX,
            baseOffsetY = baseOffsetY,
            baseRotationDegrees = baseRotationDegrees,
            baseVolume = baseVolume,
          )
        inside.add(0, startEval.copy(offsetMs = 0L).toClipKeyframe("${idPrefix}_start", hasVolumeKeyframes))
      }
    }

    val targetDuration = rangeOffsetEndMs - rangeOffsetStartMs
    if (inside.none { it.offsetMs == targetDuration }) {
      val needsEndAnchor = sorted.any { it.offsetMs < rangeOffsetEndMs } && sorted.any { it.offsetMs > rangeOffsetEndMs }
      if (needsEndAnchor) {
        val endEval =
          evaluateAt(
            offsetMs = rangeOffsetEndMs,
            keyframes = sorted,
            baseScale = baseScale,
            baseOpacity = baseOpacity,
            baseOffsetX = baseOffsetX,
            baseOffsetY = baseOffsetY,
            baseRotationDegrees = baseRotationDegrees,
            baseVolume = baseVolume,
          )
        inside.add(endEval.copy(offsetMs = targetDuration).toClipKeyframe("${idPrefix}_end", hasVolumeKeyframes))
      }
    }

    return inside.sortedBy { it.offsetMs }
  }

  /**
   * Scales all keyframe timestamps proportionally when clip playback speed changes.
   */
  fun rescaleKeyframesForDuration(
    keyframes: List<ClipKeyframe>,
    oldDurationMs: Long,
    newDurationMs: Long,
  ): List<ClipKeyframe> {
    if (keyframes.isEmpty() || oldDurationMs <= 0L || newDurationMs <= 0L) return keyframes
    val ratio = newDurationMs.toDouble() / oldDurationMs.toDouble()
    return keyframes
      .map { kf ->
        val scaledOffset = (kf.offsetMs.toDouble() * ratio).toLong().coerceIn(0L, newDurationMs)
        kf.copy(offsetMs = scaledOffset)
      }
      .sortedBy { it.offsetMs }
  }

  private fun lerpFloat(start: Float, end: Float, t: Double): Float {
    val result = start.toDouble() + (end.toDouble() - start.toDouble()) * t
    return if (abs(result) < 1e-6) 0f else result.toFloat()
  }
}
