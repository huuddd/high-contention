package com.example.ticketing.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * EventRepository — data access for events with strategy-specific query methods.
 *
 * <p><b>Problem:</b> Different concurrency strategies require different SQL patterns
 * to read/update event data: plain SELECT (naive), SELECT FOR UPDATE (pessimistic),
 * conditional UPDATE (OCC).
 *
 * <p><b>Mechanism:</b> Spring Data JPA with native queries for strategy-specific
 * operations. Each query method maps to a specific concurrency pattern.
 *
 * <p><b>Design pattern:</b> Repository Pattern — abstracts data access behind
 * a clean interface. Strategy implementations call the appropriate method.
 *
 * <p><b>Scalability:</b> Native queries allow fine-grained control over SQL,
 * essential for lock hints and isolation-specific patterns.
 */
@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * Plain SELECT — used by Naive strategy (2.A).
     * No locking, no version check — vulnerable to race conditions.
     */
    Optional<Event> findById(Long id);

    /**
     * SELECT FOR UPDATE — used by Pessimistic Locking strategy (2.B).
     * Acquires exclusive row lock; competing transactions block until lock released.
     */
    @Query(value = "SELECT * FROM events WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Event> findByIdForUpdate(@Param("id") Long id);

    /**
     * Decrement available_seats with OCC version check — used by OCC strategy (2.C).
     * Returns number of affected rows: 1 = success, 0 = version conflict.
     */
    @Modifying
    @Query(value = "UPDATE events SET available_seats = available_seats - 1, " +
            "version = version + 1 WHERE id = :id AND version = :version AND available_seats > 0",
            nativeQuery = true)
    int decrementAvailableSeatsWithVersion(@Param("id") Long id, @Param("version") Long version);

    /**
     * Decrement available_seats without version check — used by Naive (2.A) and Pessimistic (2.B).
     */
    @Modifying
    @Query(value = "UPDATE events SET available_seats = available_seats - 1 WHERE id = :id AND available_seats > 0",
            nativeQuery = true)
    int decrementAvailableSeats(@Param("id") Long id);
}
