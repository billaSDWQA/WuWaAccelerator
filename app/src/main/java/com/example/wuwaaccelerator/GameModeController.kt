package com.example.wuwaaccelerator

import java.io.File
import java.util.concurrent.TimeUnit

internal data class GameModeResult(val success: Boolean, val message: String)
internal data class GameModeSnapshot(val currentMode: String, val availableModes: Set<Int>)

internal object GameModeParser {
    fun parse(output: String): GameModeSnapshot? {
        val current = Regex("""current\s+mode\s*:\s*([a-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.get(1)?.lowercase() ?: return null
        val availableText = Regex("""available\s+game\s+modes\s*:\s*\[([^]]*)]""", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.get(1) ?: return null
        val modes = availableText.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        return GameModeSnapshot(current, modes)
    }
}

internal class GameModeController(
    private val gamePackage: String,
    private val stateFile: File,
    private val commandTimeoutMs: Long = 2500L,
    private val runner: (List<String>, Long) -> String? = ::runCommand
) {
    fun applyPerformance(): GameModeResult {
        if (stateFile.exists()) {
            val prior = restore()
            if (!prior.success) return GameModeResult(false, "先前 Game Mode 備份尚未還原：${prior.message}")
        }
        val snapshot = readSnapshot()
            ?: return GameModeResult(false, "無法讀取鳴潮 Game Mode；未變更設定")
        if (2 !in snapshot.availableModes) {
            return GameModeResult(false, "裝置未向鳴潮提供 Performance mode；未變更設定")
        }
        if (snapshot.currentMode == "performance") {
            return GameModeResult(true, "鳴潮已處於 Performance mode；未額外修改設定")
        }
        if (snapshot.currentMode != "standard") {
            return GameModeResult(false, "鳴潮目前模式為 ${snapshot.currentMode}；拒絕覆蓋自訂設定")
        }
        if (!writeState(snapshot.currentMode)) {
            return GameModeResult(false, "無法安全保存原 Game Mode；未切換")
        }
        if (runCommand(listOf("cmd", "game", "mode", "performance", gamePackage)) == null) {
            val rollback = restore()
            return GameModeResult(false, "切換 Performance mode 失敗；${rollback.message}")
        }
        if (readSnapshot()?.currentMode == "performance") {
            return GameModeResult(true, "已切換並讀回確認鳴潮 Performance mode；未套用降解析度或 FPS 覆寫")
        }
        val rollback = restore()
        return GameModeResult(false, "Performance mode 讀回驗證失敗；${rollback.message}")
    }

    fun restore(): GameModeResult {
        if (!stateFile.exists()) return GameModeResult(true, "沒有需要還原的 Game Mode")
        val original = runCatching { stateFile.readText().trim() }.getOrNull()
        if (original != "standard" && original != "performance") {
            return GameModeResult(false, "Game Mode 備份無效；保留檔案，不盲目還原")
        }
        val current = readSnapshot()?.currentMode
            ?: return GameModeResult(false, "無法讀回目前 Game Mode；保留備份，不盲目還原")
        if (current != original && current != "performance") {
            return GameModeResult(false, "偵測到 Game Mode 已被外部改為 $current；不覆蓋外部設定，備份保留")
        }
        if (current == "performance") {
            if (runCommand(listOf("cmd", "game", "mode", original, gamePackage)) == null) {
                return GameModeResult(false, "還原 Game Mode 至 $original 失敗；備份保留")
            }
        }
        if (readSnapshot()?.currentMode != original) {
            return GameModeResult(false, "Game Mode 還原讀回不符；備份保留")
        }
        if (!stateFile.delete()) {
            return GameModeResult(false, "Game Mode 已還原，但無法刪除備份")
        }
        return GameModeResult(true, "Game Mode 已還原至 $original")
    }

    private fun readSnapshot(): GameModeSnapshot? {
        val output = runner(listOf("cmd", "game", "list-modes", gamePackage), commandTimeoutMs) ?: return null
        return GameModeParser.parse(output)
    }

    private fun writeState(mode: String): Boolean = runCatching {
        val parent = stateFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temp = File(parent, stateFile.name + ".tmp")
        temp.writeText(mode)
        if (!temp.renameTo(stateFile)) return false
        stateFile.readText().trim() == mode
    }.getOrDefault(false)

    private fun runCommand(args: List<String>): String? = runner(args, commandTimeoutMs)

    companion object {
        private fun runCommand(args: List<String>, timeoutMs: Long): String? {
            return try {
                val process = ProcessBuilder(listOf("/system/bin/" + args.first()) + args.drop(1))
                    .redirectErrorStream(true).start()
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    runCatching { process.waitFor(250, TimeUnit.MILLISECONDS) }
                    return null
                }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (process.exitValue() == 0) output else null
            } catch (_: Exception) { null }
        }
    }
}
