# Implementation Plan — High Contention Ticketing System

> **Designing a Write-Scalable Ticketing Service under High Contention**
>
> 6 chiến lược xử lý contention, từ Naive (broken) → Queue-based (production-grade).
> Mục tiêu: hiểu *tại sao* trước khi hỏi *làm thế nào*.

---

## Mục tiêu học tập

- Hiểu tại sao concurrent writes vào shared resource là bài toán khó — từ MVCC, lock manager, WAL level
- Nắm failure mode của từng chiến lược qua benchmark thực tế — không chỉ lý thuyết
- Biết chọn chiến lược phù hợp cho 2 bối cảnh: **single DB high contention** vs **multi-instance UX**
- Tích lũy decision framework: cho bối cảnh X, chọn Y vì Z, chấp nhận trade-off W

---

## Invariants bắt buộc — KHÔNG được vi phạm

1. **available_seats ≥ 0** — số ghế trống KHÔNG bao giờ âm
2. **Không double-book** — 1 seat không được bán cho 2 user khác nhau
3. **Không oversell** — `sold_count <= total_seats` tại mọi thời điểm
4. **Fencing token validity** — token chỉ hợp lệ nếu đúng giá trị VÀ reservation chưa hết hạn (TTL)

---

## Coverage map — 6 phiên bản

| Phiên bản | Chiến lược | Branch | Task | Tầng |
|-----------|-----------|--------|------|------|
| A | Naive (intentionally broken) | `impl/naive` | 2.A | 1 — Baseline |
| B | Pessimistic Locking (SELECT FOR UPDATE) | `impl/pessimistic-locking` | 2.B | 2 — Core |
| C | OCC + Exponential Backoff Retry | `impl/occ` | 2.C | 2 — Core |
| D | SERIALIZABLE Isolation + Retry | `impl/serializable` | 2.D | 2 — Core |
| E | Reservation + TTL + Fencing Token | `impl/reservation-fencing` | 2.E | 3 — UX |
| F | Queue-based per-event (Redis) | `impl/queue-based` | 2.F | 3 — UX |

---

## Branch Strategy

```
main                              ← foundation, shared code, Makefile
├── impl/naive                    ← Version A — intentionally broken baseline
├── impl/pessimistic-locking      ← Version B
├── impl/occ                      ← Version C
├── impl/serializable             ← Version D
├── impl/reservation-fencing      ← Version E
├── impl/queue-based              ← Version F
└── benchmark/results             ← kết quả benchmark — KHÔNG merge vào impl/*
```

**Quy tắc:**
- Mỗi `impl/*` branch chạy **độc lập**: `make db-up && make bench:<version>`
- Shared code thay đổi trên `main` → merge vào `impl/*` (không ngược lại)
- `benchmark/results` chỉ chứa kết quả `.md` — không chứa code

---

## Learning Path — Progression từ Broken → Production-grade

```
Naive (A)          "Tôi hiểu race condition xảy ra như thế nào"
    ↓
Pessimistic (B)    "Tôi hiểu row-level lock giải quyết correctness nhưng giết throughput"
    ↓
OCC (C)            "Tôi hiểu optimistic approach tốt hơn khi contention thấp-trung bình"
    ↓
SERIALIZABLE (D)   "Tôi hiểu DB-level isolation vs application-level control"
    ↓
Reservation (E)    "Tôi hiểu cách tách UX flow khỏi contention window"
    ↓
Queue (F)          "Tôi hiểu serialize-at-ingress pattern cho production"
```

---

## Tầng 0 — Foundation (branch: `main`)

| Task | Mô tả | Size | Output | Dependency |
|------|--------|------|--------|------------|
| 1.1 | Docker Compose + Makefile | S | `make db-up` khởi động PostgreSQL 16 + Redis 7 | — |
| 1.2 | Flyway schema migration | M | Tables: `events`, `seats`, `tickets`, `reservations` | 1.1 |
| 1.3 | Seed data | S | `make seed` tạo 1 event + 100 seats | 1.2 |
| 1.4 | Shared utilities | M | `IdempotencyFilter`, `ConflictMetrics`, `RetryWithBackoff` | 1.2 |

