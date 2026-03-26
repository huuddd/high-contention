package com.example.ticketing.common;

/**
 * TicketingConstants — centralized constants to eliminate magic numbers.
 *
 * <p><b>Problem:</b> Magic numbers scattered across strategy implementations
 * make code harder to understand, maintain, and benchmark consistently.
 *
 * <p><b>Mechanism:</b> Static final constants — zero runtime overhead,
 * inlined by JIT compiler. Configurable values should go to application.yml;
 * these are truly constant values.
 *
 * <p><b>Design pattern:</b> Constants Class — single source of truth for
 * shared literal values across all strategies.
 *
 * <p><b>Scalability:</b> No runtime impact — compile-time constants.
 */
public final class TicketingConstants {

    private TicketingConstants() {
        // Không cho phép instantiate
    }

    // --- Strategy names (dùng cho metrics tagging) ---
    public static final String STRATEGY_NAIVE = "naive";
    public static final String STRATEGY_PESSIMISTIC = "pessimistic";
    public static final String STRATEGY_OCC = "occ";
    public static final String STRATEGY_SERIALIZABLE = "serializable";
    public static final String STRATEGY_RESERVATION = "reservation";
    public static final String STRATEGY_QUEUE = "queue";

    // --- Seat status ---
    public static final String SEAT_AVAILABLE = "AVAILABLE";
    public static final String SEAT_LOCKED = "LOCKED";
    public static final String SEAT_SOLD = "SOLD";

    // --- Reservation status ---
    public static final String RESERVATION_PENDING = "PENDING";
    public static final String RESERVATION_CONFIRMED = "CONFIRMED";
    public static final String RESERVATION_EXPIRED = "EXPIRED";
    public static final String RESERVATION_CANCELLED = "CANCELLED";

    // --- PostgreSQL error codes ---
    /** Serialization failure — triggers retry in OCC and SERIALIZABLE strategies. */
    public static final String PG_SERIALIZATION_FAILURE = "40001";
    /** Deadlock detected — triggers retry or abort in pessimistic strategy. */
    public static final String PG_DEADLOCK_DETECTED = "40P01";
    /** Unique violation — double-book attempt caught by UNIQUE constraint. */
    public static final String PG_UNIQUE_VIOLATION = "23505";
    /** Check constraint violation — available_seats < 0 caught by CHECK. */
    public static final String PG_CHECK_VIOLATION = "23514";

    // --- HTTP headers ---
    public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String HEADER_STRATEGY = "X-Strategy";
}
