# Catalogue import

Use only the separately licensed OFF seed in the publication tree:

```sh
python backend/scripts/portfolio_demo.py catalog
```

This invokes the catalogue importer inside the isolated demo backend. Repeating an unchanged import skips existing versions rather than duplicating them. Do not point demo tooling at a pilot database.

For an explicitly configured operator environment, the underlying command is:

```sh
python -m tamaq.catalog import catalog_seed/openfoodfacts-kz.json --apply
```

Run from backend with the intended private database configuration. Review a dry run before applying new data. Existing confirmed diary entries retain their nutrition/source snapshots; imported versions do not rewrite them. [Sources](../DATA_SOURCES.md).
