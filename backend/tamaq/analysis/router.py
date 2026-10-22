import os, json, hashlib
from uuid import UUID
from fastapi import APIRouter, Header, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool
from ..contracts import AnalysisError, AnalysisResponse
from ..provider import OpenAIProvider, PROMPT, PROMPT_VERSION, SCHEMA_VERSION, REQUEST_SETTINGS, PRICING_VERSION
MAX_BYTES=2*1024*1024

def router(ledger,provider,guest,environment,normalize_bounded):
    api=APIRouter()
    @api.get("/v1/pilot/config")
    def config(authorization: str | None = Header(default=None)):
        guest(authorization)
        from importlib.metadata import version
        return {"model": provider.model if provider else os.environ.get("OPENAI_MODEL", ""),
                "prompt_version": PROMPT_VERSION, "schema_version": SCHEMA_VERSION,
                "prompt_sha256": hashlib.sha256(PROMPT.encode()).hexdigest(), "sdk_version": version("openai"),
                "pricing_version": PRICING_VERSION, "settings": REQUEST_SETTINGS,
                "is_demo": bool(provider and provider.is_demo),
                "verification_budget": ledger.verification_limit(),
                "configured": bool(provider or os.environ.get("OPENAI_API_KEY")),
                "account_access_verified": False}

    @api.get("/v1/analyses/{key}/receipt")
    def receipt(key: str, authorization: str | None = Header(default=None)):
        owner = guest(authorization)
        with ledger.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            owner = ledger._owner(db,owner)
            row = db.execute("SELECT charged,reserved,usage,status FROM requests WHERE guest=? AND key=?",
                             (owner, key)).fetchone()
        if row is None:
            raise AnalysisError("analysis_not_found", 404)
        return {"analysis_id": key, "cost_micro_usd": row["charged"] if row["reserved"] == 0 else row["reserved"],
                "cost_kind": "usage_priced" if row["reserved"] == 0 else "reserved_estimate",
                "usage": json.loads(row["usage"]) if row["usage"] else None, "status": row["status"]}

    @api.delete("/v1/guest")
    def delete_guest(authorization: str | None = Header(default=None)):
        owner=guest(authorization)
        if getattr(owner,"user_id",None): raise AnalysisError("account_requires_user_deletion",403)
        ledger.delete_guest(owner)
        return {"deleted": True}

    @api.post("/v1/analyses")
    async def analyze(request: Request, authorization: str | None = Header(default=None),
                      idempotency_key: str | None = Header(default=None)):
        owner = await run_in_threadpool(guest, authorization)
        try:
            key = str(UUID(idempotency_key or ""))
        except ValueError:
            raise AnalysisError("invalid_idempotency_key", 400) from None
        if request.headers.get("content-type") not in ("image/jpeg", "image/png"):
            raise AnalysisError("unsupported_media_type", 415)
        if request.headers.get("content-length", "").isdigit() and int(request.headers["content-length"]) > MAX_BYTES:
            raise AnalysisError("image_too_large", 413)
        raw = bytearray()
        async for chunk in request.stream():
            raw.extend(chunk)
            if len(raw) > MAX_BYTES:
                raise AnalysisError("image_too_large", 413)
        if not raw:
            raise AnalysisError("empty_image", 400)
        jpeg = await run_in_threadpool(normalize_bounded, bytes(raw))
        # Digest original bytes so same key with changed content always conflicts.
        digest = hashlib.sha256(raw).hexdigest()
        prior = await run_in_threadpool(ledger.reserve, owner, key, digest)
        if prior is None:
            try:
                active_provider = provider
                if active_provider is None:
                    try:
                        active_provider = OpenAIProvider(os.environ.get("OPENAI_API_KEY", ""),
                                                         os.environ.get("OPENAI_MODEL", ""))
                    except AnalysisError as error:
                        error.cost_micro_usd = 0  # failed before any network call
                        raise
                output = await run_in_threadpool(active_provider.analyze, jpeg)
                output.result.validate_semantics()
                response = AnalysisResponse(analysis_id=key, schema_version=SCHEMA_VERSION,
                    prompt_version=PROMPT_VERSION, model=active_provider.model,
                    is_demo=active_provider.is_demo, result=output.result)
                prior = json.dumps({"status": 200, "body": response.model_dump()}, allow_nan=False)
                await run_in_threadpool(ledger.finish, owner, key, prior, output.cost_micro_usd, output.usage)
            except AnalysisError as error:
                prior = json.dumps({"status": error.status_code, "body": {"error": {"code": error.code}}})
                await run_in_threadpool(ledger.finish, owner, key, prior, getattr(error, "cost_micro_usd", None), getattr(error, "usage", None))
            except Exception:
                # No exception details, tokens, photos or PII are logged or returned.
                prior = json.dumps({"status": 502, "body": {"error": {"code": "analysis_outcome_unknown"}}})
                await run_in_threadpool(ledger.finish, owner, key, prior)
        cached = json.loads(prior)
        if environment == "production" and cached["body"].get("is_demo") is True:
            raise AnalysisError("demo_result_forbidden", 503)
        return JSONResponse(cached["body"], status_code=cached["status"])

    return api
