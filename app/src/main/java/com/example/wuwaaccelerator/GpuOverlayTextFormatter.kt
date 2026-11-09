package com.example.wuwaaccelerator

/** Formats the read-only telemetry string for the compact floating HUD. */
internal object GpuOverlayTextFormatter {

    fun compact(snapshotText: String): String {
        val lines = snapshotText.lineSequence().map(String::trim).toList()
        val stale = lines.any {
            it.startsWith("取樣新鮮度：") &&
                (it.contains("資料已過期") || it.contains("時間戳異常"))
        }
        val load = if (stale) null else lines.firstOrNull {
            it.startsWith("GPU 驅動利用率（SGPU）：") || it.startsWith("GPU 負載：")
        }?.substringAfter("：")
        val clock = if (stale) null else lines.firstOrNull {
            it.startsWith("GPU 實際時脈 cur_freq：")
        }?.substringAfter("：")

        fun displayValue(value: String?): String {
            val normalized = value?.trim()
            return normalized?.takeIf {
                it.isNotEmpty() &&
                    !it.equals("不可用", ignoreCase = true) &&
                    !it.equals("unavailable", ignoreCase = true) &&
                    !it.equals("n/a", ignoreCase = true)
            } ?: "N/A"
        }

        val loadValue = displayValue(load)
        val clockValue = displayValue(clock)
        return "GPU $loadValue  |  $clockValue"
    }
}
