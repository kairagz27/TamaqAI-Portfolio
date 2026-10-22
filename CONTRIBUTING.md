# Contributing

Start with [development](docs/DEVELOPMENT.md), [architecture](docs/ARCHITECTURE.md)
and [security](SECURITY.md). Use the separate demo package and disposable
databases; never test by clearing a user's diary or pilot ledger.

1. Open a focused issue or describe the problem in a PR. Do not include secrets,
   account exports, personal photographs or security exploit details in public.
2. Branch from the reviewed base. Keep changes small and preserve Room/SQL
   migrations and API ownership checks. Do not rewrite applied migrations.
3. Add a regression test for a concrete defect. Run the relevant checks from
   DEVELOPMENT; list checks you could not perform.
4. Update behavior/setup documentation and provenance for any new data/assets.
5. Inspect staged paths/content. Keep `.env`, `.demo`, databases, ledger, backups,
   signing keys, APKs and build output out of Git.
6. Submit a PR; ordinary contributors use a fork and do not push to the owner's
   maintained branches. No force push or shared-history rewrite.

Code contributions are offered under the root MIT license. Third-party data,
fonts and media keep their own licenses; do not submit an asset without a clear
source and redistribution terms. Paid AI calls and infrastructure changes need
explicit authorization. Never claim an unrun test or infer model accuracy from
synthetic fixtures.


This portfolio repository starts from a prepared snapshot; see [AUTHORS](AUTHORS.md)
and [export provenance](docs/export/README.md). Preserve the authentic snapshot
boundary and use real commits for new work. Ordinary contribution permission does
not authorize changing the repository's private visibility or publishing a draft
release.
