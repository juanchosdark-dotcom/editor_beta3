package com.example.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
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

  Column(
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
    )

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
      onOpenShortcuts = { viewModel.setShortcutsDialogOpen(true) },
      onQuickOpenColorLut = { viewModel.openInspectorOrToggleTab(InspectorTab.COLOR_LUT) },
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
      onEraserTapClip = viewModel::eraserTapClip,
      loadClipThumbnail = viewModel::loadClipThumbnail,
      loadClipWaveform = viewModel::loadClipWaveform,
      modifier = Modifier.weight(1f),
    )

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
            )
          }

      EditorToolSubPanel.NONE -> Unit
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
    )
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
