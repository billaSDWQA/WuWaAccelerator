package com.example.wuwaaccelerator

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuOverlayTextFormatterTest {

    @Test
    fun compactHudShowsDriverUtilizationAndActualClock() {
        val input = """
            GPU 驅動利用率（SGPU）：86%
            GPU 實際時脈 cur_freq：1300.0 MHz（1300000 kHz）
            GPU 最低頻率設定 min_freq：750.0 MHz
        """.trimIndent()

        assertEquals(
            "GPU 86%  |  1300.0 MHz（1300000 kHz）",
            GpuOverlayTextFormatter.compact(input)
        )
    }


    @Test
    fun compactHudHidesMetricsWhenSnapshotIsStale() {
        val input = """
            GPU 驅動利用率（SGPU）：86%
            GPU 實際時脈 cur_freq：1300.0 MHz（1300000 kHz）
            取樣新鮮度：資料已過期（4000 ms）
        """.trimIndent()

        assertEquals("GPU N/A  |  N/A", GpuOverlayTextFormatter.compact(input))
    }

    @Test
    fun compactHudShowsUnavailableInsteadOfInventingValues() {
        assertEquals(
            "GPU N/A  |  N/A",
            GpuOverlayTextFormatter.compact("GPU 驅動利用率（SGPU）：不可用")
        )
    }
}
