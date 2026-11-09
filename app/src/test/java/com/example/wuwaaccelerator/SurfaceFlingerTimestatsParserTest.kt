package com.example.wuwaaccelerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceFlingerTimestatsParserTest {
    @Test
    fun calculatesAverageAndOnePercentLowFromPresentationHistogram() {
        val output = """
            {
              "layers": [
                {
                  "layerName": "SurfaceView[com.kurogame.wutheringwaves.global/com.example.Game]",
                  "totalFrames": 100,
                  "droppedFrames": 3,
                  "present2present": {
                    "16666666": 80,
                    "33333333": 15,
                    "50000000": 5
                  }
                },
                {
                  "layerName": "StatusBar",
                  "totalFrames": 200,
                  "droppedFrames": 0
                }
              ]
            }
        """.trimIndent()
        val result = SurfaceFlingerTimestatsParser.parse(output, 1234L)
        assertEquals(100L, result.totalFrames)
        assertEquals(3L, result.droppedFrames)
        val expectedAverage = 1_000_000_000.0 /
            ((16_666_666.0 * 80.0 + 33_333_333.0 * 15.0 + 50_000_000.0 * 5.0) / 100.0)
        assertEquals(expectedAverage, result.averageFps!!, 0.01)
        assertEquals(20.0, result.onePercentLowFps!!, 0.01)
    }

    @Test
    fun doesNotGuessWhenGameLayerIsMissing() {
        val result = SurfaceFlingerTimestatsParser.parse(
            """{"layers":[{"layerName":"StatusBar","totalFrames":50}]}""",
            10L
        )
        assertNull(result.layerName)
        assertNotNull(result.status)
    }

    @Test
    fun onePercentLowWaitsUntilAtLeastOneHundredSamples() {
        val result = SurfaceFlingerTimestatsParser.parse(
            """{"layers":[{"layerName":"com.kurogame.wutheringwaves.global","totalFrames":50,"present2present":{"16666666":50}}]}""",
            10L
        )
        assertEquals(60.0, result.averageFps!!, 0.1)
        assertNull(result.onePercentLowFps)
    }
}