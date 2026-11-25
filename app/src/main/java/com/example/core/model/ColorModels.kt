package com.example.core.model

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Deterministic ordered stages of FrameLine's non-destructive color grading pipeline.
 */
enum class ColorPipelineStage {
  BASE_ADJUSTMENTS,
  LUT_APPLICATION,
  OPACITY_BLEND,
}

/**
 * Validation & deterministic clamping bounds for parametric clip and adjustment-layer color controls.
 */
object ColorGradeBounds {
  const val MIN_BRIGHTNESS = -0.5f
  const val MAX_BRIGHTNESS = 0.5f
  const val DEFAULT_BRIGHTNESS = 0.0f

  const val MIN_EXPOSURE = -1.0f
  const val MAX_EXPOSURE = 1.0f
  const val DEFAULT_EXPOSURE = 0.0f

  const val MIN_CONTRAST = 0.5f
  const val MAX_CONTRAST = 1.8f
  const val DEFAULT_CONTRAST = 1.0f

  const val MIN_SATURATION = 0.0f
  const val MAX_SATURATION = 2.0f
  const val DEFAULT_SATURATION = 1.0f

  const val MIN_TEMPERATURE = -1.0f
  const val MAX_TEMPERATURE = 1.0f
  const val DEFAULT_TEMPERATURE = 0.0f

  const val MIN_TINT = -1.0f
  const val MAX_TINT = 1.0f
  const val DEFAULT_TINT = 0.0f

  const val MIN_SHADOWS = -1.0f
  const val MAX_SHADOWS = 1.0f
  const val DEFAULT_SHADOWS = 0.0f

  const val MIN_MIDTONES = -1.0f
  const val MAX_MIDTONES = 1.0f
  const val DEFAULT_MIDTONES = 0.0f

  const val MIN_HIGHLIGHTS = -1.0f
  const val MAX_HIGHLIGHTS = 1.0f
  const val DEFAULT_HIGHLIGHTS = 0.0f

  const val MIN_VIGNETTE = 0.0f
  const val MAX_VIGNETTE = 1.0f
  const val DEFAULT_VIGNETTE = 0.0f

  const val MIN_LUT_STRENGTH = 0.0f
  const val MAX_LUT_STRENGTH = 1.0f
  const val DEFAULT_LUT_STRENGTH = 1.0f

  const val MIN_BLEND_OPACITY = 0.0f
  const val MAX_BLEND_OPACITY = 1.0f
  const val DEFAULT_BLEND_OPACITY = 1.0f

  fun clampFinite(
    value: Float,
    min: Float,
    max: Float,
    defaultValue: Float,
  ): Float = (if (value.isFinite()) value else defaultValue).coerceIn(min, max)

  fun clampBrightness(value: Float): Float =
    clampFinite(value, MIN_BRIGHTNESS, MAX_BRIGHTNESS, DEFAULT_BRIGHTNESS)

  fun clampExposure(value: Float): Float =
    clampFinite(value, MIN_EXPOSURE, MAX_EXPOSURE, DEFAULT_EXPOSURE)

  fun clampContrast(value: Float): Float =
    clampFinite(value, MIN_CONTRAST, MAX_CONTRAST, DEFAULT_CONTRAST)

  fun clampSaturation(value: Float): Float =
    clampFinite(value, MIN_SATURATION, MAX_SATURATION, DEFAULT_SATURATION)

  fun clampTemperature(value: Float): Float =
    clampFinite(value, MIN_TEMPERATURE, MAX_TEMPERATURE, DEFAULT_TEMPERATURE)

  fun clampTint(value: Float): Float =
    clampFinite(value, MIN_TINT, MAX_TINT, DEFAULT_TINT)

  fun clampShadows(value: Float): Float =
    clampFinite(value, MIN_SHADOWS, MAX_SHADOWS, DEFAULT_SHADOWS)

  fun clampMidtones(value: Float): Float =
    clampFinite(value, MIN_MIDTONES, MAX_MIDTONES, DEFAULT_MIDTONES)

  fun clampHighlights(value: Float): Float =
    clampFinite(value, MIN_HIGHLIGHTS, MAX_HIGHLIGHTS, DEFAULT_HIGHLIGHTS)

  fun clampVignette(value: Float): Float =
    clampFinite(value, MIN_VIGNETTE, MAX_VIGNETTE, DEFAULT_VIGNETTE)

