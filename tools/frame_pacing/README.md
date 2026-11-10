# Frame-pacing A/B measurement (external ADB tool)

This tool captures Android SurfaceFlinger TimeStats for Wuthering Waves and writes the raw dump plus a JSON summary. It is a development/acceptance tool only: it adds no in-app HUD, changes no Game Mode or sysfs setting, alters no CPU/GPU setting, and kills no process.

## Requirements
- Windows PowerShell, macOS, or Linux with Python 3.10+.
- Android Platform Tools (adb) installed and on PATH.
- A55 connected with USB debugging authorized.
- Start the game and enter the fixed test route before capture.

## Run

From the repository root:

    python tools/frame_pacing/measure_timestats.py --duration-seconds 900 --target-fps 60

Use --duration-seconds 1800 for a 30-minute run. The default is 15 minutes. The script enables and clears TimeStats, waits for the requested duration, dumps the result, and disables TimeStats in a finally block. Raw output and a JSON report are saved under frame-pacing-results/.

If Samsung's layer name does not include the package name, inspect the preserved raw dump and retry with a distinctive substring:

    python tools/frame_pacing/measure_timestats.py --duration-seconds 900 --layer-contains "SurfaceView["

## Interpretation and acceptance
- Select the Wuthering Waves layer, not System UI, navigation bar, or another app.
- dropped_frame_percent is calculated only when SurfaceFlinger exposes both droppedFrames and totalFrames (presented frames): droppedFrames / (totalFrames + droppedFrames) * 100. The project target is at most 1.0%. Missing fields are reported as null, never fabricated as zero.
- P50/P90/P95/P99 are calculated from the selected layer's presentToPresent histogram. They describe display presentation intervals, not UE4 internal game-thread, render-thread, shader, or GPU execution time.
- long_gap_interval_percent counts histogram intervals greater than 1.5 times the target frame budget (25 ms at 60 FPS). This is a diagnostic long-gap proxy, not the dropped-frame percentage and not proof of engine-side missed frames.
- If the target layer is absent or the OEM output format is unsupported, the script exits with an error and preserves the raw dump. Do not substitute display refresh rate or another layer's FPS.

For a valid A/B comparison, keep game version, graphics settings, route, capture duration, display mode, battery/charging state, background workload, and starting thermal state as consistent as practical. Run baseline and candidate settings separately; do not combine Game Mode downscaling with INI changes. Repeat paired runs and compare dropped-frame percentage plus P95/P99 presentation intervals. A CI pass or a single run is not proof of a real-device performance improvement.

SurfaceFlinger data is an external presentation-side proxy. It cannot identify every UE4 internal bottleneck. See Android's frame-rate measurement guidance at https://developer.android.com/games/optimize/framerate and slow-session guidance at https://developer.android.com/topic/performance/issues/slow-session.
