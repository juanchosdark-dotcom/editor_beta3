package com.example.core.media

import com.example.core.media.engine.CompiledCompositionResult
import com.example.core.media.engine.CompositionCompiler
import com.example.core.media.model.ExportPreset
import com.example.core.media.model.MediaRenderTarget
import com.example.core.media.model.PreviewQualityProfile
import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.ClipKeyframe
import com.example.core.model.ClipKind
import com.example.core.model.MediaAsset
import com.example.core.model.MediaFingerprint
import com.example.core.model.MediaType
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.model.VideoClipConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CompositionCompilerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun compile_usesProxyOnlyForPreviewAndAlwaysOriginalForExport() {
    val rootDir = tempFolder.newFolder("portable_proj")
    val mediaFile = File(rootDir, "media/clip1.mp4").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2, 3, 4))
    }
    val proxyFile = File(rootDir, "proxies/clip1_proxy.mp4").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2))
    }

    val asset =
      MediaAsset(
        assetId = "a1",
        type = MediaType.VIDEO,
        displayName = "clip1.mp4",
        relativePath = "media/${mediaFile.name}",
        proxyRelativePath = "proxies/${proxyFile.name}",
        fingerprint = MediaFingerprint(4L, 3_000L, "abc", "video/mp4"),
      )

    val timeline =
      TimelineState(
        tracks = listOf(Track("v1", TrackType.VIDEO, 0, "Video")),
        clips =
          listOf(
            TimelineClip(
              clipId = "c1",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "clip1",
              timelineStartMs = 0L,
              timelineEndMs = 3_000L,
              sourceInMs = 0L,
              sourceOutMs = 3_000L,
              assetId = "a1",
              isEnabled = true,
            ),
            TimelineClip(
              clipId = "c_off",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "disabled",
              timelineStartMs = 3_000L,
              timelineEndMs = 5_000L,
              sourceInMs = 0L,
              sourceOutMs = 2_000L,
              assetId = "a1",
              isEnabled = false,
            ),
          ),
      )

    val compiler = CompositionCompiler()

    val previewResult =
      compiler.compile(
        timeline = timeline,
        assets = listOf(asset),
        projectRootDir = rootDir,
        renderTarget =
          MediaRenderTarget.Preview(
            profile = PreviewQualityProfile.DRAFT_360P,
            useProxies = true,
          ),
      ) as CompiledCompositionResult.Ready

    assertEquals(1, previewResult.totalVideoItems)
    assertEquals(1, previewResult.usedProxyCount)

    val exportResult =
      compiler.compile(
        timeline = timeline,
        assets = listOf(asset),
        projectRootDir = rootDir,
        renderTarget = MediaRenderTarget.Export(ExportPreset.HD_1080P_H264.config),
      ) as CompiledCompositionResult.Ready

    assertEquals(1, exportResult.totalVideoItems)
    assertEquals(0, exportResult.usedProxyCount)
    assertTrue(exportResult.composition.sequences.isNotEmpty())
  }

  @Test
  fun compile_appliesClippingConfigurationKeyframesAndAdjustmentEffects() {
    val rootDir = tempFolder.newFolder("export_fx_proj")
    val mediaFile = File(rootDir, "media/clip_trimmed.mp4").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(1, 2, 3, 4, 5))
    }

    val asset =
      MediaAsset(
        assetId = "a1",
        type = MediaType.VIDEO,
        displayName = "clip_trimmed.mp4",
        relativePath = "media/${mediaFile.name}",
        fingerprint = MediaFingerprint(5L, 8_000L, "def", "video/mp4"),
      )

    val timeline =
      TimelineState(
        tracks =
          listOf(
            Track("v1", TrackType.VIDEO, 0, "Video"),
            Track("adj1", TrackType.ADJUSTMENT, 1, "Color"),
          ),
        clips =
          listOf(
            TimelineClip(
              clipId = "c_trim",
              trackId = "v1",
              kind = ClipKind.VIDEO,
              label = "trimmed",
              timelineStartMs = 0L,
              timelineEndMs = 3_500L,
              sourceInMs = 1_200L,
              sourceOutMs = 4_700L,
              assetId = "a1",
              videoConfig =
                VideoClipConfig(
                  scale = 1.2f,
                  offsetX = 0.1f,
                  opacity = 0.85f,
                  keyframes =
                    listOf(
                      ClipKeyframe("kf1", offsetMs = 0L, scale = 1.0f, opacity = 0.8f),
                      ClipKeyframe("kf2", offsetMs = 2_000L, scale = 1.25f, offsetX = 0.15f, opacity = 0.9f),
                    ),
                ),
            ),
            TimelineClip(
              clipId = "c_adj",
              trackId = "adj1",
              kind = ClipKind.ADJUSTMENT,
              label = "IA Color",
              timelineStartMs = 0L,
              timelineEndMs = 4_000L,
              adjustmentConfig =
                AdjustmentClipConfig(
                  brightness = 0.12f,
                  contrast = 1.15f,
                  saturation = 1.20f,
                  temperature = 0.25f,
                ),
            ),
          ),
      )

    val exportResult =
      CompositionCompiler().compile(
        timeline = timeline,
        assets = listOf(asset),
        projectRootDir = rootDir,
        renderTarget = MediaRenderTarget.Export(ExportPreset.HD_1080P_H264.config),
      ) as CompiledCompositionResult.Ready

    val videoItem = exportResult.composition.sequences.first().editedMediaItems.first()
    assertEquals(1_200L, videoItem.mediaItem.clippingConfiguration.startPositionMs)
    assertEquals(4_700L, videoItem.mediaItem.clippingConfiguration.endPositionMs)
    // MatrixTransformation + RgbAdjustment(opacity) + Brightness + Contrast + HslAdjustment + RgbAdjustment(temp) = 6
    assertEquals(6, exportResult.appliedVideoEffectCount)
  }
}
