package com.clipai.v2.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipai.v2.data.local.ProjectEntity
import com.clipai.v2.domain.DeviceCapabilities
import com.clipai.v2.domain.FallbackPlan
import com.clipai.v2.domain.PipelineProgress
import com.clipai.v2.domain.PipelineStageStatus
import com.clipai.v2.ui.theme.StudioColors
import java.util.Locale

@Composable
fun HomeProjectsScreen(
    projects: List<ProjectEntity>,
    activeProjectId: String?,
    pipelineProgress: PipelineProgress,
    device: DeviceCapabilities,
    fallbackPlan: FallbackPlan,
    onPickVideoUri: (Uri) -> Unit,
    onIngestLink: (String, Int) -> Unit,
    onCancelPipeline: () -> Unit,
    onOpenProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit
) {
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onPickVideoUri(uri)
    }

    var linkInput by remember { mutableStateOf("") }
    var captureSeconds by remember { mutableIntStateOf(120) }
    var showStageDetails by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.ObsidianCanvas),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Minimal Studio Brand Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "ClipAI",
                    style = MaterialTheme.typography.headlineMedium,
                    color = StudioColors.TextPrimary
                )
                Text(
                    text = "Turn any video or live stream into 9:16 vertical clips",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary
                )
            }
        }

        // Clean Link Paste + Local Video Entry Card
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = StudioColors.SurfaceCard,
                border = BorderStroke(1.dp, StudioColors.HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Paste Video or Live Stream Link",
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioColors.TextPrimary
                    )

                    OutlinedTextField(
                        value = linkInput,
                        onValueChange = { linkInput = it },
                        placeholder = {
                            Text(
                                "YouTube, Live stream, .m3u8, or MP4 link...",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.TextMuted
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Link,
                                contentDescription = null,
                                tint = StudioColors.TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        singleLine = true,
                        enabled = !pipelineProgress.isRunning,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = StudioColors.AccentAmber,
                            unfocusedBorderColor = StudioColors.HairlineBorder,
                            focusedTextColor = StudioColors.TextPrimary,
                            unfocusedTextColor = StudioColors.TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(60 to "1m", 120 to "2m", 180 to "3m").forEach { (sec, label) ->
                                val selected = captureSeconds == sec
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) StudioColors.SurfaceElevated else StudioColors.ObsidianCanvas,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) StudioColors.AccentAmber.copy(alpha = 0.7f) else StudioColors.HairlineBorder
                                    ),
                                    modifier = Modifier.clickable(enabled = !pipelineProgress.isRunning) {
                                        captureSeconds = sec
                                    }
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) StudioColors.TextPrimary else StudioColors.TextSecondary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                if (linkInput.isNotBlank()) {
                                    onIngestLink(linkInput.trim(), captureSeconds)
                                }
                            },
                            enabled = !pipelineProgress.isRunning && linkInput.isNotBlank() && fallbackPlan.storageBlockedReason == null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.AccentAmber,
                                contentColor = StudioColors.OnAmber
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Text("Clip Link", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    HorizontalDivider(color = StudioColors.HairlineBorder)

                    OutlinedButton(
                        onClick = { videoPicker.launch(arrayOf("video/*")) },
                        enabled = !pipelineProgress.isRunning && fallbackPlan.storageBlockedReason == null,
                        border = BorderStroke(1.dp, StudioColors.HairlineBorder),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Icon(
                            Icons.Outlined.VideoLibrary,
                            contentDescription = null,
                            tint = StudioColors.TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Pick Video from Device", color = StudioColors.TextPrimary, fontWeight = FontWeight.Medium)
                    }

                    if (fallbackPlan.storageBlockedReason != null) {
                        GuardrailBanner(message = fallbackPlan.storageBlockedReason, isError = true)
                    } else if (fallbackPlan.batteryWarning != null) {
                        GuardrailBanner(message = fallbackPlan.batteryWarning, isError = false)
                    }
                }
            }
        }

        // Sleek Compact Live Progress Card (only while running or on error)
        if (pipelineProgress.isRunning || pipelineProgress.errorMessage != null) {
            item {
                val activeStage = pipelineProgress.stages.firstOrNull { it.status == PipelineStageStatus.RUNNING }
                val doneCount = pipelineProgress.stages.count {
                    it.status == PipelineStageStatus.DONE || it.status == PipelineStageStatus.SKIPPED
                }
                val progressFraction = (doneCount.toFloat() / pipelineProgress.stages.size.coerceAtLeast(1)).coerceIn(0.06f, 1f)

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = StudioColors.SurfaceCard,
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = activeStage?.stage?.displayTitle
                                        ?: if (pipelineProgress.errorMessage != null) "Clipping paused" else "Finishing clips",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = StudioColors.TextPrimary
                                )
                                Text(
                                    text = activeStage?.detail ?: pipelineProgress.errorMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (pipelineProgress.errorMessage != null) StudioColors.ErrorCrimson else StudioColors.TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (pipelineProgress.isRunning) {
                                TextButton(onClick = onCancelPipeline) {
                                    Icon(Icons.Outlined.Close, contentDescription = null, tint = StudioColors.ErrorCrimson, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Cancel", color = StudioColors.ErrorCrimson)
                                }
                            }
                        }

                        if (pipelineProgress.isRunning) {
                            val pct = activeStage?.progressPercent
                            if (pct != null) {
                                LinearProgressIndicator(
                                    progress = { maxOf(progressFraction, pct / 100f) },
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    color = StudioColors.AccentAmber,
                                    trackColor = StudioColors.SurfaceElevated
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    color = StudioColors.AccentAmber,
                                    trackColor = StudioColors.SurfaceElevated
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showStageDetails = !showStageDetails },
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (showStageDetails) "Hide steps" else "Show steps",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioColors.TextMuted
                                )
                                Icon(
                                    imageVector = if (showStageDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = null,
                                    tint = StudioColors.TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            if (showStageDetails) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    pipelineProgress.stages.forEach { st ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = st.stage.displayTitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = when (st.status) {
                                                    PipelineStageStatus.RUNNING -> StudioColors.AccentAmber
                                                    PipelineStageStatus.DONE -> StudioColors.TextPrimary
                                                    else -> StudioColors.TextMuted
                                                }
                                            )
                                            Text(
                                                text = when (st.status) {
                                                    PipelineStageStatus.DONE -> "Done"
                                                    PipelineStageStatus.RUNNING -> st.progressPercent?.let { "$it%" } ?: "Working"
                                                    PipelineStageStatus.SKIPPED -> "Skipped"
                                                    else -> "Pending"
                                                },
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
        }

        // Clean Recent Projects Section
        if (projects.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Projects",
                        style = MaterialTheme.typography.titleLarge,
                        color = StudioColors.TextPrimary
                    )
                    Text(
                        text = "${projects.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioColors.TextMuted
                    )
                }
            }

            items(projects, key = { it.id }) { proj ->
                val isSelected = proj.id == activeProjectId
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) StudioColors.SurfaceElevated else StudioColors.SurfaceCard,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) StudioColors.AccentAmber.copy(alpha = 0.55f) else StudioColors.HairlineBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenProject(proj.id) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = proj.fileName,
                                style = MaterialTheme.typography.titleMedium,
                                color = StudioColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${proj.width}×${proj.height} · ${formatDuration(proj.durationSec)} · ${formatSize(proj.fileSizeBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioColors.TextSecondary
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onDeleteProject(proj.id) }) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete project",
                                    tint = StudioColors.TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.ChevronRight,
                                contentDescription = "Open project",
                                tint = StudioColors.AccentAmber,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Text(
                    text = String.format(
                        Locale.ROOT,
                        "Device ready · %d GB RAM · %.1f GB free",
                        device.totalRamGb,
                        device.freeStorageGb
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioColors.TextMuted
                )
            }
        }
    }
}

@Composable
private fun GuardrailBanner(message: String, isError: Boolean) {
    val tint = if (isError) StudioColors.ErrorCrimson else StudioColors.WarningAmber
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = StudioColors.TextPrimary
            )
        }
    }
}

internal fun formatDuration(sec: Double): String {
    val total = sec.coerceAtLeast(0.0)
    val mins = (total / 60).toInt()
    val rem = total - mins * 60
    return String.format(Locale.ROOT, "%02d:%04.1f", mins, rem)
}

internal fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.ROOT, "%.1f MB", mb)
    }
}
