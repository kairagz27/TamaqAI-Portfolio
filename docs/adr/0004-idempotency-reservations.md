# Idempotency and budget reservations

Status: accepted, retrospective documentation of existing implementation (22 October 2026).

## Context

Retries, concurrency and process restarts can duplicate work or overspend a small pilot budget.

## Decision

Persist operation identity, results and expense reservations transactionally in PostgreSQL.

## Alternatives and consequences

Requires reconciliation of ambiguous provider outcomes and durable shared storage; client-only counters are insufficient.
