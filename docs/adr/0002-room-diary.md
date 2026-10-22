# Room local diary

Status: accepted, retrospective documentation of existing implementation (22 October 2026).

## Context

Meal review and history must survive process loss without server availability.

## Decision

Keep owner-scoped diary snapshots in Room with explicit migrations.

## Alternatives and consequences

Local use and schema checks are simple, but account registration does not provide diary backup or multi-device sync.
