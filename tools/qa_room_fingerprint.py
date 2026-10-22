"""Fingerprint only the emulator's existing visual QA Room, without reading row values into logs.

Stops the explicit QA process for a consistent private DB/WAL copy. Never opens
the main app or physical device, clears data, or sends an application request.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
from contextlib import closing

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.tamaqai.visual"
DATABASE = "tamaq_meals.db"


def digest_json(value: object) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"),
        default=lambda item: {"bytes": item.hex()} if isinstance(item, bytes) else str(item)).encode()).hexdigest()


def fingerprint(path: Path) -> dict[str, object]:
    with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)) as connection:
        names = [row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
        tables = {}
        for name in names:
            quoted = '"' + name.replace('"', '""') + '"'
            rows = connection.execute("SELECT * FROM " + quoted).fetchall()
            # Stable ordering without depending on hidden rowid or SQL collations.
            hashes = sorted(digest_json(row) for row in rows)
            tables[name] = {"rows": len(rows), "sha256": digest_json(hashes)}
        schema = connection.execute("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name").fetchall()
        return {"schema_sha256": digest_json(schema), "tables": tables,
                "user_version": connection.execute("PRAGMA user_version").fetchone()[0]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--compare")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        raise ValueError("Only an explicitly selected Android emulator is permitted")
    directory = (ROOT / args.out).resolve()
    if not directory.is_relative_to(ROOT / ".android") or directory.exists():
        raise ValueError("Use a new private output directory under workspace .android")
    prior = None
    if args.compare:
        source = (ROOT / args.compare).resolve()
        if not source.is_relative_to(ROOT / ".android"):
            raise ValueError("Comparison proof must remain in the private workspace")
        prior = json.loads(source.read_text(encoding="utf-8"))
        if prior.get("package") != PACKAGE:
            raise ValueError("Comparison proof must belong to the same visual QA package")
    adb = str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe")

    def command(*values: str) -> bytes:
        return subprocess.check_output([adb, "-s", args.serial, *values], timeout=20, stderr=subprocess.PIPE)

    if command("shell", "getprop", "ro.kernel.qemu").strip() != b"1":
        raise ValueError("Selected target does not identify itself as an emulator")
    if not command("shell", "pm", "path", PACKAGE).startswith(b"package:"):
        raise ValueError("The isolated visual QA package must already be installed")
    command("shell", "am", "force-stop", PACKAGE)
    directory.mkdir(parents=True)
    for name in (DATABASE, DATABASE + "-wal"):
        if name.endswith("-wal"):
            exists = subprocess.run([adb, "-s", args.serial, "shell", "run-as", PACKAGE,
                "test", "-f", "databases/" + name], capture_output=True, timeout=20)
            if exists.returncode != 0:
                continue
        (directory / name).write_bytes(command("exec-out", "run-as", PACKAGE, "cat", "databases/" + name))
    current = fingerprint(directory / DATABASE)
    proof = {"package": PACKAGE, "emulator_only": True, "raw_values_logged": False, "fingerprint": current}
    if prior is not None:
        proof["matches_previous"] = current == prior["fingerprint"]
    (directory / "fingerprint.json").write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"package": PACKAGE, "tables": len(current["tables"]),
                      "rows": sum(item["rows"] for item in current["tables"].values()),
                      "matches_previous": proof.get("matches_previous")}, indent=2))
    if proof.get("matches_previous") is False:
        raise SystemExit("Visual QA Room changed; inspect private fingerprints before continuing")


if __name__ == "__main__":
    main()
