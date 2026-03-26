package com.example.ticketing.ticket.strategy;

import com.example.ticketing.ticket.TicketResult;
import java.util.UUID;

/**
 * TicketingStrategy — unified interface for all concurrency control strategies.
 *
 * <p><b>Problem:</b> Different concurrency strategies (Naive, Pessimistic Locking, OCC,
 * SERIALIZABLE, Reservation+Fencing, Queue-based) share the same business operation
 * but differ fundamentally in how they handle concurrent writes to shared resources.
 *
 * <p><b>Mechanism:</b> Each implementation encapsulates its own locking/retry/queuing
 * mechanism. The controller delegates to this interface without knowing which strategy
 * is active — decoupling business logic from concurrency control.
 *
 * <p><b>Design pattern:</b> Strategy Pattern (GoF) — allows runtime switching of
 * concurrency strategy via configuration, without modifying any controller or service code.
 *
 * <p><b>Scalability:</b> Each strategy has different throughput/latency characteristics.
 * Strategy selection should match the deployment context: single-DB high contention
 * vs multi-instance UX-oriented flows.
 */
public interface TicketingStrategy {

    /**
     * Reserve and buy a ticket for a specific seat in an event.
     *
     * @param eventId   the event to purchase a ticket for
     * @param userId    the user making the purchase
     * @param seatLabel the desired seat label (e.g., "A15")
     * @return result containing ticket ID on success, or error details on failure
     */
    TicketResult reserveAndBuy(Long eventId, UUID userId, String seatLabel);

    /**
     * Returns the strategy name for metrics tagging and logging.
     *
     * @return strategy identifier (e.g., "naive", "pessimistic", "occ")
     */
    String strategyName();
}
