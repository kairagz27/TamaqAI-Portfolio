# Architecture

The public history begins with the prepared portfolio snapshot of 22 October 2026.
The Android planning additions below describe source version 1.5.0.
[Source and export boundary](export/README.md).

## Android

MainActivity owns the Compose entry/navigation and identity changes. Screens render state, ViewModels coordinate it, domain models represent meals/components/plans, and repositories implement storage/transport. Some screens coordinate repositories directly: these are pragmatic layers, not strict Clean Architecture.

Room schema version **6** stores the local diary and planning documents. The additive **5 → 6** migration creates `planning_workspaces` and `shopping_documents` without replacing existing tables. Explicit migrations and exported schemas are retained; destructive migration is not configured. Owner-scoped repositories prevent account switches from showing another owner's diary or plans. Explicit guest linking requires both session proofs. Account/profile synchronization does not back up the Room diary or local planning documents.

An analysis creates a pending draft with stable identity. Editing estimated mass and eaten fraction are separate operations; recalculation starts from baseline nutrition. Confirmation adds a snapshot to totals. Catalogue version/source metadata remains in confirmed entries; later imports do not rewrite them. Unknown nutrients remain nullable.

CameraX is lifecycle-bound and disposed/unbound by the camera composable. Network operations have finite timeouts. Session refresh is coordinated, with logout/generation checks against stale restoration. Cancellation now disconnects a blocked HttpURLConnection; a local socket regression verifies cancellation latency.

## Local planning and shopping

`PlanningViewModel` exposes `StateFlow` to the shared Compose plan and shopping
screens. The `PlanningRepository` contract has one Room implementation for both
server and standalone compositions: planning needs no AI or network adapter.
Each workspace is owner-scoped and stores a selected 1–7-day window (default 3),
a revision, immutable recipe snapshots and explicit legacy-import receipts.
Entries outside the selected window remain stored.

`ShoppingListCalculator` is pure business logic. It scales recipe ingredients by
planned servings / recipe yield, groups exact product identities and preparation
states, and normalizes compatible units. Mass/volume/piece conversions require
explicit, consistent metadata. Unknown quantities and per-field nutrients remain
nullable. A known shopping subtotal can carry an unknown contribution; an
incomplete nutrition total is never reported as a complete zero.

The saved shopping document records the source workspace revision. Plan edits
mark it stale; explicit refresh preserves manual rows, notes and marks. Increased
marked demand requires acknowledgement. Removed generated rows carrying notes or
marks remain visible as inactive rather than losing user input.

Room transactions coordinate writes and diary confirmation. Confirming the
actual eaten servings snapshots the baseline recipe into a diary entry whose
stable UID derives from the plan entry ID. Retries and explicit guest linkage
retain that identity. Later plan changes do not rewrite a confirmed diary meal.
Guest linking rejects ambiguous target planning data rather than silently merging
or overwriting it.

The existing daily `PlanStore` remains separate. Its cached plans can be imported
explicitly once; loading newer server data cannot overwrite local multi-day edits.
Neither the local workspace nor shopping document is uploaded to the backend.
The six original MIT recipe fixtures have illustrative nutrition and one
intentionally incomplete recipe. [Behavior, data rules and checks](MEAL_PLANS_SHOPPING.md)
· [Recipe provenance](licenses/demo-recipes.md).

## Standalone composition

`standaloneDemo` installs `com.tamaqai.standalone` with `TamaqStandaloneApplication`.
The composition root selects `AccountStore`, `NutritionStore`, `PlanStore` and
`CatalogStore` local implementations, together with the same Room meal repository
and domain calculations. It never constructs `SessionClient` or `SecureSessions`.
The packaged manifest removes `INTERNET`; no account identity or invitation is simulated.

An injected `Clock` anchors one-time seed data. A durable receipt and stable IDs
make retries insert missing records without replacing edits. Explicit reset has a
recoverable pending phase and is restricted to the standalone package and owner.
The sample provider accepts only the exact bundled CC0 photograph; arbitrary
photos cannot receive its prepared answer. Plans use stable meal IDs, and only
explicit confirmation enters the diary. [Details and checks](STANDALONE_DEMO.md).

## FastAPI modular monolith

Auth, Users, Analysis and Usage run in one service. PostgreSQL holds accounts, hashed rotating refresh sessions, single-use verification/reset tokens, invitations, results, quotas, reservations and usage. Versioned server migrations run with `python -m tamaq.db`. Legacy SQLite import code remains available for explicit operator use; the portfolio demo does not import or reset pilot state.

Server identity comes from validated credentials, not a client UID. Passwords use Argon2id. Email delivery is an adapter; Mailpit is development-only. Uploads have byte and decoded-pixel limits. Analysis ownership is checked. Idempotency and transaction/advisory-lock protected reservations serialize parallel spending decisions. Provider failure accounting follows the recorded operation state.

## Trust and operation

Android contains the endpoint, never an OpenAI key. Production rejects fake mode. PostgreSQL/files need persistent volumes and verified backups: a container writable layer is not durable storage.

The portfolio stack uses generated local credentials and separate volumes. Backend/PostgreSQL/Mailpit have an internal network; a loopback gateway exposes fixed destinations. Fake AI, blank OpenAI credentials and local SMTP are forced. The separate demo package visibly identifies itself and does not modify the original app or ledger.

[Decisions and trade-offs](adr/README.md) · [Security](../SECURITY.md) · [Development](DEVELOPMENT.md)
