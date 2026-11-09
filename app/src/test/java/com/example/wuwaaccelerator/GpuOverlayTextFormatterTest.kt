package com.example.wuwaaccelerator

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuOverlayTextFormatterTest {

    @Test
    fun compactHudAlwaysShowsCpuGpuTemperatureAndFrameMetrics() {
        val input = """
            GPU 驅動利用率（SGPU）：86%
            GPU 實際時脈 cur_freq：1300.0 MHz（1300000 kHz）
            GPU 溫度：52.0°C（GPU）
            CPU 整體使用率：63%
            policy0 [CPU 0-3]: 1800 MHz（min/max 300/1800 MHz）
            policy4 [CPU 4-7]: 2400 MHz（min/max 300/2400 MHz）
            平均 FPS：58.4
            1% Low FPS：42.0
        """.trimIndent()

        assertEquals(
            "GPU 86% | 1300.0 MHz | 52.0°C（GPU）\nCPU 63% | 1800 MHz/2400 MHz\nFPS 58.4 | 1% Low 42.0",
            GpuOverlayTextFormatter.compact(input)
        )
    }

    @Test
    fun compactHudMarksStaleGpuAndCpuReadingsUnavailable() {
        val input = """
            GPU 驅動利用率（SGPU）：86%
            GPU 實際時脈 cur_freq：1300.0 MHz（1300000 kHz）
            CPU 整體使用率：63%
            取樣新鮮度：資料已過期（4000 ms）
            CPU 監測：資料已過期
            平均 FPS：58.4
            1% Low FPS：42.0
        """.trimIndent()

        assertEquals(
            "GPU N/A | N/A | N/A\nCPU N/A | N/A\nFPS 58.4 | 1% Low 42.0",
            GpuOverlayTextFormatter.compact(input)
        )
    }

    @Test
    fun missingMetricsAreNeverInvented() {
        assertEquals(
            "GPU N/A | N/A | N/A\nCPU N/A | N/A\nFPS N/A | 1% Low N/A",
            GpuOverlayTextFormatter.compact("GPU 溫度：N/A")
        )
    }
}