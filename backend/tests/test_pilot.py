import hashlib
import json
import os
import sqlite3
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import patch
from uuid import uuid4

import httpx
from fastapi.testclient import TestClient
from openai import APIConnectionError, APITimeoutError, APIStatusError, RateLimitError

import test_backend as stage_one
image = stage_one.image
from tamaq.app import create_app
from tamaq.contracts import AnalysisError, FoodResult
from tamaq.ledger import Ledger
from tamaq.provider import OpenAIProvider, RESERVE_MICRO_USD
from tamaq.settings import invitations, money


class PilotTest(unittest.TestCase):
    setUp = stage_one.BackendTest.setUp
    tearDown = stage_one.BackendTest.tearDown
    post = stage_one.BackendTest.post
    def test_refresh_and_deletion_do_not_reset_quota_or_idempotency(self):
        key = str(uuid4())
        first = self.post(key)
        second = self.client.post("/v1/guests", headers={"X-Pilot-Invite": "test-invite"}).json()["token"]
        self.assertEqual(first.json(), self.post(key, token=second).json())
        self.assertEqual(1, self.calls)
        self.assertEqual(200, self.post(token=second).status_code)
        self.client.delete("/v1/guest", headers={"Authorization": "Bearer " + second})
        third = self.client.post("/v1/guests", headers={"X-Pilot-Invite": "test-invite"}).json()["token"]
        self.assertEqual(429, self.post(token=third).status_code)
        self.assertEqual(409, self.post(key, token=third).status_code)
        self.assertEqual(2, self.calls)

    def test_expired_token_no_provider_call(self):
        with self.ledger.connection() as db:
            db.execute("UPDATE guests SET expires_at=0")
        response = self.post()
        self.assertEqual(401, response.status_code)
        self.assertEqual("token_expired", response.json()["error"]["code"])
        self.assertEqual(0, self.calls)

    def test_same_invite_session_creation_cannot_grow_without_bound(self):
        for _ in range(4):
            self.assertEqual(200, self.client.post("/v1/guests", headers={"X-Pilot-Invite": "test-invite"}).status_code)
        self.assertEqual(429, self.client.post("/v1/guests", headers={"X-Pilot-Invite": "test-invite"}).status_code)

    def test_concurrent_same_key_calls_provider_once(self):
        entered, release = threading.Event(), threading.Event()
        original = self.provider.analyze
        def slow(jpeg):
            entered.set()
            release.wait(5)
            return original(jpeg)
        self.provider.analyze = slow
        key = str(uuid4())
        with ThreadPoolExecutor(max_workers=2) as executor:
            first = executor.submit(self.post, key)
            self.assertTrue(entered.wait(5))
            second = executor.submit(self.post, key)
            self.assertEqual(409, second.result(timeout=5).status_code)
            release.set()
            self.assertEqual(200, first.result(timeout=5).status_code)
        self.assertEqual(1, self.calls)
        self.assertEqual(200, self.post(key).status_code)
        self.assertEqual(1, self.calls)

    def test_separate_pilot_cap_is_shared_and_persistent(self):
        ledger = Ledger(self.path, pilot_budget=RESERVE_MICRO_USD)
        key = str(uuid4())
        ledger.reserve(self.owner, key, "d")
        with self.assertRaises(AnalysisError):
            ledger.reserve(ledger.authenticate(ledger.issue_guest()), str(uuid4()), "d")
        restarted = Ledger(self.path, pilot_budget=50_000_000)
        self.assertEqual(RESERVE_MICRO_USD, restarted.report()["pilot_cap_micro_usd"])

    def test_one_dollar_verification_cap_covers_parallel_unknown_and_restart(self):
        self.ledger.limit_verification(1_000_000)
        owners = [self.ledger.authenticate(self.ledger.issue_guest()) for _ in range(8)]
        def admit(owner):
            key = str(uuid4())
            try:
                self.ledger.reserve(owner, key, "d")
                self.ledger.finish(owner, key, "unknown", cost=None)
                return True
            except AnalysisError:
                return False
        with ThreadPoolExecutor(max_workers=8) as executor:
            self.assertEqual(3, sum(executor.map(admit, owners)))
        restarted = Ledger(self.path)
        restarted.limit_verification(1_000_000)
        restarted.limit_verification(2_000_000)  # cannot raise the persisted ceiling
        report = restarted.report()
        self.assertEqual(900_000, report["reserved_estimate_micro_usd"])
        self.assertEqual(1_000_000, report["verification_cap_micro_usd"])
        self.assertEqual(5_000_000, report["pilot_cap_micro_usd"])
        for owner in owners:
            restarted.delete_guest(owner)
        with self.assertRaises(AnalysisError):
            restarted.reserve(restarted.authenticate(restarted.issue_guest()), str(uuid4()), "d")

    def test_verification_baseline_does_not_reset_or_ignore_pending_calls(self):
        key = str(uuid4())
        self.ledger.reserve(self.owner, key, "d")
        self.ledger.finish(self.owner, key, "saved", 100_000)
        pending = str(uuid4())
        self.ledger.reserve(self.owner, pending, "p")
        self.ledger.limit_verification(300_000)
        self.assertEqual(400_000, self.ledger.report()["verification_cap_micro_usd"])
        other = self.ledger.authenticate(self.ledger.issue_guest())
        with self.assertRaises(AnalysisError): self.ledger.reserve(other, str(uuid4()), "d")
        self.ledger.finish(self.owner, pending, "saved", 200_000)
        self.ledger.limit_verification(300_000)
        self.assertEqual(400_000, self.ledger.report()["verification_cap_micro_usd"])
        self.assertEqual({"limit_micro_usd": 300_000, "baseline_micro_usd": 100_000}, self.ledger.verification_limit())
        self.assertEqual("saved", self.ledger.reserve(self.owner, key, "d"))

    def test_stop_blocks_new_calls_but_serves_completed(self):
        key = str(uuid4())
        self.post(key)
        self.ledger.stop()
        self.assertEqual(200, self.post(key).status_code)
        self.assertEqual(503, self.post().status_code)
        self.assertEqual(1, self.calls)

    def test_receipt_reports_unknown_estimate_without_cross_user_leak(self):
        key = str(uuid4())
        self.provider.analyze = lambda _: (_ for _ in ()).throw(AnalysisError("provider_outcome_unknown", 504))
        self.post(key)
        response = self.client.get(f"/v1/analyses/{key}/receipt", headers={"Authorization": "Bearer " + self.token})
        self.assertEqual("reserved_estimate", response.json()["cost_kind"])
        self.assertEqual(RESERVE_MICRO_USD, response.json()["cost_micro_usd"])
        with patch.dict(os.environ, {"PILOT_INVITE_TOKEN": "", "PILOT_INVITE_TOKENS": json.dumps(["test-invite" * 4, "other-invite" * 4])}):
            client = TestClient(create_app(self.ledger, self.provider))
            other = client.post("/v1/guests", headers={"X-Pilot-Invite": "other-invite" * 4}).json()["token"]
        self.client = client
        self.assertEqual(404, self.client.get(f"/v1/analyses/{key}/receipt", headers={"Authorization": "Bearer " + other}).status_code)

    def test_removed_invitation_invalidates_existing_token_before_provider(self):
        self.client = TestClient(create_app(self.ledger, self.provider, "replacement-invite"))
        self.assertEqual(401, self.post().status_code)
        self.assertEqual(0, self.calls)
        self.assertEqual(401, self.client.get("/v1/pilot/config", headers={"Authorization": "Bearer " + self.token}).status_code)

    def test_production_rejects_cached_demo_without_new_call(self):
        key = str(uuid4())
        self.assertEqual(200, self.post(key).status_code)
        with patch.dict(os.environ, {"TAMAQ_ENV": "production", "TAMAQ_PROVIDER": "openai", "OPENAI_API_KEY": ""}):
            self.client = TestClient(create_app(self.ledger, invite="test-invite"), base_url="https://pilot.example")
            response = self.post(key)
        self.assertEqual(503, response.status_code)
        self.assertEqual("demo_result_forbidden", response.json()["error"]["code"])
        self.assertEqual(1, self.calls)

    def test_missing_real_key_is_clear_and_free(self):
        with patch.dict(os.environ, {"TAMAQ_ENV": "development", "TAMAQ_PROVIDER": "openai", "OPENAI_API_KEY": "", "OPENAI_MODEL": "gpt-6-luna"}):
            self.client = TestClient(create_app(self.ledger, invite="test-invite"))
            response = self.post()
        self.assertEqual(503, response.status_code)
        self.assertEqual("provider_not_configured", response.json()["error"]["code"])
        self.assertEqual(0, self.ledger.report()["charged_micro_usd"])
        self.assertEqual(0, self.ledger.report()["reserved_estimate_micro_usd"])

    def test_production_https_and_configuration(self):
        with patch.dict(os.environ, {"TAMAQ_ENV": "production", "TAMAQ_PROVIDER": "openai",
                                     "PILOT_INVITE_TOKENS": json.dumps(["x" * 40]), "OPENAI_API_KEY": ""}):
            app = create_app(self.ledger)
            client = TestClient(app)
            self.assertEqual(200, client.get("/health").status_code)
            self.assertEqual(400, client.post("/v1/guests").status_code)
            secure = TestClient(app, base_url="https://pilot.example")
            response = secure.post("/v1/guests", headers={"X-Pilot-Invite": "x" * 40})
            self.assertEqual(200, response.status_code)
            self.assertEqual("no-store", response.headers["cache-control"])
            self.assertEqual(404, secure.get("/openapi.json").status_code)
        with patch.dict(os.environ, {"PILOT_INVITE_TOKENS": "[]"}):
            with self.assertRaises(ValueError): invitations("production")

    def test_old_ledger_additive_migration_preserves_result_and_spend(self):
        path = os.path.join(self.directory.name, "v1.sqlite3")
        db = sqlite3.connect(path)
        now = int(time.time())
        db.executescript("CREATE TABLE guests(token_hash TEXT PRIMARY KEY,created INTEGER NOT NULL);"
            "CREATE TABLE requests(guest TEXT,key TEXT,digest TEXT,created INTEGER,status TEXT,reserved INTEGER,"
            "charged INTEGER DEFAULT 0,body TEXT,PRIMARY KEY(guest,key));"
            "CREATE TABLE budget(id INTEGER PRIMARY KEY,deleted_spend INTEGER NOT NULL);")
        token = "legacy-secret-token"
        owner = Ledger.token_hash(token)
        db.execute("INSERT INTO guests VALUES (?,?)", (owner, now))
        db.execute("INSERT INTO requests VALUES (?,?,?,?,?,?,?,?)", (owner, "old-key", "d", now, "Finished", 0, 123, "saved"))
        db.execute("INSERT INTO budget VALUES (1,456)")
        db.commit(); db.close()
        migrated = Ledger(path)
        self.assertEqual(owner, migrated.authenticate(token))
        self.assertEqual("saved", migrated.reserve(owner, "old-key", "d"))
        self.assertEqual(579, migrated.report()["charged_micro_usd"])
        other = migrated.authenticate(migrated.issue_guest())
        with self.assertRaises(AnalysisError): migrated.reserve(other, "old-key", "d")

    def test_deletion_during_call_keeps_charge_without_restoring_food(self):
        key = str(uuid4())
        self.ledger.reserve(self.owner, key, "d")
        self.ledger.delete_guest(self.owner)
        self.ledger.finish(self.owner, key, "private food", 100, {"input_tokens": 100})
        with self.ledger.connection() as db:
            row = db.execute("SELECT * FROM requests").fetchone()
        self.assertIsNone(row["body"])
        self.assertIsNone(row["usage"])
        self.assertEqual(100, row["charged"])
        self.assertEqual(0, row["reserved"])

    def test_sdk_transport_errors_no_retries(self):
        provider = OpenAIProvider("unit-test-only", "gpt-6-luna")
        request = httpx.Request("POST", "https://api.openai.com/v1/responses")
        for error, code, charged in [
            (APITimeoutError(request=request), "provider_outcome_unknown", None),
            (APIConnectionError(request=request), "provider_outcome_unknown", None),
            (RateLimitError("limited", response=httpx.Response(429, request=request), body=None), "provider_rate_limit", 0),
            (APIStatusError("missing", response=httpx.Response(404, request=request), body=None), "model_unavailable", 0),
            (APIStatusError("server", response=httpx.Response(500, request=request), body=None), "provider_unavailable", None),
        ]:
            with patch.object(provider.client.responses, "create", side_effect=error) as create:
                with self.assertRaises(AnalysisError) as failure: provider.analyze(image())
                self.assertEqual(code, failure.exception.code)
                self.assertEqual(charged, getattr(failure.exception, "cost_micro_usd", None))
                self.assertEqual(1, create.call_count)
        self.assertEqual(0, provider.client.max_retries)

    def test_real_sdk_no_food_and_missing_usage(self):
        provider = OpenAIProvider("unit-test-only", "gpt-6-luna")
        result = FoodResult(status="no_food", name="Нет еды", components=[], explanation=None, question=None)
        response = SimpleNamespace(status="completed", output=[], output_text=result.model_dump_json(),
            usage=SimpleNamespace(input_tokens=100, output_tokens=20, input_tokens_details=None))
        with patch.object(provider.client.responses, "create", return_value=response):
            self.assertEqual("no_food", provider.analyze(image()).result.status)
        response.usage = None
        with patch.object(provider.client.responses, "create", return_value=response):
            with self.assertRaises(AnalysisError) as failure: provider.analyze(image())
            self.assertEqual("usage_unknown", failure.exception.code)

    def test_money_config_rejects_invalid_values(self):
        for value in ("NaN", "-1", "0", "0.0000001"):
            with patch.dict(os.environ, {"TAMAQ_PILOT_BUDGET_USD": value}):
                with self.assertRaises(ValueError): money("TAMAQ_PILOT_BUDGET_USD", "5")

    def test_long_context_billing_and_unexpected_tier(self):
        provider = OpenAIProvider("unit-test-only", "gpt-6-luna")
        result = self.provider.analyze(image()).result
        response = SimpleNamespace(status="completed", output=[], output_text=result.model_dump_json(),
            usage=SimpleNamespace(input_tokens=300000, output_tokens=100, input_tokens_details=None))
        with patch.object(provider.client.responses, "create", return_value=response):
            self.assertEqual(60075, provider.analyze(image()).cost_micro_usd)
        response.service_tier = "fast"
        with patch.object(provider.client.responses, "create", return_value=response):
            with self.assertRaises(AnalysisError) as failure: provider.analyze(image())
            self.assertEqual("usage_unknown", failure.exception.code)
            self.assertIsNone(getattr(failure.exception, "cost_micro_usd", None))

    def test_settlement_cannot_be_overwritten_by_duplicate_completion(self):
        key = str(uuid4())
        self.ledger.reserve(self.owner, key, "d")
        self.ledger.finish(self.owner, key, "first", 100)
        self.ledger.finish(self.owner, key, "second", 1)
        self.assertEqual("first", self.ledger.reserve(self.owner, key, "d"))
        self.assertEqual(100, self.ledger.report()["charged_micro_usd"])
