# Benchmark Final — So sánh 6 Strategy (A-F)

> Template — điền số liệu sau khi chạy benchmark trên từng branch.

---

## Môi trường benchmark

| Parameter | Value |
|-----------|-------|
| Machine | _TODO: CPU, RAM, OS_ |
| PostgreSQL | 16 (Docker) |
| Redis | 7-alpine (Docker) |
| HikariCP pool | 50 connections |
| JVM | Java 17, default heap |
| k6 | _TODO: version_ |

---

## B1 — Stock=1 (Extreme Contention)

> 200 VUs, 1 seat, mỗi VU gửi 1 request

| Strategy | Successes | Conflicts | Errors | p50 (ms) | p95 (ms) | p99 (ms) | Oversell? | Deadlocks | Retries |
|----------|-----------|-----------|--------|----------|----------|----------|-----------|-----------|---------|
| A: Naive | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | 0 | 0 |
| B: Pessimistic | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | _TODO_ | 0 |
| C: OCC | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | 0 | _TODO_ |
| D: SERIALIZABLE | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | 0 | _TODO_ |
| E: Reservation | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | 0 | 0 |
| F: Queue | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | 0 | 0 |

### Phân tích B1

**Kỳ vọng:**
- **Naive (A)**: oversell — nhiều hơn 1 success → chứng minh race condition
- **Pessimistic (B)**: p95 cao nhất — blocking, sequential lock acquisition
- **OCC (C)**: retry storm — nhiều retries nhưng fast fail → p95 trung bình
- **SERIALIZABLE (D)**: tương tự OCC — PostgreSQL retry serialization failures
- **Reservation (E)**: 2-step process → latency cao hơn OCC/SERIALIZABLE
- **Queue (F)**: sequential by design → latency phụ thuộc queue depth

**Câu hỏi cần trả lời:**
1. Naive oversell bao nhiêu ticket? → con số cụ thể chứng minh race condition
2. Strategy nào có p95 thấp nhất? → hiệu quả nhất dưới extreme contention
3. OCC vs SERIALIZABLE: retry count khác nhau bao nhiêu?
4. Deadlock có xảy ra ở Pessimistic không? Nếu có, bao nhiêu?

---

## B2 — Hot-Seat (Realistic Contention)

> 500 VUs, 100 seats, 80% traffic → A1-A10 (hot seats)

| Strategy | Successes | Conflicts | Errors | p50 (ms) | p95 (ms) | p99 (ms) | Oversell? | Deadlocks | Retries |
|----------|-----------|-----------|--------|----------|----------|----------|-----------|-----------|---------|
| A: Naive | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | 0 | 0 |
| B: Pessimistic | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | _TODO_ | 0 |
| C: OCC | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | 0 | _TODO_ |
| D: SERIALIZABLE | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | 0 | _TODO_ |
| E: Reservation | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | 0 | 0 |
| F: Queue | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | 0 | 0 |

### Phân tích B2

**Kỳ vọng:**
- Hot seats (A1-A10) sẽ có contention cao → conflict rate cao hơn cold seats
- OCC/SERIALIZABLE có thể tốt hơn Pessimistic vì cold seats không bị block
- Naive sẽ oversell trên hot seats nhưng cold seats có thể OK

**Câu hỏi cần trả lời:**
1. Throughput (successes/sec) của mỗi strategy?
2. Strategy nào handle mixed contention (hot + cold) tốt nhất?
3. Naive oversell bao nhiêu? Chỉ trên hot seats hay cả cold seats?

---

## B3 — Burst (Flash Sale Simulation)

> Ramp 0 → 1000 VUs trong 5s, sustained 30s, ramp down 5s. 100 seats.

| Strategy | Successes | Conflicts | 503s | Errors | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) | Oversell? | Deadlocks | Timeouts |
|----------|-----------|-----------|------|--------|----------|----------|----------|----------|-----------|-----------|----------|
| A: Naive | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | 0 | 0 |
| B: Pessimistic | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | _TODO_ | _TODO_ |
| C: OCC | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | 0 | _TODO_ |
| D: SERIALIZABLE | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | 0 | _TODO_ |
| E: Reservation | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | 0 | _TODO_ |
| F: Queue | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | _TODO_ | No | 0 | _TODO_ |

### Phân tích B3

**Kỳ vọng:**
- **Pessimistic**: connection pool exhaustion → timeout spike khi VUs > pool size (50)
- **OCC**: retry storm → exponential backoff giúp giảm load nhưng p99 tăng
- **Queue**: back-pressure → 503 khi queue đầy, nhưng DB an toàn
- **Naive**: oversell ngay từ burst đầu tiên

**Câu hỏi cần trả lời:**
1. Strategy nào bị connection pool exhaustion?
2. Queue strategy có bao nhiêu 503 (back-pressure reject)?
3. Max latency của mỗi strategy? → tail latency sensitivity
4. Sau khi tất cả seats bán hết, error rate bao nhiêu?

---

## Tổng hợp — Strategy Selection Guide

### Correctness

| Strategy | Oversell? | Double-book? | Consistent? |
|----------|-----------|-------------|-------------|
| A: Naive | ⚠️ YES | ⚠️ Possible | ❌ |
| B: Pessimistic | ✅ No | ✅ No | ✅ |
| C: OCC | ✅ No | ✅ No | ✅ |
| D: SERIALIZABLE | ✅ No | ✅ No | ✅ |
| E: Reservation | ✅ No | ✅ No | ✅ |
| F: Queue | ✅ No | ✅ No | ✅ |

### Performance Summary (from B3 — Burst)

| Strategy | Throughput | p95 Latency | Failure Mode | Best For |
|----------|-----------|-------------|-------------|----------|
| A: Naive | _TODO_ | _TODO_ | Oversell | ❌ Never use |
| B: Pessimistic | _TODO_ | _TODO_ | Pool exhaustion, deadlock | Low contention, simple audit |
| C: OCC | _TODO_ | _TODO_ | Retry storm | Medium contention, fast reads |
| D: SERIALIZABLE | _TODO_ | _TODO_ | Serialization retry | Strong consistency needs |
| E: Reservation | _TODO_ | _TODO_ | TTL expiry complexity | UX-first: "hold your seat" |
| F: Queue | _TODO_ | _TODO_ | Back-pressure 503 | Flash sale, strict fairness |

### Khuyến nghị

#### Bối cảnh 1: Single DB, High Contention (e.g., flash sale 1000+ concurrent)
- **Khuyến nghị**: _TODO_ (sau khi có số liệu)
- **Lý do**: _TODO_

#### Bối cảnh 2: Multi-instance, UX-oriented (e.g., e-commerce checkout)
- **Khuyến nghị**: _TODO_ (sau khi có số liệu)
- **Lý do**: _TODO_

---

## Cách chạy benchmark đầy đủ

```bash
# Cho mỗi strategy (ví dụ: pessimistic):
# 1. Checkout branch
git checkout impl/pessimistic-locking

# 2. Reset DB
make db-reset
make migrate
make seed

# 3. Start app
make app-run

# 4. Trong terminal khác — chạy benchmark
make bench:pessimistic          # B1: Stock=1
# Reset DB giữa các benchmark run
make db-reset && make migrate && make seed
make hot-seat:pessimistic       # B2: Hot-Seat
make db-reset && make migrate && make seed
make burst:pessimistic          # B3: Burst

# 5. Ghi kết quả vào bảng trên
# 6. Kiểm tra invariants
curl http://localhost:8080/events/1/stats | jq
```

> **Lưu ý:** Phải reset DB giữa mỗi benchmark run để đảm bảo initial state giống nhau.
