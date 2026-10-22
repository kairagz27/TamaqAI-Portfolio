import io
import json
import os
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import patch
from uuid import uuid4

from fastapi.testclient import TestClient
from PIL import Image

from tamaq.app import create_app, normalize_image
from tamaq.contracts import AnalysisError, Component, FoodResult
from tamaq.dev_fake import FakeProvider
from tamaq.ledger import Ledger
from tamaq.provider import OpenAIProvider, RESERVE_MICRO_USD


def image(color="red"):
    stream = io.BytesIO()
    Image.new("RGB", (32, 32), color).save(stream, "JPEG")
    return stream.getvalue()


class BackendTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.directory.name, "test.sqlite3")
        self.environment = patch.dict(os.environ, {"TAMAQ_ENV": "development", "TAMAQ_PROVIDER": "fake"})
        self.environment.start()
        self.ledger = Ledger(self.path)
        self.provider = FakeProvider()
        self.calls = 0
        analyze = self.provider.analyze
        def counted(jpeg):
            self.calls += 1
            return analyze(jpeg)
        self.provider.analyze = counted
        self.client = TestClient(create_app(self.ledger, self.provider, "test-invite"))
        self.token = self.client.post("/v1/guests", headers={"X-Pilot-Invite": "test-invite"}).json()["token"]
        self.owner = self.ledger.authenticate(self.token)

    def tearDown(self):
        self.environment.stop()
        self.directory.cleanup()

    def post(self, key=None, photo=None, token=None):
        return self.client.post("/v1/analyses", content=photo or image(), headers={
            "Content-Type": "image/jpeg", "Authorization": "Bearer " + (token or self.token),
            "Idempotency-Key": key or str(uuid4())})

    def test_idempotency_conflict_and_restart(self):
        key = str(uuid4())
        first = self.post(key)
        self.assertEqual(200, first.status_code)
        self.assertTrue(first.json()["is_demo"])
        self.assertEqual(first.json(), self.post(key).json())
        self.assertEqual(1, self.calls)
        self.assertEqual(409, self.post(key, image("blue")).status_code)
        self.client = TestClient(create_app(Ledger(self.path), self.provider, "test-invite"))
        self.assertEqual(first.json(), self.post(key).json())
        self.assertEqual(1, self.calls)

    def test_auth_type_size_and_corrupt_image(self):
        self.assertEqual(401, self.post(token="invalid").status_code)
        self.assertEqual(415, self.post(photo=b"not an image").status_code)
        self.assertEqual(413, self.post(photo=b"x" * (2 * 1024 * 1024 + 1)).status_code)
        self.assertEqual(0, self.calls)
        self.assertEqual(401, self.client.post("/v1/guests").status_code)

    def test_rate_limits_survive_restart(self):
        self.assertEqual(200, self.post().status_code)
        self.assertEqual(200, self.post().status_code)
        self.client = TestClient(create_app(Ledger(self.path), self.provider, "test-invite"))
        self.assertEqual(429, self.post().status_code)
        self.assertEqual(2, self.calls)

    def test_atomic_budget_reservation(self):
        limited = Ledger(self.path, budget=RESERVE_MICRO_USD)
        def reserve(_):
            try:
                limited.reserve(self.owner, str(uuid4()), "digest")
                return True
            except AnalysisError:
                return False
        with ThreadPoolExecutor(max_workers=2) as pool:
            self.assertEqual(1, sum(pool.map(reserve, range(2))))
        restarted = Ledger(self.path, budget=RESERVE_MICRO_USD)
        other = restarted.authenticate(restarted.issue_guest())
        with self.assertRaises(AnalysisError): restarted.reserve(other, str(uuid4()), "d")

    def test_unknown_outcome_never_calls_again(self):
        key = str(uuid4())
        def timeout(_): raise AnalysisError("provider_outcome_unknown", 504)
        self.provider.analyze = timeout
        self.assertEqual(504, self.post(key).status_code)
        self.provider.analyze = lambda _: self.fail("repeated paid call")
        self.assertEqual(504, self.post(key).status_code)
        with self.ledger.connection() as db:
            self.assertEqual(RESERVE_MICRO_USD, db.execute("SELECT reserved FROM requests").fetchone()[0])

    def test_crash_reservation_never_retries(self):
        key = str(uuid4())
        import hashlib
        self.ledger.reserve(self.owner, key, hashlib.sha256(image()).hexdigest())
        self.assertEqual(409, self.post(key).status_code)
        self.assertEqual(0, self.calls)

    def test_fake_forbidden_in_production(self):
        with patch.dict(os.environ, {"TAMAQ_ENV": "production"}):
            with self.assertRaises(RuntimeError): create_app(self.ledger, self.provider, "invite")

    def test_semantic_validation(self):
        result = self.provider.analyze(image()).result
        for value in (float("nan"), float("inf"), -1.0):
            invalid = result.model_copy(deep=True)
            invalid.components[0].fat = value
            with self.assertRaises(ValueError): invalid.validate_semantics()
        with self.assertRaises(ValueError): result.model_copy(update={"status": "no_food"}).validate_semantics()
        with self.assertRaises(ValueError): result.model_copy(update={"status": "ambiguous"}).validate_semantics()

    def test_image_orientation_exif_and_decoded_size(self):
        photo = Image.new("RGB", (1600, 800), "red")
        exif = Image.Exif()
        exif[274] = 6
        exif[271] = "test-camera"
        buffer = io.BytesIO()
        photo.save(buffer, "JPEG", exif=exif)
        with Image.open(io.BytesIO(normalize_image(buffer.getvalue()))) as normalized:
            self.assertEqual((512, 1024), normalized.size)
            self.assertIsNone(normalized.getexif().get(271))
        buffer = io.BytesIO()
        huge = Image.new("RGB", (5000, 3500))
        huge.save(buffer, "JPEG")
        huge.close()
        with self.assertRaises(AnalysisError): normalize_image(buffer.getvalue())

    def test_delete_guest_keeps_budget_spent(self):
        self.ledger.reserve(self.owner, str(uuid4()), "digest")
        self.assertEqual(200, self.client.delete("/v1/guest", headers={"Authorization": "Bearer " + self.token}).status_code)
        self.assertEqual(401, self.post().status_code)
        with self.ledger.connection() as db:
            self.assertEqual(RESERVE_MICRO_USD, db.execute("SELECT SUM(reserved) FROM requests").fetchone()[0])
            self.assertIsNone(db.execute("SELECT body FROM requests").fetchone()[0])
        self.ledger.finish(self.owner, "deleted-in-flight", "private result", 1)

    def test_daily_quota_and_known_usage_settlement(self):
        for i in range(10):
            with patch("tamaq.ledger.time.time", return_value=86400 * 20000 + i * 61):
                key = str(uuid4())
                self.ledger.reserve(self.owner, key, "digest")
                self.ledger.finish(self.owner, key, "result", 10, {"input_tokens": 100})
        with patch("tamaq.ledger.time.time", return_value=86400 * 20000 + 10 * 61):
            with self.assertRaises(AnalysisError) as error: self.ledger.reserve(self.owner, str(uuid4()), "digest")
            self.assertEqual("daily_quota", error.exception.code)
        with self.ledger.connection() as db:
            self.assertEqual(100, db.execute("SELECT SUM(charged) FROM requests").fetchone()[0])
            self.assertEqual(0, db.execute("SELECT SUM(reserved) FROM requests").fetchone()[0])
            self.assertEqual({"input_tokens": 100}, json.loads(db.execute("SELECT usage FROM requests").fetchone()[0]))

    def test_sdk_parameters_and_usage_without_network(self):
        provider = OpenAIProvider("unit-test-only", "gpt-6-luna")
        result = self.provider.analyze(image()).result
        response = SimpleNamespace(status="completed", output=[], output_text=result.model_dump_json(),
            usage=SimpleNamespace(input_tokens=1000, output_tokens=100, input_tokens_details=SimpleNamespace(cached_tokens=100)))
        with patch.object(provider.client.responses, "create", return_value=response) as create:
            output = provider.analyze(image())
            self.assertEqual(141, output.cost_micro_usd)
            args = create.call_args.kwargs
            self.assertEqual("gpt-6-luna", args["model"])
            self.assertTrue(args["text"]["format"]["strict"])
            self.assertFalse(args["store"])
            self.assertEqual("input_image", args["input"][1]["content"][0]["type"])
            self.assertEqual("default", args["service_tier"])
        response.usage.input_tokens_details.cache_write_tokens = 200
        with patch.object(provider.client.responses, "create", return_value=response):
            self.assertEqual(146, provider.analyze(image()).cost_micro_usd)
        with self.assertRaises(AnalysisError): OpenAIProvider("unit-test-only", "substituted-model")

    def test_retention_removes_results_without_repeating_or_resetting_spend(self):
        key = str(uuid4())
        self.ledger.reserve(self.owner, key, "digest")
        self.ledger.finish(self.owner, key, "private result", 10)
        with self.ledger.connection() as db:
            db.execute("UPDATE requests SET created=0")
        self.ledger.prune()
        with self.assertRaises(AnalysisError): self.ledger.reserve(self.owner, key, "digest")
        with self.ledger.connection() as db:
            self.assertEqual(10, db.execute("SELECT charged FROM requests").fetchone()[0])
            self.assertIsNone(db.execute("SELECT body FROM requests").fetchone()[0])

    def test_unexpected_price_preserves_actual_cost_and_halts(self):
        key = str(uuid4())
        self.ledger.reserve(self.owner, key, "digest")
        self.ledger.finish(self.owner, key, "result", RESERVE_MICRO_USD + 1)
        with self.ledger.connection() as db:
            self.assertEqual(RESERVE_MICRO_USD + 1, db.execute("SELECT charged FROM requests").fetchone()[0])
        other = self.ledger.authenticate(self.ledger.issue_guest())
        with self.assertRaises(AnalysisError): self.ledger.reserve(other, str(uuid4()), "digest")

    def test_refusal_and_incomplete_keep_known_cost(self):
        provider = OpenAIProvider("unit-test-only", "gpt-6-luna")
        usage = SimpleNamespace(input_tokens=1000, output_tokens=100, input_tokens_details=SimpleNamespace(cached_tokens=0))
        for status, output, code in [
            ("incomplete", [], "incomplete_response"),
            ("completed", [SimpleNamespace(content=[SimpleNamespace(type="refusal")])], "refusal"),
            ("completed", [], "invalid_provider_result"),
        ]:
            with patch.object(provider.client.responses, "create", return_value=SimpleNamespace(status=status, output=output, output_text="{}", usage=usage)):
                with self.assertRaises(AnalysisError) as error: provider.analyze(image())
                self.assertEqual(code, error.exception.code)
                self.assertEqual(150, error.exception.cost_micro_usd)


if __name__ == "__main__": unittest.main()
