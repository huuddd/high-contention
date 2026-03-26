package com.example.ticketing.ticket;

import java.util.UUID;

/**
 * TicketResult — immutable result of a reserve-and-buy operation.
 *
 * <p><b>Problem:</b> Different strategies may succeed, fail due to contention,
 * or fail due to business rules. A uniform result type enables consistent
 * response handling regardless of the active strategy.
 *
 * <p><b>Mechanism:</b> Java 17 record — immutable, compact, with built-in
 * equals/hashCode/toString. Uses sealed-class-like approach with status enum.
 *
 * <p><b>Design pattern:</b> Result Object — encapsulates success/failure
 * without throwing exceptions for expected business outcomes (e.g., seat taken).
 *
 * <p><b>Scalability:</b> Zero overhead — stack-allocated in most JVM scenarios,
 * no synchronization needed due to immutability.
 */
public record TicketResult(
        Status status,
        Long ticketId,
        Long eventId,
        String seatLabel,
        UUID userId,
        String message
) {

    public enum Status {
        SUCCESS,
        SEAT_NOT_AVAILABLE,
        EVENT_NOT_FOUND,
        CONFLICT,
        MAX_RETRIES_EXCEEDED,
        ERROR
    }

    public static TicketResult success(Long ticketId, Long eventId, String seatLabel, UUID userId) {
        return new TicketResult(Status.SUCCESS, ticketId, eventId, seatLabel, userId, "Ticket purchased successfully");
    }

    public static TicketResult seatNotAvailable(Long eventId, String seatLabel) {
        return new TicketResult(Status.SEAT_NOT_AVAILABLE, null, eventId, seatLabel, null, "Seat " + seatLabel + " is not available");
    }

    public static TicketResult eventNotFound(Long eventId) {
        return new TicketResult(Status.EVENT_NOT_FOUND, null, eventId, null, null, "Event " + eventId + " not found");
    }

    public static TicketResult conflict(Long eventId, String seatLabel, String reason) {
        return new TicketResult(Status.CONFLICT, null, eventId, seatLabel, null, reason);
    }

    public static TicketResult maxRetriesExceeded(Long eventId, String seatLabel, int attempts) {
        return new TicketResult(Status.MAX_RETRIES_EXCEEDED, null, eventId, seatLabel, null,
                "Failed after " + attempts + " retry attempts");
    }

    public static TicketResult error(Long eventId, String seatLabel, String errorMessage) {
        return new TicketResult(Status.ERROR, null, eventId, seatLabel, null, errorMessage);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
