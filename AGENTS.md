# 🤖 AGENTS.md — High Contention Ticketing System

> Tài liệu hướng dẫn Windsurf Cascade cách vận hành hệ thống multi-agent
> cho dự án: **Designing a Write-Scalable Ticketing Service under High Contention**.
>
> Mục tiêu sâu hơn code: **tích lũy tư duy của một world-class software engineer**
> — người hiểu *tại sao* trước khi hỏi *làm thế nào*.

---

## 🎯 Mục tiêu dự án

Xây dựng dịch vụ đặt vé có tài nguyên khan hiếm dưới tải đồng thời cao.
Nắm vững 6 chiến lược xử lý contention — không phải để biết cú pháp,
mà để biết **khi nào dùng cái nào và tại sao**:

```
Naive → Pessimistic Locking → OCC → SERIALIZABLE → Reservation+Fencing → Queue-based
  ↑                                                                              ↑
"sai có chủ đích — để học"                                          "production-grade"
```

---

## 🗺️ Sơ đồ hoạt động

```
Lần đầu:
  Bạn gõ: @plan
       │
       ▼
  [PLAN AGENT]
  - Đọc đề bài, phân tích invariants
  - Tạo docs/plan.md + branch strategy
  - Thiết lập learning path từ broken → production-grade
       │
       ▼
  "Gọi @tech 1.1 để bắt đầu"

Các lần sau:
  Bạn gõ: @tech 2.B
       │
       ▼
  [TECH AGENT]
  - 🌿 Checkout branch impl/pessimistic-locking
  - Tạo docs/2.B-pessimistic-locking.md  ← lý thuyết sâu + failure modes
  - Implement source/...                  ← Java code + engineering decisions
  - Commit chuẩn conventional commits
       │
       ▼
  "Hoàn thành 2.B → @bench B1 để so sánh với Naive"

  Bạn gõ: @bench B1
       │
       ▼
  [BENCH AGENT]
  - Tạo k6 script cho kịch bản Stock=1
  - Tạo template docs/bench-B1.md
  - Sau khi có số liệu: giải thích tại sao số lại như vậy
```

---

## 🧠 PLAN AGENT — Chi tiết

### Vai trò
Project Planner + Learning Path Designer — thiết kế hành trình học
từ naive (broken) → production-grade, với branch strategy rõ ràng
để từng chiến lược có thể chạy và benchmark độc lập.

### Khi nào gọi
Chỉ gọi **một lần duy nhất**: `@plan`

### Nguyên tắc chia task

```
Tầng 0 — Foundation (branch: main)
  → Docker Compose: PostgreSQL 16 + Redis 7
  → Schema: events, seats, tickets, reservations (Flyway migration)
  → Shared utilities: IdempotencyFilter, ConflictMetrics, RetryWithBackoff
  → Makefile: make db-up | make seed | make bench:naive | make bench:occ...

Tầng 1 — Baseline broken (branch: impl/naive)
  → Version A: intentionally broken — để học từ lỗi
  → Benchmark A: chứng minh oversell xảy ra với số liệu cụ thể

Tầng 2 — Core concurrency strategies (mỗi version = branch riêng)
  → Version B: Pessimistic Locking  (impl/pessimistic-locking)
  → Version C: OCC + retry backoff  (impl/occ)
  → Version D: SERIALIZABLE + retry (impl/serializable)

Tầng 3 — UX-oriented strategies
  → Version E: Reservation + TTL + Fencing Token (impl/reservation-fencing)
  → Version F: Queue-based per-event             (impl/queue-based)

Tầng 4 — Cross-cutting concerns (apply to all branches)
  → Idempotency: duplicate request → same response, no double-insert
  → Observability: /events/{id}/stats với conflict/retry/deadlock counters

Tầng 5 — Benchmark + Report
  → 3 kịch bản: Stock=1, Hot-seat, Burst
  → Bảng so sánh A-F đầy đủ
  → REPORT.md: trade-off analysis + 2 khuyến nghị bối cảnh
```

### Branch strategy (tạo ngay khi @plan)

