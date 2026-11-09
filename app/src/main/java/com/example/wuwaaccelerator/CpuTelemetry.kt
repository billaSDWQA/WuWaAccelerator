package com.example.wuwaaccelerator

import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class CpuCoreLoad(
    val core: Int,
    val utilizationPercent: Int
)

internal data class CpuFrequencyPolicySnapshot(
    val policyName: String,
    val relatedCpus: String,
    val currentKHz: Long?,
    val minimumKHz: Long?,
    val maximumKHz: Long?
)

internal data class CpuTelemetrySnapshot(
    val sampledAtMs: Long,
    val overallUtilizationPercent: Int?,
    val coreLoads: List<CpuCoreLoad>,
    val frequencyPolicies: List<CpuFrequencyPolicySnapshot>,
    val onlineCpus: String?,
    val error: String? = null
) {
    fun toDisplayText(): String {
        if (error != null) return "CPU 監測：$error"
        val load = overallUtilizationPercent?.let { "$it%" } ?: "取樣中"
        val cores = if (coreLoads.isEmpty()) {
            "不可用"
        } else {
            coreLoads.joinToString("  ") { "CPU${it.core}=${it.utilizationPercent}%" }
        }
        val policies = if (frequencyPolicies.isEmpty()) {
            "不可用"
        } else {
            frequencyPolicies.joinToString("\n") { policy ->
                val current = policy.currentKHz?.let { String.format(Locale.US, "%.0f MHz", it / 1000.0) } ?: "N/A"
                val min = policy.minimumKHz?.let { String.format(Locale.US, "%.0f", it / 1000.0) } ?: "N/A"
                val max = policy.maximumKHz?.let { String.format(Locale.US, "%.0f", it / 1000.0) } ?: "N/A"
                "${policy.policyName} [CPU ${policy.relatedCpus}]: ${current}（min/max ${min}/${max} MHz）"
            }
        }
        return listOf(
            "CPU 整體使用率：$load",
            "各核心使用率：$cores",
            "CPU 頻率政策：",
            policies,
            "線上核心：${onlineCpus ?: "N/A"}"
        ).joinToString("\n")
    }
}

/**
 * Read-only CPU telemetry. It deliberately does not write cpufreq, cpuset,
 * scheduler, uclamp or thermal nodes. Policy topology is discovered from the
 * device instead of assuming a fixed big.LITTLE core numbering.
 */
internal class CpuTelemetryCollector(
    private val readText: (String) -> String? = { path ->
        val file = File(path)
        if (file.isFile && file.canRead()) file.readText().trim() else null
    },
    private val listPolicyDirectories: () -> List<String> = {
        File("/sys/devices/system/cpu/cpufreq")
            .listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("policy\\d+")) }
            ?.map { it.absolutePath }
            .orEmpty()
    },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private var previousCounters: Map<Int, CpuStatCounters> = emptyMap()

    @Synchronized
    fun sample(): CpuTelemetrySnapshot {
        val now = clockMs()
        val statText = readText("/proc/stat")
            ?: return CpuTelemetrySnapshot(now, null, emptyList(), emptyList(), null, "無法讀取 /proc/stat")
        val currentCounters = CpuTelemetryParser.parseProcStat(statText)
        var totalDelta = 0L
        var busyDelta = 0L
        val coreLoads = currentCounters.mapNotNull { (core, counters) ->
            val previous = previousCounters[core] ?: return@mapNotNull null
            val elapsed = counters.total - previous.total
            val idle = counters.idle - previous.idle
            if (elapsed <= 0L || idle < 0L || idle > elapsed) {
                null
            } else {
                totalDelta += elapsed
                busyDelta += elapsed - idle
                val percent = (((elapsed - idle).toDouble() / elapsed) * 100.0)
                    .toInt().coerceIn(0, 100)
                CpuCoreLoad(core, percent)
            }
        }
        previousCounters = currentCounters

        // Weight by actual CPU time deltas; do not average cores equally.
        val overall = if (totalDelta > 0L) {
            ((busyDelta.toDouble() / totalDelta) * 100.0).toInt().coerceIn(0, 100)
        } else null

        val policies = listPolicyDirectories().sorted().mapNotNull { directory ->
            val name = directory.substringAfterLast('/')
            val related = readText("$directory/related_cpus")
                ?: readText("$directory/affected_cpus")
                ?: return@mapNotNull null
            CpuFrequencyPolicySnapshot(
                policyName = name,
                relatedCpus = related,
                currentKHz = readPositiveLong("$directory/scaling_cur_freq")
                    ?: readPositiveLong("$directory/cpuinfo_cur_freq"),
                minimumKHz = readPositiveLong("$directory/scaling_min_freq"),
                maximumKHz = readPositiveLong("$directory/scaling_max_freq")
            )
        }

        return CpuTelemetrySnapshot(
            sampledAtMs = now,
            overallUtilizationPercent = overall,
            coreLoads = coreLoads,
            frequencyPolicies = policies,
            onlineCpus = readText("/sys/devices/system/cpu/online")
        )
    }

    private fun readPositiveLong(path: String): Long? =
        readText(path)?.toLongOrNull()?.takeIf { it > 0L }
}

