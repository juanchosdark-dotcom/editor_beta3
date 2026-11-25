package com.example.core.ai

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import com.example.core.ai.api.LocalAiSettings
import com.example.core.ai.api.ThermalTier
import com.example.core.ai.local.DeviceResourceGuard
import com.example.core.ai.local.LocalRhythmAndSilenceAssistant
import com.example.core.ergonomics.InputDeviceMode
import com.example.core.ergonomics.KeyboardShortcutMapper
import com.example.core.ergonomics.ShortcutAction
import com.example.core.ergonomics.TimelineErgonomicsHelper
import com.example.core.ergonomics.TimelineViewportErgonomics
import com.example.core.model.ClipKind
import com.example.core.model.MarkerColor
import com.example.core.model.TimelineClip
import com.example.core.model.TimelineMarker
import com.example.core.model.TimelineState
import com.example.core.model.Track
import com.example.core.model.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiAndErgonomicsTest {

  @Test
  fun `resource guard blocks when module disabled or thermal severe or low memory`() {
    val disabledReason =
      DeviceResourceGuard.evaluatePolicy(
        settings = LocalAiSettings(enabled = false),
        thermalTier = ThermalTier.NORMAL,
        isLowMemory = false,
        isPowerSave = false,
        batteryPercent = 90,
      )
    assertNotNull(disabledReason)

    val thermalBlocked =
      DeviceResourceGuard.evaluatePolicy(
        settings = LocalAiSettings(enabled = true, maxAllowedThermalTier = ThermalTier.MODERATE),
        thermalTier = ThermalTier.SEVERE,
        isLowMemory = false,
        isPowerSave = false,
        batteryPercent = 85,
      )
    assertNotNull(thermalBlocked)

    val lowMemBlocked =
      DeviceResourceGuard.evaluatePolicy(
        settings = LocalAiSettings(enabled = true),
        thermalTier = ThermalTier.NORMAL,
        isLowMemory = true,
        isPowerSave = false,
        batteryPercent = 85,
      )
    assertNotNull(lowMemBlocked)
  }

  @Test
  fun `resource guard respects battery saver override and allows normal conditions`() {
    val blockedOnSaver =
      DeviceResourceGuard.evaluatePolicy(
        settings = LocalAiSettings(enabled = true, allowOnBatterySaver = false),
        thermalTier = ThermalTier.MODERATE,
        isLowMemory = false,
        isPowerSave = true,
        batteryPercent = 60,
      )
    assertNotNull(blockedOnSaver)

    val allowedWithOverride =
      DeviceResourceGuard.evaluatePolicy(
        settings = LocalAiSettings(enabled = true, allowOnBatterySaver = true),
        thermalTier = ThermalTier.MODERATE,
        isLowMemory = false,
        isPowerSave = true,
        batteryPercent = 10,
      )
    assertNull(allowedWithOverride)
  }

  @Test
  fun `beat onset detector finds strong peaks and enforces cooldown spacing`() {
    // 40 windows of 20ms = 800ms; baseline RMS = 0.05f with spikes at 300ms, 340ms (within cooldown), and 700ms
    val windows =
      (0 until 40).map { idx ->
        val ts = idx * 20L
        val rms =
          when (ts) {
            300L -> 0.35f
            340L -> 0.40f // skipped because minBeatSpacingMs = 350ms
            700L -> 0.38f
            else -> 0.05f
          }
        ts to rms
      }

    val beats =
      LocalRhythmAndSilenceAssistant.detectBeatOnsetsFromRmsWindows(
        rmsWindows = windows,
        sensitivityMultiplier = 1.65f,
        minBeatSpacingMs = 350L,
      )

    assertEquals(2, beats.size)
    assertEquals(300L, beats[0].positionMs)
    assertEquals(700L, beats[1].positionMs)
    assertTrue(beats[0].strength > 1.65f)
  }

  @Test
  fun `keyboard shortcut mapper resolves timeline actions accurately`() {
    assertEquals(
      ShortcutAction.PLAY_PAUSE,
      KeyboardShortcutMapper.mapKey(Key.Spacebar, isCtrlPressed = false, isShiftPressed = false),
    )
    assertEquals(
      ShortcutAction.SPLIT_AT_PLAYHEAD,
      KeyboardShortcutMapper.mapKey(Key.B, isCtrlPressed = true, isShiftPressed = false),
    )
    assertEquals(
      ShortcutAction.SPLIT_AT_PLAYHEAD,
      KeyboardShortcutMapper.mapKey(Key.S, isCtrlPressed = false, isShiftPressed = false),
    )
    assertEquals(
      ShortcutAction.UNDO,
      KeyboardShortcutMapper.mapKey(Key.Z, isCtrlPressed = true, isShiftPressed = false),
    )
    assertEquals(
      ShortcutAction.STEP_BACK_FRAME,
      KeyboardShortcutMapper.mapKey(Key.DirectionLeft, isCtrlPressed = false, isShiftPressed = true),
    )
    assertEquals(
      ShortcutAction.STEP_FORWARD_COARSE,
      KeyboardShortcutMapper.mapKey(Key.L, isCtrlPressed = false, isShiftPressed = false),
    )
    assertEquals(
      ShortcutAction.DELETE_CLIP,
      KeyboardShortcutMapper.mapKey(Key.Delete, isCtrlPressed = false, isShiftPressed = false),
    )
    assertEquals(
      ShortcutAction.ZOOM_IN,
      KeyboardShortcutMapper.mapKey(Key.Plus, isCtrlPressed = false, isShiftPressed = false),
    )
  }

  @Test
  fun `ergonomics helper scales stylus scrubbing and snaps to markers and clip boundaries`() {
    val state =
      TimelineState(
        tracks = listOf(Track(trackId = "t1", type = TrackType.VIDEO, order = 0, name = "V1")),
        clips =
          listOf(
            TimelineClip(
              clipId = "c1",
              trackId = "t1",
              kind = ClipKind.VIDEO,
              label = "Clip 1",
              timelineStartMs = 1_000L,
              timelineEndMs = 4_000L,
            ),
          ),
        markers =
          listOf(
            TimelineMarker(
              markerId = "m1",
              positionMs = 2_500L,
              label = "Beat",
              color = MarkerColor.AMBER,
            ),
          ),
      )

    // Within 160ms touch threshold -> snaps to marker 2500ms
    val snappedTouch =
      TimelineErgonomicsHelper.applySnapIfEnabled(
        rawPositionMs = 2_420L,
        state = state,
        thresholdMs = InputDeviceMode.TOUCH.snapThresholdMs,
        snapEnabled = true,
      )
    assertEquals(2_500L, snappedTouch)

    // Outside 45ms stylus threshold -> stays precise at 2420ms
    val preciseStylus =
      TimelineErgonomicsHelper.applySnapIfEnabled(
        rawPositionMs = 2_420L,
        state = state,
        thresholdMs = InputDeviceMode.STYLUS.snapThresholdMs,
        snapEnabled = true,
      )
    assertEquals(2_420L, preciseStylus)

    // Stylus scales drag delta by 0.25x compared to touch 1.0x
    val touchDeltaMs =
      TimelineErgonomicsHelper.pointerDeltaPxToTimelineMs(
        deltaPx = 100f,
        pxPerSecond = 100f,
        inputMode = InputDeviceMode.TOUCH,
      )
    val stylusDeltaMs =
      TimelineErgonomicsHelper.pointerDeltaPxToTimelineMs(
        deltaPx = 100f,
        pxPerSecond = 100f,
        inputMode = InputDeviceMode.STYLUS,
      )
    assertEquals(1_000L, touchDeltaMs)
    assertEquals(250L, stylusDeltaMs)

    assertEquals(
      InputDeviceMode.STYLUS,
      InputDeviceMode.fromPointerType(PointerType.Stylus),
    )
    assertEquals(
      InputDeviceMode.STYLUS,
      InputDeviceMode.fromPointerType(PointerType.Eraser),
    )
    assertEquals(
      TimelineViewportErgonomics.MAX_ZOOM,
      TimelineErgonomicsHelper.clampZoom(3.5f, 2.0f),
      0.001f,
    )
  }
}