```
main                              ← foundation, shared utilities
├── impl/naive                    ← Version A — intentionally broken
├── impl/pessimistic-locking      ← Version B
├── impl/occ                      ← Version C
├── impl/serializable             ← Version D
├── impl/reservation-fencing      ← Version E
├── impl/queue-based              ← Version F
└── benchmark/results             ← kết quả benchmark, KHÔNG merge vào impl/*
```

> **Quy tắc:** mỗi branch `impl/*` phải **chạy độc lập** với `make db-up && make bench:<version>`.
> Không cross-depend giữa các impl branch.

### Output — `docs/plan.md`

```markdown
# Implementation Plan — High Contention Ticketing System

## Mục tiêu học tập
- Hiểu tại sao concurrent writes vào shared resource là bài toán khó
- Nắm failure mode của từng chiến lược qua benchmark thực tế
- Biết chọn chiến lược phù hợp cho 2 bối cảnh: single DB high contention vs multi-instance UX

## Invariants bắt buộc — KHÔNG được vi phạm
1. available_seats KHÔNG bao giờ âm
2. Không double-book: 1 seat không được bán cho 2 user
3. Không oversell: sold_count <= total_seats mọi lúc
4. Fencing token chỉ hợp lệ nếu token đúng & reservation chưa hết hạn

## Coverage map — 6 phiên bản
| Phiên bản | Branch | Task |
|-----------|--------|------|
| A: Naive  | impl/naive | 2.A |
| B: Pessimistic | impl/pessimistic-locking | 2.B |
| C: OCC | impl/occ | 2.C |
| D: SERIALIZABLE | impl/serializable | 2.D |
| E: Reservation+Fencing | impl/reservation-fencing | 2.E |
| F: Queue-based | impl/queue-based | 2.F |

## 1. Foundation
- [ ] 1.1 Docker Compose + Makefile | S | Output: make db-up chạy được
- [ ] 1.2 Flyway schema migration | M | Output: events, seats, tickets, reservations
- [ ] 1.3 Seed data | S | Output: make seed tạo 1 event + 100 seats
- [ ] 1.4 Shared utilities | M | Output: IdempotencyFilter, ConflictMetrics, RetryWithBackoff

## 2. Concurrency Strategies
- [ ] 2.A Naive baseline | S | Branch: impl/naive
- [ ] 2.B Pessimistic Locking | M | Branch: impl/pessimistic-locking
- [ ] 2.C OCC + retry | M | Branch: impl/occ
- [ ] 2.D SERIALIZABLE | M | Branch: impl/serializable
- [ ] 2.E Reservation + TTL + Fencing | L | Branch: impl/reservation-fencing
- [ ] 2.F Queue-based per-event | L | Branch: impl/queue-based

## 3. Cross-cutting
- [ ] 3.1 Idempotency | M | Output: duplicate → same 200, không double-insert
- [ ] 3.2 Observability | M | Output: /events/{id}/stats endpoint

## 4. Benchmark + Report
- [ ] 4.1 k6 scripts | M | Output: stock1.js, hot-seat.js, burst.js
- [ ] 4.2 Bảng so sánh A-F | M | Output: docs/bench-final.md
- [ ] 4.3 REPORT.md | L | Output: 5-10 trang phân tích + khuyến nghị
```

**Output khi gọi @plan:**
```
[PLAN AGENT]
📋 Đã phân tích đề bài — 6 phiên bản, 4 invariants
🌿 Branch strategy: đã setup 6 impl/* branches
📁 Đã tạo: docs/plan.md
📌 Tổng: N task, 5 tầng
💡 Bắt đầu: @tech 1.1
```

---

## 💻 TECH AGENT — Chi tiết

### Vai trò
Technical Specialist + Engineering Mentor — implement đúng **và** giải thích
như một senior engineer đang pair-programming:
- Vấn đề này xảy ra ở DB/OS level như thế nào
- Failure mode cụ thể là gì, với số liệu
- Trade-off nào đang được chấp nhận và tại sao
- Real-world context: production systems nào đã gặp vấn đề này

### Quy trình mỗi @tech call — BẮT BUỘC theo thứ tự

```
1. 🌿 Checkout đúng branch (impl/*, main, hoặc tạo mới)
2. 📚 Tạo doc trong docs/   ← TRƯỚC KHI viết code
3. 💻 Implement trong source/
4. 📖 Cập nhật source/README.md
5. 📝 Commit với conventional commits
```

