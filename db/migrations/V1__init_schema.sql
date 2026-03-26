-- ============================================================================
-- V1__init_schema.sql
-- High Contention Ticketing Service — Foundation schema
-- ============================================================================
-- Tables: events, seats, tickets, reservations
-- Invariants enforced at DB level:
--   1. available_seats >= 0              (CHECK constraint)
--   2. available_seats <= total_seats    (CHECK constraint)
--   3. No double-book: 1 seat per event (UNIQUE constraint on tickets)
--   4. Seat status state machine         (CHECK constraint)
--   5. Reservation status state machine  (CHECK constraint)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. EVENTS — mỗi event có tổng ghế và counter ghế còn trống
-- ---------------------------------------------------------------------------
CREATE TABLE events (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    total_seats     INT             NOT NULL,
    available_seats INT             NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    -- Safety net: không bao giờ bán quá số ghế
    CONSTRAINT chk_events_available_non_negative
        CHECK (available_seats >= 0),

    -- Sanity check: ghế trống không thể nhiều hơn tổng ghế
    CONSTRAINT chk_events_available_lte_total
        CHECK (available_seats <= total_seats),

    -- Tổng ghế phải dương
    CONSTRAINT chk_events_total_positive
        CHECK (total_seats > 0)
);

-- ---------------------------------------------------------------------------
-- 2. SEATS — từng ghế cụ thể trong event
-- ---------------------------------------------------------------------------
-- Status state machine: AVAILABLE → LOCKED → SOLD
--                       AVAILABLE → SOLD (direct buy, strategies A-D)
--                       LOCKED → AVAILABLE (reservation expired/cancelled)
CREATE TABLE seats (
    id              BIGSERIAL       PRIMARY KEY,
    event_id        BIGINT          NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    seat_label      VARCHAR(10)     NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'AVAILABLE',
    locked_by       UUID,
    locked_until    TIMESTAMPTZ,

    CONSTRAINT chk_seats_status
        CHECK (status IN ('AVAILABLE', 'LOCKED', 'SOLD'))
);

-- Tìm ghế trống nhanh cho 1 event
CREATE INDEX idx_seats_event_status ON seats(event_id, status);

-- Mỗi event không có 2 ghế cùng label
CREATE UNIQUE INDEX uq_seats_event_label ON seats(event_id, seat_label);

-- ---------------------------------------------------------------------------
-- 3. TICKETS — vé đã bán, bằng chứng quyền sở hữu
-- ---------------------------------------------------------------------------
CREATE TABLE tickets (
    id              BIGSERIAL       PRIMARY KEY,
    event_id        BIGINT          NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    seat_id         BIGINT          NOT NULL REFERENCES seats(id) ON DELETE CASCADE,
    user_id         UUID            NOT NULL,
    idempotency_key VARCHAR(255),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    -- INVARIANT: 1 seat chỉ bán cho 1 user trong 1 event
    -- Đây là DB-level guard chống double-book — kể cả application bug
    CONSTRAINT uq_tickets_event_seat
        UNIQUE (event_id, seat_id)
);

-- Tra cứu vé theo user
CREATE INDEX idx_tickets_user ON tickets(user_id);

-- Tra cứu vé theo event
CREATE INDEX idx_tickets_event ON tickets(event_id);

-- Tra cứu theo idempotency_key (task 3.1)
CREATE INDEX idx_tickets_idempotency ON tickets(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 4. RESERVATIONS — giữ chỗ tạm thời (strategy 2.E)
-- ---------------------------------------------------------------------------
-- Status state machine: PENDING → CONFIRMED (user thanh toán xong)
--                       PENDING → EXPIRED   (TTL hết hạn, background job)
--                       PENDING → CANCELLED (user hủy)
CREATE TABLE reservations (
    id              BIGSERIAL       PRIMARY KEY,
    event_id        BIGINT          NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    seat_id         BIGINT          NOT NULL REFERENCES seats(id) ON DELETE CASCADE,
    user_id         UUID            NOT NULL,
    fencing_token   UUID            NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    expires_at      TIMESTAMPTZ     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_reservations_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'EXPIRED', 'CANCELLED'))
);

-- Background job cần tìm reservations hết hạn
CREATE INDEX idx_reservations_expires
    ON reservations(expires_at)
    WHERE status = 'PENDING';

-- Tra cứu reservation active cho 1 seat
CREATE INDEX idx_reservations_seat_status
    ON reservations(seat_id, status)
    WHERE status = 'PENDING';

-- Tra cứu theo fencing token (confirm flow)
CREATE INDEX idx_reservations_fencing_token
    ON reservations(fencing_token)
    WHERE status = 'PENDING';
