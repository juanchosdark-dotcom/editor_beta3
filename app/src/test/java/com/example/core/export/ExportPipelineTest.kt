package com.example.core.export

import androidx.test.core.app.ApplicationProvider
import com.example.core.media.engine.MediaEngineBackend
import com.example.core.media.engine.RenderProgress
import com.example.core.media.model.ExportPreset
import com.example.core.media.model.MediaRenderTarget
import com.example.core.model.ClipKind
import com.example.core.model.MediaAsset
import com.example.core.model.MediaFingerprint
import com.example.core.model.MediaType
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExportPipelineTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private class FakeMediaBackend : MediaEngineBackend {
    var failNextRender: Boolean = false
    var streamChannel: Channel<RenderProgress>? = null
    var cancelCalls: Int = 0
    var renderCalls: Int = 0

    override fun renderTimeline(
      timeline: TimelineState,
      assets: List<MediaAsset>,
      projectRootDir: File,
      renderTarget: MediaRenderTarget,
      outputFile: File,
    ): Flow<RenderProgress> {
      renderCalls++
      val ch = streamChannel
      if (ch != null) {
        return ch.receiveAsFlow()
      }
      return flow {
        emit(RenderProgress.Preparing)
        emit(RenderProgress.Running(35))
        if (failNextRender) {
          emit(RenderProgress.Failed("Codec H.265 no soportado"))
        } else {
          outputFile.parentFile?.mkdirs()
          outputFile.writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70))
          emit(RenderProgress.Running(100))
          emit(
            RenderProgress.Completed(
              outputFile = outputFile,
              durationMs = 2_000L,
              fileSizeBytes = outputFile.length(),
            ),
          )
        }
      }
    }

    override fun transcodeProxy(
      sourceFile: File,
      proxyOutputFile: File,
      targetHeight: Int,
    ): Flow<RenderProgress> = flow { emit(RenderProgress.Cancelled) }

    override fun cancelActiveRender() {
      cancelCalls++
      streamChannel?.trySend(RenderProgress.Cancelled)
      streamChannel?.close()
    }
  }

  private fun sampleTimelineAndAssets(): Pair<TimelineState, List<MediaAsset>> {
    val timeline =
      TimelineState(
        tracks = listOf(Track("v1", TrackType.VIDEO, 0, "Video")),
        clips =
          listOf(
            TimelineClip(
              clipId = "c1",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "Clip 1",
              timelineStartMs = 0L,
              timelineEndMs = 2_000L,
              sourceInMs = 0L,
              sourceOutMs = 2_000L,
              assetId = "a1",
            ),
          ),
      )
    val assets =
      listOf(
        MediaAsset(
          assetId = "a1",
          type = MediaType.VIDEO,
          displayName = "clip1.mp4",
          relativePath = "media/clip1.mp4",
          fingerprint = MediaFingerprint(128L, 2_000L, "hash", "video/mp4"),
        ),
      )
    return timeline to assets
  }

  @Test
  fun enqueueExport_completesAndPublishesMp4PathAndGalleryUri() = runTest {
    val rootDir = tempFolder.newFolder("export_success")
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val backend = FakeMediaBackend()
    val queueManager =
      ExportQueueManager(
        backend = backend,
        gallerySaveHelper = GallerySaveHelper(
          context = context,
          ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        ),
        scope = TestScope(testScheduler),
      )

    val (timeline, assets) = sampleTimelineAndAssets()
    val jobId =
      queueManager.enqueue(
        title = "Reel_Final",
        range = ExportRange.FullTimeline,
        exportConfig = ExportPreset.HD_1080P_H264.config,
        timelineSnapshot = timeline,
        assetsSnapshot = assets,
        projectRootDir = rootDir,
      )

    advanceUntilIdle()

    val item = queueManager.queue.value.first { it.jobId == jobId }
    assertEquals(ExportJobStatus.COMPLETED, item.status)
    assertEquals(100, item.progressPercent)
    assertNotNull(item.outputFilePath)
    assertNotNull(item.galleryUri)
    assertTrue((item.outputSizeBytes ?: 0L) > 0L)
    assertNull(item.errorMessage)
  }

  @Test
  fun cancelRunningJob_transitionsSafelyToCancelledAndUnblocksNextJob() = runTest {
    val rootDir = tempFolder.newFolder("export_cancel")
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val backend = FakeMediaBackend()
    val channel = Channel<RenderProgress>(Channel.UNLIMITED)
    backend.streamChannel = channel

    val queueManager =
      ExportQueueManager(
        backend = backend,
        gallerySaveHelper = GallerySaveHelper(
          context = context,
          ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        ),
        scope = TestScope(testScheduler),
      )

    val (timeline, assets) = sampleTimelineAndAssets()
    val firstId =
      queueManager.enqueue(
        title = "First_Job",
        range = ExportRange.FullTimeline,
        exportConfig = ExportPreset.HD_1080P_H264.config,
        timelineSnapshot = timeline,
        assetsSnapshot = assets,
        projectRootDir = rootDir,
      )

    channel.trySend(RenderProgress.Preparing)
    channel.trySend(RenderProgress.Running(42))
    advanceUntilIdle()

    val runningItem = queueManager.queue.value.first { it.jobId == firstId }
    assertEquals(ExportJobStatus.RUNNING, runningItem.status)
    assertEquals(42, runningItem.progressPercent)

    // Switch backend to normal completion for the second job once the first is cancelled.
    queueManager.cancelJob(firstId)
    backend.streamChannel = null

    val secondId =
      queueManager.enqueue(
        title = "Second_Job",
        range = ExportRange.FullTimeline,
        exportConfig = ExportPreset.HD_1080P_H264.config,
        timelineSnapshot = timeline,
        assetsSnapshot = assets,
        projectRootDir = rootDir,
      )

    advanceUntilIdle()

    val firstAfterCancel = queueManager.queue.value.first { it.jobId == firstId }
    val secondAfterCancel = queueManager.queue.value.first { it.jobId == secondId }
    assertEquals(1, backend.cancelCalls)
    assertEquals(ExportJobStatus.CANCELLED, firstAfterCancel.status)
    assertEquals(ExportJobStatus.COMPLETED, secondAfterCancel.status)
  }

  @Test
  fun failedExport_surfacesErrorMessageAndRecoversOnRetry() = runTest {
    val rootDir = tempFolder.newFolder("export_retry")
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val backend = FakeMediaBackend().apply { failNextRender = true }
    val queueManager =
      ExportQueueManager(
        backend = backend,
        gallerySaveHelper = GallerySaveHelper(
          context = context,
          ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        ),
        scope = TestScope(testScheduler),
      )

    val (timeline, assets) = sampleTimelineAndAssets()
    val jobId =
      queueManager.enqueue(
        title = "Retry_Job",
        range = ExportRange.FullTimeline,
        exportConfig = ExportPreset.HD_1080P_H264.config,
        timelineSnapshot = timeline,
        assetsSnapshot = assets,
        projectRootDir = rootDir,
      )

    advanceUntilIdle()

    val failedItem = queueManager.queue.value.first { it.jobId == jobId }
    assertEquals(ExportJobStatus.FAILED, failedItem.status)
    assertEquals("Codec H.265 no soportado", failedItem.errorMessage)

    backend.failNextRender = false
    queueManager.retryJob(jobId)
    advanceUntilIdle()

    val retriedItem = queueManager.queue.value.first { it.jobId == jobId }
    assertEquals(ExportJobStatus.COMPLETED, retriedItem.status)
    assertNull(retriedItem.errorMessage)
    assertEquals(100, retriedItem.progressPercent)
  }
}