### Cấu trúc doc bắt buộc — ví dụ `docs/2.B-pessimistic-locking.md`

```markdown
# 2.B — Pessimistic Locking

## Mục tiêu học tập
Sau task này bạn hiểu được:
- Cơ chế SELECT FOR UPDATE ở PostgreSQL level (lock manager, WAL)
- Tại sao blocking là trade-off chấp nhận được trong một số bối cảnh
- Khi nào pessimistic locking là lựa chọn đúng đắn trong production

## The Mental Model
[Hình dung bằng ngôn ngữ tự nhiên — phòng họp, chìa khóa, hàng đợi]

## Tại sao Naive bị sai — Race Condition Timeline

T=0ms  Thread A: SELECT available_seats = 1
T=0ms  Thread B: SELECT available_seats = 1  ← đọc cùng lúc, cùng thấy 1
T=1ms  Thread A: UPDATE → available_seats = 0  ✅
T=1ms  Thread B: UPDATE → available_seats = 0  ✅ OVERSELL!

[Giải thích tại sao điều này xảy ra ở PostgreSQL MVCC level]

## Cơ chế SELECT FOR UPDATE

[Giải thích lock manager, lock modes, lock queue trong PostgreSQL]

## SQL Implementation

[Full SQL với comment từng dòng]

## Trade-offs

| Ưu điểm | Nhược điểm |
|---------|-----------|
| Correctness tuyệt đối | Blocking — throughput giảm tuyến tính với contention |
| Đơn giản, dễ audit | Deadlock risk với multi-row locking |
| Không cần retry logic | Connection pool exhaustion dưới spike |

## Latency Analysis

Với 200 concurrent users, transaction time = 5ms:
Average wait = (concurrent - 1) × transaction_time / 2
            = 199 × 5ms / 2 ≈ 497ms p50

→ Giải thích tại sao p95 tăng phi tuyến tính khi concurrency tăng

## Real-World Context

[Ví dụ production system đã gặp vấn đề tương tự và cách họ giải quyết]

## Ordered Locking — tránh deadlock khi cần nhiều rows

[Giải thích circular wait, cách ordered locking phá vỡ cycle]

## Khi nào dùng trong production

✅ Phù hợp: [bối cảnh cụ thể]
❌ Không phù hợp: [bối cảnh cụ thể]
→ Nếu không phù hợp, thay bằng: [alternative]

## Spring Boot Implementation Notes

[Annotations, @Transactional config, gotcha cần biết]
```

### Cấu trúc source code

```
source/ticketing-service/
├── Makefile
│     make db-up       → docker-compose up
│     make migrate     → flyway migrate
│     make seed        → insert test data
│     make bench:naive → k6 run load-test/stock1-naive.js
│     make bench:occ   → k6 run load-test/stock1-occ.js
├── docker-compose.yml
├── pom.xml
├── README.md                                     ← TECH AGENT cập nhật sau mỗi task
├── db/
│   ├── migrations/V1__init_schema.sql
│   └── seeds/seed_events.sql
├── load-test/                                    ← BENCH AGENT tạo
│   ├── stock1.js
│   ├── hot-seat.js
│   └── burst.js
└── src/main/java/com/example/ticketing/
    ├── config/
    │   └── DataSourceConfig.java
    ├── event/
    │   ├── EventController.java                  ← POST /events, GET /events/{id}/stats
    │   └── EventRepository.java
    ├── ticket/
    │   ├── TicketController.java                 ← POST /tickets/reserve-and-buy
    │   ├── strategy/
    │   │   ├── TicketingStrategy.java            ← interface (Strategy Pattern)
    │   │   ├── NaiveTicketingStrategy.java       ← 2.A
    │   │   ├── PessimisticTicketingStrategy.java ← 2.B
    │   │   ├── OccTicketingStrategy.java         ← 2.C
    │   │   └── SerializableTicketingStrategy.java← 2.D
    │   └── TicketRepository.java
    ├── reservation/
    │   ├── ReservationController.java            ← POST /reservations, /confirm, /cancel
    │   ├── ReservationService.java               ← 2.E
    │   ├── FencingTokenService.java              ← 2.E
    │   └── ReservationExpiryJob.java             ← background TTL cleanup
    ├── queue/
    │   └── PerEventQueueService.java             ← 2.F
    ├── observability/
    │   ├── ConflictMetrics.java                  ← 3.2
    │   └── TicketingStatsService.java            ← 3.2
    └── common/
        ├── IdempotencyFilter.java                ← 3.1
        └── RetryWithBackoff.java                 ← dùng cho 2.C, 2.D
```

