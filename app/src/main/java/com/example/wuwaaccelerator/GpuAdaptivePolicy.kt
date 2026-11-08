package com.example.wuwaaccelerator

internal enum class MemoryPressure {
    NORMAL,
    ELEVATED,
    CRITICAL,
    UNKNOWN
}

/**
 * Conservative adaptive GPU floor policy for the SM-A5560.
 *
 * The requested floor is only a devfreq minimum. The kernel governor and
 * Android thermal framework retain control of the actual operating clock.
 */
internal object GpuAdaptivePolicy {
    const val BASE_FLOOR = "750000"
    const val MID_FLOOR = "864000"
    const val HIGH_FLOOR = "960000"

    private val thermalStatusPattern =
        Regex("(?m)^\\s*Thermal Status:\\s*(\\d+)\\s*$")
    private val thermalOverridePattern =
        Regex("(?im)^\\s*IsStatusOverride:\\s*true\\s*$")
    private val exactLoadPattern =
        Regex("^\\s*(\\d{1,3})\\s*%?\\s*$")
    private val clockLoadPattern =
        Regex("^\\s*(\\d{1,3})\\s*@\\s*\\d+.*$")

    fun parseThermalStatus(output: String?): Int? {
        if (output.isNullOrBlank() || thermalOverridePattern.containsMatchIn(output)) {
            return null
        }
        val value = thermalStatusPattern.find(output)?.groupValues?.getOrNull(1)
            ?.toIntOrNull() ?: return null
        return value.takeIf { it in 0..6 }
    }

    /** Accept only an unambiguous percentage, not arbitrary busy/total counters. */
    fun parseGpuLoadPercent(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val value = exactLoadPattern.matchEntire(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: clockLoadPattern.matchEntire(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?:^|[^0-9])(\\d{1,3})\\s*%").find(raw)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        return value.takeIf { it in 0..100 }
    }

    fun classifyMemoryPressure(totalKb: Long?, availableKb: Long?): MemoryPressure {
        if (totalKb == null || availableKb == null || totalKb <= 0L ||
            availableKb < 0L || availableKb > totalKb
        ) return MemoryPressure.UNKNOWN

        val availablePercent = availableKb.toDouble() / totalKb.toDouble()
        return when {
            availablePercent < 0.08 || availableKb < 600L * 1024L ->
                MemoryPressure.CRITICAL
            availablePercent < 0.15 || availableKb < 1200L * 1024L ->
                MemoryPressure.ELEVATED
            else -> MemoryPressure.NORMAL
        }
    }

    /**
     * NONE permits load-based escalation. LIGHT limits the request to baseline.
     * MODERATE or worse, or an unknown thermal reading, disables the boost.
     * Memory pressure never triggers third-party process killing.
     */
    fun chooseTarget(
        thermalStatus: Int?,
        gpuLoadPercent: Int?,
        memoryPressure: MemoryPressure
    ): String? {
        if (thermalStatus == null || thermalStatus !in 0..1) return null
        if (memoryPressure == MemoryPressure.CRITICAL) return null

        if (thermalStatus == 1 || memoryPressure != MemoryPressure.NORMAL) {
            return BASE_FLOOR
        }

        return when {
            gpuLoadPercent != null && gpuLoadPercent >= 90 -> HIGH_FLOOR
            gpuLoadPercent != null && gpuLoadPercent >= 70 -> MID_FLOOR
            else -> BASE_FLOOR
        }
    }

    /** Fall back to the next lower supported floor instead of failing the session. */
    fun supportedTarget(
        requested: String,
        maximum: String?,
        availableFrequencies: Set<String>?
    ): String? {
        val candidates = when (requested) {
            HIGH_FLOOR -> listOf(HIGH_FLOOR, MID_FLOOR, BASE_FLOOR)
            MID_FLOOR -> listOf(MID_FLOOR, BASE_FLOOR)
            BASE_FLOOR -> listOf(BASE_FLOOR)
            else -> emptyList()
        }
        return candidates.firstOrNull {
            GpuFrequencyPolicy.isHardwareTargetSupported(it, maximum, availableFrequencies)
        }
    }

    fun thermalLabel(status: Int?): String = when (status) {
        0 -> "正常"
        1 -> "輕度"
        2 -> "中度"
        3 -> "嚴重"
        4 -> "危急"
        5 -> "緊急"
        6 -> "關機級"
        else -> "未知"
    }

    fun memoryLabel(pressure: MemoryPressure): String = when (pressure) {
        MemoryPressure.NORMAL -> "正常"
        MemoryPressure.ELEVATED -> "偏緊"
        MemoryPressure.CRITICAL -> "不足"
        MemoryPressure.UNKNOWN -> "未知"
    }
}
