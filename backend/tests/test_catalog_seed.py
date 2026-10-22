"""Validate the shipped curated catalogue without PostgreSQL or external APIs."""
from collections import Counter
from datetime import date
from pathlib import Path
import re
import unicodedata
import unittest
from urllib.parse import urlsplit

from tamaq.catalog.importer import import_file, load, validate
from tamaq.catalog.models import Entry, Selection
from tamaq.catalog.service import selection_snapshot
from tamaq.contracts import AnalysisError


SEED_DIRECTORY = Path(__file__).resolve().parents[1] / "catalog_seed"
SEED_COUNTS = {"openfoodfacts-kz.json": 39}


def normalized(value):
    """Only formatting normalization; never infer that similar SKUs are identical."""
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value or "").casefold()).strip()


class CatalogSeedTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.by_file = {}
        for filename in SEED_COUNTS:
            rows = load(SEED_DIRECTORY / filename)
            entries, errors = validate(rows)
            if errors:
                raise AssertionError(f"Invalid curated seed {filename}: {errors}")
            cls.by_file[filename] = entries
        cls.entries = [entry for entries in cls.by_file.values() for entry in entries]

    def test_public_seeds_pass_the_standard_import_validator(self):
        self.assertEqual(set(SEED_COUNTS), {path.name for path in SEED_DIRECTORY.glob("*.json")})
        for filename, expected in SEED_COUNTS.items():
            with self.subTest(filename=filename):
                report = import_file(SEED_DIRECTORY / filename, database=None, dry_run=True)
                self.assertEqual(expected, report["input_rows"])
                self.assertEqual(expected, report["added"])
                self.assertEqual(0, report["rejected"])
                self.assertEqual([], report["errors"])
                self.assertFalse(report["database_checked"])
                expected_review = {"coffee-boom-astana.json": 2, "sultan-facts.json": 1}.get(filename, 0)
                self.assertEqual(expected_review,
                                 len(report["review"]))

    def test_public_seed_has_only_redistributable_off_records(self):
        self.assertEqual(Counter(product=39), Counter(entry.kind for entry in self.entries))
        self.assertEqual(39, sum(entry.nutrients.complete for entry in self.entries))
        self.assertEqual(39, sum(entry.barcode is not None for entry in self.entries))
        self.assertTrue(all(entry.source.namespace == "openfoodfacts" for entry in self.entries))

    def test_ids_barcodes_source_ids_and_exact_skus_do_not_duplicate(self):
        ids, barcodes, source_ids, skus = set(), set(), set(), set()
        for entry in self.entries:
            with self.subTest(entry=entry.id):
                self.assertNotIn(entry.id, ids)
                ids.add(entry.id)
                if entry.barcode is not None:
                    self.assertNotIn(entry.barcode, barcodes)
                    barcodes.add(entry.barcode)
                source_key = (entry.source.namespace, entry.source.external_id)
                self.assertNotIn(source_key, source_ids)
                source_ids.add(source_key)
                sku = (entry.kind, normalized(entry.brand), normalized(entry.name),
                       normalized(entry.variant), normalized(entry.city),
                       normalized(entry.chain), normalized(entry.branch_scope))
                self.assertNotIn(sku, skus)
                skus.add(sku)

    def test_each_entry_retains_dated_provenance_and_kazakhstan_availability(self):
        for entry in self.entries:
            with self.subTest(entry=entry.id):
                self.assertIn("KZ", entry.country_of_sale)
                self.assertLessEqual(entry.source.checked_at, date.today())
                self.assertGreaterEqual(entry.source.checked_at, date(2026, 10, 20))
                self.assertTrue(entry.source.external_id.strip())
                self.assertTrue(entry.source.license.strip())
                self.assertTrue(entry.source.namespace.strip())
                self.assertIn(entry.source.status, ("official", "open"))
                self.assertEqual("https", urlsplit(entry.source.url).scheme)
                self.assertTrue(urlsplit(entry.source.url).hostname)
                self.assertEqual([], entry.country_of_manufacture)
                if entry.kind == "product":
                    self.assertTrue(entry.brand and entry.brand.strip())

    def test_off_barcode_identity_basis_and_minimal_source_evidence_agree(self):
        import json
        evidence_path = SEED_DIRECTORY.parents[1] / "docs/catalog/openfoodfacts-evidence-20261020.json"
        evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
        by_code = {product["code"]: product for product in evidence["products"]}
        self.assertEqual(39, len(by_code))
        excluded = {product["code"]: product for product in evidence["excluded_products"]}
        self.assertNotIn("4870003431646", by_code)
        self.assertEqual("excluded_qualification", excluded["4870003431646"]["selection_status"])
        self.assertEqual({"proteins_modifier": ">", "fat_modifier": ">",
                          "carbohydrates_modifier": ">"},
                         excluded["4870003431646"]["source_nutrient_modifiers"])
        for entry in self.by_file["openfoodfacts-kz.json"]:
            with self.subTest(entry=entry.id):
                self.assertEqual("off:" + entry.barcode, entry.id)
                self.assertEqual(entry.barcode, entry.source.external_id)
                self.assertEqual("openfoodfacts", entry.source.namespace)
                self.assertEqual("open", entry.source.status)
                self.assertIn("ODbL", entry.source.license)
                self.assertIn("DbCL", entry.source.license)
                proof = by_code[entry.barcode]
                self.assertIn("en:kazakhstan", proof["source_countries_tags"])
                self.assertEqual([], proof["source_quality_errors"])
                # Bounds such as "> 2.8" are not exact values. The current model
                # must exclude them rather than erase a source's qualification.
                self.assertEqual({}, proof["source_nutrient_modifiers"])
                self.assertEqual("per_" + proof["source_nutrition_data_per"], entry.basis)
                self.assertEqual(entry.source.url, proof["source_url"])
                self.assertEqual(entry.source.checked_at.isoformat(), proof["checked_at"])
                for field, source_field in (("calories", "energy-kcal_100g"),
                                            ("protein", "proteins_100g"),
                                            ("fat", "fat_100g"),
                                            ("carbs", "carbohydrates_100g")):
                    self.assertEqual(getattr(entry.nutrients, field),
                                     proof["source_nutriments"][source_field])

    def test_original_two_off_products_retain_expected_facts(self):
        by_id = {entry.id: entry for entry in self.by_file["openfoodfacts-kz.json"]}
        expected = {
            "off:5449000000439": {
                "name": "Coca Cola Original taste, 1.5 L", "brand": "Coca-Cola",
                "barcode": "5449000000439", "variant": "1.5 L", "portion_ml": 100,
                "nutrients": {"calories": 45, "protein": 0, "fat": 0, "carbs": 11.2},
                "aliases": ["Coca-Cola", "Кока-Кола", "кола 1.5л", "Кока-Кола сусыны"],
            },
            "off:5449000004840": {
                "name": "Fanta Naranja, 2 L", "brand": "COCA-COLA SERVICES SA/NV",
                "barcode": "5449000004840", "variant": "2 L", "portion_ml": 250,
                "nutrients": {"calories": 44, "protein": 0, "fat": 0, "carbs": 10.7},
                "aliases": ["Fanta Orange", "Фанта апельсин", "Фанта апельсин сусыны"],
            },
        }
        for entry_id, facts in expected.items():
            with self.subTest(entry=entry_id):
                entry = by_id[entry_id]
                actual = entry.model_dump(mode="json")
                self.assertEqual(facts, {key: actual[key] for key in facts})
                self.assertEqual("per_100ml", entry.basis)
                self.assertIsNone(entry.portion_grams)
                self.assertEqual(["KZ"], entry.country_of_sale)
                self.assertEqual("https://world.openfoodfacts.org/product/" + entry.barcode,
                                 entry.source.url)
                self.assertEqual(date(2026, 10, 20), entry.source.checked_at)

    def test_unknown_synthetic_product_is_not_zero_nutrition(self):
        # Original test data, not a venue's recipe or published nutrition claim.
        row = self.entries[0].model_dump()
        row.update(id="fixture:unknown", name="Synthetic product", kind="product", brand=None,
                   barcode=None, basis="unknown", portion_grams=None, portion_ml=None,
                   nutrients={"calories": None, "protein": None, "fat": None, "carbs": None})
        row['source'].update(url="https://example.com/synthetic-fixture", namespace="synthetic",
                             external_id="unknown", license="MIT synthetic test fixture")
        entry = Entry(**row, version_id="0" * 64, revision=1)
        self.assertFalse(entry.nutrients.complete)
        with self.assertRaises(AnalysisError) as error:
            selection_snapshot(entry, Selection(version_id=entry.version_id, quantity=1, unit="portion"))
        self.assertEqual("catalog_nutrition_missing", error.exception.code)


if __name__ == "__main__":
    unittest.main()
