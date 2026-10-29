# TamaqAI-Portfolio repository instructions

## Preserve the working application

- Keep the Kotlin / Jetpack Compose Android app and modular FastAPI backend.
- Preserve Room data and settings. Database changes require explicit migrations; never use destructive migrations, uninstall the user's app, or clear its data for verification.
- Preserve backend URL, session behavior, invitation access, idempotency, quotas, budget limits, reservations and the existing ledger. Never reset them for tests.
- Use isolated test packages, accounts and databases. Do not alter real diary records for tests. Paid AI calls require explicit authorization and the current spending cap.
- Keep API keys on the backend. Do not log passwords, tokens, invitations or environment secrets.
- Keep the approved local typography: Bodoni Moda 400 for English headings, Lora as the licensed temporary replacement for Georgia in Russian/Kazakh headings, Inter for body text and numbers, and Noto Sans as the glyph fallback. Shared UI styling belongs in the Compose design system.

## Snapshot and publication boundaries

- This repository starts from the prepared source snapshot `4d1473f8a34e230f0b0203d5f036bdb955eed603`. Preserve authorship, original notices and the explicit export provenance. Do not recreate or impersonate earlier development commits.
- Work only in this portfolio repository. The original development repository remains private; do not rewrite or publish its history.
- The owner authorized public visibility and publication of `v1.3.1-portfolio.1` on 2026-10-23. The original development repository must remain private. Ordinary commit/push permission does not authorize other visibility changes or future release publications.
- Distinguish the demo variants before download links. The existing `demo` / `com.tamaqai.demo` APK uses fake AI and requires local services on port 8100 plus `adb reverse`; it is not standalone or hosted. The separate `standaloneDemo` / `com.tamaqai.standalone` package uses local implementations and bundled examples, needs no backend/internet/reverse, and must remove `INTERNET` from its packaged manifest. Do not silently return a prepared result for arbitrary user photographs.
- The earlier standalone task authorized draft release `v1.4.0-standalone.1`, not its publication. The meal-planning/shopping update authorizes an ordinary merge preserving individual commits after successful required checks; it does not authorize a new public release. Keep both earlier releases/tags/assets unchanged. Draft assets are not publicly downloadable; use the releases overview until publication is separately authorized.
- Include only reviewed assets/data with confirmed redistribution terms. Preserve the separate OFL, Apache and ODbL/DbCL notices; the root MIT license does not override them.

## Verification, commits and GitHub

After every completed task:

1. Run checks appropriate to the changes, including relevant builds, tests, lint and visual checks for UI changes. Record what ran and what remains unverified.
2. Update documentation whenever behavior, configuration or launch instructions change.
3. Inspect `.gitignore`, staged paths and content. Never commit API keys, secret `.env` files, real invitations or tokens, user databases, ledger data, backups, keystores, local machine settings, build directories or APKs. Include safe configuration templates without secret values.
4. Create a clear, descriptive commit and push it to `https://github.com/kairagz27/TamaqAI-Portfolio` using the current working branch. This public portfolio repository is separate from the private development repository.
5. Verify that local `HEAD` equals the remote branch SHA after pushing. Report the actual commit and result.

The user has authorized ordinary future commits and pushes to this repository. Fetch remote changes before pushing; do not use force push, rewrite shared history or overwrite other contributors' changes. If authentication or a conflict prevents a safe push, finish local work and explain the concrete remaining action. Never ask for passwords or tokens in chat.

If a task is interrupted, preserve a safe intermediate result in a separate commit explicitly marked incomplete. Do not commit a known broken build to `main`: put unfinished work on a separate branch and document outstanding work and checks. If push is impossible, retain the local commit and report the specific blocker. Respect environment restrictions and do not ask for repeated authorization for ordinary commits/pushes.

Do not claim a build, test, visual check or push succeeded without checking its result.
