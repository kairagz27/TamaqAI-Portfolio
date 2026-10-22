"""Bounded gfxinfo comparison on the isolated release-optimized QA package.

No adb input, screenshot, account record, provider endpoint, or main-package data
is accessed. Native self-targeted instrumentation drives accessibility actions.
This measures rendered frames;
it is not a touch latency, fling, Macrobenchmark, or TalkBack measurement.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import statistics
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.tamaqai.performance"
RUNNER = PACKAGE + "/com.tamaqai.benchmark.WarmGlassFrameInstrumentation"


def frame_values(body: str) -> dict[str, int | float | None]:
    values: dict[str, int | float | None] = {}
    for label, pattern in {
        "total_frames": r"Total frames rendered:\s*(\d+)",
        "janky_frames": r"Janky frames:\s*(\d+)",
        "janky_frames_legacy": r"Janky frames \(legacy\):\s*(\d+)",
        "missed_vsync": r"Number Missed Vsync:\s*(\d+)",
        "frame_deadline_missed": r"Number Frame deadline missed:\s*(\d+)",
        "frame_deadline_missed_legacy": r"Number Frame deadline missed \(legacy\):\s*(\d+)",
        "slow_ui_thread": r"Number Slow UI thread:\s*(\d+)",
        "high_input_latency": r"Number High input latency:\s*(\d+)",
        "p50_ms": r"50th percentile:\s*(\d+)ms",
        "p90_ms": r"90th percentile:\s*(\d+)ms",
        "p95_ms": r"95th percentile:\s*(\d+)ms",
        "p99_ms": r"99th percentile:\s*(\d+)ms",
    }.items():
        match = re.search(pattern, body)
        values[label] = int(match.group(1)) if match else None
    total = values["total_frames"]
    janky = values["janky_frames"]
    values["janky_percent"] = round(100 * janky / total, 2) if total and janky is not None else None
    legacy = values["janky_frames_legacy"]
    values["janky_percent_legacy"] = round(100 * legacy / total, 2) if total and legacy is not None else None
    return values


def native_outcome(body: str) -> dict[str, object]:
    counts = {}
    for label, key in (("scroll_actions", "warm_glass_scroll_actions"), ("tab_selections", "warm_glass_tab_selections"),
                       ("drawer_cycles", "warm_glass_drawer_cycles")):
        match = re.search(r"INSTRUMENTATION_RESULT:\s*" + key + r"=(\d+)", body)
        counts[label] = int(match.group(1)) if match else None
    return {"passed": bool(re.search(r"INSTRUMENTATION_RESULT:\s*warm_glass_probe_passed=true", body))
            and counts == {"scroll_actions": 12, "tab_selections": 8, "drawer_cycles": 2}, "actions": counts}


def measure(args: argparse.Namespace, out: Path) -> dict[str, object]:
    out.mkdir(parents=True, exist_ok=True)
    adb = str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe")

    def command(*values: str, timeout: int = 20) -> str:
        return subprocess.check_output([adb, "-s", args.serial, *values], text=True,
                                       encoding="utf-8", errors="replace", timeout=timeout,
                                       stderr=subprocess.PIPE)

    def shell(*values: str) -> str:
        return command("shell", *values)

    def conditions() -> dict[str, object]:
        battery = shell("dumpsys", "battery")
        thermal = shell("dumpsys", "thermalservice")
        def integer(body: str, key: str) -> int | None:
            match = re.search(r"^\s*" + re.escape(key) + r":\s*(-?\d+)\s*$", body, re.MULTILINE)
            return int(match.group(1)) if match else None
        return {
            "size": shell("wm", "size").strip().splitlines(),
            "density": shell("wm", "density").strip().splitlines(),
            "font_scale": shell("settings", "get", "system", "font_scale").strip(),
            "animation_scales": {key: shell("settings", "get", "global", key).strip() for key in (
                "window_animation_scale", "transition_animation_scale", "animator_duration_scale")},
            "peak_refresh_rate_setting": shell("settings", "get", "system", "peak_refresh_rate").strip(),
            "min_refresh_rate_setting": shell("settings", "get", "system", "min_refresh_rate").strip(),
            "battery_level_percent": integer(battery, "level"),
            "battery_temperature_tenths_celsius": integer(battery, "temperature"),
            "thermal_status": integer(thermal, "Thermal Status"),
        }

    if not shell("pm", "path", PACKAGE).strip().startswith("package:"):
        raise ValueError("Install only the separate com.tamaqai.performance APK first")
    package_dump = shell("dumpsys", "package", PACKAGE)
    flags = re.findall(r"(?:pkgFlags|flags)=\[([^\]]+)\]", package_dump)
    if not flags or any("DEBUGGABLE" in flag.split() for flag in flags):
        raise ValueError("The measurement target must be a non-debuggable optimized build")
    is_emulator = shell("getprop", "ro.kernel.qemu").strip() == "1"
    model = shell("getprop", "ro.product.model").strip()
    manufacturer = shell("getprop", "ro.product.manufacturer").strip()
    # Physical identity only, no account/profile/window title is recorded.
    device = {"model": model, "manufacturer": manufacturer, "emulator": is_emulator,
              "android": shell("getprop", "ro.build.version.release").strip()}
    conditions_before = conditions()
    rows: queue.Queue[str | None] = queue.Queue()
    process = subprocess.Popen([adb, "-s", args.serial, "shell", "am", "instrument", "-r", "-w", RUNNER], stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")

    def reader() -> None:
        assert process.stdout is not None
        for line in process.stdout:
            rows.put(line)
        rows.put(None)

    threading.Thread(target=reader, daemon=True).start()
    log: list[str] = []
    phases: list[str] = []
    graphics: str | None = None
    reset = False
    reset_ms: float | None = None
    collection_ms: float | None = None
    deadline = time.monotonic() + 160
    try:
        while time.monotonic() < deadline:
            try:
                line = rows.get(timeout=1)
            except queue.Empty:
                continue
            if line is None:
                break
            log.append(line)
            match = re.search(r"warm_glass_phase=(\S+)", line)
            if match:
                phase = match.group(1)
                phases.append(phase)
                if phase == "awaiting-activity":
                    shell("am", "start", "-n", PACKAGE + "/com.tamaqai.MainActivity")
                elif phase == "measure-ready":
                    sync_start = time.monotonic()
                    shell("dumpsys", "gfxinfo", PACKAGE, "reset")
                    reset_ms = round((time.monotonic() - sync_start) * 1000, 1)
                    if reset_ms >= 1200:
                        raise RuntimeError("Frame reset was too slow for the bounded 1800ms readiness interval; discard run")
                    reset = True
                elif phase == "measure-done" and reset:
                    sync_start = time.monotonic()
                    graphics = shell("dumpsys", "gfxinfo", PACKAGE, "framestats")
                    collection_ms = round((time.monotonic() - sync_start) * 1000, 1)
                    if collection_ms >= 2500:
                        raise RuntimeError("Frame collection overlapped the end of the bounded native probe; discard run")
        else:
            raise TimeoutError("Bounded performance instrumentation did not finish in 160 seconds")
        process.wait(timeout=10)
    finally:
        if process.poll() is None:
            process.terminate()
            shell("am", "force-stop", PACKAGE)
        (out / "instrumentation.log").write_text("".join(log), encoding="utf-8")
    body = "".join(log)
    native = native_outcome(body)
    if (process.returncode != 0 or native["passed"] is not True
            or graphics is None or "finished" not in phases):
        raise RuntimeError("Optimized QA probe did not pass; inspect private instrumentation.log. No valid result.")
    values = frame_values(graphics)
    if not values["total_frames"]:
        raise RuntimeError("No rendered frame samples were reported; no performance claim is possible")
    (out / "gfxinfo-private.txt").write_text(graphics, encoding="utf-8")
    report = {"label": args.label, "package": PACKAGE, "build": "release-optimized; non-debuggable",
        "apk_sha256": args.apk_sha256, "device": device, "phases": phases,
        "conditions_before": conditions_before, "conditions_after": conditions(),
        "gfxinfo_reset_ms": reset_ms, "gfxinfo_collection_ms": collection_ms,
        "warmup_routines": 2, "measured_routines": 2,
        "workload": "12 synthetic meals; 12 accepted native accessibility viewport scroll actions, 8 tab selections, 2 drawer open/close cycles",
        "driver": "native framework self-targeted instrumentation; no AndroidX Test/Compose Test or injected gestures",
        "native_actions_completed": native["actions"],
        "fixture_provider_calls": 0, "account": "isolated guest", "actual_talkback_verified": False,
        "limitation": "gfxinfo render probe, not physical touch/fling or input latency benchmark",
        "metrics": values}
    report["jank_counter_interpretation"] = "Modern and legacy gfxinfo counters are distinct; neither is omitted or substituted for the other"
    (out / "measurement.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--label", choices=("before", "after"), required=True)
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--runs", type=int, choices=(1, 3), default=3)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-f0-9]{64}", args.apk_sha256):
        raise ValueError("Record the exact installed optimized APK SHA256")
    out = (ROOT / args.out).resolve()
    if not out.is_relative_to(ROOT / ".android"):
        raise ValueError("Raw measurements must remain in workspace .android")
    out.mkdir(parents=True, exist_ok=True)
    # Hash the actual installed target, rather than trusting a supplied label.
    adb = str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe")
    paths = subprocess.check_output([adb, "-s", args.serial, "shell", "pm", "path", PACKAGE],
        text=True, timeout=20, stderr=subprocess.PIPE).strip().splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:/data/app/") or not paths[0].endswith(".apk"):
        raise ValueError("A single explicit installed performance APK is required")
    installed = out / "installed-apk-private.apk"
    try:
        subprocess.run([adb, "-s", args.serial, "pull", paths[0].removeprefix("package:"), str(installed)],
                       check=True, capture_output=True, timeout=30)
        if hashlib.sha256(installed.read_bytes()).hexdigest() != args.apk_sha256:
            raise ValueError("Installed performance APK SHA256 differs from --apk-sha256")
    finally:
        installed.unlink(missing_ok=True)
    results = []
    for index in range(args.runs):
        results.append(measure(args, out / f"run-{index + 1}"))
        print(f"Completed bounded optimized QA run {index + 1}/{args.runs}", flush=True)
    metrics = [report["metrics"] for report in results]
    median = {key: statistics.median([row[key] for row in metrics if row[key] is not None])
              for key in metrics[0] if any(row[key] is not None for row in metrics)}
    summary = {"label": args.label, "package": PACKAGE, "runs": args.runs,
               "device": results[0]["device"], "apk_sha256": args.apk_sha256,
               "installed_apk_sha256_verified": True,
               "median_metrics": median, "individual_metrics": metrics,
               "individual_conditions": [{"before": result["conditions_before"], "after": result["conditions_after"]}
                                         for result in results],
               "actual_talkback_verified": False, "paid_ai_calls": 0,
               "limitation": results[0]["limitation"]}
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
