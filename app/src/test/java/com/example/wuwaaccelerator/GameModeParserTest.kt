package com.example.wuwaaccelerator

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameModeParserTest {
    @Test fun parsesPerformanceModeAndAvailableModes() {
        val parsed = GameModeParser.parse("com.kurogame.wutheringwaves.global current mode: performance, available game modes: [1, 2]")
        assertEquals("performance", parsed?.currentMode)
        assertEquals(setOf(1, 2), parsed?.availableModes)
    }
    @Test fun parsesStandardMode() {
        assertEquals("standard", GameModeParser.parse("current mode: standard, available game modes: [1, 2]")?.currentMode)
    }
    @Test fun rejectsUnknownOutput() {
        assertEquals(null, GameModeParser.parse("null"))
    }
    @Test fun rejectsMissingModeList() {
        assertEquals(null, GameModeParser.parse("current mode: performance"))
    }
    @Test fun switchesToPerformanceAndRestoresOriginalMode() {
        val dir = Files.createTempDirectory("wuwa-game-mode").toFile()
        var current = "standard"
        val runner: (List<String>, Long) -> String? = { args, _ ->
            when (args.drop(1).firstOrNull()) {
                "list-modes" -> "current mode: $current, available game modes: [1, 2]"
                "mode" -> { current = args[3]; "ok" }
                else -> null
            }
        }
        val controller = GameModeController("game.pkg", File(dir, "mode-state"), runner = runner)
        assertTrue(controller.applyPerformance().success)
        assertEquals("performance", current)
        assertTrue(File(dir, "mode-state").exists())
        assertTrue(controller.restore().success)
        assertEquals("standard", current)
        assertFalse(File(dir, "mode-state").exists())
        dir.deleteRecursively()
    }
    @Test fun doesNotChangeModeWhenPerformanceIsUnavailable() {
        val dir = Files.createTempDirectory("wuwa-game-mode").toFile()
        var current = "standard"
        val runner: (List<String>, Long) -> String? = { args, _ ->
            if (args.drop(1).firstOrNull() == "list-modes") "current mode: $current, available game modes: [1]"
            else { current = args.getOrNull(3) ?: current; "ok" }
        }
        val result = GameModeController("game.pkg", File(dir, "mode-state"), runner = runner).applyPerformance()
        assertFalse(result.success)
        assertEquals("standard", current)
        dir.deleteRecursively()
    }
    @Test fun alreadyActivePerformanceModeIsNotClaimedAsOurChange() {
        val dir = Files.createTempDirectory("wuwa-game-mode").toFile()
        val runner: (List<String>, Long) -> String? = { _, _ -> "current mode: performance, available game modes: [1, 2]" }
        val controller = GameModeController("game.pkg", File(dir, "mode-state"), runner = runner)
        assertTrue(controller.applyPerformance().success)
        assertFalse(File(dir, "mode-state").exists())
        dir.deleteRecursively()
    }
}
