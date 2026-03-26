package com.example.ticketing.ticket;

import com.example.ticketing.event.Event;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Seat — JPA entity representing an individual seat within an event.
 *
 * <p><b>Problem:</b> Seats are the fine-grained resource that must not be double-booked.
 * The status field implements a state machine: AVAILABLE → LOCKED → SOLD,
 * or AVAILABLE → SOLD (direct buy for strategies A-D).
 *
 * <p><b>Mechanism:</b> Status transitions are enforced by CHECK constraint at DB level
 * and validated in application code. locked_by + locked_until support the
 * Reservation+Fencing strategy (2.E).
 *
 * <p><b>Design pattern:</b> JPA Entity with state machine semantics.
 *
 * <p><b>Scalability:</b> Individual seat rows allow fine-grained locking
 * (lock only the target seat, not the entire event).
 */
@Entity
@Table(name = "seats")
public class Seat {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_LOCKED = "LOCKED";
    public static final String STATUS_SOLD = "SOLD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "event_id", insertable = false, updatable = false)
    private Long eventId;

    @Column(name = "seat_label", nullable = false)
    private String seatLabel;

    @Column(nullable = false)
    private String status;

    @Column(name = "locked_by")
    private UUID lockedBy;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    protected Seat() {}

    public Seat(Event event, String seatLabel) {
        this.event = event;
        this.eventId = event.getId();
        this.seatLabel = seatLabel;
        this.status = STATUS_AVAILABLE;
    }

    public Long getId() { return id; }
    public Event getEvent() { return event; }
    public Long getEventId() { return eventId; }
    public String getSeatLabel() { return seatLabel; }
    public String getStatus() { return status; }
    public UUID getLockedBy() { return lockedBy; }
    public Instant getLockedUntil() { return lockedUntil; }

    public void setStatus(String status) { this.status = status; }
    public void setLockedBy(UUID lockedBy) { this.lockedBy = lockedBy; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }

    public boolean isAvailable() { return STATUS_AVAILABLE.equals(status); }
    public boolean isSold() { return STATUS_SOLD.equals(status); }
}
