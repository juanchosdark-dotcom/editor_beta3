package com.example.core.media.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import com.example.core.ai.api.DeviceMemoryTier
import com.example.core.ai.api.DeviceProfile
import com.example.core.media.audio.AudioFadeCurve
import com.example.core.media.audio.AudioTrackMixer
import com.example.core.media.audio.DynamicPreviewEnvelopeAudioProcessor
import com.example.core.media.audio.PreviewAudioSegmentContext
import com.example.core.media.engine.KeyframeMedia3Effects
import com.example.core.media.model.MediaUiError
import com.example.core.media.model.MediaUiErrorKind
import com.example.core.media.model.PlaybackDiagnosticNotice
import com.example.core.media.model.RenderSettings
import com.example.core.model.ClipKind
import com.example.core.model.DuckingWindow
import com.example.core.model.EvaluatedClipProperties
import com.example.core.model.MediaAsset
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.TrackType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

data class PreviewPlayerState(
  val isPlaying: Boolean = false,
  val isScrubbing: Boolean = false,
  val isLoopEnabled: Boolean = false,
  val hasPlayableMedia: Boolean = false,
  val activeProxyCount: Int = 0,
  val videoRotationDegrees: Int = 0,
  val videoWidth: Int = 0,
  val videoHeight: Int = 0,
  val statusNote: String? = null,
  val diagnosticNotice: PlaybackDiagnosticNotice? = null,
  val uiError: MediaUiError? = null,
) {
  val isPortraitVideo: Boolean
    get() = videoHeight > videoWidth && videoWidth > 0
}

internal data class TimelinePlaybackSegment(
  val clipId: String,
  val windowIndex: Int,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
  val sourceInMs: Long,
  val speed: Float,
  val isHdrOrHighResProtected: Boolean,
  val clip: TimelineClip,
  val audioContext: PreviewAudioSegmentContext,
  val cumulativeStreamStartUs: Long = 0L,
)

internal data class AudioPlaybackSegment(
  val clipId: String,
  val trackId: String,
  val windowIndex: Int,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
  val sourceInMs: Long,
  val speed: Float,
  val clip: TimelineClip,
  val audioContext: PreviewAudioSegmentContext,
)

private data class TimelineGapWindow(
  val windowIndex: Int,
  val timelineStartMs: Long,
  val timelineEndMs: Long,
)

/**
 * Stability-hardened Media3 [ExoPlayer] controller for multitrack timeline preview.
 *
 * - Plays primary Video V1 + synchronized auxiliary Audio A1 using gap-aligned [MediaSource]
 *   timelines (`SilenceMediaSource` for leading & inter-clip gaps) so A/V tracks stay locked.
 * - Shares [AudioTrackMixer.computeClipEnvelopeGainAt] via [DynamicPreviewEnvelopeAudioProcessor]
 *   across preview and export (track volume, Mute/Solo, keyframed volume, pan, smooth S-curve
 *   fades, and ducking).
 * - Binds to [Lifecycle] via [LifecycleEventObserver] to pause on `ON_PAUSE` and release on `ON_DESTROY`.
 */
