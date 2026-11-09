package com.example.wuwaaccelerator

import android.os.SystemClock
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Canonical SGPU sysfs paths shared by telemetry and the adaptive controller.
 * Candidate load nodes keep the 0.7 order and parser semantics.
 */
internal object GpuSysfsPaths {
    private const val NODE = "/sys/class/devfreq/10e00000.sgpu"

    const val MIN_FREQ = "$NODE/interface/min_freq"
    const val MAX_FREQ = "$NODE/max_freq"
    const val CURRENT_FREQ = "$NODE/cur_freq"
    const val GOVERNOR = "$NODE/governor"
    const val AVAILABLE_FREQUENCIES = "$NODE/available_frequencies"
    const val CURRENT_UTILIZATION = "$NODE/interface/current_utilization"
    const val CURRENT_CU_UTILIZATION = "$NODE/interface/current_cu_utilization"
    const val UTILIZATION_SOURCE = "$NODE/interface/utilization_source"
    const val AVAILABLE_UTILIZATION_SOURCES = "$NODE/interface/available_utilization_sources"

    val LOAD_CANDIDATES = listOf(
        CURRENT_UTILIZATION,
        "$NODE/load",
        "$NODE/gpu_load",
        "$NODE/gpu_busy_percent",
        "/sys/class/drm/card0/device/gpu_busy_percent"
    )
}

internal data class GpuTelemetryMetric(
    val sourcePath: String,
    val rawValue: String?,
    val normalizedValue: String?,
    val unit: String?,
    val sampledAtElapsedMs: Long,
    val error: String? = null
)