**Chi tiết Foundation:**

### 1.1 — Docker Compose + Makefile
- `docker-compose.yml`: PostgreSQL 16 (port 5432) + Redis 7 (port 6379)
- `Makefile` với targets: `db-up`, `db-down`, `migrate`, `seed`, `bench:naive`, `bench:pessimistic`, `bench:occ`, `bench:serializable`, `bench:reservation`, `bench:queue`
- Health check cho cả PG và Redis
- Volume mount cho persistent data (dev only)

### 1.2 — Flyway Schema Migration
- `db/migrations/V1__init_schema.sql`
- Tables:
  - `events` (id, name, total_seats, available_seats, version, created_at)
  - `seats` (id, event_id, seat_label, status ENUM, locked_by, locked_until)
  - `tickets` (id, event_id, seat_id, user_id, idempotency_key, created_at)
  - `reservations` (id, event_id, seat_id, user_id, fencing_token, expires_at, status, created_at)
- Indexes: event_id trên seats, tickets; unique constraint trên (event_id, seat_id) trong tickets
- CHECK constraint: `available_seats >= 0`

### 1.3 — Seed Data
- `db/seeds/seed_events.sql`
- 1 event "Concert Alpha" với 100 seats (A1-A100)
- available_seats = 100, version = 0

### 1.4 — Shared Utilities
- `IdempotencyFilter.java` — servlet filter, check Redis/DB cho duplicate idempotency_key
- `ConflictMetrics.java` — Micrometer counters: conflicts, retries, deadlocks, timeouts
- `RetryWithBackoff.java` — generic retry with exponential backoff + jitter, dùng cho 2.C, 2.D
- `TicketingStrategy.java` — Strategy Pattern interface

---

## Tầng 1 — Baseline Broken (branch: `impl/naive`)

| Task | Mô tả | Size | Output | Dependency |
|------|--------|------|--------|------------|
| 2.A | Naive baseline — intentionally broken | S | `NaiveTicketingStrategy.java` — oversell xảy ra | 1.1–1.4 |

**Chi tiết 2.A:**
- SELECT available_seats → check > 0 → UPDATE available_seats - 1 → INSERT ticket
- **Không có locking, không có version check** — TOST (Time-Of-Check to Time-Of-Use)
- Mục đích: chứng minh bằng benchmark rằng oversell xảy ra dưới concurrent load
- Race condition timeline sẽ được document chi tiết trong `docs/2.A-naive-baseline.md`

---

## Tầng 2 — Core Concurrency Strategies

| Task | Mô tả | Size | Branch | Dependency |
|------|--------|------|--------|------------|
| 2.B | Pessimistic Locking | M | `impl/pessimistic-locking` | 1.1–1.4 |
| 2.C | OCC + Exponential Backoff Retry | M | `impl/occ` | 1.1–1.4 |
| 2.D | SERIALIZABLE Isolation + Retry | M | `impl/serializable` | 1.1–1.4 |

**Chi tiết 2.B — Pessimistic Locking:**
- `SELECT ... FOR UPDATE` trên events row trước khi kiểm tra available_seats
- Lock manager queue: threads block cho đến khi lock released
- Trade-off: correctness 100%, throughput = 1/tx_time (serialize hoàn toàn)
- Failure modes: deadlock khi multi-row, connection pool exhaustion dưới spike
- Document: ordered locking strategy để tránh deadlock

**Chi tiết 2.C — OCC (Optimistic Concurrency Control):**
- Dùng `version` column trên `events` table
- `UPDATE events SET available_seats = available_seats - 1, version = version + 1 WHERE id = ? AND version = ?`
- Nếu affected rows = 0 → conflict → retry với exponential backoff + jitter
- Trade-off: không blocking, nhưng retry storm dưới high contention
- Max retries configurable, conflict counter cho observability

