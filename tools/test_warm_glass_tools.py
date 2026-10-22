"""Host-only parser and unchanged-pixel comparison checks; never contacts adb."""
import unittest
import tempfile
from pathlib import Path
import subprocess
import sys
import json
import sqlite3
from contextlib import closing
from unittest.mock import patch

from PIL import Image

from compare_warm_glass import pixel_hash, ROOT, SCREENS, THEMES
from measure_warm_glass import frame_values, native_outcome
from verify_warm_glass_android import groups, expected_tests, merged_services, valid_service, WarmGlassSuite, DEFAULT_TALKBACK
from qa_room_fingerprint import fingerprint
from reparse_warm_glass_metrics import reparsed


class WarmGlassHostToolsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # A clean checkout has no ignored local artefact directory.
        (ROOT / ".android").mkdir(parents=True, exist_ok=True)

    def test_artifact_parent_created_in_clean_checkout(self):
        with tempfile.TemporaryDirectory(prefix="clean-host-tools-") as folder:
            clean = Path(folder)
            with patch.object(sys.modules[__name__], "ROOT", clean):
                self.setUpClass()
            self.assertTrue((clean / ".android").is_dir())

    def test_native_probe_requires_its_true_result_and_all_expected_actions(self):
        body = "\n".join(["INSTRUMENTATION_RESULT: warm_glass_probe_passed=true",
                         "INSTRUMENTATION_RESULT: warm_glass_scroll_actions=12",
                         "INSTRUMENTATION_RESULT: warm_glass_tab_selections=8",
                         "INSTRUMENTATION_RESULT: warm_glass_drawer_cycles=2"])
        self.assertTrue(native_outcome(body)["passed"])
        self.assertFalse(native_outcome(body.replace("scroll_actions=12", "scroll_actions=11"))["passed"])
        self.assertFalse(native_outcome(body.replace("probe_passed=true", "probe_passed=false"))["passed"])
        self.assertFalse(native_outcome("OK (1 test)")["passed"])

    def test_emulator_matrix_has_four_material_tests_six_locale_cases_and_real_service_probe(self):
        matrix = groups()
        self.assertEqual(8, len(matrix))
        forms = [group for group in matrix if group.name.startswith("forms-")]
        self.assertEqual(6, len(forms))
        self.assertEqual({(language, theme) for language in ("ru", "kk", "en") for theme in ("light", "dark")},
                         {(group.flags["warmGlassLanguage"], group.flags["warmGlassTheme"]) for group in forms})
        self.assertEqual("WarmGlassMaterialTest", matrix[0].test_class)
        self.assertEqual("AccessibilityProbeTest", matrix[-1].test_class)
        self.assertEqual(2, sum(group.animation_scale == "0" for group in groups(True)))
        self.assertEqual(11, expected_tests(matrix))
        self.assertEqual(13, expected_tests(groups(True)))

    def test_accessibility_service_merge_preserves_other_enabled_services(self):
        other = "example.accessibility/example.accessibility.Reader"
        self.assertEqual(DEFAULT_TALKBACK, merged_services("null", DEFAULT_TALKBACK))
        self.assertEqual(other + ":" + DEFAULT_TALKBACK, merged_services(other, DEFAULT_TALKBACK))
        self.assertEqual(other + ":" + DEFAULT_TALKBACK,
                         merged_services(other + ":" + DEFAULT_TALKBACK, DEFAULT_TALKBACK))
        self.assertTrue(valid_service(DEFAULT_TALKBACK))
        self.assertFalse(valid_service("package/Service; another-command"))

    def test_emulator_runner_rejects_physical_target_before_any_adb_call(self):
        with self.assertRaisesRegex(ValueError, "emulator"):
            WarmGlassSuite("physical-device", ROOT / ".android/unused-host-test", groups(), DEFAULT_TALKBACK)

    def test_private_room_fingerprint_detects_content_and_schema_without_row_values(self):
        with tempfile.TemporaryDirectory(prefix="host-room-fixture-", dir=ROOT / ".android") as folder:
            self.assertTrue(Path(folder).resolve().is_relative_to(ROOT / ".android"))
            database = Path(folder) / "fixture.db"
            with closing(sqlite3.connect(database)) as connection:
                connection.execute("CREATE TABLE meals(uid TEXT PRIMARY KEY, details BLOB)")
                connection.execute("INSERT INTO meals VALUES(?, ?)", ("synthetic", b"example"))
                connection.commit()
            first = fingerprint(database)
            self.assertEqual(1, first["tables"]["meals"]["rows"])
            self.assertNotIn("synthetic", json.dumps(first))
            with closing(sqlite3.connect(database)) as connection:
                connection.execute("UPDATE meals SET details=?", (b"changed",))
                connection.commit()
            second = fingerprint(database)
            self.assertEqual(first["schema_sha256"], second["schema_sha256"])
            self.assertNotEqual(first["tables"], second["tables"])

    def test_gfxinfo_zero_jank_is_a_value(self):
        values = frame_values("Total frames rendered: 80\nJanky frames: 0\n50th percentile: 8ms\n")
        self.assertEqual(80, values["total_frames"])
        self.assertEqual(0.0, values["janky_percent"])
        self.assertEqual(8, values["p50_ms"])
        self.assertIsNone(values["p99_ms"])
        self.assertIsNone(values["janky_frames_legacy"])
        self.assertIsNone(values["janky_percent_legacy"])

    def test_gfxinfo_reports_modern_and_legacy_jank_separately(self):
        values = frame_values("Total frames rendered: 1346\nJanky frames: 0 (0.00%)\n"
                              "Janky frames (legacy): 42 (3.12%)\nNumber Missed Vsync: 3\n"
                              "Number Frame deadline missed: 0\nNumber Frame deadline missed (legacy): 42\n"
                              "Number Slow UI thread: 2\nNumber High input latency: 1\n")
        self.assertEqual(0.0, values["janky_percent"])
        self.assertEqual(42, values["janky_frames_legacy"])
        self.assertEqual(3.12, values["janky_percent_legacy"])
        self.assertEqual(3, values["missed_vsync"])
        self.assertEqual(0, values["frame_deadline_missed"])
        self.assertEqual(42, values["frame_deadline_missed_legacy"])
        self.assertEqual(2, values["slow_ui_thread"])
        self.assertEqual(1, values["high_input_latency"])

    def test_completed_native_evidence_reparse_keeps_raw_sample_and_original_json_unchanged(self):
        with tempfile.TemporaryDirectory(prefix="host-native-parser-", dir=ROOT / ".android") as folder:
            directory = Path(folder)
            self.assertTrue(directory.resolve().is_relative_to(ROOT / ".android"))
            run = directory / "run-1"; run.mkdir()
            summary = {"package": "com.tamaqai.performance", "runs": 1, "apk_sha256": "a" * 64}
            report = {"package": summary["package"], "apk_sha256": summary["apk_sha256"],
                      "native_actions_completed": {"scroll_actions": 12, "tab_selections": 8, "drawer_cycles": 2},
                      "metrics": {"total_frames": 1346, "janky_frames": 0}}
            raw = "Total frames rendered: 1346\nJanky frames: 0\nJanky frames (legacy): 42 (3.12%)\n95th percentile: 8ms\n"
            (directory / "summary.json").write_text(json.dumps(summary), encoding="utf-8")
            (run / "measurement.json").write_text(json.dumps(report), encoding="utf-8")
            (run / "gfxinfo-private.txt").write_text(raw, encoding="utf-8")
            updated, measurements = reparsed(directory)
            self.assertEqual(3.12, updated["median_metrics"]["janky_percent_legacy"])
            self.assertEqual(42, measurements[0][1]["metrics"]["janky_frames_legacy"])
            self.assertEqual(raw, (run / "gfxinfo-private.txt").read_text(encoding="utf-8"))
            self.assertEqual(report, json.loads((run / "measurement.json").read_text(encoding="utf-8")))

    def test_missing_or_zero_frames_do_not_claim_smoothness(self):
        self.assertIsNone(frame_values("")["janky_percent"])
        self.assertIsNone(frame_values("Total frames rendered: 0\nJanky frames: 0")["janky_percent"])

    def test_gfxinfo_jank_ratio_and_all_percentiles(self):
        values = frame_values("Total frames rendered: 100\nJanky frames: 13 (13%)\n"
                              "50th percentile: 8ms\n90th percentile: 17ms\n"
                              "95th percentile: 25ms\n99th percentile: 40ms\n")
        self.assertEqual(13.0, values["janky_percent"])
        self.assertEqual([8, 17, 25, 40], [values[key] for key in ("p50_ms", "p90_ms", "p95_ms", "p99_ms")])

    def test_hash_checks_pixels_independent_of_container_metadata(self):
        before = Image.new("RGB", (4, 4), "#F7F2EA")
        after = before.copy()
        after.info["private-metadata"] = "not part of the pixels"
        self.assertEqual(pixel_hash(before), pixel_hash(after))
        after.putpixel((0, 0), (0, 0, 0))
        self.assertNotEqual(pixel_hash(before), pixel_hash(after))

    def test_comparison_keeps_source_pixels_and_uses_only_review_directory(self):
        with tempfile.TemporaryDirectory(prefix="host-tool-fixture-", dir=ROOT / ".android") as folder:
            self.assertTrue(Path(folder).resolve().is_relative_to(ROOT / ".android"))
            directory = Path(folder)
            before, after, output = [directory / name for name in ("before", "after", "review")]
            before.mkdir(); after.mkdir()
            for theme in THEMES:
                for screen in SCREENS:
                    for path, color in ((before, "#3F4532"), (after, "#F7F2EA")):
                        Image.new("RGB", (40, 80), color).save(path / f"warm-glass-ru-{theme}-{screen}.png")
            subprocess.run([sys.executable, str(ROOT / "tools/compare_warm_glass.py"),
                            "--before", str(before), "--after", str(after), "--out", str(output)],
                           check=True, capture_output=True)
            proof = json.loads((output / "comparisons.json").read_text(encoding="utf-8"))
            self.assertEqual(10, len(proof["comparisons"]))
            self.assertFalse(proof["published_after_review"])
            with Image.open(output / "ru-light-home-comparison.png") as image:
                self.assertEqual((63, 69, 50), image.getpixel((0, image.height - 1)))
                self.assertEqual((247, 242, 234), image.getpixel((image.width - 1, image.height - 1)))
                self.assertFalse(image.info)


if __name__ == "__main__":
    unittest.main()
