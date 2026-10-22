"""python -m evaluation.run --dataset path.json --output private/run.json

Default validates only. --allow-paid additionally needs --url, --max-cases and
--max-cost-usd. Uses the same authenticated backend and ledger as Android.
"""
import argparse
import hashlib
import json
import statistics
import time
import unicodedata
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field, model_validator

from tamaq.pilot import PilotClient, read_photo, save_private
from tamaq.provider import RESERVE_MICRO_USD

CATEGORIES = ("simple", "mixed_plate", "plov", "manty", "beshbarmak", "soup_sauce", "poor_light", "no_food")


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class Nutrients(Strict):
    calories: float = Field(ge=0)
    protein: float = Field(ge=0)
    fat: float = Field(ge=0)
    carbs: float = Field(ge=0)


class Reference(Strict):
    reliable: bool
    source: str | None
    nutrients: Nutrients | None
    notes: str | None


class Case(Strict):
    id: str = Field(pattern=r"^[a-zA-Z0-9_-]{1,48}$")  # anonymous IDs only
    photo: str
    category: Literal["simple", "mixed_plate", "plov", "manty", "beshbarmak", "soup_sauce", "poor_light", "no_food"]
    food_expected: bool
    known_name: str
    accepted_names: list[str]
    ingredients: list[str]
    recipe: str | None
    weighed_mass_grams: float | None = Field(gt=0)
    reference: Reference

    @model_validator(mode="after")
    def valid_reference(self):
        if self.reference.reliable and (self.weighed_mass_grams is None or
                self.reference.nutrients is None or not self.reference.source or not self.reference.source.strip()):
            raise ValueError("A reliable reference requires weighed mass, nutrients and a source")
        if not self.known_name.strip() or not self.photo.strip():
            raise ValueError("Name/photo are required")
        if self.category == "no_food" and self.food_expected:
            raise ValueError("no_food category cannot expect food")
        return self


class Dataset(Strict):
    schema_version: Literal["1"]
    cases: list[Case]

    @model_validator(mode="after")
    def unique(self):
        if len({c.id for c in self.cases}) != len(self.cases):
            raise ValueError("Duplicate case IDs")
        return self


def label(value):
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def metrics(case, outcome):
    body = outcome["response"]
    if "result" not in body:
        code = body.get("error", {}).get("code", "invalid_contract")
        return {"refusal": True, "error_code": code} if code == "refusal" else {"technical_error": code, "refusal": False}
    result = body["result"]
    components = result["components"]
    found = result["status"] != "no_food"
    data = {"food_presence_correct": found == case.food_expected,
            "recognition_alias_match": label(result["name"]) in {label(n) for n in [case.known_name, *case.accepted_names]} if case.food_expected else None,
            "recognition_manual_review": "pending", "status": result["status"],
            "quantitative_reference": case.reference.reliable,
            "portion_estimate_coverage": bool(components) and all(c["mass_grams"] is not None for c in components)}
    # Portion is a separate metric, independent of nutrition reference reliability.
    if case.weighed_mass_grams and data["portion_estimate_coverage"]:
        predicted = sum(c["mass_grams"] for c in components)
        data["portion_absolute_error_grams"] = abs(predicted - case.weighed_mass_grams)
        data["portion_relative_error"] = abs(predicted - case.weighed_mass_grams) / case.weighed_mass_grams
    if case.reference.reliable:
        for nutrient in ("calories", "protein", "fat", "carbs"):
            expected = getattr(case.reference.nutrients, nutrient)
            absolute = abs(sum(c[nutrient] for c in components) - expected)
            data[f"{nutrient}_absolute_error"] = absolute
            # Near zero denominators are unstable: >=10 kcal and >=1g macro.
            data[f"{nutrient}_relative_error"] = absolute / expected if expected >= (10 if nutrient == "calories" else 1) else None
    return data


