package com.example.wuwaaccelerator

import org.json.JSONObject

internal enum class TelemetryStatus {
    AVAILABLE,
    UNAVAILABLE,
    MALFORMED
}

internal data class TelemetryReading<T>(
    val status: TelemetryStatus,
    val value: T? = null,
    val source: String? = null,
    val raw: String? = null,
    val reason: String? = null
)

internal data class GpuTelemetrySnapshot(
    val sampledAtMs: Long,
    val loadPercent: TelemetryReading<Int>,
    val currentFrequencyKHz: TelemetryReading<Long>,
    val minimumFrequencyKHz: TelemetryReading<Long>,
    val maximumFrequencyKHz: TelemetryReading<Long>,
    val governor: TelemetryReading<String>,
    val availableFrequenciesKHz: TelemetryReading<List<Long>>
) {
    fun toJson(): String {
        val json = JSONObject().put("sampledAtMs", sampledAtMs)
        putMetric(json, "load", loadPercent, loadPercent.value?.toString())
        putMetric(json, "currentFrequencyKHz", currentFrequencyKHz, currentFrequencyKHz.value?.toString())
        putMetric(json, "minimumFrequencyKHz", minimumFrequencyKHz, minimumFrequencyKHz.value?.toString())
        putMetric(json, "maximumFrequencyKHz", maximumFrequencyKHz, maximumFrequencyKHz.value?.toString())
        putMetric(json, "governor", governor, governor.value)
        putMetric(
            json,
            "availableFrequenciesKHz",
            availableFrequenciesKHz,
            availableFrequenciesKHz.value?.joinToString(",")
        )
        return json.toString()
    }

    private fun putMetric(
        json: JSONObject,
        key: String,
        reading: TelemetryReading<*>,
        displayValue: String?
    ) {
        json.put("${key}Status", reading.status.name)
        json.put(key, displayValue ?: JSONObject.NULL)
        json.put("${key}Source", reading.source ?: JSONObject.NULL)
        json.put("${key}Raw", reading.raw ?: JSONObject.NULL)
        json.put("${key}Reason", reading.reason ?: JSONObject.NULL)
    }

    companion object {
        fun unavailable(sampledAtMs: Long, reason: String): GpuTelemetrySnapshot {
            fun <T> missing() = TelemetryReading<T>(TelemetryStatus.UNAVAILABLE, reason = reason)
            return GpuTelemetrySnapshot(
                sampledAtMs,
                missing(),
                missing(),
                missing(),
                missing(),
                missing(),
                missing()
            )
        }
    }
}

internal object GpuTelemetryParser {
    private val percentage = Regex("^\\s*(\\d{1,3})\\s*%?\\s*$")
    private val clockPercentage = Regex("^\\s*(\\d{1,3})\\s*@\\s*\\d+.*$")

    /** Only explicit, bounded percentages are accepted. Busy/total counters are not. */
    fun parseLoadPercent(source: String, raw: String?): TelemetryReading<Int> {
        if (raw.isNullOrBlank()) {
            return TelemetryReading(TelemetryStatus.UNAVAILABLE, source = source, reason = "節點沒有資料")
        }
        val value = percentage.matchEntire(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: clockPercentage.matchEntire(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return if (value != null && value in 0..100) {
            TelemetryReading(TelemetryStatus.AVAILABLE, value, source, raw, "百分比格式")
        } else {
            TelemetryReading(
                TelemetryStatus.MALFORMED,
                source = source,
                raw = raw.take(160),
                reason = "格式不是可確認的 0–100% 百分比；不當作負載使用"
            )
        }
    }

    fun firstReadableLoad(
        paths: List<String>,
        read: (String) -> String?
    ): TelemetryReading<Int> {
        var malformed: TelemetryReading<Int>? = null
        for (path in paths) {
            val raw = read(path) ?: continue
            val parsed = parseLoadPercent(path, raw)
            if (parsed.status == TelemetryStatus.AVAILABLE) return parsed
            if (malformed == null) malformed = parsed
        }
        return malformed ?: TelemetryReading(
            TelemetryStatus.UNAVAILABLE,
            reason = "候選 GPU 負載節點皆不可讀"
        )
    }

    fun parseFrequencyKHz(
        source: String,
        raw: String?,
        allowZero: Boolean = false
    ): TelemetryReading<Long> {
        if (raw.isNullOrBlank()) {
            return TelemetryReading(TelemetryStatus.UNAVAILABLE, source = source, reason = "節點不可讀")
        }
        val value = raw.trim().toLongOrNull()
        val valid = value != null && if (allowZero) value >= 0L else value > 0L
        return if (valid) {
            TelemetryReading(TelemetryStatus.AVAILABLE, value, source, raw, "kHz")
        } else {
            TelemetryReading(
                TelemetryStatus.MALFORMED,
                source = source,
                raw = raw.take(160),
                reason = "不是有效的頻率數值"
            )
        }
    }

    fun parseGovernor(source: String, raw: String?): TelemetryReading<String> =
        if (raw.isNullOrBlank()) {
            TelemetryReading(TelemetryStatus.UNAVAILABLE, source = source, reason = "節點不可讀")
        } else {
            TelemetryReading(TelemetryStatus.AVAILABLE, raw.trim(), source, raw, "文字")
        }

    fun parseFrequencyTable(
        source: String,
        raw: String?
    ): TelemetryReading<List<Long>> {
        if (raw.isNullOrBlank()) {
            return TelemetryReading(TelemetryStatus.UNAVAILABLE, source = source, reason = "頻率表不可讀")
        }
        val values = raw.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        val tokens = raw.trim().split(Regex("\\s+"))
        return if (values.isNotEmpty() && values.size == tokens.size && values.all { it > 0L }) {
            TelemetryReading(TelemetryStatus.AVAILABLE, values, source, raw, "kHz 支援頻率表")
        } else {
            TelemetryReading(
                TelemetryStatus.MALFORMED,
                source = source,
                raw = raw.take(160),
                reason = "頻率表含有無法解析或非正數項目"
            )
        }
    }
}
