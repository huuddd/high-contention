package com.example.ticketing.common;

/**
 * SerializationConflictException — thrown when PostgreSQL SERIALIZABLE isolation
 * detects a serialization anomaly (SQLState 40001).
 *
 * <p><b>Problem:</b> Under SERIALIZABLE isolation, PostgreSQL's SSI engine aborts
 * transactions that would produce non-serializable results. The aborted transaction
 * must be retried from scratch with a fresh snapshot.
 *
 * <p><b>Mechanism:</b> Wraps the original database exception to provide a clean
 * signal to RetryWithBackoff. Spring maps PostgreSQL 40001 to
 * CannotSerializeTransactionException, but we wrap it for consistent retry handling.
 *
 * <p><b>Design pattern:</b> Exception as control flow signal — same pattern as
 * OccConflictException (2.C), but triggered by database-level detection rather
 * than application-level version check.
 *
 * <p><b>Scalability:</b> SSI has a higher false-positive rate (~5-15%) than manual OCC,
 * so this exception may fire more frequently than OccConflictException.
 */
public class SerializationConflictException extends RuntimeException {

    public SerializationConflictException(String message, Throwable cause) {
        super(message, cause);
    }

    public SerializationConflictException(String message) {
        super(message);
    }
}
