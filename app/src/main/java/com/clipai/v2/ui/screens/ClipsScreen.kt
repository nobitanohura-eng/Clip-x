package com.clipai.v2.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipai.v2.data.local.ProjectEntity
import com.clipai.v2.domain.ClipCandidate
import com.clipai.v2.domain.FallbackPlan
import com.clipai.v2.ui.theme.StudioColors
import java.util.Locale

@Composable
fun ClipsScreen(
    project: ProjectEntity?,
    clips: List<ClipCandidate>,
    fallbackPlan: FallbackPlan,
    dedupGapSec: Int,
    onOpenEditor: (String) -> Unit,
    onGoProjects: () -> Unit
) {
    var showDeduped by remember { mutableStateOf(false) }

    if (project == null) {
        EmptyStateBox(
            title = "No project selected",
            body = "Paste a YouTube / live stream link or pick a video from Home to generate ready-to-edit clips.",
            actionLabel = "Go to Home",
            onAction = onGoProjects
        )
        return
    }

    val selectedClips = clips.filter { it.selected }.sortedBy { it.rank }
    val visibleClips = if (showDeduped) clips else selectedClips
    val discardedCount = (clips.size - selectedClips.size).coerceAtLeast(0)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.ObsidianCanvas),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = project.fileName,
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Generated Clips",
                            style = MaterialTheme.typography.headlineMedium,
                            color = StudioColors.TextPrimary
                        )
                        Text(
                            text = "${selectedClips.size} top moments ready to edit",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextSecondary
                        )
                    }

                    if (discardedCount > 0) {
                        OutlinedButton(
                            onClick = { showDeduped = !showDeduped },
                            border = BorderStroke(1.dp, StudioColors.HairlineBorder),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (showDeduped) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = StudioColors.TextSecondary
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = if (showDeduped) "Hide ($discardedCount)" else "More ($discardedCount)",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.TextPrimary
                            )
                        }
                    }
                }

                if (fallbackPlan.llmDisabledReason != null) {
                    Text(
                        text = fallbackPlan.llmDisabledReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioColors.TextMuted
                    )
                }
            }
        }

        if (visibleClips.isEmpty()) {
            item {
                EmptyStateBox(
                    title = "No clips generated yet",
                    body = "Wait for Auto Clip to finish or paste a transcript inside the Editor.",
                    actionLabel = "Back to Home",
                    onAction = onGoProjects
                )
            }
        } else {
            items(visibleClips, key = { it.id }) { clip ->
                ClipScoreCard(
                    clip = clip,
                    dedupGapSec = dedupGapSec,
                    onOpenEditor = { onOpenEditor(clip.id) }
                )
            }
        }
    }
}

@Composable
private fun ClipScoreCard(
    clip: ClipCandidate,
    dedupGapSec: Int,
    onOpenEditor: () -> Unit
) {
    var showScoreMath by remember(clip.id) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (clip.selected) StudioColors.SurfaceCard else StudioColors.SurfaceCard.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, StudioColors.HairlineBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (clip.selected) {
                        String.format(
                            Locale.ROOT,
                            "Clip #%d · %s – %s · %.0fs",
                            clip.rank,
                            formatDuration(clip.startSec),
                            formatDuration(clip.endSec),
                            clip.durationSec
                        )
                    } else {
                        "Deduped · ${formatDuration(clip.startSec)} – ${formatDuration(clip.endSec)}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (clip.selected) StudioColors.AccentAmber else StudioColors.TextMuted
                )

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = StudioColors.SurfaceElevated,
                    border = BorderStroke(1.dp, StudioColors.HairlineBorder)
                ) {
                    Text(
                        text = "${clip.scores.total}/100",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioColors.TextPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = "“${clip.verbatimHook}”",
                style = MaterialTheme.typography.titleMedium,
                color = StudioColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (clip.scores.hookSignals.isNotEmpty()) {
                Text(
                    text = clip.scores.hookSignals.take(3).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!clip.selected && clip.dedupedByClipId != null) {
                Text(
                    text = if (clip.dedupedByClipId == "limit-reached") {
                        "Skipped: above max clips limit"
                    } else {
                        "Skipped by ${dedupGapSec}s dedup rule (gap ${clip.dedupGapSec ?: 0}s)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioColors.WarningAmber
                )
            }

            if (showScoreMath) {
                HorizontalDivider(color = StudioColors.HairlineBorder)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScoreMetricRow("Hook (35%)", clip.scores.hook)
                    ScoreMetricRow("Flow (25%)", clip.scores.flow)
                    ScoreMetricRow("Value (25%)", clip.scores.value)
                    ScoreMetricRow("Audio-Visual (15%)", clip.scores.audioVisual)
                }
                Text(
                    text = String.format(
                        Locale.ROOT,
                        "%.2f w/s · %d%% silence · %d%% face · %.2fx energy",
                        clip.scores.infoDensityWps,
                        (clip.scores.silenceFraction * 100).toInt(),
                        (clip.scores.faceVisibleFraction * 100).toInt(),
                        clip.scores.energyRatio
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioColors.TextMuted
                )
                Text(
                    text = "Title: ${clip.titleHook}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider(color = StudioColors.HairlineBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (clip.qaPassed == true) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = StudioColors.SuccessEmerald,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Exported", style = MaterialTheme.typography.labelSmall, color = StudioColors.SuccessEmerald)
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(
                        onClick = { showScoreMath = !showScoreMath },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (showScoreMath) "Hide Math" else "Score Math",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioColors.TextSecondary
                        )
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            imageVector = if (showScoreMath) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = StudioColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Button(
                    onClick = onOpenEditor,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioColors.AccentAmber,
                        contentColor = StudioColors.OnAmber
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit Clip", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ScoreMetricRow(label: String, score: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = StudioColors.TextSecondary)
            Text("$score", style = MaterialTheme.typography.labelSmall, color = StudioColors.TextPrimary)
        }
        LinearProgressIndicator(
            progress = { (score / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = StudioColors.AccentAmber,
            trackColor = StudioColors.SurfaceElevated
        )
    }
}

@Composable
internal fun EmptyStateBox(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.ObsidianCanvas)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = StudioColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = StudioColors.TextSecondary)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(
                containerColor = StudioColors.AccentAmber,
                contentColor = StudioColors.OnAmber
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(actionLabel, fontWeight = FontWeight.SemiBold)
        }
    }
}
