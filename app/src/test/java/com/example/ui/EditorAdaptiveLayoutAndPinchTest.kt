package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ergonomics.TimelineViewportErgonomics
import com.example.ui.editor.EditorScreen
import com.example.ui.editor.EditorToolSubPanel
import com.example.ui.editor.TimelineSkeletonViewModel
import com.example.ui.theme.FrameLineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1024dp-h1024dp")
class EditorAdaptiveLayoutAndPinchTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `editor adapts across portrait landscape and wide layouts while keeping state and timeline visible`() {
    val tempRoot = Files.createTempDirectory("frameline_adaptive_ui_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.selectClip("clip_v1")
      vm.seekPlayhead(1_800L)
      vm.updateSelectedClipColorParams(brightness = 0.22f, fromSliderDrag = false)
      vm.setTimelineZoom(1.45f)
      vm.toggleSubPanel(EditorToolSubPanel.ADJUSTMENTS)

      var viewportSize by mutableStateOf(DpSize(411.dp, 820.dp))

      composeRule.setContent {
        FrameLineTheme {
          val uiState by vm.uiState.collectAsStateWithLifecycle()
          val previewState by vm.previewState.collectAsStateWithLifecycle()
          Box(modifier = Modifier.size(viewportSize.width, viewportSize.height)) {
            EditorScreen(
              uiState = uiState,
              previewState = previewState,
              viewModel = vm,
              onOpenMediaPicker = {},
            )
          }
        }
      }

      composeRule.waitForIdle()
      // Portrait with Inspector open: timeline deck stays generous and inspector + action deck are displayed
      composeRule.onNodeWithTag("editor_portrait_layout").assertIsDisplayed()
      composeRule.onNodeWithTag("adjustments_subpanel").assertIsDisplayed()
      composeRule.onNodeWithTag("multitrack_timeline_deck").assertIsDisplayed()
      composeRule.onNodeWithTag("split_button").assertIsDisplayed()
      val portraitTimelineBounds =
        composeRule.onNodeWithTag("multitrack_timeline_deck").fetchSemanticsNode().boundsInRoot
      assertTrue(
        "Portrait timeline height should not be crushed (was ${portraitTimelineBounds.height}px)",
        portraitTimelineBounds.height >= 80f,
      )

      // Rotate / resize to phone Landscape (rail takes ~80dp, EditorScreen gets ~760x390dp)
      composeRule.runOnUiThread {
        viewportSize = DpSize(760.dp, 390.dp)
      }
      composeRule.waitForIdle()

      composeRule.onNodeWithTag("editor_landscape_layout").assertIsDisplayed()
      composeRule.onNodeWithTag("compact_landscape_transport_strip").assertIsDisplayed()
      composeRule.onNodeWithTag("adjustments_subpanel").assertIsDisplayed()
      composeRule.onNodeWithTag("multitrack_timeline_deck").assertIsDisplayed()
      composeRule.onNodeWithTag("preview_play_pause_button").assertIsDisplayed()
      composeRule.onNodeWithTag("zoom_fit_button").assertIsDisplayed()
      composeRule.onNodeWithTag("split_button").assertIsDisplayed()

      val landscapeTimelineBounds =
        composeRule.onNodeWithTag("multitrack_timeline_deck").fetchSemanticsNode().boundsInRoot
      assertTrue(
        "Landscape timeline height must remain usable (was ${landscapeTimelineBounds.height}px)",
        landscapeTimelineBounds.height >= 100f,
      )
      assertTrue(
        "Landscape timeline width should expand to use extra width (was ${landscapeTimelineBounds.width}px)",
        landscapeTimelineBounds.width > portraitTimelineBounds.width,
      )

      // State preserved across portrait -> landscape transition
      assertEquals(1_800L, vm.uiState.value.timeline.playheadMs)
      assertEquals("clip_v1", vm.uiState.value.selectedClipId)
      assertEquals(0.22f, vm.uiState.value.selectedClip!!.videoConfig!!.brightness, 0.001f)
      assertEquals(1.45f, vm.uiState.value.ergonomics.zoomScale, 0.001f)
      assertEquals(EditorToolSubPanel.ADJUSTMENTS, vm.uiState.value.activeSubPanel)

      // Expand to Wide Tablet Landscape (960 x 620 dp): all 3 upper panes + timeline visible
      composeRule.runOnUiThread {
        viewportSize = DpSize(960.dp, 620.dp)
      }
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("editor_landscape_layout").assertIsDisplayed()
      composeRule.onNodeWithTag("adjustments_subpanel").assertIsDisplayed()
      composeRule.onNodeWithTag("zoom_fit_button").assertIsDisplayed()
      composeRule.onNodeWithTag("multitrack_timeline_deck").assertIsDisplayed()

      // Return to Portrait: all editor state remains intact
      composeRule.runOnUiThread {
        viewportSize = DpSize(411.dp, 820.dp)
      }
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("editor_portrait_layout").assertIsDisplayed()
      assertEquals(1_800L, vm.uiState.value.timeline.playheadMs)
      assertEquals("clip_v1", vm.uiState.value.selectedClipId)
      assertEquals(1.45f, vm.uiState.value.ergonomics.zoomScale, 0.001f)
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `two finger pinch zooms timeline smoothly without interfering with trim or playhead scrub`() {
    val tempRoot = Files.createTempDirectory("frameline_pinch_ui_").toFile()
    try {
      val vm = TimelineSkeletonViewModel(projectRootDir = tempRoot)
      vm.selectClip("clip_v1")
      val initialPlayheadMs = vm.uiState.value.timeline.playheadMs
      val initialClipStartMs = vm.uiState.value.selectedClip!!.timelineStartMs
      assertEquals(1.0f, vm.uiState.value.ergonomics.zoomScale, 0.001f)

      composeRule.setContent {
        FrameLineTheme {
          val uiState by vm.uiState.collectAsStateWithLifecycle()
          val previewState by vm.previewState.collectAsStateWithLifecycle()
          Box(modifier = Modifier.size(440.dp, 840.dp)) {
            EditorScreen(
              uiState = uiState,
              previewState = previewState,
              viewModel = vm,
              onOpenMediaPicker = {},
            )
          }
        }
      }

      composeRule.waitForIdle()

      // 1. Two-finger pinch out on the timeline deck -> zooms in without moving playhead or trimming clip
      composeRule.onNodeWithTag("multitrack_timeline_deck").performTouchInput {
        val c = center
        pinch(
          start0 = c - Offset(50f, 0f),
          end0 = c - Offset(160f, 0f),
          start1 = c + Offset(50f, 0f),
          end1 = c + Offset(160f, 0f),
        )
      }
      composeRule.waitForIdle()

      val zoomedInScale = vm.uiState.value.ergonomics.zoomScale
      assertTrue("Expected pinch-out to increase zoomScale above 1.0 (was $zoomedInScale)", zoomedInScale > 1.2f)
      assertEquals(initialPlayheadMs, vm.uiState.value.timeline.playheadMs)
      assertEquals(initialClipStartMs, vm.uiState.value.selectedClip!!.timelineStartMs)

      // Quick reset pill appears on the timeline when zoomed and resets cleanly to 100%
      composeRule.onNodeWithTag("timeline_zoom_reset_pill").assertIsDisplayed().performClick()
      composeRule.waitForIdle()
      assertEquals(1.0f, vm.uiState.value.ergonomics.zoomScale, 0.001f)

      // 2. Single-finger drag on trim_handle_start_clip_v1 trims clip without changing zoom
      composeRule.onNodeWithTag("trim_handle_start_clip_v1").performTouchInput {
        swipeRight(startX = centerX, endX = centerX + 80f)
      }
      composeRule.waitForIdle()
      assertEquals(1.0f, vm.uiState.value.ergonomics.zoomScale, 0.001f)
      assertTrue(
        "Single-finger drag on trim handle must still trim clip",
        vm.uiState.value.selectedClip!!.timelineStartMs > initialClipStartMs,
      )

      // 3. Single-finger drag on timeline_playhead_handle scrubs playhead without changing zoom
      val playheadBeforeScrub = vm.uiState.value.timeline.playheadMs
      composeRule.onNodeWithTag("timeline_playhead_handle").performTouchInput {
        swipeRight(startX = centerX, endX = centerX + 90f)
      }
      composeRule.waitForIdle()
      assertEquals(1.0f, vm.uiState.value.ergonomics.zoomScale, 0.001f)
      assertTrue(
        "Single-finger drag on playhead handle must scrub playhead forward",
        vm.uiState.value.timeline.playheadMs > playheadBeforeScrub,
      )

      // 4. Quick Fit Zoom pill adjusts zoom within valid bounds
      composeRule.onNodeWithTag("timeline_zoom_fit_pill").performClick()
      composeRule.waitForIdle()
      val fitScale = vm.uiState.value.ergonomics.zoomScale
      assertTrue(fitScale in TimelineViewportErgonomics.MIN_ZOOM..TimelineViewportErgonomics.MAX_ZOOM)
      assertTrue("Fit zoom should adjust scale from 1.0f (was $fitScale)", kotlin.math.abs(fitScale - 1.0f) > 0.05f)
    } finally {
      tempRoot.deleteRecursively()
    }
  }
}