  fun clampLutStrength(value: Float): Float =
    clampFinite(value, MIN_LUT_STRENGTH, MAX_LUT_STRENGTH, DEFAULT_LUT_STRENGTH)

  fun clampBlendOpacity(value: Float): Float =
    clampFinite(value, MIN_BLEND_OPACITY, MAX_BLEND_OPACITY, DEFAULT_BLEND_OPACITY)

  fun clampVideoColor(cfg: VideoClipConfig): VideoClipConfig =
    cfg.copy(
      brightness = clampBrightness(cfg.brightness),
      contrast = clampContrast(cfg.contrast),
      saturation = clampSaturation(cfg.saturation),
      exposure = clampExposure(cfg.exposure),
      temperature = clampTemperature(cfg.temperature),
      vignette = clampVignette(cfg.vignette),
      lutPresetId = cfg.lutPresetId?.takeUnless { it.isBlank() || it == BuiltInLutPresets.ID_NONE },
      lutStrength = clampLutStrength(cfg.lutStrength),
      tint = clampTint(cfg.tint),
      shadows = clampShadows(cfg.shadows),
      midtones = clampMidtones(cfg.midtones),
      highlights = clampHighlights(cfg.highlights),
    )

  fun clampAdjustmentColor(cfg: AdjustmentClipConfig): AdjustmentClipConfig =
    cfg.copy(
      brightness = clampBrightness(cfg.brightness),
      contrast = clampContrast(cfg.contrast),
      saturation = clampSaturation(cfg.saturation),
      exposure = clampExposure(cfg.exposure),
      temperature = clampTemperature(cfg.temperature),
      vignette = clampVignette(cfg.vignette),
      lutPresetId = cfg.lutPresetId?.takeUnless { it.isBlank() || it == BuiltInLutPresets.ID_NONE },
      lutStrength = clampLutStrength(cfg.lutStrength),
      tint = clampTint(cfg.tint),
      shadows = clampShadows(cfg.shadows),
      midtones = clampMidtones(cfg.midtones),
      highlights = clampHighlights(cfg.highlights),
      blendOpacity = clampBlendOpacity(cfg.blendOpacity),
    )
}

/**
 * Resolved parametric grading values for a single stage or layer, already clamped and blend-scaled.
 */
data class ColorGradeStageParams(
  val brightness: Float = 0.0f,
  val exposure: Float = 0.0f,
  val contrast: Float = 1.0f,
  val saturation: Float = 1.0f,
  val temperature: Float = 0.0f,
  val tint: Float = 0.0f,
  val shadows: Float = 0.0f,
  val midtones: Float = 0.0f,
  val highlights: Float = 0.0f,
  val vignette: Float = 0.0f,
  val activeLut: LutPreset? = null,
  val lutStrength: Float = 0.0f,
  val blendOpacity: Float = 1.0f,
) {
  val netBrightnessAndExposure: Float
    get() = (brightness + exposure * 0.35f).coerceIn(-1.0f, 1.0f)

  val hasScalarBaseAdjustments: Boolean
    get() =
      abs(netBrightnessAndExposure) > 0.005f ||
        abs(contrast - 1.0f) > 0.005f ||
        abs(saturation - 1.0f) > 0.005f ||
        abs(temperature) > 0.005f ||
        abs(tint) > 0.005f

  val hasToneWheelAdjustments: Boolean
    get() =
      abs(shadows) > 0.005f ||
        abs(midtones) > 0.005f ||
        abs(highlights) > 0.005f

  val hasLutApplication: Boolean
    get() =
      activeLut != null &&
        activeLut.id != BuiltInLutPresets.ID_NONE &&
        lutStrength > 0.005f
}

/**
 * Deterministic representation of the complete ordered color pipeline:
 * 1) Base parametric adjustments (clip + blend-scaled adjustment base)
 * 2) LUT application (clip LUT + blend-scaled adjustment LUT)
 * 3) Opacity / blend (clip opacity and layer blend opacity)
 */
data class ResolvedColorPipeline(
  val orderedStages: List<ColorPipelineStage> =
    listOf(
      ColorPipelineStage.BASE_ADJUSTMENTS,
      ColorPipelineStage.LUT_APPLICATION,
      ColorPipelineStage.OPACITY_BLEND,
    ),
  val clipBaseStage: ColorGradeStageParams = ColorGradeStageParams(),
  val clipLutStage: ColorGradeStageParams = ColorGradeStageParams(),
  val adjustmentBaseStage: ColorGradeStageParams? = null,
  val adjustmentLutStage: ColorGradeStageParams? = null,
  val clipOpacity: Float = 1.0f,
  val combinedGrade: EffectiveColorGrading = EffectiveColorGrading(),
)

