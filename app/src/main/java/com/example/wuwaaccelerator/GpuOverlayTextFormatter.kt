package com.example.wuwaaccelerator

/** Formats the read-only telemetry string for the compact floating HUD. */
internal object GpuOverlayTextFormatter {

    fun compact(snapshotText: String): String {
        val lines = snapshotText.lineSequence().map(String::trim).toList()
        val load = lines.firstOrNull {
            it.startsWith("GPU 驅動利用率（SGPU）：") || it.startsWith("GPU 負載：")
        }?.substringAfter("：")
        val clock = lines.firstOrNull {
            it.startsWith("GPU 實際時脈 cur_freq：")
        }?.substringAfter("：")

        val loadValue = load?.takeIf { it.isNotBlank() } ?: "N/A"
        val clockValue = clock?.takeIf { it.isNotBlank() } ?: "N/A"
        return "GPU $loadValue  |  $clockValue"
    }
}
