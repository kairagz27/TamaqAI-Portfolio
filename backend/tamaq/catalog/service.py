"""Transactional immutable versions and deterministic nutrient calculations."""
from decimal import Decimal
import hashlib
import json
import re
import time
import unicodedata

from psycopg.types.json import Jsonb

from ..contracts import AnalysisError
from .models import Entry, EntryInput, Calculation, Selection, Match

CATALOG_LOCK = 8426500005


def normalized(value):
    value = unicodedata.normalize("NFKC", value).casefold().replace("ё", "е")
    return " ".join(re.findall(r"[^\W_]+", value, re.UNICODE))


def canonical(entry):
    return json.dumps(entry.model_dump(mode="json"), ensure_ascii=False,
                      sort_keys=True, separators=(",", ":"), allow_nan=False)


def entry_key(entry_id, owner=None):
    return hashlib.sha256((str(owner or "shared") + "\0" + entry_id).encode()).hexdigest()


def shared_rejection(entry):
    if entry.source.status == "user":
        return "private_owner_required"
    if entry.kind == "product" and "KZ" not in entry.country_of_sale:
        return "kazakhstan_availability_not_evidenced"
    return None


class Catalog:
    def __init__(self, database):
        self.database = database

    def import_entries(self, entries, *, dry_run=True, owner=None):
        """One transaction per validated batch; a process lock makes repeat imports race safe."""
        report = {"added": 0, "updated": 0, "skipped": 0, "rejected": 0,
                  "dry_run": dry_run, "review": [], "errors": []}
        with self.database.connection() as db:
            db.execute("SELECT pg_advisory_xact_lock(%s)", (CATALOG_LOCK,))
            for entry in entries:
                rejection = shared_rejection(entry) if owner is None else None
                if rejection:
                    report["rejected"] += 1
                    report["errors"].append({"id": entry.id, "code": rejection})
                    continue
                key = entry_key(entry.id, owner)
                version = hashlib.sha256((key + "\0" + canonical(entry)).encode()).hexdigest()
                prior = db.execute("SELECT current_version,namespace FROM catalog_entries WHERE entry_key=%s", (key,)).fetchone()
                if prior and prior["namespace"] != entry.source.namespace:
                    report["rejected"] += 1
                    report["errors"].append({"id": entry.id, "code": "source_namespace_change"})
                    continue
                if prior and prior["current_version"] == version:
                    report["skipped"] += 1
                    continue
                if not entry.nutrients.complete or entry.basis == "unknown":
                    report["review"].append({"id": entry.id, "code": "nutrition_not_published", "calculable": False})
                # Once a version existed, re-importing old source data selects that exact
                # version again without mutating it or creating a duplicate revision.
                existing = db.execute("SELECT revision FROM catalog_versions WHERE version_id=%s", (version,)).fetchone()
                revision = existing["revision"] if existing else db.execute(
                    "SELECT COALESCE(MAX(revision),0)+1 AS n FROM catalog_versions WHERE entry_key=%s", (key,)).fetchone()["n"]
                report["updated" if prior else "added"] += 1
                if dry_run:
                    continue
                search = normalized(" ".join(filter(None, [entry.name, entry.brand, entry.barcode,
                                                           entry.chain, entry.city, entry.variant, *entry.aliases])))
                db.execute("""INSERT INTO catalog_entries(entry_key,owner_id,catalog_id,namespace,search_text,current_version,created_at)
                            VALUES(%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(entry_key) DO UPDATE
                            SET current_version=EXCLUDED.current_version,search_text=EXCLUDED.search_text""",
                           (key, owner, entry.id, entry.source.namespace, search, version, int(time.time())))
                if not existing:
                    document = Entry(**entry.model_dump(), version_id=version, revision=revision)
                    db.execute("INSERT INTO catalog_versions VALUES(%s,%s,%s,%s,%s)",
                               (version, key, revision, Jsonb(document.model_dump(mode="json")), int(time.time())))
        return report

    @staticmethod
    def visible(owner):
        return "(e.owner_id IS NULL OR e.owner_id=%s)", (owner,)

    def search(self, q="", kind=None, city=None, chain=None, barcode=None, limit=30, owner=None, branch_scope=None):
        clause, params = self.visible(owner)
        clauses, values = [clause], list(params)
        for term in normalized(q).split():
            clauses.append("e.search_text LIKE %s")
            values.append("%" + term + "%")
        for field, value in (("kind", kind), ("city", city), ("chain", chain), ("barcode", barcode), ("branch_scope", branch_scope)):
            if value is not None:
                clauses.append("v.document->>%s = %s")
                values.extend((field, value))
        where = " AND ".join(clauses)
        with self.database.connection() as db:
            total = db.execute("SELECT COUNT(*) AS n FROM catalog_entries e JOIN catalog_versions v ON v.version_id=e.current_version WHERE " + where, values).fetchone()["n"]
            rows = db.execute("SELECT v.document FROM catalog_entries e JOIN catalog_versions v ON v.version_id=e.current_version WHERE " + where +
                              " ORDER BY v.document->>'name',e.entry_key LIMIT %s", (*values, limit)).fetchall()
        return {"items": [row["document"] for row in rows], "total": total}

    def entry(self, identifier, owner=None):
        clause, params = self.visible(owner)
        with self.database.connection() as db:
            rows = db.execute("SELECT v.document FROM catalog_versions v JOIN catalog_entries e ON e.entry_key=v.entry_key WHERE " + clause +
                              " AND (v.version_id=%s OR (e.catalog_id=%s AND v.version_id=e.current_version)) ORDER BY e.owner_id NULLS LAST",
                              (*params, identifier, identifier)).fetchall()
        if not rows:
            raise AnalysisError("catalog_entry_not_found", 404)
        # Stable shared IDs are source namespaced; private IDs cannot override a
        # shared entry silently. The client should use the returned version_id.
        if len(rows) > 1:
            raise AnalysisError("catalog_entry_ambiguous", 409)
        return Entry.model_validate(rows[0]["document"])

    def owned_entry(self, identifier, owner):
        with self.database.connection() as db:
            row = db.execute("""SELECT v.document FROM catalog_entries e JOIN catalog_versions v ON v.version_id=e.current_version
                                WHERE e.owner_id=%s AND e.catalog_id=%s""", (owner, identifier)).fetchone()
        if not row:
            raise AnalysisError("catalog_entry_not_found", 404)
        return Entry.model_validate(row["document"])

    def cities(self):
        with self.database.connection() as db:
            rows = db.execute("""SELECT DISTINCT v.document->>'city' AS city FROM catalog_entries e
                                JOIN catalog_versions v ON v.version_id=e.current_version WHERE e.owner_id IS NULL
                                AND v.document->>'kind'='dish' AND v.document->>'city' IS NOT NULL ORDER BY city""").fetchall()
        return {"items": [row["city"] for row in rows]}

    def restaurants(self, city=None):
        with self.database.connection() as db:
            rows = db.execute("""SELECT DISTINCT v.document->>'chain' AS chain,v.document->>'city' AS city,
                                v.document->>'branch_scope' AS branch_scope FROM catalog_entries e
                                JOIN catalog_versions v ON v.version_id=e.current_version
                                WHERE e.owner_id IS NULL AND v.document->>'kind'='dish'
                                AND (%s::text IS NULL OR v.document->>'city'=%s)
                                ORDER BY chain,city,branch_scope""", (city, city)).fetchall()
        return {"items": [dict(row) for row in rows]}

    def match(self, request: Match, owner=None):
        # Recognition is only a search hint, never a brand/restaurant attribution.
        # A barcode is explicit identity evidence, but still requires confirmation.
        candidates = {}
        groups = []
        queries = request.names or ([""] if request.barcode else [])
        for name in queries:
            result = self.search("" if request.barcode else name, city=request.city, chain=request.chain,
                                 barcode=request.barcode, limit=10, owner=owner, branch_scope=request.branch_scope)
            groups.append({"name": name, "candidates": result["items"]})
            for item in result["items"]:
                candidates[item["version_id"]] = item
        return {"candidates": list(candidates.values())[:30], "matches": groups, "requires_confirmation": True}

    def calculate(self, request: Calculation, owner=None):
        base = self.entry(request.version_id, owner)
        snapshot = selection_snapshot(base, request)
        additions = []
        seen = set()
        for selection in request.addons:
            addon = self.entry(selection.version_id, owner)
            if addon.id not in base.addon_ids or addon.version_id in seen:
                raise AnalysisError("catalog_addon_not_allowed", 422)
            seen.add(addon.version_id)
            additions.append(selection_snapshot(addon, selection))
        snapshots = [snapshot, *additions]
        nutrition = {key: float(sum(Decimal(str(part["nutrients"][key])) for part in snapshots))
                     for key in ("calories", "protein", "fat", "carbs")}
        # A partially unknown total mass/volume is unknown, not a misleading subtotal.
        def total_measure(key):
            if any(part[key] is None for part in snapshots):
                return None
            return float(sum(Decimal(str(part[key])) for part in snapshots))
        mass, volume = total_measure("mass_grams"), total_measure("volume_ml")
        if nutrition["calories"] > 10000 or any(nutrition[key] > 1000 for key in ("protein", "fat", "carbs")) or (mass and mass > 10000):
            raise AnalysisError("catalog_quantity_too_large", 422)
        result = {"name": base.name, "nutrients": nutrition, "mass_grams": mass, "volume_ml": volume}
        return {**result, "snapshot": {**snapshot, "addons": additions, "nutrients": nutrition,
                                       "mass_grams": mass, "volume_ml": volume}}


