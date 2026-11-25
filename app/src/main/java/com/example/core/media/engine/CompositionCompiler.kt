package com.example.core.media.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.example.core.media.audio.EnvelopeAudioProcessor
import com.example.core.media.model.MediaRenderTarget
import com.example.core.model.DuckingWindow
import com.example.core.model.MediaAsset
import com.example.core.model.SubtitleAlignment
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

sealed interface CompiledCompositionResult {
  data class Ready(
    val composition: Composition,
    val totalVideoItems: Int,
    val totalAudioItems: Int,
    val usedProxyCount: Int,
    val appliedVideoEffectCount: Int = 0,
    val renderPlan: TimelineRenderPlan? = null,
  ) : CompiledCompositionResult

  data class EmptyOrMissingMedia(val reason: String) : CompiledCompositionResult
}

/**
 * Translates our portable [TimelineState] and [MediaAsset] registry into a Media3 [Composition]
 * backed by an explicit [TimelineRenderPlan].
 *
 * Includes:
 * - Pre-encode [TimelineRenderPlan] resolution with overlapping-clip trimming, source EOF/speed
 *   clamping, micro-segment coalescing, and automatic gap compensation for strict A/V sync.
 * - Multi-layer / partial-overlap [ClipKind.ADJUSTMENT] splitting and deterministic grading merge.
 * - Styled timeline [ClipKind.SUBTITLE] rendering via Media3 [OverlayEffect].
 * - Mute/Solo resolution, track gain, pan, smooth fades, and ducking via [EnvelopeAudioProcessor].
 */
@OptIn(UnstableApi::class)
class CompositionCompiler {

  private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
    private val safeSpeed =
      speed.coerceIn(TimelineRenderPlanner.MIN_SPEED, TimelineRenderPlanner.MAX_SPEED)

