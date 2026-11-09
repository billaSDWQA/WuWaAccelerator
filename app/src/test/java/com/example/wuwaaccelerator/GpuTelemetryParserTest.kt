package com.example.wuwaaccelerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuTelemetryParserTest {

    @Test
    fun acceptsOnlyExplicitBoundedPercentages() {
        assertEquals(97, GpuTelemetryParser.parseLoadPercent("sgpu/load", "97").value)
        assertEquals(82, GpuTelemetryParser.parseLoadPercent("sgpu/gpu_busy_percent", "82%").value)
        assertEquals(75, GpuTelemetryParser.parseLoadPercent("sgpu/load", "75@960000").value)

        assertEquals(
            TelemetryStatus.MALFORMED,
            GpuTelemetryParser.parseLoadPercent("sgpu/load", "75 100").status
        )
        assertEquals(
            TelemetryStatus.MALFORMED,
            GpuTelemetryParser.parseLoadPercent("sgpu/load", "101").status
        )
        assertEquals(
            TelemetryStatus.MALFORMED,
            GpuTelemetryParser.parseLoadPercent("sgpu/load", "busy=75 total=100").status
        )
    }

    @Test
    fun preservesSourceAndRawValue() {
        val reading = GpuTelemetryParser.parseLoadPercent("/sys/example/gpu_busy_percent", "64%")
        assertEquals(TelemetryStatus.AVAILABLE, reading.status)
        assertEquals("/sys/example/gpu_busy_percent", reading.source)
        assertEquals("64%", reading.raw)
    }

    @Test
    fun loadSelectionSkipsMalformedSourceButKeepsProvenance() {
        val reading = GpuTelemetryParser.firstReadableLoad(listOf("first", "second")) {
            when (it) {
                "first" -> "75 100"
                "second" -> "88%"
                else -> null
            }
        }
        assertEquals(88, reading.value)
        assertEquals("second", reading.source)
    }

    @Test
    fun returnsMalformedInsteadOfInventingLoadWhenOnlyBadDataExists() {
        val reading = GpuTelemetryParser.firstReadableLoad(listOf("counter")) { "9000 10000" }
        assertEquals(TelemetryStatus.MALFORMED, reading.status)
        assertNull(reading.value)
    }

    @Test
    fun distinguishesCurrentClockFromSupportedFrequencyTable() {
        assertEquals(0L, GpuTelemetryParser.parseFrequencyKHz("cur_freq", "0", allowZero = true).value)
        assertEquals(960000L, GpuTelemetryParser.parseFrequencyKHz("cur_freq", "960000").value)
        assertEquals(listOf(450000L, 750000L, 960000L), GpuTelemetryParser
            .parseFrequencyTable("available_frequencies", "450000 750000 960000").value)
        assertEquals(
            TelemetryStatus.MALFORMED,
            GpuTelemetryParser.parseFrequencyTable("available_frequencies", "450000 nope").status
        )
    }

    @Test
    fun everyUnavailableReadingCarriesAReason() {
        val reading = GpuTelemetryParser.parseLoadPercent("gpu/load", null)
        assertEquals(TelemetryStatus.UNAVAILABLE, reading.status)
        assertTrue(!reading.reason.isNullOrBlank())
    }
}