def selection_snapshot(entry: Entry, selection: Selection):
    if not entry.nutrients.complete or entry.basis == "unknown":
        raise AnalysisError("catalog_nutrition_missing", 422)
    quantity = Decimal(str(selection.quantity))
    grams = Decimal(str(entry.portion_grams)) if entry.portion_grams is not None else None
    ml = Decimal(str(entry.portion_ml)) if entry.portion_ml is not None else None
    mass = volume = None
    if entry.basis == "per_100g":
        if selection.unit == "g":
            mass = quantity
        elif selection.unit == "portion" and grams is not None:
            mass = quantity * grams
        else:
            raise AnalysisError("catalog_quantity_basis", 422)
        factor = mass / 100
    elif entry.basis == "per_100ml":
        if selection.unit == "ml":
            volume = quantity
        elif selection.unit == "portion" and ml is not None:
            volume = quantity * ml
        else:
            raise AnalysisError("catalog_quantity_basis", 422)
        factor = volume / 100
        # Published matched portion mass+volume provide a measured density. With
        # only volume the mass remains null; 100 ml never becomes 100 g by default.
        if grams is not None and ml is not None:
            mass = volume * grams / ml
    else:
        if selection.unit == "portion":
            factor = quantity
        elif selection.unit == "g" and grams is not None:
            factor = quantity / grams
        elif selection.unit == "ml" and ml is not None:
            factor = quantity / ml
        else:
            raise AnalysisError("catalog_quantity_basis", 422)
        if grams is not None:
            mass = grams * factor
        if ml is not None:
            volume = ml * factor
    nutrients = {key: float(Decimal(str(value)) * factor)
                 for key, value in entry.nutrients.model_dump().items()}
    return {"entry": entry.model_dump(mode="json"), "quantity": selection.quantity,
            "unit": selection.unit, "nutrients": nutrients,
            "mass_grams": float(mass) if mass is not None else None,
            "volume_ml": float(volume) if volume is not None else None}
