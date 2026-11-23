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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerifiedUser
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.clipai.v2.data.local.ModelStateEntity
import com.clipai.v2.data.models.ModelCatalog
import com.clipai.v2.data.prefs.UserSettings
import com.clipai.v2.domain.DeviceCapabilities
import com.clipai.v2.domain.DownloadStatus
import com.clipai.v2.domain.FallbackLadder
import com.clipai.v2.domain.FallbackPlan
import com.clipai.v2.domain.ModelCategory
import com.clipai.v2.domain.ModelDescriptor
import com.clipai.v2.ui.theme.StudioColors

@Composable
fun ModelManagerScreen(
    modelStates: Map<String, ModelStateEntity>,
    device: DeviceCapabilities,
    fallbackPlan: FallbackPlan,
    settings: UserSettings,
    hasHfToken: Boolean,
    activeModelInRam: String?,
    onDownload: (String) -> Unit,
    onPause: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSelectAsr: (String) -> Unit,
    onSelectLlm: (String) -> Unit,
    onToggleLlmEnabled: (Boolean) -> Unit,
    onSaveHfToken: (String) -> Unit,
    onClearHfToken: () -> Unit,
    onRefreshDevice: () -> Unit
) {
    var showTokenDialog by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.ObsidianCanvas),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Model Manager", style = MaterialTheme.typography.headlineMedium, color = StudioColors.TextPrimary)
                    Text(
                        text = "On-demand downloads into app-private storage · HTTP Range resume · SHA-256 verified",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary
                    )
                }
                IconButton(onClick = onRefreshDevice) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh hardware state", tint = StudioColors.TextSecondary)
                }
            }
        }

        // Hardware & Fallback Ladder Summary Card
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = StudioColors.SurfaceCard,
                border = BorderStroke(1.dp, StudioColors.HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Detected Hardware & Fallback Ladder", style = MaterialTheme.typography.titleMedium, color = StudioColors.TextPrimary)
                    Text(
                        text = String.format(
                            java.util.Locale.ROOT,
                            "RAM: %d GB · Free storage: %.1f GB · Active in RAM: %s",
                            device.totalRamGb,
                            device.freeStorageGb,
                            activeModelInRam ?: "None"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioColors.TextSecondary
                    )
                    Text(
                        text = "Resolved ASR: ${fallbackPlan.activeAsrModelId} · Resolved LLM: ${fallbackPlan.activeLlmModelId ?: "Rule-based (No LLM)"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioColors.AccentAmber
                    )

                    HorizontalDivider(color = StudioColors.HairlineBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable Optional LLM Re-ranking", style = MaterialTheme.typography.bodyMedium, color = StudioColors.TextPrimary)
                            Text(
                                text = if (device.totalRamGb < 6) "Blocked automatically below 6 GB RAM" else "Re-ranks top 10 candidates only via LiteRT-LM",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.TextMuted
                            )
                        }
                        Switch(
                            checked = settings.llmEnabled && device.totalRamGb >= 6,
                            enabled = device.totalRamGb >= 6,
                            onCheckedChange = onToggleLlmEnabled
                        )
                    }

                    HorizontalDivider(color = StudioColors.HairlineBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hugging Face Read Token (Gemma Gated)", style = MaterialTheme.typography.bodyMedium, color = StudioColors.TextPrimary)
                            Text(
                                text = if (hasHfToken) "Saved locally in EncryptedSharedPreferences" else "Not configured (optional — rule-based mode needs no token)",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioColors.TextMuted
                            )
                        }
                        TextButton(onClick = { showTokenDialog = true }) {
                            Icon(Icons.Outlined.Key, contentDescription = null, tint = StudioColors.AccentAmber, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (hasHfToken) "Update" else "Set Token", color = StudioColors.AccentAmber)
                        }
                    }
                }
            }
        }

        items(ModelCatalog.models, key = { it.id }) { descriptor ->
            val entity = modelStates[descriptor.id]
            val status = entity?.status?.let { s ->
                DownloadStatus.entries.firstOrNull { it.name == s }
            } ?: DownloadStatus.NOT_DOWNLOADED

            val blockReason = FallbackLadder.canDownloadModel(
                model = descriptor,
                device = device,
                wifiOnlyOver100Mb = settings.wifiOnlyOver100Mb,
                hasUserHfToken = hasHfToken
            )

            val isSelected = when (descriptor.category) {
                ModelCategory.ASR -> settings.selectedAsrModelId == descriptor.id
                ModelCategory.LLM -> settings.selectedLlmModelId == descriptor.id
                ModelCategory.VISION -> true
            }

            ModelRowCard(
                descriptor = descriptor,
                entity = entity,
                status = status,
                isSelected = isSelected,
                blockReason = blockReason,
                onDownload = { onDownload(descriptor.id) },
                onPause = { onPause(descriptor.id) },
                onDelete = { onDelete(descriptor.id) },
                onSelect = {
                    when (descriptor.category) {
                        ModelCategory.ASR -> onSelectAsr(descriptor.id)
                        ModelCategory.LLM -> onSelectLlm(descriptor.id)
                        ModelCategory.VISION -> {}
                    }
                }
            )
        }
    }

    if (showTokenDialog) {
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            containerColor = StudioColors.SurfaceCard,
            title = { Text("Hugging Face Read Token", color = StudioColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Accept Gemma terms on Hugging Face, then paste your personal read token. Stored only in Android EncryptedSharedPreferences and never logged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary
                    )
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text("hf_...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveHfToken(tokenInput)
                        tokenInput = ""
                        showTokenDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StudioColors.AccentAmber, contentColor = StudioColors.OnAmber)
                ) {
                    Text("Save Token")
                }
            },
            dismissButton = {
                Row {
                    if (hasHfToken) {
                        TextButton(onClick = {
                            onClearHfToken()
                            showTokenDialog = false
                        }) {
                            Text("Clear", color = StudioColors.ErrorCrimson)
                        }
                    }
                    TextButton(onClick = { showTokenDialog = false }) {
                        Text("Cancel", color = StudioColors.TextSecondary)
                    }
                }
            }
        )
    }
}