**Chi tiết 2.D — SERIALIZABLE Isolation:**
- `@Transactional(isolation = Isolation.SERIALIZABLE)`
- PostgreSQL SSI (Serializable Snapshot Isolation) phát hiện serialization anomalies
- Catch `SerializationFailureException` → retry
- Trade-off: DB tự đảm bảo correctness, nhưng đắt hơn về CPU/memory (predicate locks)
- So sánh với OCC: DB-level control vs application-level control

---

## Tầng 3 — UX-Oriented Strategies

| Task | Mô tả | Size | Branch | Dependency |
|------|--------|------|--------|------------|
| 2.E | Reservation + TTL + Fencing Token | L | `impl/reservation-fencing` | 1.1–1.4 |
| 2.F | Queue-based per-event (Redis) | L | `impl/queue-based` | 1.1–1.4 |

**Chi tiết 2.E — Reservation + TTL + Fencing:**
- 2-phase flow: `POST /reservations` → (user thanh toán) → `POST /reservations/{id}/confirm`
- TTL: reservation expires sau N phút → background job cleanup
- Fencing token: UUID sinh khi reserve, phải khớp khi confirm → chống stale confirm
- Tách contention window (reserve nhanh) khỏi payment flow (confirm chậm)
- Lock window chỉ vài ms (khi reserve), không hold lock suốt payment

**Chi tiết 2.F — Queue-based per-event:**
- Redis LIST per event: `queue:event:{id}`
- Request vào queue → worker consume tuần tự → serialize writes hoàn toàn
- Client poll hoặc SSE để nhận kết quả
- Trade-off: latency tăng (qua queue), nhưng throughput ổn định, không conflict
- Production-grade: đây là pattern Ticketmaster/12306 dùng

---

## Tầng 4 — Cross-cutting Concerns

| Task | Mô tả | Size | Output | Dependency |
|------|--------|------|--------|------------|
| 3.1 | Idempotency | M | Duplicate request → same 200, không double-insert | 1.4, 2.A–2.F |
| 3.2 | Observability | M | `GET /events/{id}/stats` endpoint | 1.4, 2.A–2.F |

**Chi tiết 3.1 — Idempotency:**
- Header `Idempotency-Key` trên mỗi request
- Check Redis trước (fast path) → check DB nếu Redis miss
- Nếu key đã tồn tại → trả lại response cũ (200), KHÔNG tạo ticket mới
- Apply cho tất cả 6 branches

**Chi tiết 3.2 — Observability:**
- `GET /events/{id}/stats` trả về:
  - `total_seats`, `available_seats`, `sold_count`
  - `conflict_count`, `retry_count`, `deadlock_count`, `timeout_count`
- Micrometer counters + `/actuator/metrics` endpoint
- Dashboard-ready data cho benchmark analysis

---

## Tầng 5 — Benchmark + Report

| Task | Mô tả | Size | Output | Dependency |
|------|--------|------|--------|------------|
| 4.1 | k6 scripts cho 3 kịch bản | M | `load-test/stock1.js`, `hot-seat.js`, `burst.js` | 2.A–2.F |
| 4.2 | Bảng so sánh A-F | M | `docs/bench-final.md` | 4.1 + kết quả benchmark |
| 4.3 | REPORT.md | L | 5-10 trang phân tích + 2 khuyến nghị bối cảnh | 4.2 |

**3 kịch bản benchmark:**

| ID | Kịch bản | Setup | Mục tiêu đo |
|----|----------|-------|-------------|
| B1 | Stock=1 | 1 event, 1 ghế, 5-10k requests/30s | Oversell count, correctness |
| B2 | Hot-seat | Seat "A15" được 2000+ req/s nhắm vào | Conflicts, deadlocks, retry rate |
| B3 | Burst | 3 đợt × 1000 requests trong 1s | Recovery time, error spike pattern |

**Bảng so sánh cuối cùng (docs/bench-final.md):**

