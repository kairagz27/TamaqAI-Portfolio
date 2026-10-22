"""Catalogue tests use synthetic nutrition and disposable PostgreSQL, never pilot data."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest

from pydantic import ValidationError

from tamaq.catalog.importer import import_file, validate
from tamaq.catalog.models import Calculation, Entry, EntryInput, Match, Selection
from tamaq.catalog.service import Catalog, selection_snapshot
from tamaq.contracts import AnalysisError
import test_accounts as accounts


def fixture(entry_id="fixture:rice", basis="per_100g", **updates):
    raw = dict(id=entry_id, kind="product", name="Тестовый рис", aliases=["күріш", "Rice"],
               country_of_sale=["KZ"], basis=basis,
               nutrients=dict(calories=123.4, protein=2.5, fat=1.2, carbs=25.3),
               source=dict(url="https://example.com/label", checked_at="2026-01-01",
                           external_id=entry_id, license="Synthetic test fixture", status="official", namespace="fixture"))
    raw.update(updates)
    return EntryInput.model_validate(raw)


def version(entry):
    return Entry(**entry.model_dump(), version_id="0" * 64, revision=1)


class CatalogCalculationTest(unittest.TestCase):
    def test_per_100g_uses_original_unrounded_values(self):
        entry = version(fixture())
        first = selection_snapshot(entry, Selection(version_id=entry.version_id, quantity=125, unit="g"))
        self.assertEqual(154.25, first["nutrients"]["calories"])
        self.assertEqual(3.125, first["nutrients"]["protein"])
        self.assertEqual(125, first["mass_grams"])
        self.assertEqual(61.7, selection_snapshot(entry, Selection(version_id=entry.version_id, quantity=50, unit="g"))["nutrients"]["calories"])

    def test_per_portion_and_size(self):
        entry = version(fixture(basis="per_portion", portion_grams=250))
        self.assertEqual(61.7, selection_snapshot(entry, Selection(version_id=entry.version_id, quantity=0.5, unit="portion"))["nutrients"]["calories"])
        self.assertEqual(123.4, selection_snapshot(entry, Selection(version_id=entry.version_id, quantity=250, unit="g"))["nutrients"]["calories"])
        entry = version(fixture(basis="per_100g", portion_grams=300))
        self.assertEqual(370.2, selection_snapshot(entry, Selection(version_id=entry.version_id, quantity=1, unit="portion"))["nutrients"]["calories"])

    def test_100ml_keeps_unknown_mass_and_rejects_grams(self):
        entry = version(fixture(basis="per_100ml", portion_ml=250))
        result = selection_snapshot(entry, Selection(version_id=entry.version_id, quantity=250, unit="ml"))
        self.assertEqual(308.5, result["nutrients"]["calories"])
        self.assertIsNone(result["mass_grams"])
        self.assertEqual(250, result["volume_ml"])
        with self.assertRaises(AnalysisError):
            selection_snapshot(entry, Selection(version_id=entry.version_id, quantity=250, unit="g"))

    def test_published_density_allows_known_mass(self):
        entry = version(fixture(basis="per_100ml", portion_ml=250, portion_grams=270))
        self.assertEqual(108, selection_snapshot(entry, Selection(version_id=entry.version_id, quantity=100, unit="ml"))["mass_grams"])

    def test_unknown_nutrition_is_not_zero(self):
        entry = version(fixture(basis="unknown", nutrients={}))
        self.assertIsNone(entry.nutrients.calories)
        with self.assertRaises(AnalysisError) as error:
            selection_snapshot(entry, Selection(version_id=entry.version_id, quantity=1, unit="portion"))
        self.assertEqual("catalog_nutrition_missing", error.exception.code)
        partial = version(fixture(nutrients={"calories": 100}))
        with self.assertRaises(AnalysisError):
            selection_snapshot(partial, Selection(version_id=partial.version_id, quantity=100, unit="g"))

    def test_suspicious_and_unknown_basis_require_review_without_449_equality(self):
        self.assertEqual(123.4, fixture().nutrients.calories)  # Not an exact 4/9/4 equality.
        for update in ({"nutrients": {"protein": 101}}, {"nutrients": {"protein": 40, "fat": 40, "carbs": 40}},
                       {"basis": "unknown"}, {"barcode": "1234567890123"}):
            with self.assertRaises(ValidationError):
                fixture(**update)
        with self.assertRaises(ValidationError):
            Calculation(version_id="x", quantity=float("nan"), unit="g")
        with self.assertRaises(ValidationError):
            Calculation(version_id="fixture:rice", quantity=100, unit="g")

    def test_json_csv_import_validation_and_no_db_dryrun(self):
        import csv
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "food.json"
            path.write_text(json.dumps({"entries": [fixture().model_dump(mode="json")]}), encoding="utf-8")
            report = import_file(path, None)
            self.assertEqual(1, report["added"])
            self.assertFalse(report["database_checked"])
            csv_path = Path(directory) / "food.csv"
            row = fixture().model_dump(mode="json")
            with csv_path.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=list(row))
                writer.writeheader()
                writer.writerow({key: json.dumps(value, ensure_ascii=False) if isinstance(value, (dict, list)) else value for key, value in row.items()})
            self.assertEqual(1, import_file(csv_path, None)["added"])
        accepted, errors = validate([fixture().model_dump(mode="json"), {"password": "do-not-echo"}])
        self.assertEqual(1, len(accepted))
        self.assertNotIn("do-not-echo", json.dumps(errors))
        private = fixture().model_dump(mode="json")
        private["source"]["status"] = "user"
        accepted, errors = validate([private])
        self.assertEqual([], accepted)
        self.assertEqual("private_owner_required", errors[0]["code"])
        unpublished_country = fixture(country_of_sale=[]).model_dump(mode="json")
        self.assertEqual("kazakhstan_availability_not_evidenced", validate([unpublished_country])[1][0]["code"])


@unittest.skipUnless(os.environ.get("TAMAQ_DATABASE_URL"), "Explicit isolated PostgreSQL configuration required")
class CatalogDatabaseTest(unittest.TestCase):
    setUpClass = classmethod(accounts.AccountsTest.setUpClass.__func__)
    tearDownClass = classmethod(accounts.AccountsTest.tearDownClass.__func__)
    headers = accounts.AccountsTest.headers
    account = accounts.AccountsTest.account
    guest = accounts.AccountsTest.guest

    def setUp(self):
        accounts.AccountsTest.setUp(self)
        with self.database.connection() as db:
            db.execute("TRUNCATE catalog_entries CASCADE")
        self.catalog = Catalog(self.database)

    def seed(self, *entries):
        return self.catalog.import_entries(entries, dry_run=False)

    def test_dry_run_repeat_versions_and_historical_snapshot(self):
        entry = fixture()
        self.assertEqual(1, self.catalog.import_entries([entry])["added"])
        self.assertEqual(0, self.catalog.search()["total"])
        self.assertEqual(1, self.seed(entry)["added"])
        self.assertEqual(1, self.seed(entry)["skipped"])
        old = self.catalog.entry(entry.id)
        snapshot = self.catalog.calculate(Calculation(version_id=old.version_id, quantity=100, unit="g"))
        updated = entry.model_copy(update={"nutrients": entry.nutrients.model_copy(update={"calories": 222})})
        self.assertEqual(1, self.seed(updated)["updated"])
        new = self.catalog.entry(entry.id)
        self.assertNotEqual(old.version_id, new.version_id)
        self.assertEqual(2, new.revision)
        self.assertEqual(123.4, self.catalog.entry(old.version_id).nutrients.calories)
        self.assertEqual(123.4, snapshot["nutrients"]["calories"])
        self.assertEqual(old.model_dump(mode="json"), snapshot["snapshot"]["entry"])
        with self.database.connection() as db:
            self.assertEqual(2, db.execute("SELECT COUNT(*) AS n FROM catalog_versions").fetchone()["n"])
            with self.assertRaises(Exception):
                db.execute("UPDATE catalog_versions SET revision=99 WHERE version_id=%s", (old.version_id,))

    def test_sizes_allowed_addons_and_unknown_mass(self):
        rice = fixture(addon_ids=["fixture:sauce"])
        sauce = fixture("fixture:sauce", basis="per_portion", portion_grams=20,
                        nutrients={"calories": 10, "protein": 0, "fat": 1, "carbs": 0})
        self.seed(rice, sauce)
        base, addon = self.catalog.entry(rice.id), self.catalog.entry(sauce.id)
        result = self.catalog.calculate(Calculation(version_id=base.version_id, quantity=100, unit="g",
                    addons=[Selection(version_id=addon.version_id, quantity=2, unit="portion")]))
        self.assertEqual(143.4, result["nutrients"]["calories"])
        self.assertEqual(140, result["mass_grams"])
        self.assertEqual(addon.version_id, result["snapshot"]["addons"][0]["entry"]["version_id"])
        with self.assertRaises(AnalysisError):
            self.catalog.calculate(Calculation(version_id=addon.version_id, quantity=1, unit="portion",
                addons=[Selection(version_id=base.version_id, quantity=100, unit="g")]))
        small = fixture("fixture:small", name="Один напиток", variant="200 мл", basis="per_100ml", portion_ml=200)
        large = fixture("fixture:large", name="Один напиток", variant="400 мл", basis="per_100ml", portion_ml=400)
        self.seed(small, large)
        small_result = self.catalog.calculate(Calculation(version_id=self.catalog.entry(small.id).version_id, quantity=1, unit="portion"))
        large_result = self.catalog.calculate(Calculation(version_id=self.catalog.entry(large.id).version_id, quantity=1, unit="portion"))
        self.assertEqual(small_result["nutrients"]["calories"] * 2, large_result["nutrients"]["calories"])
        self.assertIsNone(large_result["mass_grams"])

    def test_public_search_aliases_barcode_and_ambiguous_ai_hints(self):
        a = fixture(brand="A", barcode="5449000000439")
        b = fixture("fixture:rice-b", brand="B")
        self.seed(a, b)
        for query in ("рис", "RICE", "күріш"):
            response = self.client.get("/v1/catalog/search", params={"q": query})
            self.assertEqual(200, response.status_code)
            self.assertEqual(2, response.json()["total"])
        match = self.client.post("/v1/catalog/match", json={"names": ["рис"]}).json()
        self.assertTrue(match["requires_confirmation"])
        self.assertEqual(2, len(match["candidates"]))
        match = self.client.post("/v1/catalog/match", json={"names": ["рис"], "barcode": a.barcode}).json()
        self.assertEqual(1, len(match["candidates"]))
        self.assertEqual("A", match["candidates"][0]["brand"])
        self.assertEqual(0, self.calls)

    def test_ai_match_groups_preserve_components_and_order(self):
        rice = fixture()
        sauce = fixture("fixture:sauce", name="Соус", aliases=["sauce", "тұздық"])
        self.seed(rice, sauce)
        result = self.client.post("/v1/catalog/match", json={"names": ["соус", "рис", "неизвестный компонент"]}).json()
        self.assertEqual(["соус", "рис", "неизвестный компонент"], [group["name"] for group in result["matches"]])
        self.assertEqual([sauce.id], [item["id"] for item in result["matches"][0]["candidates"]])
        self.assertEqual([rice.id], [item["id"] for item in result["matches"][1]["candidates"]])
        self.assertEqual([], result["matches"][2]["candidates"])
        self.assertTrue(result["requires_confirmation"])
        self.assertEqual(0, self.calls)

    def test_private_products_do_not_escape_owner_or_mutate_shared(self):
        self.seed(fixture())
        a = self.account()
        b = self.account("other@example.com")
        body = fixture("personal:fixture", name="Личный продукт").model_dump(mode="json")
        response = self.client.post("/v1/catalog/personal", headers=self.headers(a), json=body)
        self.assertEqual(201, response.status_code, response.text)
        private = response.json()
        self.assertEqual("user", private["source"]["status"])
        for headers in ({}, self.headers(b), {"Authorization": "Bearer " + self.guest()}):
            self.assertEqual(1, self.client.get("/v1/catalog/search", headers=headers).json()["total"])
            self.assertEqual(404, self.client.get("/v1/catalog/entries/" + private["version_id"], headers=headers).status_code)
            self.assertEqual(404, self.client.post("/v1/catalog/calculate", headers=headers,
                json={"version_id": private["version_id"], "quantity": 100, "unit": "g"}).status_code)
        self.assertEqual(2, self.client.get("/v1/catalog/search", headers=self.headers(a)).json()["total"])
        self.assertEqual(401, self.client.get("/v1/catalog/search", headers={"Authorization": "Bearer invalid"}).status_code)
        self.client.post("/v1/auth/logout", headers=self.headers(a))
        self.assertEqual(401, self.client.get("/v1/catalog/entries/" + private["version_id"], headers=self.headers(a)).status_code)

    def test_private_id_cannot_override_or_disguise_a_shared_entry(self):
        shared = fixture()
        self.seed(shared)
        a = self.account()
        body = shared.model_dump(mode="json")
        body["nutrients"]["calories"] = 111
        result = self.client.post("/v1/catalog/personal", headers=self.headers(a), json=body)
        self.assertEqual(201, result.status_code, result.text)
        self.assertEqual("user", result.json()["source"]["status"])
        self.assertEqual(123.4, self.client.get("/v1/catalog/entries/" + shared.id).json()["nutrients"]["calories"])
        self.assertEqual(409, self.client.get("/v1/catalog/entries/" + shared.id, headers=self.headers(a)).status_code)
        self.assertEqual(111, self.client.get("/v1/catalog/entries/" + result.json()["version_id"], headers=self.headers(a)).json()["nutrients"]["calories"])

    def test_menu_unknown_and_city_scope(self):
        dish = fixture("fixture:menu", kind="dish", city="Астана", chain="Fixture Coffee",
                       branch_scope="Только тестовый филиал", variant="маленький", basis="unknown", nutrients={})
        self.seed(dish)
        self.assertEqual(["Астана"], self.client.get("/v1/catalog/cities").json()["items"])
        self.assertEqual([], self.client.get("/v1/catalog/restaurants", params={"city": "Алматы"}).json()["items"])
        detail = self.catalog.entry(dish.id)
        response = self.client.post("/v1/catalog/calculate", json={"version_id": detail.version_id, "quantity": 1, "unit": "portion"})
        self.assertEqual(422, response.status_code)
        self.assertEqual("catalog_nutrition_missing", response.json()["error"]["code"])
        self.assertEqual(0, self.calls)

    def test_restaurant_branch_scope_does_not_mix_menus(self):
        common = dict(kind="dish", city="Астана", chain="Coffee Boom", basis="unknown", nutrients={})
        first = fixture("fixture:branch-a", branch_scope="Тестовый филиал A", **common)
        second = fixture("fixture:branch-b", branch_scope="Тестовый филиал B", **common)
        self.seed(first, second)
        restaurants = self.client.get("/v1/catalog/restaurants", params={"city": "Астана"}).json()["items"]
        self.assertEqual(2, len(restaurants))
        query = {"kind": "dish", "city": "Астана", "chain": "Coffee Boom", "branch_scope": first.branch_scope}
        result = self.client.get("/v1/catalog/search", params=query).json()
        self.assertEqual(1, result["total"])
        self.assertEqual(first.id, result["items"][0]["id"])
        match = self.client.post("/v1/catalog/match", json={"names": ["рис"], "city": "Астана", "chain": "Coffee Boom", "branch_scope": second.branch_scope}).json()
        self.assertEqual([second.id], [item["id"] for item in match["candidates"]])

    def test_import_never_changes_financial_or_auth_state(self):
        a = self.account()
        self.guest()
        with self.database.connection() as db:
            before = {table: db.execute("SELECT COUNT(*) AS n FROM " + table).fetchone()["n"]
                      for table in ("users", "auth_sessions", "guests", "invitations", "requests")}
            budget = dict(db.execute("SELECT * FROM budget").fetchone())
        self.seed(fixture())
        self.database.migrate()  # Repeat migration is harmless.
        with self.database.connection() as db:
            after = {table: db.execute("SELECT COUNT(*) AS n FROM " + table).fetchone()["n"] for table in before}
            self.assertEqual(budget, dict(db.execute("SELECT * FROM budget").fetchone()))
        self.assertEqual(before, after)
        self.assertEqual(200, self.client.get("/v1/users/me", headers=self.headers(a)).status_code)

    def test_additive_migration_from_four_preserves_existing_state(self):
        self.account()
        self.guest()
        # This class owns a newly-created, disposable test database. Reconstitute
        # its pre-005 state, then run the same immutable migration runner as deploy.
        self.assertTrue(self.database.url.rsplit("/", 1)[1].startswith("tamaq_test_"))
        tables = ("users", "auth_sessions", "refresh_tokens", "email_actions", "guests",
                  "requests", "registration", "budget", "invitations", "import_manifest")
        def fingerprints():
            with self.database.connection() as db:
                return {table: hashlib.sha256(json.dumps(sorted(
                    [json.dumps(dict(row), sort_keys=True, default=str) for row in db.execute("SELECT * FROM " + table).fetchall()]
                ), ensure_ascii=False).encode()).hexdigest() for table in tables}
        before = fingerprints()
        with self.database.connection() as db:
            db.execute("DROP TABLE catalog_versions,catalog_entries")
            db.execute("DROP FUNCTION catalog_version_immutable()")
            db.execute("DELETE FROM schema_versions WHERE version=5")
        self.database.migrate()
        self.database.require_current()
        self.assertEqual(before, fingerprints())
        self.assertEqual(1, self.seed(fixture())["added"])

    def test_account_deletion_removes_private_versions_only(self):
        self.seed(fixture())
        a = self.account()
        response = self.client.post("/v1/catalog/personal", headers=self.headers(a),
                                    json=fixture("personal:delete").model_dump(mode="json"))
        self.assertEqual(201, response.status_code)
        private = response.json()["version_id"]
        self.assertEqual(200, self.client.request("DELETE", "/v1/users/me", headers=self.headers(a), json={"password": accounts.PASSWORD}).status_code)
        with self.database.connection() as db:
            self.assertIsNone(db.execute("SELECT 1 FROM catalog_versions WHERE version_id=%s", (private,)).fetchone())
            self.assertEqual(1, db.execute("SELECT COUNT(*) AS n FROM catalog_versions").fetchone()["n"])
