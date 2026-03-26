package com.example.ticketing.reservation;

import java.time.Instant;
import java.util.UUID;

/**
 * ReservationResult — immutable result object for reservation lifecycle operations.
 *
 * <p><b>Problem:</b> Reservation operations (reserve/confirm/cancel) have multiple
 * outcome types. Using exceptions for flow control is anti-pattern. A result object
 * encapsulates success/failure with typed data.
 *
 * <p><b>Mechanism:</b> Java 17 record with static factory methods for each outcome.
 * Status enum drives HTTP response mapping in the controller.
 *
 * <p><b>Design pattern:</b> Result Object Pattern — similar to TicketResult but
 * with reservation-specific fields (fencing token, expiry time).
 *
 * <p><b>Scalability:</b> Immutable record — safe for concurrent access, zero overhead.
 */
public record ReservationResult(
        Status status,
        Long reservationId,
        Long ticketId,
        UUID fencingToken,
        Instant expiresAt,
        Long eventId,
        String seatLabel,
        String message
) {

    public enum Status {
        RESERVED,
        CONFIRMED,
        CANCELLED,
        EXPIRED,
        SEAT_NOT_AVAILABLE,
        EVENT_NOT_FOUND,
        CONFIRM_FAILED,
        CANCEL_FAILED,
        CONFLICT,
        ERROR
    }

    // --- Factory methods ---

    public static ReservationResult reserved(Long reservationId, UUID fencingToken, Instant expiresAt) {
        return new ReservationResult(Status.RESERVED, reservationId, null, fencingToken,
                expiresAt, null, null, "Seat reserved — confirm within TTL");
    }

    public static ReservationResult confirmed(Long reservationId, Long ticketId) {
        return new ReservationResult(Status.CONFIRMED, reservationId, ticketId, null,
                null, null, null, "Reservation confirmed — ticket created");
    }

    public static ReservationResult cancelled(Long reservationId) {
        return new ReservationResult(Status.CANCELLED, reservationId, null, null,
                null, null, null, "Reservation cancelled — seat released");
    }

    public static ReservationResult seatNotAvailable(Long eventId, String seatLabel) {
        return new ReservationResult(Status.SEAT_NOT_AVAILABLE, null, null, null,
                null, eventId, seatLabel, "Seat not available");
    }

    public static ReservationResult eventNotFound(Long eventId) {
        return new ReservationResult(Status.EVENT_NOT_FOUND, null, null, null,
                null, eventId, null, "Event not found");
    }

    public static ReservationResult confirmFailed(Long reservationId, String reason) {
        return new ReservationResult(Status.CONFIRM_FAILED, reservationId, null, null,
                null, null, null, reason);
    }

    public static ReservationResult cancelFailed(Long reservationId, String reason) {
        return new ReservationResult(Status.CANCEL_FAILED, reservationId, null, null,
                null, null, null, reason);
    }

    public static ReservationResult conflict(Long eventId, String seatLabel, String reason) {
        return new ReservationResult(Status.CONFLICT, null, null, null,
                null, eventId, seatLabel, reason);
    }

    public static ReservationResult error(String message) {
        return new ReservationResult(Status.ERROR, null, null, null,
                null, null, null, message);
    }
}
