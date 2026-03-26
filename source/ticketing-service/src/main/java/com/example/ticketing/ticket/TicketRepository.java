package com.example.ticketing.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * TicketRepository — data access for tickets.
 *
 * <p><b>Problem:</b> Need to query tickets by event, user, and idempotency key
 * for correctness verification and duplicate detection.
 *
 * <p><b>Mechanism:</b> JPA Repository with custom queries for idempotency lookups.
 *
 * <p><b>Design pattern:</b> Repository Pattern.
 *
 * <p><b>Scalability:</b> Indexes on (event_id), (user_id), and (idempotency_key)
 * keep lookups O(log n).
 */
@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /**
     * Find ticket by idempotency key — for duplicate detection (task 3.1).
     */
    Optional<Ticket> findByIdempotencyKey(String idempotencyKey);

    /**
     * Count sold tickets for an event — for correctness verification.
     */
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.eventId = :eventId")
    long countByEventId(@Param("eventId") Long eventId);

    /**
     * Check if a ticket exists for a specific event+seat — double-book detection.
     */
    @Query("SELECT COUNT(t) > 0 FROM Ticket t WHERE t.eventId = :eventId AND t.seatId = :seatId")
    boolean existsByEventIdAndSeatId(@Param("eventId") Long eventId, @Param("seatId") Long seatId);
}
