package com.example.ticketing.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * ReservationController — REST endpoints for the reserve → confirm → cancel lifecycle.
 *
 * <p><b>Problem:</b> The reservation strategy (2.E) splits ticket purchase into two steps:
 * reserve (hold seat with TTL) and confirm (finalize with fencing token). This controller
 * exposes those steps as separate HTTP endpoints.
 *
 * <p><b>Mechanism:</b> Delegates to {@link ReservationService}. Maps ReservationResult
 * status to appropriate HTTP status codes. Validates fencing tokens on confirm/cancel.
 *
 * <p><b>Design pattern:</b> Thin controller — zero business logic, only HTTP concerns.
 *
 * <p><b>Scalability:</b> Stateless controller. Reserve and confirm are independent
 * short transactions (~5ms each), so this endpoint handles high concurrency well.
 */
@RestController
@RequestMapping("/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /**
     * POST /reservations — reserve a seat, get fencing token + expiry.
     */
    @PostMapping
    public ResponseEntity<ReservationResult> reserve(@Valid @RequestBody ReserveRequest request) {
        ReservationResult result = reservationService.reserve(
                request.eventId(), request.userId(), request.seatLabel());

        return switch (result.status()) {
            case RESERVED -> ResponseEntity.status(HttpStatus.CREATED).body(result);
            case SEAT_NOT_AVAILABLE -> ResponseEntity.status(HttpStatus.CONFLICT).body(result);
            case EVENT_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(result);
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        };
    }

    /**
     * POST /reservations/{id}/confirm — confirm reservation with fencing token.
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<ReservationResult> confirm(@PathVariable Long id,
                                                      @Valid @RequestBody ConfirmRequest request) {
        ReservationResult result = reservationService.confirm(id, request.fencingToken());

        return switch (result.status()) {
            case CONFIRMED -> ResponseEntity.ok(result);
            case CONFIRM_FAILED -> ResponseEntity.status(HttpStatus.CONFLICT).body(result);
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        };
    }

    /**
     * POST /reservations/{id}/cancel — voluntarily cancel reservation.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ReservationResult> cancel(@PathVariable Long id,
                                                     @Valid @RequestBody CancelRequest request) {
        ReservationResult result = reservationService.cancel(id, request.fencingToken());

        return switch (result.status()) {
            case CANCELLED -> ResponseEntity.ok(result);
            case CANCEL_FAILED -> ResponseEntity.status(HttpStatus.CONFLICT).body(result);
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        };
    }

    // --- Request DTOs ---

    public record ReserveRequest(
            @NotNull Long eventId,
            @NotNull UUID userId,
            @NotBlank String seatLabel
    ) {}

    public record ConfirmRequest(
            @NotNull UUID fencingToken
    ) {}

    public record CancelRequest(
            @NotNull UUID fencingToken
    ) {}
}
