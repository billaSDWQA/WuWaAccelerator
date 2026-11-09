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
            GpuSysfsPaths.LOAD_CANDIDATES[0] to "78",
            GpuSysfsPaths.UTILIZATION_SOURCE to "jiffies",
            GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES to "jiffies hw_counter random swing fake_hw_counter"
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
    fun refusesAdaptiveLoadWhenCandidateSourcesDisagree() {
        val values = mapOf(
            GpuSysfsPaths.LOAD_CANDIDATES[0] to "92",
            GpuSysfsPaths.LOAD_CANDIDATES[1] to "41%",
            GpuSysfsPaths.UTILIZATION_SOURCE to "jiffies",
            GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES to "jiffies hw_counter"
        )
        val snapshot = GpuTelemetryCollector(
            readText = { values[it] },
            clockMs = { 3500L }
        ).sample()

        assertNull(snapshot.gpuLoadPercent(3500L, 3000L))
        assertTrue(snapshot.gpuLoad?.error?.contains("差異") == true)
        assertEquals(5, snapshot.loadSourceReadings.size)
    }

    @Test
    fun acceptsCandidateSourcesWhenExplicitPercentagesAgree() {
        val values = mapOf(
            GpuSysfsPaths.LOAD_CANDIDATES[0] to "78",
            GpuSysfsPaths.LOAD_CANDIDATES[1] to "84%",
            GpuSysfsPaths.UTILIZATION_SOURCE to "jiffies",
            GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES to "jiffies hw_counter"
        )
        val snapshot = GpuTelemetryCollector(
            readText = { values[it] },
            clockMs = { 3600L }
        ).sample()

        assertEquals(78, snapshot.gpuLoadPercent(3600L, 3000L))
        assertEquals(2, snapshot.loadSourceReadings.count { it.error == null })
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
            GpuSysfsPaths.LOAD_CANDIDATES[0] to "80",
            GpuSysfsPaths.UTILIZATION_SOURCE to "jiffies",
            GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES to "jiffies hw_counter"
        )
        val snapshot = GpuTelemetryCollector(
            readText = { values[it] },
            clockMs = { 1000L }
        ).sample()

        val output = snapshot.toDisplayText(2000L, monitorRunning = true)

        assertTrue(output.contains("資料新鮮"))
        assertTrue(output.contains("GPU 實際時脈 cur_freq：648.0 MHz"))
        assertTrue(output.contains("GPU 最低頻率設定 min_freq：750.0 MHz"))
        assertTrue(output.contains("GPU 最高頻率上限 max_freq：1300.0 MHz"))
        assertTrue(output.contains("負載來源：" + GpuSysfsPaths.LOAD_CANDIDATES[0]))
        assertFalse(output.contains("GPU 實際時脈 cur_freq：750.0 MHz"))
    }


    @Test
    fun staleSnapshotDoesNotDisplayOldGpuLoadClockOrCuAsCurrent() {
        val values = mapOf(
            GpuSysfsPaths.CURRENT_UTILIZATION to "86",
            GpuSysfsPaths.UTILIZATION_SOURCE to "jiffies",
            GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES to "jiffies hw_counter",
            GpuSysfsPaths.CURRENT_FREQ to "1300000",
            GpuSysfsPaths.CURRENT_CU_UTILIZATION to "35"
        )
        val snapshot = GpuTelemetryCollector(
            readText = { values[it] },
            clockMs = { 1000L }
        ).sample()

        val output = snapshot.toDisplayText(5000L, monitorRunning = true)
        assertTrue(output.contains("資料已過期"))
        assertTrue(output.contains("GPU 驅動利用率（SGPU）：不可用"))
        assertTrue(output.contains("GPU CU 利用率（輔助指標，非整體 GPU 使用率）：不可用"))
        assertTrue(output.contains("GPU 實際時脈 cur_freq：資料已過期"))
        assertTrue(output.contains("資料已過期或時間戳異常"))
        assertNull(snapshot.gpuLoadPercent(5000L, GpuTelemetrySnapshot.STALE_AFTER_MS))
    }

    @Test
    fun currentUtilizationIsAcceptedOnlyWhenDriverModeIsVerifiedAsJiffies() {
        val values = mapOf(
            GpuSysfsPaths.CURRENT_UTILIZATION to "86",
            GpuSysfsPaths.UTILIZATION_SOURCE to "jiffies",
            GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES to
                "jiffies hw_counter random swing fake_hw_counter"
        )
        val snapshot = GpuTelemetryCollector(
            readText = { values[it] },
            clockMs = { 4500L }
        ).sample()

        assertEquals(86, snapshot.gpuLoadPercent(4500L, 3000L))
        assertEquals("jiffies", snapshot.utilizationSource?.normalizedValue)
        assertEquals(
            GpuSysfsPaths.CURRENT_UTILIZATION,
            snapshot.gpuLoad?.sourcePath
        )
        assertTrue(snapshot.toDisplayText(4500L, true).contains("utilization_source：jiffies"))
        assertTrue(snapshot.toDisplayText(4500L, true).contains("驅動支援的負載來源：jiffies hw_counter random swing fake_hw_counter"))

        val unverified = GpuTelemetryCollector(
            readText = { path ->
                when (path) {
                    GpuSysfsPaths.CURRENT_UTILIZATION -> "86"
                    GpuSysfsPaths.UTILIZATION_SOURCE -> "hw_counter"
                    else -> null
                }
            },
            clockMs = { 4500L }
        ).sample()
        assertNull(unverified.gpuLoadPercent(4500L, 3000L))
        assertTrue(unverified.toDisplayText(4500L, true).contains("不交給自動調頻"))

        val notAdvertised = GpuTelemetryCollector(
            readText = { path ->
                when (path) {
                    GpuSysfsPaths.CURRENT_UTILIZATION -> "86"
                    GpuSysfsPaths.UTILIZATION_SOURCE -> "jiffies"
                    GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES -> "hw_counter random"
                    else -> null
                }
            },
            clockMs = { 4500L }
        ).sample()
        assertNull(notAdvertised.gpuLoadPercent(4500L, 3000L))
    }

    @Test
    fun rejectsNonIntegerAndOutOfRangeSgpuUtilizationValues() {
        listOf("86%", "86 100", "101", "-1", "86.5").forEach { raw ->
            val snapshot = GpuTelemetryCollector(
                readText = { path ->
                    when (path) {
                        GpuSysfsPaths.CURRENT_UTILIZATION -> raw
                        GpuSysfsPaths.UTILIZATION_SOURCE -> "jiffies"
                        GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES -> "jiffies hw_counter"
                        else -> null
                    }
                },
                clockMs = { 4550L }
            ).sample()
            assertNull("raw=$raw", snapshot.gpuLoadPercent(4550L, 3000L))
        }
    }

    @Test
    fun idleZeroUtilizationRemainsAValidZeroPercentReading() {
        val snapshot = GpuTelemetryCollector(
            readText = { path ->
                when (path) {
                    GpuSysfsPaths.CURRENT_UTILIZATION -> "0"
                    GpuSysfsPaths.UTILIZATION_SOURCE -> "jiffies"
                    GpuSysfsPaths.CURRENT_FREQ -> "0"
                    GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES -> "jiffies hw_counter"
                    GpuSysfsPaths.CURRENT_CU_UTILIZATION -> "0"
                    else -> null
                }
            },
            clockMs = { 4600L }
        ).sample()

        assertEquals(0, snapshot.gpuLoadPercent(4600L, 3000L))
        assertTrue(snapshot.toDisplayText(4600L, true).contains("GPU 驅動利用率（SGPU）：0%"))
        assertTrue(snapshot.toDisplayText(4600L, true).contains("GPU CU 利用率（輔助指標，非整體 GPU 使用率）：0%"))
        assertTrue(snapshot.toDisplayText(4600L, true).contains("0 kHz（閒置或驅動未回報）"))
    }

    @Test
    fun controllerReceivesOnlyFreshParsedLoadSamples() {
        val values = mapOf(
            GpuSysfsPaths.LOAD_CANDIDATES[0] to "75",
            GpuSysfsPaths.UTILIZATION_SOURCE to "jiffies",
            GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES to "jiffies hw_counter"
        )
        val snapshot = GpuTelemetryCollector(
            readText = { values[it] },
            clockMs = { 1000L }
        ).sample()

        assertEquals(75, snapshot.gpuLoadPercent(2000L, 2000L))
        assertNull(snapshot.gpuLoadPercent(500L, 2000L))
        assertNull(snapshot.gpuLoadPercent(5000L, 2000L))
        assertNull(snapshot.gpuLoadPercent(2000L, -1L))
    }
}
