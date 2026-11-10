package com.example.wuwaaccelerator
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class GameFpsResult(val success: Boolean, val applied: Boolean, val message: String)
internal data class GameModeIntervention(val scaling: String, val useAngle: Boolean, val fps: String)

internal object GameModeInterventionParser {
    fun performance(output: String): GameModeIntervention? {
        val m = Regex("""Game Mode\s*:\s*2\s*,\s*Scaling\s*:\s*([0-9.]+)\s*,\s*Use Angle\s*:\s*(true|false)\s*,\s*Fps\s*:\s*([^,\]]*)""", RegexOption.IGNORE_CASE)
            .find(output) ?: return null
        return GameModeIntervention(m.groupValues[1], m.groupValues[2].equals("true", true), m.groupValues[3].trim())
    }
}

internal class GameFpsInterventionController(
    private val gamePackage: String,
    private val stateFile: File,
    private val commandTimeoutMs: Long = 2500L,
    private val runner: (List<String>, Long) -> String? = ::runCommand
) {
    fun apply(): GameFpsResult {
        if (stateFile.exists()) {
            val prior = restore()
            if (!prior.success) return GameFpsResult(false, false, "舊 FPS 備份尚未還原：" + prior.message)
        }
        val override = command(listOf("device_config", "get", "game_overlay", gamePackage))?.trim()
            ?: return GameFpsResult(true, false, "無法確認原始 game_overlay；略過 FPS intervention")
        if (!override.equals("null", true)) return GameFpsResult(true, false, "存在既有 game_overlay 設定；保留原設定")
        val before = readConfig() ?: return GameFpsResult(true, false, "無法解析 OEM Performance profile；不修改")
        if (before.scaling != "1.0" || before.useAngle || before.fps.isNotEmpty()) {
            return GameFpsResult(true, false, "OEM Performance profile 已有設定；不覆寫")
        }
        if (!writeState(before)) return GameFpsResult(false, false, "無法保存 OEM FPS 原始設定；未修改")
        if (command(listOf("cmd", "game", "set", "--mode", "2", "--fps", "60", gamePackage)) == null) {
            val rollback = restore()
            return GameFpsResult(false, false, "設定 60 FPS intervention 失敗；" + rollback.message)
        }
        val after = readConfig()
        if (after?.scaling == before.scaling && after.useAngle == before.useAngle && after.fps == "60") {
            return GameFpsResult(true, true, "已讀回確認 Performance FPS=60，解析度比例維持 1.0")
        }
        val rollback = restore()
        return GameFpsResult(false, false, "60 FPS intervention 讀回驗證失敗；" + rollback.message)
    }

    fun restore(): GameFpsResult {
        if (!stateFile.exists()) return GameFpsResult(true, false, "沒有 FPS intervention 備份")
        val original = readState() ?: return GameFpsResult(false, false, "FPS intervention 備份校驗失敗；保留備份")
        val current = readConfig() ?: return GameFpsResult(false, false, "無法讀回 Performance profile；保留備份")
        if (current == original) {
            if (!stateFile.delete()) return GameFpsResult(false, false, "設定已是 OEM 原始值，但備份無法刪除")
            return GameFpsResult(true, false, "FPS intervention 原本未生效；已清除無需還原的備份")
        }
        if (current.scaling != original.scaling || current.useAngle != original.useAngle || current.fps != "60") {
            return GameFpsResult(false, false, "FPS intervention 已被外部修改；拒絕覆蓋，備份保留")
        }
        if (command(listOf("cmd", "game", "reset", "--mode", "2", gamePackage)) == null) {
            return GameFpsResult(false, false, "重設 Performance FPS intervention 失敗；備份保留")
        }
        if (readConfig() != original) return GameFpsResult(false, false, "FPS intervention 還原讀回不符；備份保留")
        if (!stateFile.delete()) return GameFpsResult(false, false, "FPS intervention 已還原，但備份無法刪除")
        return GameFpsResult(true, false, "FPS intervention 已還原至 OEM 原始設定")
    }

    private fun readConfig(): GameModeIntervention? =
        command(listOf("cmd", "game", "list-configs", gamePackage))?.let(GameModeInterventionParser::performance)

    private fun writeState(state: GameModeIntervention): Boolean = runCatching {
        val parent = stateFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        if (!restrictToOwner(parent, true)) return false
        val payload = "1\n" + state.scaling + "\n" + state.useAngle + "\n" + state.fps + "\n"
        val complete = payload + sha256(payload) + "\n"
        val temp = File(parent, stateFile.name + ".tmp")
        if (temp.exists() && !temp.delete()) return false
        FileOutputStream(temp).use { it.write(complete.toByteArray(StandardCharsets.UTF_8)); it.flush(); it.fd.sync() }
        if (!restrictToOwner(temp, false)) { temp.delete(); return false }
        if (readStateFrom(temp) != state) { temp.delete(); return false }
        Files.move(temp.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        if (!restrictToOwner(stateFile, false)) return false
        readState() == state
    }.getOrDefault(false)

    private fun readState(): GameModeIntervention? = readStateFrom(stateFile)
    private fun readStateFrom(file: File): GameModeIntervention? = runCatching {
        if (!file.isFile) return null
        val lines = file.readLines(StandardCharsets.UTF_8)
        if (lines.size != 5 || lines[0] != "1") return null
        val payload = lines.take(4).joinToString("\n", postfix = "\n")
        if (sha256(payload) != lines[4]) return null
        val angle = lines[2].toBooleanStrictOrNull() ?: return null
        if (lines[1] != "1.0" || angle || lines[3].isNotEmpty()) return null
        GameModeIntervention(lines[1], angle, lines[3])
    }.getOrNull()

    private fun restrictToOwner(file: File, directory: Boolean): Boolean {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        val readable = file.setReadable(true, true)
        val writable = file.setWritable(true, true)
        val executable = !directory || file.setExecutable(true, true)
        return readable && writable && executable
    }

    private fun command(args: List<String>): String? = runner(args, commandTimeoutMs)
    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private fun runCommand(args: List<String>, timeoutMs: Long): String? {
            return try {
                val exe = if (args.first() == "cmd") "/system/bin/cmd" else "/system/bin/" + args.first()
                val p = ProcessBuilder(listOf(exe) + args.drop(1)).redirectErrorStream(true).start()
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                    runCatching { p.waitFor(250, TimeUnit.MILLISECONDS) }
                    return null
                }
                val output = p.inputStream.bufferedReader().use { it.readText() }
                if (p.exitValue() == 0) output else null
            } catch (_: Exception) { null }
        }
    }
}
