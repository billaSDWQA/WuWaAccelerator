#!/usr/bin/env python3
"""Capture Android SurfaceFlinger TimeStats for a game layer; external A/B tool only."""
from __future__ import annotations
import argparse
import json
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_PACKAGE = "com.kurogame.wutheringwaves.global"
HISTOGRAM_RE = re.compile(r"(\d+)ms\s*=\s*(\d+)")
FIELD_RE = re.compile(r"^\s*(totalFrames|droppedFrames|averageFPS)\s*=\s*([\d.]+)\s*$", re.M)


def run_adb(adb: str, args: list[str], timeout: int = 30) -> str:
    result = subprocess.run([adb, *args], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode:
        message = result.stderr.strip() or result.stdout.strip()
        raise RuntimeError(f"ADB command failed ({result.returncode}): {message}")
    return result.stdout


def split_layer_blocks(raw: str) -> list[str]:
    return [block for block in re.split(r"\n\s*\n", raw) if "layerName" in block]


def parse_histogram(block: str) -> dict[int, int]:
    marker = re.search(r"presentToPresent histogram is as below:\s*", block, re.I)
    if not marker:
        marker = re.search(r"present_to_present histogram is as below:\s*", block, re.I)
    if not marker:
        return {}
    tail = re.split(r"\n\s*[A-Za-z][A-Za-z0-9_ ]+\s+(?:histogram|is as below)",
                    block[marker.end():], maxsplit=1)[0]
    return {int(ms): int(count) for ms, count in HISTOGRAM_RE.findall(tail)}


def parse_layer(block: str) -> dict | None:
    fields = dict(FIELD_RE.findall(block))
    if "totalFrames" not in fields:
        return None
    layer = re.search(r"^\s*layerName\s*=\s*(.+?)\s*$", block, re.M)
    package = re.search(r"^\s*packageName\s*=\s*(.+?)\s*$", block, re.M)
    histogram = parse_histogram(block)
    total = int(float(fields["totalFrames"]))
    dropped = int(float(fields["droppedFrames"])) if "droppedFrames" in fields else None
    count = sum(histogram.values())
    percentiles = {}
    if count:
        for percentile in (50, 90, 95, 99):
            rank = max(1, (count * percentile + 99) // 100)
            cumulative = 0
            for ms, amount in sorted(histogram.items()):
                cumulative += amount
                if cumulative >= rank:
                    percentiles[f"p{percentile}_present_to_present_ms"] = ms
                    break
    drop_percent = 100.0 * dropped / total if total and dropped is not None else None
    return {
        "layer_name": layer.group(1) if layer else None,
        "package_name": package.group(1) if package else None,
        "total_frames": total,
        "dropped_frames": dropped,
        "dropped_frame_percent": drop_percent,
        "meets_dropped_frame_target": drop_percent <= 1.0 if drop_percent is not None else None,
        "average_fps_reported": float(fields["averageFPS"]) if "averageFPS" in fields else None,
        "present_to_present_histogram_ms": histogram,
        "histogram_frame_count": count,
        **percentiles,
    }


def select_layers(raw: str, package: str, layer_contains: str | None = None) -> list[dict]:
    needle = (layer_contains or package).lower()
    found = []
    for block in split_layer_blocks(raw):
        layer = parse_layer(block)
        if layer and needle in ((layer["layer_name"] or "") + " " + (layer["package_name"] or "")).lower():
            found.append(layer)
    return found


def summarize(layer: dict, target_fps: float) -> dict:
    budget = 1000.0 / target_fps
    histogram = layer["present_to_present_histogram_ms"]
    count = sum(histogram.values())
    long_gaps = sum(n for ms, n in histogram.items() if ms > budget * 1.5)
    return {
        **layer,
        "target_fps": target_fps,
        "target_frame_budget_ms": round(budget, 3),
        "long_gap_threshold_ms_exclusive": round(budget * 1.5, 3),
        "long_gap_interval_count": long_gaps,
        "long_gap_interval_percent": 100.0 * long_gaps / count if count else None,
        "measurement_scope": "SurfaceFlinger presentation intervals; not UE internal CPU/GPU frame time",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--layer-contains", help="Override package matching for OEM-specific layer names")
    parser.add_argument("--duration-seconds", type=int, default=900)
    parser.add_argument("--target-fps", type=float, default=60.0)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output-dir", type=Path, default=Path("frame-pacing-results"))
    args = parser.parse_args()
    if args.duration_seconds < 1 or args.target_fps <= 0:
        parser.error("duration-seconds and target-fps must be positive")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    raw_path = args.output_dir / f"timestats-{stamp}.txt"
    report_path = args.output_dir / f"frame-pacing-{stamp}.json"
    try:
        devices = run_adb(args.adb, ["devices"])
        if not re.search(r"^\S+\s+device$", devices, re.M):
            raise RuntimeError("No authorized ADB device found; check adb devices.")
        fingerprint = run_adb(args.adb, ["shell", "getprop", "ro.build.fingerprint"]).strip()
        run_adb(args.adb, ["shell", "dumpsys", "SurfaceFlinger", "--timestats", "-clear", "-enable"])
        print(f"TimeStats capture started; play the fixed route for {args.duration_seconds}s.", flush=True)
        try:
            time.sleep(args.duration_seconds)
            raw = run_adb(args.adb, ["shell", "dumpsys", "SurfaceFlinger", "--timestats", "-dump"], 60)
        finally:
            run_adb(args.adb, ["shell", "dumpsys", "SurfaceFlinger", "--timestats", "-disable"])
        raw_path.write_text(raw, encoding="utf-8")
        layers = select_layers(raw, args.package, args.layer_contains)
        if not layers:
            raise RuntimeError(f"No layer matched '{args.layer_contains or args.package}'. Raw dump: {raw_path}")
        report = {
            "captured_at_utc": datetime.now(timezone.utc).isoformat(),
            "device_fingerprint": fingerprint,
            "package": args.package,
            "duration_seconds": args.duration_seconds,
            "target_fps": args.target_fps,
            "layers": [summarize(layer, args.target_fps) for layer in layers],
            "raw_dump": str(raw_path),
            "interpretation": {
                "dropped_frame_percent": "droppedFrames / totalFrames * 100; target <= 1.0%; null means unavailable, never zero",
                "p99_present_to_present": "SurfaceFlinger presentation interval, not UE internal render time",
                "long_gap_percent": "Intervals > 1.5x target frame budget; diagnostic proxy, not droppedFrames",
            },
        }
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        print(f"Raw dump: {raw_path}\nReport: {report_path}")
        return 0
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
