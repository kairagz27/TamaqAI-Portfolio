import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from uuid import uuid4

import httpx
from PIL import Image

from tamaq.dev_fake import FakeProvider
from tamaq.pilot import PilotClient, main, read_photo, save_private


class SmokeTest(unittest.TestCase):
    def test_local_openai_still_requires_real_mode_budget_and_localhost(self):
        original_client = httpx.Client
        for demo in (True, False):
            transport = httpx.MockTransport(lambda request: httpx.Response(200, json={"is_demo": demo,
                "configured": True, "model": "gpt-6-luna", "verification_budget": {"limit_micro_usd": 1_000_000},
                "settings": {"sdk_retries": 0}}))
            with patch.dict(os.environ, {"TAMAQ_PILOT_TOKEN": "test-only"}), patch("tamaq.pilot.httpx.Client",
                side_effect=lambda **kwargs: original_client(transport=transport, **kwargs)):
                if demo:
                    with self.assertRaises(ValueError): PilotClient("http://127.0.0.1:8000", local_openai=True)
                else:
                    self.assertFalse(PilotClient("http://127.0.0.1:8000", local_openai=True).config["is_demo"])
                with self.assertRaises(ValueError): PilotClient("http://example.com", local_openai=True)
                with self.assertRaises(ValueError): PilotClient("http://127.0.0.1:8000", True, True)

    def test_paid_client_requires_durable_one_dollar_ceiling_before_post(self):
        original_client = httpx.Client
        for limit, retries in [(None, 0), ({"limit_micro_usd": 2_000_000}, 0),
                               ({"limit_micro_usd": 1_000_000}, 1)]:
            requests = []
            def metadata(request):
                requests.append(request.method)
                return httpx.Response(200, json={"is_demo": False, "configured": True,
                    "model": "gpt-6-luna", "verification_budget": limit, "settings": {"sdk_retries": retries}})
            with patch.dict(os.environ, {"TAMAQ_PILOT_TOKEN": "test-only"}), patch("tamaq.pilot.httpx.Client",
                side_effect=lambda **kwargs: original_client(transport=httpx.MockTransport(metadata), **kwargs)):
                with self.assertRaises(ValueError): PilotClient("https://pilot.example")
            self.assertEqual(["GET"], requests)

    def test_paid_client_accepts_armed_ceiling_without_model_call(self):
        original_client = httpx.Client
        transport = httpx.MockTransport(lambda request: httpx.Response(200, json={"is_demo": False,
            "configured": True, "model": "gpt-6-luna", "verification_budget": {"limit_micro_usd": 1_000_000},
            "settings": {"sdk_retries": 0}}))
        with patch.dict(os.environ, {"TAMAQ_PILOT_TOKEN": "test-only"}), patch("tamaq.pilot.httpx.Client",
            side_effect=lambda **kwargs: original_client(transport=transport, **kwargs)):
            self.assertEqual("gpt-6-luna", PilotClient("https://pilot.example").config["model"])

    def test_saved_smoke_does_not_post_but_explicit_verification_does(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            Image.new("RGB", (32, 32)).save(root / "fixture.jpg")
            key = str(uuid4())
            config = {"synthetic": True}
            body = {"analysis_id": key, "schema_version": "1", "prompt_version": "test",
                    "model": "FAKE-DEVELOPMENT", "is_demo": True,
                    "result": FakeProvider().analyze(read_photo(root / "fixture.jpg")).result.model_dump()}
            receipt = {"analysis_id": key, "cost_micro_usd": 0, "cost_kind": "usage_priced", "status": "Finished", "usage": None}
            outcome = {"http_status": 200, "response": body, "receipt": receipt}
            import hashlib
            save_private(root / "run.json", {"analysis_id": key, "config": config,
                "digest": hashlib.sha256(read_photo(root / "fixture.jpg")).hexdigest(),
                "url": "http://127.0.0.1:8000", "outcome": outcome})
            client = Mock(config=config)
            client.get.return_value = receipt
            client.analyze.return_value = outcome
            args = ["smoke", "--url", "http://127.0.0.1:8000", "--image", str(root / "fixture.jpg"),
                    "--run", str(root / "run.json"), "--local-fake"]
            with patch("tamaq.pilot.PilotClient", return_value=client):
                self.assertEqual(0, main(args))
                client.analyze.assert_not_called()
                self.assertEqual(0, main(args + ["--verify-idempotency"]))
            client.analyze.assert_called_once_with(read_photo(root / "fixture.jpg"), key)
            self.assertTrue(json.loads((root / "run.json").read_text(encoding="utf-8"))["idempotency_check"]["receipt_equal"])


if __name__ == "__main__":
    unittest.main()
