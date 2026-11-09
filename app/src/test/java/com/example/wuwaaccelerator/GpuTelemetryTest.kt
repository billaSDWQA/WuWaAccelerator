package com.example.wuwaaccelerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuTelemetryTest {

    @Test
    fun collectsCurrentMinimumMaximumAndGovernorSeparately() {
        val values = mapOf(
            GpuSysfsPaths.MIN_FREQ to "750000",
            GpuSysfsPaths.CURRENT_FREQ to "648000",
            GpuSysfsPaths.MAX_FREQ to "1300000",
            GpuSysfsPaths.GOVERNOR to "sgpu_governor",
            GpuSysfsPaths.AVAILABLE_FREQUENCIES to "450000 544000 648000 750000 864000 1300000",
            GpuSysfsPaths.LOAD_CANDIDATES[0] to "78%"
        )
        val snapshot = GpuTelemetryCollector(
            readText = { values[it] },
            clockMs = { 1000L }
        ).sample()

        assertEquals("750000", snapshot.minFrequency?.normalizedValue)
        assertEquals("648000", snapshot.currentFrequency?.normalizedValue)
        assertEquals("1300000", snapshot.maxFrequency?.normalizedValue)
        assertEquals("sgpu_governor", snapshot.governor?.normalizedValue)
        assertEquals("78", snapshot.gpuLoad?.normalizedValue)
        assertEquals(GpuSysfsPaths.LOAD_CANDIDATES[0], snapshot.gpuLoad?.sourcePath)
    }

    @Test
    fun fallsThroughUnreadableOrUnparseableLoadCandidatesAndKeepsSource() {
        val values = mapOf(
            GpuSysfsPaths.LOAD_CANDIDATES[0] to "busy=bad",
            GpuSysfsPaths.LOAD_CANDIDATES[1] to "82%"
        )
        val snapshot = GpuTelemetryCollector(
            readText = { values[it] },
            clockMs = { 2000L }
        ).sample()

        assertEquals("82", snapshot.gpuLoad?.normalizedValue)
        assertEquals(GpuSysfsPaths.LOAD_CANDIDATES[1], snapshot.gpuLoad?.sourcePath)
        assertEquals("82%", snapshot.gpuLoad?.rawValue)
    }

    @Test
    fun doesNotTurnCountersOrInvalidValuesIntoZeroPercent() {
        val values = mapOf(
            GpuSysfsPaths.LOAD_CANDIDATES[0] to "600 800",
            GpuSysfsPaths.LOAD_CANDIDATES[1] to "101"
        )
        val snapshot = GpuTelemetryCollector(
            readText = { values[it] },
            clockMs = { 3000L }
        ).sample()

        assertNull(snapshot.gpuLoadPercent(3000L, 3000L))
        assertNull(snapshot.gpuLoad?.normalizedValue)
        assertEquals("600 800", snapshot.gpuLoad?.rawValue)
        assertEquals("原始格式無法解析", snapshot.gpuLoad?.error)
    }

    @Test
    fun reportsUnavailableInsteadOfFabricatingLoadWhenNoCandidateCanBeRead() {
        val snapshot = GpuTelemetryCollector(
            readText = { null },
            clockMs = { 4000L }
        ).sample()

        assertNull(snapshot.gpuLoadPercent(4000L, 3000L))
        assertTrue(snapshot.gpuLoad?.error?.contains("無法讀取") == true)
    }

    @Test
    fun displayDistinguishesActualClockFromMinimumFrequencyRequest() {
        val values = mapOf(
            GpuSysfsPaths.MIN_FREQ to "750000",
            GpuSysfsPaths.CURRENT_FREQ to "648000",
            GpuSysfsPaths.MAX_FREQ to "1300000",
            GpuSysfsPaths.GOVERNOR to "sgpu_governor",
            GpuSysfsPaths.AVAILABLE_FREQUENCIES to "450000 648000 750000 1300000",
            GpuSysfsPaths.LOAD_CANDIDATES[0] to "80"
        )
        val snapshot = GpuTelemetryCollector(
            readText = { values[it] },
            clockMs = { 1000L }
        ).sample()

        val output = snapshot.toDisplayText(5000L, monitorRunning = true)

        assertTrue(output.contains("資料已過期"))
        assertTrue(output.contains("GPU 實際時脈 cur_freq：648.0 MHz"))
        assertTrue(output.contains("GPU 最低頻率設定 min_freq：750.0 MHz"))
        assertTrue(output.contains("GPU 最高頻率上限 max_freq：1300.0 MHz"))
        assertTrue(output.contains("負載來源：" + GpuSysfsPaths.LOAD_CANDIDATES[0]))
        assertFalse(output.contains("GPU 實際時脈 cur_freq：750.0 MHz"))
    }

    @Test
    fun controllerReceivesOnlyFreshParsedLoadSamples() {
        val values = mapOf(GpuSysfsPaths.LOAD_CANDIDATES[0] to "75%")
        val snapshot = GpuTelemetryCollector(
            readText = { values[it] },
            clockMs = { 1000L }
        ).sample()

        assertEquals(75, snapshot.gpuLoadPercent(2000L, 2000L))
        assertNull(snapshot.gpuLoadPercent(5000L, 2000L))
    }
}
