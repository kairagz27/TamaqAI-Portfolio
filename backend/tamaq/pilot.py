"""Operator commands. No inference without an explicit --allow-paid flag.

Secrets are read from environment, never command arguments or printed output.
"""
import argparse
import hashlib
import json
import os
import getpass
import time
from pathlib import Path
from urllib.parse import urlparse
from uuid import uuid4

import httpx

from .app import normalize_image
from .contracts import AnalysisError, AnalysisResponse
from .ledger import Ledger
from .provider import MODEL, OpenAIProvider, RESERVE_MICRO_USD


class PilotClient:
    def __init__(self, url, local_fake=False, local_openai=False):
        if local_fake and local_openai:
            raise ValueError("Select exactly one local provider mode")
        parsed = urlparse(url)
        if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/"):
            raise ValueError("Use a backend origin without credentials/path/query")
        if parsed.scheme != "https" and not ((local_fake or local_openai) and parsed.scheme == "http" and
                parsed.hostname in ("localhost", "127.0.0.1")):
            raise ValueError("HTTPS required; explicit local mode permits loopback HTTP only")
        token = os.environ.get("TAMAQ_PILOT_TOKEN", "")
        if not token and os.environ.get("TAMAQ_PILOT_TOKEN_FILE"):
            token = json.loads(Path(os.environ["TAMAQ_PILOT_TOKEN_FILE"]).read_text(encoding="utf-8"))["token"]
        if not token:
            raise ValueError("Set TAMAQ_PILOT_TOKEN in your local environment")
        self.client = httpx.Client(base_url=url.rstrip("/"), timeout=45, follow_redirects=False,
                                   trust_env=False, headers={"Authorization": "Bearer " + token})
        self.config = self.get("/v1/pilot/config")
        if bool(self.config["is_demo"]) != local_fake:
            raise ValueError("Provider mode differs from explicitly selected mode")
        if not self.config["configured"]:
            raise ValueError("Backend provider is not configured")
        if not local_fake:
            limit = self.config.get("verification_budget")
            if not limit or type(limit.get("limit_micro_usd")) is not int or not 0 < limit["limit_micro_usd"] <= 1_000_000:
                raise ValueError("Server must durably enforce the authorized verification budget (maximum $1)")
            if self.config["model"] != MODEL or self.config["settings"]["sdk_retries"] != 0:
                raise ValueError("Unexpected model/retry configuration")

    def get(self, path):
        response = self.client.get(path)
        if response.status_code != 200:
            raise ValueError("Backend metadata unavailable; check access/configuration")
        return response.json()

    def analyze(self, jpeg, key):
        try:
            response = self.client.post("/v1/analyses", content=jpeg,
                headers={"Content-Type": "image/jpeg", "Idempotency-Key": key})
            body = response.json()
            receipt_response = self.client.get(f"/v1/analyses/{key}/receipt")
            if receipt_response.status_code == 200:
                receipt = receipt_response.json()
            elif receipt_response.status_code == 404 and response.status_code in (400, 401, 413, 415, 429, 503):
                receipt = {"cost_micro_usd": 0, "cost_kind": "usage_priced", "usage": None}
            else:
                raise ValueError("Receipt unavailable")
            return {"http_status": response.status_code, "response": body, "receipt": receipt}
        except (httpx.HTTPError, ValueError):
            # The server may have accepted it. Persist the same key; never retry here.
            return {"http_status": None, "response": {"error": {"code": "client_outcome_unknown"}},
                    "receipt": {"cost_micro_usd": RESERVE_MICRO_USD, "cost_kind": "reserved_estimate"}}


