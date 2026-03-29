package com.example.ticketing.ticket;

import com.example.ticketing.ticket.strategy.TicketingStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * TicketController — REST endpoint for ticket purchase operations.
 *
 * <p><b>Problem:</b> Multiple concurrency strategies share the same HTTP API.
 * The controller must delegate to the active strategy without coupling to
 * any specific implementation.
 *
 * <p><b>Mechanism:</b> Injects {@link TicketingStrategy} via constructor injection.
 * Spring wires the correct implementation based on configuration
 * ({@code ticketing.strategy} property).
 *
 * <p><b>Design pattern:</b> Controller delegates to Strategy — zero business logic
 * in this class. Only HTTP concerns (validation, status codes, response mapping).
 *
 * <p><b>Scalability:</b> Stateless controller — scales horizontally.
 * Concurrency bottleneck is in the strategy implementation, not here.
 */
@RestController
@RequestMapping("/tickets")
public class TicketController {

    private static final Logger log = LoggerFactory.getLogger(TicketController.class);

    private final TicketingStrategy strategy;

    public TicketController(TicketingStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * POST /tickets/reserve-and-buy — purchase a ticket for a specific seat.
     */
    @PostMapping("/reserve-and-buy")
    public ResponseEntity<TicketResult> reserveAndBuy(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PurchaseRequest request) {
        log.info("Purchase request: eventId={}, seatLabel={}, userId={}, strategy={}, idempotencyKey={}",
                request.eventId(), request.seatLabel(), request.userId(), strategy.strategyName(), idempotencyKey);

        TicketResult result = strategy.reserveAndBuy(
                request.eventId(),
                request.userId(),
                request.seatLabel(),
                idempotencyKey
        );

        return switch (result.status()) {
            case SUCCESS -> ResponseEntity.status(HttpStatus.CREATED).body(result);
            case DUPLICATE -> ResponseEntity.status(HttpStatus.OK).body(result);
            case SEAT_NOT_AVAILABLE -> ResponseEntity.status(HttpStatus.CONFLICT).body(result);
            case EVENT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(result);
            case MAX_RETRIES_EXCEEDED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
            case ERROR -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        };
    }

    /**
     * Request body for ticket purchase.
     */
    public record PurchaseRequest(
            @NotNull Long eventId,
            @NotNull UUID userId,
            @NotBlank String seatLabel
    ) {}
}
