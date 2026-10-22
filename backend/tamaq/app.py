import hashlib
import io
import json
import os
import asyncio
import threading
from contextlib import asynccontextmanager, suppress
from pathlib import Path
from uuid import UUID

from fastapi import FastAPI, Header, Request
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from PIL import Image, ImageOps, UnidentifiedImageError
from starlette.concurrency import run_in_threadpool

from .contracts import AnalysisError, AnalysisResponse
from .ledger import Ledger
from .provider import OpenAIProvider, PROMPT, PROMPT_VERSION, SCHEMA_VERSION, REQUEST_SETTINGS, PRICING_VERSION
from .settings import invitations, money

MAX_BYTES = 2 * 1024 * 1024
MAX_PIXELS = 16_000_000


def normalize_image(raw: bytes) -> bytes:
    try:
        with Image.open(io.BytesIO(raw)) as image:
            if image.format not in ("JPEG", "PNG") or image.width * image.height > MAX_PIXELS:
                raise AnalysisError("unsupported_image", 415)
            image.verify()
        with Image.open(io.BytesIO(raw)) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            image.thumbnail((1024, 1024))
            output = io.BytesIO()
            image.save(output, "JPEG", quality=85)  # no EXIF is copied
            return output.getvalue()
    except (UnidentifiedImageError, OSError, SyntaxError, ValueError, Image.DecompressionBombError):
        raise AnalysisError("invalid_image", 415) from None