    override fun getSpeed(timeUs: Long): Float = safeSpeed

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
  }

  /**
   * Renders active [PlannedSubtitleOverlay] items onto a cached frame-sized transparent bitmap
   * without allocating per frame when the active subtitle set hasn't changed.
   */
  private class TimelineSubtitleBitmapOverlay(
    private val subtitles: List<PlannedSubtitleOverlay>,
    private val frameWidth: Int,
    private val frameHeight: Int,
  ) : BitmapOverlay() {

    private val emptyBitmap: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private var cachedKey: String? = null
    private var cachedBitmap: Bitmap = emptyBitmap

    private val hiddenSettings =
      StaticOverlaySettings.Builder()
        .setAlphaScale(0f)
        .build()

    private val visibleSettings =
      StaticOverlaySettings.Builder()
        .setAlphaScale(1f)
        .build()

    private fun activeSubtitlesAt(presentationTimeUs: Long): List<PlannedSubtitleOverlay> {
      val posMs = (presentationTimeUs.coerceAtLeast(0L) / 1_000L)
      return subtitles.filter { posMs in it.timelineStartMs until it.timelineEndMs }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
      return if (activeSubtitlesAt(presentationTimeUs).isEmpty()) hiddenSettings else visibleSettings
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
      val active = activeSubtitlesAt(presentationTimeUs)
      if (active.isEmpty()) return emptyBitmap

      val key = active.joinToString("|") { "${it.clipId}:${it.config.text}:${it.config.alignment}:${it.config.fontSizeSp}" }
      if (key == cachedKey) return cachedBitmap

      val rendered = renderSubtitlesBitmap(active)
      if (cachedBitmap !== emptyBitmap && !cachedBitmap.isRecycled) {
        cachedBitmap.recycle()
      }
      cachedBitmap = rendered
      cachedKey = key
      return rendered
    }

    private fun renderSubtitlesBitmap(active: List<PlannedSubtitleOverlay>): Bitmap {
      val w = frameWidth.coerceIn(320, 1920)
      val h = frameHeight.coerceIn(240, 1080)
      val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      val scaleFactor = h / 720f

      for (sub in active) {
        val cfg = sub.config
        val textPaint =
          TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColorOrDefault(cfg.textColorHex, Color.WHITE)
            textSize = (cfg.fontSizeSp.coerceIn(12f, 64f) * 1.45f * scaleFactor).coerceAtLeast(18f)
            typeface = resolveTypeface(cfg.fontFamily)
            setShadowLayer(4f * scaleFactor, 0f, 2f * scaleFactor, Color.argb(180, 0, 0, 0))
          }

        val maxTextWidth = (w * 0.84f).roundToInt().coerceAtLeast(160)
        val layout =
          StaticLayout.Builder.obtain(cfg.text, 0, cfg.text.length, textPaint, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

        val textHeight = layout.height.toFloat()
        val maxLineWidth =
          (0 until layout.lineCount)
            .maxOfOrNull { layout.getLineWidth(it) }
            ?.coerceAtLeast(64f)
            ?: maxTextWidth.toFloat()

        val padH = 22f * scaleFactor
        val padV = 12f * scaleFactor
        val boxWidth = (maxLineWidth + padH * 2f).coerceAtMost(w * 0.92f)
        val boxHeight = textHeight + padV * 2f
        val boxLeft = (w - boxWidth) / 2f
        val marginV = h * 0.08f
        val boxTop =
          when (cfg.alignment) {
            SubtitleAlignment.TOP_CENTER -> marginV
            SubtitleAlignment.MIDDLE_CENTER -> (h - boxHeight) / 2f
            SubtitleAlignment.BOTTOM_CENTER -> h - marginV - boxHeight
          }

        val bgColor = parseColorOrDefault(cfg.backgroundColorHex, Color.argb(140, 0, 0, 0))
        if (Color.alpha(bgColor) > 0) {
          val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
          val rect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
          val radius = 12f * scaleFactor
          canvas.drawRoundRect(rect, radius, radius, bgPaint)
        }

        canvas.save()
        val textLeft = (w - maxTextWidth) / 2f
        canvas.translate(textLeft, boxTop + padV)
        layout.draw(canvas)
        canvas.restore()
      }

      return bitmap
    }

    private fun parseColorOrDefault(hex: String, fallback: Int): Int =
      runCatching { Color.parseColor(hex) }.getOrDefault(fallback)

    private fun resolveTypeface(family: String): Typeface =
      when {
        family.contains("medium", ignoreCase = true) || family.contains("bold", ignoreCase = true) ->
          Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        family.contains("condensed", ignoreCase = true) ->
          Typeface.create("sans-serif-condensed", Typeface.BOLD)
        family.contains("serif", ignoreCase = true) && !family.contains("sans", ignoreCase = true) ->
          Typeface.SERIF
        family.contains("mono", ignoreCase = true) -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
      }
  }

  fun plan(
    timeline: TimelineState,
    assets: List<MediaAsset>,
    projectRootDir: File,
    renderTarget: MediaRenderTarget,
  ): TimelineRenderPlan {
    val useProxies = renderTarget is MediaRenderTarget.Preview && renderTarget.useProxies
    return TimelineRenderPlanner.buildPlan(
      timeline = timeline,
      assets = assets,
      projectRootDir = projectRootDir,
      useProxies = useProxies,
    )
  }

  fun compile(
    timeline: TimelineState,
    assets: List<MediaAsset>,
    projectRootDir: File,
    renderTarget: MediaRenderTarget,
  ): CompiledCompositionResult {
    val renderPlan = plan(timeline, assets, projectRootDir, renderTarget)
    if (renderPlan.isEmpty) {
      return CompiledCompositionResult.EmptyOrMissingMedia(
        "No hay clips multimedia locales en línea para procesar en este rango.",
      )
    }

    var appliedVideoEffectCount = 0

    // 1. Primary video track sequence built directly from validated segments & compensated gaps
    val sequences = mutableListOf<EditedMediaItemSequence>()
    if (renderPlan.primaryVideoSegments.isNotEmpty()) {
      val videoSeqBuilder =
        EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO))
      for (segment in renderPlan.primaryVideoSegments) {
        if (segment.leadingGapMs > 0L) {
          videoSeqBuilder.addGap(segment.leadingGapMs * 1_000L)
        }
        val (item, effectCount) = buildVideoEditedItem(segment, renderTarget)
        appliedVideoEffectCount += effectCount
        videoSeqBuilder.addItem(item)
      }
      sequences.add(videoSeqBuilder.build())
    }

    // 2. Auxiliary audio track sequences (one gap-aligned sequence per audible audio track)
    for (audioTrack in renderPlan.audioTracks) {
      val audioSeqBuilder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
      for (segment in audioTrack.segments) {
        if (segment.leadingGapMs > 0L) {
          audioSeqBuilder.addGap(segment.leadingGapMs * 1_000L)
        }
        audioSeqBuilder.addItem(buildAudioEditedItem(segment))
      }
      sequences.add(audioSeqBuilder.build())
    }

    val compositionEffects = buildCompositionWideEffects(renderTarget, renderPlan.subtitleOverlays)
    val composition =
      Composition.Builder(sequences)
        .setEffects(compositionEffects)
        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        .build()

    return CompiledCompositionResult.Ready(
      composition = composition,
      totalVideoItems = renderPlan.primaryVideoSegments.size,
      totalAudioItems = renderPlan.totalAudioSegmentCount,
      usedProxyCount = renderPlan.usedProxyCount,
      appliedVideoEffectCount = appliedVideoEffectCount,
      renderPlan = renderPlan,
    )
  }

  private fun buildVideoEditedItem(
    segment: PlannedVideoSegment,
    renderTarget: MediaRenderTarget,
  ): Pair<EditedMediaItem, Int> {
    val clip = segment.clip
    val sourceUri = toAndroidUri(segment.mediaSource)

    val mediaItemBuilder = MediaItem.Builder().setUri(sourceUri)
    if (segment.isImage) {
      mediaItemBuilder.setImageDurationMs(
        segment.expectedRenderedDurationMs.coerceAtLeast(TimelineRenderPlanner.MIN_RENDER_SEGMENT_MS),
      )
    } else {
      mediaItemBuilder.setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(segment.sourceInMs)
          .setEndPositionMs(segment.sourceOutMs)
          .build(),
      )
    }
    val mediaItem = mediaItemBuilder.build()

    val videoEffects =
      KeyframeMedia3Effects.buildEffectsForClip(clip, segment.effectiveAdjustment).let { baseEffects ->
        if (renderTarget is MediaRenderTarget.Preview) {
          baseEffects + Presentation.createForHeight(renderTarget.profile.targetHeight)
        } else {
          baseEffects
        }
      }
    val effectCount = videoEffects.size

    val clipBaseVolume = (clip.videoConfig?.volume ?: 1f).coerceIn(0f, 2f)
    val volumeKeyframes = clip.videoConfig?.keyframes.orEmpty()
    val hasAudibleKeyframeVolume =
      volumeKeyframes.any { ((it.volume ?: clipBaseVolume) * segment.trackVolume) > 0.001f }
    val effectiveStaticVolume = (clipBaseVolume * segment.trackVolume).coerceIn(0f, 2f)
    val fadeInMs = (clip.videoConfig?.fadeInMs ?: 0L).coerceIn(0L, clip.durationMs)
    val fadeOutMs = (clip.videoConfig?.fadeOutMs ?: 0L).coerceIn(0L, clip.durationMs)
    val audioProcessors =
      buildEnvelopeProcessors(
        clip = clip,
        baseVolume = clipBaseVolume,
        trackVolume = segment.trackVolume,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        pan = 0.0f,
        duckingWindows = segment.duckingWindows,
      )

    val editedBuilder =
      EditedMediaItem.Builder(mediaItem)
        .setRemoveAudio(
          segment.isImage ||
            !segment.isTrackAudible ||
            segment.trackVolume <= 0.001f ||
            (effectiveStaticVolume <= 0.001f && !hasAudibleKeyframeVolume),
        )
        .setEffects(Effects(audioProcessors, videoEffects))

    if (segment.isImage) {
      editedBuilder.setFrameRate(30)
    } else if (abs(segment.effectiveSpeed - 1.0f) > 0.005f) {
      editedBuilder.setSpeed(ConstantSpeedProvider(segment.effectiveSpeed))
    }

    return editedBuilder.build() to effectCount
  }

  private fun buildAudioEditedItem(segment: PlannedAudioSegment): EditedMediaItem {
    val clip = segment.clip
    val sourceUri = toAndroidUri(segment.mediaSource)
    val mediaItem =
      MediaItem.Builder()
        .setUri(sourceUri)
        .setClippingConfiguration(
          MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(segment.sourceInMs)
            .setEndPositionMs(segment.sourceOutMs)
            .build(),
        )
        .build()

    val clipBaseVolume = (clip.audioConfig?.volume ?: 1f).coerceIn(0f, 2f)
    val pan = (clip.audioConfig?.pan ?: 0f).coerceIn(-1f, 1f)
    val fadeInMs = (clip.audioConfig?.fadeInMs ?: 0L).coerceIn(0L, clip.durationMs)
    val fadeOutMs = (clip.audioConfig?.fadeOutMs ?: 0L).coerceIn(0L, clip.durationMs)
    val audioProcessors =
      buildEnvelopeProcessors(
        clip = clip,
        baseVolume = clipBaseVolume,
        trackVolume = segment.trackVolume,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        pan = pan,
        duckingWindows = segment.duckingWindows,
      )

    val builder =
      EditedMediaItem.Builder(mediaItem)
        .setRemoveVideo(true)
        .setEffects(Effects(audioProcessors, emptyList()))

    if (abs(segment.effectiveSpeed - 1.0f) > 0.005f) {
      builder.setSpeed(ConstantSpeedProvider(segment.effectiveSpeed))
    }

    return builder.build()
  }

  private fun buildCompositionWideEffects(
    renderTarget: MediaRenderTarget,
    subtitleOverlays: List<PlannedSubtitleOverlay>,
  ): Effects {
    val (targetWidth, targetHeight) =
      when (renderTarget) {
        is MediaRenderTarget.Preview -> {
          val h = renderTarget.profile.targetHeight.coerceAtLeast(240)
          ((h * 16f / 9f).roundToInt()) to h
        }
        is MediaRenderTarget.Export ->
          renderTarget.config.width to renderTarget.config.height
      }

    val presentation =
      when (renderTarget) {
        is MediaRenderTarget.Preview ->
          Presentation.createForHeight(renderTarget.profile.targetHeight)
        is MediaRenderTarget.Export ->
          Presentation.createForWidthAndHeight(
            renderTarget.config.width,
            renderTarget.config.height,
            Presentation.LAYOUT_SCALE_TO_FIT,
          )
      }

    val effects = mutableListOf<Effect>(presentation)
    if (subtitleOverlays.isNotEmpty()) {
      effects.add(
        OverlayEffect(
          listOf(
            TimelineSubtitleBitmapOverlay(
              subtitles = subtitleOverlays,
              frameWidth = targetWidth,
              frameHeight = targetHeight,
            ),
          ),
        ),
      )
    }

    return Effects(emptyList(), effects)
  }

  private fun buildEnvelopeProcessors(
    clip: TimelineClip,
    baseVolume: Float,
    trackVolume: Float,
    fadeInMs: Long,
    fadeOutMs: Long,
    pan: Float,
    duckingWindows: List<DuckingWindow>,
  ): List<AudioProcessor> {
    val volumeKeyframes = clip.keyframes()
    val hasVolumeKeyframes = volumeKeyframes.any { it.volume != null }
    val combinedVolume = (baseVolume * trackVolume).coerceIn(0f, 2f)
    val needsProcessing =
      combinedVolume !in 0.99f..1.01f ||
        abs(pan) > 0.005f ||
        fadeInMs > 0L ||
        fadeOutMs > 0L ||
        duckingWindows.isNotEmpty() ||
        hasVolumeKeyframes
    if (!needsProcessing) return emptyList()

    return listOf(
      EnvelopeAudioProcessor(
        baseVolume = baseVolume,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        clipStartOnTimelineMs = clip.timelineStartMs,
        clipDurationMs = clip.durationMs,
        duckingWindows = duckingWindows,
        volumeKeyframes = volumeKeyframes,
        trackVolume = trackVolume,
        clipSpeed = clip.speed,
        pan = pan,
      ),
    )
  }

  private fun toAndroidUri(source: PlannedMediaSource): Uri =
    when {
      source.sourceFile != null -> Uri.fromFile(source.sourceFile)
      !source.externalUri.isNullOrBlank() -> Uri.parse(source.externalUri)
      else -> Uri.EMPTY
    }
}

