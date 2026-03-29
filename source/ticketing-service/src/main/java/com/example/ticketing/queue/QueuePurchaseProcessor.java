package com.example.ticketing.queue;

import com.example.ticketing.common.TicketingConstants;
import com.example.ticketing.event.Event;
import com.example.ticketing.event.EventRepository;
import com.example.ticketing.observability.ConflictMetrics;
import com.example.ticketing.ticket.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * QueuePurchaseProcessor — extracted from QueueWorker to allow Spring AOP
 * to correctly proxy @Transactional on processPurchase().
 *
 * <p><b>Why separate bean:</b> Spring AOP proxies cannot intercept self-calls
 * within the same bean. QueueWorker.workerLoop() calling this.processPurchase()
 * would bypass the @Transactional proxy, causing @Modifying queries to fail
 * with "no transaction is in progress". Extracting into a separate @Service
 * bean ensures Spring's proxy wraps each call in a proper transaction.
 */
@Service
public class QueuePurchaseProcessor {

    private static final Logger log = LoggerFactory.getLogger(QueuePurchaseProcessor.class);

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final ConflictMetrics metrics;

    public QueuePurchaseProcessor(EventRepository eventRepository,
                                   SeatRepository seatRepository,
                                   TicketRepository ticketRepository,
                                   ConflictMetrics metrics) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.metrics = metrics;
    }

    /**
     * Process a single ticket purchase — the actual DB work.
     * No locking needed — only one worker processes per event at a time.
     * Uses plain SELECT + UPDATE — zero contention.
     */
    @Transactional
    public TicketResult processPurchase(QueueTicketRequest request) {
        try {
            // Bước 1: Read event — no lock needed (single writer)
            Event event = eventRepository.findById(request.eventId()).orElse(null);
            if (event == null) {
                metrics.recordFailure(TicketingConstants.STRATEGY_QUEUE);
                return TicketResult.eventNotFound(request.eventId());
            }

            // Bước 2: Check available seats
            if (event.getAvailableSeats() <= 0) {
                metrics.recordFailure(TicketingConstants.STRATEGY_QUEUE);
                return TicketResult.seatNotAvailable(request.eventId(), request.seatLabel());
            }

            // Bước 3: Find seat
            Seat seat = seatRepository.findByEventIdAndSeatLabel(request.eventId(), request.seatLabel())
                    .orElse(null);
            if (seat == null || !seat.isAvailable()) {
                metrics.recordFailure(TicketingConstants.STRATEGY_QUEUE);
                return TicketResult.seatNotAvailable(request.eventId(), request.seatLabel());
            }

            // Bước 4: Decrement counter — safe: single writer
            int updated = eventRepository.decrementAvailableSeats(request.eventId());
            if (updated == 0) {
                metrics.recordFailure(TicketingConstants.STRATEGY_QUEUE);
                return TicketResult.seatNotAvailable(request.eventId(), request.seatLabel());
            }

            // Bước 5: Mark seat as SOLD
            int seatUpdated = seatRepository.markAsSold(seat.getId());
            if (seatUpdated == 0) {
                log.error("Queue: seat {} already sold — THIS SHOULD NOT HAPPEN (single writer)",
                        request.seatLabel());
                metrics.recordOversell(TicketingConstants.STRATEGY_QUEUE);
                metrics.recordFailure(TicketingConstants.STRATEGY_QUEUE);
                return TicketResult.conflict(request.eventId(), request.seatLabel(),
                        "Seat sold unexpectedly");
            }

            // Bước 6: Create ticket
            Ticket ticket = new Ticket(event, seat, request.userId(), null);
            ticket = ticketRepository.save(ticket);

            metrics.recordSuccess(TicketingConstants.STRATEGY_QUEUE);
            log.info("Queue: ticket {} created for event={}, seat={}, user={}",
                    ticket.getId(), request.eventId(), request.seatLabel(), request.userId());
            return TicketResult.success(ticket.getId(), request.eventId(),
                    request.seatLabel(), request.userId());

        } catch (DataIntegrityViolationException e) {
            log.warn("Queue: DataIntegrityViolation for event={}, seat={}: {}",
                    request.eventId(), request.seatLabel(), e.getMostSpecificCause().getMessage());
            metrics.recordConflict(TicketingConstants.STRATEGY_QUEUE);
            metrics.recordFailure(TicketingConstants.STRATEGY_QUEUE);
            return TicketResult.conflict(request.eventId(), request.seatLabel(),
                    "DB constraint: " + e.getMostSpecificCause().getMessage());
        }
    }
}
