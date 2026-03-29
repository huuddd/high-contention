# REPORT — High Contention Ticketing System

> Trade-off Analysis & Strategy Selection Guide
>
> 6 chiến lược xử lý contention, phân tích từ first principles,
> với khuyến nghị cho 2 bối cảnh production thực tế.

---

## 1. Executive Summary

Dự án implement 6 chiến lược xử lý concurrent writes vào shared resource (ghế ngồi),
từ **Naive** (broken by design) đến **Queue-based** (production-grade).

**Kết luận chính:**
- Naive (A) chứng minh race condition xảy ra — **không bao giờ dùng trong production**
- Pessimistic (B) đảm bảo correctness nhưng **giết throughput** dưới high contention
- OCC (C) tốt cho **medium contention** — retry storm là bottleneck khi contention cao
- SERIALIZABLE (D) tương tự OCC nhưng **DB quản lý** thay vì application
- Reservation (E) tách UX flow khỏi contention — **tốt nhất cho checkout flow**
- Queue (F) serialize tại ingress — **tốt nhất cho flash sale extreme contention**

---

## 2. Bài toán — Tại sao khó?

### 2.1 Shared Mutable State

```
100 seats, 1000 users → mỗi user muốn 1 seat
→ 1000 concurrent writes vào cùng 1 row (events.available_seats)
→ Nếu không serialize: race condition → oversell
```

### 2.2 Invariants phải giữ

| # | Invariant | Vi phạm → Hậu quả |
|---|-----------|-------------------|
| 1 | `available_seats >= 0` | Bán vé ma — revenue loss + legal |
| 2 | 1 seat = 1 user | Double-book — 2 người cùng ghế |
| 3 | `sold_count <= total_seats` | Oversell — nhiều vé hơn ghế |
| 4 | Fencing token valid + not expired | Stale confirm → corrupt state |

### 2.3 CAP Trade-off trong bối cảnh ticketing

```
Consistency ←→ Availability ←→ Partition Tolerance

Ticketing system: Consistency > Availability
  → Thà từ chối request (409) còn hơn oversell
  → Đây là lý do tại sao mọi strategy (B-F) đều chấp nhận failure rate
```

---

## 3. Phân tích từng Strategy

### 3.1 Version A — Naive (Intentionally Broken)

**Cơ chế:** SELECT → CHECK → UPDATE → INSERT (4 bước, không atomic)

```
T=0ms  Thread A: SELECT available_seats = 1
T=0ms  Thread B: SELECT available_seats = 1  ← cùng snapshot
T=1ms  Thread A: UPDATE available_seats = 0, INSERT ticket  ✅
T=1ms  Thread B: UPDATE available_seats = 0, INSERT ticket  ✅ OVERSELL!
```

**Root cause:** PostgreSQL MVCC — mỗi transaction thấy snapshot tại thời điểm BEGIN.
Hai transactions đọc cùng giá trị, cả hai đều nghĩ mình hợp lệ.

**Verdict:** ❌ Không bao giờ dùng. Chỉ có giá trị học tập.

---

### 3.2 Version B — Pessimistic Locking

**Cơ chế:** `SELECT ... FOR UPDATE` → exclusive row lock → serialize tất cả writers

```
T=0ms  Thread A: SELECT FOR UPDATE → acquired lock
T=0ms  Thread B: SELECT FOR UPDATE → BLOCKED (waiting for A)
T=5ms  Thread A: UPDATE + COMMIT → release lock
T=5ms  Thread B: acquired lock → sees updated data → proceeds
```

**Ưu điểm:**
- Correctness 100% — simple mental model
- Không cần retry logic
- Dễ audit, dễ giải thích cho stakeholder

**Nhược điểm:**
- **Throughput = 1/tx_time** — hoàn toàn serialize
- **Connection pool exhaustion** — 50 connections, 200 VUs → 150 waiting
- **Deadlock risk** — multi-row locking nếu không ordered
- **p95 latency tăng phi tuyến** — queue theory: wait = (N-1) × tx_time / 2

**Failure modes:**
- Deadlock: PostgreSQL 40P01 → auto-rollback 1 transaction
- Lock wait timeout: HikariCP connection-timeout = 5s → request fail
- Pool exhaustion: tất cả connections bị lock-wait → new requests timeout

**Best for:** Low-medium contention (< 50 concurrent), correctness-critical, audit-required

---

### 3.3 Version C — Optimistic Concurrency Control (OCC)

**Cơ chế:** `UPDATE ... WHERE version = ?` → conflict detection tại write time