@OptIn(UnstableApi::class)
class PreviewPlayerController(
  private val context: Context,
) {

  private var exoPlayer: ExoPlayer? = null
  private var auxAudioPlayer: ExoPlayer? = null
  private var currentSegments: List<TimelinePlaybackSegment> = emptyList()
  private var currentVideoGaps: List<TimelineGapWindow> = emptyList()
  private var currentAudioSegments: List<AudioPlaybackSegment> = emptyList()
  private var currentAudioGaps: List<TimelineGapWindow> = emptyList()
  private var auxTotalTimelineMs: Long = 0L

  @Volatile
  private var activeSegmentSnapshot: TimelinePlaybackSegment? = null
  private val previewAudioProcessor = DynamicPreviewEnvelopeAudioProcessor()
  private val auxAudioProcessor = DynamicPreviewEnvelopeAudioProcessor()
  private var lastProfileTier: DeviceMemoryTier = DeviceMemoryTier.BALANCED
  private var boundLifecycle: Lifecycle? = null
  private var lifecycleObserver: LifecycleEventObserver? = null
  private var lastCompiledParams: LastCompileRequest? = null
  private var resumePositionMs: Long = 0L
  private var loopEnabledPreference: Boolean = false
  private var effectsDisabledFallback: Boolean = false
  private var lastScrubDispatchMs: Long = Long.MIN_VALUE
  private var lastScrubWallClockMs: Long = 0L

  private data class LastCompileRequest(
    val timeline: TimelineState,
    val assets: List<MediaAsset>,
    val projectRootDir: File,
    val renderSettings: RenderSettings,
    val deviceProfile: DeviceProfile,
  )

  private val _state = MutableStateFlow(PreviewPlayerState())
  val state: StateFlow<PreviewPlayerState> = _state.asStateFlow()

  fun getOrCreatePlayer(profile: DeviceProfile = DeviceProfile()): ExoPlayer {
    val existing = exoPlayer
    if (existing != null) return existing

    val loadControl = buildLoadControlForProfile(profile)
    val renderersFactory =
      object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
          context: Context,
          enableFloatOutput: Boolean,
          enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink =
          DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(previewAudioProcessor))
            .build()
      }.setEnableDecoderFallback(true)

    val audioAttributes =
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    return ExoPlayer.Builder(context, renderersFactory)
      .setLoadControl(loadControl)
      .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
      .setHandleAudioBecomingNoisy(true)
      .build()
      .also { player ->
        lastProfileTier = profile.tier
        player.setSeekParameters(SeekParameters.DEFAULT)
        player.repeatMode =
          if (loopEnabledPreference) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        player.addListener(
          object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
              if (currentSegments.isNotEmpty()) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
                if (!isPlaying) {
                  runCatching { auxAudioPlayer?.pause() }
                }
              }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
              val windowIdx = player.currentMediaItemIndex
              val seg = currentSegments.firstOrNull { it.windowIndex == windowIdx }
              if (seg != null) {
                activeSegmentSnapshot = seg
                val safeSpeed = seg.speed.coerceIn(0.25f, 4.0f)
                val offsetInClip =
                  (player.currentPosition.coerceAtLeast(0L) / safeSpeed)
                    .roundToLong()
                    .coerceIn(0L, seg.clip.durationMs)
                previewAudioProcessor.updateContext(seg.audioContext.copy(anchorClipOffsetMs = offsetInClip))
                runCatching { player.setPlaybackParameters(PlaybackParameters(safeSpeed)) }
              } else {
                activeSegmentSnapshot = null
                previewAudioProcessor.updateContext(null)
                runCatching { player.setPlaybackParameters(PlaybackParameters.DEFAULT) }
              }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
              if (videoSize.width > 0 && videoSize.height > 0) {
                val unappliedRot =
                  if (videoSize.unappliedRotationDegrees != 0) {
                    ((videoSize.unappliedRotationDegrees % 360) + 360) % 360
                  } else {
                    _state.value.videoRotationDegrees
                  }
                _state.value =
                  _state.value.copy(
                    videoWidth = videoSize.width,
                    videoHeight = videoSize.height,
                    videoRotationDegrees = unappliedRot,
                    uiError = null,
                  )
              }
            }

            override fun onPlayerError(error: PlaybackException) {
              handlePlaybackException(error)
            }
          },
        )
        exoPlayer = player
      }
  }

  private fun getOrCreateAuxAudioPlayer(profile: DeviceProfile = DeviceProfile()): ExoPlayer {
    val existing = auxAudioPlayer
    if (existing != null) return existing

    val loadControl = buildLoadControlForProfile(profile)
    val renderersFactory =
      object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
          context: Context,
          enableFloatOutput: Boolean,
          enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink =
          DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(auxAudioProcessor))
            .build()
      }.setEnableDecoderFallback(true)

    val audioAttributes =
      AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    return ExoPlayer.Builder(context, renderersFactory)
      .setLoadControl(loadControl)
      .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
      .build()
      .also { player ->
        player.setSeekParameters(SeekParameters.DEFAULT)
        player.repeatMode =
          if (loopEnabledPreference) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        player.addListener(
          object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
              if (currentSegments.isEmpty() && currentAudioSegments.isNotEmpty()) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
              }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
              val windowIdx = player.currentMediaItemIndex
              val seg = currentAudioSegments.firstOrNull { it.windowIndex == windowIdx }
              if (seg != null) {
                val safeSpeed = seg.speed.coerceIn(0.25f, 4.0f)
                val offsetInClip =
                  (player.currentPosition.coerceAtLeast(0L) / safeSpeed)
                    .roundToLong()
                    .coerceIn(0L, seg.clip.durationMs)
                auxAudioProcessor.updateContext(seg.audioContext.copy(anchorClipOffsetMs = offsetInClip))
                runCatching { player.setPlaybackParameters(PlaybackParameters(safeSpeed)) }
              } else {
                auxAudioProcessor.updateContext(null)
                runCatching { player.setPlaybackParameters(PlaybackParameters.DEFAULT) }
              }
            }
          },
        )
        auxAudioPlayer = player
      }
  }

  /**
   * Attaches a [LifecycleEventObserver] that pauses audio/video immediately on `ON_PAUSE`
   * and frees the hardware decoder on `ON_DESTROY`.
   */
  fun createLifecycleObserver(): LifecycleEventObserver =
    LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE,
        Lifecycle.Event.ON_STOP, -> pausePlayback()
        Lifecycle.Event.ON_DESTROY -> release()
        else -> Unit
      }
    }

  fun attachToLifecycle(lifecycle: Lifecycle) {
    if (boundLifecycle === lifecycle && lifecycleObserver != null) return
    detachLifecycle()
    val observer = createLifecycleObserver()
    lifecycle.addObserver(observer)
    boundLifecycle = lifecycle
    lifecycleObserver = observer
  }

  fun detachLifecycle() {
    val observer = lifecycleObserver
    if (observer != null) {
      runCatching { boundLifecycle?.removeObserver(observer) }
    }
    lifecycleObserver = null
    boundLifecycle = null
  }

  /**
   * Restores the compiled Media3 preview after a configuration change / rotation
   * if [exoPlayer] was released during `ON_DESTROY` while the ViewModel survived.
   */
  fun restoreIfNeeded(currentPlayheadMs: Long? = null) {
    if (exoPlayer != null || auxAudioPlayer != null) return
    val saved = lastCompiledParams ?: return
    val restoredTimeline =
      currentPlayheadMs?.let { pos ->
        saved.timeline.copy(playheadMs = pos.coerceAtLeast(0L))
      } ?: saved.timeline.copy(playheadMs = resumePositionMs.coerceAtLeast(0L))

    loadTimelinePreview(
      timeline = restoredTimeline,
      assets = saved.assets,
      projectRootDir = saved.projectRootDir,
      renderSettings = saved.renderSettings,
      deviceProfile = saved.deviceProfile,
    )
  }

  fun loadTimelinePreview(
    timeline: TimelineState,
    assets: List<MediaAsset>,
    projectRootDir: File,
    renderSettings: RenderSettings,
    deviceProfile: DeviceProfile = DeviceProfile(),
  ) {
    val isSameMediaRequest =
      lastCompiledParams?.assets == assets &&
        lastCompiledParams?.renderSettings?.useProxiesForPreview == renderSettings.useProxiesForPreview
    if (!isSameMediaRequest) {
      effectsDisabledFallback = false
    }

    lastCompiledParams =
      LastCompileRequest(
        timeline = timeline,
        assets = assets,
        projectRootDir = projectRootDir,
        renderSettings = renderSettings,
        deviceProfile = deviceProfile,
      )
    resumePositionMs = timeline.playheadMs.coerceAtLeast(0L)

    if ((exoPlayer != null || auxAudioPlayer != null) && lastProfileTier != deviceProfile.tier) {
      // Recreate ExoPlayer with updated memory buffer budget when DeviceProfile tier shifts.
      releasePlayerOnly()
    }

    val assetById = assets.associateBy { it.assetId }
    val trackAudibility = AudioTrackMixer.resolveTrackAudibility(timeline.tracks)
    val trackById = timeline.tracks.associateBy { it.trackId }
    val duckingWindows = AudioTrackMixer.computeDuckingWindows(timeline)
    val mediaSourceFactory = DefaultMediaSourceFactory(context)

    val videoTrack =
      timeline.tracksSorted().firstOrNull { it.type == TrackType.VIDEO && it.isVisible }
    val videoClips =
      videoTrack?.let { track ->
        timeline.clipsForTrack(track.trackId).filter { it.isEnabled && it.kind == ClipKind.VIDEO }
      }.orEmpty()

    val videoMediaSources = mutableListOf<MediaSource>()
    val videoSegments = mutableListOf<TimelinePlaybackSegment>()
    val videoGaps = mutableListOf<TimelineGapWindow>()
    var proxyCount = 0
    var hdrOrHighResCount = 0
    var missingFiles = 0
    var primaryRotation = 0
    var primaryWidth = 0
    var primaryHeight = 0
    var cumulativeStreamUs = 0L
    var videoCursorMs = 0L

    for (clip in videoClips.sortedBy { it.timelineStartMs }) {
      val asset = clip.assetId?.let { assetById[it] }
      if (asset == null) {
        missingFiles++
        continue
      }

      val resolved = resolveSafePreviewUri(asset, projectRootDir, renderSettings, deviceProfile)
      if (resolved == null) {
        missingFiles++
        continue
      }
      if (resolved.isProxy) proxyCount++
      if (resolved.isHdrOrHighResProtected) hdrOrHighResCount++
      if (videoSegments.isEmpty()) {
        primaryRotation = asset.fingerprint.rotationDegrees
        val (orientedW, orientedH) = asset.fingerprint.orientedResolution()
        primaryWidth = orientedW ?: 0
        primaryHeight = orientedH ?: 0
      }

      val gapMs = (clip.timelineStartMs - videoCursorMs).coerceAtLeast(0L)
      if (gapMs > 0L) {
        val gapIdx = videoMediaSources.size
        videoMediaSources += SilenceMediaSource(gapMs * 1_000L)
        videoGaps += TimelineGapWindow(gapIdx, videoCursorMs, clip.timelineStartMs)
        cumulativeStreamUs += gapMs * 1_000L
      }

      val startMs = clip.sourceInMs.coerceAtLeast(0L)
      val endMs = clip.sourceOutMs.coerceAtLeast(startMs + 100L)
      val item =
        MediaItem.Builder()
          .setMediaId(clip.clipId)
          .setUri(resolved.uri)
          .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
              .setStartPositionMs(startMs)
              .setEndPositionMs(endMs)
              .build(),
          )
          .build()

      val isTrackAudible = trackAudibility[clip.trackId] == true
      val trackVolume = trackById[clip.trackId]?.volume?.coerceIn(0f, 2f) ?: 1f
      val clipDuckingWindows: List<DuckingWindow> =
        if (timeline.duckingConfig.targetTrackId == clip.trackId) {
          AudioTrackMixer.duckingWindowsForClip(clip, duckingWindows)
        } else {
          emptyList()
        }
      val audioCtx =
        PreviewAudioSegmentContext(
          isTrackAudible = isTrackAudible,
          clipStartOnTimelineMs = clip.timelineStartMs,
          clipDurationMs = clip.durationMs,
          clipSpeed = clip.speed,
          baseVolume = (clip.videoConfig?.volume ?: 1f).coerceIn(0f, 2f),
          trackVolume = trackVolume,
          fadeInMs = (clip.videoConfig?.fadeInMs ?: 0L).coerceIn(0L, clip.durationMs),
          fadeOutMs = (clip.videoConfig?.fadeOutMs ?: 0L).coerceIn(0L, clip.durationMs),
          volumeKeyframes = clip.keyframes(),
          duckingWindows = clipDuckingWindows,
          anchorClipOffsetMs = 0L,
          pan = 0.0f,
          fadeCurve = AudioFadeCurve.SMOOTH_S_CURVE,
        )

      val windowIdx = videoMediaSources.size
      videoMediaSources += mediaSourceFactory.createMediaSource(item)
      videoSegments +=
        TimelinePlaybackSegment(
          clipId = clip.clipId,
          windowIndex = windowIdx,
          timelineStartMs = clip.timelineStartMs,
          timelineEndMs = clip.timelineEndMs,
          sourceInMs = startMs,
          speed = clip.speed,
          isHdrOrHighResProtected = resolved.isHdrOrHighResProtected,
          clip = clip,
          audioContext = audioCtx,
          cumulativeStreamStartUs = cumulativeStreamUs,
        )
      cumulativeStreamUs += (clip.durationMs.coerceAtLeast(100L)) * 1_000L
      videoCursorMs = maxOf(videoCursorMs, clip.timelineEndMs)
    }

    // Build synchronized Audio A1 track sequence (including leading/inter-clip SilenceMediaSource gaps)
    val primaryAudioTrack =
      timeline.tracksSorted().firstOrNull { track ->
        track.type == TrackType.AUDIO &&
          timeline.clipsForTrack(track.trackId).any { it.isEnabled && it.kind == ClipKind.AUDIO }
      }
    val audioClips =
      primaryAudioTrack?.let { track ->
        timeline.clipsForTrack(track.trackId)
          .filter { it.isEnabled && it.kind == ClipKind.AUDIO }
          .sortedBy { it.timelineStartMs }
      }.orEmpty()

    val auxMediaSources = mutableListOf<MediaSource>()
    val auxSegments = mutableListOf<AudioPlaybackSegment>()
    val auxGaps = mutableListOf<TimelineGapWindow>()
    var auxCursorMs = 0L

    for (clip in audioClips) {
      val asset = clip.assetId?.let { assetById[it] } ?: continue
      val resolved =
        resolveSafePreviewUri(asset, projectRootDir, renderSettings, deviceProfile) ?: continue

      val gapMs = (clip.timelineStartMs - auxCursorMs).coerceAtLeast(0L)
      if (gapMs > 0L) {
        val gapIdx = auxMediaSources.size
        auxMediaSources += SilenceMediaSource(gapMs * 1_000L)
        auxGaps += TimelineGapWindow(gapIdx, auxCursorMs, clip.timelineStartMs)
      }

      val startMs = clip.sourceInMs.coerceAtLeast(0L)
      val endMs = clip.sourceOutMs.coerceAtLeast(startMs + 100L)
      val item =
        MediaItem.Builder()
          .setMediaId(clip.clipId)
          .setUri(resolved.uri)
          .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
              .setStartPositionMs(startMs)
              .setEndPositionMs(endMs)
              .build(),
          )
          .build()

      val isTrackAudible = trackAudibility[clip.trackId] == true
      val trackVolume = trackById[clip.trackId]?.volume?.coerceIn(0f, 2f) ?: 1f
      val clipDuckingWindows =
        if (timeline.duckingConfig.targetTrackId == clip.trackId) {
          AudioTrackMixer.duckingWindowsForClip(clip, duckingWindows)
        } else {
          emptyList()
        }
      val audioCtx =
        PreviewAudioSegmentContext(
          isTrackAudible = isTrackAudible,
          clipStartOnTimelineMs = clip.timelineStartMs,
          clipDurationMs = clip.durationMs,
          clipSpeed = clip.speed,
          baseVolume = (clip.audioConfig?.volume ?: 1f).coerceIn(0f, 2f),
          trackVolume = trackVolume,
          fadeInMs = (clip.audioConfig?.fadeInMs ?: 0L).coerceIn(0L, clip.durationMs),
          fadeOutMs = (clip.audioConfig?.fadeOutMs ?: 0L).coerceIn(0L, clip.durationMs),
          volumeKeyframes = clip.keyframes(),
          duckingWindows = clipDuckingWindows,
          anchorClipOffsetMs = 0L,
          pan = (clip.audioConfig?.pan ?: 0f).coerceIn(-1f, 1f),
          fadeCurve = AudioFadeCurve.SMOOTH_S_CURVE,
        )

      val windowIdx = auxMediaSources.size
      auxMediaSources += mediaSourceFactory.createMediaSource(item)
      auxSegments +=
        AudioPlaybackSegment(
          clipId = clip.clipId,
          trackId = clip.trackId,
          windowIndex = windowIdx,
          timelineStartMs = clip.timelineStartMs,
          timelineEndMs = clip.timelineEndMs,
          sourceInMs = startMs,
          speed = clip.speed,
          clip = clip,
          audioContext = audioCtx,
        )
      auxCursorMs = maxOf(auxCursorMs, clip.timelineEndMs)
    }

    if (videoMediaSources.isEmpty() && auxMediaSources.isEmpty()) {
      runCatching {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        auxAudioPlayer?.stop()
        auxAudioPlayer?.clearMediaItems()
      }
      currentSegments = emptyList()
      currentVideoGaps = emptyList()
      currentAudioSegments = emptyList()
      currentAudioGaps = emptyList()
      auxTotalTimelineMs = 0L
      activeSegmentSnapshot = null
      previewAudioProcessor.updateContext(null)
      auxAudioProcessor.updateContext(null)
      val error =
        if (missingFiles > 0) {
          MediaUiError(
            kind = MediaUiErrorKind.FILE_UNAVAILABLE,
            title = "Archivo multimedia no disponible",
            detail = "Uno o más clips vinculados no se encontraron en la carpeta portable.",
          )
        } else {
          null
        }
      _state.value =
        PreviewPlayerState(
          isLoopEnabled = loopEnabledPreference,
          hasPlayableMedia = false,
          statusNote = error?.detail ?: "No hay medios reproducibles en este rango.",
          uiError = error,
        )
      return
    }

    try {
      currentSegments = videoSegments
      currentVideoGaps = videoGaps
      currentAudioSegments = auxSegments
      currentAudioGaps = auxGaps
      auxTotalTimelineMs = auxCursorMs

      var primaryPlayer: ExoPlayer? = null
      var effectsFailed = false

      if (videoMediaSources.isNotEmpty()) {
        val player = getOrCreatePlayer(deviceProfile)
        primaryPlayer = player
        activeSegmentSnapshot =
          videoSegments.firstOrNull { timeline.playheadMs in it.timelineStartMs until it.timelineEndMs }
            ?: videoSegments.firstOrNull()
        val previewEffects =
          if (effectsDisabledFallback) {
            emptyList()
          } else {
            buildLivePreviewEffects(timeline, videoClips)
          }
        effectsFailed =
          runCatching { player.setVideoEffects(previewEffects) }
            .onFailure {
              effectsDisabledFallback = true
              runCatching { player.setVideoEffects(emptyList()) }
            }
            .isFailure
        player.setMediaSources(videoMediaSources, /* resetPosition = */ true)
        player.prepare()
      } else {
        activeSegmentSnapshot = null
        previewAudioProcessor.updateContext(null)
        runCatching {
          exoPlayer?.stop()
          exoPlayer?.clearMediaItems()
        }
      }

      if (auxMediaSources.isNotEmpty()) {
        val auxPlayer = getOrCreateAuxAudioPlayer(deviceProfile)
        auxPlayer.setMediaSources(auxMediaSources, /* resetPosition = */ true)
        auxPlayer.prepare()
      } else {
        auxAudioProcessor.updateContext(null)
        runCatching {
          auxAudioPlayer?.stop()
          auxAudioPlayer?.clearMediaItems()
        }
      }

      if (primaryPlayer != null) {
        seekInternal(primaryPlayer, timeline.playheadMs, useClosestSync = false)
      } else {
        seekAuxAudioTo(timeline.playheadMs, useClosestSync = false)
      }

      val notice =
        when {
          effectsDisabledFallback || effectsFailed ->
            PlaybackDiagnosticNotice(
              message = "Compatibilidad HDR/alta res: filtros GL desactivados en vista previa para proteger el decodificador.",
              isMemoryAdjusted = deviceProfile.tier == DeviceMemoryTier.CONSERVATIVE,
              isHdrProtected = true,
            )
          hdrOrHighResCount > 0 && proxyCount > 0 ->
            PlaybackDiagnosticNotice(
              message = "HDR/alta res protegido con proxy ligero (${deviceProfile.tier.label}).",
              isMemoryAdjusted = deviceProfile.tier == DeviceMemoryTier.CONSERVATIVE,
              isHdrProtected = true,
            )
          hdrOrHighResCount > 0 ->
            PlaybackDiagnosticNotice(
              message = "Clip HDR/4K detectado: reproducción protegida con fallback de decodificador.",
              isHdrProtected = true,
            )
          deviceProfile.tier == DeviceMemoryTier.CONSERVATIVE ->
            PlaybackDiagnosticNotice(
              message = "Búfer ajustado por memoria (${deviceProfile.availableRamMb} MB libres).",
              isMemoryAdjusted = true,
            )
          else -> null
        }

      val partialMissingError =
        if (missingFiles > 0) {
          MediaUiError(
            kind = MediaUiErrorKind.FILE_UNAVAILABLE,
            title = "$missingFiles clip(s) sin archivo local",
            detail = "Se omitieron los clips ausentes para evitar bloqueos.",
          )
        } else {
          null
        }

      val activePlaying = primaryPlayer?.isPlaying ?: (auxAudioPlayer?.isPlaying == true)
      _state.value =
        PreviewPlayerState(
          isPlaying = activePlaying,
          isLoopEnabled = loopEnabledPreference,
          hasPlayableMedia = true,
          activeProxyCount = proxyCount,
          videoRotationDegrees = primaryRotation,
          videoWidth = primaryWidth,
          videoHeight = primaryHeight,
          statusNote = null,
          diagnosticNotice = notice,
          uiError = partialMissingError,
        )
    } catch (t: Throwable) {
      _state.value =
        PreviewPlayerState(
          isLoopEnabled = loopEnabledPreference,
          hasPlayableMedia = false,
          statusNote = "No se pudo iniciar la vista previa para este clip.",
          uiError = mapThrowableToUiError(t),
        )
    }
  }

  /**
   * Deterministic evaluation of visual keyframe properties at [timelinePositionMs], shared
   * across live preview, 540p/proxy preview, standby monitor, and final export.
   */
  fun evaluateActiveClipPropertiesAt(timelinePositionMs: Long): EvaluatedClipProperties? {
    val segments = currentSegments
    if (segments.isEmpty()) return null
    val match =
      segments.firstOrNull { timelinePositionMs in it.timelineStartMs until it.timelineEndMs }
        ?: segments.lastOrNull { timelinePositionMs >= it.timelineStartMs }
        ?: segments.first()
    val offsetInClipMs = (timelinePositionMs - match.timelineStartMs).coerceIn(0L, match.clip.durationMs)
    return match.clip.evaluatePropertiesAt(offsetInClipMs)
  }

  /**
   * Deterministic evaluation of primary video audio gain (including keyframed volume, track
   * volume, fade-in/out, and ducking) at [timelinePositionMs].
   */
  fun evaluatePreviewAudioGainAt(timelinePositionMs: Long): Float? {
    val segments = currentSegments
    if (segments.isEmpty()) return null
    val match =
      segments.firstOrNull { timelinePositionMs in it.timelineStartMs until it.timelineEndMs }
        ?: return if (currentVideoGaps.any { timelinePositionMs in it.timelineStartMs until it.timelineEndMs }) {
          0f
        } else {
          null
        }
    val ctx = match.audioContext
    if (!ctx.isTrackAudible) return 0f
    val offsetInClipMs = (timelinePositionMs - match.timelineStartMs).coerceIn(0L, match.clip.durationMs)
    return AudioTrackMixer.computeClipEnvelopeGainAt(
      clipStartOnTimelineMs = ctx.clipStartOnTimelineMs,
      clipOffsetMs = offsetInClipMs,
      clipDurationMs = ctx.clipDurationMs,
      baseVolume = ctx.baseVolume,
      fadeInMs = ctx.fadeInMs,
      fadeOutMs = ctx.fadeOutMs,
      duckingWindows = ctx.duckingWindows,
      volumeKeyframes = ctx.volumeKeyframes,
      trackVolume = ctx.trackVolume,
      fadeCurve = ctx.fadeCurve,
    )
  }

  /**
   * Deterministic evaluation of auxiliary Track Audio A1 gain at [timelinePositionMs],
   * returning `0f` inside silent gaps or when muted/solo-suppressed, and `null` if no A1 clips exist.
   */
  fun evaluateAuxiliaryAudioGainAt(
    timelinePositionMs: Long,
    trackId: String? = null,
  ): Float? {
    val segments =
      if (trackId == null) currentAudioSegments else currentAudioSegments.filter { it.trackId == trackId }
    if (segments.isEmpty()) return null

    val match = segments.firstOrNull { timelinePositionMs in it.timelineStartMs until it.timelineEndMs }
      ?: return 0f
    val ctx = match.audioContext
    if (!ctx.isTrackAudible) return 0f
    val offsetInClipMs = (timelinePositionMs - match.timelineStartMs).coerceIn(0L, match.clip.durationMs)
    return AudioTrackMixer.computeClipEnvelopeGainAt(
      clipStartOnTimelineMs = ctx.clipStartOnTimelineMs,
      clipOffsetMs = offsetInClipMs,
      clipDurationMs = ctx.clipDurationMs,
      baseVolume = ctx.baseVolume,
      fadeInMs = ctx.fadeInMs,
      fadeOutMs = ctx.fadeOutMs,
      duckingWindows = ctx.duckingWindows,
      volumeKeyframes = ctx.volumeKeyframes,
      trackVolume = ctx.trackVolume,
      fadeCurve = ctx.fadeCurve,
    )
  }

  fun activeAudioSegmentCount(): Int = currentAudioSegments.size

  fun activeAudioGapWindowCount(): Int = currentAudioGaps.size

  internal fun buildLivePreviewEffects(
    timeline: TimelineState,
    videoClips: List<TimelineClip>,
  ): List<Effect> {
    val activeClip =
      videoClips.firstOrNull { timeline.playheadMs in it.timelineStartMs until it.timelineEndMs }
        ?: videoClips.firstOrNull()
        ?: return emptyList()

    val adjustment =
      timeline.clips
        .firstOrNull { adj ->
          adj.isEnabled &&
            adj.kind == ClipKind.ADJUSTMENT &&
            adj.timelineStartMs < activeClip.timelineEndMs &&
            adj.timelineEndMs > activeClip.timelineStartMs
        }
        ?.adjustmentConfig

    val anyHasKeyframes = videoClips.any { it.videoConfig?.keyframes?.isNotEmpty() == true }
    val anyHasAnimatedOpacity =
      videoClips.any { clip ->
        val cfg = clip.videoConfig
        cfg != null && (cfg.opacity < 0.99f || cfg.keyframes.any { it.opacity < 0.99f })
      }

    return runCatching {
      KeyframeMedia3Effects.buildEffectsWithEvaluator(
        videoCfg = activeClip.videoConfig,
        adjustment = adjustment,
        hasKeyframesOverride = anyHasKeyframes || activeClip.videoConfig?.keyframes?.isNotEmpty() == true,
        hasAnimatedOpacityOverride = anyHasAnimatedOpacity,
      ) { presentationTimeUs ->
        val seg = activeSegmentSnapshot
        if (seg != null) {
          val localPresentationUs =
            if (seg.cumulativeStreamStartUs > 0L && presentationTimeUs >= seg.cumulativeStreamStartUs) {
              presentationTimeUs - seg.cumulativeStreamStartUs
            } else {
              presentationTimeUs
            }
          KeyframeMedia3Effects.evaluateClipAtPresentationUs(seg.clip, localPresentationUs)
        } else {
          KeyframeMedia3Effects.evaluateClipAtPresentationUs(activeClip, presentationTimeUs)
        }
      }
    }.getOrElse { emptyList() }
  }

  fun beginScrubbing() {
    val activePlayer = exoPlayer ?: auxAudioPlayer ?: return
    if (_state.value.isScrubbing) return
    runCatching {
      if (activePlayer.isPlaying) activePlayer.pause()
      exoPlayer?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
      auxAudioPlayer?.let { aux ->
        if (aux.isPlaying) aux.pause()
        aux.setSeekParameters(SeekParameters.CLOSEST_SYNC)
      }
    }.onFailure { err ->
      _state.value = _state.value.copy(uiError = mapThrowableToUiError(err))
      return
    }
    lastScrubDispatchMs = Long.MIN_VALUE
    lastScrubWallClockMs = 0L
    _state.value = _state.value.copy(isScrubbing = true, isPlaying = false)
  }

  fun scrubTo(timelinePositionMs: Long) {
    if (exoPlayer == null && auxAudioPlayer == null) return
    if (!_state.value.isScrubbing) {
      beginScrubbing()
    }
    val profile = lastCompiledParams?.deviceProfile ?: DeviceProfile()
    val minIntervalMs =
      when (profile.tier) {
        DeviceMemoryTier.CONSERVATIVE -> 48L
        DeviceMemoryTier.BALANCED -> 24L
        DeviceMemoryTier.PERFORMANCE -> 12L
      }
    val now = System.currentTimeMillis()
    val clamped = timelinePositionMs.coerceAtLeast(0L)
    if (
      lastScrubDispatchMs != Long.MIN_VALUE &&
      abs(clamped - lastScrubDispatchMs) < 350L &&
      (now - lastScrubWallClockMs) < minIntervalMs
    ) {
      return
    }
    lastScrubDispatchMs = clamped
    lastScrubWallClockMs = now
    resumePositionMs = clamped
    runCatching {
      val primary = exoPlayer
      if (primary != null && currentSegments.isNotEmpty()) {
        seekInternal(primary, clamped, useClosestSync = true)
      } else {
        seekAuxAudioTo(clamped, useClosestSync = true)
      }
    }.onFailure { err ->
      _state.value = _state.value.copy(uiError = mapThrowableToUiError(err))
    }
  }

  fun endScrubbing(finalTimelinePositionMs: Long) {
    if (exoPlayer == null && auxAudioPlayer == null) return
    val clamped = finalTimelinePositionMs.coerceAtLeast(0L)
    lastScrubDispatchMs = Long.MIN_VALUE
    resumePositionMs = clamped
    runCatching {
      exoPlayer?.setSeekParameters(SeekParameters.EXACT)
      auxAudioPlayer?.setSeekParameters(SeekParameters.EXACT)
      val primary = exoPlayer
      if (primary != null && currentSegments.isNotEmpty()) {
        seekInternal(primary, clamped, useClosestSync = false)
      } else {
        seekAuxAudioTo(clamped, useClosestSync = false)
      }
    }.onFailure { err ->
      _state.value = _state.value.copy(uiError = mapThrowableToUiError(err))
    }
    _state.value = _state.value.copy(isScrubbing = false)
  }

  fun seekTo(timelinePositionMs: Long) {
    if (exoPlayer == null && auxAudioPlayer == null) return
    val clamped = timelinePositionMs.coerceAtLeast(0L)
    resumePositionMs = clamped
    val closest = _state.value.isScrubbing
    runCatching {
      val primary = exoPlayer
      if (primary != null && currentSegments.isNotEmpty()) {
        seekInternal(primary, clamped, useClosestSync = closest)
      } else {
        seekAuxAudioTo(clamped, useClosestSync = closest)
      }
    }.onFailure { err ->
      _state.value = _state.value.copy(uiError = mapThrowableToUiError(err))
    }
  }

  fun togglePlayPause() {
    if (!_state.value.hasPlayableMedia) return
    val primary = exoPlayer?.takeIf { currentSegments.isNotEmpty() }
    val aux = auxAudioPlayer?.takeIf { currentAudioSegments.isNotEmpty() }
    val master = primary ?: aux ?: return

    runCatching {
      if (master.playbackState == Player.STATE_ENDED) {
        seekTo(0L)
      }
      if (master.isPlaying) {
        primary?.pause()
        aux?.pause()
      } else {
        val posMs = currentPositionMs()
        primary?.setSeekParameters(SeekParameters.DEFAULT)
        aux?.setSeekParameters(SeekParameters.DEFAULT)
        if (aux != null && posMs < auxTotalTimelineMs) {
          seekAuxAudioTo(posMs, useClosestSync = false)
          aux.play()
        }
        primary?.play()
      }
    }.onFailure { err ->
      _state.value = _state.value.copy(isPlaying = false, uiError = mapThrowableToUiError(err))
    }
  }

  fun setLoopEnabled(enabled: Boolean) {
    loopEnabledPreference = enabled
    val mode = if (enabled) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    runCatching {
      exoPlayer?.repeatMode = mode
      auxAudioPlayer?.repeatMode = mode
    }
    _state.value = _state.value.copy(isLoopEnabled = enabled)
  }

  fun toggleLoop(): Boolean {
    val next = !loopEnabledPreference
    setLoopEnabled(next)
    return next
  }

  fun pausePlayback() {
    val currentPos = currentPositionMs()
    resumePositionMs = currentPos
    runCatching {
      exoPlayer?.let { player ->
        if (player.isPlaying) player.pause()
      }
      auxAudioPlayer?.let { aux ->
        if (aux.isPlaying) aux.pause()
      }
    }
    _state.value = _state.value.copy(isPlaying = false, isScrubbing = false)
  }

  fun retryLastPreview() {
    val request = lastCompiledParams ?: return
    effectsDisabledFallback = false
    _state.value = _state.value.copy(uiError = null)
    loadTimelinePreview(
      timeline = request.timeline,
      assets = request.assets,
      projectRootDir = request.projectRootDir,
      renderSettings = request.renderSettings,
      deviceProfile = request.deviceProfile,
    )
  }

  fun clearError() {
    _state.value = _state.value.copy(uiError = null)
  }

  fun currentPositionMs(): Long {
    val primary = exoPlayer
    if (primary != null && currentSegments.isNotEmpty()) {
      return runCatching {
        val windowIdx = primary.currentMediaItemIndex
        val seg = currentSegments.firstOrNull { it.windowIndex == windowIdx }
        if (seg != null) {
          val safeSpeed = seg.speed.coerceIn(0.25f, 4.0f)
          val timelineOffset = (primary.currentPosition.coerceAtLeast(0L) / safeSpeed).roundToLong()
          val masterTimelineMs = (seg.timelineStartMs + timelineOffset).coerceAtMost(seg.timelineEndMs)
          syncAuxPlayerDriftIfNeeded(masterTimelineMs, isPrimaryPlaying = primary.isPlaying)
          masterTimelineMs
        } else {
          val gap = currentVideoGaps.firstOrNull { it.windowIndex == windowIdx }
          if (gap != null) {
            val masterTimelineMs =
              (gap.timelineStartMs + primary.currentPosition.coerceAtLeast(0L)).coerceAtMost(gap.timelineEndMs)
            syncAuxPlayerDriftIfNeeded(masterTimelineMs, isPrimaryPlaying = primary.isPlaying)
            masterTimelineMs
          } else {
            primary.currentPosition.coerceAtLeast(0L)
          }
        }
      }.getOrDefault(resumePositionMs)
    }

    val aux = auxAudioPlayer
    if (aux != null && currentAudioSegments.isNotEmpty()) {
      return runCatching {
        val windowIdx = aux.currentMediaItemIndex
        val seg = currentAudioSegments.firstOrNull { it.windowIndex == windowIdx }
        if (seg != null) {
          val safeSpeed = seg.speed.coerceIn(0.25f, 4.0f)
          val timelineOffset = (aux.currentPosition.coerceAtLeast(0L) / safeSpeed).roundToLong()
          (seg.timelineStartMs + timelineOffset).coerceAtMost(seg.timelineEndMs)
        } else {
          val gap = currentAudioGaps.firstOrNull { it.windowIndex == windowIdx }
          if (gap != null) {
            (gap.timelineStartMs + aux.currentPosition.coerceAtLeast(0L)).coerceAtMost(gap.timelineEndMs)
          } else {
            aux.currentPosition.coerceAtLeast(0L)
          }
        }
      }.getOrDefault(resumePositionMs)
    }

    return resumePositionMs
  }

  private fun syncAuxPlayerDriftIfNeeded(
    masterTimelineMs: Long,
    isPrimaryPlaying: Boolean,
  ) {
    val aux = auxAudioPlayer ?: return
    if (currentAudioSegments.isEmpty()) return
    if (!isPrimaryPlaying || masterTimelineMs >= auxTotalTimelineMs) {
      if (aux.isPlaying && masterTimelineMs >= auxTotalTimelineMs) {
        runCatching { aux.pause() }
      }
      return
    }
    if (!aux.isPlaying) {
      seekAuxAudioTo(masterTimelineMs, useClosestSync = false)
      runCatching { aux.play() }
      return
    }
    val auxWindowIdx = aux.currentMediaItemIndex
    val auxTimelineMs =
      currentAudioSegments.firstOrNull { it.windowIndex == auxWindowIdx }?.let { seg ->
        val safeSpeed = seg.speed.coerceIn(0.25f, 4.0f)
        (seg.timelineStartMs + (aux.currentPosition.coerceAtLeast(0L) / safeSpeed).roundToLong())
          .coerceAtMost(seg.timelineEndMs)
      } ?: currentAudioGaps.firstOrNull { it.windowIndex == auxWindowIdx }?.let { gap ->
        (gap.timelineStartMs + aux.currentPosition.coerceAtLeast(0L)).coerceAtMost(gap.timelineEndMs)
      } ?: return

    if (abs(auxTimelineMs - masterTimelineMs) > A_V_SYNC_DRIFT_THRESHOLD_MS) {
      seekAuxAudioTo(masterTimelineMs, useClosestSync = false)
    }
  }

  /**
   * Releases ExoPlayer and hardware decoders on `ON_DESTROY` while keeping compiled metadata
   * so configuration changes / rotation can restore preview position seamlessly.
   */
  fun release() {
    resumePositionMs = currentPositionMs()
    detachLifecycle()
    releasePlayerOnly()
    currentSegments = emptyList()
    currentVideoGaps = emptyList()
    currentAudioSegments = emptyList()
    currentAudioGaps = emptyList()
    auxTotalTimelineMs = 0L
    _state.value = PreviewPlayerState(isLoopEnabled = loopEnabledPreference)
  }

  /**
   * Permanent cleanup invoked when the owning [androidx.lifecycle.ViewModel] is cleared.
   */
  fun disposePermanently() {
    lastCompiledParams = null
    resumePositionMs = 0L
    loopEnabledPreference = false
    effectsDisabledFallback = false
    release()
  }

  private fun releasePlayerOnly() {
    runCatching {
      exoPlayer?.stop()
      exoPlayer?.clearVideoSurface()
      exoPlayer?.release()
    }
    runCatching {
      auxAudioPlayer?.stop()
      auxAudioPlayer?.release()
    }
    exoPlayer = null
    auxAudioPlayer = null
  }

  private fun seekInternal(
    player: ExoPlayer,
    timelinePositionMs: Long,
    useClosestSync: Boolean,
  ) {
    val clamped = timelinePositionMs.coerceAtLeast(0L)
    seekAuxAudioTo(clamped, useClosestSync = useClosestSync)

    val segments = currentSegments
    val gaps = currentVideoGaps
    if (segments.isEmpty() && gaps.isEmpty()) {
      player.seekTo(clamped)
      return
    }

    val matchGap = gaps.firstOrNull { clamped in it.timelineStartMs until it.timelineEndMs }
    if (matchGap != null) {
      activeSegmentSnapshot = null
      previewAudioProcessor.updateContext(null)
      runCatching { player.setPlaybackParameters(PlaybackParameters.DEFAULT) }
      if (useClosestSync) player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
      player.seekTo(matchGap.windowIndex, (clamped - matchGap.timelineStartMs).coerceAtLeast(0L))
      return
    }

    val match =
      segments.firstOrNull { clamped in it.timelineStartMs until it.timelineEndMs }
        ?: segments.lastOrNull { clamped >= it.timelineStartMs }
        ?: segments.first()

    val offsetInClipMs =
      (clamped - match.timelineStartMs).coerceIn(0L, (match.timelineEndMs - match.timelineStartMs).coerceAtLeast(0L))
    val safeSpeed = match.speed.coerceIn(0.25f, 4.0f)
    activeSegmentSnapshot = match
    previewAudioProcessor.updateContext(match.audioContext.copy(anchorClipOffsetMs = offsetInClipMs))
    runCatching { player.setPlaybackParameters(PlaybackParameters(safeSpeed)) }
    val targetWindowPos = (offsetInClipMs * safeSpeed).toLong().coerceAtLeast(0L)
    if (useClosestSync) {
      player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }
    player.seekTo(match.windowIndex, targetWindowPos)
  }

  private fun seekAuxAudioTo(
    timelinePositionMs: Long,
    useClosestSync: Boolean,
  ) {
    val aux = auxAudioPlayer ?: return
    val segments = currentAudioSegments
    val gaps = currentAudioGaps
    if (segments.isEmpty() && gaps.isEmpty()) return

    val clamped = timelinePositionMs.coerceAtLeast(0L)
    if (clamped >= auxTotalTimelineMs && auxTotalTimelineMs > 0L) {
      auxAudioProcessor.updateContext(null)
      if (aux.isPlaying) runCatching { aux.pause() }
      return
    }

    val matchGap = gaps.firstOrNull { clamped in it.timelineStartMs until it.timelineEndMs }
    if (matchGap != null) {
      auxAudioProcessor.updateContext(null)
      runCatching { aux.setPlaybackParameters(PlaybackParameters.DEFAULT) }
      if (useClosestSync) aux.setSeekParameters(SeekParameters.CLOSEST_SYNC)
      aux.seekTo(matchGap.windowIndex, (clamped - matchGap.timelineStartMs).coerceAtLeast(0L))
      return
    }

    val match =
      segments.firstOrNull { clamped in it.timelineStartMs until it.timelineEndMs }
        ?: segments.lastOrNull { clamped >= it.timelineStartMs }
        ?: segments.first()

    val offsetInClipMs =
      (clamped - match.timelineStartMs).coerceIn(0L, (match.timelineEndMs - match.timelineStartMs).coerceAtLeast(0L))
    val safeSpeed = match.speed.coerceIn(0.25f, 4.0f)
    auxAudioProcessor.updateContext(match.audioContext.copy(anchorClipOffsetMs = offsetInClipMs))
    runCatching { aux.setPlaybackParameters(PlaybackParameters(safeSpeed)) }
    val targetWindowPos = (offsetInClipMs * safeSpeed).toLong().coerceAtLeast(0L)
    if (useClosestSync) {
      aux.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }
    aux.seekTo(match.windowIndex, targetWindowPos)
  }

  private fun handlePlaybackException(error: PlaybackException) {
    val uiError = mapPlaybackExceptionToUiError(error)
    val currentReq = lastCompiledParams

    // Fallback 1: If GL VideoFrameProcessor failed (common on HDR / 10-bit surfaces), retry without GL effects
    if (
      uiError.kind == MediaUiErrorKind.HDR_COMPATIBILITY_FALLBACK &&
      !effectsDisabledFallback &&
      currentReq != null
    ) {
      effectsDisabledFallback = true
      loadTimelinePreview(
        timeline = currentReq.timeline,
        assets = currentReq.assets,
        projectRootDir = currentReq.projectRootDir,
        renderSettings = currentReq.renderSettings,
        deviceProfile = currentReq.deviceProfile,
      )
      return
    }

    // Fallback 2: If HDR/high-res or decoder failed and proxies aren't forced yet, retry with proxy fallback
    if (
      uiError.kind == MediaUiErrorKind.UNSUPPORTED_FORMAT_OR_DECODER &&
      currentReq != null &&
      !currentReq.renderSettings.useProxiesForPreview
    ) {
      effectsDisabledFallback = true
      loadTimelinePreview(
        timeline = currentReq.timeline,
        assets = currentReq.assets,
        projectRootDir = currentReq.projectRootDir,
        renderSettings = currentReq.renderSettings.copy(useProxiesForPreview = true),
        deviceProfile = currentReq.deviceProfile,
      )
      _state.value =
        _state.value.copy(
          diagnosticNotice =
            PlaybackDiagnosticNotice(
              message = "Decodificador saturado: cambiado automáticamente a modo proxy compatible.",
              isMemoryAdjusted = true,
              isHdrProtected = true,
            ),
        )
      return
    }

    _state.value =
      _state.value.copy(
        isPlaying = false,
        isScrubbing = false,
        statusNote = uiError.detail,
        uiError = uiError,
      )
  }

  private data class ResolvedPreviewSource(
    val uri: Uri,
    val isProxy: Boolean,
    val isHdrOrHighResProtected: Boolean,
  )

  private fun resolveSafePreviewUri(
    asset: MediaAsset,
    projectRootDir: File,
    renderSettings: RenderSettings,
    deviceProfile: DeviceProfile,
  ): ResolvedPreviewSource? {
    val shouldPreferProxy =
      renderSettings.useProxiesForPreview ||
        (asset.fingerprint.isHdr && deviceProfile.tier != DeviceMemoryTier.PERFORMANCE) ||
        (asset.fingerprint.isHighResolution && deviceProfile.tier == DeviceMemoryTier.CONSERVATIVE)

    val isProtected = asset.fingerprint.isHdr || asset.fingerprint.isHighResolution

    if (shouldPreferProxy && !asset.proxyRelativePath.isNullOrBlank()) {
      val proxyFile = File(projectRootDir, asset.proxyRelativePath)
      if (proxyFile.exists() && proxyFile.length() > 0L) {
        return ResolvedPreviewSource(
          uri = Uri.fromFile(proxyFile),
          isProxy = true,
          isHdrOrHighResProtected = isProtected,
        )
      }
    }

    val localFile = File(projectRootDir, asset.relativePath)
    if (localFile.exists() && localFile.length() > 0L) {
      return ResolvedPreviewSource(
        uri = Uri.fromFile(localFile),
        isProxy = false,
        isHdrOrHighResProtected = isProtected,
      )
    }

    val extUri = asset.externalSourceUri
    if (!extUri.isNullOrBlank()) {
      return ResolvedPreviewSource(
        uri = Uri.parse(extUri),
        isProxy = false,
        isHdrOrHighResProtected = isProtected,
      )
    }
    return null
  }

  companion object {
    private const val A_V_SYNC_DRIFT_THRESHOLD_MS = 60L

    fun buildLoadControlForProfile(profile: DeviceProfile): DefaultLoadControl {
      val (minBufferMs, maxBufferMs) =
        when (profile.tier) {
          DeviceMemoryTier.CONSERVATIVE -> 1_500 to 4_000
          DeviceMemoryTier.BALANCED -> 2_500 to 8_000
          DeviceMemoryTier.PERFORMANCE -> 5_000 to 15_000
        }
      return DefaultLoadControl.Builder()
        .setBufferDurationsMs(
          minBufferMs,
          maxBufferMs,
          /* bufferForPlaybackMs = */ 500,
          /* bufferForPlaybackAfterRebufferMs = */ 1_000,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    }

    fun mapPlaybackExceptionToUiError(error: PlaybackException): MediaUiError =
      mapPlaybackErrorCodeToUiError(
        errorCode = error.errorCode,
        errorCodeName = error.errorCodeName,
        cause = error.cause,
      )

    fun mapPlaybackErrorCodeToUiError(
      errorCode: Int,
      errorCodeName: String = "",
      cause: Throwable? = null,
    ): MediaUiError {
      if (cause is OutOfMemoryError) {
        return MediaUiError(
          kind = MediaUiErrorKind.MEMORY_PRESSURE_ADJUSTED,
          title = "Límite de memoria alcanzado",
          detail = "Reduce la calidad de vista previa a 360p o genera proxies para liberar RAM.",
        )
      }
      return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, ->
          MediaUiError(
            kind = MediaUiErrorKind.FILE_UNAVAILABLE,
            title = "Archivo multimedia inaccesible",
            detail =
              if (errorCodeName.isNotBlank()) {
                "No se puede leer el clip desde almacenamiento ($errorCodeName)."
              } else {
                "No se puede leer el clip desde almacenamiento."
              },
          )

        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED, ->
          MediaUiError(
            kind = MediaUiErrorKind.HDR_COMPATIBILITY_FALLBACK,
            title = "Compatibilidad HDR / GPU",
            detail = "El procesador de video no toleró los efectos sobre este formato; usa modo proxy si persiste.",
          )

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, ->
          MediaUiError(
            kind = MediaUiErrorKind.UNSUPPORTED_FORMAT_OR_DECODER,
            title = "Formato o decodificador no soportado",
            detail = "El decodificador de hardware rechazó este clip (prueba generar proxies 540p).",
          )

        else ->
          MediaUiError(
            kind = MediaUiErrorKind.PLAYBACK_GENERAL,
            title = "Error de reproducción",
            detail = "No se pudo reproducir el video seleccionado.",
          )
      }
    }

    fun mapThrowableToUiError(t: Throwable): MediaUiError =
      when (t) {
        is OutOfMemoryError ->
          MediaUiError(
            kind = MediaUiErrorKind.MEMORY_PRESSURE_ADJUSTED,
            title = "Límite de memoria alcanzado",
            detail = "Reduce la calidad de vista previa a 360p o genera proxies para liberar RAM.",
          )
        is SecurityException ->
          MediaUiError(
            kind = MediaUiErrorKind.FILE_UNAVAILABLE,
            title = "Acceso al archivo denegado",
            detail = "Vuelve a seleccionar el video desde Medios para renovar el acceso.",
          )
        else ->
          MediaUiError(
            kind = MediaUiErrorKind.PLAYBACK_GENERAL,
            title = "Fallo al preparar vista previa",
            detail = "No se pudo preparar la vista previa del video seleccionado.",
          )
      }
  }
}