def summarize(rows):
    result = {"cases": len(rows), "technical_errors": sum("technical_error" in r.get("metrics", {}) for r in rows),
              "refusals": sum(r.get("metrics", {}).get("refusal", False) for r in rows),
              "ambiguous": sum(r.get("metrics", {}).get("status") == "ambiguous" for r in rows),
              "no_food": sum(r.get("metrics", {}).get("status") == "no_food" for r in rows),
              "cost_usage_priced_micro_usd": sum(r.get("outcome", {}).get("receipt", {}).get("cost_micro_usd", 0)
                    for r in rows if r.get("outcome", {}).get("receipt", {}).get("cost_kind") == "usage_priced"),
              "cost_estimate_micro_usd": sum(r.get("outcome", {}).get("receipt", {}).get("cost_micro_usd", 0)
                    for r in rows if r.get("outcome", {}).get("receipt", {}).get("cost_kind") == "reserved_estimate")}
    for metric in ("calories_absolute_error", "protein_absolute_error", "calories_relative_error", "protein_relative_error",
                   "portion_absolute_error_grams", "portion_relative_error", "recognition_alias_match", "food_presence_correct"):
        values = [r["metrics"][metric] for r in rows if r.get("metrics", {}).get(metric) is not None]
        result[metric] = {"n": len(values), "mean": statistics.mean(values) if values else None}
    latencies = [r["latency_seconds"] for r in rows if r.get("latency_seconds") is not None]
    result["latency_seconds"] = {"n": len(latencies), "median": statistics.median(latencies) if latencies else None,
                                  "max": max(latencies) if latencies else None}
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--url")
    parser.add_argument("--allow-paid", action="store_true")
    parser.add_argument("--local-fake", action="store_true")
    parser.add_argument("--max-cases", type=int)
    parser.add_argument("--max-cost-usd")
    args = parser.parse_args(argv)
    try:
        manifest = Path(args.dataset)
        dataset = Dataset.model_validate_json(manifest.read_text(encoding="utf-8"))
        # Validate every supplied photo before any possible paid request.
        image_digests = {c.id: hashlib.sha256(read_photo(manifest.parent / c.photo)).hexdigest() for c in dataset.cases}
        if not (args.allow_paid or args.local_fake):
            print(json.dumps({"mode": "dry_run", "cases_validated": len(image_digests),
                              "missing_categories": sorted(set(CATEGORIES) - {c.category for c in dataset.cases}),
                              "network_requests": 0}, indent=2))
            return 0
        if not args.url or not args.max_cases or args.max_cases < 1 or not args.max_cost_usd or not dataset.cases:
            raise ValueError("Explicit origin, positive case/spend limits and nonempty dataset required")
        # Decimal parsing avoids float spend limits; no environment mutation.
        from decimal import Decimal
        amount = Decimal(args.max_cost_usd)
        if not amount.is_finite() or amount <= 0:
            raise ValueError("Invalid spend cap")
        cap = int(amount * 1_000_000)
        client = PilotClient(args.url, args.local_fake)
        path = Path(args.output)
        dataset_digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
        if path.exists():
            run = json.loads(path.read_text(encoding="utf-8"))
            if run["dataset_digest"] != dataset_digest or run["config"] != client.config or run["url"] != args.url:
                raise ValueError("Journal belongs to another dataset/config/backend")
        else:
            run = {"started_utc": datetime.now(timezone.utc).isoformat(), "dataset_digest": dataset_digest,
                   "config": client.config, "url": args.url, "is_demo": args.local_fake, "rows": []}
        # An interrupted submitting row is uncertain and consumes the entire bound.
        spent = sum(r.get("outcome", {}).get("receipt", {}).get("cost_micro_usd", RESERVE_MICRO_USD) for r in run["rows"])
        for case in dataset.cases[:args.max_cases]:
            if any(r["case_id"] == case.id for r in run["rows"]):
                continue  # no automatic resubmission, including uncertain rows
            if spent + RESERVE_MICRO_USD > cap:
                break
            if run["rows"]:
                time.sleep(31)  # <=2/minute, same quotas as Android
            jpeg = read_photo(manifest.parent / case.photo)
            if hashlib.sha256(jpeg).hexdigest() != image_digests[case.id]:
                raise ValueError("Photo changed during evaluation")
            row = {"case_id": case.id, "category": case.category, "analysis_id": str(uuid4()),
                   "photo_digest": hashlib.sha256(jpeg).hexdigest(), "state": "submitting"}
            run["rows"].append(row)
            save_private(path, run)
            start = time.monotonic()
            row["outcome"] = client.analyze(jpeg, row["analysis_id"])
            row["latency_seconds"] = time.monotonic() - start
            row["metrics"] = metrics(case, row["outcome"])
            row["state"] = "finished"
            spent += row["outcome"]["receipt"]["cost_micro_usd"]
            run["summary"] = summarize(run["rows"])
            save_private(path, run)
            if row["outcome"]["receipt"]["cost_kind"] == "reserved_estimate" or row["outcome"]["http_status"] != 200:
                break  # reconcile/resolve errors before further paid calls
        print(json.dumps({"mode": "fake" if args.local_fake else "paid", "summary": run.get("summary"),
                          "completed_or_uncertain_cases": len(run["rows"])}, indent=2))
        return 0
    except (ValueError, OSError, KeyError):
        print("Evaluation stopped: check dataset/configuration and private journal. No automatic retry.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
