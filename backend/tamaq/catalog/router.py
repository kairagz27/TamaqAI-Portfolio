"""Public shared facts; private facts always derive the owner from a live session."""
from fastapi import APIRouter, Query, Request

from ..contracts import AnalysisError
from .models import Calculation, Entry, EntryInput, Match
from .service import Catalog


def router(database, auth, guest):
    api = APIRouter(prefix="/v1/catalog", tags=["catalog"])
    catalog = Catalog(database)

    def owner(request):
        authorization = request.headers.get("authorization")
        if not authorization:
            return None
        if not authorization.startswith("Bearer "):
            raise AnalysisError("unauthorized", 401)
        token = authorization[7:]
        if token.count(".") == 2:
            return auth.identity(token)["id"]
        guest(authorization)
        return None

    @api.get("/cities")
    def cities():
        return catalog.cities()

    @api.get("/restaurants")
    def restaurants(city: str | None = Query(default=None, max_length=100)):
        return catalog.restaurants(city)

    @api.get("/search")
    def search(request: Request, q: str = Query(default="", max_length=200),
               kind: str | None = Query(default=None, pattern="^(product|dish)$"),
               city: str | None = Query(default=None, max_length=100),
               chain: str | None = Query(default=None, max_length=120),
               branch_scope: str | None = Query(default=None, max_length=300),
               barcode: str | None = Query(default=None, max_length=14),
               limit: int = Query(default=30, ge=1, le=100)):
        return catalog.search(q, kind, city, chain, barcode, limit, owner(request), branch_scope)

    @api.get("/entries/{identifier}", response_model=Entry)
    def entry(identifier: str, request: Request):
        if len(identifier) > 200:
            raise AnalysisError("catalog_entry_not_found", 404)
        return catalog.entry(identifier, owner(request))

    @api.post("/calculate")
    def calculate(body: Calculation, request: Request):
        return catalog.calculate(body, owner(request))

    @api.post("/match")
    def match(body: Match, request: Request):
        return catalog.match(body, owner(request))

    @api.post("/personal", response_model=Entry, status_code=201)
    def personal(body: EntryInput, request: Request):
        user = owner(request)
        if user is None:
            raise AnalysisError("unauthorized", 401)
        # The client cannot publish an official/shared record or choose its owner.
        document = body.model_copy(update={"source": body.source.model_copy(update={
            "status": "user", "namespace": "personal", "license": "Private user supplied data",
        })})
        catalog.import_entries([document], dry_run=False, owner=user)
        return catalog.owned_entry(document.id, user)

    return api
