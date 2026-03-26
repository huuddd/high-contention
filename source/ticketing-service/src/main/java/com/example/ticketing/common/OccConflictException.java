package com.example.ticketing.common;

/**
 * OccConflictException — thrown when an OCC version mismatch is detected.
 *
 * <p><b>Problem:</b> OCC detects conflicts at UPDATE time (0 rows affected).
 * This exception signals RetryWithBackoff to retry the entire operation
 * with a fresh read + new version number.
 *
 * <p><b>Mechanism:</b> Unchecked exception — propagates through @Transactional
 * boundary, causing Spring to rollback the current transaction. RetryWithBackoff
 * catches it, waits with exponential backoff + jitter, then starts a new transaction.
 *
 * <p><b>Design pattern:</b> Exception as control flow signal — separates
 * conflict detection (in strategy) from retry logic (in RetryWithBackoff).
 *
 * <p><b>Scalability:</b> Creating exceptions is cheap. The main cost is the
 * wasted work of the failed transaction, not the exception itself.
 */
public class OccConflictException extends RuntimeException {

    private final Long eventId;
    private final String seatLabel;
    private final Long expectedVersion;

    public OccConflictException(Long eventId, String seatLabel, Long expectedVersion) {
        super(String.format("OCC conflict: event=%d, seat=%s, expected version=%d",
                eventId, seatLabel, expectedVersion));
        this.eventId = eventId;
        this.seatLabel = seatLabel;
        this.expectedVersion = expectedVersion;
    }

    public Long getEventId() { return eventId; }
    public String getSeatLabel() { return seatLabel; }
    public Long getExpectedVersion() { return expectedVersion; }
}