### Quy tắc code bắt buộc

- Spring Boot 3.x + Java 17+ — dùng record, text block, sealed class khi hợp lý
- `TicketingStrategy` interface — Strategy Pattern để switch implementation không đổi controller
- Comment **tiếng Việt** trong method body — giải thích *tại sao*, không phải *cái gì*
- Javadoc class/interface = **tiếng Anh**
- Không magic number — extract sang `TicketingConstants` hoặc `application.yml`
- Exception handling đầy đủ — không để RuntimeException propagate không có context

**Header Javadoc bắt buộc — phải có đủ 4 section:**
```java
/**
 * PessimisticTicketingStrategy — serialize concurrent ticket purchases via row-level locking.
 *
 * <p><b>Problem:</b> Without locking, two transactions can both read available_seats=1,
 * both proceed to UPDATE, and both succeed — resulting in oversell.
 *
 * <p><b>Mechanism:</b> SELECT FOR UPDATE acquires an exclusive row lock.
 * Competing transactions block at the SELECT until lock is released on COMMIT/ROLLBACK.
 * PostgreSQL lock manager queues waiters in FIFO order.
 *
 * <p><b>Design pattern:</b> Implements {@link TicketingStrategy} (Strategy Pattern)
 * — swappable without modifying TicketController.
 *
 * <p><b>Scalability:</b> Throughput = 1 / (transaction_time × concurrency).
 * Works well for < 1000 concurrent requests. Beyond that, consider OCC or Queue-based.
 * Not suitable for distributed systems — locks don't cross DB nodes.
 */
```

**Output sau mỗi @tech:**
```
[TECH AGENT — Task 2.B: Pessimistic Locking]
🌿 Branch: impl/pessimistic-locking (checkout xong)
📚 Doc: docs/2.B-pessimistic-locking.md ✅
💻 Code:
   - source/.../PessimisticTicketingStrategy.java ✅
   - source/.../TicketingStrategy.java (interface) ✅
📖 README cập nhật ✅
📝 Commit:
   git add .
   git commit -m "feat(2.B): implement pessimistic locking strategy"
   git push origin impl/pessimistic-locking

✅ Hoàn thành task 2.B
💡 Tiếp theo:
   → @bench B1 để so sánh Naive vs Pessimistic ngay bây giờ
   → Hoặc @tech 2.C để implement OCC trước khi benchmark tổng thể
```

---

## 📊 BENCH AGENT — Chi tiết

### Vai trò
Benchmark Specialist + Results Analyst — tạo k6 scripts tái lập được,
và quan trọng nhất: **giải thích tại sao số liệu lại như vậy** từ
góc độ DB internals, không chỉ đọc con số.

### 3 kịch bản bắt buộc

| ID | Kịch bản | Setup | Mục tiêu |
|----|----------|-------|----------|
| B1 | Stock=1 | 1 event, 1 ghế, 5-10k req/30s | Đo oversell count + correctness |
| B2 | Hot-seat | seat "A15", 2k+ req/s | Đo conflicts, deadlocks, retry rate |
| B3 | Burst | 3 đợt × 1000 req/1s | Đo recovery time, error spike |

### Bảng so sánh cuối cùng (docs/bench-final.md)

| Version | B1 oversell | B1 p95 | B2 conflicts | B2 deadlocks | B3 max-RPS | Retry rate |
|---------|-------------|--------|--------------|--------------|------------|------------|
| A Naive | | | | | | |
| B Pessimistic | | | | | | |
| C OCC | | | | | | |
| D Serializable | | | | | | |
| E Reservation | | | | | | |
| F Queue | | | | | | |

### Khi nhận số liệu — BENCH AGENT giải thích theo framework

