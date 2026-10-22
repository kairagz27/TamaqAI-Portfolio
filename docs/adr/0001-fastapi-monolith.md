# FastAPI modular monolith

Status: accepted, retrospective documentation of existing implementation (22 October 2026).

## Context

Auth, Users, Analysis and Usage share transactions and a small operational footprint.

## Decision

Keep one deployable service with module boundaries and PostgreSQL.

## Alternatives and consequences

Independent microservices would add network consistency and deployment cost. The monolith needs disciplined module ownership.
