package com.example.wuwaaccelerator

import android.os.Build
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
        private const val GAME_PACKAGE = "com.kurogame.wutheringwaves.global"
        private const val GPU_MIN_FREQ_PATH =
            "/sys/class/devfreq/10e00000.sgpu/interface/min_freq"

        // Initial test floor: one available step above 648 MHz on this device.
        // Thermal limits remain under kernel / vendor control.
        private const val BOOST_MIN_FREQ = GpuFrequencyPolicy.BOOST_MIN_FREQ

        private const val POLL_INTERVAL_MS = 1500L
        private const val UNKNOWN_RESTORE_SAMPLES = 3
        private const val EXIT_RESTORE_SAMPLES = 2
    }

    private val lock = Any()
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "WuWa-GPU-Monitor").apply { isDaemon = true }
        }

    private val backupDirectory = File("/data/local/tmp/WuWaAccelerator")
    private val backupFile = File(backupDirectory, "gpu-state-v1")
    private val foregroundOutput = File(backupDirectory, "foreground.txt")
    private val gpuMinFreqFile = File(GPU_MIN_FREQ_PATH)

    @Volatile
    private var status = "尚未啟動"

    private var monitoring = false
    private var boostAttempted = false
    private var boostActive = false
    private var unknownSamples = 0
    private var nonGameSamples = 0
    private var monitorFuture: ScheduledFuture<*>? = null

    override fun startMonitoring(): Boolean = synchronized(lock) {
        if (monitoring) {
            status = "自動偵測已在執行"
            return@synchronized true
        }

        // Recover an unfinished previous session before applying anything new.
        if (backupFile.exists() && !restoreLocked()) {
            status = "安全還原失敗，保留備份；拒絕開始新的加速"
            return@synchronized false
        }

        if (!SupportedDevicePolicy.isSupported(Build.MANUFACTURER, Build.MODEL)) {
            status = "本版本只支援 Samsung SM-A5560；未修改任何 GPU 設定"
            return@synchronized false
        }

        monitoring = true
        boostAttempted = false
        boostActive = false
        unknownSamples = 0
        nonGameSamples = 0
        scheduleMonitorLocked(POLL_INTERVAL_MS)
        status = "自動偵測已啟動，等待鳴潮進入前景"
        true
    }

    override fun stopAndRestore(): Boolean = synchronized(lock) {
        monitoring = false
        val restored = restoreLocked()

        if (restored) {
            monitorFuture?.cancel(false)
            monitorFuture = null
            boostAttempted = false
            boostActive = false
            status = "已停止自動偵測；GPU 原始設定已還原"
        } else {
            status = "GPU 還原尚未完成；備份已保留，背景正在重試"
            scheduleMonitorLocked(2000L)
        }

        restored
    }

    override fun getStatus(): String = status

    override fun destroy() {
        synchronized(lock) {
            monitoring = false
            monitorFuture?.cancel(false)
            monitorFuture = null
            // A final best-effort restore. If it fails, the validated backup remains.
            restoreLocked()
            scheduler.shutdownNow()
        }
        System.exit(0)
    }

    private fun scheduleMonitorLocked(initialDelayMs: Long) {
        monitorFuture?.cancel(false)
        monitorFuture = scheduler.scheduleWithFixedDelay(
            {
                synchronized(lock) {
                    if (monitoring) {
                        pollForegroundLocked()
                    } else if (backupFile.exists()) {
                        if (restoreLocked()) {
                            status = "GPU 原始設定已還原"
                            monitorFuture?.cancel(false)
                            monitorFuture = null
                        }
                    }
                }
            },
            initialDelayMs,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun pollForegroundLocked() {
        val foregroundPackage = readForegroundPackage()

        if (foregroundPackage == null) {
            unknownSamples++
            if (backupFile.exists() && unknownSamples >= UNKNOWN_RESTORE_SAMPLES) {
                if (restoreLocked()) {
                    boostAttempted = false
                    boostActive = false
                    nonGameSamples = 0
                    status = "無法確認前景程式，已依安全原則還原 GPU 設定"
                }
            } else if (!backupFile.exists()) {
                status = "前景辨識暫時失敗，正在重試"
            }
            return
        }

        unknownSamples = 0

        if (foregroundPackage == GAME_PACKAGE) {
            nonGameSamples = 0

            if (backupFile.exists()) {
                if (boostActive) {
                    status = "鳴潮正在前景執行；GPU 加速已套用"
                } else if (restoreLocked()) {
                    status = "先前的 GPU 操作未完成，已還原；本次不重複套用"
                    boostAttempted = true
                }
            } else if (!boostAttempted) {
                applyGpuBoostLocked()
            }
            return
        }

        if (backupFile.exists()) {
            nonGameSamples++
            if (nonGameSamples >= EXIT_RESTORE_SAMPLES && restoreLocked()) {
                boostAttempted = false
                boostActive = false
                nonGameSamples = 0
                status = "鳴潮已離開前景；GPU 原始設定已還原"
            }
        } else {
            nonGameSamples = 0
            boostAttempted = false
            status = "自動偵測中，等待鳴潮"
        }
    }

    private fun applyGpuBoostLocked() {
        boostAttempted = true

        val original = readGpuMinFreq()
        if (original == null || !isValidFrequency(original)) {
            status = "無法安全讀取 SGPU 最低頻率；未修改任何設定"
            return
        }

        val shouldRaise = GpuFrequencyPolicy.shouldRaise(original)
        if (shouldRaise == null) {
            status = "GPU 原始最低頻率不在已確認的格式範圍；未修改任何設定"
            return
        }
        if (!shouldRaise) {
            status = "GPU 原始最低頻率已達或高於 750 MHz；保留原設定，不降低 GPU 下限"
            return
        }

        if (!writeBackup(original)) {
            status = "無法建立安全備份；拒絕修改 GPU"
            return
        }

        val writeSucceeded = writeGpuMinFreq(BOOST_MIN_FREQ)
        val readBack = readGpuMinFreq()

        if (!writeSucceeded || readBack != BOOST_MIN_FREQ) {
            status = "GPU 加速寫入失敗；正在還原原始設定"
            if (restoreLocked()) {
                status = "GPU 節點拒絕加速寫入；原始設定已還原"
            } else {
                status = "GPU 寫入及即時還原均未完成；備份保留並持續重試"
            }
            return
        }

        boostActive = true
        status = "已偵測到鳴潮；SGPU 最低頻率提升至 750 MHz"
    }

    private fun readForegroundPackage(): String? {
        return try {
            if (!backupDirectory.exists() && !backupDirectory.mkdirs()) {
                return null
            }
            Os.chmod(backupDirectory.absolutePath, 448) // 0700

            val command =
                "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'"
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .redirectOutput(foregroundOutput)
                .start()

            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }

            if (process.exitValue() != 0 || !foregroundOutput.isFile) {
                return null
            }

            ForegroundPackageParser.parse(foregroundOutput.readLines())
        } catch (_: Exception) {
            null
        }
    }

    private fun readGpuMinFreq(): String? {
        return try {
            if (!gpuMinFreqFile.exists()) return null
            gpuMinFreqFile.readText().trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeGpuMinFreq(value: String): Boolean {
        return try {
            if (!isValidFrequency(value)) return false
            FileOutputStream(gpuMinFreqFile).use { stream ->
                stream.write((value + "\n").toByteArray(StandardCharsets.US_ASCII))
                stream.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidFrequency(value: String): Boolean {
        return GpuFrequencyPolicy.isValid(value)
    }

    private data class BackupState(
        val fingerprint: String,
        val originalMinFreq: String
    )

    private fun writeBackup(originalMinFreq: String): Boolean {
        return try {
            if (backupFile.exists()) return false
            if (!backupDirectory.exists() && !backupDirectory.mkdirs()) return false
            Os.chmod(backupDirectory.absolutePath, 448) // 0700

            val payload = "1\n${Build.FINGERPRINT}\n$originalMinFreq\n"
            val complete = payload + sha256(payload) + "\n"
            val temporary = File(backupDirectory, "gpu-state-v1.tmp")
            if (temporary.exists() && !temporary.delete()) return false

            FileOutputStream(temporary).use { stream ->
                stream.write(complete.toByteArray(StandardCharsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            Os.chmod(temporary.absolutePath, 384) // 0600

            if (!temporary.renameTo(backupFile)) {
                temporary.delete()
                return false
            }
            Os.chmod(backupFile.absolutePath, 384) // 0600
            readBackup() != null
        } catch (_: Exception) {
            false
        }
    }

    private fun readBackup(): BackupState? {
        return try {
            if (!backupFile.isFile) return null
            val lines = backupFile.readLines(StandardCharsets.UTF_8)
            if (lines.size != 4 || lines[0] != "1") return null

            val payload = "${lines[0]}\n${lines[1]}\n${lines[2]}\n"
            if (sha256(payload) != lines[3]) return null
            if (!isValidFrequency(lines[2])) return null

            BackupState(lines[1], lines[2])
        } catch (_: Exception) {
            null
        }
    }

    private fun restoreLocked(): Boolean {
        if (!backupFile.exists()) {
            boostActive = false
            return true
        }

        val backup = readBackup()
        if (backup == null) {
            status = "GPU 備份檔格式或校驗不正確；拒絕猜測還原值"
            return false
        }

        if (backup.fingerprint != Build.FINGERPRINT) {
            status = "裝置韌體指紋與備份不符；拒絕寫入舊設定"
            return false
        }

        if (!writeGpuMinFreq(backup.originalMinFreq)) {
            status = "無法寫回 GPU 原始最低頻率"
            return false
        }

        if (readGpuMinFreq() != backup.originalMinFreq) {
            status = "GPU 原始頻率寫回後驗證失敗"
            return false
        }

        if (!backupFile.delete()) {
            status = "GPU 設定已寫回，但備份檔無法刪除；將再次驗證"
            return false
        }

        boostActive = false
        return true
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
