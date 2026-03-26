package com.example.ticketing.queue;

import java.io.Serializable;
import java.util.UUID;

/**
 * QueueTicketRequest — serializable DTO for enqueued ticket purchase requests.
 *
 * <p><b>Problem:</b> Redis stores strings. The ticket request must be serialized
 * to JSON for Redis LPUSH and deserialized on BRPOP by the worker.
 *
 * <p><b>Mechanism:</b> Java record with all fields needed to process a ticket purchase.
 * The requestId links the enqueued request to its CompletableFuture in the pending map.
 *
 * <p><b>Design pattern:</b> DTO (Data Transfer Object) — carries data between
 * the HTTP thread (producer) and the worker thread (consumer) via Redis.
 *
 * <p><b>Scalability:</b> Small payload (~200 bytes JSON). Redis LPUSH handles
 * millions of these per second.
 */
public record QueueTicketRequest(
        String requestId,
        Long eventId,
        UUID userId,
        String seatLabel
) implements Serializable {
}
