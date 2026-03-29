package com.example.ticketing.ticket.strategy;

import com.example.ticketing.common.SerializationConflictException;
import com.example.ticketing.common.TicketingConstants;
import com.example.ticketing.event.Event;
import com.example.ticketing.event.EventRepository;
import com.example.ticketing.observability.ConflictMetrics;
import com.example.ticketing.ticket.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * SerializableAttemptService — separate service for SERIALIZABLE transaction attempts.
 *
 * <p><b>Problem:</b> Same as OccAttemptService — Spring AOP @Transactional doesn't work
 * on internal method calls. SerializableTicketingStrategy calling its own doAttempt()
 * bypasses Spring proxy → @Transactional ignored → @Modifying queries fail.
 *
 * <p><b>Solution:</b> Extract doAttempt() into separate @Service bean.
 * Each call runs in SERIALIZABLE isolation with proper transaction management.
 */
@Service
public class SerializableAttemptService {

    private static final Logger log = LoggerFactory.getLogger(SerializableAttemptService.class);

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final ConflictMetrics metrics;

    public SerializableAttemptService(EventRepository eventRepository,
                                       SeatRepository seatRepository,
                                       TicketRepository ticketRepository,
                                       ConflictMetrics metrics) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.metrics = metrics;
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
    public TicketResult doAttempt(Long eventId, UUID userId, String seatLabel, String idempotencyKey) {
        try {
            // Bước 1: READ event — plain SELECT (SSI tracks read dependency)
            // Giống hệt Naive — PostgreSQL tự track rw-dependencies
            Event event = eventRepository.findById(eventId).orElse(null);
            if (event == null) {
                metrics.recordFailure(TicketingConstants.STRATEGY_SERIALIZABLE);
                return TicketResult.eventNotFound(eventId);
            }

            // Bước 2: Check available seats
            // SSI snapshot: giá trị stable trong toàn bộ transaction
            // (khác READ COMMITTED nơi mỗi statement thấy snapshot mới)
            if (event.getAvailableSeats() <= 0) {
                metrics.recordFailure(TicketingConstants.STRATEGY_SERIALIZABLE);
                return TicketResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 3: Tìm seat — plain SELECT (SSI tracks)
            Seat seat = seatRepository.findByEventIdAndSeatLabel(eventId, seatLabel).orElse(null);
            if (seat == null || !seat.isAvailable()) {
                metrics.recordFailure(TicketingConstants.STRATEGY_SERIALIZABLE);
                return TicketResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 4: Decrement counter — SSI sẽ detect conflict TẠI ĐÂY
            // Nếu concurrent txn đã modify event row → serialization failure
            int updated = eventRepository.decrementAvailableSeats(eventId);
            if (updated == 0) {
                metrics.recordFailure(TicketingConstants.STRATEGY_SERIALIZABLE);
                return TicketResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 5: Mark seat as SOLD
            int seatUpdated = seatRepository.markAsSold(seat.getId());
            if (seatUpdated == 0) {
                log.warn("Serializable: seat {} already sold after counter decremented", seatLabel);
                metrics.recordConflict(TicketingConstants.STRATEGY_SERIALIZABLE);
                metrics.recordFailure(TicketingConstants.STRATEGY_SERIALIZABLE);
                return TicketResult.conflict(eventId, seatLabel, "Seat sold by another transaction");
            }

            // Bước 6: Create ticket
            Ticket ticket = new Ticket(event, seat, userId, idempotencyKey);
            ticket = ticketRepository.save(ticket);

            metrics.recordSuccess(TicketingConstants.STRATEGY_SERIALIZABLE);
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
                metrics.recordDeadlock(TicketingConstants.STRATEGY_SERIALIZABLE);
            }
            throw new SerializationConflictException(
                    "Locking failure for event=" + eventId + ", seat=" + seatLabel, e);
        }
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