/** A single sampler owns /proc/stat deltas so UI and HUD cannot distort each other's readings. */
internal class CpuTelemetryMonitor(
    private val collector: CpuTelemetryCollector = CpuTelemetryCollector(),
    private val intervalMs: Long = 1000L
) {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "WuWa-CPU-Telemetry").apply { isDaemon = true } }
    private val latest = AtomicReference(
        CpuTelemetrySnapshot(0L, null, emptyList(), emptyList(), null, "尚未取樣")
    )
    @Volatile private var running = false
    private var future: ScheduledFuture<*>? = null

    @Synchronized
    fun start() {
        if (future?.isDone == false && future?.isCancelled == false) return
        running = true
        future = executor.scheduleWithFixedDelay(
            {
                runCatching { collector.sample() }.onSuccess { latest.set(it) }
                    .onFailure {
                        latest.set(CpuTelemetrySnapshot(
                            System.nanoTime() / 1_000_000L, null, emptyList(), emptyList(), null,
                            "取樣失敗：${it.javaClass.simpleName}"
                        ))
                    }
            },
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

    fun displayText(): String {
        val snapshot = latest.get()
        return if (running && snapshot.sampledAtMs > 0L &&
            System.nanoTime() / 1_000_000L - snapshot.sampledAtMs > intervalMs * 3
        ) {
            "CPU 監測：資料已過期"
        } else snapshot.toDisplayText()
    }

    @Synchronized
    fun shutdown() {
        stop()
        executor.shutdownNow()
    }
}

internal data class CpuStatCounters(
    val total: Long,
    val idle: Long
)

internal object CpuTelemetryParser {
    /** Parse per-core counters from /proc/stat; aggregate 'cpu' is intentionally ignored. */
    fun parseProcStat(text: String): Map<Int, CpuStatCounters> {
        val result = linkedMapOf<Int, CpuStatCounters>()
        text.lineSequence().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val label = parts.firstOrNull() ?: return@forEach
            if (!label.matches(Regex("cpu\\d+")) || parts.size < 5) return@forEach
            val core = label.removePrefix("cpu").toIntOrNull() ?: return@forEach
            val values = parts.drop(1).map { it.toLongOrNull() ?: return@forEach }
            if (values.size < 4 || values.take(8).any { it < 0L }) return@forEach
            // Linux /proc/stat guest and guest_nice are already included in user/nice.
            // Sum the first eight fields only to avoid double-counting them.
            val counters = values.take(8)
            val total = counters.sum()
            val idle = counters[3] + (counters.getOrNull(4) ?: 0L)
            if (total >= idle) result[core] = CpuStatCounters(total, idle)
        }
        return result
    }
}