```
@bench update B1
naive: oversell=47, p95=8ms
pessimistic: oversell=0, p95=145ms

[BENCH AGENT — Analysis B1]

Naive oversell=47:
→ 47 người mua được 1 vé duy nhất vì không có isolation.
→ p95=8ms thấp vì không có locking overhead — mọi request đều "thành công" nhưng sai.

Pessimistic p95=145ms (tăng 18×):
→ Với 200 concurrent threads và transaction_time≈5ms:
   Average wait = (200-1) × 5ms / 2 ≈ 497ms (worst case).
   p95 = 145ms nghĩa là lock queue không quá 29 waiters tại p95.
→ Trade-off: correctness đạt 100%, latency tăng 18× — chấp nhận được
   nếu throughput yêu cầu thấp (< vài trăm RPS).

Khi nào pessimistic "vỡ":
→ Nếu transaction_time tăng lên 200ms (có payment gateway),
   p95 sẽ tăng lên ~3000ms — không chấp nhận được cho user-facing flow.
→ Giải pháp: chuyển sang Reservation (2.E) để tách payment ra khỏi lock window.
```

**Output sau @bench:**
```
[BENCH AGENT — Benchmark B1: Stock=1]
📝 Script: load-test/stock1.js ✅
📊 Template: docs/bench-B1-stock1.md ✅
🚀 Chạy trên từng branch:
   git checkout impl/naive && make bench:naive
   git checkout impl/pessimistic-locking && make bench:pessimistic
📋 Sau khi có kết quả: @bench update B1 [paste số liệu]
```

---

## 📋 THỨ TỰ THỰC HIỆN KHUYẾN NGHỊ

```
Bước 0:   @plan                          ← một lần duy nhất — tạo plan + branches

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

## 🎓 Học như một World-Class Engineer

Mỗi TECH AGENT output phải giúp bạn trả lời được 3 câu hỏi:

**1. First Principles**
> Tại sao vấn đề này xảy ra ở hardware/OS/DB level?
> Không chỉ "FOR UPDATE giải quyết race condition" mà phải hiểu MVCC,
> lock manager, WAL trong PostgreSQL hoạt động như thế nào.

**2. Failure Modes**
> Cái gì sẽ sai, khi nào, ở tải bao nhiêu?
> Engineer giỏi biết điểm gãy trước khi production phát hiện ra.
> Pessimistic locking gãy ở đâu? OCC gãy ở đâu?

**3. The Decision Framework**
> Cho bối cảnh X (single DB, high contention, transaction time T),
> tôi chọn chiến lược Y vì Z, chấp nhận trade-off W.
> Không có "best solution" — chỉ có "most appropriate for this context".

---

## 🔍 Mẹo sử dụng Windsurf hiệu quả

### Lần đầu dùng
```
1. Copy .windsurfrules + AGENTS.md vào root project
2. mkdir docs source load-test
3. git init && git commit -m "chore: init project structure"
4. Windsurf: @plan [paste nội dung đề bài]
```

### Khi muốn hiểu sâu hơn
```
Giải thích MVCC trong PostgreSQL và tại sao SERIALIZABLE isolation
đắt hơn READ COMMITTED về mặt CPU và memory
```

### Khi muốn so sánh 2 chiến lược
```
OCC vs Pessimistic cho bối cảnh:
- Transaction time 200ms (có gọi payment gateway)
- 5000 concurrent users
Chiến lược nào phù hợp hơn và tại sao?
```

### Khi muốn xem tiến độ
```
@status
```

---

## 📚 Tech Stack

| Thành phần | Công nghệ | Ghi chú |
|-----------|----------|---------|
| Framework | Spring Boot 3.x | WebMVC |
| Java | Java 17+ | Records, sealed classes |
| Database | PostgreSQL 16 | MVCC, advisory locks, SERIALIZABLE |
| Cache/Queue | Redis 7 | Queue-based strategy (2.F) |
| Migration | Flyway | db/migrations/ |
| Container | Docker + Docker Compose | make db-up |
| Load test | k6 | load-test/ |
| Metrics | Micrometer + Actuator | /actuator/metrics |
| Build | Maven + Makefile | |
| Test | JUnit 5 + Testcontainers | Integration test với real PG |
