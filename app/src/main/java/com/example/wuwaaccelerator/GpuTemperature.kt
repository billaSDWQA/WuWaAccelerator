package com.example.wuwaaccelerator

import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class GpuTemperatureReading(
    val sensorType: String,
    val celsius: Double
)

internal object GpuTemperatureParser {
    private val gpuSensorName = Regex("(?i).*(gpu|sgpu|xclipse|g3d).*")

    fun parse(sensorType: String?, rawTemperature: String?): GpuTemperatureReading? {
        val type = sensorType?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!gpuSensorName.matches(type)) return null
        val raw = rawTemperature?.trim()?.toDoubleOrNull() ?: return null
        val celsius = if (kotlin.math.abs(raw) >= 1000.0) raw / 1000.0 else raw
        if (!celsius.isFinite() || celsius !in -20.0..150.0) return null
        return GpuTemperatureReading(type, celsius)
    }
}

/**
 * Reads only thermal zones explicitly named for GPU/SGPU/Xclipse/G3D.
 * CPU and generic SoC zones are never relabelled as GPU temperature.
 */
internal class GpuTemperatureMonitor(
    private val listZones: () -> List<File> = {
        File("/sys/class/thermal").listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("thermal_zone\\d+")) }
            .orEmpty()
    },
    private val readText: (File) -> String? = { file ->
        runCatching { if (file.isFile && file.canRead()) file.readText().trim() else null }.getOrNull()
    },
    private val intervalMs: Long = 5000L
) {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "WuWa-GPU-Temperature").apply { isDaemon = true } }
    private val latest = AtomicReference<GpuTemperatureReading?>(null)
    @Volatile private var running = false
    @Volatile private var sampled = false
    private var future: ScheduledFuture<*>? = null

    @Synchronized
    fun start() {
        if (future?.isDone == false && future?.isCancelled == false) return
        running = true
        future = executor.scheduleWithFixedDelay(
            { runCatching { sample() } }, 0L, intervalMs, TimeUnit.MILLISECONDS
        )
    }

    private fun sample() {
        val readings = listZones().mapNotNull { zone ->
            val type = readText(File(zone, "type"))
            val temp = readText(File(zone, "temp"))
            GpuTemperatureParser.parse(type, temp)
        }
        latest.set(readings.maxByOrNull { it.celsius })
        sampled = true
    }

    fun displayText(): String {
        if (!running && !sampled) return "GPU 溫度：N/A（監測尚未啟動）"
        val reading = latest.get() ?: return "GPU 溫度：N/A（韌體未公開 GPU 感測器或 Shizuku 無讀取權限）"
        return "GPU 溫度：" + String.format(Locale.US, "%.1f°C（%s）", reading.celsius, reading.sensorType)
    }

    @Synchronized
    fun stop() {
        running = false
        future?.cancel(false)
        future = null
    }

    @Synchronized
    fun shutdown() {
        stop()
        executor.shutdownNow()
    }
}