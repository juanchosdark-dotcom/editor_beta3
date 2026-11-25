package com.example.core.media.engine

import android.graphics.Matrix
import android.opengl.Matrix as GlMatrix
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.ColorGradeStageParams
import com.example.core.model.ColorGradingEngine
import com.example.core.model.EvaluatedClipProperties
import com.example.core.model.TimelineClip
import com.example.core.model.VideoClipConfig
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Time-varying Media3 [RgbMatrix] shader effect applying keyframed clip opacity
 * deterministically at each frame's `presentationTimeUs` across preview and export.
 */
@OptIn(UnstableApi::class)
class AnimatedOpacityRgbEffect(
  private val opacityProvider: (presentationTimeUs: Long) -> Float,
) : RgbMatrix {

  override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
    val alpha = opacityProvider(presentationTimeUs).coerceIn(0.05f, 1.0f)
    return FloatArray(16).apply {
      GlMatrix.setIdentityM(this, 0)
      GlMatrix.scaleM(this, 0, alpha, alpha, alpha)
    }
  }

  override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

/**
 * Shared deterministic Media3 visual-effect pipeline used by both [CompositionCompiler]
 * (final export / 540p proxy compilation) and [com.example.core.media.playback.PreviewPlayerController]
 * (real-time preview playback) to guarantee preview/render parity.
 *
 * Processing order is strictly:
 * 1. Geometric Transform
 * 2. Base Adjustments (Exposure/Brightness, Contrast, Temp/Tint, Saturation, Lift/Gamma/Gain)
 * 3. LUT Application (3D LUT cube via [SingleColorLut])
 * 4. Opacity / Blend
 */
@OptIn(UnstableApi::class)
object KeyframeMedia3Effects {

  /**
   * Maps a frame timestamp `presentationTimeUs` (in clip-source time starting at `0`)
   * onto the clip's timeline-relative keyframe offset in milliseconds, accounting for [speed].
   */
  fun presentationTimeUsToClipOffsetMs(
    presentationTimeUs: Long,
    clipDurationMs: Long,
    speed: Float = 1.0f,
  ): Long {
    val safeSpeed = speed.coerceIn(0.25f, 4.0f)
    val sourceElapsedMs = presentationTimeUs.coerceAtLeast(0L).toDouble() / 1_000.0
    val clipOffsetMs = (sourceElapsedMs / safeSpeed.toDouble()).roundToLong()
    return clipOffsetMs.coerceIn(0L, clipDurationMs.coerceAtLeast(0L))
  }

  /**
   * Evaluates a [TimelineClip] deterministically at [presentationTimeUs] inside the clip.
   */
  fun evaluateClipAtPresentationUs(
    clip: TimelineClip,
    presentationTimeUs: Long,
  ): EvaluatedClipProperties {
    val offsetMs =
      presentationTimeUsToClipOffsetMs(
        presentationTimeUs = presentationTimeUs,
        clipDurationMs = clip.durationMs,
        speed = clip.speed,
      )
    return clip.evaluatePropertiesAt(offsetMs)
  }

  /**
   * Builds the deterministic Android 2D transform [Matrix] matching preview and final export.
   */
  fun buildTransformMatrix(evaluated: EvaluatedClipProperties): Matrix =
    Matrix().apply {
      if (evaluated.rotationDegrees != 0.0f) {
        postRotate(evaluated.rotationDegrees)
      }
      if (evaluated.scale != 1.0f) {
        postScale(evaluated.scale, evaluated.scale)
      }
      if (evaluated.offsetX != 0.0f || evaluated.offsetY != 0.0f) {
        postTranslate(
          evaluated.offsetX.coerceIn(-1.5f, 1.5f),
          evaluated.offsetY.coerceIn(-1.5f, 1.5f),
        )
      }
    }

  /**
   * Builds per-clip Media3 GL [Effect]s for a single [clip] (used directly per [androidx.media3.transformer.EditedMediaItem]).
   */
  fun buildEffectsForClip(
    clip: TimelineClip,
    adjustment: AdjustmentClipConfig?,
  ): List<Effect> =
    buildEffectsWithEvaluator(
      videoCfg = clip.videoConfig,
      adjustment = adjustment,
      evaluator = { presentationTimeUs ->
        evaluateClipAtPresentationUs(clip, presentationTimeUs)
      },
    )

