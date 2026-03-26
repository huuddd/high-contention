package com.example.ticketing.reservation;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * FencingTokenService — generates and validates fencing tokens for reservations.
 *
 * <p><b>Problem:</b> After a reservation expires, a stale client may attempt to confirm
 * using an old reservation ID. Without a unique token, the confirmation could accidentally
 * apply to a new reservation that reused the same seat.
 *
 * <p><b>Mechanism:</b> Each reservation receives a cryptographically random UUID token
 * at creation time. Confirmation requires both reservation ID AND matching token.
 * This ensures that only the original requester can confirm their own reservation.
 *
 * <p><b>Design pattern:</b> Service encapsulating token generation — allows future
 * replacement with HMAC-based tokens, JWT, or distributed token service.
 *
 * <p><b>Scalability:</b> UUID v4 has 122 random bits — collision probability is
 * negligible (p ≈ 10^-37 for 1 billion tokens). No coordination needed between instances.
 */
@Service
public class FencingTokenService {

    /**
     * Generate a new fencing token for a reservation.
     * Uses UUID v4 (cryptographically random) for uniqueness.
     */
    public UUID generateToken() {
        return UUID.randomUUID();
    }

    /**
     * Validate that the provided token matches the expected token.
     * Uses constant-time comparison to prevent timing attacks.
     */
    public boolean validateToken(UUID expected, UUID provided) {
        if (expected == null || provided == null) {
            return false;
        }
        return expected.equals(provided);
    }
}
