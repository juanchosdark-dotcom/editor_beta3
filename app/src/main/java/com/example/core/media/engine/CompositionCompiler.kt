package com.example.core.media.engine

import android.graphics.Matrix
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.example.core.media.audio.AudioTrackMixer
import com.example.core.media.audio.EnvelopeAudioProcessor
import com.example.core.media.model.MediaRenderTarget
import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.BuiltInLutPresets
import com.example.core.model.ClipKind
import com.example.core.model.DuckingWindow
import com.example.core.model.MediaAsset
import com.example.core.model.MediaType
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.TrackType
import com.example.core.model.VideoClipConfig
import java.io.File
import kotlin.math.abs

sealed interface CompiledCompositionResult {
  data class Ready(
    val composition: Composition,
    val totalVideoItems: Int,
    val totalAudioItems: Int,
    val usedProxyCount: Int,
    val appliedVideoEffectCount: Int = 0,
  ) : CompiledCompositionResult

  data class EmptyOrMissingMedia(val reason: String) : CompiledCompositionResult
}

/**
 * Translates our portable [TimelineState] and [MediaAsset] registry into a Media3 [Composition].
 *
 * Includes:
 * - Source trimming via [MediaItem.ClippingConfiguration] (`sourceInMs..sourceOutMs`).
 * - Timeline gap insertion (`EditedMediaItemSequence.Builder.addGap`) on both Video V1 and Audio A1
 *   to preserve multitrack A/V synchronization across trims, gaps, and multi-cuts.
 * - Speed synchronization via [SpeedProvider] when `clip.speed != 1.0f`.
 * - Static & keyframe-animated scale, position (`offsetX`/`offsetY`), rotation, and opacity.
 * - Overlapping [ClipKind.ADJUSTMENT] color grading (brightness, contrast, saturation, temperature).
 * - Separate Preview quality / proxy usage vs. full-resolution Export with HDR->SDR tone mapping.
 * - Mute/Solo resolution, track gain, pan, smooth fades, and ducking via [EnvelopeAudioProcessor].
 */
@OptIn(UnstableApi::class)
class CompositionCompiler {

  private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
    private val safeSpeed = speed.coerceIn(0.25f, 4.0f)

