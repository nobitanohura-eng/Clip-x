package com.clipai.v2.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackLadderAndQaGateTest {

    @Test
    fun storageBelow3GbBlocksJobCleanly() {
        val lowStorageDevice = DeviceCapabilities(
            totalRamGb = 8,
            freeStorageGb = 2.4,
            isWifiConnected = true,
            isCharging = true,
            batteryPercent = 90
        )
        val plan = FallbackLadder.resolve(
            device = lowStorageDevice,
            installedModelIds = setOf(FallbackLadder.MODEL_WHISPER_TINY),
            preferredAsrId = FallbackLadder.MODEL_WHISPER_TINY,
            preferredLlmId = FallbackLadder.MODEL_GEMMA_4_E2B,
            llmEnabledByUser = false,
            requireChargingForHeavy = true
        )
        assertNotNull(plan.storageBlockedReason)
    }

    @Test
    fun fourGbPhoneEnforcesTinyOnlyNoLlmAnd720pPreset() {
        val minDevice = DeviceCapabilities(
            totalRamGb = 4,
            freeStorageGb = 8.0,
            isWifiConnected = true,
            isCharging = true,
            batteryPercent = 80
        )
        val plan = FallbackLadder.resolve(
            device = minDevice,
            installedModelIds = setOf(
                FallbackLadder.MODEL_WHISPER_TINY,
                FallbackLadder.MODEL_WHISPER_BASE,
                FallbackLadder.MODEL_GEMMA_4_E2B
            ),
            preferredAsrId = FallbackLadder.MODEL_WHISPER_BASE,
            preferredLlmId = FallbackLadder.MODEL_GEMMA_4_E2B,
            llmEnabledByUser = true,
            requireChargingForHeavy = true
        )
        assertEquals(FallbackLadder.MODEL_WHISPER_TINY, plan.activeAsrModelId)
        assertNull(plan.activeLlmModelId)
        assertEquals(ExportPreset.FAST_720P, plan.recommendedPreset)
        assertNotNull(plan.llmDisabledReason)
    }

    @Test
    fun sixGbPhoneFallsBackToGemma31BWhenEnabled() {
        val midDevice = DeviceCapabilities(6, 10.0, true, true, 85)
        val plan = FallbackLadder.resolve(
            device = midDevice,
            installedModelIds = setOf(
                FallbackLadder.MODEL_WHISPER_TINY,
                FallbackLadder.MODEL_GEMMA_3_1B,
                FallbackLadder.MODEL_GEMMA_4_E2B
            ),
            preferredAsrId = FallbackLadder.MODEL_WHISPER_TINY,
            preferredLlmId = FallbackLadder.MODEL_GEMMA_4_E2B,
            llmEnabledByUser = true,
            requireChargingForHeavy = true
        )
        assertEquals(FallbackLadder.MODEL_GEMMA_3_1B, plan.activeLlmModelId)
        assertNull(plan.llmDisabledReason)
    }

    @Test
    fun qaGateRejectsBlankThumbnailAndPassesValidVerticalExport() {
        val valid = QaGate.evaluate(
            durationSec = 42.0,
            width = 1080,
            height = 1920,
            fileSizeBytes = 42_000_000L,
            thumbnailLumaMean = 74.5f,
            measuredLufs = -14.0f,
            measuredTruePeakDbtp = -1.2f
        )
        assertTrue(valid.passed)
        assertNull(valid.failureSummary)

        val blankThumb = QaGate.evaluate(
            durationSec = 42.0,
            width = 1080,
            height = 1920,
            fileSizeBytes = 42_000_000L,
            thumbnailLumaMean = 1.0f,
            measuredLufs = -14.0f,
            measuredTruePeakDbtp = -1.2f
        )
        assertFalse(blankThumb.passed)
        assertFalse(blankThumb.thumbnailOk)
        assertNotNull(blankThumb.failureSummary)
    }
}
