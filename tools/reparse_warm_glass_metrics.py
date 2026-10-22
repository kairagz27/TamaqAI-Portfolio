"""Reparse completed native QA render evidence; never contacts adb or changes raw samples.

Use only after the measurement process has finished. Original JSON is retained
privately before --apply, and gfxinfo-private.txt is always left untouched.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import statistics

from measure_warm_glass import ROOT, PACKAGE, frame_values


def reparsed(directory: Path) -> tuple[dict, list[tuple[Path, dict]]]:
    directory = directory.resolve()
    if not directory.is_relative_to(ROOT / ".android"):
        raise ValueError("Only private workspace measurement directories are accepted")
    summary = json.loads((directory / "summary.json").read_text(encoding="utf-8"))
    if summary.get("package") != PACKAGE:
        raise ValueError("The completed summary must target only the isolated performance package")
    reports = []
    for path in sorted(directory.glob("run-*/measurement.json")):
        report = json.loads(path.read_text(encoding="utf-8"))
        if (report.get("package") != PACKAGE or report.get("apk_sha256") != summary.get("apk_sha256")
                or report.get("native_actions_completed") != {"scroll_actions": 12, "tab_selections": 8, "drawer_cycles": 2}):
            raise ValueError("Only completed native probes with matching APK and exact actions may be reparsed")
        raw = path.parent / "gfxinfo-private.txt"
        if not 0 < raw.stat().st_size <= 4 * 1024 * 1024:
            raise ValueError("Missing or unexpectedly large raw gfxinfo sample")
        values = frame_values(raw.read_text(encoding="utf-8", errors="replace"))
        if not values["total_frames"]:
            raise ValueError("A zero-frame sample cannot establish a performance result")
        report["metrics"] = values
        report["metrics_reparsed_from_saved_gfxinfo"] = True
        report["jank_counter_interpretation"] = "Modern and legacy gfxinfo counters are distinct; neither is omitted or substituted for the other"
        reports.append((path, report))
    if not reports or len(reports) != summary.get("runs"):
        raise ValueError("All expected completed runs must exist before reparsing")
    metrics = [report["metrics"] for _, report in reports]
    summary["individual_metrics"] = metrics
    summary["median_metrics"] = {key: statistics.median([row[key] for row in metrics if row[key] is not None])
        for key in metrics[0] if any(row[key] is not None for row in metrics)}
    summary["metrics_reparsed_from_saved_gfxinfo"] = True
    summary["jank_counter_interpretation"] = reports[0][1]["jank_counter_interpretation"]
    return summary, reports


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", action="append", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    # Validate every directory completely before replacing any JSON.
    prepared = []
    for value in args.directory:
        directory = (ROOT / value).resolve()
        summary, reports = reparsed(directory)
        prepared.append((directory, summary, reports))
    for directory, summary, reports in prepared:
        if args.apply:
            for path, value in [*reports, (directory / "summary.json", summary)]:
                backup = path.with_name(path.stem + ".before-legacy-parser.json")
                if not backup.exists():
                    backup.write_bytes(path.read_bytes())
                path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"label": summary.get("label"), "runs": summary["runs"], "applied": args.apply,
            "modern_janky_percent_median": summary["median_metrics"].get("janky_percent"),
            "legacy_janky_percent_median": summary["median_metrics"].get("janky_percent_legacy"),
            "missed_vsync_median": summary["median_metrics"].get("missed_vsync"),
            "p95_ms_median": summary["median_metrics"].get("p95_ms")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
