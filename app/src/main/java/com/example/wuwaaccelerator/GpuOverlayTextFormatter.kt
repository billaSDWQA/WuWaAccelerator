package com.example.wuwaaccelerator

/** Formats only the essential live metrics for the compact in-game HUD. */
internal object GpuOverlayTextFormatter {

    fun compact(snapshotText: String): String {
        val lines = snapshotText.lineSequence().map(String::trim).toList()
        val gpuStale = lines.any {
            it.startsWith("取樣新鮮度：") &&
                (it.contains("資料已過期") || it.contains("時間戳異常"))
        }
        val cpuStale = lines.any { it == "CPU 監測：資料已過期" }

        fun value(prefix: String, stale: Boolean = false): String {
            if (stale) return "N/A"
            return lines.firstOrNull { it.startsWith(prefix) }
                ?.substringAfter("：")
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty() &&
                        !it.equals("不可用", ignoreCase = true) &&
                        !it.equals("unavailable", ignoreCase = true) &&
                        !it.equals("n/a", ignoreCase = true)
                } ?: "N/A"
        }

        val gpuLoad = value("GPU 驅動利用率（SGPU）：", gpuStale)
            .takeIf { it != "N/A" } ?: value("GPU 負載：", gpuStale)
        val gpuClockRaw = value("GPU 實際時脈 cur_freq：", gpuStale)
        val gpuClock = Regex("""^([0-9.]+\\s*MHz)""").find(gpuClockRaw)?.groupValues?.get(1) ?: gpuClockRaw
        val gpuTemp = value("GPU 溫度：")
        val cpuLoad = value("CPU 整體使用率：", cpuStale)
        val cpuFrequencies = if (cpuStale) "N/A" else {
            lines.filter { it.startsWith("policy") && it.contains("[CPU ") }
                .mapNotNull { Regex(""":\\s*([0-9.]+\\s*MHz)""").find(it)?.groupValues?.get(1) }
                .distinct()
                .joinToString("/")
                .ifBlank { "N/A" }
        }
        val averageFps = value("平均 FPS：")
        val onePercentLow = value("1% Low FPS：")
            .replace("樣本不足（至少 100 幀）", "N/A")

        return "GPU $gpuLoad | $gpuClock | $gpuTemp\nCPU $cpuLoad | $cpuFrequencies\nFPS $averageFps | 1% Low $onePercentLow"
    }
}