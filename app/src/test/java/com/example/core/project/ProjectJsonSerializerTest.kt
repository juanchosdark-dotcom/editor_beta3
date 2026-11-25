package com.example.core.project

import com.example.core.model.AdjustmentClipConfig
import com.example.core.model.AudioClipConfig
import com.example.core.model.AudioDuckingConfig
import com.example.core.model.BuiltInLutPresets
import com.example.core.model.BuiltInSubtitleStyles
import com.example.core.model.ClipKeyframe
import com.example.core.model.ClipKind
import com.example.core.model.KeyframeInterpolation
import com.example.core.model.ProjectDocument
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineSequence
import com.example.core.model.TimelineSnapshot
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import com.example.core.model.VideoClipConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectJsonSerializerTest {

  @Test
  fun encodeDecode_preservesSnapshotsSequencesDuckingAndSubtitleStyles() {
    val serializer = ProjectJsonSerializer()
    val timeline =
      TimelineState(
        playheadMs = 1_200L,
        tracks = listOf(Track("t_vid", TrackType.VIDEO, 0, "Video")),
        clips =
          listOf(
            TimelineClip("c1", "t_vid", ClipKind.VIDEO, "Intro", 0L, 3_000L, 0L, 3_000L),
          ),
        duckingConfig = AudioDuckingConfig(enabled = true, attenuationFactor = 0.3f),
      )

    val doc =
      ProjectDocument(
        projectId = "p_1",
        name = "Portable Test",
        createdAtIso = "2026-11-23T10:00:00Z",
        updatedAtIso = "2026-11-23T10:20:00Z",
        timeline = timeline,
        activeSequenceId = "seq_main",
        sequences =
          listOf(TimelineSequence("seq_main", "Principal", "2026-11-23T10:00:00Z", timeline)),
        snapshots =
          listOf(TimelineSnapshot("snap_1", "Checkpoint 1", "2026-11-23T10:15:00Z", "", timeline)),
        savedSubtitleStyles = listOf(BuiltInSubtitleStyles.HIGH_CONTRAST_AMBER),
      )

    val json = serializer.encode(doc)
    val decoded = serializer.decode(json)

    assertTrue(decoded.isSuccess)
    val value = decoded.getOrThrow()
    assertEquals(doc.projectId, value.projectId)
    assertEquals(1, value.snapshots.size)
    assertEquals(1, value.sequences.size)
    assertTrue(value.timeline.duckingConfig.enabled)
    assertEquals(0.3f, value.timeline.duckingConfig.attenuationFactor, 0.001f)
    assertEquals(1, value.savedSubtitleStyles.size)
  }

  @Test
  fun decode_legacyJsonWithoutKeyframeVolumeInterpolationOrAudioKeyframes_staysCompatible() {
    val legacyJson =
      """
      {
        "schemaVersion": 1,
        "projectId": "legacy_proj",
        "name": "Legacy Project",
        "createdAtIso": "2025-05-01T10:00:00Z",
        "updatedAtIso": "2025-05-01T10:05:00Z",
        "timeline": {
          "playheadMs": 500,
          "tracks": [
            {"trackId": "t_vid", "type": "VIDEO", "order": 0, "name": "Video"},
            {"trackId": "t_aud", "type": "AUDIO", "order": 1, "name": "Audio"}
          ],
          "clips": [
            {
              "clipId": "c_vid_legacy",
              "trackId": "t_vid",
              "kind": "VIDEO",
              "label": "Clip Antiguo",
              "timelineStartMs": 0,
              "timelineEndMs": 4000,
              "sourceInMs": 0,
              "sourceOutMs": 4000,
              "videoConfig": {
                "volume": 0.45,
                "keyframes": [
                  {"keyframeId": "k1", "offsetMs": 0, "scale": 1.0, "opacity": 0.8, "offsetX": 0.0, "offsetY": 0.0, "rotationDegrees": 0.0},
                  {"keyframeId": "k2", "offsetMs": 2000, "scale": 1.5, "opacity": 1.0, "offsetX": 0.2, "offsetY": -0.1, "rotationDegrees": 15.0}
                ]
              }
            },
            {
              "clipId": "c_aud_legacy",
              "trackId": "t_aud",
              "kind": "AUDIO",
              "label": "Audio Antiguo",
              "timelineStartMs": 0,
              "timelineEndMs": 4000,
              "sourceInMs": 0,
              "sourceOutMs": 4000,
              "audioConfig": {
                "volume": 0.75,
                "fadeInMs": 200
              }
            }
          ]
        }
      }
      """.trimIndent()

    val serializer = ProjectJsonSerializer()
    val decoded = serializer.decode(legacyJson).getOrThrow()
    val vidClip = decoded.timeline.clips.first { it.clipId == "c_vid_legacy" }
    val audClip = decoded.timeline.clips.first { it.clipId == "c_aud_legacy" }

    val kfs = vidClip.videoConfig!!.keyframes
    assertEquals(2, kfs.size)
    assertNull(kfs[0].volume)
    assertEquals(KeyframeInterpolation.LINEAR, kfs[0].interpolation)

    // Legacy keyframes without explicit volume must preserve base videoConfig.volume (0.45)
    val evalMid = vidClip.evaluatePropertiesAt(1_000L)
    assertEquals(1.25f, evalMid.scale, 0.001f)
    assertEquals(0.9f, evalMid.opacity, 0.001f)
    assertEquals(0.1f, evalMid.offsetX, 0.001f)
    assertEquals(-0.05f, evalMid.offsetY, 0.001f)
    assertEquals(7.5f, evalMid.rotationDegrees, 0.001f)
    assertEquals(0.45f, evalMid.volume, 0.001f)

    assertTrue(audClip.audioConfig!!.keyframes.isEmpty())
    assertEquals(0.75f, audClip.evaluatePropertiesAt(1_000L).volume, 0.001f)
  }

  @Test
  fun encodeDecode_roundTripsAllKeyframePropertiesAndInterpolations() {
    val serializer = ProjectJsonSerializer()
    val timeline =
      TimelineState(
        tracks = listOf(Track("t_vid", TrackType.VIDEO, 0, "Video"), Track("t_aud", TrackType.AUDIO, 1, "Audio")),
        clips =
          listOf(
            TimelineClip(
              clipId = "c_v",
              trackId = "t_vid",
              kind = ClipKind.VIDEO,
              label = "V1",
              timelineStartMs = 0L,
              timelineEndMs = 4_000L,
              sourceInMs = 0L,
              sourceOutMs = 4_000L,
              videoConfig =
                VideoClipConfig(
                  keyframes =
                    listOf(
                      ClipKeyframe(
                        keyframeId = "kv1",
                        offsetMs = 0L,
                        scale = 1.0f,
                        opacity = 0.5f,
                        offsetX = -0.25f,
                        offsetY = 0.1f,
                        rotationDegrees = -15f,
                        volume = 0.6f,
                        interpolation = KeyframeInterpolation.EASE_IN_OUT,
                      ),
                      ClipKeyframe(
                        keyframeId = "kv2",
                        offsetMs = 3_000L,
                        scale = 1.6f,
                        opacity = 1.0f,
                        offsetX = 0.35f,
                        offsetY = -0.2f,
                        rotationDegrees = 45f,
                        volume = 1.4f,
                        interpolation = KeyframeInterpolation.EASE_OUT,
                      ),
                    ),
                ),
            ),
            TimelineClip(
              clipId = "c_a",
              trackId = "t_aud",
              kind = ClipKind.AUDIO,
              label = "A1",
              timelineStartMs = 0L,
              timelineEndMs = 4_000L,
              sourceInMs = 0L,
              sourceOutMs = 4_000L,
              audioConfig =
                AudioClipConfig(
                  volume = 1.0f,
                  keyframes =
                    listOf(
                      ClipKeyframe("ka1", offsetMs = 500L, volume = 0.2f, interpolation = KeyframeInterpolation.EASE_IN),
                      ClipKeyframe("ka2", offsetMs = 2_500L, volume = 1.5f, interpolation = KeyframeInterpolation.LINEAR),
                    ),
                ),
            ),
          ),
      )

    val doc =
      ProjectDocument(
        projectId = "p_kf",
        name = "Keyframes RoundTrip",
        createdAtIso = "2026-11-24T10:00:00Z",
        updatedAtIso = "2026-11-24T10:00:00Z",
        timeline = timeline,
      )

    val decoded = serializer.decode(serializer.encode(doc)).getOrThrow()
    val kv1 = decoded.timeline.clips.first { it.clipId == "c_v" }.keyframes().first()
    val ka1 = decoded.timeline.clips.first { it.clipId == "c_a" }.keyframes().first()

    assertEquals(KeyframeInterpolation.EASE_IN_OUT, kv1.interpolation)
    assertEquals(-0.25f, kv1.offsetX, 0.001f)
    assertEquals(0.1f, kv1.offsetY, 0.001f)
    assertEquals(-15f, kv1.rotationDegrees, 0.001f)
    assertEquals(0.6f, kv1.volume ?: -1f, 0.001f)
    assertEquals(KeyframeInterpolation.EASE_IN, ka1.interpolation)
    assertEquals(0.2f, ka1.volume ?: -1f, 0.001f)
  }

  @Test
  fun decode_legacyJsonWithoutTintLiftGammaGainOrBlendOpacity_usesNeutralDefaults() {
    val legacyColorJson =
      """
      {
        "schemaVersion": 1,
        "projectId": "legacy_color",
        "name": "Legacy Color",
        "createdAtIso": "2025-06-01T10:00:00Z",
        "updatedAtIso": "2025-06-01T10:05:00Z",
        "timeline": {
          "tracks": [
            {"trackId": "t_vid", "type": "VIDEO", "order": 0, "name": "Video"},
            {"trackId": "t_adj", "type": "ADJUSTMENT", "order": 1, "name": "LUT"}
          ],
          "clips": [
            {
              "clipId": "v_old",
              "trackId": "t_vid",
              "kind": "VIDEO",
              "label": "Video Old",
              "timelineStartMs": 0,
              "timelineEndMs": 3000,
              "sourceInMs": 0,
              "sourceOutMs": 3000,
              "videoConfig": {
                "brightness": 0.1,
                "contrast": 1.2,
                "saturation": 1.1,
                "temperature": -0.2
              }
            },
            {
              "clipId": "adj_old",
              "trackId": "t_adj",
              "kind": "ADJUSTMENT",
              "label": "LUT Old",
              "timelineStartMs": 0,
              "timelineEndMs": 3000,
              "adjustmentConfig": {
                "exposure": 0.25,
                "lutPresetId": "teal_orange",
                "lutStrength": 0.8
              }
            }
          ]
        }
      }
      """.trimIndent()

    val serializer = ProjectJsonSerializer()
    val decoded = serializer.decode(legacyColorJson).getOrThrow()
    val vCfg = decoded.timeline.clips.first { it.clipId == "v_old" }.videoConfig!!
    val aCfg = decoded.timeline.clips.first { it.clipId == "adj_old" }.adjustmentConfig!!

    assertEquals(0.1f, vCfg.brightness, 0.001f)
    assertEquals(0.0f, vCfg.tint, 0.001f)
    assertEquals(0.0f, vCfg.shadows, 0.001f)
    assertEquals(0.0f, vCfg.midtones, 0.001f)
    assertEquals(0.0f, vCfg.highlights, 0.001f)

    assertEquals(0.25f, aCfg.exposure, 0.001f)
    assertEquals("teal_orange", aCfg.lutPresetId)
    assertEquals(0.8f, aCfg.lutStrength, 0.001f)
    assertEquals(0.0f, aCfg.tint, 0.001f)
    assertEquals(0.0f, aCfg.shadows, 0.001f)
    assertEquals(0.0f, aCfg.midtones, 0.001f)
    assertEquals(0.0f, aCfg.highlights, 0.001f)
    assertEquals(1.0f, aCfg.blendOpacity, 0.001f)
  }

  @Test
  fun encodeDecode_roundTripsAllParametricColorAndLiftGammaGainFields() {
    val serializer = ProjectJsonSerializer()
    val timeline =
      TimelineState(
        tracks =
          listOf(
            Track("t_vid", TrackType.VIDEO, 0, "Video"),
            Track("t_adj", TrackType.ADJUSTMENT, 1, "LUT"),
          ),
        clips =
          listOf(
            TimelineClip(
              clipId = "v_graded",
              trackId = "t_vid",
              kind = ClipKind.VIDEO,
              label = "Graded Clip",
              timelineStartMs = 0L,
              timelineEndMs = 5_000L,
              sourceInMs = 0L,
              sourceOutMs = 5_000L,
              videoConfig =
                VideoClipConfig(
                  brightness = -0.15f,
                  contrast = 1.28f,
                  saturation = 1.18f,
                  exposure = 0.35f,
                  temperature = -0.22f,
                  vignette = 0.4f,
                  lutPresetId = BuiltInLutPresets.FILM_2383.id,
                  lutStrength = 0.65f,
                  tint = 0.31f,
                  shadows = -0.25f,
                  midtones = 0.19f,
                  highlights = 0.42f,
                ),
            ),
            TimelineClip(
              clipId = "adj_graded",
              trackId = "t_adj",
              kind = ClipKind.ADJUSTMENT,
              label = "Master Look",
              timelineStartMs = 0L,
              timelineEndMs = 5_000L,
              adjustmentConfig =
                AdjustmentClipConfig(
                  brightness = 0.08f,
                  contrast = 1.12f,
                  saturation = 0.92f,
                  exposure = -0.18f,
                  temperature = 0.27f,
                  vignette = 0.15f,
                  lutPresetId = BuiltInLutPresets.GOLDEN_HOUR.id,
                  lutStrength = 0.85f,
                  tint = -0.44f,
                  shadows = 0.33f,
                  midtones = -0.21f,
                  highlights = 0.16f,
                  blendOpacity = 0.72f,
                ),
            ),
          ),
      )

    val doc =
      ProjectDocument(
        projectId = "p_color_rt",
        name = "Color RoundTrip",
        createdAtIso = "2026-11-24T12:00:00Z",
        updatedAtIso = "2026-11-24T12:00:00Z",
        timeline = timeline,
      )

    val decoded = serializer.decode(serializer.encode(doc)).getOrThrow()
    val v = decoded.timeline.clips.first { it.clipId == "v_graded" }.videoConfig!!
    val a = decoded.timeline.clips.first { it.clipId == "adj_graded" }.adjustmentConfig!!

    assertEquals(-0.15f, v.brightness, 0.001f)
    assertEquals(1.28f, v.contrast, 0.001f)
    assertEquals(1.18f, v.saturation, 0.001f)
    assertEquals(0.35f, v.exposure, 0.001f)
    assertEquals(-0.22f, v.temperature, 0.001f)
    assertEquals(BuiltInLutPresets.FILM_2383.id, v.lutPresetId)
    assertEquals(0.65f, v.lutStrength, 0.001f)
    assertEquals(0.31f, v.tint, 0.001f)
    assertEquals(-0.25f, v.shadows, 0.001f)
    assertEquals(0.19f, v.midtones, 0.001f)
    assertEquals(0.42f, v.highlights, 0.001f)

    assertEquals(0.08f, a.brightness, 0.001f)
    assertEquals(-0.44f, a.tint, 0.001f)
    assertEquals(0.33f, a.shadows, 0.001f)
    assertEquals(-0.21f, a.midtones, 0.001f)
    assertEquals(0.16f, a.highlights, 0.001f)
    assertEquals(0.72f, a.blendOpacity, 0.001f)
  }
}