  /**
   * Builds Media3 GL [Effect]s given a [videoCfg], optional [adjustment], and custom
   * timestamp [evaluator] in strict deterministic order:
   * Transform -> Clip Base Adjustments -> Clip Lift/Gamma/Gain & LUT -> Adjustment Base -> Adjustment Lift/Gamma/Gain & LUT -> Opacity/Blend.
   */
  fun buildEffectsWithEvaluator(
    videoCfg: VideoClipConfig?,
    adjustment: AdjustmentClipConfig?,
    hasKeyframesOverride: Boolean? = null,
    hasAnimatedOpacityOverride: Boolean? = null,
    evaluator: (presentationTimeUs: Long) -> EvaluatedClipProperties,
  ): List<Effect> {
    val effects = mutableListOf<Effect>()
    val pipeline = ColorGradingEngine.buildPipeline(videoCfg, adjustment)

    // 0. Geometric transform
    var shouldEmitAnimatedOpacity = false
    videoCfg?.let { cfg ->
      val hasKeyframes = hasKeyframesOverride ?: cfg.keyframes.isNotEmpty()
      val hasTranslation = cfg.offsetX != 0.0f || cfg.offsetY != 0.0f
      val hasScaleOrRotate = cfg.scale != 1.0f || cfg.rotationDegrees != 0.0f

      if (hasKeyframes || hasTranslation) {
        effects.add(
          MatrixTransformation { presentationTimeUs ->
            buildTransformMatrix(evaluator(presentationTimeUs))
          },
        )
      } else if (hasScaleOrRotate) {
        effects.add(
          ScaleAndRotateTransformation.Builder()
            .setScale(cfg.scale.coerceIn(0.1f, 4.0f), cfg.scale.coerceIn(0.1f, 4.0f))
            .setRotationDegrees(cfg.rotationDegrees)
            .build(),
        )
      }

      shouldEmitAnimatedOpacity =
        hasAnimatedOpacityOverride
          ?: (cfg.opacity < 0.99f || cfg.keyframes.any { it.opacity < 0.99f })

      // 1 & 2. Clip Stage 1 (Base Adjustments) -> Clip Stage 2 (Lift/Gamma/Gain & LUT)
      appendScalarBaseEffects(effects, pipeline.clipBaseStage)
      appendToneAndLutCubeEffect(effects, pipeline.clipBaseStage, pipeline.clipLutStage)
    }

    // Adjustment layer (Base Adjustments -> Lift/Gamma/Gain & LUT, scaled by layer blendOpacity)
    pipeline.adjustmentBaseStage?.let { adjBase ->
      appendScalarBaseEffects(effects, adjBase)
      appendToneAndLutCubeEffect(effects, adjBase, pipeline.adjustmentLutStage)
    } ?: pipeline.adjustmentLutStage?.let { adjLut ->
      appendToneAndLutCubeEffect(effects, null, adjLut)
    }

    // 3. Stage 3: Opacity / Blend
    if (shouldEmitAnimatedOpacity) {
      effects.add(
        AnimatedOpacityRgbEffect { presentationTimeUs ->
          evaluator(presentationTimeUs).opacity
        },
      )
    }

    return effects
  }

  private fun appendScalarBaseEffects(
    effects: MutableList<Effect>,
    stage: ColorGradeStageParams,
  ) {
    val netBrightness = stage.netBrightnessAndExposure
    if (abs(netBrightness) > 0.005f) {
      effects.add(Brightness(netBrightness))
    }
    if (abs(stage.contrast - 1.0f) > 0.005f) {
      effects.add(Contrast((stage.contrast - 1.0f).coerceIn(-1f, 1f)))
    }
    if (abs(stage.saturation - 1.0f) > 0.005f) {
      val satShift = ((stage.saturation - 1.0f) * 100f).coerceIn(-100f, 100f)
      effects.add(HslAdjustment.Builder().adjustSaturation(satShift).build())
    }
    if (abs(stage.temperature) > 0.005f || abs(stage.tint) > 0.005f) {
      val tempShift = (stage.temperature * 0.18f).coerceIn(-0.35f, 0.35f)
      val tintShift = (stage.tint * 0.16f).coerceIn(-0.32f, 0.32f)
      effects.add(
        RgbAdjustment.Builder()
          .setRedScale((1.0f + tempShift + tintShift * 0.5f).coerceIn(0.2f, 2.0f))
          .setGreenScale((1.0f - tintShift).coerceIn(0.2f, 2.0f))
          .setBlueScale((1.0f - tempShift + tintShift * 0.5f).coerceIn(0.2f, 2.0f))
          .build(),
      )
    }
  }

  private fun appendToneAndLutCubeEffect(
    effects: MutableList<Effect>,
    toneStage: ColorGradeStageParams?,
    lutStage: ColorGradeStageParams?,
  ) {
    val needsToneCube = toneStage?.hasToneWheelAdjustments == true
    val needsLutCube = lutStage?.hasLutApplication == true
    if (!needsToneCube && !needsLutCube) return

    val cube =
      ColorGradingEngine.buildStage3dLutCube(
        size = 17,
        toneStage = toneStage,
        lutStage = lutStage,
      )
    effects.add(SingleColorLut.createFromCube(cube))
  }
}
