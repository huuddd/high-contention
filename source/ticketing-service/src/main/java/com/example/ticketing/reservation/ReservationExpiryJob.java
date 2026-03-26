package com.example.ticketing.reservation;

import com.example.ticketing.event.EventRepository;
import com.example.ticketing.ticket.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ReservationExpiryJob — background job that cleans up expired reservations.
 *
 * <p><b>Problem:</b> Users may reserve seats but never confirm (abandon checkout,
 * lose connection, etc.). Without cleanup, seats stay LOCKED forever — "ghost reservations"
 * that reduce available inventory and cause lost revenue.
 *
 * <p><b>Mechanism:</b> Runs every 30 seconds via {@code @Scheduled}. Finds PENDING
 * reservations where {@code expires_at < NOW()}, marks them EXPIRED, releases the
 * locked seats back to AVAILABLE, and increments the event's available_seats counter.
 *
 * <p><b>Design pattern:</b> Scheduled job with batch processing. Uses
 * {@code FOR UPDATE SKIP LOCKED} to avoid blocking concurrent reserve/confirm
 * operations on the same rows.
 *
 * <p><b>Scalability:</b> Batch size limits transaction duration. SKIP LOCKED ensures
 * multiple instances can run concurrently without deadlocks (in a multi-instance deployment).
 */
@Component
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);
    private static final int BATCH_SIZE = 100;

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;

    public ReservationExpiryJob(ReservationRepository reservationRepository,
                                 SeatRepository seatRepository,
                                 EventRepository eventRepository) {
        this.reservationRepository = reservationRepository;
        this.seatRepository = seatRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * Expire stale reservations every 30 seconds.
     * Uses FOR UPDATE SKIP LOCKED to avoid contention with user operations.
     */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void expireReservations() {
        List<Reservation> expired = reservationRepository.findExpiredReservations(BATCH_SIZE);

        if (expired.isEmpty()) {
            return;
        }

        log.info("Expiry job: found {} expired reservations", expired.size());

        // Bước 1: Mark reservations as EXPIRED
        List<Long> ids = expired.stream().map(Reservation::getId).toList();
        int expiredCount = reservationRepository.expireReservations(ids);

        // Bước 2: Release seats — LOCKED → AVAILABLE
        int seatsReleased = 0;
        for (Reservation reservation : expired) {
            int released = seatRepository.releaseSeat(reservation.getSeat().getId());
            if (released > 0) {
                seatsReleased++;
                // Bước 3: Increment available_seats counter
                eventRepository.incrementAvailableSeats(reservation.getEvent().getId());
            }
        }

        log.info("Expiry job: expired {} reservations, released {} seats",
                expiredCount, seatsReleased);
    }
}
