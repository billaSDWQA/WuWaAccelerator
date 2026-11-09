package com.example.wuwaaccelerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuTelemetryParserTest {
    @Test
    fun parsesPerCoreProcStatAndIgnoresAggregate() {
        val parsed = CpuTelemetryParser.parseProcStat(
            "cpu 100 0 20 300 5 0 0 0\n" +
                "cpu0 40 0 10 100 2 0 0 0\n" +
                "cpu1 60 0 10 200 3 0 0 0\n"
        )
        assertEquals(setOf(0, 1), parsed.keys)
        assertEquals(152L, parsed[0]?.total)
        assertEquals(102L, parsed[0]?.idle)
    }

    @Test
    fun rejectsMalformedAndNegativeCounters() {
        val parsed = CpuTelemetryParser.parseProcStat(
            "cpu2 1 2 nope 4\n" +
                "cpu3 1 2 3 4 -1\n" +
                "cpuX 1 2 3 4\n"
        )
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun calculatesDeltaBasedLoadWithoutAssumingCoreTopology() {
        var now = 1000L
        val samples = ArrayDeque(
            listOf(
                "cpu 100 0 20 300 5 0 0 0\ncpu2 40 0 10 100 2 0 0 0",
                "cpu 200 0 40 400 10 0 0 0\ncpu2 80 0 20 120 4 0 0 0"
            )
        )
        val collector = CpuTelemetryCollector(
            readText = { path ->
                when (path) {
                    "/proc/stat" -> samples.removeFirst()
                    "/sys/devices/system/cpu/online" -> "2"
                    else -> null
                }
            },
            listPolicyDirectories = { emptyList() },
            clockMs = { now++ }
        )
        assertEquals(null, collector.sample().overallUtilizationPercent)
        assertEquals(66, collector.sample().overallUtilizationPercent)
        assertEquals(66, collector.sample().coreLoads.single().utilizationPercent)
    }
}
