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

## Quick Start

```bash
make db-up      # Start PostgreSQL + Redis
make seed        # Seed test data
make bench:naive # Run benchmark for naive strategy
```

## Project Structure

```
source/ticketing-service/
├── pom.xml
└── src/main/java/com/example/ticketing/
    ├── config/
    ├── event/
    ├── ticket/strategy/
    ├── reservation/
    ├── queue/
    ├── observability/
    └── common/
```
