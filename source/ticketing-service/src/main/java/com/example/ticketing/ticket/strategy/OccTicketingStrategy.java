package com.example.ticketing.ticket.strategy;

import com.example.ticketing.common.OccConflictException;
import com.example.ticketing.common.RetryWithBackoff;
import com.example.ticketing.common.TicketingConstants;
import com.example.ticketing.observability.ConflictMetrics;
import com.example.ticketing.ticket.TicketResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

/**
 * OccTicketingStrategy — optimistic concurrency control with version-based conflict detection.
 *
 * <p><b>Problem:</b> Pessimistic locking (2.B) serializes ALL requests through a single lock,
 * causing high latency under moderate concurrency. Most of the time threads are just
 * waiting — wasting connection pool slots doing nothing.
 *
 * <p><b>Mechanism:</b> "Assume no conflict, detect at commit time."
 * <ol>
 *   <li>READ event with current version (no lock)</li>
 *   <li>UPDATE with conditional WHERE: {@code version = :expected AND available_seats > 0}</li>
 *   <li>If 0 rows affected → version mismatch → throw OccConflictException</li>
 *   <li>RetryWithBackoff catches exception, waits with exponential backoff + jitter, retries</li>
 * </ol>
 *
 * <p><b>Design pattern:</b> Implements {@link TicketingStrategy}. Delegates retry logic
 * to {@link RetryWithBackoff} — keeping the strategy focused on the OCC mechanism.
 * Each retry attempt runs in a NEW transaction (critical: stale reads if same txn).
 *
 * <p><b>Scalability:</b> Non-blocking reads → high throughput under low contention.
 * Under high contention (conflict rate &gt; 30%), retry storms degrade throughput
 * below pessimistic locking. Crossover point: ~30% conflict rate.
 * For hot-key scenarios (Stock=1), prefer Queue-based (2.F).
 */
public class OccTicketingStrategy implements TicketingStrategy {

    private static final Logger log = LoggerFactory.getLogger(OccTicketingStrategy.class);

    private final ConflictMetrics metrics;
    private final RetryWithBackoff retryWithBackoff;
    private final OccAttemptService attemptService;

    public OccTicketingStrategy(ConflictMetrics metrics,
                                 RetryWithBackoff retryWithBackoff,
                                 OccAttemptService attemptService) {
        this.metrics = metrics;
        this.retryWithBackoff = retryWithBackoff;
        this.attemptService = attemptService;
    }

    /**
     * Reserve and buy — outer method WITHOUT @Transactional.
     * Retry loop wraps multiple transaction attempts.
     * Each attempt runs in its own transaction via OccAttemptService.
     */
    @Override
    public TicketResult reserveAndBuy(Long eventId, UUID userId, String seatLabel, String idempotencyKey) {
        try {
            // RetryWithBackoff calls attemptService.doAttempt() multiple times on OccConflictException
            // Each call = new transaction (Spring proxy intercepts attemptService calls)
            return retryWithBackoff.execute(
                    () -> attemptService.doAttempt(eventId, userId, seatLabel, idempotencyKey),
                    OccConflictException.class,
                    strategyName()
            );
        } catch (OccConflictException e) {
            // Max retries exceeded — RetryWithBackoff đã log và count metrics
            metrics.recordFailure(strategyName());
            return TicketResult.maxRetriesExceeded(eventId, seatLabel,
                    retryWithBackoff.calculateDelay(1) > 0 ? 5 : 5);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE hoặc CHECK violation — DB safety net
            log.warn("OCC: DataIntegrityViolation for event={}, seat={}: {}",
                    eventId, seatLabel, e.getMostSpecificCause().getMessage());
            metrics.recordConflict(strategyName());
            metrics.recordFailure(strategyName());
            return TicketResult.conflict(eventId, seatLabel,
                    "DB constraint violation: " + e.getMostSpecificCause().getMessage());
        } catch (Exception e) {
            log.error("OCC: unexpected error for event={}, seat={}: {}",
                    eventId, seatLabel, e.getMessage(), e);
            metrics.recordFailure(strategyName());
            return TicketResult.error(eventId, seatLabel, e.getMessage());
        }
    }


    @Override
    public String strategyName() {
        return TicketingConstants.STRATEGY_OCC;
    }
}
