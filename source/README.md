# High Contention Ticketing Service

> Designing a Write-Scalable Ticketing Service under High Contention

## Overview

Dịch vụ đặt vé với 6 chiến lược xử lý contention, từ Naive (broken) → Queue-based (production-grade).

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Framework | Spring Boot 3.x (WebMVC) |
| Language | Java 17+ |
| Database | PostgreSQL 16 |
| Cache/Queue | Redis 7 |
| Migration | Flyway |
| Container | Docker + Docker Compose |
| Load Test | k6 |
| Metrics | Micrometer + Actuator |
| Build | Maven + Makefile |
| Test | JUnit 5 + Testcontainers |

## Strategies

| Version | Strategy | Branch | Status |
|---------|----------|--------|--------|
| A | Naive (intentionally broken) | `impl/naive` | ⬜ |
| B | Pessimistic Locking | `impl/pessimistic-locking` | ⬜ |
| C | OCC + Retry Backoff | `impl/occ` | ⬜ |
| D | SERIALIZABLE | `impl/serializable` | ⬜ |
| E | Reservation + TTL + Fencing | `impl/reservation-fencing` | ⬜ |
| F | Queue-based per-event | `impl/queue-based` | ⬜ |

## Prerequisites

- Docker Desktop (for PostgreSQL 16 + Redis 7)
- Java 17+
- Maven 3.9+
- k6 (for load testing)
- Make (GNU Make / Git Bash on Windows)

## Quick Start

```bash
# 1. Start infrastructure
make db-up          # PostgreSQL 16 + Redis 7 via Docker Compose

# 2. Run migrations (after task 1.2)
make migrate        # Flyway schema migration

# 3. Seed test data (after task 1.3)
make seed           # 1 event + 100 seats

# 4. Start application
make app-run        # Spring Boot on :8080

# 5. Run benchmarks (after bench scripts created)
make bench:naive    # k6 benchmark for naive strategy
```

## Available Make Targets

| Target | Description |
|--------|-------------|
| `db-up` | Start PostgreSQL + Redis containers |
| `db-down` | Stop containers |
| `db-reset` | Remove volumes + restart fresh |
| `db-logs` | Tail container logs |
| `migrate` | Run Flyway migrations |
| `seed` | Insert test seed data |
| `app-run` | Start Spring Boot app |
| `bench:naive` | k6 benchmark — Naive |
| `bench:pessimistic` | k6 benchmark — Pessimistic Locking |
| `bench:occ` | k6 benchmark — OCC |
| `bench:serializable` | k6 benchmark — SERIALIZABLE |
| `bench:reservation` | k6 benchmark — Reservation+Fencing |
| `bench:queue` | k6 benchmark — Queue-based |

## Project Structure

```
source/ticketing-service/
├── pom.xml                              ← Spring Boot 3.2.5 + Java 17
├── src/main/java/com/example/ticketing/
│   ├── TicketingApplication.java        ← entry point
│   ├── config/
│   ├── event/
│   ├── ticket/strategy/
│   ├── reservation/
│   ├── queue/
│   ├── observability/
│   └── common/
└── src/main/resources/
    └── application.yml                  ← DB, Redis, Hikari, Actuator config
```

## Database Schema

```
events          → id, name, total_seats, available_seats, version, created_at
seats           → id, event_id, seat_label, status, locked_by, locked_until
tickets         → id, event_id, seat_id, user_id, idempotency_key, created_at
reservations    → id, event_id, seat_id, user_id, fencing_token, status, expires_at, created_at
```

**DB-level invariant guards:**
- `CHECK (available_seats >= 0)` — ngăn oversell
- `UNIQUE (event_id, seat_id)` on tickets — ngăn double-book
- `CHECK (status IN (...))` — state machine enforcement

## Completed Tasks

- [x] **1.1** Docker Compose + Makefile — `make db-up` starts PG16 + Redis 7
- [x] **1.2** Flyway schema migration — events, seats, tickets, reservations
- [x] **1.3** Seed data — `make seed` creates 1 event + 100 seats (idempotent)
