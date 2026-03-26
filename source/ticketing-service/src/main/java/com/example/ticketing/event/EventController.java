package com.example.ticketing.event;

import com.example.ticketing.ticket.TicketRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * EventController — REST endpoints for event management and stats.
 *
 * <p><b>Problem:</b> Benchmarks and observability require real-time visibility
 * into event state: available seats, sold count, and conflict metrics.
 *
 * <p><b>Mechanism:</b> Direct repository queries — no caching, always fresh data.
 * Stats endpoint aggregates data from events + tickets tables.
 *
 * <p><b>Design pattern:</b> RESTful Controller with read-only endpoints.
 *
 * <p><b>Scalability:</b> Read operations — no contention. Can add caching if needed.
 */
@RestController
@RequestMapping("/events")
public class EventController {

    private final EventRepository eventRepository;
    private final TicketRepository ticketRepository;

    public EventController(EventRepository eventRepository, TicketRepository ticketRepository) {
        this.eventRepository = eventRepository;
        this.ticketRepository = ticketRepository;
    }

    /**
     * GET /events/{id} — get event details.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Event> getEvent(@PathVariable Long id) {
        return eventRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /events/{id}/stats — get event statistics for observability.
     * Returns available seats, sold count, and checks consistency.
     */
    @GetMapping("/{id}/stats")
    public ResponseEntity<Map<String, Object>> getStats(@PathVariable Long id) {
        return eventRepository.findById(id)
                .map(event -> {
                    long soldCount = ticketRepository.countByEventId(id);
                    // Kiểm tra consistency: available + sold = total
                    boolean consistent = (event.getAvailableSeats() + soldCount) == event.getTotalSeats();

                    return ResponseEntity.ok(Map.of(
                            "eventId", event.getId(),
                            "eventName", event.getName(),
                            "totalSeats", event.getTotalSeats(),
                            "availableSeats", event.getAvailableSeats(),
                            "soldCount", soldCount,
                            "consistent", consistent
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
