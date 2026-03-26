package com.example.ticketing.reservation;

import com.example.ticketing.event.Event;
import com.example.ticketing.event.EventRepository;
import com.example.ticketing.observability.ConflictMetrics;
import com.example.ticketing.ticket.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * ReservationService — orchestrates the reserve → confirm → cancel lifecycle.
 *
 * <p><b>Problem:</b> Strategies A-D hold DB locks or contention during the entire
 * user checkout flow (30s+). This exhausts connection pools and degrades p95 latency.
 *
 * <p><b>Mechanism:</b> Splits the purchase into two short DB operations:
 * <ol>
 *   <li><b>Reserve</b> (~5ms): pessimistic lock event+seat → decrement counter →
 *       mark seat LOCKED → create reservation with fencing token → release lock</li>
 *   <li><b>Confirm</b> (~5ms): validate fencing token + expiry → mark seat SOLD →
 *       create ticket → release lock</li>
 * </ol>
 * Between reserve and confirm, the user has TTL minutes to complete payment.
 * No DB lock is held during this period.
 *
 * <p><b>Design pattern:</b> Service layer encapsulating transactional boundaries.
 * Each method is a separate @Transactional — reserve and confirm are independent transactions.
 *
 * <p><b>Scalability:</b> Contention window reduced from 30s to ~5ms per operation.
 * 200 concurrent users: p95 for reserve ≈ 950ms (vs 95 MINUTES for Pessimistic full-flow).
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final String STRATEGY_NAME = "reservation";

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final ReservationRepository reservationRepository;
    private final FencingTokenService fencingTokenService;
    private final ConflictMetrics metrics;
    private final long ttlMinutes;

    public ReservationService(EventRepository eventRepository,
                               SeatRepository seatRepository,
                               TicketRepository ticketRepository,
                               ReservationRepository reservationRepository,
                               FencingTokenService fencingTokenService,
                               ConflictMetrics metrics,
                               @Value("${ticketing.reservation.ttl-minutes:10}") long ttlMinutes) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.reservationRepository = reservationRepository;
        this.fencingTokenService = fencingTokenService;
        this.metrics = metrics;
        this.ttlMinutes = ttlMinutes;
    }

    /**
     * Reserve a seat — short pessimistic lock, returns fencing token.
     * User has TTL minutes to confirm with the returned token.
     */
    @Transactional
    public ReservationResult reserve(Long eventId, UUID userId, String seatLabel) {
        try {
            // Bước 1: Lock event row — serialize reserve operations (ngắn: ~5ms)
            Event event = eventRepository.findByIdForUpdate(eventId).orElse(null);
            if (event == null) {
                metrics.recordFailure(STRATEGY_NAME);
                return ReservationResult.eventNotFound(eventId);
            }

            // Bước 2: Check available seats — đáng tin cậy vì đang hold lock
            if (event.getAvailableSeats() <= 0) {
                metrics.recordFailure(STRATEGY_NAME);
                return ReservationResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 3: Lock seat row — consistent order: event → seat
            Seat seat = seatRepository.findByEventIdAndSeatLabelForUpdate(eventId, seatLabel)
                    .orElse(null);
            if (seat == null) {
                metrics.recordFailure(STRATEGY_NAME);
                return ReservationResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 4: Check seat — phải AVAILABLE (không LOCKED, không SOLD)
            if (!seat.isAvailable()) {
                metrics.recordFailure(STRATEGY_NAME);
                return ReservationResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 5: Decrement counter
            int updated = eventRepository.decrementAvailableSeats(eventId);
            if (updated == 0) {
                metrics.recordFailure(STRATEGY_NAME);
                return ReservationResult.seatNotAvailable(eventId, seatLabel);
            }

            // Bước 6: Lock seat — AVAILABLE → LOCKED
            Instant expiresAt = Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES);
            int seatUpdated = seatRepository.lockForReservation(seat.getId(), userId, expiresAt);
            if (seatUpdated == 0) {
                log.warn("Reserve: could not lock seat {} — race condition", seatLabel);
                metrics.recordConflict(STRATEGY_NAME);
                metrics.recordFailure(STRATEGY_NAME);
                return ReservationResult.conflict(eventId, seatLabel, "Could not lock seat");
            }

            // Bước 7: Create reservation with fencing token
            UUID fencingToken = fencingTokenService.generateToken();
            Reservation reservation = new Reservation();
            reservation.setEvent(event);
            reservation.setSeat(seat);
            reservation.setUserId(userId);
            reservation.setFencingToken(fencingToken);
            reservation.setStatus("PENDING");
            reservation.setExpiresAt(expiresAt);
            reservation.setCreatedAt(Instant.now());
            reservation = reservationRepository.save(reservation);

            // COMMIT → release ALL locks — user có ttlMinutes để confirm
            log.info("Reserve: reservation {} created for event={}, seat={}, user={}, " +
                    "token={}, expires={}",
                    reservation.getId(), eventId, seatLabel, userId, fencingToken, expiresAt);
            return ReservationResult.reserved(reservation.getId(), fencingToken, expiresAt);

        } catch (DataIntegrityViolationException e) {
            log.warn("Reserve: DataIntegrityViolation for event={}, seat={}: {}",
                    eventId, seatLabel, e.getMostSpecificCause().getMessage());
            metrics.recordConflict(STRATEGY_NAME);
            metrics.recordFailure(STRATEGY_NAME);
            return ReservationResult.conflict(eventId, seatLabel,
                    "DB constraint: " + e.getMostSpecificCause().getMessage());
        }
    }

    /**
     * Confirm reservation — validate fencing token + expiry, create ticket.
     * Short pessimistic lock (~5ms).
     */
    @Transactional
    public ReservationResult confirm(Long reservationId, UUID fencingToken) {
        try {
            // Bước 1: Atomic status transition — PENDING → CONFIRMED
            // WHERE: id match + token match + status PENDING + not expired
            int confirmed = reservationRepository.confirmReservation(reservationId, fencingToken);
            if (confirmed == 0) {
                // Reservation expired, wrong token, hoặc already confirmed/cancelled
                log.warn("Confirm: failed for reservation={}, token={} — expired or invalid",
                        reservationId, fencingToken);
                metrics.recordFailure(STRATEGY_NAME);
                return ReservationResult.confirmFailed(reservationId,
                        "Reservation expired, invalid token, or already processed");
            }

            // Bước 2: Đọc reservation để lấy thông tin seat/event
            Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
            if (reservation == null) {
                metrics.recordFailure(STRATEGY_NAME);
                return ReservationResult.confirmFailed(reservationId, "Reservation not found after confirm");
            }

            // Bước 3: Mark seat as SOLD — LOCKED → SOLD
            int seatUpdated = seatRepository.markLockedAsSold(reservation.getSeat().getId());
            if (seatUpdated == 0) {
                log.error("Confirm: seat {} could not be marked SOLD after reservation confirmed — "
                        + "THIS SHOULD NOT HAPPEN", reservation.getSeat().getId());
                metrics.recordOversell(STRATEGY_NAME);
                metrics.recordFailure(STRATEGY_NAME);
                return ReservationResult.confirmFailed(reservationId, "Seat state inconsistency");
            }

            // Bước 4: Create ticket
            Ticket ticket = new Ticket(
                    reservation.getEvent(),
                    reservation.getSeat(),
                    reservation.getUserId(),
                    null
            );
            ticket = ticketRepository.save(ticket);

            metrics.recordSuccess(STRATEGY_NAME);
            log.info("Confirm: ticket {} created from reservation={}, event={}, seat={}, user={}",
                    ticket.getId(), reservationId,
                    reservation.getEvent().getId(),
                    reservation.getSeat().getSeatLabel(),
                    reservation.getUserId());
            return ReservationResult.confirmed(reservationId, ticket.getId());

        } catch (DataIntegrityViolationException e) {
            log.warn("Confirm: DataIntegrityViolation for reservation={}: {}",
                    reservationId, e.getMostSpecificCause().getMessage());
            metrics.recordConflict(STRATEGY_NAME);
            metrics.recordFailure(STRATEGY_NAME);
            return ReservationResult.confirmFailed(reservationId,
                    "DB constraint: " + e.getMostSpecificCause().getMessage());
        }
    }

    /**
     * Cancel reservation — voluntary cancel by user, releases seat.
     */
    @Transactional
    public ReservationResult cancel(Long reservationId, UUID fencingToken) {
        // Bước 1: Atomic cancel — PENDING → CANCELLED
        int cancelled = reservationRepository.cancelReservation(reservationId, fencingToken);
        if (cancelled == 0) {
            metrics.recordFailure(STRATEGY_NAME);
            return ReservationResult.cancelFailed(reservationId,
                    "Reservation not found, wrong token, or already processed");
        }

        // Bước 2: Đọc reservation để release seat
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            return ReservationResult.cancelFailed(reservationId, "Reservation not found after cancel");
        }

        // Bước 3: Release seat — LOCKED → AVAILABLE
        seatRepository.releaseSeat(reservation.getSeat().getId());

        // Bước 4: Increment available_seats
        eventRepository.incrementAvailableSeats(reservation.getEvent().getId());

        log.info("Cancel: reservation {} cancelled, seat {} released for event={}",
                reservationId, reservation.getSeat().getSeatLabel(),
                reservation.getEvent().getId());
        return ReservationResult.cancelled(reservationId);
    }
}
