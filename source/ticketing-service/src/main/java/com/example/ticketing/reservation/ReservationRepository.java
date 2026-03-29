package com.example.ticketing.reservation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ReservationRepository — data access for reservation lifecycle management.
 *
 * <p><b>Problem:</b> Reservations have a complex lifecycle (PENDING → CONFIRMED/EXPIRED/CANCELLED)
 * with concurrent access from user confirm, user cancel, and background expiry job.
 *
 * <p><b>Mechanism:</b> Atomic status transitions via conditional UPDATEs.
 * {@code WHERE status = 'PENDING' AND fencing_token = :token AND expires_at > NOW()}
 * ensures only valid, non-expired reservations with correct token can be confirmed.
 *
 * <p><b>Design pattern:</b> Repository Pattern with native queries for atomic operations.
 *
 * <p><b>Scalability:</b> Expiry batch updates use {@code WHERE ... LIMIT} to avoid
 * long-running transactions. Confirm/cancel are single-row atomic updates.
 */
@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /**
     * Find reservation by ID and fencing token — for confirm/cancel validation.
     */
    Optional<Reservation> findByIdAndFencingToken(Long id, UUID fencingToken);

    /**
     * Find active (PENDING, not expired) reservation for a specific seat.
     */
    @Query("SELECT r FROM Reservation r WHERE r.seat.id = :seatId AND r.status = 'PENDING' " +
            "AND r.expiresAt > CURRENT_TIMESTAMP")
    Optional<Reservation> findActiveBySeatId(@Param("seatId") Long seatId);

    /**
     * Atomic confirm — transitions PENDING → CONFIRMED only if token matches and not expired.
     * Returns 0 if reservation expired, wrong token, or already confirmed/cancelled.
     */
    @Modifying
    @Query(value = "UPDATE reservations SET status = 'CONFIRMED' " +
            "WHERE id = :id AND fencing_token = :token AND status = 'PENDING' " +
            "AND expires_at > NOW()", nativeQuery = true)
    int confirmReservation(@Param("id") Long id, @Param("token") UUID token);

    /**
     * Atomic cancel — transitions PENDING → CANCELLED.
     */
    @Modifying
    @Query(value = "UPDATE reservations SET status = 'CANCELLED' " +
            "WHERE id = :id AND fencing_token = :token AND status = 'PENDING'", nativeQuery = true)
    int cancelReservation(@Param("id") Long id, @Param("token") UUID token);

    /**
     * Find expired PENDING reservations — for background cleanup job.
     * Limits batch size to avoid long transactions.
     */
    @Query(value = "SELECT * FROM reservations WHERE status = 'PENDING' AND expires_at < NOW() " +
            "ORDER BY expires_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Reservation> findExpiredReservations(@Param("batchSize") int batchSize);

    /**
     * Batch expire — mark all found reservations as EXPIRED.
     */
    @Modifying
    @Query(value = "UPDATE reservations SET status = 'EXPIRED' " +
            "WHERE id IN :ids AND status = 'PENDING'", nativeQuery = true)
    int expireReservations(@Param("ids") List<Long> ids);

    /**
     * Count active reservations for an event — for stats endpoint.
     */
    @Query("SELECT COUNT(r) FROM Reservation r WHERE r.event.id = :eventId AND r.status = 'PENDING' " +
            "AND r.expiresAt > CURRENT_TIMESTAMP")
    long countActiveByEventId(@Param("eventId") Long eventId);
}
