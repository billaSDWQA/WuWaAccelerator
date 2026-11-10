package com.example.wuwaaccelerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpuAdaptiveHysteresisTest {

    @Test
    fun raisesFloorOnFirstValidLoadSample() {
        val policy = GpuAdaptiveHysteresis()
        assertEquals(GpuAdaptivePolicy.BASE_FLOOR, policy.update(GpuAdaptivePolicy.BASE_FLOOR))
        assertEquals(GpuAdaptivePolicy.MID_FLOOR, policy.update(GpuAdaptivePolicy.MID_FLOOR))
    }

    @Test
    fun requiresFourConsecutiveSamplesBeforeLoweringFloor() {
        val policy = GpuAdaptiveHysteresis()
        policy.update(GpuAdaptivePolicy.BASE_FLOOR)
        policy.update(GpuAdaptivePolicy.HIGH_FLOOR)
        assertEquals(GpuAdaptivePolicy.HIGH_FLOOR, policy.update(GpuAdaptivePolicy.HIGH_FLOOR))
        assertEquals(GpuAdaptivePolicy.HIGH_FLOOR, policy.update(GpuAdaptivePolicy.BASE_FLOOR))
        assertEquals(GpuAdaptivePolicy.HIGH_FLOOR, policy.update(GpuAdaptivePolicy.BASE_FLOOR))
        assertEquals(GpuAdaptivePolicy.HIGH_FLOOR, policy.update(GpuAdaptivePolicy.BASE_FLOOR))
        assertEquals(GpuAdaptivePolicy.BASE_FLOOR, policy.update(GpuAdaptivePolicy.BASE_FLOOR))
    }

    @Test
    fun thermalGuardCanForceImmediateLowerFloor() {
        val policy = GpuAdaptiveHysteresis()
        policy.update(GpuAdaptivePolicy.HIGH_FLOOR)
        assertEquals(
            GpuAdaptivePolicy.BASE_FLOOR,
            policy.update(GpuAdaptivePolicy.BASE_FLOOR, forceImmediate = true)
        )
        assertNull(policy.update(null))
    }
}
