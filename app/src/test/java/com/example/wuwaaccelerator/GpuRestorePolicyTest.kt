package com.example.wuwaaccelerator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuRestorePolicyTest {

    private val record = GpuRestoreRecord(
        original = "450000",
        lastApplied = "960000",
        previousApplied = "864000"
    )

    @Test
    fun allowsRestoreOnlyForOriginalOrLastKnownAppValues() {
        assertTrue(GpuRestorePolicy.mayRestore("450000", record))
        assertTrue(GpuRestorePolicy.mayRestore("960000", record))
        assertTrue(GpuRestorePolicy.mayRestore("864000", record))
        assertFalse(GpuRestorePolicy.mayRestore("750000", record))
        assertFalse(GpuRestorePolicy.mayRestore(null, record))
    }

    @Test
    fun identifiesWhenAWriteIsNeeded() {
        assertFalse(GpuRestorePolicy.needsWrite("450000", record))
        assertTrue(GpuRestorePolicy.needsWrite("960000", record))
    }
}
