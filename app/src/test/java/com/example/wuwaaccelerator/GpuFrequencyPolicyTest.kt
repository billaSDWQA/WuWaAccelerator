package com.example.wuwaaccelerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuFrequencyPolicyTest {

    @Test
    fun acceptsKnownFrequencyValues() {
        listOf(
            "0", "450000", "544000", "648000", "750000", "864000",
            "960000", "1025000", "1152000", "1300000"
        ).forEach { assertTrue("Expected $it to be accepted", GpuFrequencyPolicy.isValid(it)) }
    }

    @Test
    fun rejectsMalformedOrUnknownValues() {
        listOf("", "-1", "750", "not-a-frequency", "999999").forEach {
            assertFalse("Expected $it to be rejected", GpuFrequencyPolicy.isValid(it))
        }
    }

    @Test
    fun raisesOnlyWhenOriginalIsBelowTestFloor() {
        assertEquals(true, GpuFrequencyPolicy.shouldRaise("544000"))
        assertEquals(true, GpuFrequencyPolicy.shouldRaise("648000"))
        assertEquals(false, GpuFrequencyPolicy.shouldRaise("750000"))
        assertEquals(false, GpuFrequencyPolicy.shouldRaise("864000"))
    }

    @Test
    fun refusesToChooseForUnknownOriginalValue() {
        assertNull(GpuFrequencyPolicy.shouldRaise(null))
        assertNull(GpuFrequencyPolicy.shouldRaise("999999"))
        assertNull(GpuFrequencyPolicy.shouldRaise("garbage"))
    }

    @Test
    fun rejectsTargetAboveCurrentMaximum() {
        assertFalse(
            GpuFrequencyPolicy.isHardwareTargetSupported(
                "750000", "648000", null
            )
        )
    }

    @Test
    fun rejectsTargetNotAdvertisedByDriver() {
        assertFalse(
            GpuFrequencyPolicy.isHardwareTargetSupported(
                "750000", "1300000",
                setOf("450000", "648000", "864000")
            )
        )
    }

    @Test
    fun acceptsTargetWithinHardwareLimits() {
        assertTrue(
            GpuFrequencyPolicy.isHardwareTargetSupported(
                "750000", "1300000",
                setOf("450000", "750000", "1300000")
            )
        )
    }

    @Test
    fun permitsUnknownOptionalHardwareNodes() {
        assertTrue(GpuFrequencyPolicy.isHardwareTargetSupported("750000", null, null))
        assertFalse(GpuFrequencyPolicy.isHardwareTargetSupported("750001", null, null))
    }
}
