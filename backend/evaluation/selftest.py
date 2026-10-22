"""Harness checks on synthetic fixtures; these are NOT food accuracy results."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from .run import Case, Dataset, metrics, main, summarize
from tamaq.pilot import main as smoke_main


def case(reliable=True):
    return Case(id="anonymous01", photo="photo.jpg", category="simple", food_expected=True,
        known_name="Test fixture", accepted_names=[], ingredients=[], recipe=None,
        weighed_mass_grams=100, reference={"reliable": reliable, "source": "synthetic test only" if reliable else None,
        "nutrients": {"calories": 200, "protein": 10, "fat": 5, "carbs": 20} if reliable else None, "notes": None})


class HarnessTest(unittest.TestCase):
    def test_independent_portion_and_nutrition_errors(self):
        outcome = {"response": {"result": {"status": "food", "name": "TEST FIXTURE", "components": [
            {"calories": 150, "protein": 8, "fat": 5, "carbs": 20, "mass_grams": 80}]}}}
        value = metrics(case(), outcome)
        self.assertTrue(value["recognition_alias_match"])
        self.assertEqual(50, value["calories_absolute_error"])
        self.assertEqual(2, value["protein_absolute_error"])
        self.assertEqual(20, value["portion_absolute_error_grams"])
        self.assertNotIn("calories_absolute_error", metrics(case(False), outcome))

    def test_unreliable_reference_not_quantitative_and_near_zero_relative_omitted(self):
        reference = case().model_copy(deep=True)
        reference.reference.nutrients.protein = 0
        outcome = {"response": {"result": {"status": "food", "name": "Fixture", "components": []}}}
        self.assertIsNone(metrics(reference, outcome)["protein_relative_error"])
        with self.assertRaises(ValueError):
            Case.model_validate(case(False).model_dump() | {"reference": {"reliable": True, "source": None, "nutrients": None, "notes": None}})

    def test_errors_and_unknown_cost_kept_separate(self):
        outcome = {"response": {"error": {"code": "refusal"}},
                   "receipt": {"cost_kind": "reserved_estimate", "cost_micro_usd": 300000}}
        summary = summarize([{"outcome": outcome, "metrics": metrics(case(), outcome), "latency_seconds": 1}])
        self.assertEqual(1, summary["refusals"])
        self.assertEqual(300000, summary["cost_estimate_micro_usd"])
        self.assertEqual(0, summary["cost_usage_priced_micro_usd"])
        self.assertIsNone(summary["calories_absolute_error"]["mean"])

    def test_default_evaluation_and_smoke_make_no_network_requests(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            Image.new("RGB", (32, 32)).save(root / "photo.jpg")
            (root / "dataset.json").write_text(Dataset(schema_version="1", cases=[case(False)]).model_dump_json())
            with patch("evaluation.run.PilotClient", side_effect=AssertionError("network")), patch("tamaq.pilot.PilotClient", side_effect=AssertionError("network")):
                self.assertEqual(0, main(["--dataset", str(root / "dataset.json"), "--output", str(root / "run.json")]))
                self.assertEqual(0, smoke_main(["smoke", "--image", str(root / "photo.jpg"), "--url", "https://pilot.example", "--run", str(root / "smoke.json")]))
                self.assertFalse((root / "run.json").exists())

    def test_resume_does_not_retry_unknown_case(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            Image.new("RGB", (32, 32)).save(root / "photo.jpg")
            (root / "dataset.json").write_text(Dataset(schema_version="1", cases=[case(False)]).model_dump_json())
            client = unittest.mock.Mock()
            client.config = {"model": "fake"}
            client.analyze.return_value = {"http_status": None, "response": {"error": {"code": "client_outcome_unknown"}},
                "receipt": {"cost_micro_usd": 300000, "cost_kind": "reserved_estimate"}}
            args = ["--dataset", str(root / "dataset.json"), "--output", str(root / "run.json"), "--url", "http://127.0.0.1:8000",
                    "--local-fake", "--max-cases", "1", "--max-cost-usd", "0.30"]
            with patch("evaluation.run.PilotClient", return_value=client):
                self.assertEqual(0, main(args)); self.assertEqual(0, main(args))
            self.assertEqual(1, client.analyze.call_count)


if __name__ == "__main__":
    unittest.main()
