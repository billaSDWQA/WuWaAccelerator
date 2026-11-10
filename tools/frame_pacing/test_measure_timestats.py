import unittest
from measure_timestats import parse_layer, select_layers, summarize

SAMPLE = """
layerName = SurfaceView[com.kurogame.wutheringwaves.global/com.kurogame...](BLAST)#123
packageName = com.kurogame.wutheringwaves.global
totalFrames = 1000
droppedFrames = 7
averageFPS = 59.4
presentToPresent histogram is as below:
0ms=0 1ms=0 15ms=200 16ms=500 17ms=250 25ms=40 33ms=10

layerName = NavigationBar
packageName = com.android.systemui
totalFrames = 100
droppedFrames = 0
presentToPresent histogram is as below:
16ms=100
"""


class TimeStatsParserTests(unittest.TestCase):
    def test_selects_game_layer_and_calculates_drop_rate(self):
        layers = select_layers(SAMPLE, "com.kurogame.wutheringwaves.global")
        self.assertEqual(1, len(layers))
        self.assertEqual(1000, layers[0]["total_frames"])
        self.assertEqual(7, layers[0]["dropped_frames"])
        self.assertAlmostEqual(0.7, layers[0]["dropped_frame_percent"])
        self.assertTrue(layers[0]["meets_dropped_frame_target"])

    def test_calculates_p99_and_long_gap_proxy(self):
        layer = select_layers(SAMPLE, "com.kurogame.wutheringwaves.global")[0]
        result = summarize(layer, 60.0)
        self.assertEqual(25, result["p99_present_to_present_ms"])
        self.assertEqual(10, result["long_gap_interval_count"])
        self.assertEqual(1.0, result["long_gap_interval_percent"])

    def test_returns_no_layer_when_package_is_absent(self):
        self.assertEqual([], select_layers(SAMPLE, "com.example.missing"))

    def test_supports_oem_layer_name_override(self):
        self.assertEqual(1, len(select_layers(SAMPLE, "missing", "SurfaceView[")))

    def test_missing_dropped_frame_field_is_unavailable_not_zero(self):
        layer = parse_layer("layerName = Game\ntotalFrames = 10")
        self.assertIsNone(layer["dropped_frames"])
        self.assertIsNone(layer["dropped_frame_percent"])
        self.assertIsNone(layer["meets_dropped_frame_target"])

    def test_missing_histogram_keeps_drop_rate_but_omits_percentiles(self):
        layer = parse_layer("layerName = Game\ntotalFrames = 10\ndroppedFrames = 1")
        self.assertAlmostEqual(10.0, layer["dropped_frame_percent"])
        self.assertNotIn("p99_present_to_present_ms", layer)


if __name__ == "__main__":
    unittest.main()
