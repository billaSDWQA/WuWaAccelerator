package com.example.wuwaaccelerator

import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class FramePresentationSnapshot(
    val sampledAtMs: Long,
    val layerName: String?,
    val totalFrames: Long?,
    val droppedFrames: Long?,
    val averageFps: Double?,
    val onePercentLowFps: Double?,
    val status: String
) {
    fun toDisplayText(): String {
        if (layerName == null) return "遊戲幀率統計：$status"
        val average = averageFps?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "N/A"
        val low = onePercentLowFps?.let { String.format(java.util.Locale.US, "%.1f", it) }
            ?: "樣本不足（至少 100 幀）"
        val dropped = droppedFrames?.toString() ?: "N/A"
        return listOf(
            "平均 FPS：$average",
            "1% Low FPS：$low",
            "SurfaceFlinger droppedFrames：$dropped",
            "來源：SurfaceFlinger 呈現間隔估算，非遊戲引擎內部 GPU render time",
            "狀態：$status"
        ).joinToString("\n")
    }

    companion object {
        fun unavailable(status: String, nowMs: Long = System.nanoTime() / 1_000_000L) =
            FramePresentationSnapshot(nowMs, null, null, null, null, null, status)
    }
}
/**
 * Best-effort SurfaceFlinger timestats sampler. It is enabled only while the game
 * is detected in the foreground, and is always disabled on confirmed exit/stop.
 * The reported intervals describe compositor presentation cadence, not the
 * game's internal render/GPU execution time.
 */
