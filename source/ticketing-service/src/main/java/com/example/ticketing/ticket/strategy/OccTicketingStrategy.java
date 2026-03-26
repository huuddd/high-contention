package com.example.ticketing.ticket.strategy;

import com.example.ticketing.common.OccConflictException;
import com.example.ticketing.common.RetryWithBackoff;
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

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final ConflictMetrics metrics;
    private final RetryWithBackoff retryWithBackoff;

    public OccTicketingStrategy(EventRepository eventRepository,
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
     * Reserve and buy — outer method WITHOUT @Transactional.
     * Retry loop wraps multiple transaction attempts.
     * Each attempt (doAttempt) runs in its own transaction.
     */
    @Override
    public TicketResult reserveAndBuy(Long eventId, UUID userId, String seatLabel) {
        try {
            // RetryWithBackoff sẽ gọi doAttempt nhiều lần nếu OccConflictException
            // Mỗi lần gọi = 1 transaction mới (vì doAttempt có @Transactional)
            return retryWithBackoff.execute(
                    () -> doAttempt(eventId, userId, seatLabel),
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

    /**
     * Single attempt — runs in its OWN transaction.
     * Throws OccConflictException on version mismatch → triggers retry in outer loop.
     */
    @Transactional
    public TicketResult doAttempt(Long eventId, UUID userId, String seatLabel) {
        // Bước 1: READ event — plain SELECT, không lock
        // Đọc current version để dùng trong conditional UPDATE
        Event event = eventRepository.findById(eventId).orElse(null);
        if (event == null) {
            metrics.recordFailure(strategyName());
            return TicketResult.eventNotFound(eventId);
        }

        // Bước 2: Check available seats — giá trị có thể stale, nhưng OK
        // Nếu thực sự hết vé, conditional UPDATE sẽ catch ở bước 3
        if (event.getAvailableSeats() <= 0) {
            metrics.recordFailure(strategyName());
            return TicketResult.seatNotAvailable(eventId, seatLabel);
        }

        // Bước 3: Tìm seat — không lock
        Seat seat = seatRepository.findByEventIdAndSeatLabel(eventId, seatLabel).orElse(null);
        if (seat == null || !seat.isAvailable()) {
            metrics.recordFailure(strategyName());
            return TicketResult.seatNotAvailable(eventId, seatLabel);
        }

        // Bước 4: Conditional UPDATE — đây là trái tim của OCC
        // WHERE version = :expected đảm bảo không ai sửa giữa READ và UPDATE
        // affected_rows = 0 → version mismatch → RETRY
        Long expectedVersion = event.getVersion();
        int updated = eventRepository.decrementAvailableSeatsWithVersion(eventId, expectedVersion);

        if (updated == 0) {
            // 2 khả năng: version mismatch HOẶC available_seats = 0
            // Đọc lại để phân biệt
            Event fresh = eventRepository.findById(eventId).orElse(null);
            if (fresh != null && fresh.getAvailableSeats() <= 0) {
                // Thực sự hết vé — không retry
                metrics.recordFailure(strategyName());
                return TicketResult.seatNotAvailable(eventId, seatLabel);
            }

            // Version mismatch — throw để trigger retry
            log.debug("OCC conflict: event={}, expected version={}, seat={}",
                    eventId, expectedVersion, seatLabel);
            throw new OccConflictException(eventId, seatLabel, expectedVersion);
        }

        // Bước 5: Mark seat as SOLD — status-based OCC
        // WHERE status = 'AVAILABLE' đóng vai trò version check cho seat
        int seatUpdated = seatRepository.markAsSold(seat.getId());
        if (seatUpdated == 0) {
            // Seat đã bị sold bởi thread khác — counter đã giảm nhưng seat không available
            // Counter drift xảy ra — nhưng ít nghiêm trọng hơn Naive vì event version đã tăng
            log.warn("OCC: seat {} already sold after event counter decremented — counter drift",
                    seatLabel);
            metrics.recordConflict(strategyName());
            metrics.recordFailure(strategyName());
            return TicketResult.conflict(eventId, seatLabel,
                    "Seat sold by another thread after counter update");
        }

        // Bước 6: Create ticket — UNIQUE constraint là safety net cuối
        Ticket ticket = new Ticket(event, seat, userId, null);
        ticket = ticketRepository.save(ticket);

        metrics.recordSuccess(strategyName());
        log.info("OCC: ticket {} created for event={}, seat={}, user={}, version={}→{}",
                ticket.getId(), eventId, seatLabel, userId, expectedVersion, expectedVersion + 1);
        return TicketResult.success(ticket.getId(), eventId, seatLabel, userId);
    }

    @Override
    public String strategyName() {
        return TicketingConstants.STRATEGY_OCC;
    }
}
