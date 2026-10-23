# Security

This portfolio repository and its first demo release are public with the owner's authorization of 23 October 2026. The original development repository remains private. No dedicated private vulnerability-reporting address or GitHub private reporting channel has been configured and verified. Do not put credentials, personal data or exploit details in an issue. Contact the owner through an existing private channel to arrange a secure report.

## Boundaries

- Android stores session material encrypted; production requires HTTPS. Loopback HTTP is a development configuration.
- OpenAI keys stay server-side. Credentials establish identity; a client owner ID is not authentication.
- Argon2id passwords, hashed rotating/revocable refresh sessions, expiring single-use verification/reset tokens and auth rate limits protect accounts.
- Upload byte/pixel limits constrain image input. Human confirmation separates estimates from diary totals.
- Idempotency, quota checks and expense reservations share durable PostgreSQL state. Registration does not mint budget.
- The demo uses separate generated credentials/data, fake AI and local Mailpit. It does not establish real model access, external delivery or production readiness.

Keep secret `.env` files, `.demo`, real invitations/tokens, databases, ledger, backups, signing keys and machine settings out of Git and Docker contexts. Commit safe templates only. Never attach raw credentials, verification links or personal screenshots to issues.

If a real secret leaks, revoke or rotate it before Git cleanup; deleting the latest file is insufficient. The [export audit](docs/export/README.md) explains the explicit file boundary and review limitations. Pattern scanning does not guarantee that every possible secret can be detected. Review each new source, asset and release attachment before distribution.

The demo's fake provider and empty OpenAI credentials are forced by Compose, and backend services have no external network route. Do not expose development ports publicly or replace this stack with a real pilot environment for a demonstration.