internal class SurfaceFlingerFrameMonitor(
    private val command: (List<String>, Long) -> String? = ::runDumpsys,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val intervalMs: Long = 10_000L
) {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "WuWa-Frame-Presentation").apply { isDaemon = true } }
    private val latest = AtomicReference(FramePresentationSnapshot.unavailable("尚未啟動"))
    @Volatile private var enabled = false
    @Volatile private var requested = false
    private var future: ScheduledFuture<*>? = null

    @Synchronized
    fun start() {
        if (requested) return
        requested = true
        future = executor.scheduleWithFixedDelay({
            if (!requested) return@scheduleWithFixedDelay
            if (!enabled) {
                val result = command(listOf("SurfaceFlinger", "--timestats", "-enable"), 2500L)
                enabled = result != null
                if (!requested && enabled) {
                    command(listOf("SurfaceFlinger", "--timestats", "-disable"), 2500L)
                    enabled = false
                    return@scheduleWithFixedDelay
                }
                if (!enabled) {
                    latest.set(FramePresentationSnapshot.unavailable(
                        "此韌體不允許或不支援 SurfaceFlinger timestats"
                    ))
                    return@scheduleWithFixedDelay
                }
                latest.set(FramePresentationSnapshot.unavailable("已啟用，等待第一筆統計"))
            }
            val dump = command(listOf("SurfaceFlinger", "--timestats", "-dump"), 2500L)
            val parsed = dump?.let { SurfaceFlingerTimestatsParser.parse(it, clockMs()) }
            latest.set(parsed ?: FramePresentationSnapshot.unavailable(
                "無法讀取 timestats；保留上一筆有效資料"
            ))
        }, 0L, intervalMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stop() {
        requested = false
        future?.cancel(false)
        future = null
        if (enabled) {
            // Run disable on the same executor after any active dump has returned.
            enabled = false
            executor.execute {
                command(listOf("SurfaceFlinger", "--timestats", "-disable"), 2500L)
                latest.set(FramePresentationSnapshot.unavailable("已停止並關閉 SurfaceFlinger timestats"))
            }
        } else {
            latest.set(FramePresentationSnapshot.unavailable("未啟用"))
        }
    }

    fun displayText(): String = latest.get().toDisplayText()

    @Synchronized
    fun shutdown() {
        stop()
        executor.shutdown()
    }

    companion object {
        private fun runDumpsys(arguments: List<String>, timeoutMs: Long): String? {
            return try {
                val process = ProcessBuilder(
                    listOf("/system/bin/dumpsys") + arguments
                ).redirectErrorStream(true).start()
                val output = ByteArrayOutputStream()
                val reader = Thread {
                    runCatching { process.inputStream.use { it.copyTo(output) } }
                }.apply { isDaemon = true; start() }
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    runCatching { process.waitFor(200, TimeUnit.MILLISECONDS) }
                    reader.join(300L)
                    return null
                }
                reader.join(500L)
                if (reader.isAlive || process.exitValue() != 0) null
                else String(output.toByteArray(), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }
}

internal object SurfaceFlingerTimestatsParser {
    private val layerPattern = Regex(
        "\"layerName\"\\s*:\\s*\"([^\"]*com\\.kurogame\\.wutheringwaves\\.global[^\"]*)\""
    )
    private val totalPattern = Regex("\"totalFrames\"\\s*:\\s*(\\d+)")
    private val droppedPattern = Regex("\"droppedFrames\"\\s*:\\s*(\\d+)")
    private val histogramPattern = Regex("\"(\\d+)\"\\s*:\\s*(\\d+)")

    fun parse(output: String, nowMs: Long): FramePresentationSnapshot {
        val matches = layerPattern.findAll(output).toList()
        if (matches.isEmpty()) {
            return FramePresentationSnapshot.unavailable(
                "找不到鳴潮 SurfaceFlinger Layer；此裝置可能未公開遊戲層統計",
                nowMs
            )
        }
        val parsed = matches.mapNotNull { match ->
            val nextLayer = layerPattern.find(output, match.range.last + 1)?.range?.first ?: output.length
            val block = output.substring(match.range.last + 1, nextLayer)
            val total = totalPattern.find(block)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val dropped = droppedPattern.find(block)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val histogramStart = block.indexOf("\"present2present\"")
            val histogram = if (histogramStart >= 0) {
                val open = block.indexOf('{', histogramStart)
                val close = if (open >= 0) block.indexOf('}', open + 1) else -1
                if (open >= 0 && close > open) block.substring(open + 1, close) else ""
            } else ""
            val bins = histogramPattern.findAll(histogram).mapNotNull { bin ->
                val nanos = bin.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val count = bin.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                if (nanos <= 0L || count <= 0L) null else nanos to count
            }.toList()
            if (total == null && dropped == null && bins.isEmpty()) null
            else Triple(match.groupValues[1], total, dropped) to bins
        }
        if (parsed.isEmpty()) {
            return FramePresentationSnapshot.unavailable("找到鳴潮 Layer，但統計格式無法解析", nowMs)
        }
        val selected = parsed.maxByOrNull { it.first.second ?: 0L }
            ?: return FramePresentationSnapshot.unavailable("沒有可用影格統計", nowMs)
        val bins = selected.second
        val sampleCount = bins.sumOf { it.second }
        val averageIntervalNs = if (sampleCount > 0L) {
            bins.sumOf { it.first.toDouble() * it.second.toDouble() } / sampleCount.toDouble()
        } else null
        val averageFps = averageIntervalNs
            ?.takeIf { it > 0.0 }
            ?.let { 1_000_000_000.0 / it }
        val onePercentLowFps = if (sampleCount >= 100L) {
            val tailCount = kotlin.math.ceil(sampleCount * 0.01).toLong().coerceAtLeast(1L)
            var remaining = tailCount
            var fpsSum = 0.0
            for ((intervalNs, count) in bins.sortedByDescending { it.first }) {
                if (remaining <= 0L) break
                val take = minOf(count, remaining)
                fpsSum += (1_000_000_000.0 / intervalNs.toDouble()) * take.toDouble()
                remaining -= take
            }
            if (remaining == 0L) fpsSum / tailCount.toDouble() else null
        } else null
        return FramePresentationSnapshot(
            sampledAtMs = nowMs,
            layerName = selected.first.first,
            totalFrames = selected.first.second,
            droppedFrames = selected.first.third,
            averageFps = averageFps,
            onePercentLowFps = onePercentLowFps,
            status = "取樣成功；直方圖樣本數=$sampleCount"
        )
    }

}