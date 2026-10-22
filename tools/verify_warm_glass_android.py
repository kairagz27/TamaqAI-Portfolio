"""Sequential warm-glass checks in the installed emulator QA package only.

No installation, user database clearing, main-app access, provider or AI request.
The target tests replace only QA Activity content; TalkBack uses real navigation.
Font, animation and accessibility settings are restored after every outcome.
Physical devices are rejected before any settings or test operation.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import time

from verify_apricot_android import EmulatorSuite, Group, PACKAGE, ROOT

DEFAULT_TALKBACK = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
SECURE_ACCESSIBILITY_KEYS = ("enabled_accessibility_services", "accessibility_enabled", "touch_exploration_enabled")


def groups(include_zero_animations: bool = False) -> list[Group]:
    result = [Group("materials", "WarmGlassMaterialTest", {"warmGlassMaterialProbe": "true"},
                    timeout_seconds=600)]
    for language in ("ru", "kk", "en"):
        for theme in ("light", "dark"):
            result.append(Group(f"forms-{language}-{theme}", "WarmGlassAccessibilityTest",
                {"warmGlassAccessibilityProbe": "true", "warmGlassLanguage": language, "warmGlassTheme": theme},
                font_scale="1.3", timeout_seconds=150))
    if include_zero_animations:
        for theme in ("light", "dark"):
            result.append(Group(f"zero-animations-{theme}", "WarmGlassAccessibilityTest",
                {"warmGlassAccessibilityProbe": "true", "warmGlassLanguage": "ru", "warmGlassTheme": theme},
                font_scale="1.3", animation_scale="0", timeout_seconds=150))
    result.append(Group("talkback-service", "AccessibilityProbeTest", {"accessibilityProbe": "true"},
                        timeout_seconds=150))
    return result


def expected_tests(selected: list[Group]) -> int:
    return sum(4 if group.name == "materials" else 1 for group in selected)


def merged_services(original: str, component: str) -> str:
    values = [] if original in ("", "null") else original.split(":")
    return ":".join(dict.fromkeys([*values, component]))


def valid_service(component: str) -> bool:
    return re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_.]*/[a-zA-Z_.][a-zA-Z0-9_.]*", component) is not None


class WarmGlassSuite(EmulatorSuite):
    def __init__(self, serial: str, output: Path, selected: list[Group], talkback_service: str) -> None:
        super().__init__(serial, output, selected)
        if not valid_service(talkback_service):
            raise ValueError("TalkBack service must be an explicit Android package/component")
        self.talkback_service = talkback_service

    def save_settings(self) -> None:
        super().save_settings()
        for key in SECURE_ACCESSIBILITY_KEYS:
            self.previous[("secure", key)] = self.shell("settings", "get", "secure", key)

    def profile(self, group: Group) -> None:
        super().profile(group)
        if group.name != "talkback-service":
            return
        package, service = self.talkback_service.split("/", 1)
        # Never install a service or select an arbitrary third-party app. The
        # test itself checks the truly enabled service through Android's manager.
        if not self.shell("pm", "path", package).startswith("package:"):
            raise RuntimeError("TalkBack is not installed; this group remains unverified")
        expanded = package + service if service.startswith(".") else service
        dump = self.shell("dumpsys", "package", package)
        compact = package + "/." + expanded.removeprefix(package + ".")
        if expanded not in dump and self.talkback_service not in dump and compact not in dump:
            raise RuntimeError("The specified installed TalkBack service was not found")
        original = self.previous[("secure", "enabled_accessibility_services")]
        self.shell("settings", "put", "secure", "enabled_accessibility_services",
                   merged_services(original, self.talkback_service))
        self.shell("settings", "put", "secure", "accessibility_enabled", "1")
        self.shell("settings", "put", "secure", "touch_exploration_enabled", "1")

    def record(self) -> None:
        for row in self.results:
            expected = 4 if row["name"] == "materials" else 1
            row["junit_tests_expected"] = expected
            if row["passed"] and row["tests_succeeded"] != expected:
                row["passed"] = False
                row["host_issue"] = "expected_junit_count_mismatch"
        super().record()
        path = self.output / "summary.json"
        summary = json.loads(path.read_text(encoding="utf-8"))
        talkback = next((row for row in self.results if row["name"] == "talkback-service"), None)
        summary.update({
            "scope": "emulator QA; no main package or user diary operations",
            "material_tests_expected": 4 if any(group.name == "materials" for group in self.selected) else 0,
            "localized_forms_expected": sum(group.name.startswith("forms-") for group in self.selected),
            "junit_tests_expected": expected_tests(self.selected),
            "actual_talkback_service_probe_passed": bool(talkback and talkback["passed"]),
            "talkback_audio_and_manual_swipe_reviewed": False,
            "accessibility_settings_in_restore_set": all(("secure", key) in self.previous
                                                          for key in SECURE_ACCESSIBILITY_KEYS),
            "paid_ai_calls_by_runner": 0,
        })
        path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    def run(self) -> bool:
        try:
            result = super().run()
        finally:
            # A successful settings put is not sufficient evidence that the
            # accessibility service left the device in its original state.
            if self.restore_attempted:
                time.sleep(.5)
                for (namespace, key), expected in self.previous.items():
                    try:
                        if self.shell("settings", "get", namespace, key) != expected:
                            self.restore_errors.append(f"{namespace}/{key}: readback mismatch")
                    except (OSError, subprocess.SubprocessError):
                        self.restore_errors.append(f"{namespace}/{key}: readback unavailable")
                self.record()
        return result and not self.restore_errors


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--out")
    parser.add_argument("--talkback-service", default=DEFAULT_TALKBACK)
    parser.add_argument("--include-zero-animations", action="store_true")
    parser.add_argument("--groups", help="Comma-separated exact group names; omitted checks are not passes")
    parser.add_argument("--list-groups", action="store_true")
    args = parser.parse_args()
    selected = groups(args.include_zero_animations)
    if args.list_groups:
        print("\n".join(group.name for group in selected))
        return
    if not args.serial or not args.out:
        parser.error("--serial and --out are required")
    if args.groups:
        names = {value.strip() for value in args.groups.split(",") if value.strip()}
        unknown = names - {group.name for group in selected}
        if unknown:
            parser.error("Unknown groups: " + ", ".join(sorted(unknown)))
        selected = [group for group in selected if group.name in names]
    if not selected:
        parser.error("No groups selected")
    output = (ROOT / args.out).resolve()
    if not output.is_relative_to(ROOT / ".android"):
        parser.error("Output must remain in the private workspace .android directory")
    output.mkdir(parents=True, exist_ok=True)
    suite = WarmGlassSuite(args.serial, output, selected, args.talkback_service)
    raise SystemExit(0 if suite.run() else 1)


if __name__ == "__main__":
    main()
