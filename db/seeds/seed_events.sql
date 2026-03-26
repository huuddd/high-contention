-- ============================================================================
-- seed_events.sql
-- High Contention Ticketing Service — Test seed data
-- ============================================================================
-- Idempotent: TRUNCATE + INSERT — safe to run multiple times
-- Creates: 1 event "Concert Alpha" with 100 seats (A1–A100)
-- Usage: make seed
-- ============================================================================

-- Xóa toàn bộ data cũ, reset auto-increment
-- CASCADE: xóa tickets, reservations dependent trước
TRUNCATE TABLE reservations, tickets, seats, events RESTART IDENTITY CASCADE;

-- ---------------------------------------------------------------------------
-- Event: Concert Alpha — 100 ghế, tất cả available
-- ---------------------------------------------------------------------------
INSERT INTO events (name, total_seats, available_seats, version)
VALUES ('Concert Alpha', 100, 100, 0);

-- ---------------------------------------------------------------------------
-- 100 Seats: A1 → A100, tất cả AVAILABLE
-- generate_series tạo 100 rows trong 1 statement — hiệu quả hơn 100 INSERT
-- ---------------------------------------------------------------------------
INSERT INTO seats (event_id, seat_label, status)
SELECT 1, 'A' || gs, 'AVAILABLE'
FROM generate_series(1, 100) AS gs;

-- ---------------------------------------------------------------------------
-- Verification: đảm bảo data consistent
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_event_available INT;
    v_seat_count      INT;
BEGIN
    SELECT available_seats INTO v_event_available FROM events WHERE id = 1;
    SELECT COUNT(*)        INTO v_seat_count      FROM seats  WHERE event_id = 1 AND status = 'AVAILABLE';

    IF v_event_available != v_seat_count THEN
        RAISE EXCEPTION 'SEED VERIFICATION FAILED: events.available_seats (%) != seats count (%)',
            v_event_available, v_seat_count;
    END IF;

    RAISE NOTICE 'SEED OK: event_id=1, available_seats=%, seat_count=%',
        v_event_available, v_seat_count;
END $$;