/**
 * Deterministic CPU + 3D-LUT color math shared by preview, standby UI monitor, and export.
 */
object ColorGradingEngine {

  fun extractClipBaseStage(cfg: VideoClipConfig): ColorGradeStageParams {
    val clean = ColorGradeBounds.clampVideoColor(cfg)
    return ColorGradeStageParams(
      brightness = clean.brightness,
      exposure = clean.exposure,
      contrast = clean.contrast,
      saturation = clean.saturation,
      temperature = clean.temperature,
      tint = clean.tint,
      shadows = clean.shadows,
      midtones = clean.midtones,
      highlights = clean.highlights,
      vignette = clean.vignette,
      blendOpacity = 1.0f,
    )
  }

  fun extractClipLutStage(cfg: VideoClipConfig): ColorGradeStageParams {
    val clean = ColorGradeBounds.clampVideoColor(cfg)
    val preset = BuiltInLutPresets.findById(clean.lutPresetId)?.takeIf { it.id != BuiltInLutPresets.ID_NONE }
    val strength = if (preset != null) clean.lutStrength else 0.0f
    return ColorGradeStageParams(
      activeLut = preset,
      lutStrength = strength,
      blendOpacity = 1.0f,
    )
  }

  fun extractAdjustmentBaseStage(cfg: AdjustmentClipConfig): ColorGradeStageParams {
    val clean = ColorGradeBounds.clampAdjustmentColor(cfg)
    val op = clean.blendOpacity
    return ColorGradeStageParams(
      brightness = clean.brightness * op,
      exposure = clean.exposure * op,
      contrast = 1.0f + (clean.contrast - 1.0f) * op,
      saturation = 1.0f + (clean.saturation - 1.0f) * op,
      temperature = clean.temperature * op,
      tint = clean.tint * op,
      shadows = clean.shadows * op,
      midtones = clean.midtones * op,
      highlights = clean.highlights * op,
      vignette = clean.vignette * op,
      blendOpacity = op,
    )
  }

  fun extractAdjustmentLutStage(cfg: AdjustmentClipConfig): ColorGradeStageParams {
    val clean = ColorGradeBounds.clampAdjustmentColor(cfg)
    val op = clean.blendOpacity
    val preset = BuiltInLutPresets.findById(clean.lutPresetId)?.takeIf { it.id != BuiltInLutPresets.ID_NONE }
    val effectiveStrength = if (preset != null) (clean.lutStrength * op).coerceIn(0f, 1f) else 0f
    return ColorGradeStageParams(
      activeLut = preset,
      lutStrength = effectiveStrength,
      blendOpacity = op,
    )
  }

