package com.example.wuwaaccelerator

import android.os.Build
import android.os.Process
import android.system.Os
import androidx.annotation.Keep
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Keep
class GpuUserService : IAcceleratorService.Stub() {

    companion object {
        private const val GAME = "com.kurogame.wutheringwaves.global"
        private const val NODE = "/sys/class/devfreq/10e00000.sgpu"
        private const val MIN_NODE = "$NODE/interface/min_freq"
        private const val MAX_NODE = "$NODE/max_freq"
        private const val CUR_NODE = "$NODE/cur_freq"
        private const val GOV_NODE = "$NODE/governor"
        private const val AVAILABLE_NODE = "$NODE/available_frequencies"
        private const val POLL_MS = 2000L
        private const val THERMAL_SAMPLE_INTERVAL_MS = 5000L
        private const val THERMAL_TIMEOUT_MS = 2500L
        private const val DUMPSYS_TIMEOUT_MS = 3000L
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0
        private const val UNKNOWN_LIMIT = 3
        private const val EXIT_LIMIT = 2
    }

    private val lock = Any()
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "WuWa-GPU-Monitor").apply { isDaemon = true } }
    private val directory = File("/data/local/tmp/WuWaAccelerator")
    private val backup = File(directory, "gpu-state-v2")
    private val legacyBackup = File(directory, "gpu-state-v1")
    private val foregroundDump = File(directory, "foreground.txt")
    private val thermalDump = File(directory, "thermal.txt")
    private val minFile = File(MIN_NODE)
    private val gpuLoadNodes = listOf(
        "$NODE/load",
        "$NODE/gpu_load",
        "$NODE/gpu_busy_percent",
        "/sys/class/drm/card0/device/gpu_busy_percent"
    )

    @Volatile private var status = "尚未啟動"
    private var monitoring = false
    private var boostActive = false
    private var unknownSamples = 0
    private var exitSamples = 0
    private var thermalStatus: Int? = null
    private var lastThermalSampleAtMs = 0L
    private val adaptiveHysteresis = GpuAdaptiveHysteresis()
    private var future: ScheduledFuture<*>? = null

    private data class BackupState(
        val fingerprint: String,
        val original: String,
        val lastApplied: String,
        val previousApplied: String
    )
    private data class ForegroundResult(val packageName: String?, val error: String? = null)

    private val telemetryScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor {
            Thread(it, "WuWa-GPU-Telemetry").apply { isDaemon = true }
        }

    @Volatile
    private var telemetrySnapshot = GpuTelemetrySnapshot.unavailable(
        System.currentTimeMillis(),
        "尚未完成第一次取樣"
    )

    init {
        telemetryScheduler.scheduleWithFixedDelay({
            telemetrySnapshot = runCatching { sampleGpuTelemetry() }
                .getOrElse {
                    GpuTelemetrySnapshot.unavailable(
                        System.currentTimeMillis(),
                        "遙測取樣例外：${it.javaClass.simpleName}"
                    )
                }
        }, 0L, 1L, TimeUnit.SECONDS)
    }

    override fun startMonitoring(): Boolean = synchronized(lock) {
        if (monitoring) {
            status = "監控已啟動；${gpuSummary()}"
            return@synchronized true
        }
        val uid = Process.myUid()
        if (uid != SHELL_UID && uid != ROOT_UID) {
            status = "UserService 執行身分錯誤：UID=$uid（預期 2000 或 0），拒絕寫入"
            return@synchronized false
        }
        if (hasBackup() && !restoreLocked()) {
            status = "舊備份無法安全還原，拒絕開始：$status"
            return@synchronized false
        }
        if (!SupportedDevicePolicy.isSupported(Build.MANUFACTURER, Build.MODEL)) {
            status = "不支援裝置：${Build.MANUFACTURER} ${Build.MODEL}"
            return@synchronized false
        }
        val minimum = readMin()
        if (minimum == null || !GpuFrequencyPolicy.isValid(minimum)) {
            status = "無法讀取或驗證 $MIN_NODE；未修改 GPU"
            return@synchronized false
        }

        monitoring = true
        boostActive = false
        unknownSamples = 0
        exitSamples = 0
        thermalStatus = null
        lastThermalSampleAtMs = 0L
        adaptiveHysteresis.reset()
        scheduleLocked(0L)
        status = "監控啟動（Shizuku UID=$uid）；${gpuSummary()}；等待鳴潮"
        true
    }

    override fun stopAndRestore(): Boolean = synchronized(lock) {
        monitoring = false
        val restored = restoreLocked()
        if (restored) {
            future?.cancel(false)
            future = null
            boostActive = false
            adaptiveHysteresis.reset()
            status = "已停止；GPU 原始值已確認還原。${gpuSummary()}"
        } else {
            status = "還原失敗，備份保留並持續重試：$status"
            scheduleLocked(2000L)
        }
        restored
    }

    override fun getStatus(): String = status

    override fun getGpuTelemetry(): String = telemetrySnapshot.toJson()

    override fun destroy() {
        synchronized(lock) {
            monitoring = false
            future?.cancel(false)
            future = null
            restoreLocked()
            scheduler.shutdownNow()
            telemetryScheduler.shutdownNow()
        }
        System.exit(0)
    }

    private fun scheduleLocked(delay: Long) {
        future?.cancel(false)
        future = scheduler.scheduleWithFixedDelay({
            synchronized(lock) {
                if (monitoring) pollLocked()
                else if (hasBackup() && restoreLocked()) {
                    status = "GPU 原始值已確認還原。${gpuSummary()}"
                    future?.cancel(false)
                    future = null
                }
            }
        }, delay, POLL_MS, TimeUnit.MILLISECONDS)
    }

    private fun pollLocked() {
        val detection = readForeground()
        val foreground = detection.packageName
        if (foreground == null) {
            unknownSamples++
            if (hasBackup() && unknownSamples >= UNKNOWN_LIMIT) {
                if (restoreLocked()) {
                    boostActive = false
                    adaptiveHysteresis.reset()
                    exitSamples = 0
                    status = "前景連續辨識失敗，已安全還原：${detection.error}"
                } else {
                    status = "前景辨識失敗，還原亦失敗；備份保留：$status"
                }
            } else {
                status = "前景辨識失敗（$unknownSamples/$UNKNOWN_LIMIT）：${detection.error}"
            }
            return
        }
        unknownSamples = 0

        if (foreground == GAME) {
            exitSamples = 0
            val now = System.currentTimeMillis()
            if (now - lastThermalSampleAtMs >= THERMAL_SAMPLE_INTERVAL_MS) {
                thermalStatus = readThermalStatus()
                lastThermalSampleAtMs = now
            }

            val gpuLoad = readGpuLoadPercent()
            val memoryPressure = readMemoryPressure()
            val proposedTarget = GpuAdaptivePolicy.chooseTarget(
                thermalStatus,
                gpuLoad,
                memoryPressure
            )
            val requestedTarget = adaptiveHysteresis.update(
                proposedTarget,
                forceImmediate = thermalStatus == 1 || memoryPressure != MemoryPressure.NORMAL
            )

            if (requestedTarget == null) {
                if (proposedTarget != null) {
                    status = "正在確認 GPU 負載，暫不提高最低頻率；thermal=${GpuAdaptivePolicy.thermalLabel(thermalStatus)}"
                    return
                }
                if (hasBackup() && !restoreLocked()) {
                    status = "熱狀態／記憶體壓力要求停止加速，但還原失敗；備份保留：$status"
                } else {
                    boostActive = false
                    status = "保護模式：thermal=${GpuAdaptivePolicy.thermalLabel(thermalStatus)}，" +
                        "memory=${GpuAdaptivePolicy.memoryLabel(memoryPressure)}；GPU 已回到原始設定"
                }
                return
            }

            applyTargetLocked(requestedTarget, gpuLoad, memoryPressure)
            return
        }

        if (hasBackup()) {
            exitSamples++
            if (exitSamples >= EXIT_LIMIT && restoreLocked()) {
                boostActive = false
                adaptiveHysteresis.reset()
                exitSamples = 0
                status = "鳴潮已離開前景；GPU 原始值已確認還原。${gpuSummary()}"
            }
        } else {
            exitSamples = 0
            adaptiveHysteresis.reset()
            status = "自動偵測中；目前前景：$foreground"
        }
    }

    private fun applyTargetLocked(
        requestedTarget: String,
        gpuLoad: Int?,
        memoryPressure: MemoryPressure
    ) {
        val maximum = readNode(MAX_NODE)
        val availableText = readNode(AVAILABLE_NODE)
        val available = availableText?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.toSet()
        val target = GpuAdaptivePolicy.supportedTarget(requestedTarget, maximum, available)
        if (target == null) {
            if (hasBackup() && !restoreLocked()) {
                status = "沒有可安全使用的 GPU 頻率步進，且原始值還原失敗；備份保留：$status"
            } else {
                status = "沒有可安全使用的 GPU 頻率步進；已回到原始設定。max_freq=$maximum"
            }
            return
        }

        if (legacyBackup.exists() && !backup.exists()) {
            status = "偵測到 0.7.0 舊備份；缺少最後套用值，為避免覆蓋外部修改，拒絕繼續加速"
            return
        }

        if (backup.exists()) {
            val state = readBackup()
            if (state == null || state.fingerprint != Build.FINGERPRINT) {
                status = "GPU 備份無效或韌體不符；拒絕覆寫，請先安全處理備份"
                return
            }
            val originalValue = state.original.toLongOrNull()
            val targetValue = target.toLongOrNull()
            if (originalValue == null || targetValue == null) {
                status = "GPU 備份或目標頻率格式錯誤；拒絕修改"
                return
            }
            if (targetValue <= originalValue) {
                if (restoreLocked()) {
                    status = "原始 min_freq=${state.original} 已不低於目標 $target；維持原始設定"
                }
                return
            }

            val current = readMin()
            if (current == null) {
                status = "無法讀取目前 min_freq；備份保留，不盲目寫入"
                return
            }
            val ownership = GpuRestoreRecord(
                original = state.original,
                lastApplied = state.lastApplied,
                previousApplied = state.previousApplied
            )
            if (!GpuRestorePolicy.mayRestore(current, ownership)) {
                status = "min_freq 已被外部修改（目前=$current）；拒絕覆寫，也不自動還原"
                return
            }

            if (current != target) {
                // Persist both the new requested value and the last known app-owned value
                // before writing. A restart can then safely recognize either side of this
                // single pending transition without clobbering an unrelated setting.
                val pending = state.copy(lastApplied = target, previousApplied = current)
                if (!writeBackup(pending)) {
                    status = "無法安全更新 GPU 還原紀錄；未寫入新頻率"
                    return
                }
                val error = writeAndVerify(target)
                if (error != null) {
                    status = "GPU 頻率調整至 $target 失敗：$error；嘗試安全還原"
                    if (restoreLocked()) status = "調整失敗，原始值已確認還原"
                    return
                }
            }

            boostActive = true
            status = "自適應 GPU 最低頻率請求=$target kHz；GPU load=${gpuLoad?.let { "$it%" } ?: "未知"}；" +
                "thermal=${GpuAdaptivePolicy.thermalLabel(thermalStatus)}；" +
                "memory=${GpuAdaptivePolicy.memoryLabel(memoryPressure)}；${gpuSummary()}"
            return
        }

        val original = readMin()
        if (original == null || !GpuFrequencyPolicy.isValid(original)) {
            status = "無法安全讀取 SGPU min_freq；拒絕修改"
            return
        }
        val originalValue = original.toLongOrNull() ?: return
        val targetValue = target.toLongOrNull() ?: return
        if (originalValue >= targetValue) {
            boostActive = false
            status = "原始 min_freq=$original 已達或高於目標 $target；不覆寫原始設定"
            return
        }

        val initialState = BackupState(
            fingerprint = Build.FINGERPRINT,
            original = original,
            lastApplied = target,
            previousApplied = original
        )
        if (!writeBackup(initialState)) {
            status = "GPU 原始值／最後套用值備份建立或校驗失敗；拒絕修改"
            return
        }

        val error = writeAndVerify(target)
        if (error != null) {
            status = "min_freq 寫入／讀回失敗：$error；正在安全還原"
            if (restoreLocked()) status = "未套用加速：$error；原始值 $original 已確認還原"
            else status = "套用與還原都未完成：$error；備份保留。$status"
            return
        }

        boostActive = true
        status = "自適應 GPU 最低頻率請求=$target kHz；GPU load=${gpuLoad?.let { "$it%" } ?: "未知"}；" +
            "thermal=${GpuAdaptivePolicy.thermalLabel(thermalStatus)}；" +
            "memory=${GpuAdaptivePolicy.memoryLabel(memoryPressure)}；${gpuSummary()}"
    }

    private fun readThermalStatus(): Int? {
        return try {
            if (!directory.exists() && !directory.mkdirs()) return null
            Os.chmod(directory.absolutePath, 448)
            val process = ProcessBuilder("/system/bin/dumpsys", "thermalservice")
                .redirectErrorStream(true)
                .redirectOutput(thermalDump)
                .start()
            if (!process.waitFor(THERMAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                runCatching { process.waitFor(250, TimeUnit.MILLISECONDS) }
                return null
            }
            if (process.exitValue() != 0 || !thermalDump.isFile) return null
            Os.chmod(thermalDump.absolutePath, 384)
            GpuAdaptivePolicy.parseThermalStatus(
                thermalDump.readText(StandardCharsets.UTF_8)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readGpuLoadPercent(): Int? {
        val snapshot = telemetrySnapshot
        val ageMs = System.currentTimeMillis() - snapshot.sampledAtMs
        if (ageMs < 0L || ageMs > 3000L) return null
        return snapshot.loadPercent.value
    }

    /**
     * Read-only sampling runs on WuWa-GPU-Telemetry, never on the control scheduler.
     * This collector performs only small sysfs reads; it never runs dumpsys or takes
     * the controller lock.
     */
    private fun sampleGpuTelemetry(): GpuTelemetrySnapshot {
        val sampledAt = System.currentTimeMillis()
        return GpuTelemetrySnapshot(
            sampledAtMs = sampledAt,
            loadPercent = GpuTelemetryParser.firstReadableLoad(gpuLoadNodes, ::readNode),
            currentFrequencyKHz = GpuTelemetryParser.parseFrequencyKHz(
                CUR_NODE, readNode(CUR_NODE), allowZero = true
            ),
            minimumFrequencyKHz = GpuTelemetryParser.parseFrequencyKHz(
                MIN_NODE, readNode(MIN_NODE)
            ),
            maximumFrequencyKHz = GpuTelemetryParser.parseFrequencyKHz(
                MAX_NODE, readNode(MAX_NODE)
            ),
            governor = GpuTelemetryParser.parseGovernor(
                GOV_NODE, readNode(GOV_NODE)
            ),
            availableFrequenciesKHz = GpuTelemetryParser.parseFrequencyTable(
                AVAILABLE_NODE, readNode(AVAILABLE_NODE)
            )
        )
    }

    private fun hasBackup(): Boolean = backup.exists() || legacyBackup.exists()

    private fun readMemoryPressure(): MemoryPressure {
        return try {
            val lines = File("/proc/meminfo").readLines(StandardCharsets.US_ASCII)
            fun value(name: String): Long? {
                val line = lines.firstOrNull { it.startsWith("$name:") } ?: return null
                return Regex("\\d+").find(line)?.value?.toLongOrNull()
            }
            GpuAdaptivePolicy.classifyMemoryPressure(
                value("MemTotal"),
                value("MemAvailable")
            )
        } catch (_: Exception) {
            MemoryPressure.UNKNOWN
        }
    }

    // grep consumes the complete dumpsys stream; unlike the old awk command,
    // it does not exit after the first topResumedActivity record.
    private fun readForeground(): ForegroundResult {
        return try {
            if (!directory.exists() && !directory.mkdirs()) {
                return ForegroundResult(null, "無法建立診斷目錄")
            }
            Os.chmod(directory.absolutePath, 448)
            val command = "/system/bin/dumpsys activity activities | " +
                "/system/bin/grep -E 'topResumedActivity|mResumedActivity'"
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .redirectOutput(foregroundDump)
                .start()
            if (!process.waitFor(DUMPSYS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                runCatching { process.waitFor(250, TimeUnit.MILLISECONDS) }
                return ForegroundResult(null, "dumpsys 逾時（3 秒）")
            }
            if (process.exitValue() != 0 || !foregroundDump.isFile) {
                return ForegroundResult(null, "dumpsys 失敗，exit=${process.exitValue()}")
            }
            Os.chmod(foregroundDump.absolutePath, 384)
            val candidates = foregroundDump.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.filter {
                    it.contains("topResumedActivity") || it.contains("mResumedActivity")
                }.toList()
            }
            val parsed = ForegroundPackageParser.parse(candidates)
            if (parsed != null) ForegroundResult(parsed)
            else ForegroundResult(
                null,
                candidates.firstOrNull()?.take(160)
                    ?.let { "活動記錄無法解析：$it" }
                    ?: "找不到 topResumedActivity／mResumedActivity"
            )
        } catch (e: Exception) {
            ForegroundResult(null, "${e.javaClass.simpleName}: ${e.message ?: "未知錯誤"}")
        }
    }

    private fun readMin(): String? = readNode(MIN_NODE)

    private fun readNode(path: String): String? = try {
        val file = File(path)
        if (!file.exists() || !file.isFile) null
        else file.readText(StandardCharsets.US_ASCII).trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun writeAndVerify(value: String): String? {
        if (!GpuFrequencyPolicy.isValid(value)) return "頻率不在白名單：$value"
        return try {
            FileOutputStream(minFile).use {
                it.write((value + "\n").toByteArray(StandardCharsets.US_ASCII))
                it.flush()
            }
            val actual = readMin() ?: return "寫入後無法讀回 min_freq"
            if (actual == value) null else "讀回 $actual，預期 $value"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message ?: "寫入遭拒"}"
        }
    }

    private fun writeBackup(state: BackupState): Boolean {
        var tempFile: File? = null
        return try {
            if (!GpuFrequencyPolicy.isValid(state.original) ||
                !GpuFrequencyPolicy.isValid(state.lastApplied) ||
                !GpuFrequencyPolicy.isValid(state.previousApplied) ||
                legacyBackup.exists()
            ) return false

            if (!directory.exists() && !directory.mkdirs()) return false
            Os.chmod(directory.absolutePath, 448)
            val payload = listOf(
                "2",
                state.fingerprint,
                state.original,
                state.lastApplied,
                state.previousApplied
            ).joinToString("\\n", postfix = "\\n")
            val complete = payload + sha256(payload) + "\\n"
            val temp = File(directory, "gpu-state-v2.tmp")
            tempFile = temp
            if (temp.exists() && !temp.delete()) return false

            FileOutputStream(temp).use {
                it.write(complete.toByteArray(StandardCharsets.UTF_8))
                it.flush()
                it.fd.sync()
            }
            Os.chmod(temp.absolutePath, 384)
            if (readBackupFrom(temp) != state) {
                temp.delete()
                return false
            }

            Files.move(
                temp.toPath(),
                backup.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            Os.chmod(backup.absolutePath, 384)
            readBackup() == state
        } catch (_: Exception) {
            tempFile?.delete()
            false
        }
    }

    private fun readBackup(): BackupState? = readBackupFrom(backup)

    private fun readBackupFrom(file: File): BackupState? {
        return try {
            if (!file.isFile) return null
            val lines = file.readLines(StandardCharsets.UTF_8)
            if (lines.size != 6 || lines[0] != "2") return null
            val payload = lines.take(5).joinToString("\\n", postfix = "\\n")
            if (sha256(payload) != lines[5] ||
                !GpuFrequencyPolicy.isValid(lines[2]) ||
                !GpuFrequencyPolicy.isValid(lines[3]) ||
                !GpuFrequencyPolicy.isValid(lines[4])
            ) {
                null
            } else {
                BackupState(lines[1], lines[2], lines[3], lines[4])
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun restoreLocked(): Boolean {
        if (!hasBackup()) {
            boostActive = false
            return true
        }

        // 0.7.0 backups did not record the last value written by the app. If the
        // current value is already original, clear that stale backup. Otherwise,
        // ownership cannot be proven, so preserve it and refuse to overwrite.
        if (legacyBackup.exists() && !backup.exists()) {
            val legacy = readLegacyBackup()
            if (legacy == null) {
                status = "0.7.0 舊備份格式或 SHA-256 校驗失敗；拒絕猜測還原值"
                return false
            }
            if (legacy.first != Build.FINGERPRINT) {
                status = "0.7.0 舊備份韌體指紋不符；拒絕寫入舊設定"
                return false
            }
            val current = readMin()
            if (current == null) {
                status = "無法讀取目前 min_freq；舊備份保留，不盲目寫入"
                return false
            }
            if (current != legacy.second) {
                status = "0.7.0 舊備份沒有最後套用值；目前 min_freq=$current，無法證明仍由本程式控制，拒絕覆寫並保留備份"
                return false
            }
            if (!legacyBackup.delete()) {
                status = "目前值已等於 0.7.0 原始值，但舊備份無法刪除；保留備份"
                return false
            }
            boostActive = false
            return true
        }

        val state = readBackup()
        if (state == null) {
            status = "備份格式或 SHA-256 校驗失敗；拒絕猜測還原值"
            return false
        }
        if (state.fingerprint != Build.FINGERPRINT) {
            status = "韌體指紋與備份不符；拒絕寫入舊設定"
            return false
        }

        val current = readMin()
        if (current == null) {
            status = "無法讀取目前 min_freq；備份保留，不盲目寫入"
            return false
        }
        val record = GpuRestoreRecord(
            original = state.original,
            lastApplied = state.lastApplied,
            previousApplied = state.previousApplied
        )
        if (!GpuRestorePolicy.mayRestore(current, record)) {
            status = "min_freq 已被外部修改（目前=$current）；拒絕覆寫，還原備份保留"
            return false
        }

        if (GpuRestorePolicy.needsWrite(current, record)) {
            val error = writeAndVerify(state.original)
            if (error != null) {
                status = "還原 min_freq=${state.original} 失敗：$error；備份保留"
                return false
            }
        }
        if (!backup.delete()) {
            status = "原始 min_freq 已確認，但備份無法刪除；保留並再次驗證"
            return false
        }
        boostActive = false
        return true
    }

    private fun readLegacyBackup(): Pair<String, String>? {
        return try {
            if (!legacyBackup.isFile) return null
            val lines = legacyBackup.readLines(StandardCharsets.UTF_8)
            if (lines.size != 4 || lines[0] != "1") return null
            val payload = "1\\n${lines[1]}\\n${lines[2]}\\n"
            if (sha256(payload) != lines[3] || !GpuFrequencyPolicy.isValid(lines[2])) {
                null
            } else {
                lines[1] to lines[2]
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun gpuSummary(): String {
        val values = listOfNotNull(
            readNode(MIN_NODE)?.let { "min_freq=$it" },
            readNode(CUR_NODE)?.let { "cur_freq=$it" },
            readNode(MAX_NODE)?.let { "max_freq=$it" },
            readNode(GOV_NODE)?.let { "governor=$it" }
        )
        return if (values.isEmpty()) "SGPU 節點讀取不到" else "SGPU ${values.joinToString(", ")}"
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
