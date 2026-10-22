import hashlib
import secrets
import sqlite3
import time
import json
from contextlib import contextmanager

from .contracts import AnalysisError
from .provider import RESERVE_MICRO_USD


class Ledger:
    """Persistent single-pilot ledger; requests.guest is a stable invite principal.

    Additive migrations preserve existing v1 token principals and financial data.
    """
    def __init__(self, path: str, budget: int = 50_000_000, pilot_budget: int = 5_000_000,
                 session_seconds: int = 7 * 86400):
        if min(budget, pilot_budget, session_seconds) <= 0:
            raise ValueError("Limits must be positive")
        self.path, self.session_seconds = path, session_seconds
        with self.connection() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS guests (token_hash TEXT PRIMARY KEY, created INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS requests (
                    guest TEXT NOT NULL, key TEXT NOT NULL, digest TEXT NOT NULL,
                    created INTEGER NOT NULL, status TEXT NOT NULL, reserved INTEGER NOT NULL,
                    charged INTEGER NOT NULL DEFAULT 0, body TEXT,
                    PRIMARY KEY(guest, key));
                CREATE TABLE IF NOT EXISTS registration (created INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS budget (id INTEGER PRIMARY KEY CHECK(id=1), deleted_spend INTEGER NOT NULL);
                INSERT OR IGNORE INTO budget(id,deleted_spend) VALUES (1,0);
            """)
            self._column(db, "requests", "usage", "TEXT")
            self._column(db, "budget", "halted", "INTEGER NOT NULL DEFAULT 0")
            self._column(db, "budget", "global_cap", "INTEGER")
            self._column(db, "budget", "pilot_cap", "INTEGER")
            self._column(db, "budget", "verification_cap", "INTEGER")
            self._column(db, "budget", "verification_baseline", "INTEGER")
            self._column(db, "guests", "principal", "TEXT")
            self._column(db, "guests", "expires_at", "INTEGER")
            self._column(db, "registration", "principal", "TEXT")
            db.execute("UPDATE guests SET principal=token_hash WHERE principal IS NULL")
            db.execute("UPDATE guests SET expires_at=created+? WHERE expires_at IS NULL", (session_seconds,))
            # Configuration/restart can lower caps, never silently reset/increase them.
            db.execute("UPDATE budget SET global_cap=MIN(COALESCE(global_cap,?),?),"
                       "pilot_cap=MIN(COALESCE(pilot_cap,?),?) WHERE id=1",
                       (budget, budget, pilot_budget, pilot_budget))
            db.execute("CREATE INDEX IF NOT EXISTS request_quota ON requests(guest,created)")
            db.execute("CREATE INDEX IF NOT EXISTS request_key ON requests(key)")
        self.prune()

    @staticmethod
    def _column(db, table, name, definition):
        if name not in {row[1] for row in db.execute(f"PRAGMA table_info({table})")}:
            db.execute(f"ALTER TABLE {table} ADD COLUMN {name} {definition}")

    def prune(self):
        with self.connection() as db:
            db.execute("PRAGMA secure_delete=ON")
            db.execute("UPDATE requests SET body=NULL,status='Expired' WHERE created<? AND body IS NOT NULL",
                       (int(time.time()) - 30 * 86400,))
            db.execute("DELETE FROM registration WHERE created<?", (int(time.time()) - 86400,))
            db.execute("DELETE FROM guests WHERE expires_at<?", (int(time.time()) - 86400,))

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA busy_timeout=10000")
        try:
            yield db
        finally:
            db.close()

    @staticmethod
    def token_hash(token):
        return hashlib.sha256(token.encode()).hexdigest()

    def issue_guest(self, principal=None):
        now = int(time.time())
        token = secrets.token_urlsafe(32)
        principal = principal or self.token_hash(token)  # legacy/operator tests only
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            self._before_guest_issue(db, principal)
            if db.execute("SELECT COUNT(*) FROM registration WHERE created>?", (now - 3600,)).fetchone()[0] >= 20:
                raise AnalysisError("registration_limit", 429)
            if db.execute("SELECT COUNT(*) FROM registration WHERE principal=? AND created>?",
                          (principal, now - 3600)).fetchone()[0] >= 5:
                raise AnalysisError("registration_limit", 429)
            db.execute("INSERT INTO registration(created,principal) VALUES (?,?)", (now, principal))
            db.execute("INSERT INTO guests(token_hash,created,principal,expires_at) VALUES (?,?,?,?)",
                       (self.token_hash(token), now, principal, now + self.session_seconds))
            db.execute("COMMIT")
        return token

    def authenticate(self, token):
        with self.connection() as db:
            row = db.execute("SELECT principal,expires_at FROM guests WHERE token_hash=?",
                             (self.token_hash(token),)).fetchone()
            if row is None:
                raise AnalysisError("unauthorized", 401)
            if row["expires_at"] <= int(time.time()):
                raise AnalysisError("token_expired", 401)
            return row["principal"]

    def _before_guest_issue(self, db, principal):
        pass

    def _owner(self, db, owner):
        return owner

    def reserve(self, guest, key, digest):
        now = int(time.time())
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            guest = self._owner(db, guest)
            prior = db.execute("SELECT * FROM requests WHERE guest=? AND key=?", (guest, key)).fetchone()
            if prior:
                if prior["digest"] != digest:
                    raise AnalysisError("idempotency_conflict", 409)
                if prior["body"] is None:
                    raise AnalysisError("analysis_outcome_unknown", 409)
                return prior["body"]
            if db.execute("SELECT 1 FROM requests WHERE key=?", (key,)).fetchone():
                # Also guard old v1 requests when an expired legacy token is replaced
                # by a new invitation principal. Never expose another principal's body.
                raise AnalysisError("analysis_outcome_unknown", 409)
            if db.execute("SELECT COUNT(*) FROM requests WHERE guest=? AND created>?",
                          (guest, now - 60)).fetchone()[0] >= 2:
                raise AnalysisError("rate_limit", 429)
            day_start = now // 86400 * 86400
            if db.execute("SELECT COUNT(*) FROM requests WHERE guest=? AND created>=?",
                          (guest, day_start)).fetchone()[0] >= 10:
                raise AnalysisError("daily_quota", 429)
            state = db.execute("SELECT * FROM budget WHERE id=1").fetchone()
            used = db.execute("SELECT COALESCE(SUM(reserved+charged),0) FROM requests").fetchone()[0] + state["deleted_spend"]
            if state["halted"]:
                raise AnalysisError("pilot_stopped", 503)
            caps = [state["global_cap"], state["pilot_cap"]]
            if state["verification_cap"] is not None:
                caps.append(state["verification_cap"])
            if used + RESERVE_MICRO_USD > min(caps):
                raise AnalysisError("global_budget_exhausted", 429)
            db.execute("INSERT INTO requests(guest,key,digest,created,status,reserved) VALUES(?,?,?,?,?,?)",
                       (guest, key, digest, now, "Analyzing", RESERVE_MICRO_USD))
            db.execute("COMMIT")
        return None

    def finish(self, guest, key, body, cost=None, usage=None):
        if cost is not None and (not isinstance(cost, int) or cost < 0):
            raise ValueError("Invalid charge")
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM requests WHERE guest=? AND key=?", (guest, key)).fetchone()
            if row is None or row["status"] not in ("Analyzing", "Deleted"):
                return
            if row["status"] == "Deleted" and row["reserved"] == 0:
                return
            if cost is not None and cost > RESERVE_MICRO_USD:
                db.execute("UPDATE budget SET halted=1 WHERE id=1")
            deleted = row["status"] == "Deleted"
            db.execute("UPDATE requests SET status=?,body=?,reserved=?,charged=?,usage=? WHERE guest=? AND key=?",
                       ("Deleted" if deleted else "Finished", None if deleted else body,
                        row["reserved"] if cost is None else 0, cost or 0,
                        json.dumps(usage) if usage is not None and not deleted else None, guest, key))
            db.execute("COMMIT")

    def delete_guest(self, guest):
        with self.connection() as db:
            db.execute("PRAGMA secure_delete=ON")
            db.execute("BEGIN IMMEDIATE")
            # Minimal pseudonymous finance/quota/idempotency tombstones survive.
            db.execute("UPDATE requests SET body=NULL,usage=NULL,status='Deleted' WHERE guest=?", (guest,))
            db.execute("DELETE FROM guests WHERE principal=?", (guest,))
            db.execute("COMMIT")

    def report(self):
        with self.connection() as db:
            state = dict(db.execute("SELECT * FROM budget WHERE id=1").fetchone())
            totals = db.execute("SELECT COALESCE(SUM(charged),0),COALESCE(SUM(reserved),0),"
                                "COUNT(*),SUM(CASE WHEN reserved>0 THEN 1 ELSE 0 END) FROM requests").fetchone()
        return {"charged_micro_usd": totals[0] + state["deleted_spend"],
                "reserved_estimate_micro_usd": totals[1], "requests": totals[2],
                "uncertain_requests": totals[3] or 0, "pilot_cap_micro_usd": state["pilot_cap"],
                "global_cap_micro_usd": state["global_cap"], "stopped": bool(state["halted"]),
                "verification_cap_micro_usd": state["verification_cap"],
                "verification_baseline_micro_usd": state["verification_baseline"]}

    def limit_verification(self, amount):
        """Arm a durable additional ceiling. Pending reservations consume it too.

        Restart, repeated configuration and deleting a guest cannot replenish it.
        The $5 pilot cap remains independent. Raising/removing this operator limit
        requires a separately authorized maintenance operation, never a restart.
        """
        if type(amount) is not int or amount <= 0:
            raise ValueError("Invalid verification limit")
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            state = db.execute("SELECT * FROM budget WHERE id=1").fetchone()
            baseline = state["verification_baseline"]
            if baseline is None:
                baseline = db.execute("SELECT COALESCE(SUM(charged),0) FROM requests").fetchone()[0] + state["deleted_spend"]
            cap = baseline + amount
            if state["verification_cap"] is not None:
                cap = min(cap, state["verification_cap"])
            db.execute("UPDATE budget SET verification_baseline=?,verification_cap=? WHERE id=1", (baseline, cap))
            db.execute("COMMIT")

    def verification_limit(self):
        report = self.report()
        cap, baseline = report["verification_cap_micro_usd"], report["verification_baseline_micro_usd"]
        return None if cap is None else {"limit_micro_usd": cap - baseline, "baseline_micro_usd": baseline}

    def stop(self):
        with self.connection() as db:
            db.execute("UPDATE budget SET halted=1 WHERE id=1")
