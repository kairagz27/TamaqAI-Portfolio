"""Repeatable catalogue imports. Validation reports never echo submitted field values."""
import csv
import json
from pathlib import Path

from pydantic import ValidationError

from .models import EntryInput
from .service import Catalog, shared_rejection

MAX_IMPORT_BYTES = 5 * 1024 * 1024
ARRAY_FIELDS = ("aliases", "country_of_sale", "country_of_manufacture", "addon_ids")
REVIEW_CODES = {"unknown_basis_requires_review", "suspicious_macronutrients_requires_review",
                "suspicious_macronutrient_sum_requires_review", "suspicious_energy_requires_review"}


def load(path):
    path = Path(path)
    if path.stat().st_size > MAX_IMPORT_BYTES:
        raise ValueError("import_too_large")
    if path.suffix.lower() == ".csv":
        with path.open(encoding="utf-8-sig", newline="") as stream:
            rows = list(csv.DictReader(stream))
        if len(rows) > 10000:
            raise ValueError("too_many_import_rows")
        result = []
        for source in rows:
            row = {key: value if value != "" else None for key, value in source.items()}
            try:
                for key in (*ARRAY_FIELDS, "nutrients", "source"):
                    if row.get(key) is not None:
                        row[key] = json.loads(row[key])
                for key in ("portion_grams", "portion_ml"):
                    if row.get(key) is not None:
                        row[key] = float(row[key])
            except (ValueError, TypeError):
                result.append({})  # Field validation reports this row without echoing input.
                continue
            for key in ARRAY_FIELDS:
                if row.get(key) is None:
                    row[key] = []
            result.append(row)
        return result
    raw = json.loads(path.read_text(encoding="utf-8-sig"))
    if isinstance(raw, dict) and set(raw) == {"entries"}:
        raw = raw["entries"]
    if not isinstance(raw, list) or len(raw) > 10000:
        raise ValueError("invalid_import_document")
    return raw


def validate(rows):
    accepted, errors, seen = [], [], set()
    for index, row in enumerate(rows):
        try:
            entry = EntryInput.model_validate(row)
            rejection = shared_rejection(entry)
            if rejection:
                errors.append({"row": index + 1, "code": rejection})
                continue
            if entry.id in seen:
                errors.append({"row": index + 1, "code": "duplicate_id_in_batch"})
                continue
            seen.add(entry.id)
            accepted.append(entry)
        except ValidationError as error:
            # Only our fixed validator identifiers are returned. Pydantic messages,
            # submitted values and arbitrary exception strings are never echoed.
            codes = [str(issue.get("ctx", {}).get("error", "")) for issue in error.errors()]
            review = next((code for code in codes if code in REVIEW_CODES), None)
            errors.append({"row": index + 1, "code": review or "invalid_entry",
                           "fields": sorted({str(issue["loc"][0]) for issue in error.errors() if issue["loc"]})})
    # Each import keeps one source namespace: sources are not flattened into an
    # unattributed combined product record. Redistribution still needs licence review.
    if len({entry.source.namespace for entry in accepted}) > 1:
        return [], [*errors, *({"id": entry.id, "code": "mixed_source_namespaces_import_separately"}
                               for entry in accepted)]
    return accepted, errors


def import_file(path, database, dry_run=True):
    rows = load(path)
    entries, errors = validate(rows)
    if database is None:
        if not dry_run:
            raise ValueError("database_required")
        report = {"added": len(entries), "updated": 0, "skipped": 0, "rejected": 0,
                  "dry_run": True, "database_checked": False, "review": [], "errors": []}
        for entry in entries:
            if not entry.nutrients.complete or entry.basis == "unknown":
                report["review"].append({"id": entry.id, "code": "nutrition_not_published", "calculable": False})
    else:
        database.require_current()
        report = Catalog(database).import_entries(entries, dry_run=dry_run)
        report["database_checked"] = True
    report["rejected"] += len(errors)
    report["errors"].extend(errors)
    report["review"].extend({**error, "calculable": False} for error in errors if error["code"] in REVIEW_CODES)
    report["input_rows"] = len(rows)
    return report
