package com.example.ticketing.reservation;

import com.example.ticketing.event.Event;
import com.example.ticketing.ticket.Seat;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Reservation — JPA entity for temporary seat holds with TTL and fencing token.
 *
 * <p><b>Problem:</b> In UX-oriented flows, users need time to complete payment
 * after selecting a seat. Without a reservation mechanism, the seat could be
 * taken by another user during payment.
 *
 * <p><b>Mechanism:</b> A reservation holds a seat for a limited time (TTL).
 * The fencing token ensures that only the original reservation holder can
 * confirm the purchase — preventing stale confirmations after TTL expiry.
 *
 * <p><b>Design pattern:</b> State Machine — PENDING → CONFIRMED | EXPIRED | CANCELLED.
 * Background job transitions PENDING → EXPIRED after TTL.
 *
 * <p><b>Scalability:</b> Reservations decouple the contention window (reserve = fast)
 * from the payment flow (confirm = slow). Lock is held only during reserve, not during payment.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "event_id", insertable = false, updatable = false)
    private Long eventId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Column(name = "seat_id", insertable = false, updatable = false)
    private Long seatId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "fencing_token", nullable = false)
    private UUID fencingToken;

    @Column(nullable = false)
    private String status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Reservation() {}

    public Reservation(Event event, Seat seat, UUID userId, UUID fencingToken, Instant expiresAt) {
        this.event = event;
        this.eventId = event.getId();
        this.seat = seat;
        this.seatId = seat.getId();
        this.userId = userId;
        this.fencingToken = fencingToken;
        this.status = STATUS_PENDING;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Event getEvent() { return event; }
    public Long getEventId() { return eventId; }
    public Seat getSeat() { return seat; }
    public Long getSeatId() { return seatId; }
    public UUID getUserId() { return userId; }
    public UUID getFencingToken() { return fencingToken; }
    public String getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setEvent(Event event) { this.event = event; this.eventId = event != null ? event.getId() : null; }
    public void setSeat(Seat seat) { this.seat = seat; this.seatId = seat != null ? seat.getId() : null; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public void setFencingToken(UUID fencingToken) { this.fencingToken = fencingToken; }
    public void setStatus(String status) { this.status = status; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public boolean isPending() { return STATUS_PENDING.equals(status); }
    public boolean isExpired() { return STATUS_EXPIRED.equals(status) || Instant.now().isAfter(expiresAt); }
}
