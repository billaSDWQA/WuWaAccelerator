package com.example.wuwaaccelerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertNull(GameModeParser.parse("null"))
    }
    @Test fun rejectsMissingModeList() {
        assertNull(GameModeParser.parse("current mode: performance"))
    }
}
