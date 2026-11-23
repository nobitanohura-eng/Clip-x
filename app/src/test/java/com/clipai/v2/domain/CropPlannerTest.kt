package com.clipai.v2.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropPlannerTest {

    @Test
    fun snapsImmediatelyAcrossSceneCutInsteadOfDrifting() {
        val samples = listOf(
            FaceSample(0.0, 0.30f, 0.95f),
            FaceSample(1.0, 0.31f, 0.95f),
            FaceSample(2.0, 0.72f, 0.95f), // After cut at 1.5s
            FaceSample(3.0, 0.73f, 0.95f)
        )
        val cuts = listOf(SceneCut(timeSec = 1.5, delta = 0.7f))

        val keyframes = CropPlanner.planSmoothedCrop(samples, cuts, clipStartSec = 0.0, clipEndSec = 4.0)
        val afterCut = keyframes.first { it.timeSec == 2.0 }

        assertTrue(afterCut.isCutBoundary)
        assertEquals(0.72f, afterCut.centerX, 0.001f)
    }

    @Test
    fun deadbandSuppressesMicroHeadNodJitter() {
        val samples = listOf(
            FaceSample(0.0, 0.50f, 0.95f),
            FaceSample(0.5, 0.51f, 0.95f), // Within 0.025 deadband
            FaceSample(1.0, 0.49f, 0.95f)  // Within 0.025 deadband
        )
        val keyframes = CropPlanner.planSmoothedCrop(samples, emptyList(), 0.0, 2.0)
        assertTrue(keyframes.all { it.centerX == 0.50f })
    }

    @Test
    fun manualOffsetOverrideIsSafelyClamped() {
        val keyframes = listOf(
            CropKeyframe(0.0, 0.80f, 0.9f, true),
            CropKeyframe(5.0, 0.80f, 0.9f, false)
        )
        val resolvedRight = CropPlanner.resolveCenterXAt(keyframes, 2.0, manualOffsetX = 0.30f)
        val resolvedLeft = CropPlanner.resolveCenterXAt(keyframes, 2.0, manualOffsetX = -0.90f)
        assertEquals(CropPlanner.MAX_CENTER_X, resolvedRight, 0.0001f)
        assertEquals(CropPlanner.MIN_CENTER_X, resolvedLeft, 0.0001f)
    }
}
