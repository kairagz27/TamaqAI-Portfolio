"""python -m tamaq.catalog import FILE --dry-run | --apply [--report FILE]."""
import argparse
import json
import os
from pathlib import Path

from ..db import Database
from .importer import import_file


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    importer = commands.add_parser("import")
    importer.add_argument("file")
    mode = importer.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--apply", action="store_true", help="Commit imported versions; default is dry-run")
    importer.add_argument("--report")
    args = parser.parse_args()
    try:
        database = Database() if os.environ.get("TAMAQ_DATABASE_URL") else None
        report = import_file(args.file, database, dry_run=not args.apply)
    except Exception:
        # Connection exceptions may contain credentials. Do not serialize them.
        report = {"error": "catalog_import_failed", "action": "Check input format and private PostgreSQL configuration"}
    rendered = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False)
    if args.report:
        destination=Path(args.report)
        destination.parent.mkdir(parents=True,exist_ok=True)
        destination.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 1 if "error" in report or report.get("rejected") else 0


if __name__ == "__main__":
    raise SystemExit(main())
