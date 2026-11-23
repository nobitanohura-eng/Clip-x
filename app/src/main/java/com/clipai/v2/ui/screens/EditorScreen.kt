package com.clipai.v2.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MovieCreation
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.clipai.v2.data.local.ProjectEntity
import com.clipai.v2.data.prefs.UserSettings
import com.clipai.v2.domain.CaptionEngine
import com.clipai.v2.domain.ClipCandidate
import com.clipai.v2.domain.ConfidenceLevel
import com.clipai.v2.domain.CropMode
import com.clipai.v2.domain.HookMode
import com.clipai.v2.domain.WordToken
import com.clipai.v2.ui.ExportUiState
import com.clipai.v2.ui.theme.StudioColors
import kotlinx.coroutines.delay
import java.util.Locale

private enum class EditorToolTab(val label: String) {
    CAPTIONS("Captions"),
    FRAME_HOOK("Frame & Hook"),
    EXPORT("Export")
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    project: ProjectEntity?,
    clips: List<ClipCandidate>,
    selectedClip: ClipCandidate?,
    words: List<WordToken>,
    settings: UserSettings,
    exportState: ExportUiState,
    onSelectClip: (String) -> Unit,
    onUpdateCrop: (String, CropMode, Float) -> Unit,
    onUpdateHook: (String, HookMode, String, String) -> Unit,
    onLockWordEdit: (String, String) -> Unit,
    onImportTranscript: (String) -> Unit,
    onExportClip: () -> Unit,
    onCancelExport: () -> Unit,
    onBuildShareIntent: (String) -> Intent?,
    onBack: () -> Unit,
    onGoProjects: () -> Unit
) {
    if (project == null || selectedClip == null) {
        EmptyStateBox(
            title = "No clip loaded",
            body = "Paste a video link or pick a video from Home to preview 9:16 framing, edit captions, and export.",
            actionLabel = "Go to Home",
            onAction = onGoProjects
        )
        return
    }

    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(EditorToolTab.CAPTIONS) }
    var showSafeGuides by remember { mutableStateOf(true) }
    var filterNeedsReview by remember { mutableStateOf(false) }
    var editingWord by remember { mutableStateOf<WordToken?>(null) }
    var editingWordDraft by remember { mutableStateOf("") }
    var showPasteTranscriptDialog by remember { mutableStateOf(false) }
    var transcriptDraft by remember { mutableStateOf("") }
    var hookTextDraft by remember(selectedClip.id, selectedClip.hookMode) {
        mutableStateOf(
            if (selectedClip.hookMode == HookMode.VERBATIM_QUOTE) selectedClip.verbatimHook
            else selectedClip.titleHook
        )
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(project.uriString, selectedClip.id, selectedClip.startSec, selectedClip.endSec) {
        val clippedItem = MediaItem.Builder()
            .setUri(Uri.parse(project.uriString))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((selectedClip.startSec * 1000.0).toLong().coerceAtLeast(0L))
                    .setEndPositionMs((selectedClip.endSec * 1000.0).toLong())
                    .build()
            )
            .build()
        exoPlayer.setMediaItem(clippedItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = false
    }

    var playbackPosMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(exoPlayer, selectedClip.id) {
        while (true) {
            playbackPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            delay(80L)
        }
    }

    val captionLines = remember(words, selectedClip.startSec, selectedClip.endSec) {
        CaptionEngine.buildCaptionLines(words, selectedClip.startSec, selectedClip.endSec)
    }
    val relTimeSec = playbackPosMs / 1000.0
    val absTimeSec = selectedClip.startSec + relTimeSec
    val activeLine = captionLines.firstOrNull { absTimeSec in (it.startSec - 0.05)..(it.endSec + 0.12) }

    val visibleLines = remember(captionLines, filterNeedsReview) {
        if (!filterNeedsReview) captionLines
        else captionLines.filter { line ->
            line.words.any { it.confidenceLevel != ConfidenceLevel.HIGH && !it.locked }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.ObsidianCanvas)
    ) {
        // Slim distraction-free Studio Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(StudioColors.SurfaceCard)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back to Clips",
                        tint = StudioColors.TextPrimary
                    )
                }
                Column {
                    Text(
                        text = "Clip #${selectedClip.rank}",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioColors.TextPrimary
                    )
                    Text(
                        text = "${formatDuration(relTimeSec)} / ${formatDuration(selectedClip.durationSec)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioColors.TextMuted
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { showSafeGuides = !showSafeGuides }) {
                    Icon(
                        Icons.Outlined.GridOn,
                        contentDescription = "Toggle Safe Margins",
                        tint = if (showSafeGuides) StudioColors.AccentAmber else StudioColors.TextMuted
                    )
                }
                Button(
                    onClick = {
                        activeTab = EditorToolTab.EXPORT
                        if (!exportState.isExporting) onExportClip()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.AccentAmber,
                        contentColor = StudioColors.OnAmber
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (exportState.isExporting) "${exportState.progressPercent}%" else "Export",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        HorizontalDivider(color = StudioColors.HairlineBorder)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Optional quick clip switcher when > 1 clip exists
            if (clips.size > 1) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(clips, key = { it.id }) { item ->
                            val active = item.id == selectedClip.id
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (active) StudioColors.AccentAmber else StudioColors.SurfaceCard,
                                border = BorderStroke(1.dp, if (active) StudioColors.AccentAmber else StudioColors.HairlineBorder),
                                modifier = Modifier.clickable { onSelectClip(item.id) }
                            ) {
                                Text(
                                    text = "#${item.rank} · ${item.scores.total}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (active) StudioColors.OnAmber else StudioColors.TextPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Centered 9:16 Stage
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.68f)
                            .aspectRatio(9f / 16f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black)
                            .border(1.dp, StudioColors.HairlineBorder, RoundedCornerShape(14.dp))
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    useController = true
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (relTimeSec <= 3.0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                                        )
                                    )
                                    .padding(top = 20.dp, start = 14.dp, end = 14.dp, bottom = 24.dp),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (selectedClip.hookMode == HookMode.MARKED_TITLE) {
                                        Text(
                                            text = "TITLE",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StudioColors.KaraokeYellow,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    val hookDisplay = if (selectedClip.hookMode == HookMode.VERBATIM_QUOTE) {
                                        "“${selectedClip.verbatimHook}”"
                                    } else {
                                        selectedClip.titleHook
                                    }
                                    Text(
                                        text = hookDisplay,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        if (activeLine != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(bottom = 50.dp, start = 12.dp, end = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val annotated = buildAnnotatedString {
                                    activeLine.words.forEachIndexed { idx, w ->
                                        if (idx > 0) append(" ")
                                        val isActive = absTimeSec in (w.startSec - 0.04)..(w.endSec + 0.08)
                                        withStyle(
                                            SpanStyle(
                                                color = if (isActive) StudioColors.KaraokeYellow else Color.White,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        ) {
                                            append(w.displayText)
                                        }
                                    }
                                }
                                Text(
                                    text = annotated,
                                    fontSize = 17.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        if (showSafeGuides) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(bottom = 42.dp)
                                    .height(1.dp)
                                    .background(StudioColors.AccentAmber.copy(alpha = 0.65f))
                            )
                        }
                    }
                }
            }

            // 3-Tab Inspector Switcher
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StudioColors.SurfaceCard,
                    border = BorderStroke(1.dp, StudioColors.HairlineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        EditorToolTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) StudioColors.SurfaceElevated else Color.Transparent,
                                border = if (selected) BorderStroke(1.dp, StudioColors.AccentAmber.copy(alpha = 0.6f)) else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { activeTab = tab }
                            ) {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) StudioColors.AccentAmber else StudioColors.TextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Focused Panel Content for Selected Tab
            when (activeTab) {
                EditorToolTab.CAPTIONS -> {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = StudioColors.SurfaceCard,
                            border = BorderStroke(1.dp, StudioColors.HairlineBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Karaoke Captions", style = MaterialTheme.typography.titleMedium, color = StudioColors.TextPrimary)
                                        Text("Tap any word to seek, edit & lock", style = MaterialTheme.typography.labelSmall, color = StudioColors.TextMuted)
                                    }
                                    TextButton(onClick = {
                                        transcriptDraft = words.joinToString(" ") { it.text }
                                        showPasteTranscriptDialog = true
                                    }) {
                                        Icon(Icons.Outlined.Description, contentDescription = null, tint = StudioColors.AccentAmber, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Paste Script", color = StudioColors.AccentAmber)
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { filterNeedsReview = false },
                                        border = BorderStroke(1.dp, if (!filterNeedsReview) StudioColors.AccentAmber else StudioColors.HairlineBorder),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("All (${captionLines.size})", style = MaterialTheme.typography.bodySmall, color = StudioColors.TextPrimary)
                                    }
                                    OutlinedButton(
                                        onClick = { filterNeedsReview = true },
                                        border = BorderStroke(1.dp, if (filterNeedsReview) StudioColors.AccentAmber else StudioColors.HairlineBorder),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Needs Review", style = MaterialTheme.typography.bodySmall, color = StudioColors.AccentAmber)
                                    }
                                }

                                if (visibleLines.isEmpty()) {
                                    Text(
                                        text = if (filterNeedsReview) "All words in this clip are verified or locked." else "No captions yet — tap Paste Script to align English, Hindi, or Hinglish text.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioColors.TextSecondary
                                    )
                                } else {
                                    visibleLines.forEach { line ->
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = formatDuration(line.startSec - selectedClip.startSec),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = StudioColors.TextMuted
                                                )
                                                Text(
                                                    text = "${line.wordCount}w · ${line.graphemeCount}/42ch${if (line.hasDevanagari) " · Devanagari" else ""}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = StudioColors.TextMuted
                                                )
                                            }

                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                line.words.forEach { word ->
                                                    val borderColor = when {
                                                        word.locked -> StudioColors.SuccessEmerald.copy(alpha = 0.5f)
                                                        word.confidenceLevel == ConfidenceLevel.UNCERTAIN -> StudioColors.ErrorCrimson
                                                        word.confidenceLevel == ConfidenceLevel.REVIEW -> StudioColors.WarningAmber
                                                        else -> StudioColors.HairlineBorder
                                                    }
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = StudioColors.SurfaceElevated,
                                                        border = BorderStroke(1.dp, borderColor),
                                                        modifier = Modifier.clickable {
                                                            exoPlayer.seekTo(((word.startSec - selectedClip.startSec) * 1000.0).toLong().coerceAtLeast(0L))
                                                            editingWord = word
                                                            editingWordDraft = word.text
                                                        }
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = word.displayText,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = StudioColors.TextPrimary
                                                            )
                                                            if (word.locked) {
                                                                Spacer(Modifier.width(4.dp))
                                                                Icon(Icons.Outlined.Lock, contentDescription = null, tint = StudioColors.SuccessEmerald, modifier = Modifier.size(11.dp))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            HorizontalDivider(color = StudioColors.HairlineBorder.copy(alpha = 0.5f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                EditorToolTab.FRAME_HOOK -> {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = StudioColors.SurfaceCard,
                            border = BorderStroke(1.dp, StudioColors.HairlineBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text("9:16 Framing & Hook", style = MaterialTheme.typography.titleMedium, color = StudioColors.TextPrimary)

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CropMode.entries.forEach { mode ->
                                        val selected = selectedClip.cropMode == mode
                                        OutlinedButton(
                                            onClick = { onUpdateCrop(selectedClip.id, mode, selectedClip.manualCropOffsetX) },
                                            border = BorderStroke(1.dp, if (selected) StudioColors.AccentAmber else StudioColors.HairlineBorder),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = if (mode == CropMode.SMART_FACE_TRACK) "Face Track" else "Center Crop",
                                                color = if (selected) StudioColors.AccentAmber else StudioColors.TextSecondary,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }

                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Horizontal Offset", style = MaterialTheme.typography.bodySmall, color = StudioColors.TextSecondary)
                                        Text(
                                            text = String.format(Locale.ROOT, "%+d%%", (selectedClip.manualCropOffsetX * 100).toInt()),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = StudioColors.TextPrimary
                                        )
                                    }
                                    Slider(
                                        value = selectedClip.manualCropOffsetX,
                                        onValueChange = { offset ->
                                            onUpdateCrop(selectedClip.id, selectedClip.cropMode, offset)
                                        },
                                        valueRange = -0.35f..0.35f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = StudioColors.AccentAmber,
                                            activeTrackColor = StudioColors.AccentAmber
                                        )
                                    )
                                }

                                HorizontalDivider(color = StudioColors.HairlineBorder)

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    HookMode.entries.forEach { mode ->
                                        val selected = selectedClip.hookMode == mode
                                        OutlinedButton(
                                            onClick = {
                                                onUpdateHook(selectedClip.id, mode, selectedClip.verbatimHook, selectedClip.titleHook)
                                            },
                                            border = BorderStroke(1.dp, if (selected) StudioColors.AccentAmber else StudioColors.HairlineBorder),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = if (mode == HookMode.VERBATIM_QUOTE) "Verbatim Quote" else "Marked Title",
                                                color = if (selected) StudioColors.AccentAmber else StudioColors.TextSecondary,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = hookTextDraft,
                                    onValueChange = { next ->
                                        hookTextDraft = next
                                        if (selectedClip.hookMode == HookMode.VERBATIM_QUOTE) {
                                            onUpdateHook(selectedClip.id, selectedClip.hookMode, next, selectedClip.titleHook)
                                        } else {
                                            onUpdateHook(selectedClip.id, selectedClip.hookMode, selectedClip.verbatimHook, next)
                                        }
                                    },
                                    label = { Text("Top-third hook (0–3s)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                EditorToolTab.EXPORT -> {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = StudioColors.SurfaceCard,
                            border = BorderStroke(1.dp, StudioColors.HairlineBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text("Export 9:16 Vertical MP4", style = MaterialTheme.typography.titleMedium, color = StudioColors.TextPrimary)
                                Text(
                                    text = "${settings.exportPreset.label} · -14.0 LUFS · Hardware Media3",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioColors.TextSecondary
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (exportState.isExporting) {
                                        OutlinedButton(
                                            onClick = onCancelExport,
                                            border = BorderStroke(1.dp, StudioColors.ErrorCrimson),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Outlined.Close, contentDescription = null, tint = StudioColors.ErrorCrimson, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Cancel (${exportState.progressPercent}%)", color = StudioColors.ErrorCrimson)
                                        }
                                    } else {
                                        Button(
                                            onClick = onExportClip,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = StudioColors.AccentAmber,
                                                contentColor = StudioColors.OnAmber
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Outlined.MovieCreation, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Export MP4", fontWeight = FontWeight.SemiBold)
                                        }
                                    }

                                    val sharePath = exportState.lastOutputPath ?: selectedClip.exportedFilePath
                                    if (!exportState.isExporting && !sharePath.isNullOrBlank()) {
                                        OutlinedButton(
                                            onClick = {
                                                onBuildShareIntent(sharePath)?.let { intent ->
                                                    context.startActivity(Intent.createChooser(intent, "Share vertical clip"))
                                                }
                                            },
                                            border = BorderStroke(1.dp, StudioColors.SuccessEmerald),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Outlined.Share, contentDescription = null, tint = StudioColors.SuccessEmerald, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Share", color = StudioColors.SuccessEmerald)
                                        }
                                    }
                                }

                                if (exportState.isExporting) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(exportState.statusText, style = MaterialTheme.typography.labelSmall, color = StudioColors.AccentAmber)
                                        LinearProgressIndicator(
                                            progress = { exportState.progressPercent / 100f },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp),
                                            color = StudioColors.AccentAmber,
                                            trackColor = StudioColors.SurfaceElevated
                                        )
                                    }
                                }

                                if (exportState.errorMessage != null) {
                                    Text(exportState.errorMessage, style = MaterialTheme.typography.bodySmall, color = StudioColors.ErrorCrimson)
                                }

                                exportState.lastQaResult?.let { qa ->
                                    HorizontalDivider(color = StudioColors.HairlineBorder)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (qa.passed) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                                            contentDescription = null,
                                            tint = if (qa.passed) StudioColors.SuccessEmerald else StudioColors.ErrorCrimson,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = if (qa.passed) "Quality Check Passed" else (qa.failureSummary ?: "Quality issues detected"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (qa.passed) StudioColors.SuccessEmerald else StudioColors.ErrorCrimson
                                        )
                                    }
                                    Text(
                                        text = String.format(
                                            Locale.ROOT,
                                            "%d×%d · %.1fs · %d kbps · Thumb %s · %.1f LUFS",
                                            qa.width,
                                            qa.height,
                                            qa.durationSec,
                                            qa.bitrateKbps,
                                            if (qa.thumbnailOk) "OK" else "Blank",
                                            qa.loudnessLufs
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioColors.TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingWord?.let { target ->
        AlertDialog(
            onDismissRequest = { editingWord = null },
            containerColor = StudioColors.SurfaceCard,
            title = { Text("Edit & Lock Word", color = StudioColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Confidence: ${(target.confidence * 100).toInt()}%. Locked edits stay preserved across re-scoring.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary
                    )
                    OutlinedTextField(
                        value = editingWordDraft,
                        onValueChange = { editingWordDraft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onLockWordEdit(target.id, editingWordDraft)
                        editingWord = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.AccentAmber, contentColor = StudioColors.OnAmber)
                ) {
                    Text("Save & Lock")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingWord = null }) {
                    Text("Cancel", color = StudioColors.TextSecondary)
                }
            }
        )
    }

    if (showPasteTranscriptDialog) {
        AlertDialog(
            onDismissRequest = { showPasteTranscriptDialog = false },
            containerColor = StudioColors.SurfaceCard,
            title = { Text("Paste Transcript", color = StudioColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Supports English, Hindi Devanagari, and Hinglish. Aligns across your video timeline and re-scores clips.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary
                    )
                    OutlinedTextField(
                        value = transcriptDraft,
                        onValueChange = { transcriptDraft = it },
                        minLines = 5,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onImportTranscript(transcriptDraft)
                        showPasteTranscriptDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.AccentAmber, contentColor = StudioColors.OnAmber)
                ) {
                    Text("Align & Re-Score")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteTranscriptDialog = false }) {
                    Text("Cancel", color = StudioColors.TextSecondary)
                }
            }
        )
    }
}
