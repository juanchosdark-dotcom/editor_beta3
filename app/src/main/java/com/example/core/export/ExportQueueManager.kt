package com.example.core.export

import com.example.core.media.engine.MediaEngineBackend
import com.example.core.media.engine.RenderProgress
import com.example.core.media.model.MediaRenderTarget
import com.example.core.model.ExportConfig
import com.example.core.model.MediaAsset
import com.example.core.model.TimelineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Sequential local export queue supporting full-timeline exports, arbitrary time ranges,
 * and marker-delimited chapter batch exports.
 *
 * Uses a single-worker sequential loop so hardware encoders on mobile devices are never
 * oversubscribed, while allowing safe cancellation and retry of individual jobs.
 */
class ExportQueueManager(
  private val backend: MediaEngineBackend,
  private val gallerySaveHelper: GallerySaveHelper,
  private val scope: CoroutineScope,
) {

  private data class JobPayload(
    val timelineSnapshot: TimelineState,
    val assetsSnapshot: List<MediaAsset>,
    val projectRootDir: File,
  )

  private val payloads = mutableMapOf<String, JobPayload>()

  private val _queue = MutableStateFlow<List<ExportQueueItem>>(emptyList())
  val queue: StateFlow<List<ExportQueueItem>> = _queue.asStateFlow()

  private var workerJob: Job? = null
  private var activeJobId: String? = null

  fun enqueue(
    title: String,
    range: ExportRange,
    exportConfig: ExportConfig,
    timelineSnapshot: TimelineState,
    assetsSnapshot: List<MediaAsset>,
    projectRootDir: File,
  ): String {
    val jobId = "exp_${UUID.randomUUID().toString().take(8)}"
    val item =
      ExportQueueItem(
        jobId = jobId,
        title = title,
        range = range,
        exportConfig = exportConfig,
      )
    payloads[jobId] = JobPayload(timelineSnapshot, assetsSnapshot, projectRootDir)
    _queue.update { it + item }
    ensureWorkerRunning()
    return jobId
  }

  /**
   * Expands the timeline markers into consecutive chapter segments and enqueues one
   * export job per segment.
   */
  fun enqueueMarkerChapters(
    projectName: String,
    exportConfig: ExportConfig,
    timelineSnapshot: TimelineState,
    assetsSnapshot: List<MediaAsset>,
    projectRootDir: File,
  ): List<String> {
    val spans =
      RangeSliceHelper.buildMarkerChapterSpans(
        markers = timelineSnapshot.markers,
        totalDurationMs = timelineSnapshot.totalDurationMs,
      )
    return spans.mapIndexed { index, span ->
      enqueue(
        title = "${projectName}_Cap${index + 1}_${span.label}",
        range = span,
        exportConfig = exportConfig,
        timelineSnapshot = timelineSnapshot,
        assetsSnapshot = assetsSnapshot,
        projectRootDir = projectRootDir,
      )
    }
  }

  fun cancelJob(jobId: String) {
    val currentStatus = _queue.value.find { it.jobId == jobId }?.status ?: return
    if (currentStatus == ExportJobStatus.QUEUED) {
      updateItem(jobId) {
        it.copy(status = ExportJobStatus.CANCELLED, errorMessage = null)
      }
    } else if (currentStatus == ExportJobStatus.RUNNING && activeJobId == jobId) {
      updateItem(jobId) {
        it.copy(status = ExportJobStatus.CANCELLED, errorMessage = null)
      }
      backend.cancelActiveRender()
    }
  }

  fun retryJob(jobId: String) {
    val existing = _queue.value.find { it.jobId == jobId } ?: return
    if (existing.status == ExportJobStatus.FAILED || existing.status == ExportJobStatus.CANCELLED) {
      updateItem(jobId) {
        it.copy(
          status = ExportJobStatus.QUEUED,
          progressPercent = 0,
          outputFilePath = null,
          galleryUri = null,
          outputSizeBytes = null,
          errorMessage = null,
        )
      }
      ensureWorkerRunning()
    }
  }

  fun clearFinished() {
    val finishedIds =
      _queue.value
        .filter {
          it.status == ExportJobStatus.COMPLETED ||
            it.status == ExportJobStatus.CANCELLED ||
            it.status == ExportJobStatus.FAILED
        }
        .mapTo(mutableSetOf()) { it.jobId }

    finishedIds.forEach { payloads.remove(it) }
    _queue.update { items -> items.filterNot { it.jobId in finishedIds } }
  }

  private fun ensureWorkerRunning() {
    if (workerJob?.isActive == true) return
    workerJob =
      scope.launch {
        while (true) {
          val nextItem =
            _queue.value.firstOrNull { it.status == ExportJobStatus.QUEUED } ?: break
          processJob(nextItem)
        }
      }
  }

  private suspend fun processJob(item: ExportQueueItem) {
    val payload = payloads[item.jobId]
    if (payload == null) {
      updateItem(item.jobId) {
        it.copy(status = ExportJobStatus.FAILED, errorMessage = "Snapshot de proyecto ausente")
      }
      return
    }

    activeJobId = item.jobId
    updateItem(item.jobId) {
      it.copy(status = ExportJobStatus.RUNNING, progressPercent = 0, errorMessage = null)
    }

    val safeName = item.title.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val outputFile = File(payload.projectRootDir, "export_staging/${safeName}_${item.jobId}.mp4")

    try {
      val slicedTimeline =
        when (val range = item.range) {
          is ExportRange.FullTimeline -> payload.timelineSnapshot
          is ExportRange.TimeRange ->
            RangeSliceHelper.sliceTimelineForRange(
              payload.timelineSnapshot,
              range.startMs,
              range.endMs,
            )
          is ExportRange.MarkerSpan ->
            RangeSliceHelper.sliceTimelineForRange(
              payload.timelineSnapshot,
              range.startMs,
              range.endMs,
            )
        }

      backend
        .renderTimeline(
          timeline = slicedTimeline,
          assets = payload.assetsSnapshot,
          projectRootDir = payload.projectRootDir,
          // Final export ALWAYS enforces full-resolution ExportConfig and ignores preview proxies.
          renderTarget = MediaRenderTarget.Export(item.exportConfig),
          outputFile = outputFile,
        )
        .collect { progress ->
          when (progress) {
            is RenderProgress.Preparing -> {
              if (_queue.value.find { it.jobId == item.jobId }?.status != ExportJobStatus.CANCELLED) {
                updateItem(item.jobId) { it.copy(progressPercent = 0) }
              }
            }
            is RenderProgress.Running -> {
              if (_queue.value.find { it.jobId == item.jobId }?.status != ExportJobStatus.CANCELLED) {
                updateItem(item.jobId) { it.copy(progressPercent = progress.percent) }
              }
            }
            is RenderProgress.Completed -> {
              val publishResult =
                gallerySaveHelper.publishMp4ToGallery(progress.outputFile, item.title)
              val galleryUri = publishResult.getOrNull()?.toString()
              val galleryWarning =
                publishResult.exceptionOrNull()?.let { err ->
                  "Exportado localmente, pero falló al registrar en Galería: ${err.localizedMessage ?: "error de MediaStore"}"
                }
              updateItem(item.jobId) {
                it.copy(
                  status = ExportJobStatus.COMPLETED,
                  progressPercent = 100,
                  outputFilePath = progress.outputFile.absolutePath,
                  galleryUri = galleryUri,
                  outputSizeBytes = progress.fileSizeBytes,
                  errorMessage = galleryWarning,
                )
              }
            }
            is RenderProgress.Cancelled -> {
              if (outputFile.exists()) outputFile.delete()
              updateItem(item.jobId) {
                it.copy(status = ExportJobStatus.CANCELLED, errorMessage = null)
              }
            }
            is RenderProgress.Failed -> {
              if (outputFile.exists()) outputFile.delete()
              updateItem(item.jobId) {
                it.copy(status = ExportJobStatus.FAILED, errorMessage = progress.message)
              }
            }
          }
        }
    } catch (e: CancellationException) {
      if (outputFile.exists()) outputFile.delete()
      throw e
    } catch (t: Throwable) {
      if (outputFile.exists()) outputFile.delete()
      updateItem(item.jobId) {
        it.copy(
          status = ExportJobStatus.FAILED,
          errorMessage = t.localizedMessage ?: "Error inesperado durante la exportación",
        )
      }
    } finally {
      if (activeJobId == item.jobId) {
        activeJobId = null
      }
    }
  }

  private fun updateItem(jobId: String, transform: (ExportQueueItem) -> ExportQueueItem) {
    _queue.update { items ->
      items.map { item -> if (item.jobId == jobId) transform(item) else item }
    }
  }
}
