package com.example.ticketing.ticket.strategy;

import com.example.ticketing.common.RetryWithBackoff;
import com.example.ticketing.common.SerializationConflictException;
import com.example.ticketing.common.TicketingConstants;
import com.example.ticketing.observability.ConflictMetrics;
import com.example.ticketing.ticket.TicketResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

/**
 * SerializableTicketingStrategy — leverages PostgreSQL SERIALIZABLE isolation (SSI)
 * to detect and abort conflicting concurrent transactions automatically.
 *
 * <p><b>Problem:</b> Manual OCC (2.C) only checks a single version column on the event row.
 * Complex invariants spanning multiple tables (e.g., tickets count + reservations count
 * &le; total_seats) require manual version checks on every involved row — error-prone.
 *
 * <p><b>Mechanism:</b> PostgreSQL SSI (Serializable Snapshot Isolation) tracks
 * read-write dependencies across ALL rows touched by a transaction. If it detects a
 * dependency cycle ("dangerous structure"), it aborts one transaction with SQLState 40001.
 * The application simply retries. No version columns, no FOR UPDATE — PostgreSQL handles it.
 *
 * <p><b>Design pattern:</b> Implements {@link TicketingStrategy}. Code is nearly identical
 * to Naive (2.A) — the ONLY difference is {@code @Transactional(isolation = SERIALIZABLE)}.
 * Delegates retry to {@link RetryWithBackoff} on serialization failures.
 *
 * <p><b>Scalability:</b> SSI has non-blocking reads (unlike 2PL). However, serialization
 * failure rate increases with contention. SSI also has ~5-15% false positive rate
 * (aborts transactions that wouldn't actually cause anomalies). For simple single-row
 * contention, OCC (2.C) has fewer retries. SERIALIZABLE shines for complex multi-table invariants.
 */
public class SerializableTicketingStrategy implements TicketingStrategy {

    private static final Logger log = LoggerFactory.getLogger(SerializableTicketingStrategy.class);

    private final ConflictMetrics metrics;
    private final RetryWithBackoff retryWithBackoff;
    private final SerializableAttemptService attemptService;

    public SerializableTicketingStrategy(ConflictMetrics metrics,
                                          RetryWithBackoff retryWithBackoff,
                                          SerializableAttemptService attemptService) {
        this.metrics = metrics;
        this.retryWithBackoff = retryWithBackoff;
        this.attemptService = attemptService;
    }

    /**
     * Outer method — retry loop, WITHOUT @Transactional.
     * Each doAttempt runs in its own SERIALIZABLE transaction.
     */
    @Override
    public TicketResult reserveAndBuy(Long eventId, UUID userId, String seatLabel, String idempotencyKey) {
        try {
            return retryWithBackoff.execute(
                    () -> attemptService.doAttempt(eventId, userId, seatLabel, idempotencyKey),
                    SerializationConflictException.class,
                    strategyName()
            );
        } catch (SerializationConflictException e) {
            // Max retries exceeded
            metrics.recordFailure(strategyName());
            return TicketResult.maxRetriesExceeded(eventId, seatLabel, 5);
        } catch (DataIntegrityViolationException e) {
            log.warn("Serializable: DataIntegrityViolation for event={}, seat={}: {}",
                    eventId, seatLabel, e.getMostSpecificCause().getMessage());
            metrics.recordConflict(strategyName());
            metrics.recordFailure(strategyName());
            return TicketResult.conflict(eventId, seatLabel,
                    "DB constraint violation: " + e.getMostSpecificCause().getMessage());
        } catch (Exception e) {
            log.error("Serializable: unexpected error for event={}, seat={}: {}",
                    eventId, seatLabel, e.getMessage(), e);
            metrics.recordFailure(strategyName());
            return TicketResult.error(eventId, seatLabel, e.getMessage());
        }
    }


    @Override
    public String strategyName() {
        return TicketingConstants.STRATEGY_SERIALIZABLE;
    }

}