| Version | B1 oversell | B1 p95 | B2 conflicts | B2 deadlocks | B3 max-RPS | Retry rate |
|---------|-------------|--------|--------------|--------------|------------|------------|
| A Naive | | | | | | |
| B Pessimistic | | | | | | |
| C OCC | | | | | | |
| D Serializable | | | | | | |
| E Reservation | | | | | | |
| F Queue | | | | | | |

> Bảng này sẽ được điền bởi BENCH AGENT sau khi chạy benchmark thực tế.
> KHÔNG điền số liệu giả.

---

## Thứ tự thực hiện khuyến nghị

```
Bước 0:   @plan                          ← ✅ DONE — tạo plan + branches

── Tầng 0: Foundation (branch: main) ──
Bước 1:   @tech 1.1                      ← Docker Compose + Makefile
Bước 2:   @tech 1.2                      ← Flyway schema migration
Bước 3:   @tech 1.3                      ← Seed data
Bước 4:   @tech 1.4                      ← Shared utilities

── Tầng 1: Baseline broken ──
Bước 5:   @tech 2.A                      ← Naive (impl/naive) — intentionally broken
Bước 6:   @bench B1                      ← Chứng minh oversell
Bước 7:   @bench update B1 [kết quả A]  ← Ghi baseline

── Tầng 2: Core strategies ──
Bước 8:   @tech 2.B                      ← Pessimistic (impl/pessimistic-locking)
Bước 9:   @bench update B1 [kết quả B]  ← So sánh A vs B
Bước 10:  @tech 2.C                      ← OCC (impl/occ)
Bước 11:  @bench update B1 [kết quả C]  ← So sánh A vs B vs C
Bước 12:  @tech 2.D                      ← SERIALIZABLE (impl/serializable)
Bước 13:  @bench B2                      ← Hot-seat scenario

── Tầng 3: UX-oriented ──
Bước 14:  @tech 2.E                      ← Reservation+Fencing (impl/reservation-fencing)
Bước 15:  @tech 2.F                      ← Queue-based (impl/queue-based)
Bước 16:  @bench B3                      ← Burst scenario

── Tầng 4: Cross-cutting ──
Bước 17:  @tech 3.1                      ← Idempotency (merge vào tất cả branches)
Bước 18:  @tech 3.2                      ← Observability + /stats

── Tầng 5: Report ──
Bước 19:  @bench final                   ← Tổng hợp bảng so sánh A-F
Bước 20:  @tech 4.3                      ← REPORT.md đầy đủ
```

---

## Task Checklist

### Tầng 0 — Foundation
- [ ] 1.1 Docker Compose + Makefile | S | Output: `make db-up` chạy được
- [ ] 1.2 Flyway schema migration | M | Output: events, seats, tickets, reservations tables
- [ ] 1.3 Seed data | S | Output: `make seed` tạo 1 event + 100 seats
- [ ] 1.4 Shared utilities | M | Output: IdempotencyFilter, ConflictMetrics, RetryWithBackoff

### Tầng 1 — Baseline
- [ ] 2.A Naive baseline | S | Branch: `impl/naive`

### Tầng 2 — Core Strategies
- [ ] 2.B Pessimistic Locking | M | Branch: `impl/pessimistic-locking`
- [ ] 2.C OCC + retry | M | Branch: `impl/occ`
- [ ] 2.D SERIALIZABLE | M | Branch: `impl/serializable`

### Tầng 3 — UX Strategies
- [ ] 2.E Reservation + TTL + Fencing | L | Branch: `impl/reservation-fencing`
- [ ] 2.F Queue-based per-event | L | Branch: `impl/queue-based`

### Tầng 4 — Cross-cutting
- [ ] 3.1 Idempotency | M | Output: duplicate → same 200, không double-insert
- [ ] 3.2 Observability | M | Output: `/events/{id}/stats` endpoint

### Tầng 5 — Benchmark + Report
- [ ] 4.1 k6 scripts | M | Output: stock1.js, hot-seat.js, burst.js
- [ ] 4.2 Bảng so sánh A-F | M | Output: docs/bench-final.md
- [ ] 4.3 REPORT.md | L | Output: 5-10 trang phân tích + khuyến nghị