def create_app(ledger=None, provider=None, invite=None, auth=None):
    environment = os.environ.get("TAMAQ_ENV", "production")
    mode = os.environ.get("TAMAQ_PROVIDER", "openai")
    if environment not in ("production", "development") or mode not in ("openai", "fake"):
        raise RuntimeError("Invalid environment/provider")
    if mode == "fake" and environment != "development":
        raise RuntimeError("Fake provider is forbidden outside development")
    if provider is None and mode == "fake":
        from .dev_fake import FakeProvider
        provider = FakeProvider()
    if provider is not None and getattr(provider, "is_demo", False) and environment != "development":
        raise RuntimeError("Fake provider is forbidden outside development")
    invite_hashes = {Ledger.token_hash(value) for value in invitations(environment, invite)}
    # Bound peak decoded-image memory on the 512 MiB pilot instance. The gate runs
    # in the worker thread; waiting must not block the event loop.
    image_slots = threading.BoundedSemaphore(1)
    def normalize_bounded(raw):
        with image_slots:
            return normalize_image(raw)
    if ledger is None:
        if os.environ.get('TAMAQ_DATABASE_URL'):
            from .usage.postgres import PostgresLedger
            ledger = PostgresLedger(budget=money('TAMAQ_GLOBAL_BUDGET_USD','50'),pilot_budget=money('TAMAQ_PILOT_BUDGET_USD','5'))
        elif environment == "production" and not os.environ.get("TAMAQ_DATABASE"):
            raise RuntimeError("Production requires an explicit persistent TAMAQ_DATABASE path")
        else:
            path = Path(os.environ.get("TAMAQ_DATABASE", "var/tamaq.sqlite3"))
            path.parent.mkdir(parents=True, exist_ok=True)
            ledger = Ledger(str(path), budget=money("TAMAQ_GLOBAL_BUDGET_USD", "50"),
                        pilot_budget=money("TAMAQ_PILOT_BUDGET_USD", "5"),
                        session_seconds=int(os.environ.get("TAMAQ_SESSION_SECONDS", "604800")))
    if hasattr(ledger, 'sync_invitations'):
        ledger.sync_invitations(invite_hashes)
        if auth is None:
            from .auth.service import Auth
            from .auth.email import SMTPDelivery
            auth = Auth(ledger.database, SMTPDelivery(environment))
    if os.environ.get("TAMAQ_VERIFICATION_BUDGET_USD"):
        ledger.limit_verification(money("TAMAQ_VERIFICATION_BUDGET_USD", "1"))
    @asynccontextmanager
    async def lifespan(_):
        async def cleanup():
            while True:
                await run_in_threadpool(ledger.prune)
                await asyncio.sleep(3600)
        cleanup_task = asyncio.create_task(cleanup())
        try:
            yield
        finally:
            cleanup_task.cancel()
            with suppress(asyncio.CancelledError):
                await cleanup_task

    app = FastAPI(title="TamaqAI pilot API", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    @app.middleware("http")
    async def transport(request, call_next):
        if environment == "production" and request.url.scheme != "https" and request.url.path not in ("/health", "/ready"):
            return JSONResponse({"error": {"code": "https_required"}}, status_code=400)
        if request.url.path.startswith(('/v1/auth/','/v1/users/','/v1/nutrition/','/v1/plans','/v1/catalog/')):
            size=0; chunks=[]
            async for chunk in request.stream():
                size+=len(chunk)
                maximum=MAX_BYTES if request.url.path=='/v1/users/me/avatar' and request.method=='PUT' else 65536 if request.url.path.startswith('/v1/plans') else 16384
                if size>maximum: return JSONResponse({'error':{'code':'request_too_large'}},status_code=413)
                chunks.append(chunk)
            request._body=b''.join(chunks)
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        if request.url.scheme == "https":
            response.headers["Strict-Transport-Security"] = "max-age=31536000"
        return response

    @app.exception_handler(AnalysisError)
    async def analysis_error(_, error):
        return JSONResponse({"error": {"code": error.code}}, status_code=error.status_code)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_, error):
        # FastAPI's default includes submitted values (potential passwords/tokens).
        # Return only allowlisted field names and stable codes, never input/msg/ctx.
        fields = {}
        for issue in error.errors():
            location = issue.get('loc', ())
            if len(location) != 2 or location[0] != 'body':
                continue
            field = location[1]
            if field not in ('name', 'email', 'password', 'token'):
                continue
            kind = issue.get('type')
            fields[field] = {'missing': 'required', 'string_too_short': 'too_short',
                             'string_too_long': 'too_long'}.get(kind, 'invalid')
        return JSONResponse({'error':{'code':'invalid_input','fields':fields}},status_code=422)

    if auth:
        from .auth.router import router as auth_router
        from .users.router import router as users_router
        auth_api,identity=auth_router(auth)
        app.include_router(auth_api); app.include_router(users_router(auth,identity))
        from .nutrition.router import router as nutrition_router
        from .plans.router import router as plans_router
        app.include_router(nutrition_router(auth,identity))
        app.include_router(plans_router(auth,identity,ledger,provider,environment))

    def guest(authorization):
        if not authorization or not authorization.startswith("Bearer "):
            raise AnalysisError("unauthorized", 401)
        token=authorization[7:]
        if auth and token.count('.')==2: return auth.analysis_actor(auth.identity(token))
        principal = ledger.authenticate(token)
        if principal not in invite_hashes:
            # A token must not outlive removal of its invitation. This also
            # prevents development sessions from authorizing production calls.
            raise AnalysisError("unauthorized", 401)
        if auth:
            from .usage import Actor
            return Actor(principal,guest_hash=Ledger.token_hash(token))
        return principal

    if auth and hasattr(ledger, 'database'):
        from .catalog.router import router as catalog_router
        app.include_router(catalog_router(ledger.database, auth, guest))

    @app.get("/health")
    def health():
        with ledger.connection() as db:
            db.execute("SELECT 1").fetchone()
        return {"status": "ok", "demo": mode == "fake"}

    @app.get('/ready')
    def ready():
        try:
            with ledger.connection() as db:
                db.execute('SELECT 1').fetchone()
            if hasattr(ledger, 'database'):
                ledger.database.require_current()
            root = os.environ.get('TAMAQ_FILES_ROOT')
            if root:
                import shutil, tempfile
                if shutil.disk_usage(root).free < 64 * 1024 * 1024:
                    raise OSError()
                with tempfile.TemporaryFile(dir=root) as probe:
                    probe.write(b'readiness'); probe.flush()
            return {'status': 'ready'}
        except Exception:
            return JSONResponse({'status': 'unavailable'}, status_code=503)

    @app.post("/v1/guests")
    def issue_guest(x_pilot_invite: str | None = Header(default=None)):
        import secrets
        candidate = Ledger.token_hash(x_pilot_invite or "")
        if not any(secrets.compare_digest(candidate, value) for value in invite_hashes):
            raise AnalysisError("invite_required", 401)
        return {"token": ledger.issue_guest(candidate), "expires_in": ledger.session_seconds}

    from .analysis.router import router as analysis_router
    app.include_router(analysis_router(ledger,provider,guest,environment,normalize_bounded))

    from .observability import RequestMetadata
    app.add_middleware(RequestMetadata)
    return app
