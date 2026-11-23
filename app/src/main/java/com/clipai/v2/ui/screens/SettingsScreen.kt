package com.clipai.v2.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.clipai.v2.data.local.ModelStateEntity
import com.clipai.v2.data.prefs.UserSettings
import com.clipai.v2.domain.DeviceCapabilities
import com.clipai.v2.domain.ExportPreset
import com.clipai.v2.domain.FallbackPlan
import com.clipai.v2.ui.theme.StudioColors
import kotlin.math.roundToInt

private enum class SettingsTab(val label: String) {
    PREFERENCES("Preferences"),
    MODELS("AI Models")
}

@Composable
fun SettingsScreen(
    settings: UserSettings,
    device: DeviceCapabilities,
    fallbackPlan: FallbackPlan,
    modelStates: Map<String, ModelStateEntity>,
    hasHfToken: Boolean,
    activeModelInRam: String?,
    onUpdateSettings: ((UserSettings) -> UserSettings) -> Unit,
    onDownloadModel: (String) -> Unit,
    onPauseModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onSelectAsr: (String) -> Unit,
    onSelectLlm: (String) -> Unit,
    onToggleLlmEnabled: (Boolean) -> Unit,
    onSaveHfToken: (String) -> Unit,
    onClearHfToken: () -> Unit,
    onRefreshDevice: () -> Unit
) {
    var activeTab by remember { mutableStateOf(SettingsTab.PREFERENCES) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.ObsidianCanvas)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = StudioColors.TextPrimary)

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
                    SettingsTab.entries.forEach { tab ->
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

        if (activeTab == SettingsTab.MODELS) {
            ModelManagerScreen(
                modelStates = modelStates,
                device = device,
                fallbackPlan = fallbackPlan,
                settings = settings,
                hasHfToken = hasHfToken,
                activeModelInRam = activeModelInRam,
                onDownload = onDownloadModel,
                onPause = onPauseModel,
                onDelete = onDeleteModel,
                onSelectAsr = onSelectAsr,
                onSelectLlm = onSelectLlm,
                onToggleLlmEnabled = onToggleLlmEnabled,
                onSaveHfToken = onSaveHfToken,
                onClearHfToken = onClearHfToken,
                onRefreshDevice = onRefreshDevice
            )
            return
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                        Text("Export Quality", style = MaterialTheme.typography.titleMedium, color = StudioColors.TextPrimary)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ExportPreset.entries.forEach { preset ->
                                val selected = settings.exportPreset == preset
                                OutlinedButton(
                                    onClick = { onUpdateSettings { it.copy(exportPreset = preset) } },
                                    border = BorderStroke(1.dp, if (selected) StudioColors.AccentAmber else StudioColors.HairlineBorder),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = preset.label,
                                        color = if (selected) StudioColors.AccentAmber else StudioColors.TextSecondary,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Recommended for ${device.totalRamGb} GB RAM: ${fallbackPlan.recommendedPreset.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioColors.TextMuted
                        )
                    }
                }
            }

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
                        Text("Clipping Rules", style = MaterialTheme.typography.titleMedium, color = StudioColors.TextPrimary)

                        IntSliderSetting(
                            label = "Max Clips",
                            value = settings.maxClips,
                            valueText = "${settings.maxClips} clips",
                            range = 1f..10f,
                            onChange = { v -> onUpdateSettings { it.copy(maxClips = v) } }
                        )

                        IntSliderSetting(
                            label = "Minimum Gap Between Clips",
                            value = settings.dedupGapSec,
                            valueText = "${settings.dedupGapSec}s",
                            range = 10f..60f,
                            onChange = { v -> onUpdateSettings { it.copy(dedupGapSec = v) } }
                        )

                        IntSliderSetting(
                            label = "Minimum Duration",
                            value = settings.minClipSec,
                            valueText = "${settings.minClipSec}s",
                            range = 15f..45f,
                            onChange = { v -> onUpdateSettings { it.copy(minClipSec = v) } }
                        )

                        IntSliderSetting(
                            label = "Maximum Duration",
                            value = settings.maxClipSec,
                            valueText = "${settings.maxClipSec}s",
                            range = 40f..180f,
                            onChange = { v -> onUpdateSettings { it.copy(maxClipSec = v) } }
                        )
                    }
                }
            }

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
                        Text("Device Guardrails", style = MaterialTheme.typography.titleMedium, color = StudioColors.TextPrimary)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Wi-Fi only for models > 100 MB", style = MaterialTheme.typography.bodyMedium, color = StudioColors.TextPrimary)
                                Text("Saves mobile data for large model weights", style = MaterialTheme.typography.bodySmall, color = StudioColors.TextMuted)
                            }
                            Switch(
                                checked = settings.wifiOnlyOver100Mb,
                                onCheckedChange = { checked -> onUpdateSettings { it.copy(wifiOnlyOver100Mb = checked) } }
                            )
                        }

                        HorizontalDivider(color = StudioColors.HairlineBorder)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Warn below 30% battery", style = MaterialTheme.typography.bodyMedium, color = StudioColors.TextPrimary)
                                Text("Protects long exports from thermal shutdown", style = MaterialTheme.typography.bodySmall, color = StudioColors.TextMuted)
                            }
                            Switch(
                                checked = settings.requireChargingForHeavy,
                                onCheckedChange = { checked -> onUpdateSettings { it.copy(requireChargingForHeavy = checked) } }
                            )
                        }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StudioColors.SurfaceCard,
                    border = BorderStroke(1.dp, StudioColors.HairlineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Supported Inputs & Privacy", style = MaterialTheme.typography.titleMedium, color = StudioColors.TextPrimary)
                        Text("• Local videos (MP4/MOV/MKV) via Android Storage Access Framework.", style = MaterialTheme.typography.bodySmall, color = StudioColors.TextSecondary)
                        Text("• Public YouTube links, HLS (.m3u8) live streams, and direct MP4/WebM links.", style = MaterialTheme.typography.bodySmall, color = StudioColors.TextSecondary)
                        Text("• All clipping, framing, captions, and Media3 encoding run on-device.", style = MaterialTheme.typography.bodySmall, color = StudioColors.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun IntSliderSetting(
    label: String,
    value: Int,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = StudioColors.TextSecondary)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = StudioColors.TextPrimary)
        }
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = StudioColors.AccentAmber,
                activeTrackColor = StudioColors.AccentAmber
            )
        )
    }
}
