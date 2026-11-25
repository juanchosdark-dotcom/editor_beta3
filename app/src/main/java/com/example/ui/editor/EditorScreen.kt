package com.example.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.ergonomics.KeyboardShortcutMapper
import com.example.core.media.playback.PreviewPlayerState
import com.example.core.model.ClipKind

@Composable
fun EditorScreen(
  uiState: TimelineSkeletonUiState,
  previewState: PreviewPlayerState,
  viewModel: TimelineSkeletonViewModel,
  onOpenMediaPicker: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val markerDefaultPrefix = stringResource(R.string.label_marker_default)

  val visibleTrackIds =
    uiState.timeline.tracks.filter { it.isVisible }.mapTo(mutableSetOf()) { it.trackId }
  val activeVideoClip =
    uiState.selectedClip?.takeIf { it.kind == ClipKind.VIDEO && it.trackId in visibleTrackIds }
      ?: uiState.timeline
        .activeClipsAt(uiState.timeline.playheadMs)
        .firstOrNull { it.kind == ClipKind.VIDEO && it.trackId in visibleTrackIds }
  val activeSubtitleText =
    uiState.timeline
      .activeClipsAt(uiState.timeline.playheadMs)
      .firstOrNull { it.kind == ClipKind.SUBTITLE && it.trackId in visibleTrackIds }
      ?.subtitleConfig
      ?.text
  val activeAdjustmentClip =
    uiState.selectedClip?.takeIf { it.kind == ClipKind.ADJUSTMENT && it.trackId in visibleTrackIds }
      ?: uiState.timeline
        .activeClipsAt(uiState.timeline.playheadMs)
        .firstOrNull { it.kind == ClipKind.ADJUSTMENT && it.trackId in visibleTrackIds }
  val keyframeableClip =
    uiState.selectedClip?.takeIf { it.kind == ClipKind.VIDEO || it.kind == ClipKind.AUDIO }
  val hasActiveSubPanel =
    uiState.activeSubPanel != EditorToolSubPanel.NONE &&
      (uiState.activeSubPanel != EditorToolSubPanel.KEYFRAMES || keyframeableClip != null)

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .focusable()
        .onPreviewKeyEvent { keyEvent ->
          if (keyEvent.type == KeyEventType.KeyDown) {
            val action =
              KeyboardShortcutMapper.mapKey(
                key = keyEvent.key,
                isCtrlPressed = keyEvent.isCtrlPressed,
                isShiftPressed = keyEvent.isShiftPressed,
              )
            if (action != null) {
              viewModel.handleShortcutAction(action)
              true
            } else {
              false
            }
          } else {
            false
          }
        },
  ) {
    val isLandscapeLayout = maxWidth > maxHeight && maxWidth >= 520.dp
    val isWideThreePane = isLandscapeLayout && maxWidth >= 880.dp && maxHeight >= 400.dp

    Column(modifier = Modifier.fillMaxSize()) {
      StudioHeaderBar(
        projectName = uiState.projectName,
        playheadMs = uiState.timeline.playheadMs,
        totalDurationMs = uiState.timeline.totalDurationMs,
        inputMode = uiState.ergonomics.inputMode,
        renderSettings = uiState.renderSettings,
        isLoopEnabled = uiState.isPreviewLoopEnabled || previewState.isLoopEnabled,
        canUndo = uiState.canUndo,
        canRedo = uiState.canRedo,
        onUndo = viewModel::undo,
        onRedo = viewModel::redo,
        onSetPreviewQuality = viewModel::setPreviewQuality,
        onToggleProxies = viewModel::toggleUseProxiesForPreview,
        onToggleLoop = viewModel::togglePreviewLoop,
        onOpenShortcuts = { viewModel.setShortcutsDialogOpen(true) },
        compactSingleRow = isLandscapeLayout,
      )

      BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        val bodyWidth = maxWidth
        val bodyHeight = maxHeight
        val estimatedTimelineViewportDp = (bodyWidth - 136.dp).coerceAtLeast(180.dp)
        val fitTimelineZoom = { viewModel.fitTimelineZoom(estimatedTimelineViewportDp.value) }

        if (isLandscapeLayout) {
          val upperRowHeight =
            when {
              isWideThreePane -> (bodyHeight * 0.48f).coerceIn(190.dp, 340.dp)
              hasActiveSubPanel -> (bodyHeight * 0.54f).coerceIn(154.dp, 310.dp)
              else -> (bodyHeight * 0.48f).coerceIn(132.dp, 280.dp)
            }

          Column(
            modifier = Modifier.fillMaxSize().testTag("editor_landscape_layout"),
          ) {
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .height(upperRowHeight)
                  .padding(horizontal = 4.dp, vertical = 2.dp),
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              if (isWideThreePane && hasActiveSubPanel) {
                Media3PreviewPlayerCard(
                  previewController = viewModel.previewController,
                  previewState = previewState,
                  deviceProfile = uiState.deviceProfile,
                  activeVideoClip = activeVideoClip,
                  activeAdjustmentClip = activeAdjustmentClip,
                  activeSubtitleText = activeSubtitleText,
                  playheadMs = uiState.timeline.playheadMs,
                  onRetryPreview = viewModel::retryPreviewPlayback,
                  onDismissError = viewModel::dismissPreviewError,
                  onOpenMediaPicker = onOpenMediaPicker,
                  fillAvailableHeight = true,
                  compactDiagnostics = true,
                  outerHorizontalPadding = 2.dp,
                  outerVerticalPadding = 0.dp,
                  modifier = Modifier.weight(0.32f),
                )

                ActivePreviewSummaryCard(
                  uiState = uiState,
                  previewState = previewState,
                  onBeginScrub = viewModel::beginTimelineScrub,
                  onPlayheadScrub = viewModel::scrubPlayheadWithSnap,
                  onEndScrub = viewModel::endTimelineScrub,
                  onSeekToStart = viewModel::seekToStart,
                  onStepBackFrame = viewModel::stepFrameBackward,
                  onTogglePlayPause = viewModel::togglePlayPausePreview,
                  onStepForwardFrame = viewModel::stepFrameForward,
                  onToggleLoop = viewModel::togglePreviewLoop,
                  onPreviousMarker = viewModel::jumpToPreviousMarker,
                  onNextMarker = viewModel::jumpToNextMarker,
                  onCyclePreviewQuality = viewModel::cyclePreviewQuality,
                  onToggleProxies = viewModel::toggleUseProxiesForPreview,
                  onToggleSnap = viewModel::toggleSnapEnabled,
                  onZoomOut = { viewModel.zoomTimelineBy(0.83f) },
                  onZoomIn = { viewModel.zoomTimelineBy(1.2f) },
                  onResetZoom = { viewModel.setTimelineZoom(1.0f) },
                  onFitZoom = fitTimelineZoom,
                  onOpenShortcuts = { viewModel.setShortcutsDialogOpen(true) },
                  onQuickOpenColorLut = { viewModel.openInspectorOrToggleTab(InspectorTab.COLOR_LUT) },
                  compact = true,
                  fillAvailableHeight = true,
                  outerHorizontalPadding = 2.dp,
                  outerVerticalPadding = 0.dp,
                  modifier = Modifier.weight(0.32f),
                )

                ActiveEditorSubPanel(
                  uiState = uiState,
                  viewModel = viewModel,
                  fillAvailableHeight = true,
                  maxContentHeight = upperRowHeight,
                  outerHorizontalPadding = 2.dp,
                  outerVerticalPadding = 0.dp,
                  modifier = Modifier.weight(0.36f),
                )
              } else {
                Column(
                  modifier =
                    Modifier
                      .weight(if (hasActiveSubPanel) 0.44f else 0.48f)
                      .fillMaxHeight(),
                ) {
                  Media3PreviewPlayerCard(
                    previewController = viewModel.previewController,
                    previewState = previewState,
                    deviceProfile = uiState.deviceProfile,
                    activeVideoClip = activeVideoClip,
                    activeAdjustmentClip = activeAdjustmentClip,
                    activeSubtitleText = activeSubtitleText,
                    playheadMs = uiState.timeline.playheadMs,
                    onRetryPreview = viewModel::retryPreviewPlayback,
                    onDismissError = viewModel::dismissPreviewError,
                    onOpenMediaPicker = onOpenMediaPicker,
                    fillAvailableHeight = true,
                    compactDiagnostics = true,
                    outerHorizontalPadding = 2.dp,
                    outerVerticalPadding = 0.dp,
                    modifier = Modifier.weight(1f),
                  )

                  if (hasActiveSubPanel) {
                    CompactLandscapeTransportStrip(
                      uiState = uiState,
                      previewState = previewState,
                      onBeginScrub = viewModel::beginTimelineScrub,
                      onPlayheadScrub = viewModel::scrubPlayheadWithSnap,
                      onEndScrub = viewModel::endTimelineScrub,
                      onSeekToStart = viewModel::seekToStart,
                      onStepBackFrame = viewModel::stepFrameBackward,
                      onTogglePlayPause = viewModel::togglePlayPausePreview,
                      onStepForwardFrame = viewModel::stepFrameForward,
                      onToggleLoop = viewModel::togglePreviewLoop,
                      onToggleSnap = viewModel::toggleSnapEnabled,
                      onResetZoom = { viewModel.setTimelineZoom(1.0f) },
                      onFitZoom = fitTimelineZoom,
                      outerHorizontalPadding = 2.dp,
                    )
                  }
                }

                if (hasActiveSubPanel) {
                  ActiveEditorSubPanel(
                    uiState = uiState,
                    viewModel = viewModel,
                    fillAvailableHeight = true,
                    maxContentHeight = upperRowHeight,
                    outerHorizontalPadding = 2.dp,
                    outerVerticalPadding = 0.dp,
                    modifier = Modifier.weight(0.56f),
                  )
                } else {
                  ActivePreviewSummaryCard(
                    uiState = uiState,
                    previewState = previewState,
                    onBeginScrub = viewModel::beginTimelineScrub,
                    onPlayheadScrub = viewModel::scrubPlayheadWithSnap,
                    onEndScrub = viewModel::endTimelineScrub,
                    onSeekToStart = viewModel::seekToStart,
                    onStepBackFrame = viewModel::stepFrameBackward,
                    onTogglePlayPause = viewModel::togglePlayPausePreview,
                    onStepForwardFrame = viewModel::stepFrameForward,
                    onToggleLoop = viewModel::togglePreviewLoop,
                    onPreviousMarker = viewModel::jumpToPreviousMarker,
                    onNextMarker = viewModel::jumpToNextMarker,
                    onCyclePreviewQuality = viewModel::cyclePreviewQuality,
                    onToggleProxies = viewModel::toggleUseProxiesForPreview,
                    onToggleSnap = viewModel::toggleSnapEnabled,
                    onZoomOut = { viewModel.zoomTimelineBy(0.83f) },
                    onZoomIn = { viewModel.zoomTimelineBy(1.2f) },
                    onResetZoom = { viewModel.setTimelineZoom(1.0f) },
                    onFitZoom = fitTimelineZoom,
                    onOpenShortcuts = { viewModel.setShortcutsDialogOpen(true) },
                    onQuickOpenColorLut = { viewModel.openInspectorOrToggleTab(InspectorTab.COLOR_LUT) },
                    compact = true,
                    fillAvailableHeight = true,
                    outerHorizontalPadding = 2.dp,
                    outerVerticalPadding = 0.dp,
                    modifier = Modifier.weight(0.52f),
                  )
                }
              }
            }

            MultitrackTimelineDeck(
              uiState = uiState,
              onSelectClip = viewModel::selectClip,
              onSelectMarker = viewModel::seekPlayhead,
              onBeginScrub = viewModel::beginTimelineScrub,
              onScrubPlayhead = viewModel::scrubPlayheadWithSnap,
              onEndScrub = viewModel::endTimelineScrub,
              onToggleTrackMute = viewModel::toggleTrackMute,
              onToggleTrackSolo = viewModel::toggleTrackSolo,
              onToggleTrackLocked = viewModel::toggleTrackLocked,
              onToggleTrackVisible = viewModel::toggleTrackVisible,
              onBeginClipTrim = viewModel::beginClipEditTransaction,
              onTrimClipEdge = { clipId, isStartEdge, targetMs ->
                viewModel.trimSelectedClipEdge(
                  clipId = clipId,
                  isStartEdge = isStartEdge,
                  rawTargetMs = targetMs,
                  fromDrag = true,
                )
              },
              onEndClipTrim = viewModel::endClipEditTransaction,
              onPointerInputDetected = viewModel::updateInputMode,
              onHoverTimelineMsChanged = viewModel::updateHoverTimelineMs,
              onZoomBy = viewModel::zoomTimelineBy,
              onSetZoom = viewModel::setTimelineZoom,
              onFitZoom = { widthDp -> viewModel.fitTimelineZoom(widthDp) },
              onEraserTapClip = viewModel::eraserTapClip,
              loadClipThumbnail = viewModel::loadClipThumbnail,
              loadClipWaveform = viewModel::loadClipWaveform,
              outerHorizontalPadding = 6.dp,
              modifier = Modifier.weight(1f),
            )
          }
        } else {
          val previewTargetHeight =
            if (hasActiveSubPanel) {
              (bodyHeight * 0.20f).coerceIn(100.dp, 200.dp)
            } else {
              (bodyHeight * 0.30f).coerceIn(136.dp, 280.dp)
            }
          val subPanelMaxHeight = (bodyHeight * 0.31f).coerceIn(128.dp, 260.dp)

          Column(
            modifier = Modifier.fillMaxSize().testTag("editor_portrait_layout"),
          ) {
            Media3PreviewPlayerCard(
              previewController = viewModel.previewController,
              previewState = previewState,
              deviceProfile = uiState.deviceProfile,
              activeVideoClip = activeVideoClip,
              activeAdjustmentClip = activeAdjustmentClip,
              activeSubtitleText = activeSubtitleText,
              playheadMs = uiState.timeline.playheadMs,
              onRetryPreview = viewModel::retryPreviewPlayback,
              onDismissError = viewModel::dismissPreviewError,
              onOpenMediaPicker = onOpenMediaPicker,
              previewHeight = previewTargetHeight,
              compactDiagnostics = hasActiveSubPanel,
            )

            ActivePreviewSummaryCard(
              uiState = uiState,
              previewState = previewState,
              onBeginScrub = viewModel::beginTimelineScrub,
              onPlayheadScrub = viewModel::scrubPlayheadWithSnap,
              onEndScrub = viewModel::endTimelineScrub,
              onSeekToStart = viewModel::seekToStart,
              onStepBackFrame = viewModel::stepFrameBackward,
              onTogglePlayPause = viewModel::togglePlayPausePreview,
              onStepForwardFrame = viewModel::stepFrameForward,
              onToggleLoop = viewModel::togglePreviewLoop,
              onPreviousMarker = viewModel::jumpToPreviousMarker,
              onNextMarker = viewModel::jumpToNextMarker,
              onCyclePreviewQuality = viewModel::cyclePreviewQuality,
              onToggleProxies = viewModel::toggleUseProxiesForPreview,
              onToggleSnap = viewModel::toggleSnapEnabled,
              onZoomOut = { viewModel.zoomTimelineBy(0.83f) },
              onZoomIn = { viewModel.zoomTimelineBy(1.2f) },
              onResetZoom = { viewModel.setTimelineZoom(1.0f) },
              onFitZoom = fitTimelineZoom,
              onOpenShortcuts = { viewModel.setShortcutsDialogOpen(true) },
              onQuickOpenColorLut = { viewModel.openInspectorOrToggleTab(InspectorTab.COLOR_LUT) },
              compact = bodyHeight < 640.dp || hasActiveSubPanel,
            )

            MultitrackTimelineDeck(
              uiState = uiState,
              onSelectClip = viewModel::selectClip,
              onSelectMarker = viewModel::seekPlayhead,
              onBeginScrub = viewModel::beginTimelineScrub,
              onScrubPlayhead = viewModel::scrubPlayheadWithSnap,
              onEndScrub = viewModel::endTimelineScrub,
              onToggleTrackMute = viewModel::toggleTrackMute,
              onToggleTrackSolo = viewModel::toggleTrackSolo,
              onToggleTrackLocked = viewModel::toggleTrackLocked,
              onToggleTrackVisible = viewModel::toggleTrackVisible,
              onBeginClipTrim = viewModel::beginClipEditTransaction,
              onTrimClipEdge = { clipId, isStartEdge, targetMs ->
                viewModel.trimSelectedClipEdge(
                  clipId = clipId,
                  isStartEdge = isStartEdge,
                  rawTargetMs = targetMs,
                  fromDrag = true,
                )
              },
              onEndClipTrim = viewModel::endClipEditTransaction,
              onPointerInputDetected = viewModel::updateInputMode,
              onHoverTimelineMsChanged = viewModel::updateHoverTimelineMs,
              onZoomBy = viewModel::zoomTimelineBy,
              onSetZoom = viewModel::setTimelineZoom,
              onFitZoom = { widthDp -> viewModel.fitTimelineZoom(widthDp) },
              onEraserTapClip = viewModel::eraserTapClip,
              loadClipThumbnail = viewModel::loadClipThumbnail,
              loadClipWaveform = viewModel::loadClipWaveform,
              modifier = Modifier.weight(1f).heightIn(min = 140.dp),
            )

            if (hasActiveSubPanel) {
              ActiveEditorSubPanel(
                uiState = uiState,
                viewModel = viewModel,
                fillAvailableHeight = false,
                maxContentHeight = subPanelMaxHeight,
                outerHorizontalPadding = 12.dp,
                outerVerticalPadding = 4.dp,
              )
            }
          }
        }
      }

      EditActionDeck(
        uiState = uiState,
        onSplitAtPlayhead = viewModel::splitAtPlayhead,
        onToggleTrimPanel = { viewModel.toggleSubPanel(EditorToolSubPanel.TRIM) },
        onToggleAdjustmentsPanel = { viewModel.toggleSubPanel(EditorToolSubPanel.ADJUSTMENTS) },
        onQuickColorLut = { viewModel.openInspectorOrToggleTab(InspectorTab.COLOR_LUT) },
        onToggleKeyframesPanel = { viewModel.toggleSubPanel(EditorToolSubPanel.KEYFRAMES) },
        onDuplicateClip = viewModel::duplicateSelectedClip,
        onToggleRipple = viewModel::toggleRippleEnabled,
        onCycleRippleScope = viewModel::cycleRippleScope,
        onToggleClipEnabled = viewModel::toggleSelectedClipEnabled,
        onDeleteClip = viewModel::deleteSelectedClip,
        onAddMarker = { viewModel.addMarkerAtPlayhead(markerDefaultPrefix) },
        onOpenAudioTools = { viewModel.openSheet(EditorToolSheet.AUDIO) },
        onOpenSubtitlesBatch = { viewModel.openSheet(EditorToolSheet.SUBTITLES) },
        onOpenAttributesSnapshots = { viewModel.openSheet(EditorToolSheet.ATTRIBUTES_SNAPSHOTS) },
        compact = isLandscapeLayout,
      )
    }
  }

  when (uiState.activeSheet) {
    EditorToolSheet.AUDIO ->
      AudioToolsSheet(
        selectedClip = uiState.selectedClip,
        duckingConfig = uiState.timeline.duckingConfig,
        detectedSilences = uiState.detectedSilences,
        isAnalyzingSilence = uiState.isAnalyzingSilence,
        onUpdateClipAudio = viewModel::updateClipAudioMix,
        onUpdateDucking = viewModel::updateDuckingConfig,
        onAnalyzeSilences = viewModel::analyzeSilencesForClip,
        onRemoveDetectedSilences = viewModel::removeDetectedSilences,
        onDismiss = viewModel::closeSheet,
        tracks = uiState.timeline.tracksSorted(),
        onUpdateTrackVolume = viewModel::updateTrackVolume,
        onToggleTrackMute = viewModel::toggleTrackMute,
        onToggleTrackSolo = viewModel::toggleTrackSolo,
        onBeginTrackEdit = viewModel::beginClipEditTransaction,
        onEndTrackEdit = viewModel::endClipEditTransaction,
      )

    EditorToolSheet.SUBTITLES ->
      BatchSubtitlesSheet(
        subtitleClips = uiState.timeline.clips.filter { it.kind == ClipKind.SUBTITLE },
        savedStyles = uiState.savedSubtitleStyles,
        selectedClip = uiState.selectedClip,
        onApplyStyleToAll = viewModel::applySubtitleStyleToAll,
        onSaveStyleFromSelected = viewModel::saveCustomSubtitleStyleFromSelected,
        onGenerateBatchFromScript = viewModel::generateBatchSubtitles,
        onSaveBatchEdits = viewModel::saveBatchSubtitleEdits,
        onDismiss = viewModel::closeSheet,
      )

    EditorToolSheet.ATTRIBUTES_SNAPSHOTS ->
      AttributesAndSnapshotsSheet(
        selectedClip = uiState.selectedClip,
        availableCategories = viewModel.availableAttributesForSelected(),
        clipboard = uiState.attributeClipboard,
        snapshots = uiState.snapshots,
        sequences = uiState.sequences,
        activeSequenceId = uiState.activeSequenceId,
        onCopyAttributes = viewModel::copySelectedClipAttributes,
        onPasteToCompatibleClips = viewModel::pasteAttributesToCompatibleClips,
        onCreateSnapshot = viewModel::createTimelineSnapshot,
        onRestoreSnapshot = viewModel::restoreTimelineSnapshot,
        onDuplicateSequence = viewModel::duplicateTimelineAsSequence,
        onSwitchSequence = viewModel::switchTimelineSequence,
        onDismiss = viewModel::closeSheet,
      )

    EditorToolSheet.NONE -> Unit
  }

  if (uiState.showShortcutsDialog) {
    KeyboardShortcutsDialog(
      onDismiss = { viewModel.setShortcutsDialogOpen(false) },
    )
  }
}

