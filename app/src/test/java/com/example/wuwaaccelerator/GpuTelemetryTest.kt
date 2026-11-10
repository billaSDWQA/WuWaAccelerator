package com.example.wuwaaccelerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuTelemetryTest {

    @Test
    fun samplerReadsOnlyGpuLoadInputsNeededByController() {
        val requested = mutableListOf<String>()
        val values = mapOf(
            GpuSysfsPaths.CURRENT_UTILIZATION to "78",
            GpuSysfsPaths.UTILIZATION_SOURCE to "jiffies",
            GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES to "jiffies hw_counter"
        )
        val snapshot = GpuTelemetryCollector(
            readText = { requested += it; values[it] },
            clockMs = { 1000L }
        ).sample()

        assertEquals(78, snapshot.gpuLoadPercent(1000L, 3000L))
        assertEquals(
            setOf(
                GpuSysfsPaths.CURRENT_UTILIZATION,
                GpuSysfsPaths.UTILIZATION_SOURCE,
                GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES
            ) + GpuSysfsPaths.LOAD_CANDIDATES.drop(1),
            requested.toSet()
        )
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
    fun unverifiedCurrentUtilizationDoesNotMaskAUsableFallbackSource() {
        val values = mapOf(
            GpuSysfsPaths.CURRENT_UTILIZATION to "96",
            GpuSysfsPaths.UTILIZATION_SOURCE to "hw_counter",
            GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES to "hw_counter",
            GpuSysfsPaths.LOAD_CANDIDATES[1] to "84%"
        )
        val snapshot = GpuTelemetryCollector(
            readText = { values[it] },
            clockMs = { 3400L }
        ).sample()

        assertEquals(84, snapshot.gpuLoadPercent(3400L, 3000L))
        assertEquals(GpuSysfsPaths.LOAD_CANDIDATES[1], snapshot.gpuLoad?.sourcePath)
        assertTrue(snapshot.loadSourceReadings.first().error?.contains("未驗證 jiffies") == true)
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
        assertEquals(GpuSysfsPaths.CURRENT_UTILIZATION, snapshot.gpuLoad?.sourcePath)

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
        assertTrue(unverified.gpuLoad?.error?.contains("不交給自動調頻") == true)

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
                    GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES -> "jiffies hw_counter"
                    else -> null
                }
            },
            clockMs = { 4600L }
        ).sample()

        assertEquals(0, snapshot.gpuLoadPercent(4600L, 3000L))
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
