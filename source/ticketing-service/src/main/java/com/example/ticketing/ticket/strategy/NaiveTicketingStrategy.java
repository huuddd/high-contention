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
 * NaiveTicketingStrategy — intentionally broken baseline with no concurrency control.
 *
 * <p><b>Problem:</b> This strategy demonstrates the classic TOCTOU (Time-Of-Check
 * to Time-Of-Use) race condition. Multiple threads can read available_seats=1,
 * all pass the check, and all proceed to purchase — resulting in oversell.
 *
 * <p><b>Mechanism:</b> Plain SELECT (no lock, no version check) followed by UPDATE.
 * Under READ COMMITTED isolation, each transaction sees its own snapshot.
 * The gap between SELECT and UPDATE is the vulnerability window.
 *
 * <p><b>Design pattern:</b> Implements {@link TicketingStrategy} (Strategy Pattern).
 * Exists solely as a broken baseline for benchmark comparison.
 *
 * <p><b>Scalability:</b> Highest throughput (no blocking), lowest latency (no waiting),
 * but produces INCORRECT results. Oversell count grows linearly with concurrency.
 * Never use in production for scarce resources.
 */
public class NaiveTicketingStrategy implements TicketingStrategy {

    private static final Logger log = LoggerFactory.getLogger(NaiveTicketingStrategy.class);

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final ConflictMetrics metrics;

    public NaiveTicketingStrategy(EventRepository eventRepository,
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
    public TicketResult reserveAndBuy(Long eventId, UUID userId, String seatLabel, String idempotencyKey) {
        try {
            // Bước 1: Đọc event — plain SELECT, không lock
            // ⚠️ Tất cả concurrent threads thấy cùng snapshot tại thời điểm này
            Event event = eventRepository.findById(eventId).orElse(null);
            if (event == null) {
                metrics.recordFailure(strategyName());
                return TicketResult.eventNotFound(eventId);
            }

            // Bước 2: Kiểm tra còn vé — TOCTOU gap bắt đầu từ đây
            // ⚠️ Giữa lúc check và lúc update, thread khác có thể đã mua hết
            if (event.getAvailableSeats() <= 0) {
                metrics.recordFailure(strategyName());
                return TicketResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 3: Tìm seat — không lock, seat có thể bị thread khác sold
            Seat seat = seatRepository.findByEventIdAndSeatLabel(eventId, seatLabel).orElse(null);
            if (seat == null || !seat.isAvailable()) {
                metrics.recordFailure(strategyName());
                return TicketResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 4: Giảm counter — nhưng thread khác cũng đang giảm cùng lúc
            // ⚠️ Không có version check → nhiều threads cùng giảm counter song song
            int updated = eventRepository.decrementAvailableSeats(eventId);
            if (updated == 0) {
                // CHECK constraint chặn: available_seats đã = 0
                metrics.recordFailure(strategyName());
                return TicketResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 5: Đánh dấu seat là SOLD
            // ⚠️ Thread khác có thể đã sold cùng seat này
            int seatUpdated = seatRepository.markAsSold(seat.getId());
            if (seatUpdated == 0) {
                // Seat đã bị thread khác sold trước — nhưng counter đã giảm!
                // → Counter drift: available_seats không khớp thực tế
                log.warn("Seat {} already sold but counter was decremented — counter drift!",
                        seatLabel);
                metrics.recordOversell(strategyName());
                metrics.recordFailure(strategyName());
                return TicketResult.conflict(eventId, seatLabel,
                        "Seat already sold by another thread (counter drift)");
            }

            // Bước 6: Tạo ticket — UNIQUE constraint (event_id, seat_id) là tuyến phòng thủ cuối
            Ticket ticket = new Ticket(event, seat, userId, idempotencyKey);
            ticket = ticketRepository.save(ticket);

            metrics.recordSuccess(strategyName());
            log.info("Naive: ticket {} created for event={}, seat={}, user={}",
                    ticket.getId(), eventId, seatLabel, userId);
            return TicketResult.success(ticket.getId(), eventId, seatLabel, userId);

        } catch (DataIntegrityViolationException e) {
            // UNIQUE violation (double-book) hoặc CHECK violation (oversell)
            // → DB constraints đã chặn, nhưng state có thể inconsistent
            log.warn("Naive: DataIntegrityViolation for event={}, seat={}: {}",
                    eventId, seatLabel, e.getMostSpecificCause().getMessage());
            metrics.recordConflict(strategyName());
            metrics.recordFailure(strategyName());
            return TicketResult.conflict(eventId, seatLabel,
                    "DB constraint prevented invalid operation: " + e.getMostSpecificCause().getMessage());
        } catch (Exception e) {
            log.error("Naive: unexpected error for event={}, seat={}: {}",
                    eventId, seatLabel, e.getMessage(), e);
            metrics.recordFailure(strategyName());
            return TicketResult.error(eventId, seatLabel, e.getMessage());
        }
    }

    @Override
    public String strategyName() {
        return TicketingConstants.STRATEGY_NAIVE;
    }
}
