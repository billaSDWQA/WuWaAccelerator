package com.example.wuwaaccelerator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuWriteAccessPolicyTest {

    @Test
    fun shellUidFailsFastWhenSysfsNodeIsNotWritable() {
        assertFalse(GpuWriteAccessPolicy.canAttemptWrite(2000, 2000, 0, false))
    }

    @Test
    fun shellUidMayAttemptWriteOnlyWhenPreflightAllowsIt() {
        assertTrue(GpuWriteAccessPolicy.canAttemptWrite(2000, 2000, 0, true))
    }

    @Test
    fun rootUidDoesNotDependOnShellDacPreflight() {
        assertTrue(GpuWriteAccessPolicy.canAttemptWrite(0, 2000, 0, false))
    }

    @Test
    fun unexpectedUidIsRejected() {
        assertFalse(GpuWriteAccessPolicy.canAttemptWrite(10000, 2000, 0, true))
    }
}