```
T=0ms  Thread A: SELECT version=1
T=0ms  Thread B: SELECT version=1
T=1ms  Thread A: UPDATE WHERE version=1 → affected=1 ✅ (version→2)
T=1ms  Thread B: UPDATE WHERE version=1 → affected=0 ❌ (version already 2)
       Thread B: retry #1 → SELECT version=2 → UPDATE WHERE version=2 → ...
```

**Ưu điểm:**
- Không blocking — readers không chờ writers
- Tốt cho read-heavy workload
- Explicit conflict detection → application kiểm soát retry strategy

**Nhược điểm:**
- **Retry storm** dưới high contention — N threads, chỉ 1 thắng mỗi round
- **Starvation** — thread "xui" có thể retry vô hạn (cần max-retries cap)
- **Wasted work** — mỗi retry = 1 transaction bị rollback

**Retry strategy:** Exponential backoff + jitter
```
delay = min(initial * multiplier^attempt, maxDelay) + random(0, jitter)
→ Tránh thundering herd khi nhiều threads retry cùng lúc
```

**Best for:** Medium contention (50-200 concurrent), fast individual transactions

---

### 3.4 Version D — SERIALIZABLE Isolation

**Cơ chế:** `@Transactional(isolation = SERIALIZABLE)` → PostgreSQL SSI tự phát hiện anomalies

```
PostgreSQL SSI (Serializable Snapshot Isolation):
  → Tracks read/write dependencies giữa concurrent transactions
  → Phát hiện dangerous structure (cycle trong dependency graph)
  → Abort 1 transaction → application retry
```

**So sánh với OCC:**

| Aspect | OCC (C) | SERIALIZABLE (D) |
|--------|---------|-------------------|
| Conflict detection | Application (version check) | DB engine (SSI) |
| Retry trigger | affected_rows = 0 | SerializationFailureException |
| Granularity | Per-row (version column) | Per-transaction (predicate locks) |
| Overhead | Minimal (1 extra column) | Higher (predicate lock memory) |
| Flexibility | Application controls | DB controls |

**Best for:** Complex transactions cần strong consistency, team muốn DB quản lý concurrency

---

### 3.5 Version E — Reservation + TTL + Fencing Token

**Cơ chế:** 2-phase flow tách contention window khỏi payment

```
Phase 1 — Reserve (fast, ~5ms contention window):
  POST /reservations
  → Lock seat (status: AVAILABLE → LOCKED)
  → Generate fencing token (UUID)
  → Set TTL (10 minutes)
  → Return: { reservationId, fencingToken, expiresAt }

Phase 2 — Confirm (no contention):
  POST /reservations/{id}/confirm
  → Validate fencing token
  → Check not expired
  → Create ticket (LOCKED → SOLD)
  → Seat permanently sold

Background — Cleanup:
  @Scheduled(every 30s)
  → Find expired reservations
  → Release seats (LOCKED → AVAILABLE)
```

**Ưu điểm:**
- **UX tốt nhất** — user thấy "đang giữ chỗ", có thời gian thanh toán
- **Contention window cực ngắn** — chỉ vài ms khi reserve
- **Fairness** — first-come-first-served rõ ràng
- **Retry-friendly** — confirm fail → reserve vẫn còn, retry confirm

**Nhược điểm:**
- **Complexity** — 2 endpoints, background job, fencing token validation
- **Seat lock waste** — user reserve rồi bỏ → seat bị lock đến TTL
- **TTL tuning** — quá ngắn: user không kịp thanh toán, quá dài: seat bị giữ lâu

**Best for:** E-commerce checkout, booking.com-style "hold your seat", user-facing applications

---

### 3.6 Version F — Queue-based per-event (Redis)

**Cơ chế:** Serialize tại ingress — mỗi event có 1 Redis queue, 1 worker

```
Client → POST /tickets/reserve-and-buy
  → Enqueue to Redis LIST "queue:event:1" (LPUSH)
  → CompletableFuture.get(timeout) — sync-over-async wait
  → Worker BRPOP → process sequentially → complete future
  → Response trả về client

Key insight: Worker = 1 thread per event
  → Zero DB contention — chỉ 1 writer tại mọi thời điểm
  → DB operations đơn giản nhất có thể
```

**Ưu điểm:**
- **Zero contention** — serialize hoàn toàn, không conflict/retry/deadlock
- **Predictable latency** — queue depth × avg_processing_time
- **Back-pressure** — queue full → reject 503 → protect DB
- **Production-grade** — pattern của Ticketmaster, 12306, Booking.com

**Nhược điểm:**
- **Latency tăng** — qua Redis queue, worker processing
- **Redis dependency** — Redis down = toàn bộ ticket flow down
- **Complexity** — async processing, CompletableFuture, worker lifecycle
- **Single-writer bottleneck** — throughput bị giới hạn bởi 1 worker

