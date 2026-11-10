package com.example.wuwaaccelerator

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class DisplayRefreshResult(
    val success: Boolean,
    val applied: Boolean,
    val message: String
)

/**
 * Temporarily aligns the display refresh range to a 60 FPS game.
 *
 * This changes only Android's system display-rate settings. It does not cap
 * the game's FPS, change resolution/quality, or write CPU/GPU/thermal nodes.
 * Unsupported or unreadable settings are treated as unavailable.
 */
internal class DisplayRefreshRateController(
    private val stateFile: File,
    private val fingerprint: String,
    private val commandTimeoutMs: Long = 2500L,
    private val runner: (List<String>, Long) -> String? = ::runCommand
) {
    private data class State(
        val fingerprint: String,
        val originalMin: String,
        val originalPeak: String
    )

    fun apply(): DisplayRefreshResult {
        if (stateFile.exists()) {
            val previous = restore()
            if (!previous.success) {
                return DisplayRefreshResult(false, false, "既有螢幕刷新率備份未能還原：${previous.message}")
            }
        }

        val originalMin = readSetting(MIN_KEY)
            ?: return DisplayRefreshResult(true, false, "裝置未提供可讀取的 min_refresh_rate；略過 60Hz 顯示節能")
        val originalPeak = readSetting(PEAK_KEY)
            ?: return DisplayRefreshResult(true, false, "裝置未提供可讀取的 peak_refresh_rate；略過 60Hz 顯示節能")
        val minValue = parseRate(originalMin)
            ?: return DisplayRefreshResult(true, false, "min_refresh_rate 格式不支援；未修改")
        val peakValue = parseRate(originalPeak)
            ?: return DisplayRefreshResult(true, false, "peak_refresh_rate 格式不支援；未修改")

        if (minValue > peakValue) {
            return DisplayRefreshResult(false, false, "螢幕刷新率範圍異常（min > peak）；拒絕修改")
        }
        if (peakValue <= TARGET_HZ) {
            return DisplayRefreshResult(true, false, "螢幕最高刷新率已不高於 60Hz；不修改")
        }

        val state = State(fingerprint, originalMin, originalPeak)
        if (!writeState(state)) {
            return DisplayRefreshResult(false, false, "無法安全保存原始螢幕刷新率；未修改")
        }

        // Lower the minimum first, then the peak, avoiding an invalid min > peak interval.
        if (!putSetting(MIN_KEY, TARGET_VALUE) || !putSetting(PEAK_KEY, TARGET_VALUE) ||
            !isRate(readSetting(MIN_KEY), TARGET_HZ) ||
            !isRate(readSetting(PEAK_KEY), TARGET_HZ)
        ) {
            val rollback = restore()
            return DisplayRefreshResult(
                false,
                false,
                "60Hz 設定寫入／讀回驗證失敗；${rollback.message}"
            )
        }

        return DisplayRefreshResult(
            true,
            true,
            "已讀回確認顯示刷新率 60Hz（鳴潮遊戲 FPS 設定維持 60）；離開遊戲時還原"
        )
    }

    fun restore(): DisplayRefreshResult {
        if (!stateFile.exists()) {
            return DisplayRefreshResult(true, false, "沒有螢幕刷新率備份")
        }
        val state = readState()
            ?: return DisplayRefreshResult(false, false, "螢幕刷新率備份校驗失敗；保留備份")
        if (state.fingerprint != fingerprint) {
            return DisplayRefreshResult(false, false, "韌體指紋與螢幕刷新率備份不符；拒絕還原，保留備份")
        }

        val currentMin = readSetting(MIN_KEY)
            ?: return DisplayRefreshResult(false, false, "無法讀取目前 min_refresh_rate；保留備份")
        val currentPeak = readSetting(PEAK_KEY)
            ?: return DisplayRefreshResult(false, false, "無法讀取目前 peak_refresh_rate；保留備份")

        val minIsOriginal = sameRate(currentMin, state.originalMin)
        val peakIsOriginal = sameRate(currentPeak, state.originalPeak)
        if (minIsOriginal && peakIsOriginal) {
            if (!stateFile.delete()) {
                return DisplayRefreshResult(false, false, "設定已是原始值，但無法清除備份")
            }
            return DisplayRefreshResult(true, false, "螢幕刷新率原本已還原；備份已清除")
        }

        val minIsOurs = isRate(currentMin, TARGET_HZ)
        val peakIsOurs = isRate(currentPeak, TARGET_HZ)
        if ((!minIsOriginal && !minIsOurs) || (!peakIsOriginal && !peakIsOurs)) {
            return DisplayRefreshResult(false, false, "偵測到螢幕刷新率遭外部修改；拒絕覆蓋，備份保留")
        }

        // Restore peak first so the original min/peak range can be re-established.
        if (!putSetting(PEAK_KEY, state.originalPeak) ||
            !putSetting(MIN_KEY, state.originalMin) ||
            !sameRate(readSetting(PEAK_KEY), state.originalPeak) ||
            !sameRate(readSetting(MIN_KEY), state.originalMin)
        ) {
            return DisplayRefreshResult(false, false, "螢幕刷新率還原寫入／讀回驗證失敗；備份保留")
        }
        if (!stateFile.delete()) {
            return DisplayRefreshResult(false, false, "螢幕刷新率已還原，但無法清除備份")
        }
        return DisplayRefreshResult(true, false, "螢幕刷新率已確認還原至原始值")
    }

    private fun readSetting(key: String): String? =
        command(listOf("settings", "get", "system", key))
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", true) }

    private fun putSetting(key: String, value: String): Boolean =
        command(listOf("settings", "put", "system", key, value)) != null

    private fun parseRate(value: String): Double? =
        value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

    private fun sameRate(actual: String?, expected: String): Boolean {
        val a = actual?.let(::parseRate) ?: return false
        val b = parseRate(expected) ?: return false
        return kotlin.math.abs(a - b) < 0.01
    }

    private fun isRate(actual: String?, expected: Double): Boolean {
        val value = actual?.let(::parseRate) ?: return false
        return kotlin.math.abs(value - expected) < 0.01
    }

    private fun writeState(state: State): Boolean = runCatching {
        val parent = stateFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val payload = listOf(
            "1",
            state.fingerprint,
            state.originalMin,
            state.originalPeak,
            TARGET_VALUE,
            TARGET_VALUE
        ).joinToString("\n", postfix = "\n")
        val complete = payload + sha256(payload) + "\n"
        val temp = File(parent, stateFile.name + ".tmp")
        if (temp.exists() && !temp.delete()) return false
        FileOutputStream(temp).use {
            it.write(complete.toByteArray(StandardCharsets.UTF_8))
            it.flush()
            it.fd.sync()
        }
        temp.setReadable(false, false)
        temp.setWritable(false, false)
        if (!temp.setReadable(true, true) || !temp.setWritable(true, true)) {
            temp.delete()
            return false
        }
        if (readStateFrom(temp) != state) {
            temp.delete()
            return false
        }
        Files.move(temp.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        stateFile.setReadable(false, false)
        stateFile.setWritable(false, false)
        if (!stateFile.setReadable(true, true) || !stateFile.setWritable(true, true)) return false
        readState() == state
    }.getOrDefault(false)

    private fun readState(): State? = readStateFrom(stateFile)

    private fun readStateFrom(file: File): State? = runCatching {
        if (!file.isFile) return null
        val lines = file.readLines(StandardCharsets.UTF_8)
        if (lines.size != 7 || lines[0] != "1" || lines[4] != TARGET_VALUE || lines[5] != TARGET_VALUE) return null
        val payload = lines.take(6).joinToString("\n", postfix = "\n")
        if (sha256(payload) != lines[6]) return null
        if (parseRate(lines[2]) == null || parseRate(lines[3]) == null) return null
        State(lines[1], lines[2], lines[3])
    }.getOrNull()

    private fun command(args: List<String>): String? = runner(args, commandTimeoutMs)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val MIN_KEY = "min_refresh_rate"
        private const val PEAK_KEY = "peak_refresh_rate"
        private const val TARGET_HZ = 60.0
        private const val TARGET_VALUE = "60.0"

        private fun runCommand(args: List<String>, timeoutMs: Long): String? {
            return try {
                val process = ProcessBuilder(listOf("/system/bin/" + args.first()) + args.drop(1))
                    .redirectErrorStream(true)
                    .start()
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    runCatching { process.waitFor(250, TimeUnit.MILLISECONDS) }
                    return null
                }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (process.exitValue() == 0) output else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
