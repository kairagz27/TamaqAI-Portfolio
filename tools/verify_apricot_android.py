"""Run opt-in Apricot checks only in an installed emulator QA package.

No APK installation, main-app access, backend mutation or AI request is made by
this runner. Tests use their isolated QA fixtures. Logs stay under .android;
font and animation settings are restored even after failures and timeouts.
Video is recorded separately with apricot_record.py after visual acceptance.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass, field
import json
import os
from pathlib import Path
import re
import subprocess
import time


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.tamaqai.visual"
TEST_PACKAGE = PACKAGE + ".test"
RUNNER = TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"
ANIMATION_KEYS = ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
MAX_LOG_CHARS = 1_000_000


@dataclass(frozen=True)
class Group:
    name: str
    test_class: str
    flags: dict[str, str] = field(default_factory=dict)
    font_scale: str = "1.0"
    animation_scale: str = "1.0"
    prepare_camera: bool = False
    force_stop_before: bool = False
    timeout_seconds: int = 240


def groups() -> list[Group]:
    checks = [
        Group("connection", "ApricotConnectionTest", {"apricotConnectionProbe": "true"}, timeout_seconds=45),
        Group("home", "ApricotHomeTest", {"apricotHomeProbe": "true"}, timeout_seconds=300),
        Group("drawer", "ApricotDrawerTest", {"apricotDrawerProbe": "true"}),
        Group("glass", "ApricotGlassTest", {"apricotGlassProbe": "true"}),
        Group("navigation", "ApricotNavigationTest", {"apricotNavigationProbe": "true"}),
        Group("app-screens", "ApricotAppScreensTest", {"apricotScreensProbe": "true"}),
        Group("matrix", "MatteGalleryTest", {"matteGalleryProbe": "true"}),
        Group("root-early-frames", "RootTransitionTest", {"rootTransitionProbe": "true"}, timeout_seconds=60),
        Group("empty", "MatteEmptyStatesTest", {"matteEmptyProbe": "true"}, font_scale="1.3"),
    ]
    for theme in ("light", "dark"):
        checks += [
            Group(f"editor-{theme}", "DarkDesignUiTest", {"darkDesignProbe": "true", "matteTheme": theme}, font_scale="1.3"),
            Group(f"states-{theme}", "WellnessStatesTest", {"wellnessStatesProbe": "true", "matteTheme": theme}, font_scale="1.3"),
        ]
    checks.append(Group("keyboard-forms", "InterfaceStatesTest", {"interfaceStatesProbe": "true"}, font_scale="1.3"))
    for theme in ("light", "dark"):
        for action, method in (
            ("grant", "grantingCameraUpdatesBarsAndReopeningKeepsCorrectBars"),
            ("deny", "denyingCameraAndReopeningKeepsLightBarsAndDiary"),
        ):
            checks.append(Group(f"camera-{theme}-{action}", f"CameraPermissionGrantTest#{method}",
                                {"cameraPermissionGrantProbe": "true", "cameraTheme": theme},
                                prepare_camera=True, force_stop_before=True))
    checks += [
        Group("restart-prepare", "PresentationRestartTest", {"presentationRestart": "prepare"}),
        Group("restart-verify", "PresentationRestartTest", {"presentationRestart": "verify"}, force_stop_before=True),
        Group("zero-animations-navigation", "ApricotNavigationTest", {"apricotNavigationProbe": "true"}, animation_scale="0"),
    ]
    return checks


def parse_junit(log: str, return_code: int | None) -> dict[str, object]:
    """Count delivered JUnit results; an assumption/ignored test is not a pass."""
    codes = [int(value) for value in re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$", log, re.MULTILINE)]
    completed = [value for value in codes if value in (0, -1, -2, -3, -4)]
    succeeded = completed.count(0)
    failed = completed.count(-1) + completed.count(-2)
    skipped = completed.count(-3) + completed.count(-4)
    ok = re.search(r"\bOK \((\d+) tests?\)", log)
    failure_summary = re.search(r"Tests run:\s*(\d+)", log)
    reported = int(ok.group(1)) if ok else int(failure_summary.group(1)) if failure_summary else None
    passed = (
        return_code == 0 and ok is not None and reported is not None and reported > 0
        and succeeded == reported and len(completed) == reported and failed == 0 and skipped == 0
        and not re.search(r"FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=", log)
    )
    return {
        "passed": passed,
        "junit_tests_reported": reported,
        "tests_started": codes.count(1),
        "tests_completed": len(completed),
        "tests_succeeded": succeeded,
        "tests_failed_or_error": failed,
        "tests_skipped": skipped,
    }


def as_text(value: bytes | str | None) -> str:
    return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value or ""


class EmulatorSuite:
    def __init__(self, serial: str, output: Path, selected: list[Group]) -> None:
        if not re.fullmatch(r"emulator-\d+", serial):
            raise ValueError("This runner accepts an explicitly selected emulator only")
        self.serial = serial
        self.output = output
        self.selected = selected
        self.adb = str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe")
        self.previous: dict[tuple[str, str], str] = {}
        self.results: list[dict[str, object]] = []
        self.restore_errors: list[str] = []
        self.restore_attempted = False
        self.active_font: str | None = None
        self.active_animation: str | None = None

    def shell(self, *arguments: str) -> str:
        return subprocess.check_output(
            [self.adb, "-s", self.serial, "shell", *arguments], text=True,
            encoding="utf-8", errors="replace", timeout=20,
        ).strip()

    def guard(self) -> None:
        if self.shell("getprop", "ro.kernel.qemu") != "1":
            raise ValueError("The selected device does not identify itself as an Android emulator")
        for package in (PACKAGE, TEST_PACKAGE):
            if not self.shell("pm", "path", package).startswith("package:"):
                raise ValueError("Install both matching isolated QA APKs before running this suite")
        instrumentation = self.shell("pm", "list", "instrumentation")
        if not re.search(re.escape(RUNNER) + r"\s+\(target=" + re.escape(PACKAGE) + r"\)", instrumentation):
            raise ValueError("The runner must target the isolated QA package")

    def save_settings(self) -> None:
        for namespace, key in [("system", "font_scale"), *(("global", key) for key in ANIMATION_KEYS)]:
            self.previous[(namespace, key)] = self.shell("settings", "get", namespace, key)

    def profile(self, group: Group) -> None:
        if self.active_font != group.font_scale:
            self.shell("settings", "put", "system", "font_scale", group.font_scale)
            self.active_font = group.font_scale
        if self.active_animation != group.animation_scale:
            for key in ANIMATION_KEYS:
                self.shell("settings", "put", "global", key, group.animation_scale)
            self.active_animation = group.animation_scale

    def record(self) -> None:
        (self.output / "checks.json").write_text(json.dumps(self.results, indent=2) + "\n", encoding="utf-8")
        summary = {
            "package": PACKAGE,
            "selected_groups": [group.name for group in self.selected],
            "groups_completed": len(self.results),
            "groups_passed": sum(row["passed"] is True for row in self.results),
            "junit_tests_completed": sum(int(row["tests_completed"]) for row in self.results),
            "junit_tests_succeeded": sum(int(row["tests_succeeded"]) for row in self.results),
            "junit_tests_failed_or_error": sum(int(row["tests_failed_or_error"]) for row in self.results),
            "junit_tests_skipped": sum(int(row["tests_skipped"]) for row in self.results),
            "settings_restore_attempted": self.restore_attempted,
            "settings_restored": self.restore_attempted and not self.restore_errors,
            "settings_restore_errors": self.restore_errors,
            "all_selected_groups_passed": len(self.results) == len(self.selected)
                and all(row["passed"] is True for row in self.results)
                and self.restore_attempted and not self.restore_errors,
        }
        (self.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    def test(self, group: Group) -> None:
        started = time.monotonic()
        log, return_code, issue = "", None, None
        try:
            self.profile(group)
            if group.force_stop_before:
                self.shell("am", "force-stop", PACKAGE)
            if group.prepare_camera:
                # Only QA permission state is touched; no app data is cleared.
                subprocess.run([self.adb, "-s", self.serial, "shell", "pm", "revoke", PACKAGE,
                                "android.permission.CAMERA"], capture_output=True, timeout=20, check=False)
                self.shell("pm", "clear-permission-flags", PACKAGE, "android.permission.CAMERA", "user-set", "user-fixed")
            qualified = group.test_class if group.test_class.startswith("com.") else "com.tamaqai.ui." + group.test_class
            command = [self.adb, "-s", self.serial, "shell", "am", "instrument", "-r", "-w", "-e", "class", qualified]
            for key, value in group.flags.items():
                command += ["-e", key, value]
            command += [RUNNER]
            run = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=group.timeout_seconds)
            return_code = run.returncode
            log = run.stdout + "\n" + run.stderr
        except subprocess.TimeoutExpired as error:
            issue = "host_timeout"
            log = as_text(error.stdout) + "\n" + as_text(error.stderr) + "\nHost timeout; isolated QA process stopped.\n"
            try:
                self.shell("am", "force-stop", PACKAGE)
            except (subprocess.SubprocessError, OSError):
                log += "QA force-stop could not be confirmed.\n"
        except (subprocess.SubprocessError, OSError) as error:
            issue = type(error).__name__
            log = "Group setup or instrumentation command failed.\n" + as_text(getattr(error, "output", ""))
        row = parse_junit(log, return_code)
        row.update({
            "name": group.name, "class": group.test_class, "flags": group.flags,
            "font_scale": group.font_scale, "animation_scale": group.animation_scale,
            "seconds": round(time.monotonic() - started, 3), "return_code": return_code,
            "host_issue": issue, "host_timeout_seconds": group.timeout_seconds,
            "log_truncated": len(log) > MAX_LOG_CHARS,
        })
        if issue is not None:
            row["passed"] = False
        (self.output / (group.name + ".log")).write_text(
            log[:MAX_LOG_CHARS] + ("\n[Log truncated by host]\n" if row["log_truncated"] else ""), encoding="utf-8",
        )
        self.results.append(row)
        self.record()
        print(f"{group.name}: {'PASS' if row['passed'] else 'FAIL'} "
              f"({row['tests_succeeded']} succeeded, {row['tests_failed_or_error']} failed, {row['tests_skipped']} skipped)", flush=True)

    def run(self) -> bool:
        self.guard()
        try:
            self.save_settings()
            for group in self.selected:
                self.test(group)
        finally:
            # Each restoration is independent: one disconnected call must not
            # prevent attempts to restore the other original settings.
            self.restore_attempted = True
            for (namespace, key), value in self.previous.items():
                try:
                    if value == "null":
                        self.shell("settings", "delete", namespace, key)
                    else:
                        self.shell("settings", "put", namespace, key, value)
                except (subprocess.SubprocessError, OSError):
                    self.restore_errors.append(f"{namespace}/{key}")
            self.record()
        return (len(self.results) == len(self.selected) and all(row["passed"] is True for row in self.results)
                and not self.restore_errors)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Explicit emulator-NNNN serial; physical devices are rejected")
    parser.add_argument("--out", help="Private workspace directory under .android")
    parser.add_argument("--skip-matrix", action="store_true", help="Omit matrix; never report the omitted group as passed")
    parser.add_argument("--groups", help="Comma-separated exact group names for bounded retries; original suite order is retained")
    parser.add_argument("--list-groups", action="store_true", help="List available group names without contacting adb")
    args = parser.parse_args()
    available = groups()
    if args.list_groups:
        print("\n".join(group.name for group in available))
        return
    if not args.serial or not args.out:
        parser.error("--serial and --out are required")
    selected = available
    if args.groups is not None:
        names = {value.strip() for value in args.groups.split(",") if value.strip()}
        unknown = names - {group.name for group in available}
        if unknown:
            parser.error("Unknown groups: " + ", ".join(sorted(unknown)) + "; use --list-groups")
        selected = [group for group in available if group.name in names]
    if args.skip_matrix:
        selected = [group for group in selected if group.name != "matrix"]
    if not selected:
        parser.error("No groups selected")
    output = (ROOT / args.out).resolve()
    if not output.is_relative_to(ROOT / ".android"):
        parser.error("Logs must stay under the private workspace .android directory")
    output.mkdir(parents=True, exist_ok=True)
    suite = EmulatorSuite(args.serial, output, selected)
    raise SystemExit(0 if suite.run() else 1)


if __name__ == "__main__":
    main()