**Best for:** Flash sale, extreme contention (1000+ concurrent), strict fairness requirement

---

## 4. Trade-off Matrix

### 4.1 Correctness vs Performance

```
                    Throughput
                    ↑
                    │
         Queue (F)  │  OCC (C) ←── sweet spot cho medium contention
                    │    SERIALIZABLE (D)
                    │
         Pessimistic│(B)
                    │
         Reservation│(E) ←── best UX, moderate throughput
                    │
                    └──────────────────────→ Latency (p95)
                                              
    Naive (A) ←── high throughput nhưng BROKEN
```

### 4.2 Complexity vs Safety

| Strategy | Implementation Complexity | Operational Complexity | Safety |
|----------|--------------------------|----------------------|--------|
| A: Naive | ⭐ (trivial) | ⭐ (nothing to operate) | ❌ Broken |
| B: Pessimistic | ⭐⭐ (1 SQL hint) | ⭐⭐ (deadlock monitoring) | ✅✅✅ |
| C: OCC | ⭐⭐⭐ (retry logic) | ⭐⭐ (retry tuning) | ✅✅✅ |
| D: SERIALIZABLE | ⭐⭐ (annotation) | ⭐⭐⭐ (SSI overhead) | ✅✅✅ |
| E: Reservation | ⭐⭐⭐⭐ (2-phase + TTL) | ⭐⭐⭐⭐ (cleanup job) | ✅✅✅ |
| F: Queue | ⭐⭐⭐⭐⭐ (Redis + worker) | ⭐⭐⭐⭐⭐ (Redis ops) | ✅✅✅ |

### 4.3 Failure Mode Comparison

| Strategy | Primary Failure | Secondary Failure | Recovery |
|----------|----------------|-------------------|----------|
| A: Naive | Oversell | Double-book | None — data corrupt |
| B: Pessimistic | Pool exhaustion | Deadlock | Auto-rollback + retry |
| C: OCC | Retry storm | Starvation | Max retries → 503 |
| D: SERIALIZABLE | Serialization retry | Higher memory | Auto-rollback + retry |
| E: Reservation | TTL expiry complexity | Orphan locks | Background cleanup |
| F: Queue | Redis failure | Queue backlog | Back-pressure 503 |

---

## 5. Decision Framework

### 5.1 Flowchart chọn Strategy

```
Q1: Có cần correctness 100%?
  NO  → Naive (A) — chỉ cho prototype/demo
  YES ↓

Q2: Contention level?
  LOW (<50 concurrent)    → Pessimistic (B) — simple, correct
  MEDIUM (50-200)         → OCC (C) hoặc SERIALIZABLE (D)
  HIGH (200+)             → Q3

Q3: Cần UX "hold your seat"?
  YES → Reservation (E) — 2-phase flow
  NO  → Queue (F) — serialize at ingress

Q4: OCC vs SERIALIZABLE?
  Team muốn application control → OCC (C)
  Team muốn DB handle it        → SERIALIZABLE (D)
  Complex multi-table txn        → SERIALIZABLE (D)
  Single-row update             → OCC (C) — simpler
```

---

## 6. Khuyến nghị — 2 bối cảnh Production

### 6.1 Bối cảnh 1: Single DB, High Contention (Flash Sale)

> **Scenario:** Bán 1000 vé concert, 50,000 users truy cập đồng thời trong 10 giây đầu.

**Khuyến nghị: Queue-based (F)**

**Lý do:**
1. **Zero contention** — serialize writes hoàn toàn, không retry/deadlock
2. **Back-pressure** — queue full → reject, protect DB khỏi overload
3. **Predictable** — latency = queue_depth × processing_time, dễ capacity plan
4. **Fairness** — FIFO queue = first-come-first-served, không starvation
5. **Proven** — Ticketmaster, 12306 dùng pattern này cho billions of requests

**Trade-off chấp nhận:**
- Latency cao hơn OCC/Pessimistic cho individual requests
- Redis là SPOF — cần Redis Sentinel/Cluster cho HA
- Worker throughput capped — cần horizontal partition (shard by event_id)

**Fallback:** Nếu không muốn Redis dependency → **OCC (C)** với aggressive retry backoff

---

### 6.2 Bối cảnh 2: Multi-instance, UX-oriented (E-commerce Checkout)

> **Scenario:** Booking.com — user xem phòng, chọn phòng, nhập thông tin, thanh toán.
> Contention trung bình nhưng UX quan trọng.

**Khuyến nghị: Reservation + Fencing (E)**