@Composable
private fun ModelRowCard(
    descriptor: ModelDescriptor,
    entity: ModelStateEntity?,
    status: DownloadStatus,
    isSelected: Boolean,
    blockReason: String?,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = StudioColors.SurfaceCard,
        border = BorderStroke(
            1.dp,
            if (isSelected && status == DownloadStatus.INSTALLED) StudioColors.AccentAmber.copy(alpha = 0.6f)
            else StudioColors.HairlineBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(descriptor.name, style = MaterialTheme.typography.titleMedium, color = StudioColors.TextPrimary)
                    Text("${descriptor.runtime} · ${descriptor.license}", style = MaterialTheme.typography.labelSmall, color = StudioColors.TextMuted)
                }
                if (status == DownloadStatus.INSTALLED && entity?.verifiedSha256 == true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = StudioColors.SuccessEmerald, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Verified", style = MaterialTheme.typography.labelSmall, color = StudioColors.SuccessEmerald)
                    }
                }
            }

            Text(descriptor.role, style = MaterialTheme.typography.bodySmall, color = StudioColors.TextSecondary)
            Text(
                text = "Size ${formatSize(descriptor.sizeBytes)} · Peak RAM ~${descriptor.peakRamMb} MB · Min ${descriptor.minDeviceRamGb} GB phone",
                style = MaterialTheme.typography.labelSmall,
                color = StudioColors.TextMuted
            )
            Text(descriptor.speedNote, style = MaterialTheme.typography.labelSmall, color = StudioColors.TextMuted)

            if (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PAUSED || status == DownloadStatus.VERIFYING_SHA256) {
                val downloaded = entity?.downloadedBytes ?: 0L
                val total = (entity?.totalBytes ?: descriptor.sizeBytes).coerceAtLeast(1L)
                val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = when (status) {
                            DownloadStatus.VERIFYING_SHA256 -> "Verifying SHA-256 digest..."
                            DownloadStatus.PAUSED -> "Paused (.part ${formatSize(downloaded)} / ${formatSize(total)})"
                            else -> "Downloading ${formatSize(downloaded)} / ${formatSize(total)} (${(progress * 100).toInt()}%)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioColors.AccentAmber
                    )
                    if (status == DownloadStatus.VERIFYING_SHA256) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), color = StudioColors.AccentAmber)
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = StudioColors.AccentAmber,
                            trackColor = StudioColors.SurfaceElevated
                        )
                    }
                }
            }

            if (status == DownloadStatus.FAILED && !entity?.lastError.isNullOrBlank()) {
                Text(entity?.lastError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = StudioColors.ErrorCrimson)
            } else if (status == DownloadStatus.NOT_DOWNLOADED && blockReason != null) {
                Text(blockReason, style = MaterialTheme.typography.bodySmall, color = StudioColors.WarningAmber)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (status) {
                    DownloadStatus.INSTALLED -> {
                        if (!isSelected && descriptor.category != ModelCategory.VISION) {
                            OutlinedButton(
                                onClick = onSelect,
                                border = BorderStroke(1.dp, StudioColors.AccentAmber),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = StudioColors.AccentAmber, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Set Active", color = StudioColors.AccentAmber)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete model", tint = StudioColors.TextMuted)
                        }
                    }

                    DownloadStatus.DOWNLOADING -> {
                        OutlinedButton(
                            onClick = onPause,
                            border = BorderStroke(1.dp, StudioColors.AccentAmber),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Outlined.Pause, contentDescription = null, tint = StudioColors.AccentAmber, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Pause (.part)", color = StudioColors.AccentAmber)
                        }
                    }

                    DownloadStatus.VERIFYING_SHA256 -> {
                        Text("Checking integrity...", style = MaterialTheme.typography.labelSmall, color = StudioColors.AccentAmber)
                    }

                    else -> {
                        Button(
                            onClick = onDownload,
                            enabled = blockReason == null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioColors.AccentAmber,
                                contentColor = StudioColors.OnAmber
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (status == DownloadStatus.PAUSED) "Resume" else "Download", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
