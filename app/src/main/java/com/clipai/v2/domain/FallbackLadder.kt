package com.clipai.v2.domain

/**
 * Designed fallback ladder & hardware guardrail resolver:
 * - Refuses processing below 3.0 GB free storage
 * - 4 GB phones: Whisper Tiny only, LLM disabled, 720p fast export recommended
 * - 6 GB phones: Whisper Tiny default, optional Gemma 3 1B fallback
 * - 8 GB+ phones: Whisper Base opt-in, optional Gemma 4 E2B -> Gemma 3 1B -> Rule-based
 */
object FallbackLadder {
    const val MIN_FREE_STORAGE_GB = 3.0
    const val MODEL_WHISPER_TINY = "whisper-tiny-int8"
    const val MODEL_WHISPER_BASE = "whisper-base-int8"
    const val MODEL_MOONSHINE_TINY = "moonshine-tiny"
    const val MODEL_BLAZEFACE = "blazeface-mediapipe"
    const val MODEL_GEMMA_4_E2B = "gemma-4-e2b-int4"
    const val MODEL_GEMMA_3_1B = "gemma-3-1b-int4"

    fun resolve(
        device: DeviceCapabilities,
        installedModelIds: Set<String>,
        preferredAsrId: String,
        preferredLlmId: String,
        llmEnabledByUser: Boolean,
        requireChargingForHeavy: Boolean
    ): FallbackPlan {
        val storageBlockedReason = if (device.freeStorageGb < MIN_FREE_STORAGE_GB) {
            String.format(
                java.util.Locale.ROOT,
                "At least %.1f GB free storage is required before starting a job (currently %.1f GB free).",
                MIN_FREE_STORAGE_GB,
                device.freeStorageGb
            )
        } else {
            null
        }

        val batteryWarning = if (requireChargingForHeavy && !device.isCharging && device.batteryPercent <= 30) {
            "Battery is at ${device.batteryPercent}% and unplugged. Plug in charger for heavy stages."
        } else {
            null
        }

        // ASR Fallback ladder: Base (8GB+ only) -> Tiny -> Moonshine
        val resolvedAsr = when {
            preferredAsrId == MODEL_WHISPER_BASE &&
                device.totalRamGb >= 8 &&
                MODEL_WHISPER_BASE in installedModelIds -> MODEL_WHISPER_BASE

            preferredAsrId == MODEL_MOONSHINE_TINY &&
                MODEL_MOONSHINE_TINY in installedModelIds -> MODEL_MOONSHINE_TINY

            MODEL_WHISPER_TINY in installedModelIds -> MODEL_WHISPER_TINY
            MODEL_MOONSHINE_TINY in installedModelIds -> MODEL_MOONSHINE_TINY
            else -> MODEL_WHISPER_TINY
        }

        // LLM Fallback ladder: Gemma 4 E2B (8GB+) -> Gemma 3 1B (6GB+) -> Rule-based (null)
        val (resolvedLlm, llmDisabledReason) = when {
            !llmEnabledByUser -> null to "Rule-based scoring active (LLM disabled in settings)."
            device.totalRamGb < 6 -> null to "Phone RAM (${device.totalRamGb} GB) is below 6 GB; LLM disabled to prevent OOM."
            preferredLlmId == MODEL_GEMMA_4_E2B &&
                device.totalRamGb >= 8 &&
                MODEL_GEMMA_4_E2B in installedModelIds -> MODEL_GEMMA_4_E2B to null

            MODEL_GEMMA_3_1B in installedModelIds -> MODEL_GEMMA_3_1B to null
            device.totalRamGb >= 8 && MODEL_GEMMA_4_E2B in installedModelIds -> MODEL_GEMMA_4_E2B to null
            else -> null to "No LLM downloaded yet; using deterministic rule-based scoring."
        }

        val recommendedPreset = if (device.totalRamGb <= 4) {
            ExportPreset.FAST_720P
        } else {
            ExportPreset.STANDARD_1080P
        }

        return FallbackPlan(
            activeAsrModelId = resolvedAsr,
            activeLlmModelId = resolvedLlm,
            recommendedPreset = recommendedPreset,
            llmDisabledReason = llmDisabledReason,
            storageBlockedReason = storageBlockedReason,
            batteryWarning = batteryWarning
        )
    }

    fun canDownloadModel(
        model: ModelDescriptor,
        device: DeviceCapabilities,
        wifiOnlyOver100Mb: Boolean,
        hasUserHfToken: Boolean
    ): String? {
        if (device.freeStorageGb < MIN_FREE_STORAGE_GB) {
            return "Requires at least 3.0 GB free storage."
        }
        val sizeGb = model.sizeBytes / (1024.0 * 1024.0 * 1024.0)
        if (device.freeStorageGb - sizeGb < 1.5) {
            return "Not enough free storage to keep safe headroom after download."
        }
        if (device.totalRamGb < model.minDeviceRamGb) {
            return "Requires at least ${model.minDeviceRamGb} GB device RAM (detected ${device.totalRamGb} GB)."
        }
        if (wifiOnlyOver100Mb && model.sizeBytes > 100L * 1024L * 1024L && !device.isWifiConnected) {
            return "Connect to Wi-Fi or disable the >100 MB Wi-Fi-only guardrail in Settings."
        }
        if (model.category == ModelCategory.LLM && !device.isCharging) {
            return "Connect charger before downloading large LLM weights."
        }
        if (model.gatedHuggingFace && !hasUserHfToken) {
            return "Gated Hugging Face download: accept terms and paste your personal read token in Model Manager."
        }
        return null
    }
}
