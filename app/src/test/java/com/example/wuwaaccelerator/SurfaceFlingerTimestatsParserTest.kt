package com.example.wuwaaccelerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceFlingerTimestatsParserTest {
    @Test
    fun parsesGameLayerAndPresentationHistogram() {
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
        assertEquals(33.333333, result.p95IntervalMs!!, 0.001)
        assertEquals(50.0, result.p99IntervalMs!!, 0.001)
        assertEquals(20.0, result.over16Point7MsPercent!!, 0.001)
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
}
