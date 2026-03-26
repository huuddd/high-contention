package com.example.ticketing.event;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Event — JPA entity representing a ticketed event with seat inventory.
 *
 * <p><b>Problem:</b> Concurrent ticket purchases must decrement available_seats
 * atomically. Without proper concurrency control, multiple transactions can
 * read the same value and both decrement — causing oversell.
 *
 * <p><b>Mechanism:</b> The {@code version} field supports OCC (task 2.C) via
 * conditional UPDATE: {@code WHERE id = ? AND version = ?}. CHECK constraints
 * at DB level ensure available_seats never goes negative.
 *
 * <p><b>Design pattern:</b> JPA Entity with optimistic locking support.
 * Note: we do NOT use {@code @Version} annotation because we manage version
 * manually in each strategy for explicit control and learning purposes.
 *
 * <p><b>Scalability:</b> Single counter (available_seats) is the contention
 * bottleneck. All 6 strategies differ in how they serialize access to this counter.
 */
@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "total_seats", nullable = false)
    private Integer totalSeats;

    @Column(name = "available_seats", nullable = false)
    private Integer availableSeats;

    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Event() {}

    public Event(String name, int totalSeats) {
        this.name = name;
        this.totalSeats = totalSeats;
        this.availableSeats = totalSeats;
        this.version = 0L;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Integer getTotalSeats() { return totalSeats; }
    public Integer getAvailableSeats() { return availableSeats; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }

    public void setAvailableSeats(Integer availableSeats) { this.availableSeats = availableSeats; }
    public void setVersion(Long version) { this.version = version; }
}
