package com.example.ticketing.ticket.strategy;

import com.example.ticketing.common.TicketingConstants;
import com.example.ticketing.event.Event;
import com.example.ticketing.event.EventRepository;
import com.example.ticketing.observability.ConflictMetrics;
import com.example.ticketing.ticket.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * PessimisticTicketingStrategy — serialize concurrent ticket purchases via row-level locking.
 *
 * <p><b>Problem:</b> Without locking, two transactions can both read available_seats=1,
 * both proceed to UPDATE, and both succeed — resulting in oversell (demonstrated in 2.A).
 *
 * <p><b>Mechanism:</b> SELECT FOR UPDATE acquires an exclusive row lock on the event row.
 * Competing transactions block at the SELECT until the lock is released on COMMIT/ROLLBACK.
 * PostgreSQL lock manager queues waiters in FIFO order. After acquiring the lock,
 * the transaction re-reads the row with the latest committed value — eliminating the
 * TOCTOU gap that causes oversell in the Naive approach.
 *
 * <p><b>Design pattern:</b> Implements {@link TicketingStrategy} (Strategy Pattern)
 * — swappable without modifying TicketController. Uses database-native locking
 * rather than application-level synchronization (which doesn't work across instances).
 *
 * <p><b>Scalability:</b> Throughput = 1 / transaction_time (fully serialized).
 * Works well for &lt; 500 concurrent requests with transaction_time &lt; 10ms.
 * Beyond that, p95 latency grows linearly: p95 ≈ 0.95 × C × T.
 * Not suitable for distributed systems — locks don't cross DB nodes.
 * For high concurrency or long transactions, consider OCC (2.C) or Queue-based (2.F).
 */
public class PessimisticTicketingStrategy implements TicketingStrategy {

    private static final Logger log = LoggerFactory.getLogger(PessimisticTicketingStrategy.class);

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final ConflictMetrics metrics;

    public PessimisticTicketingStrategy(EventRepository eventRepository,
                                         SeatRepository seatRepository,
                                         TicketRepository ticketRepository,
                                         ConflictMetrics metrics) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public TicketResult reserveAndBuy(Long eventId, UUID userId, String seatLabel) {
        try {
            // Bước 1: Lock event row — SELECT FOR UPDATE
            // Tất cả concurrent threads xếp hàng ở đây (FIFO)
            // Sau khi acquire lock, đọc được giá trị MỚI NHẤT (post-commit của holder trước)
            Event event = eventRepository.findByIdForUpdate(eventId).orElse(null);
            if (event == null) {
                metrics.recordFailure(strategyName());
                return TicketResult.eventNotFound(eventId);
            }

            // Bước 2: Check available seats — giá trị đáng tin cậy vì đang hold lock
            // Không có TOCTOU gap: không thread nào khác có thể modify event row
            if (event.getAvailableSeats() <= 0) {
                // Lock released khi method return (COMMIT)
                metrics.recordFailure(strategyName());
                return TicketResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 3: Lock seat row — FOR UPDATE để tránh double-book
            // Lock order: event trước, seat sau → consistent order → no deadlock
            Seat seat = seatRepository.findByEventIdAndSeatLabelForUpdate(eventId, seatLabel)
                    .orElse(null);
            if (seat == null) {
                metrics.recordFailure(strategyName());
                return TicketResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 4: Check seat availability — đáng tin cậy vì seat đang bị lock
            if (!seat.isAvailable()) {
                // Seat đã bị sold bởi transaction trước (đã commit trước khi ta acquire lock)
                metrics.recordFailure(strategyName());
                return TicketResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 5: Decrement counter — safe vì event row đang bị lock
            int updated = eventRepository.decrementAvailableSeats(eventId);
            if (updated == 0) {
                // Defensive: CHECK constraint chặn — không nên xảy ra nếu logic đúng
                log.warn("Pessimistic: decrementAvailableSeats returned 0 for event={}, "
                        + "available_seats was {} — possible bug", eventId, event.getAvailableSeats());
                metrics.recordFailure(strategyName());
                return TicketResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 6: Mark seat as SOLD
            int seatUpdated = seatRepository.markAsSold(seat.getId());
            if (seatUpdated == 0) {
                // Defensive: seat đã bị sold — không nên xảy ra vì seat đang bị lock
                log.error("Pessimistic: markAsSold returned 0 for seat={} — THIS SHOULD NOT HAPPEN "
                        + "(seat was locked)", seatLabel);
                metrics.recordOversell(strategyName());
                metrics.recordFailure(strategyName());
                return TicketResult.conflict(eventId, seatLabel, "Seat locked but could not mark as sold");
            }

            // Bước 7: Create ticket — UNIQUE constraint là safety net cuối cùng
            Ticket ticket = new Ticket(event, seat, userId, null);
            ticket = ticketRepository.save(ticket);

            // COMMIT xảy ra khi method return → release ALL locks (event + seat)
            metrics.recordSuccess(strategyName());
            log.info("Pessimistic: ticket {} created for event={}, seat={}, user={}",
                    ticket.getId(), eventId, seatLabel, userId);
            return TicketResult.success(ticket.getId(), eventId, seatLabel, userId);

        } catch (DataIntegrityViolationException e) {
            // UNIQUE violation hoặc CHECK violation — DB safety net caught something
            // Với pessimistic locking đúng, điều này KHÔNG NÊN xảy ra
            String sqlState = extractSqlState(e);
            if (TicketingConstants.PG_DEADLOCK_DETECTED.equals(sqlState)) {
                // Deadlock: PostgreSQL đã abort 1 trong 2 transactions
                log.warn("Pessimistic: DEADLOCK detected for event={}, seat={}: {}",
                        eventId, seatLabel, e.getMostSpecificCause().getMessage());
                metrics.recordDeadlock(strategyName());
                metrics.recordFailure(strategyName());
                return TicketResult.conflict(eventId, seatLabel, "Deadlock detected — transaction aborted");
            }

            log.warn("Pessimistic: DataIntegrityViolation for event={}, seat={}: {}",
                    eventId, seatLabel, e.getMostSpecificCause().getMessage());
            metrics.recordConflict(strategyName());
            metrics.recordFailure(strategyName());
            return TicketResult.conflict(eventId, seatLabel,
                    "DB constraint violation: " + e.getMostSpecificCause().getMessage());

        } catch (Exception e) {
            // Timeout, connection error, hoặc unexpected exception
            if (e.getMessage() != null && e.getMessage().contains("lock timeout")) {
                log.warn("Pessimistic: lock timeout for event={}, seat={}", eventId, seatLabel);
                metrics.recordTimeout(strategyName());
                metrics.recordFailure(strategyName());
                return TicketResult.conflict(eventId, seatLabel, "Lock wait timeout");
            }

            log.error("Pessimistic: unexpected error for event={}, seat={}: {}",
                    eventId, seatLabel, e.getMessage(), e);
            metrics.recordFailure(strategyName());
            return TicketResult.error(eventId, seatLabel, e.getMessage());
        }
    }

    @Override
    public String strategyName() {
        return TicketingConstants.STRATEGY_PESSIMISTIC;
    }

    /**
     * Trích xuất PostgreSQL SQL state code từ exception chain.
     * Dùng để phân biệt deadlock (40P01) vs unique violation (23505) vs check violation (23514).
     */
    private String extractSqlState(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        if (cause instanceof java.sql.SQLException sqlEx) {
            return sqlEx.getSQLState();
        }
        return null;
    }
}
