package com.example.wuwaaccelerator
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameFpsInterventionControllerTest {
    private fun config(fps: String = "") =
        "Name:game.pkg Modes: {1=[Game Mode:1,Scaling:1.0,Use Angle:false,Fps:,Loading Boost Duration:-1], " +
            "2=[Game Mode:2,Scaling:1.0,Use Angle:false,Fps:" + fps + ",Loading Boost Duration:-1]}"

    @Test fun parsesPerformanceConfig() {
        assertEquals(GameModeIntervention("1.0", false, ""), GameModeInterventionParser.performance(config()))
        assertEquals("60", GameModeInterventionParser.performance(config("60"))?.fps)
    }

    @Test fun refusesExistingDeviceConfigOverride() {
        val d = Files.createTempDirectory("wuwa-fps").toFile()
        val runner: (List<String>, Long) -> String? = { a, _ -> if (a.firstOrNull() == "device_config") "mode=2,fps=45" else config() }
        val r = GameFpsInterventionController("game.pkg", File(d, "state"), runner = runner).apply()
        assertTrue(r.success); assertFalse(r.applied); d.deleteRecursively()
    }

    @Test fun applies60FpsAndRestoresOemDefault() {
        val d = Files.createTempDirectory("wuwa-fps").toFile()
        var fps = ""
        val runner: (List<String>, Long) -> String? = { a, _ ->
            when {
                a.firstOrNull() == "device_config" -> "null"
                a.getOrNull(2) == "list-configs" -> config(fps)
                a.getOrNull(2) == "set" -> { fps = "60"; "ok" }
                a.getOrNull(2) == "reset" -> { fps = ""; "ok" }
                else -> null
            }
        }
        val c = GameFpsInterventionController("game.pkg", File(d, "state"), runner = runner)
        assertTrue(c.apply().applied); assertEquals("60", fps)
        assertTrue(c.restore().success); assertEquals("", fps)
        assertFalse(File(d, "state").exists()); d.deleteRecursively()
    }

    @Test fun rollsBackWhenReadBackDoesNotMatch() {
        val d = Files.createTempDirectory("wuwa-fps").toFile()
        var fps = ""
        val runner: (List<String>, Long) -> String? = { a, _ ->
            when {
                a.firstOrNull() == "device_config" -> "null"
                a.getOrNull(2) == "list-configs" -> config(fps)
                a.getOrNull(2) == "set" -> { fps = "45"; "ok" }
                a.getOrNull(2) == "reset" -> { fps = ""; "ok" }
                else -> null
            }
        }
        assertFalse(GameFpsInterventionController("game.pkg", File(d, "state"), runner = runner).apply().success)
        assertEquals("", fps); assertFalse(File(d, "state").exists()); d.deleteRecursively()
    }

    @Test fun preservesExternallyChangedInterventionOnRestore() {
        val d = Files.createTempDirectory("wuwa-fps").toFile()
        var fps = ""
        val runner: (List<String>, Long) -> String? = { a, _ ->
            when {
                a.firstOrNull() == "device_config" -> "null"
                a.getOrNull(2) == "list-configs" -> config(fps)
                a.getOrNull(2) == "set" -> { fps = "60"; "ok" }
                a.getOrNull(2) == "reset" -> { fps = ""; "ok" }
                else -> null
            }
        }
        val c = GameFpsInterventionController("game.pkg", File(d, "state"), runner = runner)
        assertTrue(c.apply().success); fps = "45"
        assertFalse(c.restore().success); assertEquals("45", fps); assertTrue(File(d, "state").exists())
        d.deleteRecursively()
    }

    @Test fun doesNotOverrideNonDefaultOemProfile() {
        val d = Files.createTempDirectory("wuwa-fps").toFile()
        val runner: (List<String>, Long) -> String? = { a, _ ->
            when {
                a.firstOrNull() == "device_config" -> "null"
                else -> config("45")
            }
        }
        val r = GameFpsInterventionController("game.pkg", File(d, "state"), runner = runner).apply()
        assertTrue(r.success); assertFalse(r.applied); d.deleteRecursively()
    }
}
