"""Shared PostgreSQL infrastructure; Auth, Users, Analysis and Usage stay in one process."""
from contextlib import contextmanager
from pathlib import Path
import hashlib
import os
import psycopg
from psycopg.rows import dict_row

USAGE_LOCK = 8426500001
MIGRATION_LOCK = 8426500002

class Database:
    def __init__(self, url=None):
        self.url = url or os.environ.get("TAMAQ_DATABASE_URL", "")
        if not self.url.startswith(("postgresql://", "postgres://")):
            raise RuntimeError("PostgreSQL TAMAQ_DATABASE_URL is required")

    @contextmanager
    def connection(self):
        # One short-lived connection per transaction; never share a connection between workers.
        with psycopg.connect(self.url, row_factory=dict_row, connect_timeout=5,
                             options="-c statement_timeout=10000 -c lock_timeout=10000") as db:
            yield db

    def migrate(self):
        with self.connection() as db:
            db.execute("SELECT pg_advisory_xact_lock(%s)", (MIGRATION_LOCK,))
            db.execute("CREATE TABLE IF NOT EXISTS schema_versions(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL)")
            for path in sorted((Path(__file__).parent / "migrations").glob("*.sql")):
                version = int(path.name.split("_")[0]); source = path.read_text(encoding="utf-8")
                digest = hashlib.sha256(source.encode()).hexdigest()
                prior = db.execute("SELECT checksum FROM schema_versions WHERE version=%s", (version,)).fetchone()
                if prior:
                    if prior["checksum"] != digest: raise RuntimeError("Applied migration checksum differs")
                    continue
                db.execute(source)
                db.execute("INSERT INTO schema_versions VALUES(%s,%s)", (version,digest))

    def require_current(self):
        with self.connection() as db:
            versions = db.execute("SELECT version,checksum FROM schema_versions").fetchall()
        expected = {int(p.name.split("_")[0]):hashlib.sha256(p.read_text(encoding="utf-8").encode()).hexdigest()
                    for p in (Path(__file__).parent / "migrations").glob("*.sql")}
        if {r["version"]:r["checksum"] for r in versions} != expected:
            raise RuntimeError("Run PostgreSQL migrations before starting the backend")
