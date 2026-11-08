package com.example.wuwaaccelerator

import android.os.Build
import android.os.Process
import android.system.Os
import androidx.annotation.Keep
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
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
        private const val TARGET = GpuFrequencyPolicy.BOOST_MIN_FREQ
        private const val POLL_MS = 1500L
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
    private val backup = File(directory, "gpu-state-v1")
    private val foregroundDump = File(directory, "foreground.txt")
    private val minFile = File(MIN_NODE)

    @Volatile private var status = "尚未啟動"
    private var monitoring = false
    private var boostAttempted = false
    private var boostActive = false
    private var unknownSamples = 0
    private var exitSamples = 0
    private var future: ScheduledFuture<*>? = null

    private data class BackupState(val fingerprint: String, val original: String)
    private data class ForegroundResult(val packageName: String?, val error: String? = null)

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
        if (backup.exists() && !restoreLocked()) {
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
        boostAttempted = false
        boostActive = false
        unknownSamples = 0
        exitSamples = 0
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
            boostAttempted = false
            boostActive = false
            status = "已停止；GPU 原始值已確認還原。${gpuSummary()}"
        } else {
            status = "還原失敗，備份保留並持續重試：$status"
            scheduleLocked(2000L)
        }
        restored
    }

    override fun getStatus(): String = status

    override fun destroy() {
        synchronized(lock) {
            monitoring = false
            future?.cancel(false)
            future = null
            restoreLocked()
            scheduler.shutdownNow()
        }
        System.exit(0)
    }

    private fun scheduleLocked(delay: Long) {
        future?.cancel(false)
        future = scheduler.scheduleWithFixedDelay({
            synchronized(lock) {
                if (monitoring) pollLocked()
                else if (backup.exists() && restoreLocked()) {
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
            if (backup.exists() && unknownSamples >= UNKNOWN_LIMIT) {
                if (restoreLocked()) {
                    boostAttempted = false
                    boostActive = false
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
            if (backup.exists()) {
                if (boostActive) {
                    status = "鳴潮前景；min_freq 已寫入並讀回驗證。${gpuSummary()}"
                } else if (restoreLocked()) {
                    boostAttempted = true
                    status = "發現未完成的舊操作，已先還原；本次不重複套用"
                }
            } else if (!boostAttempted) {
                applyBoostLocked()
            }
            return
        }

        if (backup.exists()) {
            exitSamples++
            if (exitSamples >= EXIT_LIMIT && restoreLocked()) {
                boostAttempted = false
                boostActive = false
                exitSamples = 0
                status = "鳴潮已離開前景；GPU 原始值已確認還原。${gpuSummary()}"
            }
        } else {
            exitSamples = 0
            boostAttempted = false
            status = "自動偵測中；目前前景：$foreground"
        }
    }

    private fun applyBoostLocked() {
        boostAttempted = true
        val original = readMin()
        if (original == null || !GpuFrequencyPolicy.isValid(original)) {
            status = "無法安全讀取 SGPU min_freq；拒絕修改"
            return
        }
        when (GpuFrequencyPolicy.shouldRaise(original)) {
            null -> {
                status = "原始 min_freq=$original 不在白名單；拒絕修改"
                return
            }
            false -> {
                status = "原始 min_freq=$original 已達或高於 750 MHz；保留原值"
                return
            }
        }

        val maximum = readNode(MAX_NODE)
        val availableText = readNode(AVAILABLE_NODE)
        val available = availableText?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.toSet()
        if (!GpuFrequencyPolicy.isHardwareTargetSupported(TARGET, maximum, available)) {
            status = "拒絕套用 750 MHz：max_freq=$maximum；available_frequencies=$availableText"
            return
        }
        if (!writeBackup(original)) {
            status = "GPU 原始值備份建立或校驗失敗；拒絕修改"
            return
        }

        val error = writeAndVerify(TARGET)
        if (error != null) {
            status = "min_freq 寫入／讀回失敗：$error；正在還原"
            if (restoreLocked()) status = "未套用加速：$error；原始值 $original 已確認還原"
            else status = "套用與還原都未完成：$error；備份保留。$status"
            return
        }

        boostActive = true
        status = "min_freq 下限 750000 已寫入且讀回一致；非固定 GPU 頻率或 FPS 保證。${gpuSummary()}"
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

    private fun writeBackup(original: String): Boolean {
        var temp: File? = null
        var promoted = false
        return try {
            if (backup.exists() || !GpuFrequencyPolicy.isValid(original)) return false
            if (!directory.exists() && !directory.mkdirs()) return false
            Os.chmod(directory.absolutePath, 448)
            val payload = "1\n${Build.FINGERPRINT}\n$original\n"
            val complete = payload + sha256(payload) + "\n"
            val tempFile = File(directory, "gpu-state-v1.tmp")
            temp = tempFile
            if (tempFile.exists() && !tempFile.delete()) return false
            FileOutputStream(tempFile).use {
                it.write(complete.toByteArray(StandardCharsets.UTF_8))
                it.flush()
                it.fd.sync()
            }
            Os.chmod(tempFile.absolutePath, 384)
            if (readBackupFrom(tempFile) == null) {
                tempFile.delete()
                return false
            }
            if (!tempFile.renameTo(backup)) {
                tempFile.delete()
                return false
            }
            promoted = true
            Os.chmod(backup.absolutePath, 384)
            if (readBackup() != null) true
            else {
                backup.delete()
                promoted = false
                false
            }
        } catch (_: Exception) {
            temp?.delete()
            if (promoted) backup.delete()
            false
        }
    }

    private fun readBackup(): BackupState? = readBackupFrom(backup)

    private fun readBackupFrom(file: File): BackupState? {
        return try {
            if (!file.isFile) return null
            val lines = file.readLines(StandardCharsets.UTF_8)
            if (lines.size != 4 || lines[0] != "1") return null
            val payload = "${lines[0]}\n${lines[1]}\n${lines[2]}\n"
            if (sha256(payload) != lines[3] || !GpuFrequencyPolicy.isValid(lines[2])) null
            else BackupState(lines[1], lines[2])
        } catch (_: Exception) {
            null
        }
    }

    private fun restoreLocked(): Boolean {
        if (!backup.exists()) {
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
        if (current != state.original) {
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
