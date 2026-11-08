package com.example.wuwaaccelerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpuAdaptivePolicyTest {

    @Test
    fun parsesThermalStatusAndRejectsOverridesOrMalformedValues() {
        assertEquals(0, GpuAdaptivePolicy.parseThermalStatus("Thermal Status: 0"))
        assertEquals(3, GpuAdaptivePolicy.parseThermalStatus("IsStatusOverride: false\nThermal Status: 3"))
        assertNull(GpuAdaptivePolicy.parseThermalStatus("IsStatusOverride: true\nThermal Status: 0"))
        assertNull(GpuAdaptivePolicy.parseThermalStatus("Thermal Status: 9"))
        assertNull(GpuAdaptivePolicy.parseThermalStatus("no thermal data"))
    }

    @Test
    fun parsesOnlyUnambiguousGpuPercentages() {
        assertEquals(97, GpuAdaptivePolicy.parseGpuLoadPercent("97"))
        assertEquals(82, GpuAdaptivePolicy.parseGpuLoadPercent("82%"))
        assertEquals(75, GpuAdaptivePolicy.parseGpuLoadPercent("75@960000"))
        assertNull(GpuAdaptivePolicy.parseGpuLoadPercent("75 100"))
        assertNull(GpuAdaptivePolicy.parseGpuLoadPercent("101"))
    }

    @Test
    fun classifiesMemoryPressureConservatively() {
        assertEquals(
            MemoryPressure.NORMAL,
            GpuAdaptivePolicy.classifyMemoryPressure(8_000_000, 2_000_000)
        )
        assertEquals(
            MemoryPressure.ELEVATED,
            GpuAdaptivePolicy.classifyMemoryPressure(8_000_000, 1_000_000)
        )
        assertEquals(
            MemoryPressure.CRITICAL,
            GpuAdaptivePolicy.classifyMemoryPressure(8_000_000, 500_000)
        )
        assertEquals(
            MemoryPressure.UNKNOWN,
            GpuAdaptivePolicy.classifyMemoryPressure(null, 500_000)
        )
    }

    @Test
    fun escalatesOnlyWithNormalThermalAndMemoryHeadroom() {
        assertEquals(
            GpuAdaptivePolicy.HIGH_FLOOR,
            GpuAdaptivePolicy.chooseTarget(0, 95, MemoryPressure.NORMAL)
        )
        assertEquals(
            GpuAdaptivePolicy.MID_FLOOR,
            GpuAdaptivePolicy.chooseTarget(0, 75, MemoryPressure.NORMAL)
        )
        assertEquals(
            GpuAdaptivePolicy.BASE_FLOOR,
            GpuAdaptivePolicy.chooseTarget(0, 30, MemoryPressure.NORMAL)
        )
        assertEquals(
            GpuAdaptivePolicy.BASE_FLOOR,
            GpuAdaptivePolicy.chooseTarget(1, 99, MemoryPressure.NORMAL)
        )
        assertEquals(
            GpuAdaptivePolicy.BASE_FLOOR,
            GpuAdaptivePolicy.chooseTarget(0, 99, MemoryPressure.ELEVATED)
        )
        assertNull(GpuAdaptivePolicy.chooseTarget(2, 99, MemoryPressure.NORMAL))
        assertNull(GpuAdaptivePolicy.chooseTarget(null, 99, MemoryPressure.NORMAL))
        assertNull(GpuAdaptivePolicy.chooseTarget(0, 99, MemoryPressure.CRITICAL))
    }

    @Test
    fun fallsBackToLowerDriverSupportedFloor() {
        assertEquals(
            GpuAdaptivePolicy.MID_FLOOR,
            GpuAdaptivePolicy.supportedTarget(
                GpuAdaptivePolicy.HIGH_FLOOR,
                "1300000",
                setOf("750000", "864000", "1300000")
            )
        )
        assertEquals(
            GpuAdaptivePolicy.BASE_FLOOR,
            GpuAdaptivePolicy.supportedTarget(
                GpuAdaptivePolicy.HIGH_FLOOR,
                "750000",
                setOf("450000", "750000")
            )
        )
        assertNull(
            GpuAdaptivePolicy.supportedTarget(
                GpuAdaptivePolicy.BASE_FLOOR,
                "648000",
                setOf("450000", "648000")
            )
        )
    }
}
