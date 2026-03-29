# Tutorial — High Contention Ticketing System

> Hướng dẫn chi tiết từ A-Z: cài đặt, chạy, debug, benchmark, và các mẹo hữu ích.
> Dành cho developer muốn hiểu rõ từng bước và debug nhanh nhất có thể.

---

## Mục lục

1. [Prerequisites — Cài đặt môi trường](#1-prerequisites)
2. [Quick Start — 5 phút chạy được](#2-quick-start)
3. [Hiểu cấu trúc dự án](#3-cấu-trúc-dự-án)
4. [Chuyển đổi giữa các Strategy (Branch)](#4-chuyển-đổi-strategy)
5. [Debug từng Strategy](#5-debug-từng-strategy)
6. [Chạy Benchmark](#6-chạy-benchmark)
7. [Database — Truy vấn & Debug](#7-database-debug)
8. [Redis — Inspect & Debug](#8-redis-debug)
9. [Observability — Metrics & Stats](#9-observability)
10. [Troubleshooting — Lỗi thường gặp](#10-troubleshooting)
11. [IDE Extensions & Tools hữu ích](#11-extensions--tools)
12. [Tips & Tricks](#12-tips--tricks)
13. [Glossary — Thuật ngữ](#13-glossary)

---

## 1. Prerequisites

### 1.1 Software cần cài

| Tool | Version | Mục đích | Cài đặt |
|------|---------|----------|---------|
| **Docker Desktop** | Latest | PostgreSQL 16 + Redis 7 | https://docker.com/products/docker-desktop |
| **Java JDK** | 17+ | Spring Boot runtime | `winget install Microsoft.OpenJDK.17` |
| **Maven** | 3.9+ | Build & dependency management | `winget install Apache.Maven` |
| **Git** | Latest | Version control + branch switching | `winget install Git.Git` |
| **k6** | Latest | Load testing | `winget install k6.k6` |
| **Make** | Latest | Task runner | `winget install GnuWin32.Make` hoặc dùng Git Bash |
| **curl** | Latest | API testing | Có sẵn trên Windows 10+ |
| **jq** | Latest | JSON formatting | `winget install jqlang.jq` |

### 1.2 Kiểm tra cài đặt

```bash
# Kiểm tra tất cả tools
docker --version          # Docker version 24+
java -version             # openjdk 17+
mvn -version              # Apache Maven 3.9+
git --version             # git version 2.x
k6 version                # k6 v0.x
make --version            # GNU Make 4.x
curl --version            # curl 7.x+
jq --version              # jq-1.x
```

### 1.3 Windows-specific notes

- **Make trên Windows:** Nếu không cài được Make, dùng **Git Bash** (đi kèm Git for Windows) — nó có sẵn `make`.
- **PowerShell vs Git Bash:** Nên dùng **Git Bash** cho project này vì Makefile dùng shell syntax.
- **Docker Desktop:** Phải **start Docker Desktop** trước khi chạy `make db-up`. Nếu thấy lỗi `open //./pipe/dockerDesktopLinuxEngine`, Docker chưa khởi động.
- **Line endings:** Git trên Windows có thể convert LF → CRLF. Nếu gặp lỗi kỳ lạ với SQL files:
  ```bash
  git config core.autocrlf input
  ```

---

## 2. Quick Start

### 2.1 Clone & Setup (lần đầu)

```bash
# Clone repo
git clone <repo-url> high-contention
cd high-contention

# Start infrastructure
make db-up              # PostgreSQL 16 + Redis 7

# Run migrations
make migrate            # Tạo tables: events, seats, tickets, reservations

# Seed test data
make seed               # 1 event "Concert Alpha" + 100 seats (A1-A100)

# Start application (trên branch main — cần checkout impl/* để có strategy)
git checkout impl/naive
make app-run            # Spring Boot trên :8080
```

### 2.2 Test nhanh bằng curl

```bash
# Xem event
curl http://localhost:8080/events/1 | jq

# Mua vé (Naive strategy)
curl -X POST http://localhost:8080/tickets/reserve-and-buy \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-key-001" \
  -d '{"eventId":1, "userId":"550e8400-e29b-41d4-a716-446655440000", "seatLabel":"A1"}' | jq

# Xem stats sau khi mua
curl http://localhost:8080/events/1/stats | jq

# Test idempotency — gửi lại cùng key
curl -X POST http://localhost:8080/tickets/reserve-and-buy \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-key-001" \
  -d '{"eventId":1, "userId":"550e8400-e29b-41d4-a716-446655440000", "seatLabel":"A1"}' | jq
# → Phải trả cached response (duplicate detected)
```

### 2.3 Swagger UI

Truy cập **http://localhost:8080/swagger-ui.html** để:
- Xem tất cả API endpoints
- Test API trực tiếp từ browser (Try It Out)
- Xem request/response schema

> **Lưu ý:** Swagger UI chỉ available trên branches có springdoc dependency (impl/queue-based trở lên).

---

## 3. Cấu trúc dự án

### 3.1 Thư mục chính

```
high-contention/
│
├── 📁 docs/                          ← Tài liệu theo task (doc-before-code)
│   ├── plan.md                       ← Master plan — đọc TRƯỚC TIÊN
│   ├── REPORT.md                     ← Trade-off analysis
│   ├── tutorial.md                   ← File này
│   ├── 1.1-docker-setup.md          ← Foundation docs
│   ├── 1.2-schema-migration.md
│   ├── 1.3-seed-data.md
│   ├── 1.4-shared-utilities.md
│   ├── 3.1-idempotency.md           ← Cross-cutting docs
│   ├── 3.2-observability.md
│   ├── bench-B1-stock1.md           ← Benchmark scenarios
│   └── bench-final.md               ← Bảng so sánh A-F (fill after benchmarks)
│
├── 📁 db/
│   ├── migrations/V1__init_schema.sql  ← Flyway migration — schema definition
│   └── seeds/seed_events.sql           ← Test data — idempotent INSERT
│
├── 📁 load-test/                     ← k6 benchmark scripts
│   ├── stock1.js                     ← B1: 200 VUs, 1 seat
│   ├── hot-seat.js                   ← B2: 500 VUs, hot spots
│   └── burst.js                      ← B3: ramp 0→1000 VUs
│
├── 📁 source/ticketing-service/      ← Spring Boot app
│   ├── pom.xml                       ← Dependencies
│   ├── README.md                     ← Quick reference
│   └── src/main/java/com/example/ticketing/
│       ├── TicketingApplication.java         ← Entry point
│       ├── common/                           ← Shared code (mọi branch)
│       │   ├── IdempotencyFilter.java        ← Redis SET NX duplicate guard
│       │   ├── RetryWithBackoff.java         ← Exponential backoff + jitter
│       │   └── TicketingConstants.java       ← Constants, PG error codes
│       ├── config/                           ← Configuration (trên impl/* branches)
│       │   ├── StrategyConfig.java           ← Strategy bean wiring
│       │   └── OpenApiConfig.java            ← Swagger UI config
│       ├── event/                            ← Event domain
│       │   ├── Event.java                    ← JPA entity
│       │   ├── EventController.java          ← GET /events/{id}, /events/{id}/stats
│       │   └── EventRepository.java          ← findById, findByIdForUpdate, etc.
│       ├── ticket/                           ← Ticket domain
│       │   ├── Seat.java                     ← JPA entity (AVAILABLE→LOCKED→SOLD)
│       │   ├── SeatRepository.java           ← findForUpdate, markAsSold
│       │   ├── Ticket.java                   ← JPA entity (proof of ownership)
│       │   ├── TicketController.java         ← POST /tickets/reserve-and-buy
│       │   ├── TicketRepository.java         ← findByIdempotencyKey
│       │   ├── TicketResult.java             ← Result object (SUCCESS/CONFLICT/...)
│       │   └── strategy/                     ← Strategy Pattern implementations
│       │       ├── TicketingStrategy.java     ← Interface
│       │       ├── NaiveTicketingStrategy.java        ← 2.A (impl/naive)
│       │       ├── PessimisticTicketingStrategy.java  ← 2.B (impl/pessimistic-locking)
│       │       ├── OccTicketingStrategy.java          ← 2.C (impl/occ)
│       │       ├── SerializableTicketingStrategy.java ← 2.D (impl/serializable)
│       │       └── QueueTicketingStrategy.java        ← 2.F (impl/queue-based)
│       ├── reservation/                      ← 2.E (impl/reservation-fencing)
│       ├── queue/                            ← 2.F (impl/queue-based)
│       └── observability/                    ← Metrics
│           ├── ConflictMetrics.java          ← Micrometer counters
│           └── TicketingStatsService.java    ← Stats aggregation
│
├── docker-compose.yml                ← PG16 + Redis 7
├── Makefile                          ← All task targets
└── AGENTS.md                         ← Agent system doc
```

### 3.2 Branch Strategy — MỖI STRATEGY = 1 BRANCH

```
main                              ← Foundation code, shared utilities
├── impl/naive                    ← A: broken by design (SELECT → CHECK → UPDATE)
├── impl/pessimistic-locking      ← B: SELECT FOR UPDATE
├── impl/occ                      ← C: version-based retry
├── impl/serializable             ← D: SERIALIZABLE isolation
├── impl/reservation-fencing      ← E: 2-phase reserve/confirm + TTL
├── impl/queue-based              ← F: Redis queue + worker
└── benchmark/results             ← Benchmark output files
```

**Quy tắc quan trọng:**
- `main` chứa foundation code — KHÔNG có strategy implementation
- Mỗi `impl/*` branch chứa **đầy đủ code để chạy độc lập**
- Shared code thay đổi trên `main` → merge vào `impl/*` (không ngược lại)
- Để chạy strategy X: `git checkout impl/X` → `make app-run`

---

## 4. Chuyển đổi Strategy

### 4.1 Checkout branch

```bash
# Xem tất cả branches
git branch -a

# Chuyển sang Pessimistic Locking
git checkout impl/pessimistic-locking

# Chuyển sang OCC
git checkout impl/occ

# Quay về main
git checkout main
```

### 4.2 Kiểm tra strategy đang active

```bash
# Xem trong application.yml
cat source/ticketing-service/src/main/resources/application.yml | grep "strategy:"

# Hoặc qua API (khi app đang chạy)
curl http://localhost:8080/events/1/stats | jq '.strategy'
```

### 4.3 Merge thay đổi từ main vào impl branch

```bash
# Khi main có thay đổi mới (ví dụ: fix idempotency)
git checkout impl/occ
git merge main

# Nếu conflict:
git mergetool              # Hoặc resolve thủ công
git add .
git commit -m "merge: update from main"
```

---

## 5. Debug từng Strategy

### 5.1 Strategy A — Naive (impl/naive)

**Mục tiêu debug:** Chứng minh oversell xảy ra.

```bash
# 1. Checkout & start
git checkout impl/naive
make db-reset && make migrate && make seed
make app-run

# 2. Gửi 10 request đồng thời cho cùng 1 seat
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:8080/tickets/reserve-and-buy \
    -H "Content-Type: application/json" \
    -d "{\"eventId\":1, \"userId\":\"$(uuidgen)\", \"seatLabel\":\"A1\"}" &
done
wait

# 3. Kiểm tra — nếu soldCount > 1 cho seat A1 → OVERSELL!
curl http://localhost:8080/events/1/stats | jq
```

**Debug points:**
- `NaiveTicketingStrategy.java` → breakpoint ở SELECT và UPDATE
- Mở 2 terminal, gửi request gần đồng thời → thấy cả 2 đọc `available_seats=1`
- PostgreSQL log: `log_statement=all` (tạm bật) để thấy interleaving

### 5.2 Strategy B — Pessimistic Locking (impl/pessimistic-locking)

**Mục tiêu debug:** Thấy lock wait, deadlock detection.

```bash
git checkout impl/pessimistic-locking
make db-reset && make migrate && make seed
make app-run
```

**Debug SQL — xem lock waits:**
```sql
-- Kết nối PostgreSQL khi đang chạy benchmark
-- Xem ai đang chờ lock
SELECT pid, wait_event_type, wait_event, state, query
FROM pg_stat_activity
WHERE wait_event_type = 'Lock';

-- Xem lock details
SELECT locktype, relation::regclass, mode, granted, pid
FROM pg_locks
WHERE NOT granted;

-- Xem deadlock (nếu có)
-- PostgreSQL log sẽ có: "deadlock detected"
docker compose logs postgres | grep -i deadlock
```

**Debug points:**
- `PessimisticTicketingStrategy.java` → breakpoint sau `findByIdForUpdate()`
- `EventRepository.findByIdForUpdate()` → SQL: `SELECT * FROM events WHERE id = ? FOR UPDATE`
- Tăng `deadlock_timeout` trong docker-compose.yml để dễ observe

### 5.3 Strategy C — OCC (impl/occ)

**Mục tiêu debug:** Thấy version conflict và retry behavior.

```bash
git checkout impl/occ
make db-reset && make migrate && make seed
make app-run
```

**Debug points:**
- `OccTicketingStrategy.java` → breakpoint ở `decrementAvailableSeatsWithVersion()`
- Khi `affected rows = 0` → conflict detected → retry
- `RetryWithBackoff.java` → thấy delay tăng exponentially
- Log output: `OCC conflict on attempt X, retrying...`

**Quan sát retry:**
```bash
# Xem retry pattern trong app logs
# App log sẽ hiện:
#   OCC conflict on attempt 1 for event 1, retrying after 50ms
#   OCC conflict on attempt 2 for event 1, retrying after 100ms
#   OCC conflict on attempt 3 for event 1, retrying after 200ms
```

### 5.4 Strategy D — SERIALIZABLE (impl/serializable)

**Mục tiêu debug:** Thấy SerializationFailureException.

```bash
git checkout impl/serializable
make db-reset && make migrate && make seed
make app-run
```

**Debug points:**
- `SerializableTicketingStrategy.java` → `@Transactional(isolation = SERIALIZABLE)`
- Catch block: `org.postgresql.util.PSQLException` → state `40001` (serialization_failure)
- PostgreSQL log: `ERROR: could not serialize access due to concurrent update`

**Debug SQL:**
```sql
-- Xem isolation level của current transaction
SHOW transaction_isolation;

-- Xem serialization failures trong PG stats
SELECT * FROM pg_stat_database WHERE datname = 'ticketing';
-- Cột: conflicts, deadlocks
```

### 5.5 Strategy E — Reservation + Fencing (impl/reservation-fencing)

**Mục tiêu debug:** Thấy 2-phase flow, TTL expiry, fencing token validation.

```bash
git checkout impl/reservation-fencing
make db-reset && make migrate && make seed
make app-run
```

**Test 2-phase flow:**
```bash
# Phase 1: Reserve
RESERVE_RESPONSE=$(curl -s -X POST http://localhost:8080/reservations \
  -H "Content-Type: application/json" \
  -d '{"eventId":1, "userId":"550e8400-e29b-41d4-a716-446655440000", "seatLabel":"A1"}')
echo $RESERVE_RESPONSE | jq

# Extract reservation ID and fencing token
RESERVATION_ID=$(echo $RESERVE_RESPONSE | jq -r '.reservationId')
FENCING_TOKEN=$(echo $RESERVE_RESPONSE | jq -r '.fencingToken')

# Phase 2: Confirm
curl -X POST "http://localhost:8080/reservations/${RESERVATION_ID}/confirm" \
  -H "Content-Type: application/json" \
  -d "{\"fencingToken\":\"${FENCING_TOKEN}\"}" | jq

# Test fencing: dùng sai token → phải reject
curl -X POST "http://localhost:8080/reservations/${RESERVATION_ID}/confirm" \
  -H "Content-Type: application/json" \
  -d '{"fencingToken":"wrong-token-abc"}' | jq
```

**Debug TTL expiry:**
```sql
-- Xem reservations sắp hết hạn
SELECT id, seat_id, status, expires_at,
       expires_at - NOW() as time_left
FROM reservations
WHERE status = 'PENDING'
ORDER BY expires_at;

-- Xem seats đang bị lock
SELECT id, seat_label, status, locked_by, locked_until
FROM seats
WHERE status = 'LOCKED';
```

### 5.6 Strategy F — Queue-based (impl/queue-based)

**Mục tiêu debug:** Thấy Redis queue, worker processing, back-pressure.

```bash
git checkout impl/queue-based
make db-reset && make migrate && make seed
make app-run
```

**Debug Redis queue:**
```bash
# Xem queue length
docker compose exec redis redis-cli LLEN queue:event:1

# Xem queue contents (không consume)
docker compose exec redis redis-cli LRANGE queue:event:1 0 -1

# Monitor Redis commands real-time
docker compose exec redis redis-cli MONITOR
# → Thấy LPUSH (enqueue), BRPOP (dequeue) khi request đến
```

**Debug points:**
- `QueueTicketingStrategy.java` → enqueue + CompletableFuture.get()
- `QueueWorker.java` → BRPOP loop + process
- `PerEventQueueService.java` → Redis LPUSH/BRPOP/LLEN
- Back-pressure: khi queue size > max-size → reject 503

---

## 6. Chạy Benchmark

### 6.1 Quy trình benchmark chuẩn

```bash
# ⚠️ QUAN TRỌNG: PHẢI reset DB trước mỗi benchmark run!

# 1. Checkout strategy branch
git checkout impl/pessimistic-locking

# 2. Reset DB → fresh state
make db-reset
make migrate
make seed

# 3. Start app (terminal 1)
make app-run

# 4. Chạy benchmark (terminal 2)
make bench:pessimistic          # B1: Stock=1
# ↑ Ghi lại kết quả

# 5. Reset DB → chạy benchmark tiếp
make db-reset && make migrate && make seed
make hot-seat:pessimistic       # B2: Hot-Seat

make db-reset && make migrate && make seed
make burst:pessimistic          # B3: Burst
```

### 6.2 Tùy chỉnh benchmark

```bash
# Đổi số VUs
k6 run load-test/stock1.js -e BASE_URL=http://localhost:8080 -e STRATEGY=occ -e VUS=50

# Đổi target seat
k6 run load-test/stock1.js -e SEAT_LABEL=A50

# Burst: đổi peak VUs và duration
k6 run load-test/burst.js -e PEAK_VUS=500 -e SUSTAINED=60s

# Hot-seat: đổi hot ratio
k6 run load-test/hot-seat.js -e HOT_RATIO=0.95 -e HOT_SEATS=5
```

### 6.3 Kiểm tra kết quả sau benchmark

```bash
# Kiểm tra stats
curl http://localhost:8080/events/1/stats | jq

# Kỳ vọng:
# {
#   "consistent": true,           ← available + sold = total
#   "soldCount": <number>,        ← phải <= totalSeats
#   "metrics": {
#     "oversells": 0,             ← PHẢI = 0 (trừ Naive)
#     "conflicts": <number>,
#     "retries": <number>,
#     "deadlocks": <number>
#   }
# }

# Kiểm tra DB trực tiếp
docker compose exec postgres psql -U ticketing -d ticketing -c "
  SELECT e.available_seats, COUNT(t.id) as sold,
         e.total_seats,
         e.available_seats + COUNT(t.id) = e.total_seats as consistent
  FROM events e LEFT JOIN tickets t ON e.id = t.event_id
  WHERE e.id = 1 GROUP BY e.id;
"
```

---

## 7. Database — Truy vấn & Debug

### 7.1 Kết nối PostgreSQL

```bash
# Qua Docker
docker compose exec postgres psql -U ticketing -d ticketing

# Hoặc qua psql local (nếu cài)
psql -h localhost -p 5432 -U ticketing -d ticketing
# Password: ticketing
```

### 7.2 Truy vấn hữu ích

```sql
-- === EVENT STATE ===
-- Xem event hiện tại
SELECT * FROM events WHERE id = 1;

-- Xem tất cả seats và trạng thái
SELECT seat_label, status, locked_by, locked_until
FROM seats WHERE event_id = 1
ORDER BY seat_label;

-- Đếm seats theo status
SELECT status, COUNT(*) FROM seats WHERE event_id = 1 GROUP BY status;

-- === TICKETS ===
-- Xem tickets đã bán
SELECT t.id, s.seat_label, t.user_id, t.idempotency_key, t.created_at
FROM tickets t JOIN seats s ON t.seat_id = s.id
WHERE t.event_id = 1
ORDER BY t.created_at;

-- Phát hiện double-book
SELECT seat_id, COUNT(*) as ticket_count
FROM tickets WHERE event_id = 1
GROUP BY seat_id HAVING COUNT(*) > 1;
-- → Kết quả phải RỖNG (0 rows)

-- === CONSISTENCY CHECK ===
SELECT
  e.total_seats,
  e.available_seats,
  COUNT(t.id) as sold_count,
  e.available_seats + COUNT(t.id) as sum_check,
  (e.available_seats + COUNT(t.id) = e.total_seats) as consistent
FROM events e
LEFT JOIN tickets t ON e.id = t.event_id
WHERE e.id = 1
GROUP BY e.id;

-- === RESERVATIONS (Strategy E only) ===
SELECT r.id, s.seat_label, r.status, r.fencing_token,
       r.expires_at, r.expires_at < NOW() as expired
FROM reservations r JOIN seats s ON r.seat_id = s.id
WHERE r.event_id = 1
ORDER BY r.created_at;

-- === LOCK MONITORING ===
-- Xem active locks
SELECT pid, locktype, relation::regclass, mode, granted
FROM pg_locks
WHERE relation IS NOT NULL
ORDER BY relation, pid;

-- Xem blocked queries
SELECT blocked_locks.pid AS blocked_pid,
       blocked_activity.query AS blocked_query,
       blocking_locks.pid AS blocking_pid,
       blocking_activity.query AS blocking_query
FROM pg_catalog.pg_locks blocked_locks
JOIN pg_catalog.pg_stat_activity blocked_activity ON blocked_activity.pid = blocked_locks.pid
JOIN pg_catalog.pg_locks blocking_locks ON blocking_locks.locktype = blocked_locks.locktype
  AND blocking_locks.relation = blocked_locks.relation
  AND blocking_locks.pid != blocked_locks.pid
JOIN pg_catalog.pg_stat_activity blocking_activity ON blocking_activity.pid = blocking_locks.pid
WHERE NOT blocked_locks.granted;

-- === PERFORMANCE ===
-- Connection pool usage
SELECT count(*) as total_connections,
       count(*) FILTER (WHERE state = 'active') as active,
       count(*) FILTER (WHERE state = 'idle') as idle,
       count(*) FILTER (WHERE wait_event_type = 'Lock') as waiting_for_lock
FROM pg_stat_activity
WHERE datname = 'ticketing';
```

### 7.3 Bật PostgreSQL verbose logging (debug mode)

```bash
# Tạm bật log tất cả SQL statements
docker compose exec postgres psql -U ticketing -d ticketing -c "
  ALTER SYSTEM SET log_statement = 'all';
  SELECT pg_reload_conf();
"

# Xem logs
docker compose logs -f postgres

# Tắt lại sau khi debug xong
docker compose exec postgres psql -U ticketing -d ticketing -c "
  ALTER SYSTEM SET log_statement = 'none';
  SELECT pg_reload_conf();
"
```

---

## 8. Redis — Inspect & Debug

### 8.1 Kết nối Redis

```bash
# Qua Docker
docker compose exec redis redis-cli

# Hoặc local
redis-cli -h localhost -p 6379
```

### 8.2 Commands hữu ích

```bash
# === IDEMPOTENCY KEYS ===
# Xem tất cả idempotency keys
docker compose exec redis redis-cli KEYS "idempotency:*"

# Xem value của 1 key (cached response)
docker compose exec redis redis-cli GET "idempotency:test-key-001"
# Format: "STATUS_CODE\nRESPONSE_BODY"
# Ví dụ: "201\n{\"status\":\"SUCCESS\",\"ticketId\":42}"

# Xem TTL còn lại
docker compose exec redis redis-cli TTL "idempotency:test-key-001"

# Xóa 1 idempotency key (cho phép retry)
docker compose exec redis redis-cli DEL "idempotency:test-key-001"

# Xóa TẤT CẢ idempotency keys
docker compose exec redis redis-cli KEYS "idempotency:*" | xargs -I {} docker compose exec redis redis-cli DEL {}

# === QUEUE (Strategy F only) ===
# Xem queue length
docker compose exec redis redis-cli LLEN "queue:event:1"

# Xem queue contents
docker compose exec redis redis-cli LRANGE "queue:event:1" 0 -1

# === MONITORING ===
# Real-time command monitoring (rất hữu ích khi debug!)
docker compose exec redis redis-cli MONITOR

# Memory usage
docker compose exec redis redis-cli INFO memory

# Stats
docker compose exec redis redis-cli INFO stats

# === FLUSH (cẩn thận!) ===
# Xóa tất cả data trong Redis
docker compose exec redis redis-cli FLUSHALL
```

---

## 9. Observability

### 9.1 Stats Endpoint

```bash
# Endpoint chính — aggregate tất cả thông tin
curl http://localhost:8080/events/1/stats | jq

# Response format:
# {
#   "eventId": 1,
#   "eventName": "Concert Alpha",
#   "totalSeats": 100,
#   "availableSeats": 42,
#   "soldCount": 58,
#   "consistent": true,
#   "strategy": "pessimistic",
#   "metrics": {
#     "conflicts": 12,
#     "retries": 15,
#     "deadlocks": 3,
#     "timeouts": 1,
#     "oversells": 0,
#     "successes": 58,
#     "failures": 5
#   }
# }
```

### 9.2 Actuator Endpoints

```bash
# Health check
curl http://localhost:8080/actuator/health | jq

# Tất cả metrics
curl http://localhost:8080/actuator/metrics | jq

# Specific metric
curl http://localhost:8080/actuator/metrics/ticketing.conflicts | jq
curl http://localhost:8080/actuator/metrics/ticketing.retries | jq

# Prometheus format (cho Grafana)
curl http://localhost:8080/actuator/prometheus | grep ticketing

# HikariCP pool stats
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq
curl http://localhost:8080/actuator/metrics/hikaricp.connections.pending | jq
```

### 9.3 So sánh metrics giữa strategies

```bash
# Script: chạy benchmark rồi collect stats
STRATEGY="pessimistic"
curl -s http://localhost:8080/events/1/stats | jq "{
  strategy: .strategy,
  sold: .soldCount,
  available: .availableSeats,
  consistent: .consistent,
  conflicts: .metrics.conflicts,
  retries: .metrics.retries,
  deadlocks: .metrics.deadlocks,
  oversells: .metrics.oversells
}"
```

---

## 10. Troubleshooting

### 10.1 Docker / Infrastructure

| Lỗi | Nguyên nhân | Fix |
|-----|-------------|-----|
| `open //./pipe/dockerDesktopLinuxEngine` | Docker Desktop chưa start | Start Docker Desktop, đợi 30s |
| `port 5432 already in use` | PostgreSQL khác đang chạy | Stop local PG hoặc đổi port trong docker-compose.yml |
| `port 6379 already in use` | Redis khác đang chạy | Stop local Redis |
| `unable to get image` | Docker pull failed | Kiểm tra internet, `docker pull postgres:16-alpine` |
| Container unhealthy | PG chưa ready | `docker compose logs postgres`, đợi thêm |

### 10.2 Build / Compilation

| Lỗi | Nguyên nhân | Fix |
|-----|-------------|-----|
| `incompatible types: Map.of()` | Java type inference issue | Dùng `new LinkedHashMap<>()` thay vì `Map.of()` |
| `package does not exist` | Dependency missing | `mvn clean install -DskipTests` |
| `not on classpath` (IDE warning) | IDE chưa sync | Reimport Maven project (Ctrl+Shift+O trong IntelliJ) |
| `Compilation failure` | Syntax error hoặc missing import | Đọc error message, check line number |
| `No qualifying bean` | Strategy bean not configured | Check `StrategyConfig.java` + `application.yml` |

### 10.3 Runtime

| Lỗi | Nguyên nhân | Fix |
|-----|-------------|-----|
| `Connection refused: localhost:5432` | PG container not running | `make db-up` |
| `Connection refused: localhost:6379` | Redis container not running | `make db-up` |
| `Table 'events' doesn't exist` | Migration chưa chạy | `make migrate` |
| `No data` / `404 Not Found` | Seed chưa chạy | `make seed` |
| `HikariPool: Connection is not available` | Pool exhausted | Tăng `maximum-pool-size` trong application.yml |
| `Flyway migration checksum mismatch` | Schema file bị sửa | `make db-reset && make migrate` |
| `SerializationFailureException` | Expected! (Strategy D) | Retry logic sẽ handle |
| `OCC conflict` | Expected! (Strategy C) | Retry logic sẽ handle |

### 10.4 Benchmark

| Lỗi | Nguyên nhân | Fix |
|-----|-------------|-----|
| k6: `connection refused` | App chưa chạy | Start app trước: `make app-run` |
| `all requests failed` | Sai BASE_URL | Check `-e BASE_URL=http://localhost:8080` |
| `0 successes` | Seed data hết / chưa reset | `make db-reset && make migrate && make seed` |
| `oversell detected` | Strategy A (expected) hoặc bug | Nếu không phải Naive → BUG! Check code |

---

## 11. Extensions & Tools

### 11.1 VS Code / Windsurf Extensions

| Extension | Mục đích | Tại sao cần |
|-----------|----------|-------------|
| **Extension Pack for Java** | Java development | Syntax, IntelliSense, debug |
| **Spring Boot Extension Pack** | Spring Boot support | Boot dashboard, application.yml intellisense |
| **REST Client** | Test API trong IDE | Thay thế curl, lưu request trong `.http` files |
| **Database Client** | Connect PostgreSQL | Chạy SQL trực tiếp, xem tables |
| **Redis** (by cweijan) | Connect Redis | Xem keys, values, TTL |
| **Docker** | Manage containers | Start/stop/logs từ IDE |
| **Thunder Client** | API testing GUI | Postman alternative trong IDE |
| **GitLens** | Git blame & history | Xem ai commit gì, khi nào |
| **YAML** | YAML support | application.yml, docker-compose.yml |
| **Markdown Preview** | Preview .md files | Đọc docs ngay trong IDE |

### 11.2 IntelliJ IDEA

| Feature | Cách dùng |
|---------|-----------|
| **Database tool** | View → Tool Windows → Database → Add PG connection |
| **HTTP Client** | New → HTTP Request file → test APIs |
| **Spring Boot Run** | Right-click TicketingApplication → Run |
| **Debug mode** | Right-click → Debug → set breakpoints |
| **Services tab** | View → Tool Windows → Services → Docker |

### 11.3 Command-line Tools

| Tool | Cài đặt | Mục đích |
|------|---------|----------|
| **jq** | `winget install jqlang.jq` | Format JSON output |
| **httpie** | `pip install httpie` | Thay curl, syntax đẹp hơn |
| **pgcli** | `pip install pgcli` | PostgreSQL CLI với auto-complete |
| **lazydocker** | `winget install jesseduffield.lazydocker` | Docker TUI — quản lý container |
| **k9s** | `winget install derailed.k9s` | Kubernetes TUI (nếu deploy K8s) |

---

## 12. Tips & Tricks

### 12.1 Development Workflow

```bash
# Tip 1: Luôn reset DB trước benchmark
make db-reset && make migrate && make seed

# Tip 2: Mở 3 terminal cùng lúc
# Terminal 1: make app-run (app)
# Terminal 2: docker compose logs -f (logs)
# Terminal 3: curl / k6 (testing)

# Tip 3: Quick API test
alias buy='curl -s -X POST http://localhost:8080/tickets/reserve-and-buy \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"eventId\":1, \"userId\":\"$(uuidgen)\", \"seatLabel\":\"A1\"}" | jq'

# Tip 4: Watch stats real-time
watch -n 1 'curl -s http://localhost:8080/events/1/stats | jq'
```

### 12.2 Debug Concurrent Issues

```bash
# Tip 5: Tạo concurrent requests nhanh
for i in $(seq 1 20); do
  curl -s -X POST http://localhost:8080/tickets/reserve-and-buy \
    -H "Content-Type: application/json" \
    -d "{\"eventId\":1, \"userId\":\"$(uuidgen)\", \"seatLabel\":\"A1\"}" &
done
wait
curl http://localhost:8080/events/1/stats | jq

# Tip 6: Monitor PG locks real-time
watch -n 0.5 'docker compose exec postgres psql -U ticketing -d ticketing -c "
  SELECT pid, wait_event_type, wait_event, state, left(query,60)
  FROM pg_stat_activity WHERE datname = '\''ticketing'\'' AND state != '\''idle'\''
"'

# Tip 7: Redis MONITOR cho queue debug
docker compose exec redis redis-cli MONITOR
# Chạy request trong terminal khác → thấy LPUSH/BRPOP real-time
```

### 12.3 Performance Tuning

```yaml
# application.yml tuning tips:

# Tip 8: Tăng connection pool cho benchmark
spring.datasource.hikari:
  maximum-pool-size: 100     # Default 50, tăng nếu thấy pool exhaustion
  connection-timeout: 10000  # Default 5000, tăng cho Pessimistic strategy

# Tip 9: Retry tuning cho OCC
ticketing.retry:
  max-attempts: 10           # Tăng cho extreme contention
  initial-delay-ms: 20       # Giảm cho faster retry
  max-delay-ms: 5000         # Cap retry delay
  jitter: true               # LUÔN BẬT — tránh thundering herd

# Tip 10: Queue tuning
ticketing.queue:
  max-size: 10000            # Tăng cho burst scenario
  worker-threads: 1          # KHÔNG TĂNG — 1 worker = zero contention
  poll-timeout-seconds: 5    # Worker BRPOP timeout
```

### 12.4 Giữ sạch workspace

```bash
# Tip 11: Clean build artifacts
make clean

# Tip 12: Reset everything
make db-reset && make migrate && make seed

# Tip 13: Flush Redis (xóa idempotency keys + queue)
docker compose exec redis redis-cli FLUSHALL

# Tip 14: Nuclear option — xóa hết containers + volumes
docker compose down -v
docker compose up -d postgres redis
```

### 12.5 Học hiệu quả

```
Tip 15: Đọc docs TRƯỚC code
  docs/plan.md → hiểu big picture
  docs/2.X-*.md → hiểu strategy (trên branch tương ứng)
  Rồi mới đọc source code

Tip 16: So sánh strategies bằng diff
  git diff impl/naive..impl/pessimistic-locking -- source/
  → Thấy CHÍNH XÁC sự khác biệt giữa 2 strategies

Tip 17: Chạy Naive TRƯỚC
  → Thấy oversell → hiểu TẠI SAO cần các strategy khác

Tip 18: Đọc REPORT.md cuối cùng
  docs/REPORT.md → tổng hợp trade-offs, decision framework
```

---

## 13. Glossary

| Thuật ngữ | Giải thích |
|-----------|-----------|
| **Contention** | Nhiều threads/processes tranh giành cùng 1 resource |
| **Oversell** | Bán nhiều vé hơn số ghế → vi phạm invariant |
| **Double-book** | 1 ghế bán cho 2 người → vi phạm invariant |
| **MVCC** | Multi-Version Concurrency Control — PostgreSQL snapshot isolation |
| **TOCTOU** | Time-Of-Check to Time-Of-Use — race condition giữa check và update |
| **SELECT FOR UPDATE** | Pessimistic lock — khóa row, threads khác phải chờ |
| **OCC** | Optimistic Concurrency Control — version-based conflict detection |
| **SSI** | Serializable Snapshot Isolation — PostgreSQL implementation |
| **Fencing Token** | UUID prevent stale operations trên expired reservations |
| **TTL** | Time-To-Live — auto-expire data (Redis keys, reservations) |
| **Back-pressure** | Reject request khi system overload (503) |
| **Idempotency** | Gửi request N lần = kết quả giống lần đầu |
| **SET NX** | Redis Set-If-Not-Exists — atomic claim operation |
| **BRPOP** | Redis Blocking Right Pop — block until item available |
| **HikariCP** | JDBC connection pool — quản lý DB connections |
| **Micrometer** | Metrics library — counters, gauges, timers |
| **Flyway** | Database migration tool — versioned SQL scripts |
| **k6** | Load testing tool — JavaScript-based, modern |
| **VU** | Virtual User — simulated concurrent user trong k6 |
| **p95/p99** | Percentile latency — 95%/99% requests dưới giá trị này |
| **WAL** | Write-Ahead Log — PostgreSQL durability mechanism |
| **CAS** | Compare-And-Swap — atomic CPU operation, dùng trong counters |
