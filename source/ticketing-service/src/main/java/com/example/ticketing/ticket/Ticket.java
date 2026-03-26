package com.example.ticketing.ticket;

import com.example.ticketing.event.Event;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Ticket — JPA entity representing a purchased ticket (proof of ownership).
 *
 * <p><b>Problem:</b> A ticket is the final artifact of a successful purchase.
 * The UNIQUE constraint on (event_id, seat_id) is the DB-level guard against
 * double-booking — even if application logic has bugs, the DB will reject duplicates.
 *
 * <p><b>Mechanism:</b> INSERT into tickets table is the "point of no return" —
 * once committed, the seat is sold. All strategies must ensure this INSERT
 * happens exactly once per (event, seat) pair.
 *
 * <p><b>Design pattern:</b> JPA Entity — immutable after creation (no setters
 * for core fields).
 *
 * <p><b>Scalability:</b> Ticket creation is the write bottleneck. The UNIQUE
 * index adds a small overhead per INSERT but provides critical safety.
 */
@Entity
@Table(name = "tickets")
public class Ticket {

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

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Ticket() {}

    public Ticket(Event event, Seat seat, UUID userId, String idempotencyKey) {
        this.event = event;
        this.eventId = event.getId();
        this.seat = seat;
        this.seatId = seat.getId();
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Event getEvent() { return event; }
    public Long getEventId() { return eventId; }
    public Seat getSeat() { return seat; }
    public Long getSeatId() { return seatId; }
    public UUID getUserId() { return userId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}
