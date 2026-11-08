package com.example.wuwaaccelerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundPackageParserTest {

    @Test
    fun prefersTopResumedActivityWhenBothFieldsAreParseable() {
        val lines = listOf(
            "mResumedActivity: ActivityRecord{a1 u0 com.kurogame.wutheringwaves.global/.MainActivity t12}",
            "topResumedActivity=ActivityRecord{b2 u0 com.android.systemui/.RecentsActivity t13}"
        )
        assertEquals("com.android.systemui", ForegroundPackageParser.parse(lines))
    }

    @Test
    fun fallsBackToLegacyWhenTopResumedIsNone() {
        val lines = listOf(
            "topResumedActivity=none",
            "mResumedActivity: ActivityRecord{a1 u0 com.kurogame.wutheringwaves.global/.MainActivity t12}"
        )
        assertEquals("com.kurogame.wutheringwaves.global", ForegroundPackageParser.parse(lines))
    }

    @Test
    fun fallsBackToLegacyWhenTopResumedIsMalformed() {
        val lines = listOf(
            "topResumedActivity=ActivityRecord{a1 u0 <unknown> t12}",
            "mResumedActivity: ActivityRecord{b2 u0 com.kurogame.wutheringwaves.global/.MainActivity t13}"
        )
        assertEquals("com.kurogame.wutheringwaves.global", ForegroundPackageParser.parse(lines))
    }

    @Test
    fun parsesLegacyResumedActivityWithUserPrefix() {
        val lines = listOf(
            "mResumedActivity: ActivityRecord{a1 u0 com.kurogame.wutheringwaves.global/.MainActivity t12}"
        )
        assertEquals("com.kurogame.wutheringwaves.global", ForegroundPackageParser.parse(lines))
    }

    @Test
    fun parsesComponentWithoutUserPrefix() {
        val lines = listOf(
            "topResumedActivity=ActivityRecord{a1 com.kurogame.wutheringwaves.global/.MainActivity t12}"
        )
        assertEquals("com.kurogame.wutheringwaves.global", ForegroundPackageParser.parse(lines))
    }

    @Test
    fun ignoresUnrelatedLines() {
        val lines = listOf(
            "ResumedActivity: ActivityRecord{a1 u0 com.kurogame.wutheringwaves.global/.MainActivity t12}",
            "topResumedActivity=none"
        )
        assertNull(ForegroundPackageParser.parse(lines))
    }

    @Test
    fun returnsNullWhenNoActivityComponentCanBeParsed() {
        val lines = listOf(
            "topResumedActivity=ActivityRecord{a1 u0 <unknown> t12}",
            "mResumedActivity=none"
        )
        assertNull(ForegroundPackageParser.parse(lines))
    }
}