  fun buildPipeline(
    videoCfg: VideoClipConfig?,
    adjustmentCfg: AdjustmentClipConfig?,
    evaluatedClipOpacity: Float? = null,
  ): ResolvedColorPipeline {
    val clipBase = videoCfg?.let(::extractClipBaseStage) ?: ColorGradeStageParams()
    val clipLut = videoCfg?.let(::extractClipLutStage) ?: ColorGradeStageParams()
    val adjBase = adjustmentCfg?.let(::extractAdjustmentBaseStage)
    val adjLut = adjustmentCfg?.let(::extractAdjustmentLutStage)
    val finalOpacity =
      ColorGradeBounds.clampFinite(
        evaluatedClipOpacity ?: videoCfg?.opacity ?: 1.0f,
        0.0f,
        1.0f,
        1.0f,
      )

    val resolvedClip = videoCfg?.let { BuiltInLutPresets.resolveEffectiveColor(it) }
    val resolvedAdj = adjustmentCfg?.let { BuiltInLutPresets.resolveEffectiveColor(it) }

    val combinedBrightness =
      ((resolvedClip?.brightness ?: 0f) + (resolvedAdj?.brightness ?: 0f)).coerceIn(-0.8f, 0.8f)
    val combinedExposure =
      ((resolvedClip?.exposure ?: 0f) + (resolvedAdj?.exposure ?: 0f)).coerceIn(-1.2f, 1.2f)
    val combinedContrast =
      ((resolvedClip?.contrast ?: 1f) * (resolvedAdj?.contrast ?: 1f)).coerceIn(0.3f, 2.0f)
    val combinedSaturation =
      ((resolvedClip?.saturation ?: 1f) * (resolvedAdj?.saturation ?: 1f)).coerceIn(0.0f, 2.2f)
    val combinedTemp =
      ((resolvedClip?.temperature ?: 0f) + (resolvedAdj?.temperature ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedTint =
      ((resolvedClip?.tint ?: 0f) + (resolvedAdj?.tint ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedShadows =
      ((resolvedClip?.shadows ?: 0f) + (resolvedAdj?.shadows ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedMidtones =
      ((resolvedClip?.midtones ?: 0f) + (resolvedAdj?.midtones ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedHighlights =
      ((resolvedClip?.highlights ?: 0f) + (resolvedAdj?.highlights ?: 0f)).coerceIn(-1.0f, 1.0f)
    val combinedVignette =
      ((resolvedClip?.vignette ?: 0f) + (resolvedAdj?.vignette ?: 0f)).coerceIn(0.0f, 1.0f)

    val combinedEffective =
      EffectiveColorGrading(
        brightness = combinedBrightness,
        contrast = combinedContrast,
        saturation = combinedSaturation,
        exposure = combinedExposure,
        temperature = combinedTemp,
        vignette = combinedVignette,
        activeLut = resolvedAdj?.activeLut ?: resolvedClip?.activeLut,
        lutStrength = resolvedAdj?.lutStrength ?: resolvedClip?.lutStrength ?: 0.0f,
        tint = combinedTint,
        shadows = combinedShadows,
        midtones = combinedMidtones,
        highlights = combinedHighlights,
        blendOpacity = finalOpacity,
      )

    return ResolvedColorPipeline(
      clipBaseStage = clipBase,
      clipLutStage = clipLut,
      adjustmentBaseStage = adjBase,
      adjustmentLutStage = adjLut,
      clipOpacity = finalOpacity,
      combinedGrade = combinedEffective,
    )
  }

  /**
   * Computes Stage 1 RGB transformations (Exposure/Brightness -> Contrast -> Temp/Tint -> Saturation -> Lift/Gamma/Gain).
   */
  fun applyBaseAdjustmentsToRgb(
    rIn: Double,
    gIn: Double,
    bIn: Double,
    stage: ColorGradeStageParams,
  ): Triple<Double, Double, Double> {
    var r = rIn.coerceIn(0.0, 1.0)
    var g = gIn.coerceIn(0.0, 1.0)
    var b = bIn.coerceIn(0.0, 1.0)

    // 1. Exposure + Brightness
    val exposureGain = 2.0.pow(stage.exposure.toDouble() * 0.55)
    val brightnessShift = stage.brightness.toDouble()
    r = (r * exposureGain + brightnessShift).coerceIn(0.0, 1.0)
    g = (g * exposureGain + brightnessShift).coerceIn(0.0, 1.0)
    b = (b * exposureGain + brightnessShift).coerceIn(0.0, 1.0)

    // 2. Contrast around neutral mid-grey pivot 0.5
    val c = stage.contrast.toDouble()
    if (abs(c - 1.0) > 1e-5) {
      r = ((r - 0.5) * c + 0.5).coerceIn(0.0, 1.0)
      g = ((g - 0.5) * c + 0.5).coerceIn(0.0, 1.0)
      b = ((b - 0.5) * c + 0.5).coerceIn(0.0, 1.0)
    }

    // 3. White balance: Temperature (Warm/Cool) & Tint (Green/Magenta)
    val tempShift = stage.temperature.toDouble() * 0.18
    val tintShift = stage.tint.toDouble() * 0.16
    val rScale = (1.0 + tempShift + tintShift * 0.5).coerceAtLeast(0.2)
    val gScale = (1.0 - tintShift).coerceAtLeast(0.2)
    val bScale = (1.0 - tempShift + tintShift * 0.5).coerceAtLeast(0.2)
    r = (r * rScale).coerceIn(0.0, 1.0)
    g = (g * gScale).coerceIn(0.0, 1.0)
    b = (b * bScale).coerceIn(0.0, 1.0)

    // 4. Saturation (Rec.709 luminance)
    val sat = stage.saturation.toDouble()
    if (abs(sat - 1.0) > 1e-5) {
      val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
      r = (luma + (r - luma) * sat).coerceIn(0.0, 1.0)
      g = (luma + (g - luma) * sat).coerceIn(0.0, 1.0)
      b = (luma + (b - luma) * sat).coerceIn(0.0, 1.0)
    }

    // 5. Tonal balance: Lift (Shadows) -> Gain (Highlights) -> Gamma (Midtones)
    return applyLiftGammaGain(
      rIn = r,
      gIn = g,
      bIn = b,
      shadows = stage.shadows.toDouble(),
      midtones = stage.midtones.toDouble(),
      highlights = stage.highlights.toDouble(),
    )
  }

  /**
   * Monotonic Lift / Gamma / Gain tonal curve (`0,0` identity when all parameters are `0.0`).
   */
  fun applyLiftGammaGain(
    rIn: Double,
    gIn: Double,
    bIn: Double,
    shadows: Double,
    midtones: Double,
    highlights: Double,
  ): Triple<Double, Double, Double> {
    if (abs(shadows) <= 1e-5 && abs(midtones) <= 1e-5 && abs(highlights) <= 1e-5) {
      return Triple(rIn.coerceIn(0.0, 1.0), gIn.coerceIn(0.0, 1.0), bIn.coerceIn(0.0, 1.0))
    }

    val lift = shadows * 0.25
    val gain = 1.0 + highlights * 0.35
    val gammaExp = 2.0.pow(-midtones * 0.65)

    fun mapChannel(x: Double): Double {
      val clamped = x.coerceIn(0.0, 1.0)
      val lifted = (clamped + lift * (1.0 - clamped)).coerceIn(0.0, 1.0)
      val gained = (lifted * gain).coerceIn(0.0, 1.0)
      return gained.pow(gammaExp).coerceIn(0.0, 1.0)
    }

    return Triple(mapChannel(rIn), mapChannel(gIn), mapChannel(bIn))
  }

  /**
   * Applies a [LutPreset] look (parametric grading + 3-way shadow/midtone/highlight split-toning)
   * mixed linearly with the input pixel according to [strength] in `[0, 1]`.
   */
  fun applyLutToRgb(
    rIn: Double,
    gIn: Double,
    bIn: Double,
    preset: LutPreset?,
    strength: Double,
  ): Triple<Double, Double, Double> {
    if (preset == null || preset.id == BuiltInLutPresets.ID_NONE || strength <= 1e-5) {
      return Triple(rIn.coerceIn(0.0, 1.0), gIn.coerceIn(0.0, 1.0), bIn.coerceIn(0.0, 1.0))
    }
    val safeStrength = strength.coerceIn(0.0, 1.0)
    val presetStage =
      ColorGradeStageParams(
        brightness = preset.brightness,
        exposure = preset.exposure,
        contrast = preset.contrast,
        saturation = preset.saturation,
        temperature = preset.temperature,
        tint = preset.tint,
        shadows = preset.shadows,
        midtones = preset.midtones,
        highlights = preset.highlights,
      )
    val (gradedR, gradedG, gradedB) = applyBaseAdjustmentsToRgb(rIn, gIn, bIn, presetStage)

    // Subtle 3-way shadow / midtone / highlight chromatic split-tone from LUT palette
    val luma = (0.2126 * gradedR + 0.7152 * gradedG + 0.0722 * gradedB).coerceIn(0.0, 1.0)
    val wShadow = (1.0 - luma) * (1.0 - luma)
    val wHighlight = luma * luma
    val wMid = (1.0 - wShadow - wHighlight).coerceAtLeast(0.0)

    fun extractTintOffset(argb: Long): Triple<Double, Double, Double> {
      val tr = ((argb shr 16) and 0xFF) / 255.0 - 0.5
      val tg = ((argb shr 8) and 0xFF) / 255.0 - 0.5
      val tb = (argb and 0xFF) / 255.0 - 0.5
      return Triple(tr, tg, tb)
    }

    val (sr, sg, sb) = extractTintOffset(preset.shadowColorArgb)
    val (mr, mg, mb) = extractTintOffset(preset.midtoneColorArgb)
    val (hr, hg, hb) = extractTintOffset(preset.highlightColorArgb)

    val splitWeight = 0.14
    val lutR = (gradedR + splitWeight * (wShadow * sr + wMid * mr + wHighlight * hr)).coerceIn(0.0, 1.0)
    val lutG = (gradedG + splitWeight * (wShadow * sg + wMid * mg + wHighlight * hg)).coerceIn(0.0, 1.0)
    val lutB = (gradedB + splitWeight * (wShadow * sb + wMid * mb + wHighlight * hb)).coerceIn(0.0, 1.0)

    return Triple(
      (rIn * (1.0 - safeStrength) + lutR * safeStrength).coerceIn(0.0, 1.0),
      (gIn * (1.0 - safeStrength) + lutG * safeStrength).coerceIn(0.0, 1.0),
      (bIn * (1.0 - safeStrength) + lutB * safeStrength).coerceIn(0.0, 1.0),
    )
  }

  /**
   * Evaluates a single `[0..1]` RGB sample deterministically through the complete ordered pipeline:
   * `BASE_ADJUSTMENTS -> LUT_APPLICATION -> OPACITY_BLEND`.
   */
  fun evaluatePixelThroughPipeline(
    rIn: Float,
    gIn: Float,
    bIn: Float,
    pipeline: ResolvedColorPipeline,
  ): FloatArray {
    // Stage 1: Clip Base Adjustments
    var (r, g, b) = applyBaseAdjustmentsToRgb(rIn.toDouble(), gIn.toDouble(), bIn.toDouble(), pipeline.clipBaseStage)

    // Stage 2: Clip LUT
    if (pipeline.clipLutStage.hasLutApplication) {
      val afterClipLut =
        applyLutToRgb(r, g, b, pipeline.clipLutStage.activeLut, pipeline.clipLutStage.lutStrength.toDouble())
      r = afterClipLut.first
      g = afterClipLut.second
      b = afterClipLut.third
    }

    // Adjustment layer (Base -> LUT, already scaled by adjustment.blendOpacity)
    pipeline.adjustmentBaseStage?.let { adjBase ->
      val afterAdjBase = applyBaseAdjustmentsToRgb(r, g, b, adjBase)
      r = afterAdjBase.first
      g = afterAdjBase.second
      b = afterAdjBase.third
    }
    pipeline.adjustmentLutStage?.let { adjLut ->
      if (adjLut.hasLutApplication) {
        val afterAdjLut = applyLutToRgb(r, g, b, adjLut.activeLut, adjLut.lutStrength.toDouble())
        r = afterAdjLut.first
        g = afterAdjLut.second
        b = afterAdjLut.third
      }
    }

    // Stage 3: Opacity / Blend
    val op = pipeline.clipOpacity.toDouble().coerceIn(0.0, 1.0)
    r = (r * op).coerceIn(0.0, 1.0)
    g = (g * op).coerceIn(0.0, 1.0)
    b = (b * op).coerceIn(0.0, 1.0)

    return floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat(), op.toFloat())
  }

  /**
   * Generates a 3D LUT cube `[R][G][B]` compatible with Media3 `SingleColorLut.createFromCube(...)`
   * for Lift/Gamma/Gain + LUT application in strict stage order.
   */
  fun buildStage3dLutCube(
    size: Int = 17,
    toneStage: ColorGradeStageParams? = null,
    lutStage: ColorGradeStageParams? = null,
  ): Array<Array<IntArray>> {
    val safeSize = size.coerceIn(4, 33)
    val denom = (safeSize - 1).toDouble()
    return Array(safeSize) { rIdx ->
      val r0 = rIdx / denom
      Array(safeSize) { gIdx ->
        val g0 = gIdx / denom
        IntArray(safeSize) { bIdx ->
          val b0 = bIdx / denom
          var r = r0
          var g = g0
          var b = b0

          if (toneStage != null && toneStage.hasToneWheelAdjustments) {
            val afterTone =
              applyLiftGammaGain(
                rIn = r,
                gIn = g,
                bIn = b,
                shadows = toneStage.shadows.toDouble(),
                midtones = toneStage.midtones.toDouble(),
                highlights = toneStage.highlights.toDouble(),
              )
            r = afterTone.first
            g = afterTone.second
            b = afterTone.third
          }

          if (lutStage != null && lutStage.hasLutApplication) {
            val afterLut = applyLutToRgb(r, g, b, lutStage.activeLut, lutStage.lutStrength.toDouble())
            r = afterLut.first
            g = afterLut.second
            b = afterLut.third
          }

          Color.rgb(
            (r * 255.0).roundToInt().coerceIn(0, 255),
            (g * 255.0).roundToInt().coerceIn(0, 255),
            (b * 255.0).roundToInt().coerceIn(0, 255),
          )
        }
      }
    }
  }
}