**Lý do:**
1. **UX tốt nhất** — "Phòng đang được giữ cho bạn trong 10 phút" = giảm anxiety
2. **Tách contention** — reserve nhanh (ms), payment chậm (phút) — lock không hold suốt
3. **Retry-friendly** — payment fail → reserve vẫn còn, user retry payment
4. **Multi-step flow** — phù hợp với checkout wizard (chọn → thông tin → thanh toán → xác nhận)
5. **Scalable** — reserve là lightweight lock, confirm là idempotent write

**Trade-off chấp nhận:**
- Complexity cao — 2 endpoints, background cleanup, fencing validation
- Seat "waste" khi user bỏ giữa chừng — TTL phải tuning phù hợp
- Cần monitoring: orphan reservation count, TTL distribution

**Fallback:** Nếu flow đơn giản (1-click buy) → **OCC (C)** đủ tốt

---

## 7. Lessons Learned

### 7.1 Engineering Principles

1. **Correctness trước Performance** — Naive có throughput cao nhất nhưng broken.
   Luôn đảm bảo invariants trước khi optimize.

2. **Understand the layer** — Pessimistic ở DB level (lock manager),
   OCC ở application level (version check), Queue ở infrastructure level (Redis).
   Bug fix phải đúng layer.

3. **Trade-offs are explicit** — Không có "best" strategy. Chỉ có "best for context X".
   Document trade-offs rõ ràng để team ra quyết định informed.

4. **Defense in depth** — Idempotency: Redis (fast) + DB (durable).
   Observability: Micrometer (real-time) + /stats (aggregated).
   Không tin tưởng 1 layer duy nhất.

5. **Benchmark, don't guess** — "OCC nhanh hơn Pessimistic" là assumption.
   Benchmark B1/B2/B3 cho số liệu cụ thể. Số liệu > opinion.

### 7.2 PostgreSQL Insights

- **MVCC** — snapshot isolation mặc định → concurrent reads thấy stale data → root cause của Naive bug
- **Lock manager** — FIFO queue cho row locks → fair nhưng serial
- **SSI** — predicate locks + dependency tracking → stronger nhưng đắt hơn
- **CHECK constraint** — `available_seats >= 0` là safety net cuối cùng — DB reject thay vì oversell
- **UNIQUE constraint** — `(event_id, seat_id)` on tickets — ngăn double-book ở DB level

### 7.3 Redis Insights

- **SET NX** — atomic set-if-not-exists → perfect cho idempotency claim
- **LPUSH/BRPOP** — reliable queue pattern → per-event serialization
- **TTL** — auto-cleanup cho idempotency keys và processing markers
- **Single-threaded** — Redis operations atomic → không cần distributed lock

---

## 8. Cấu trúc dự án

```
high-contention/
├── docs/
│   ├── plan.md                    ← Toàn bộ implementation plan
│   ├── 1.1-docker-setup.md       ← Foundation docs
│   ├── 1.2-schema-migration.md
│   ├── 1.3-seed-data.md
│   ├── 1.4-shared-utilities.md
│   ├── 3.1-idempotency.md        ← Cross-cutting docs
│   ├── 3.2-observability.md
│   ├── bench-B1-stock1.md        ← Benchmark docs
│   ├── bench-final.md            ← Comparison table (fill after benchmarks)
│   ├── REPORT.md                 ← This file
│   └── tutorial.md               ← Step-by-step guide
├── load-test/
│   ├── stock1.js                  ← B1: extreme contention
│   ├── hot-seat.js                ← B2: realistic contention
│   └── burst.js                   ← B3: flash sale
├── db/
│   ├── migrations/V1__init_schema.sql
│   └── seeds/seed_events.sql
├── source/ticketing-service/      ← Spring Boot application
├── docker-compose.yml
├── Makefile
└── AGENTS.md                      ← Agent system documentation
```

**Branch per strategy:**
```
main                              ← Foundation + cross-cutting
├── impl/naive                    ← A: broken baseline
├── impl/pessimistic-locking      ← B: SELECT FOR UPDATE
├── impl/occ                      ← C: version-based retry
├── impl/serializable             ← D: SSI isolation
├── impl/reservation-fencing      ← E: 2-phase + TTL
├── impl/queue-based              ← F: Redis queue + worker
└── benchmark/results             ← Benchmark outputs
```

---

## 9. Next Steps

1. **Chạy benchmark** trên từng branch → điền `docs/bench-final.md`
2. **So sánh số liệu** → validate hoặc invalidate các assumptions trong report này
3. **REPORT update** — thêm số liệu thực tế vào Section 4 (Trade-off Matrix)
4. **Production readiness** — thêm Grafana dashboard, alerting rules, runbook
5. **Horizontal scaling** — shard by event_id, Redis Cluster, read replicas

---

> *"The best strategy is the one your team understands, can debug at 3am,
> and whose trade-offs you've explicitly accepted."*
