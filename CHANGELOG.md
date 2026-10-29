# Changelog

## 1.5.0 — meal plans and shopping lists

- Local 1–7-day planning, default 3 days, with recipe snapshots per meal,
  portion changes, dish replacement/removal, day copying and daily nutrition
  relative to dated goals.
- Shopping calculation with conservative product/preparation identity, compatible
  units, explicit conversion metadata and unknown quantities/nutrition preserved.
- Categorized purchases, dish references, bought/at-home marks, notes, manual
  positions and Android text sharing. Plan changes require refresh; increased
  marked quantities request review while user edits remain intact.
- Additive Room 5 → 6 migration, owner isolation, explicit guest transfer and stable
  actual-eaten confirmation IDs. Cached legacy-plan import cannot overwrite local
  edits or create duplicates on reimport.
- Six original MIT synthetic recipe fixtures, RU/KK/EN labels and shared warm
  light/dark styling. No AI calls, paid services or cloud diary synchronization.
- Domain, persistence/migration, ViewModel recovery and Compose scenario tests;
  actual execution results are tracked separately and are not implied by this list.

This is a source/build update. No new public release is published by this feature;
earlier release tags and assets remain unchanged.
[Behavior, limits and verification](docs/MEAL_PLANS_SHOPPING.md).

## 1.4.0-standalone.1 — draft

- Separate offline Android package, local profile and one-time Room fixtures.
- Real shared editor, manual entry, catalogue, goals, plans, themes and languages.
- Explicit CC0 photograph with matching prepared nutrition; no live AI or arbitrary-photo substitution.
- Local reset with confirmation, durable recovery and preserved appearance/language.
- No INTERNET permission, backend URL or server session implementation in the standalone composition.
- New draft assets are separate from the unchanged server demo release.

[Standalone usage and actual verification](docs/STANDALONE_DEMO.md).

## Public portfolio publication — 23 October 2026

The owner authorized public visibility for this portfolio repository and publication
of the existing [demo release v1.3.1-portfolio.1](https://github.com/kairagz27/TamaqAI-Portfolio/releases/tag/v1.3.1-portfolio.1).
The release tag remains on tested commit `b67f95bcdd59a59923e7364df662b91b2b2b463b`;
the publication update changes documentation and its integrity manifest only.
Prepared APK, video, verification record and checksums are preserved. The original
development repository remains private. [Publication checks](docs/PUBLICATION.md).

## v1.3.1-portfolio.1 — prepared snapshot, 22 October 2026

First portfolio edition, starting from source snapshot `4d1473f8a34e230f0b0203d5f036bdb955eed603`. This is a new export history, not a reconstruction of previous releases. At preparation, the repository and draft release were held private pending the owner's separate approval.

Included capabilities from the source snapshot:

- Separate Android demo package, visible fake-provider banner and isolated PostgreSQL/Mailpit stack.
- Editable analysis components and portions, confirmation, Room diary/history and account-scoped state.
- Warm light/dark UI, RU/KK/EN, local licensed fonts and native illustrations.
- Catalogue snapshots and 39 attributed Open Food Facts records; unconfirmed manufacturer/menu/reference materials withheld.
- Finite network operations, blocked HTTP-read cancellation regression, repeatable local verification helper and bounded recording cleanup.
- Pinned CI actions, builds, tests, licensing inventories and engineering documentation.

Export preparation retains an explicit source allowlist, attribution and media review, adapts setup/documentation to the portfolio repository and excludes private operational evidence. Original user data and pilot state are not part of this distribution.

Known limitation: intermittent Espresso/Compose idle/Activity-teardown instability remains unresolved. Actual new-export checks and unverified items are recorded in [the verification report](docs/PUBLICATION_VERIFICATION.md). No production-readiness or calorie-accuracy claim is made.