internal data class GpuTelemetrySnapshot(
    val capturedAtElapsedMs: Long,
    val minFrequency: GpuTelemetryMetric?,
    val currentFrequency: GpuTelemetryMetric?,
    val maxFrequency: GpuTelemetryMetric?,
    val governor: GpuTelemetryMetric?,
    val availableFrequencies: GpuTelemetryMetric?,
    val gpuLoad: GpuTelemetryMetric?,
    val sampleError: String? = null,
    val loadSourceReadings: List<GpuTelemetryMetric> = emptyList(),
    val utilizationSource: GpuTelemetryMetric? = null,
    val availableUtilizationSources: GpuTelemetryMetric? = null,
    val computeUnitUtilization: GpuTelemetryMetric? = null
) {
    fun gpuLoadPercent(nowElapsedMs: Long, maximumAgeMs: Long): Int? {
        if (capturedAtElapsedMs <= 0L || maximumAgeMs < 0L || nowElapsedMs < capturedAtElapsedMs) {
            return null
        }
        val reading = gpuLoad?.takeIf { it.error == null } ?: return null
        val sampledAt = reading.sampledAtElapsedMs
        if (sampledAt <= 0L || nowElapsedMs < sampledAt || nowElapsedMs - sampledAt > maximumAgeMs) {
            return null
        }
        // The A55's current_utilization is only trusted after confirming both
        // the active driver mode and that the driver advertises that mode.
        if (reading.sourcePath == GpuSysfsPaths.CURRENT_UTILIZATION &&
            !isVerifiedSgpuJiffiesSource()
        ) return null
        return reading.normalizedValue
            ?.toIntOrNull()
            ?.takeIf { it in 0..100 }
    }

    private fun isVerifiedSgpuJiffiesSource(): Boolean {
        if (utilizationSource?.normalizedValue != "jiffies") return false
        val supported = availableUtilizationSources
            ?.takeIf { it.error == null }
            ?.normalizedValue
            ?.split(Regex("\\s+"))
            ?.toSet()
            ?: return false
        return "jiffies" in supported
    }

    fun compactSummary(): String {
        if (capturedAtElapsedMs <= 0L) return "SGPU 遙測尚未完成"
        val min = formatFrequency(minFrequency)
        val current = formatCurrentFrequency(currentFrequency)
        val max = formatFrequency(maxFrequency)
        return "SGPU min_freq=$min, cur_freq=$current, max_freq=$max"
    }

    fun toDisplayText(nowElapsedMs: Long, monitorRunning: Boolean): String {
        if (capturedAtElapsedMs <= 0L) {
            return if (monitorRunning) {
                "GPU 監視器：啟動中，等待第一筆讀值…"
            } else {
                "GPU 監視器：尚未啟動"
            }
        }

        val ageMs = (nowElapsedMs - capturedAtElapsedMs).coerceAtLeast(0L)
        val freshness = if (ageMs > STALE_AFTER_MS) "資料已過期" else "資料新鮮"
        val state = if (monitorRunning) "執行中" else "已停止（保留最後讀值）"
        val selectedLoad = gpuLoad?.takeIf { it.error == null }
        val loadModeVerified = selectedLoad?.let { reading ->
            reading.sourcePath != GpuSysfsPaths.CURRENT_UTILIZATION || isVerifiedSgpuJiffiesSource()
        } == true
        val loadValue = if (loadModeVerified) selectedLoad?.normalizedValue else null
        val loadText = if (loadValue != null) "$loadValue%" else "不可用"
        val loadLabel = if (selectedLoad?.sourcePath == GpuSysfsPaths.CURRENT_UTILIZATION) {
            "GPU 驅動利用率（SGPU）"
        } else {
            "GPU 負載"
        }
        val cuValue = computeUnitUtilization?.takeIf { it.error == null }?.normalizedValue
        val lines = mutableListOf(
            "GPU 監視器：$state",
            "取樣新鮮度：" + freshness + "（" + ageMs + " ms）",
            "$loadLabel：$loadText",
            "GPU CU 利用率（輔助指標，非整體 GPU 使用率）：" + (cuValue?.let { "$it%" } ?: "不可用"),
            "GPU 實際時脈 cur_freq：" + formatCurrentFrequency(currentFrequency),
            "GPU 最低頻率設定 min_freq：" + formatFrequency(minFrequency),
            "GPU 最高頻率上限 max_freq：" + formatFrequency(maxFrequency),
            "GPU 負載來源模式 utilization_source：" + (utilizationSource?.normalizedValue ?: "不可用"),
            "驅動支援的負載來源：" + (availableUtilizationSources?.normalizedValue ?: "不可用"),
            "Governor：" + (governor?.normalizedValue ?: "不可用"),
            "可用頻率步進：" + (availableFrequencies?.normalizedValue ?: "不可用")
        )

        gpuLoad?.let {
            lines += "負載來源：" + it.sourcePath
            lines += "負載原始值：" + (it.rawValue ?: "無")
            if (it.error != null) {
                lines += "負載狀態：" + it.error
            } else if (it.sourcePath == GpuSysfsPaths.CURRENT_UTILIZATION && !loadModeVerified) {
                lines += "負載狀態：需要 utilization_source=jiffies 且 available_utilization_sources 包含 jiffies；不交給自動調頻"
            }
        } ?: run {
            lines += "負載狀態：沒有找到可解析的 GPU 負載來源"
        }

        listOf(
            "cur_freq" to currentFrequency,
            "min_freq" to minFrequency,
            "max_freq" to maxFrequency,
            "governor" to governor,
            "available_frequencies" to availableFrequencies
        ).forEach { (name, metric) ->
            if (metric?.error != null) {
                lines += name + " 讀取狀態：" + metric.error
            }
        }

        if (loadSourceReadings.isNotEmpty()) {
            lines += "候選負載來源核對："
            loadSourceReadings.forEach { metric ->
                val source = metric.sourcePath.substringAfterLast('/')
                val value = metric.normalizedValue?.let { "$it%" } ?: "不可用"
                val detail = metric.error?.let { "（$it）" } ?: ""
                lines += "$source：$value，raw=${metric.rawValue ?: "無"}$detail"
            }
        }

        sampleError?.let { lines += "取樣器錯誤：" + it }
        lines += "注意：min_freq 是最低頻率設定，不代表 GPU 實際時脈；實際值以 cur_freq 為準。"
        return lines.joinToString("\n")
    }

    companion object {
        const val STALE_AFTER_MS = 3000L

        fun empty(): GpuTelemetrySnapshot = GpuTelemetrySnapshot(
            capturedAtElapsedMs = 0L,
            minFrequency = null,
            currentFrequency = null,
            maxFrequency = null,
            governor = null,
            availableFrequencies = null,
            gpuLoad = null,
            computeUnitUtilization = null
        )

        fun failed(nowElapsedMs: Long, error: String): GpuTelemetrySnapshot =
            empty().copy(capturedAtElapsedMs = nowElapsedMs, sampleError = error)

        private fun formatFrequency(metric: GpuTelemetryMetric?): String {
            val khz = metric?.takeIf { it.error == null }
                ?.normalizedValue
                ?.toLongOrNull()
                ?: return "不可用"
            return String.format(Locale.US, "%.1f MHz（%d kHz）", khz / 1000.0, khz)
        }

        private fun formatCurrentFrequency(metric: GpuTelemetryMetric?): String {
            val khz = metric?.takeIf { it.error == null }
                ?.normalizedValue
                ?.toLongOrNull()
                ?: return "不可用"
            if (khz == 0L) return "0 kHz（閒置或驅動未回報）"
            return String.format(Locale.US, "%.1f MHz（%d kHz）", khz / 1000.0, khz)
        }
    }
}

