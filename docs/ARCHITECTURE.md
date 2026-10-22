# Architecture

Prepared portfolio snapshot, 22 October 2026. [Source and export boundary](export/README.md).

## Android

MainActivity owns the Compose entry/navigation and identity changes. Screens render state, ViewModels coordinate it, domain models represent meals/components/plans, and repositories implement storage/transport. Some screens coordinate repositories directly: these are pragmatic layers, not strict Clean Architecture.

Room schema version **5** stores the local diary. Explicit migrations and exported schemas are retained; destructive migration is not configured. Owner-scoped repositories prevent account switches from showing another owner's diary. Explicit guest linking requires both session proofs. Account/profile synchronization does not back up the Room diary.

An analysis creates a pending draft with stable identity. Editing estimated mass and eaten fraction are separate operations; recalculation starts from baseline nutrition. Confirmation adds a snapshot to totals. Catalogue version/source metadata remains in confirmed entries; later imports do not rewrite them. Unknown nutrients remain nullable.

CameraX is lifecycle-bound and disposed/unbound by the camera composable. Network operations have finite timeouts. Session refresh is coordinated, with logout/generation checks against stale restoration. Cancellation now disconnects a blocked HttpURLConnection; a local socket regression verifies cancellation latency.

## FastAPI modular monolith

Auth, Users, Analysis and Usage run in one service. PostgreSQL holds accounts, hashed rotating refresh sessions, single-use verification/reset tokens, invitations, results, quotas, reservations and usage. Versioned server migrations run with `python -m tamaq.db`. Legacy SQLite import code remains available for explicit operator use; the portfolio demo does not import or reset pilot state.

Server identity comes from validated credentials, not a client UID. Passwords use Argon2id. Email delivery is an adapter; Mailpit is development-only. Uploads have byte and decoded-pixel limits. Analysis ownership is checked. Idempotency and transaction/advisory-lock protected reservations serialize parallel spending decisions. Provider failure accounting follows the recorded operation state.

## Trust and operation

Android contains the endpoint, never an OpenAI key. Production rejects fake mode. PostgreSQL/files need persistent volumes and verified backups: a container writable layer is not durable storage.

The portfolio stack uses generated local credentials and separate volumes. Backend/PostgreSQL/Mailpit have an internal network; a loopback gateway exposes fixed destinations. Fake AI, blank OpenAI credentials and local SMTP are forced. The separate demo package visibly identifies itself and does not modify the original app or ledger.

[Decisions and trade-offs](adr/README.md) · [Security](../SECURITY.md) · [Development](DEVELOPMENT.md)
