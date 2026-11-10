package com.example.wuwaaccelerator

import android.os.SystemClock
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal internal GPU-load input for the adaptive controller.
 * No HUD, FPS, temperature, CPU or user-facing diagnostics are collected here.
 */
internal object GpuSysfsPaths {
    private const val NODE = "/sys/class/devfreq/10e00000.sgpu"

    const val CURRENT_UTILIZATION = "$NODE/interface/current_utilization"
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
    val gpuLoad: GpuTelemetryMetric?,
    val loadSourceReadings: List<GpuTelemetryMetric> = emptyList(),
    val utilizationSource: GpuTelemetryMetric? = null,
    val availableUtilizationSources: GpuTelemetryMetric? = null,
    val sampleError: String? = null
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

    companion object {
        const val STALE_AFTER_MS = 1500L

        fun empty(): GpuTelemetrySnapshot = GpuTelemetrySnapshot(
            capturedAtElapsedMs = 0L,
            gpuLoad = null
        )

        fun failed(nowElapsedMs: Long, error: String): GpuTelemetrySnapshot =
            empty().copy(capturedAtElapsedMs = nowElapsedMs, sampleError = error)
    }
}

/** Reads only the verified load source and fallback candidates required by the controller. */
internal class GpuTelemetryCollector(
    private val loadCandidates: List<String> = GpuSysfsPaths.LOAD_CANDIDATES,
    private val readText: (String) -> String? = ::readSysfsText,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    fun sample(): GpuTelemetrySnapshot {
        val utilizationSource = readMetric(GpuSysfsPaths.UTILIZATION_SOURCE, null) {
            it.takeIf(String::isNotBlank)
        }
        val availableUtilizationSources = readMetric(
            GpuSysfsPaths.AVAILABLE_UTILIZATION_SOURCES,
            null
        ) { it.takeIf(String::isNotBlank) }
        val (load, loadSources) = readGpuLoads(utilizationSource, availableUtilizationSources)

        return GpuTelemetrySnapshot(
            capturedAtElapsedMs = clockMs(),
            gpuLoad = load,
            loadSourceReadings = loadSources,
            utilizationSource = utilizationSource,
            availableUtilizationSources = availableUtilizationSources
        )
    }

    private fun readGpuLoads(
        utilizationSource: GpuTelemetryMetric?,
        availableUtilizationSources: GpuTelemetryMetric?
    ): Pair<GpuTelemetryMetric, List<GpuTelemetryMetric>> {
        val readings = loadCandidates.map { path ->
            readMetric(path, "%") { raw ->
                if (path == GpuSysfsPaths.CURRENT_UTILIZATION) {
                    raw.toIntOrNull()?.takeIf { it in 0..100 }?.toString()
                } else {
                    GpuAdaptivePolicy.parseGpuLoadPercent(raw)?.toString()
                }
            }
        }
        val advertisedSources = availableUtilizationSources
            ?.takeIf { it.error == null }
            ?.normalizedValue
            ?.split(Regex("\\s+"))
            ?.toSet()
            .orEmpty()
        val jiffiesVerified =
            utilizationSource?.error == null &&
                utilizationSource?.normalizedValue == "jiffies" &&
                "jiffies" in advertisedSources

        val diagnosticReadings = readings.map { metric ->
            if (metric.sourcePath == GpuSysfsPaths.CURRENT_UTILIZATION &&
                metric.error == null && !jiffiesVerified
            ) {
                metric.copy(error = "utilization_source 未驗證 jiffies；忽略此來源並檢查其他負載節點；不交給自動調頻")
            } else {
                metric
            }
        }
        val valid = diagnosticReadings.filter { metric ->
            val value = metric.normalizedValue?.toIntOrNull()
            metric.error == null && value != null && value in 0..100
        }
        if (valid.isEmpty()) {
            val firstUnparsed = diagnosticReadings.firstOrNull { it.rawValue != null }
            val unavailable = firstUnparsed ?: GpuTelemetryMetric(
                sourcePath = loadCandidates.joinToString(", "),
                rawValue = null,
                normalizedValue = null,
                unit = "%",
                sampledAtElapsedMs = clockMs(),
                error = "所有候選節點均不存在或無法讀取"
            )
            return unavailable to diagnosticReadings
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
            return conflict to diagnosticReadings
        }

        return valid.first() to diagnosticReadings
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

    companion object {
        private const val MAX_SOURCE_SPREAD_PERCENT = 15
    }
}

internal class GpuLoadSampler(
    private val collector: GpuTelemetryCollector = GpuTelemetryCollector(),
    private val intervalMs: Long = 500L
) {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "WuWa-GPU-Load-Sampler").apply { isDaemon = true }
        }
    private val latest = AtomicReference(GpuTelemetrySnapshot.empty())


    private var future: ScheduledFuture<*>? = null

    @Synchronized
    fun start() {
        if (future?.isDone == false && future?.isCancelled == false) return
        future = executor.scheduleWithFixedDelay(
            { collectOnce() },
            0L,
            intervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    @Synchronized
    fun stop() {
        future?.cancel(false)
        future = null
    }

    fun snapshot(): GpuTelemetrySnapshot = latest.get()

    @Synchronized
    fun shutdown() {
        stop()
        executor.shutdownNow()
    }

    private fun collectOnce() {
        try {
            latest.set(collector.sample())
        } catch (exception: Exception) {
            latest.set(
                GpuTelemetrySnapshot.failed(
                    SystemClock.elapsedRealtime(),
                    exception.javaClass.simpleName + ": " + (exception.message ?: "未知錯誤")
                )
            )
        }
    }
}

private fun readSysfsText(path: String): String? {
    val file = File(path)
    if (!file.exists() || !file.isFile) return null
    return file.readText(StandardCharsets.US_ASCII).trim().takeIf { it.isNotEmpty() }
}
