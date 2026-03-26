package com.example.ticketing.ticket.strategy;

import com.example.ticketing.common.RetryWithBackoff;
import com.example.ticketing.common.SerializationConflictException;
import com.example.ticketing.common.TicketingConstants;
import com.example.ticketing.event.Event;
import com.example.ticketing.event.EventRepository;
import com.example.ticketing.observability.ConflictMetrics;
import com.example.ticketing.ticket.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

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

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final ConflictMetrics metrics;
    private final RetryWithBackoff retryWithBackoff;

    public SerializableTicketingStrategy(EventRepository eventRepository,
                                          SeatRepository seatRepository,
                                          TicketRepository ticketRepository,
                                          ConflictMetrics metrics,
                                          RetryWithBackoff retryWithBackoff) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.metrics = metrics;
        this.retryWithBackoff = retryWithBackoff;
    }

    /**
     * Outer method — retry loop, WITHOUT @Transactional.
     * Each doAttempt runs in its own SERIALIZABLE transaction.
     */
    @Override
    public TicketResult reserveAndBuy(Long eventId, UUID userId, String seatLabel) {
        try {
            return retryWithBackoff.execute(
                    () -> doAttempt(eventId, userId, seatLabel),
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

    /**
     * Single attempt — runs in SERIALIZABLE isolation.
     *
     * <p>Code is nearly identical to Naive (2.A). The ONLY difference:
     * {@code isolation = Isolation.SERIALIZABLE}. PostgreSQL SSI engine
     * detects anomalies automatically — no version column, no FOR UPDATE.
     *
     * <p>If SSI detects a dangerous rw-dependency cycle, it throws
     * CannotSerializeTransactionException → we wrap it as SerializationConflictException
     * → RetryWithBackoff retries with backoff + jitter.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TicketResult doAttempt(Long eventId, UUID userId, String seatLabel) {
        try {
            // Bước 1: READ event — plain SELECT (SSI tracks read dependency)
            // Giống hệt Naive — PostgreSQL tự track rw-dependencies
            Event event = eventRepository.findById(eventId).orElse(null);
            if (event == null) {
                metrics.recordFailure(strategyName());
                return TicketResult.eventNotFound(eventId);
            }

            // Bước 2: Check available seats
            // SSI snapshot: giá trị stable trong toàn bộ transaction
            // (khác READ COMMITTED nơi mỗi statement thấy snapshot mới)
            if (event.getAvailableSeats() <= 0) {
                metrics.recordFailure(strategyName());
                return TicketResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 3: Tìm seat — plain SELECT (SSI tracks)
            Seat seat = seatRepository.findByEventIdAndSeatLabel(eventId, seatLabel).orElse(null);
            if (seat == null || !seat.isAvailable()) {
                metrics.recordFailure(strategyName());
                return TicketResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 4: Decrement counter — SSI sẽ detect conflict TẠI ĐÂY
            // Nếu concurrent txn đã modify event row → serialization failure
            int updated = eventRepository.decrementAvailableSeats(eventId);
            if (updated == 0) {
                metrics.recordFailure(strategyName());
                return TicketResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 5: Mark seat as SOLD
            int seatUpdated = seatRepository.markAsSold(seat.getId());
            if (seatUpdated == 0) {
                log.warn("Serializable: seat {} already sold after counter decremented", seatLabel);
                metrics.recordConflict(strategyName());
                metrics.recordFailure(strategyName());
                return TicketResult.conflict(eventId, seatLabel, "Seat sold by another transaction");
            }

            // Bước 6: Create ticket
            Ticket ticket = new Ticket(event, seat, userId, null);
            ticket = ticketRepository.save(ticket);

            metrics.recordSuccess(strategyName());
            log.info("Serializable: ticket {} created for event={}, seat={}, user={}",
                    ticket.getId(), eventId, seatLabel, userId);
            return TicketResult.success(ticket.getId(), eventId, seatLabel, userId);

        } catch (CannotSerializeTransactionException e) {
            // PostgreSQL SSI detected serialization anomaly (40001)
            // Wrap và throw để RetryWithBackoff catch
            log.debug("Serializable: serialization failure for event={}, seat={}: {}",
                    eventId, seatLabel, e.getMessage());
            throw new SerializationConflictException(
                    "Serialization failure for event=" + eventId + ", seat=" + seatLabel, e);

        } catch (PessimisticLockingFailureException e) {
            // Spring maps cả deadlock (40P01) và serialization failure (40001) vào parent class
            // PessimisticLockingFailureException — tên confusing nhưng behavior đúng
            // Kiểm tra xem có phải deadlock không
            String sqlState = extractSqlState(e);
            if (TicketingConstants.PG_DEADLOCK_DETECTED.equals(sqlState)) {
                log.warn("Serializable: deadlock detected for event={}, seat={}", eventId, seatLabel);
                metrics.recordDeadlock(strategyName());
            }
            throw new SerializationConflictException(
                    "Locking failure for event=" + eventId + ", seat=" + seatLabel, e);
        }
    }

    @Override
    public String strategyName() {
        return TicketingConstants.STRATEGY_SERIALIZABLE;
    }

    private String extractSqlState(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sqlEx) {
                return sqlEx.getSQLState();
            }
            cause = cause.getCause();
        }
        return null;
    }
}
