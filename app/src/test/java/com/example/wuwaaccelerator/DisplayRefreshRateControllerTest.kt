package com.example.wuwaaccelerator

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayRefreshRateControllerTest {
    private fun directory(): File = Files.createTempDirectory("wuwa-refresh").toFile()

    @Test fun applies60HzAndRestoresOriginalRange() {
        val dir = directory()
        var min = "60.0"
        var peak = "120.0"
        val runner: (List<String>, Long) -> String? = { args, _ ->
            when {
                args.getOrNull(1) == "get" && args.lastOrNull() == "min_refresh_rate" -> min
                args.getOrNull(1) == "get" && args.lastOrNull() == "peak_refresh_rate" -> peak
                args.getOrNull(1) == "put" && args.getOrNull(3) == "min_refresh_rate" -> { min = args.last(); "ok" }
                args.getOrNull(1) == "put" && args.getOrNull(3) == "peak_refresh_rate" -> { peak = args.last(); "ok" }
                else -> null
            }
        }
        val controller = DisplayRefreshRateController(File(dir, "refresh-state"), "test-fingerprint", runner = runner)
        val applied = controller.apply()
        assertTrue(applied.success)
        assertTrue(applied.applied)
        assertEquals("60.0", min)
        assertEquals("60.0", peak)
        assertTrue(controller.restore().success)
        assertEquals("60.0", min)
        assertEquals("120.0", peak)
        assertFalse(File(dir, "refresh-state").exists())
        dir.deleteRecursively()
    }

    @Test fun doesNotWriteWhenDisplayAlreadyAtOrBelow60Hz() {
        val dir = directory()
        var writes = 0
        val runner: (List<String>, Long) -> String? = { args, _ ->
            when {
                args.lastOrNull() == "min_refresh_rate" -> "60.0"
                args.lastOrNull() == "peak_refresh_rate" -> "60.0"
                args.getOrNull(1) == "put" -> { writes++; "ok" }
                else -> null
            }
        }
        val result = DisplayRefreshRateController(File(dir, "state"), "fp", runner = runner).apply()
        assertTrue(result.success)
        assertFalse(result.applied)
        assertEquals(0, writes)
        dir.deleteRecursively()
    }

    @Test fun skipsWhenSamsungSettingKeysAreUnavailable() {
        val dir = directory()
        var writes = 0
        val runner: (List<String>, Long) -> String? = { args, _ ->
            if (args.getOrNull(1) == "put") { writes++; "ok" } else "null"
        }
        val result = DisplayRefreshRateController(File(dir, "state"), "fp", runner = runner).apply()
        assertTrue(result.success)
        assertFalse(result.applied)
        assertEquals(0, writes)
        dir.deleteRecursively()
    }

    @Test fun refusesToOverwriteExternalRefreshRateChange() {
        val dir = directory()
        var min = "60.0"
        var peak = "120.0"
        val runner: (List<String>, Long) -> String? = { args, _ ->
            when {
                args.getOrNull(1) == "get" && args.lastOrNull() == "min_refresh_rate" -> min
                args.getOrNull(1) == "get" && args.lastOrNull() == "peak_refresh_rate" -> peak
                args.getOrNull(1) == "put" && args.getOrNull(3) == "min_refresh_rate" -> { min = args.last(); "ok" }
                args.getOrNull(1) == "put" && args.getOrNull(3) == "peak_refresh_rate" -> { peak = args.last(); "ok" }
                else -> null
            }
        }
        val state = File(dir, "state")
        val controller = DisplayRefreshRateController(state, "fp", runner = runner)
        assertTrue(controller.apply().applied)
        peak = "90.0"
        assertFalse(controller.restore().success)
        assertEquals("90.0", peak)
        assertTrue(state.exists())
        dir.deleteRecursively()
    }

    @Test fun rollsBackWhenSecondSettingDoesNotTakeEffect() {
        val dir = directory()
        var min = "60.0"
        var peak = "120.0"
        val runner: (List<String>, Long) -> String? = { args, _ ->
            when {
                args.getOrNull(1) == "get" && args.lastOrNull() == "min_refresh_rate" -> min
                args.getOrNull(1) == "get" && args.lastOrNull() == "peak_refresh_rate" -> peak
                args.getOrNull(1) == "put" && args.getOrNull(3) == "min_refresh_rate" -> { min = args.last(); "ok" }
                args.getOrNull(1) == "put" && args.getOrNull(3) == "peak_refresh_rate" -> "ok"
                else -> null
            }
        }
        val state = File(dir, "state")
        val controller = DisplayRefreshRateController(state, "fp", runner = runner)
        val result = controller.apply()
        assertFalse(result.success)
        assertEquals("60.0", min)
        assertEquals("120.0", peak)
        assertFalse(state.exists())
        dir.deleteRecursively()
    }
}