/**
 * One read-only collection pass. The same parsed GPU-load metric is consumed by the
 * controller and displayed by the UI, avoiding a second load-sampling loop.
 */
internal class GpuTelemetryCollector(
    private val loadCandidates: List<String> = GpuSysfsPaths.LOAD_CANDIDATES,
    private val readText: (String) -> String? = ::readSysfsText,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    fun sample(): GpuTelemetrySnapshot {
        val min = readMetric(GpuSysfsPaths.MIN_FREQ, "kHz") { parseNonNegativeLong(it) }
        val current = readMetric(GpuSysfsPaths.CURRENT_FREQ, "kHz") { parseNonNegativeLong(it) }
        val max = readMetric(GpuSysfsPaths.MAX_FREQ, "kHz") { parseNonNegativeLong(it) }
        val governor = readMetric(GpuSysfsPaths.GOVERNOR, null) { it.takeIf(String::isNotBlank) }
        val utilizationSource = readMetric(GpuSysfsPaths.UTILIZATION_SOURCE, null) {
            it.takeIf(String::isNotBlank)
        }
        val availableUtilizationSources = readMetric(
            GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES,
            null
        ) { it.takeIf(String::isNotBlank) }
        val computeUnitUtilization = readMetric(GpuSysfsPaths.CURRENT_CU_UTILIZATION, "%") {
            parseStrictPercent(it)
        }
        val available = readMetric(GpuSysfsPaths.AVAILABLE_FREQUENCIES, "kHz") {
            val values = it.split(Regex("\\s+"))
                .mapNotNull { token -> token.toLongOrNull()?.takeIf { value -> value > 0L } }
            if (values.isEmpty()) null
            else if (values.size != it.split(Regex("\\s+")).size) null
            else values.joinToString(" ")
        }
        val (load, loadSources) = readGpuLoads()

        return GpuTelemetrySnapshot(
            capturedAtElapsedMs = clockMs(),
            minFrequency = min,
            currentFrequency = current,
            maxFrequency = max,
            governor = governor,
            availableFrequencies = available,
            gpuLoad = load,
            loadSourceReadings = loadSources,
            utilizationSource = utilizationSource,
            availableUtilizationSources = availableUtilizationSources,
            computeUnitUtilization = computeUnitUtilization
        )
    }

    private fun readGpuLoads(): Pair<GpuTelemetryMetric, List<GpuTelemetryMetric>> {
        val readings = loadCandidates.map { path ->
            readMetric(path, "%") { raw ->
                if (path == GpuSysfsPaths.CURRENT_UTILIZATION) {
                    parseStrictPercent(raw)
                } else {
                    GpuAdaptivePolicy.parseGpuLoadPercent(raw)?.toString()
                }
            }
        }
        val valid = readings.filter { metric ->
            val value = metric.normalizedValue?.toIntOrNull()
            metric.error == null && value != null && value in 0..100
        }
        if (valid.isEmpty()) {
            val firstUnparsed = readings.firstOrNull { it.rawValue != null }
            val unavailable = firstUnparsed ?: GpuTelemetryMetric(
                sourcePath = loadCandidates.joinToString(", "),
                rawValue = null,
                normalizedValue = null,
                unit = "%",
                sampledAtElapsedMs = clockMs(),
                error = "所有候選節點均不存在或無法讀取"
            )
            return unavailable to readings
        }

        val values = valid.mapNotNull { it.normalizedValue?.toIntOrNull() }
        val spread = (values.maxOrNull() ?: 0) - (values.minOrNull() ?: 0)
        if (valid.size > 1 && spread > MAX_SOURCE_SPREAD_PERCENT) {
            val conflict = GpuTelemetryMetric(
                sourcePath = valid.joinToString(", ") { it.sourcePath },
                rawValue = valid.joinToString("; ") {
                    "${it.sourcePath}=${it.rawValue}"
                }.take(240),
                normalizedValue = null,
                unit = "%",
                sampledAtElapsedMs = clockMs(),
                error = "多個 GPU 負載來源差異達 $spread 個百分點；停用本次自適應升頻"
            )
            return conflict to readings
        }

        return valid.first() to readings
    }

    private fun readMetric(
        path: String,
        unit: String?,
        parser: (String) -> String?
    ): GpuTelemetryMetric {
        val sampledAt = clockMs()
        val raw = try {
            readText(path)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (exception: SecurityException) {
            return GpuTelemetryMetric(
                path, null, null, unit, sampledAt,
                "權限受限：" + (exception.message ?: "SecurityException")
            )
        } catch (exception: Exception) {
            return GpuTelemetryMetric(
                path, null, null, unit, sampledAt,
                "讀取失敗：" + exception.javaClass.simpleName
            )
        }

        if (raw == null) {
            return GpuTelemetryMetric(path, null, null, unit, sampledAt, "節點不存在或無法讀取")
        }

        val parsed = try {
            parser(raw)
        } catch (_: Exception) {
            null
        }
        return if (parsed == null) {
            GpuTelemetryMetric(path, raw, null, unit, sampledAt, "原始格式無法解析")
        } else {
            GpuTelemetryMetric(path, raw, parsed, unit, sampledAt)
        }
    }

    private fun parseNonNegativeLong(raw: String): String? =
        raw.toLongOrNull()?.takeIf { it >= 0L }?.toString()

    private fun parseStrictPercent(raw: String): String? =
        raw.toIntOrNull()?.takeIf { it in 0..100 }?.toString()

    companion object {
        private const val MAX_SOURCE_SPREAD_PERCENT = 15
    }
}

internal class GpuTelemetryMonitor(
    private val collector: GpuTelemetryCollector = GpuTelemetryCollector(),
    private val intervalMs: Long = 1000L
) {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "WuWa-GPU-Telemetry").apply { isDaemon = true }
        }
    private val latest = AtomicReference(GpuTelemetrySnapshot.empty())

    @Volatile
    private var running = false

    private var future: ScheduledFuture<*>? = null

    @Synchronized
    fun start() {
        if (future?.isDone == false && future?.isCancelled == false) return
        running = true
        future = executor.scheduleWithFixedDelay(
            { collectOnce() },
            0L,
            intervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    @Synchronized
    fun stop() {
        running = false
        future?.cancel(false)
        future = null
    }

    fun snapshot(): GpuTelemetrySnapshot = latest.get()

    fun displayText(): String =
        latest.get().toDisplayText(SystemClock.elapsedRealtime(), running)

    @Synchronized
    fun shutdown() {
        stop()
        executor.shutdownNow()
    }

    private fun collectOnce() {
        try {
            latest.set(collector.sample())
        } catch (exception: Exception) {
            latest.updateAndGet { current ->
                current.copy(sampleError = exception.javaClass.simpleName + ": " +
                    (exception.message ?: "未知錯誤"))
            }
        }
    }
}

private fun readSysfsText(path: String): String? {
    val file = File(path)
    if (!file.exists() || !file.isFile) return null
    return file.readText(StandardCharsets.US_ASCII).trim().takeIf { it.isNotEmpty() }
}
