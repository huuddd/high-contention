# Benchmark B1 — Stock=1 (Extreme Contention)

## Mục tiêu

Đo lường hành vi của mỗi strategy khi **chỉ có 1 seat duy nhất** — worst-case contention.
200 users đồng thời tranh giành 1 seat → chỉ 1 người thắng, 199 thất bại.

## Kịch bản

```
Event: 1 event, 1 seat available
Users: 200 concurrent VUs (virtual users)
Duration: mỗi VU gửi 1 request duy nhất
Seat label: "A1" (fixed — mọi VU đều nhắm cùng 1 seat)
```

## Metrics thu thập

| Metric | Ý nghĩa |
|--------|---------|
| `http_req_duration` (p50, p95, p99) | Latency phân vị |
| `http_req_failed` | Tỷ lệ HTTP error (non-2xx/4xx) |
| `success_count` | Số request trả 201 Created |
| `conflict_count` | Số request trả 409 Conflict |
| `error_count` | Số request trả 5xx |
| Total time | Wall-clock time từ start đến tất cả VU xong |

## Kỳ vọng theo strategy

| Strategy | Success | Conflict | Latency p95 | Ghi chú |
|----------|---------|----------|-------------|---------|
| A: Naive | >1 (oversell!) | ~0 | Thấp | **Broken** — chứng minh oversell |
| B: Pessimistic | =1 | =199 | Cao | Blocking — sequential execution |
| C: OCC | =1 | =199 | Trung bình | Retry storm nhưng fast fail |
| D: SERIALIZABLE | =1 | =199 | Trung bình | Similar to OCC |
| E: Reservation | =1 | =199 | Cao | 2-step process |
| F: Queue | =1 | =199 | Cao | Sequential by design |

## Cách chạy

```bash
# Trước khi chạy: reset DB + seed
make db-reset
make migrate
make seed

# Chạy benchmark cho strategy X
make bench:naive
make bench:pessimistic
make bench:occ
make bench:serializable
make bench:reservation
make bench:queue
```

## Phân tích kết quả

Sau khi chạy, kiểm tra:
1. `GET /events/1/stats` → soldCount phải = 1 (ngoại trừ Naive)
2. `consistent = true` → available + sold = total
3. Metrics: conflicts, retries, deadlocks → giải thích latency pattern