    override fun getSpeed(timeUs: Long): Float = safeSpeed

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
  }

  fun compile(
    timeline: TimelineState,
    assets: List<MediaAsset>,
    projectRootDir: File,
    renderTarget: MediaRenderTarget,
  ): CompiledCompositionResult {
    val assetById = assets.associateBy { it.assetId }
    val trackById = timeline.tracks.associateBy { it.trackId }
    val trackAudibility = AudioTrackMixer.resolveTrackAudibility(timeline.tracks)
    val duckingWindows = AudioTrackMixer.computeDuckingWindows(timeline)

    val activeAdjustmentClips =
      timeline.clips.filter { clip ->
        clip.isEnabled &&
          clip.kind == ClipKind.ADJUSTMENT &&
          trackById[clip.trackId]?.isVisible != false
      }

    var usedProxyCount = 0
    var appliedVideoEffectCount = 0

    // 1. Primary video track sequence (gap-aligned to timeline 0ms for strict A/V sync)
    val videoTracks =
      timeline.tracksSorted().filter { it.type == TrackType.VIDEO && it.isVisible }
    val primaryVideoClips =
      videoTracks.firstOrNull()?.let { track ->
        timeline.clipsForTrack(track.trackId).filter { it.isEnabled && it.kind == ClipKind.VIDEO }
      }.orEmpty()

    val videoEntries =
      primaryVideoClips.mapNotNull { clip ->
        val asset = clip.assetId?.let { assetById[it] } ?: return@mapNotNull null
        val resolved =
          resolveSourceUri(asset, projectRootDir, renderTarget) ?: return@mapNotNull null
        if (resolved.isProxy) usedProxyCount++

        val adjustmentOverlap = findOverlappingAdjustment(clip, activeAdjustmentClips)
        val isTrackAudible = trackAudibility[clip.trackId] == true
        val trackVolume = trackById[clip.trackId]?.volume?.coerceIn(0f, 2f) ?: 1f
        val clipDuckingWindows =
          if (timeline.duckingConfig.targetTrackId == clip.trackId) {
            AudioTrackMixer.duckingWindowsForClip(clip, duckingWindows)
          } else {
            emptyList()
          }

        val (item, effectCount) =
          buildVideoEditedItem(
            clip = clip,
            asset = asset,
            sourceUri = resolved.uri,
            isTrackAudible = isTrackAudible,
            trackVolume = trackVolume,
            duckingWindows = clipDuckingWindows,
            adjustment = adjustmentOverlap,
            renderTarget = renderTarget,
          )
        appliedVideoEffectCount += effectCount
        clip to item
      }

    // 2. Auxiliary audio tracks (one gap-aligned sequence per audible track, preserving exact timeline offsets)
    val audioTracks =
      timeline.tracksSorted().filter { it.type == TrackType.AUDIO && trackAudibility[it.trackId] == true }

    val audioSequences = mutableListOf<EditedMediaItemSequence>()
    var totalAudioItems = 0

    for (track in audioTracks) {
      val trackClips =
        timeline.clipsForTrack(track.trackId).filter { it.isEnabled && it.kind == ClipKind.AUDIO }
      val trackVolume = track.volume.coerceIn(0f, 2f)
      val trackEntries =
        trackClips.mapNotNull { clip ->
          val asset = clip.assetId?.let { assetById[it] } ?: return@mapNotNull null
          val resolved =
            resolveSourceUri(asset, projectRootDir, renderTarget) ?: return@mapNotNull null
          val clipDuckingWindows =
            if (timeline.duckingConfig.targetTrackId == clip.trackId) {
              AudioTrackMixer.duckingWindowsForClip(clip, duckingWindows)
            } else {
              emptyList()
            }
          clip to buildAudioEditedItem(clip, resolved.uri, trackVolume, clipDuckingWindows)
        }
      if (trackEntries.isNotEmpty()) {
        totalAudioItems += trackEntries.size
        audioSequences +=
          buildGapAlignedSequence(
            trackTypes = setOf(C.TRACK_TYPE_AUDIO),
            entries = trackEntries,
          )
      }
    }

    if (videoEntries.isEmpty() && audioSequences.isEmpty()) {
      return CompiledCompositionResult.EmptyOrMissingMedia(
        "No hay clips multimedia locales en línea para procesar en este rango.",
      )
    }

    val sequences = mutableListOf<EditedMediaItemSequence>()
    if (videoEntries.isNotEmpty()) {
      sequences.add(
        buildGapAlignedSequence(
          trackTypes = setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO),
          entries = videoEntries,
        ),
      )
    }
    sequences.addAll(audioSequences)

    val compositionEffects = buildCompositionWideEffects(renderTarget)
    val composition =
      Composition.Builder(sequences)
        .setEffects(compositionEffects)
        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        .build()

    return CompiledCompositionResult.Ready(
      composition = composition,
      totalVideoItems = videoEntries.size,
      totalAudioItems = totalAudioItems,
      usedProxyCount = usedProxyCount,
      appliedVideoEffectCount = appliedVideoEffectCount,
    )
  }

  private fun buildGapAlignedSequence(
    trackTypes: Set<Int>,
    entries: List<Pair<TimelineClip, EditedMediaItem>>,
  ): EditedMediaItemSequence {
    val builder = EditedMediaItemSequence.Builder(trackTypes)
    var cursorMs = 0L
    for ((clip, item) in entries.sortedBy { it.first.timelineStartMs }) {
      val gapMs = (clip.timelineStartMs - cursorMs).coerceAtLeast(0L)
      if (gapMs > 0L) {
        builder.addGap(gapMs * 1_000L)
      }
      builder.addItem(item)
      cursorMs = maxOf(cursorMs, clip.timelineEndMs)
    }
    return builder.build()
  }

  private fun buildVideoEditedItem(
    clip: TimelineClip,
    asset: MediaAsset,
    sourceUri: Uri,
    isTrackAudible: Boolean,
    trackVolume: Float,
    duckingWindows: List<DuckingWindow>,
    adjustment: AdjustmentClipConfig?,
    renderTarget: MediaRenderTarget,
  ): Pair<EditedMediaItem, Int> {
    val startMs = clip.sourceInMs.coerceAtLeast(0L)
    val endMs = clip.sourceOutMs.coerceAtLeast(startMs + 1L)

    val mediaItemBuilder = MediaItem.Builder().setUri(sourceUri)
    if (asset.type == MediaType.IMAGE) {
      mediaItemBuilder.setImageDurationMs(clip.durationMs.coerceAtLeast(1_000L))
    } else {
      mediaItemBuilder.setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(startMs)
          .setEndPositionMs(endMs)
          .build(),
      )
    }
    val mediaItem = mediaItemBuilder.build()

    val videoEffects =
      KeyframeMedia3Effects.buildEffectsForClip(clip, adjustment).let { baseEffects ->
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
      volumeKeyframes.any { ((it.volume ?: clipBaseVolume) * trackVolume) > 0.001f }
    val effectiveStaticVolume = (clipBaseVolume * trackVolume).coerceIn(0f, 2f)
    val fadeInMs = (clip.videoConfig?.fadeInMs ?: 0L).coerceIn(0L, clip.durationMs)
    val fadeOutMs = (clip.videoConfig?.fadeOutMs ?: 0L).coerceIn(0L, clip.durationMs)
    val audioProcessors =
      buildEnvelopeProcessors(
        clip = clip,
        baseVolume = clipBaseVolume,
        trackVolume = trackVolume,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        pan = 0.0f,
        duckingWindows = duckingWindows,
      )

    val editedBuilder =
      EditedMediaItem.Builder(mediaItem)
        .setRemoveAudio(
          asset.type == MediaType.IMAGE ||
            !isTrackAudible ||
            trackVolume <= 0.001f ||
            (effectiveStaticVolume <= 0.001f && !hasAudibleKeyframeVolume),
        )
        .setEffects(Effects(audioProcessors, videoEffects))

    if (asset.type == MediaType.IMAGE) {
      editedBuilder.setFrameRate(30)
    } else if (abs(clip.speed - 1.0f) > 0.005f) {
      editedBuilder.setSpeed(ConstantSpeedProvider(clip.speed))
    }

    return editedBuilder.build() to effectCount
  }

  private fun buildAudioEditedItem(
    clip: TimelineClip,
    sourceUri: Uri,
    trackVolume: Float,
    duckingWindows: List<DuckingWindow>,
  ): EditedMediaItem {
    val startMs = clip.sourceInMs.coerceAtLeast(0L)
    val endMs = clip.sourceOutMs.coerceAtLeast(startMs + 1L)
    val mediaItem =
      MediaItem.Builder()
        .setUri(sourceUri)
        .setClippingConfiguration(
          MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
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
        trackVolume = trackVolume,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        pan = pan,
        duckingWindows = duckingWindows,
      )

    val builder =
      EditedMediaItem.Builder(mediaItem)
        .setRemoveVideo(true)
        .setEffects(Effects(audioProcessors, emptyList()))

    if (abs(clip.speed - 1.0f) > 0.005f) {
      builder.setSpeed(ConstantSpeedProvider(clip.speed))
    }

    return builder.build()
  }

  private fun buildCompositionWideEffects(renderTarget: MediaRenderTarget): Effects {
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
    return Effects(emptyList(), listOf(presentation))
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

  private fun findOverlappingAdjustment(
    videoClip: TimelineClip,
    adjustments: List<TimelineClip>,
  ): AdjustmentClipConfig? {
    return adjustments
      .firstOrNull { adj ->
        adj.timelineStartMs < videoClip.timelineEndMs && adj.timelineEndMs > videoClip.timelineStartMs
      }
      ?.adjustmentConfig
  }

  private data class ResolvedMediaSource(val uri: Uri, val isProxy: Boolean)

  private fun resolveSourceUri(
    asset: MediaAsset,
    projectRootDir: File,
    renderTarget: MediaRenderTarget,
  ): ResolvedMediaSource? {
    if (renderTarget is MediaRenderTarget.Preview && renderTarget.useProxies) {
      val proxyRel = asset.proxyRelativePath
      if (!proxyRel.isNullOrBlank()) {
        val proxyFile = File(projectRootDir, proxyRel)
        if (proxyFile.exists() && proxyFile.length() > 0L) {
          return ResolvedMediaSource(Uri.fromFile(proxyFile), isProxy = true)
        }
      }
    }

    val relativeFile = File(projectRootDir, asset.relativePath)
    if (relativeFile.exists() && relativeFile.length() > 0L) {
      return ResolvedMediaSource(Uri.fromFile(relativeFile), isProxy = false)
    }

    val externalUri = asset.externalSourceUri
    if (!externalUri.isNullOrBlank()) {
      return ResolvedMediaSource(Uri.parse(externalUri), isProxy = false)
    }

    return null
  }
}
