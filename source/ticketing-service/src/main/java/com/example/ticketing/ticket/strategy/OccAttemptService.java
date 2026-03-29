package com.example.ticketing.ticket.strategy;

import com.example.ticketing.common.OccConflictException;
import com.example.ticketing.common.TicketingConstants;
import com.example.ticketing.event.Event;
import com.example.ticketing.event.EventRepository;
import com.example.ticketing.observability.ConflictMetrics;
import com.example.ticketing.ticket.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * OccAttemptService — separate service for OCC transaction attempts.
 *
 * <p><b>Problem:</b> Spring AOP @Transactional doesn't work on internal method calls
 * within the same class. When OccTicketingStrategy calls its own doAttempt() method,
 * Spring proxy is bypassed → @Transactional is ignored → @Modifying queries fail.
 *
 * <p><b>Solution:</b> Extract doAttempt() into a separate @Service bean.
 * OccTicketingStrategy injects this service → Spring proxy intercepts the call
 * → @Transactional works correctly.
 *
 * <p><b>Design pattern:</b> Service extraction to enable AOP interception.
 * Each call to doAttempt() runs in a NEW transaction (REQUIRES_NEW propagation).
 */
@Service
public class OccAttemptService {

    private static final Logger log = LoggerFactory.getLogger(OccAttemptService.class);

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final ConflictMetrics metrics;

    public OccAttemptService(EventRepository eventRepository,
                              SeatRepository seatRepository,
                              TicketRepository ticketRepository,
                              ConflictMetrics metrics) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.metrics = metrics;
    }

    /**
     * Single OCC attempt — runs in its OWN transaction.
     * Throws OccConflictException on version mismatch → triggers retry in caller.
     */
    @Transactional
    public TicketResult doAttempt(Long eventId, UUID userId, String seatLabel, String idempotencyKey) {
        // Bước 1: READ event — plain SELECT, không lock
        // Đọc current version để dùng trong conditional UPDATE
        Event event = eventRepository.findById(eventId).orElse(null);
        if (event == null) {
            metrics.recordFailure(TicketingConstants.STRATEGY_OCC);
            return TicketResult.eventNotFound(eventId);
        }

        // Bước 2: Check available seats — giá trị có thể stale, nhưng OK
        // Nếu thực sự hết vé, conditional UPDATE sẽ catch ở bước 3
        if (event.getAvailableSeats() <= 0) {
            metrics.recordFailure(TicketingConstants.STRATEGY_OCC);
            return TicketResult.seatNotAvailable(eventId, seatLabel);
        }

        // Bước 3: Tìm seat — không lock
        Seat seat = seatRepository.findByEventIdAndSeatLabel(eventId, seatLabel).orElse(null);
        if (seat == null || !seat.isAvailable()) {
            metrics.recordFailure(TicketingConstants.STRATEGY_OCC);
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
                metrics.recordFailure(TicketingConstants.STRATEGY_OCC);
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
            metrics.recordConflict(TicketingConstants.STRATEGY_OCC);
            metrics.recordFailure(TicketingConstants.STRATEGY_OCC);
            return TicketResult.conflict(eventId, seatLabel,
                    "Seat sold by another thread after counter update");
        }

        // Bước 6: Create ticket — UNIQUE constraint là safety net cuối
        Ticket ticket = new Ticket(event, seat, userId, idempotencyKey);
        ticket = ticketRepository.save(ticket);

        metrics.recordSuccess(TicketingConstants.STRATEGY_OCC);
        log.info("OCC: ticket {} created for event={}, seat={}, user={}, version={}→{}",
                ticket.getId(), eventId, seatLabel, userId, expectedVersion, expectedVersion + 1);
        return TicketResult.success(ticket.getId(), eventId, seatLabel, userId);
    }
}
