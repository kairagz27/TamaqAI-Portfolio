import json
import time
from contextlib import contextmanager
import psycopg
from decimal import Decimal
from ..contracts import AnalysisError
from ..db import Database, USAGE_LOCK
from ..ledger import Ledger
from . import Actor

class Record(dict):
    def __init__(self,names,values):
        super().__init__(zip(names,values)); self.values_in_order=tuple(values)
    def __getitem__(self,key):
        return self.values_in_order[key] if isinstance(key,int) else super().__getitem__(key)

def records(cursor):
    names=[column.name for column in cursor.description or ()]
    return lambda values: Record(names,values)

class SqlConnection:
    def __init__(self,db): self.db=db
    def execute(self,sql,parameters=()):
        if sql == "BEGIN IMMEDIATE": return self.db.execute("SELECT pg_advisory_xact_lock(%s)",(USAGE_LOCK,))
        if sql == "COMMIT": self.db.commit(); return None
        if sql.startswith("PRAGMA"): return None
        return self.db.execute(sql.replace("?","%s"),parameters)

class PostgresLedger(Ledger):
    """Keep the existing reservation/receipt arithmetic; transactions serialize across processes.

    An account has no fresh usage principal. It inherits the existing invite principal only
    after authenticated linkage. Guest tokens are revoked atomically when that happens.
    """
    def __init__(self,database=None,budget=50_000_000,pilot_budget=5_000_000,session_seconds=604800):
        if min(budget,pilot_budget,session_seconds)<=0: raise ValueError("Invalid limits")
        self.database=database or Database(); self.database.require_current(); self.session_seconds=session_seconds
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("INSERT INTO budget(id,deleted_spend,global_cap,pilot_cap) VALUES(1,0,?,?) ON CONFLICT DO NOTHING",(budget,pilot_budget))
            db.execute("UPDATE budget SET global_cap=LEAST(global_cap,?),pilot_cap=LEAST(pilot_cap,?) WHERE id=1",(budget,pilot_budget))

    @contextmanager
    def connection(self):
        with psycopg.connect(self.database.url,row_factory=records,connect_timeout=5,
                options="-c statement_timeout=10000 -c lock_timeout=10000") as db:
            yield SqlConnection(db)

    def sync_invitations(self,principals):
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("UPDATE invitations SET enabled=FALSE")
            for principal in principals:
                db.execute("INSERT INTO invitations(principal,enabled) VALUES(?,TRUE) ON CONFLICT(principal) DO UPDATE SET enabled=TRUE",(principal,))

    def _before_guest_issue(self,db,principal):
        row=db.execute("SELECT enabled,user_id FROM invitations WHERE principal=?",(principal,)).fetchone()
        if not row or not row["enabled"]: raise AnalysisError("invite_required",401)
        if row["user_id"] is not None: raise AnalysisError("invitation_already_linked",409)

    def authenticate(self,token):
        principal=super().authenticate(token)
        with self.connection() as db: self._before_guest_issue(db,principal)
        return principal

    def _owner(self,db,actor):
        if not isinstance(actor,Actor): return actor # trusted operator/unit interfaces only
        now=int(time.time())
        invitation=db.execute("SELECT enabled,user_id FROM invitations WHERE principal=?",(actor.principal,)).fetchone()
        if not invitation or not invitation["enabled"]: raise AnalysisError("invite_required",403)
        if actor.user_id:
            session=db.execute("SELECT s.id FROM auth_sessions s JOIN users u ON u.id=s.user_id WHERE s.id=? AND u.id=? AND s.revoked_at IS NULL AND s.expires_at>? AND u.deleted_at IS NULL AND (u.verified_at IS NOT NULL OR u.phone_verified_at IS NOT NULL)",(actor.session_id,actor.user_id,now)).fetchone()
            if not session or str(invitation["user_id"])!=actor.user_id: raise AnalysisError("unauthorized",401)
        else:
            session=db.execute("SELECT token_hash FROM guests WHERE token_hash=? AND principal=? AND expires_at>?",(actor.guest_hash,actor.principal,now)).fetchone()
            if not session or invitation["user_id"] is not None: raise AnalysisError("unauthorized",401)
        return actor.principal

    def finish(self,owner,*args,**kwargs):
        return super().finish(owner.principal if isinstance(owner,Actor) else owner,*args,**kwargs)

    def report(self):
        return {key:int(value) if isinstance(value,Decimal) else value for key,value in super().report().items()}

    def delete_guest(self,actor):
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            principal=self._owner(db,actor)
            db.execute("UPDATE requests SET body=NULL,usage=NULL,status='Deleted' WHERE guest=?",(principal,))
            db.execute("DELETE FROM guests WHERE principal=?",(principal,))