@Composable
private fun ActiveEditorSubPanel(
  uiState: TimelineSkeletonUiState,
  viewModel: TimelineSkeletonViewModel,
  fillAvailableHeight: Boolean,
  maxContentHeight: Dp,
  outerHorizontalPadding: Dp,
  outerVerticalPadding: Dp,
  modifier: Modifier = Modifier,
) {
  when (uiState.activeSubPanel) {
    EditorToolSubPanel.TRIM -> {
      val maxBound = uiState.selectedClip?.let { viewModel.maxSourceDurationForClip(it) } ?: 10_000L
      TrimRangeSubPanel(
        selectedClip = uiState.selectedClip,
        maxSourceDurationMs = maxBound,
        onBeginEdit = viewModel::beginClipEditTransaction,
        onTrimRangeChange = viewModel::updateSelectedClipTrimRange,
        onEndEdit = viewModel::endClipEditTransaction,
        onTrimStartToPlayhead = viewModel::trimSelectedStartToPlayhead,
        onTrimEndToPlayhead = viewModel::trimSelectedEndToPlayhead,
        onClose = viewModel::closeSubPanel,
        modifier = modifier,
        fillAvailableHeight = fillAvailableHeight,
        maxContentHeight = maxContentHeight,
        outerHorizontalPadding = outerHorizontalPadding,
        outerVerticalPadding = outerVerticalPadding,
      )
    }

    EditorToolSubPanel.ADJUSTMENTS ->
      ClipInspectorPanel(
        selectedClip = uiState.selectedClip,
        activeTab = uiState.activeInspectorTab,
        onSelectTab = viewModel::selectInspectorTab,
        onBeginEdit = viewModel::beginClipEditTransaction,
        onEndEdit = viewModel::endClipEditTransaction,
        onUpdateTransform = viewModel::updateSelectedClipTransformParams,
        onRotate90 = viewModel::rotateSelectedClipBy90,
        onUpdateColor = viewModel::updateSelectedClipColorParams,
        onSelectLutPreset = { presetId -> viewModel.applyLutPresetToSelected(presetId) },
        onUpdateAudio = viewModel::updateSelectedClipAudioParams,
        onUpdateSpeed = viewModel::updateSelectedClipSpeed,
        onUpdateSubtitle = viewModel::updateSelectedSubtitleInline,
        onResetTab = viewModel::resetInspectorCategory,
        onResetAll = viewModel::resetSelectedClipAdjustments,
        onOpenAudioSheet = { viewModel.openSheet(EditorToolSheet.AUDIO) },
        onOpenSubtitlesSheet = { viewModel.openSheet(EditorToolSheet.SUBTITLES) },
        onClose = viewModel::closeSubPanel,
        modifier = modifier,
        fillAvailableHeight = fillAvailableHeight,
        maxContentHeight = maxContentHeight,
        outerHorizontalPadding = outerHorizontalPadding,
        outerVerticalPadding = outerVerticalPadding,
      )

    EditorToolSubPanel.KEYFRAMES ->
      uiState.selectedClip
        ?.takeIf { it.kind == ClipKind.VIDEO || it.kind == ClipKind.AUDIO }
        ?.let { keyframeableClip ->
          KeyframeInspectorStrip(
            selectedClip = keyframeableClip,
            playheadMs = uiState.timeline.playheadMs,
            onAddKeyframe = viewModel::addOrUpdateKeyframeAtPlayhead,
            onSeekToMs = viewModel::seekPlayhead,
            onJumpPreviousKeyframe = viewModel::jumpToPreviousKeyframe,
            onJumpNextKeyframe = viewModel::jumpToNextKeyframe,
            onMoveKeyframe = viewModel::moveSelectedClipKeyframe,
            onUpdateInterpolation = viewModel::setSelectedKeyframeInterpolation,
            onRemoveKeyframe = viewModel::removeKeyframeFromSelected,
            onClearKeyframes = viewModel::clearSelectedClipKeyframes,
            onBeginEdit = viewModel::beginClipEditTransaction,
            onUpdateKeyframeProperties = { scale, opacity, offsetX, offsetY, rotationDegrees, volume, fromSliderDrag ->
              viewModel.updateSelectedClipKeyframeProperties(
                scale = scale,
                opacity = opacity,
                offsetX = offsetX,
                offsetY = offsetY,
                rotationDegrees = rotationDegrees,
                volume = volume,
                fromSliderDrag = fromSliderDrag,
              )
            },
            onEndEdit = viewModel::endClipEditTransaction,
            onClose = viewModel::closeSubPanel,
            modifier = modifier,
            fillAvailableHeight = fillAvailableHeight,
            maxContentHeight = maxContentHeight,
            outerHorizontalPadding = outerHorizontalPadding,
            outerVerticalPadding = outerVerticalPadding,
          )
        }

    EditorToolSubPanel.NONE -> Unit
  }
}
