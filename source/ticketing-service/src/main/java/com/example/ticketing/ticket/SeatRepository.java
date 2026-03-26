package com.example.ticketing.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * SeatRepository — data access for seats with strategy-specific locking queries.
 *
 * <p><b>Problem:</b> Different strategies need different ways to find and lock a seat:
 * plain SELECT (naive), SELECT FOR UPDATE (pessimistic), or status-based check (reservation).
 *
 * <p><b>Mechanism:</b> Native queries for lock hints that JPA/JPQL cannot express.
 *
 * <p><b>Design pattern:</b> Repository Pattern with strategy-aware query methods.
 *
 * <p><b>Scalability:</b> Partial indexes on (event_id, status) keep seat lookups fast
 * even with large event tables.
 */
@Repository
public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * Find a specific seat by event and label — no locking.
     */
    @Query("SELECT s FROM Seat s WHERE s.eventId = :eventId AND s.seatLabel = :seatLabel")
    Optional<Seat> findByEventIdAndSeatLabel(@Param("eventId") Long eventId,
                                              @Param("seatLabel") String seatLabel);

    /**
     * Find seat with pessimistic lock — SELECT FOR UPDATE.
     * Used by Pessimistic Locking strategy (2.B).
     */
    @Query(value = "SELECT * FROM seats WHERE event_id = :eventId AND seat_label = :seatLabel FOR UPDATE",
            nativeQuery = true)
    Optional<Seat> findByEventIdAndSeatLabelForUpdate(@Param("eventId") Long eventId,
                                                       @Param("seatLabel") String seatLabel);

    /**
     * Mark seat as SOLD — used after successful ticket creation.
     */
    @Modifying
    @Query("UPDATE Seat s SET s.status = 'SOLD' WHERE s.id = :seatId AND s.status = 'AVAILABLE'")
    int markAsSold(@Param("seatId") Long seatId);

    /**
     * Mark seat as LOCKED for reservation — used by Reservation strategy (2.E).
     */
    @Modifying
    @Query(value = "UPDATE seats SET status = 'LOCKED', locked_by = :userId, " +
            "locked_until = NOW() + INTERVAL '1 minute' * :ttlMinutes " +
            "WHERE id = :seatId AND status = 'AVAILABLE'",
            nativeQuery = true)
    int lockForReservation(@Param("seatId") Long seatId,
                           @Param("userId") java.util.UUID userId,
                           @Param("ttlMinutes") int ttlMinutes);

    /**
     * Release a locked seat back to AVAILABLE — used when reservation expires or is cancelled.
     */
    @Modifying
    @Query("UPDATE Seat s SET s.status = 'AVAILABLE', s.lockedBy = NULL, s.lockedUntil = NULL " +
            "WHERE s.id = :seatId AND s.status = 'LOCKED'")
    int releaseLockedSeat(@Param("seatId") Long seatId);
}
