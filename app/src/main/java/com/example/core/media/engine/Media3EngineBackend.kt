package com.example.core.media.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.core.media.model.MediaRenderTarget
import com.example.core.model.AudioCodec
import com.example.core.model.MediaAsset
import com.example.core.model.TimelineState
import com.example.core.model.VideoCodec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Android hardware-accelerated implementation of [MediaEngineBackend] powered by
 * Jetpack Media3 [Transformer].
 *
 * All [Transformer] calls are dispatched onto the main Looper as required by Media3 threading
 * guarantees, while progress polling, completion, errors, and safe cancellation are exposed
 * reactively as a Kotlin [Flow].
 */
@OptIn(UnstableApi::class)
class Media3EngineBackend(
  private val context: Context,
  private val compiler: CompositionCompiler = CompositionCompiler(),
) : MediaEngineBackend {

  private class ActiveRenderSession(
    val transformer: Transformer,
    val outputFile: File,
    val onCancel: () -> Unit,
  ) {
    val terminated = AtomicBoolean(false)
    var pollRunnable: Runnable? = null
  }

  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile
  private var activeSession: ActiveRenderSession? = null

  override fun renderTimeline(
    timeline: TimelineState,
    assets: List<MediaAsset>,
    projectRootDir: File,
    renderTarget: MediaRenderTarget,
    outputFile: File,
  ): Flow<RenderProgress> =
    callbackFlow {
      trySend(RenderProgress.Preparing)

      val compiled = compiler.compile(timeline, assets, projectRootDir, renderTarget)
      if (compiled is CompiledCompositionResult.EmptyOrMissingMedia) {
        trySend(RenderProgress.Failed(compiled.reason))
        close()
        return@callbackFlow
      }

      val ready = compiled as CompiledCompositionResult.Ready
      outputFile.parentFile?.mkdirs()
      if (outputFile.exists()) outputFile.delete()

      val videoMimeType =
        when (renderTarget) {
          is MediaRenderTarget.Preview -> MimeTypes.VIDEO_H264
          is MediaRenderTarget.Export ->
            when (renderTarget.config.videoCodec) {
              VideoCodec.H264 -> MimeTypes.VIDEO_H264
              VideoCodec.HEVC -> MimeTypes.VIDEO_H265
            }
        }

      val audioMimeType =
        when (renderTarget) {
          is MediaRenderTarget.Preview -> MimeTypes.AUDIO_AAC
          is MediaRenderTarget.Export ->
            when (renderTarget.config.audioCodec) {
              AudioCodec.AAC -> MimeTypes.AUDIO_AAC
              AudioCodec.OPUS -> MimeTypes.AUDIO_OPUS
            }
        }

      var createdSession: ActiveRenderSession? = null

      mainHandler.post {
        terminateSessionLocked(activeSession, deletePartialFile = true)

        var sessionRef: ActiveRenderSession? = null
        val listener =
          object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              val session = sessionRef ?: return
              if (!session.terminated.compareAndSet(false, true)) return
              session.pollRunnable?.let { mainHandler.removeCallbacks(it) }
              if (activeSession === session) activeSession = null
              trySend(RenderProgress.Running(100))
              trySend(
                RenderProgress.Completed(
                  outputFile = outputFile,
                  durationMs = exportResult.durationMs.coerceAtLeast(0L),
                  fileSizeBytes = outputFile.length(),
                ),
              )
              close()
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException,
            ) {
              val session = sessionRef ?: return
              if (!session.terminated.compareAndSet(false, true)) return
              session.pollRunnable?.let { mainHandler.removeCallbacks(it) }
              if (activeSession === session) activeSession = null
              if (outputFile.exists()) outputFile.delete()
              trySend(
                RenderProgress.Failed(
                  message = exportException.localizedMessage ?: "Error en Media3 Transformer",
                  cause = exportException,
                ),
              )
              close()
            }
          }

        try {
          val transformerBuilder =
            Transformer.Builder(context)
              .setVideoMimeType(videoMimeType)
              .setAudioMimeType(audioMimeType)
              .addListener(listener)

          if (renderTarget is MediaRenderTarget.Export) {
            val encoderFactory =
              DefaultEncoderFactory.Builder(context)
                .setEnableFallback(true)
                .setRequestedVideoEncoderSettings(
                  VideoEncoderSettings.Builder()
                    .setBitrate((renderTarget.config.videoBitrateKbps * 1_000).coerceAtLeast(500_000))
                    .build(),
                )
                .build()
            transformerBuilder.setEncoderFactory(encoderFactory)
          }

          val transformer = transformerBuilder.build()
          val session =
            ActiveRenderSession(
              transformer = transformer,
              outputFile = outputFile,
              onCancel = {
                trySend(RenderProgress.Cancelled)
                close()
              },
            )
          sessionRef = session
          createdSession = session
          activeSession = session

          transformer.start(ready.composition, outputFile.absolutePath)

          val progressHolder = ProgressHolder()
          val poller =
            object : Runnable {
              override fun run() {
                if (session.terminated.get()) return
                val state =
                  runCatching { session.transformer.getProgress(progressHolder) }
                    .getOrDefault(Transformer.PROGRESS_STATE_NOT_STARTED)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                  trySend(RenderProgress.Running(progressHolder.progress.coerceIn(0, 99)))
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                  mainHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
                }
              }
            }
          session.pollRunnable = poller
          mainHandler.postDelayed(poller, PROGRESS_POLL_INTERVAL_MS)
        } catch (t: Throwable) {
          sessionRef?.let { s ->
            s.terminated.set(true)
            s.pollRunnable?.let { mainHandler.removeCallbacks(it) }
            if (activeSession === s) activeSession = null
          }
          if (outputFile.exists()) outputFile.delete()
          trySend(
            RenderProgress.Failed(
              message = t.localizedMessage ?: "No se pudo iniciar Media3 Transformer",
              cause = t,
            ),
          )
          close()
        }
      }

      awaitClose {
        mainHandler.post {
          val session = createdSession ?: activeSession
          if (session != null && session.terminated.compareAndSet(false, true)) {
            session.pollRunnable?.let { mainHandler.removeCallbacks(it) }
            runCatching { session.transformer.cancel() }
            if (activeSession === session) activeSession = null
            if (session.outputFile.exists()) session.outputFile.delete()
          }
        }
      }
    }

  override fun transcodeProxy(
    sourceFile: File,
    proxyOutputFile: File,
    targetHeight: Int,
  ): Flow<RenderProgress> =
    callbackFlow {
      trySend(RenderProgress.Preparing)
      proxyOutputFile.parentFile?.mkdirs()
      if (proxyOutputFile.exists()) proxyOutputFile.delete()

      val editedItem =
        EditedMediaItem.Builder(MediaItem.fromUri(sourceFile.toURI().toString()))
          .setEffects(
            Effects(
              emptyList(),
              listOf(Presentation.createForHeight(targetHeight)),
            ),
          )
          .build()

      var createdSession: ActiveRenderSession? = null

      mainHandler.post {
        terminateSessionLocked(activeSession, deletePartialFile = true)

        var sessionRef: ActiveRenderSession? = null
        val listener =
          object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              val session = sessionRef ?: return
              if (!session.terminated.compareAndSet(false, true)) return
              session.pollRunnable?.let { mainHandler.removeCallbacks(it) }
              if (activeSession === session) activeSession = null
              trySend(RenderProgress.Running(100))
              trySend(
                RenderProgress.Completed(
                  outputFile = proxyOutputFile,
                  durationMs = exportResult.durationMs.coerceAtLeast(0L),
                  fileSizeBytes = proxyOutputFile.length(),
                ),
              )
              close()
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException,
            ) {
              val session = sessionRef ?: return
              if (!session.terminated.compareAndSet(false, true)) return
              session.pollRunnable?.let { mainHandler.removeCallbacks(it) }
              if (activeSession === session) activeSession = null
              if (proxyOutputFile.exists()) proxyOutputFile.delete()
              trySend(
                RenderProgress.Failed(
                  message = exportException.localizedMessage ?: "Error al generar proxy",
                  cause = exportException,
                ),
              )
              close()
            }
          }

        try {
          val transformer =
            Transformer.Builder(context)
              .setVideoMimeType(MimeTypes.VIDEO_H264)
              .setAudioMimeType(MimeTypes.AUDIO_AAC)
              .addListener(listener)
              .build()

          val session =
            ActiveRenderSession(
              transformer = transformer,
              outputFile = proxyOutputFile,
              onCancel = {
                trySend(RenderProgress.Cancelled)
                close()
              },
            )
          sessionRef = session
          createdSession = session
          activeSession = session

          transformer.start(editedItem, proxyOutputFile.absolutePath)

          val progressHolder = ProgressHolder()
          val poller =
            object : Runnable {
              override fun run() {
                if (session.terminated.get()) return
                val state =
                  runCatching { session.transformer.getProgress(progressHolder) }
                    .getOrDefault(Transformer.PROGRESS_STATE_NOT_STARTED)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                  trySend(RenderProgress.Running(progressHolder.progress.coerceIn(0, 99)))
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                  mainHandler.postDelayed(this, PROGRESS_POLL_INTERVAL_MS)
                }
              }
            }
          session.pollRunnable = poller
          mainHandler.postDelayed(poller, PROGRESS_POLL_INTERVAL_MS)
        } catch (t: Throwable) {
          sessionRef?.let { s ->
            s.terminated.set(true)
            s.pollRunnable?.let { mainHandler.removeCallbacks(it) }
            if (activeSession === s) activeSession = null
          }
          if (proxyOutputFile.exists()) proxyOutputFile.delete()
          trySend(
            RenderProgress.Failed(
              message = t.localizedMessage ?: "No se pudo iniciar generación de proxy",
              cause = t,
            ),
          )
          close()
        }
      }

      awaitClose {
        mainHandler.post {
          val session = createdSession ?: activeSession
          if (session != null && session.terminated.compareAndSet(false, true)) {
            session.pollRunnable?.let { mainHandler.removeCallbacks(it) }
            runCatching { session.transformer.cancel() }
            if (activeSession === session) activeSession = null
            if (session.outputFile.exists()) session.outputFile.delete()
          }
        }
      }
    }

  override fun cancelActiveRender() {
    mainHandler.post {
      terminateSessionLocked(activeSession, deletePartialFile = true)
    }
  }

  private fun terminateSessionLocked(
    session: ActiveRenderSession?,
    deletePartialFile: Boolean,
  ) {
    if (session == null) return
    if (!session.terminated.compareAndSet(false, true)) return
    session.pollRunnable?.let { mainHandler.removeCallbacks(it) }
    runCatching { session.transformer.cancel() }
    if (activeSession === session) activeSession = null
    if (deletePartialFile && session.outputFile.exists()) {
      session.outputFile.delete()
    }
    session.onCancel()
  }

  companion object {
    private const val PROGRESS_POLL_INTERVAL_MS = 200L
  }
}
