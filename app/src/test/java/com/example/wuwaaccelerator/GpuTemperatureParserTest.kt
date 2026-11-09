package com.example.wuwaaccelerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpuTemperatureParserTest {
    @Test
    fun parsesGpuTemperatureInMillidegrees() {
        val result = GpuTemperatureParser.parse("gpu-therm", "52300")
        assertEquals(52.3, result!!.celsius, 0.001)
        assertEquals("gpu-therm", result.sensorType)
    }

    @Test
    fun parsesGpuTemperatureAlreadyInCelsius() {
        val result = GpuTemperatureParser.parse("XCLIPSE", "48")
        assertEquals(48.0, result!!.celsius, 0.001)
    }

    @Test
    fun neverTreatsCpuOrGenericSocAsGpuTemperature() {
        assertNull(GpuTemperatureParser.parse("cpu-therm", "60000"))
        assertNull(GpuTemperatureParser.parse("soc", "60000"))
    }

    @Test
    fun rejectsImplausibleValuesAndUnknownSensors() {
        assertNull(GpuTemperatureParser.parse("gpu-therm", "250000"))
        assertNull(GpuTemperatureParser.parse("unknown", "52000"))
    }
}