def save_private(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
    temporary.chmod(0o600)  # On Windows also restrict the directory ACL to your account.
    temporary.replace(path)


def read_photo(path):
    path = Path(path)
    if not path.is_file() or not 0 < path.stat().st_size <= 20 * 1024 * 1024:
        raise ValueError("Missing/oversized evaluation image (max 20 MiB)")
    return normalize_image(path.read_bytes())


def validate_outcome(outcome, key, local_fake):
    if outcome["http_status"] != 200:
        return False
    parsed = AnalysisResponse.model_validate(outcome["response"])
    parsed.result.validate_semantics()
    if parsed.analysis_id != key or parsed.schema_version != "1" or parsed.is_demo != local_fake:
        raise ValueError("Unexpected Android response contract/provider mode")
    if not local_fake and parsed.model != MODEL:
        raise ValueError("Unexpected model")
    return True


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("report", "stop"):
        commands.add_parser(command)
    commands.add_parser("check-account", help="Only model metadata; no inference, no Responses request")
    login = commands.add_parser("login", help="Prompt for an invitation; save bearer token privately")
    login.add_argument("--url", required=True)
    login.add_argument("--token-file", required=True)
    login.add_argument("--local-fake", action="store_true")
    login.add_argument("--local-openai", action="store_true", help="Real provider over LOCAL loopback HTTP")
    smoke = commands.add_parser("smoke")
    smoke.add_argument("--image", required=True)
    smoke.add_argument("--url", required=True)
    smoke.add_argument("--run", required=True, help="Private durable run journal; same path on redelivery")
    smoke.add_argument("--allow-paid", action="store_true")
    smoke.add_argument("--local-fake", action="store_true")
    smoke.add_argument("--local-openai", action="store_true", help="Real provider over LOCAL loopback HTTP")
    smoke.add_argument("--verify-idempotency", action="store_true",
                       help="Resend a completed journal's SAME key/photo once; compare response and receipt")
    args = parser.parse_args(argv)
    try:
        if args.command in ("report", "stop"):
            path = os.environ.get("TAMAQ_DATABASE", "var/tamaq.sqlite3")
            if not Path(path).is_file():
                raise ValueError("Configured ledger does not exist; refusing to create a new budget")
            ledger = Ledger(path, budget=10**18, pilot_budget=10**18)
            if args.command == "stop":
                ledger.stop()
            print(json.dumps(ledger.report(), indent=2))
        elif args.command == "check-account":
            provider = OpenAIProvider(os.environ.get("OPENAI_API_KEY", ""), os.environ.get("OPENAI_MODEL", ""))
            try:
                metadata = provider.client.models.retrieve(provider.model)
                if metadata.id != provider.model:
                    raise ValueError("Returned model differs from configured model")
            except Exception:
                raise ValueError("Account model metadata unavailable; check key/project/model permissions") from None
            print("Model metadata accessible. Image inference/structured output still require the explicit smoke test.")
        elif args.command == "login":
            url = urlparse(args.url)
            if args.local_fake and args.local_openai:
                raise ValueError("Select exactly one local provider mode")
            if (url.scheme != "https" and not ((args.local_fake or args.local_openai) and url.scheme == "http" and url.hostname in ("localhost", "127.0.0.1"))) or url.username or url.password or url.path not in ("", "/") or url.query or url.fragment:
                raise ValueError("Invalid backend origin")
            invitation = getpass.getpass("Pilot invitation (hidden): ")
            with httpx.Client(timeout=10, follow_redirects=False, trust_env=False) as session:
                response = session.post(args.url.rstrip("/") + "/v1/guests", headers={"X-Pilot-Invite": invitation})
            if response.status_code != 200:
                raise ValueError("Registration denied")
            save_private(args.token_file, response.json())
            print("Session stored privately; no AI inference performed.")
        else:
            jpeg = read_photo(args.image)
            if not (args.allow_paid or args.local_fake):
                print("DRY RUN: photo validated. No network requests. Add --allow-paid to run one inference.")
                return 0
            client = PilotClient(args.url, args.local_fake, local_openai=args.local_openai)
            path = Path(args.run)
            digest = hashlib.sha256(jpeg).hexdigest()
            if args.verify_idempotency and not path.is_file():
                raise ValueError("Idempotency verification requires an existing completed journal")
            if path.exists():
                record = json.loads(path.read_text(encoding="utf-8"))
                if record["digest"] != digest or record["config"] != client.config or record["url"] != args.url:
                    raise ValueError("Run journal belongs to a different photo/config/backend")
                if "outcome" in record:
                    if args.verify_idempotency:
                        if not validate_outcome(record["outcome"], record["analysis_id"], args.local_fake):
                            raise ValueError("Only a successful completed analysis can be verified")
                        receipt = client.get(f"/v1/analyses/{record['analysis_id']}/receipt")
                        if receipt["status"] != "Finished" or receipt["cost_kind"] != "usage_priced" or receipt != record["outcome"]["receipt"]:
                            raise ValueError("Original result/receipt is no longer available")
                        start = time.monotonic()
                        repeated = client.analyze(jpeg, record["analysis_id"])
                        check = {"latency_seconds": time.monotonic() - start,
                                 "response_equal": repeated["response"] == record["outcome"]["response"],
                                 "receipt_equal": repeated["receipt"] == receipt,
                                 "http_status": repeated["http_status"]}
                        record["idempotency_check"] = check
                        save_private(path, record)
                        print(json.dumps(check, indent=2))
                        return 0 if check["response_equal"] and check["receipt_equal"] and check["http_status"] == 200 else 1
                    print("Saved outcome exists; no further request. Inspect private run journal/ledger.")
                    return 0 if validate_outcome(record["outcome"], record["analysis_id"], args.local_fake) else 1
                if args.verify_idempotency:
                    raise ValueError("Incomplete journal: reconcile outcome before redelivery")
            else:
                record = {"analysis_id": str(uuid4()), "digest": digest, "url": args.url, "config": client.config}
                save_private(path, record)  # key is durable BEFORE the one possible paid call
            start = time.monotonic()
            record["outcome"] = client.analyze(jpeg, record["analysis_id"])
            record["latency_seconds"] = time.monotonic() - start
            save_private(path, record)
            record["android_contract_validated"] = validate_outcome(record["outcome"], record["analysis_id"], args.local_fake)
            save_private(path, record)
            print(json.dumps({"http_status": record["outcome"]["http_status"],
                              "latency_seconds": record["latency_seconds"],
                              "android_contract_validated": record["android_contract_validated"],
                              "receipt": record["outcome"]["receipt"]}, indent=2))
            return 0 if record["outcome"]["http_status"] == 200 else 1
        return 0
    except (ValueError, AnalysisError, OSError, httpx.HTTPError, KeyError):
        print("Command failed. Check configuration/access, image format and private journal. No automatic retry.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
