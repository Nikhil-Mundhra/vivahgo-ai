package com.vivahgo.backend.redis;

import java.time.Instant;

/**
 * Represents a successful 15-minute lease hold on a vendor for a specific wedding date.
 */
public record HoldResult(
        String lockKey,
        String vendorId,
        String eventDate,
        String weddingId,
        long ttlSeconds,
        Instant acquiredAt,
        Instant expiresAt
) {
    public static HoldResult create(String lockKey, String vendorId, String eventDate, String weddingId, long ttlSeconds) {
        Instant now = Instant.now();
        return new HoldResult(
                lockKey,
                vendorId,
                eventDate,
                weddingId,
                ttlSeconds,
                now,
                now.plusSeconds(ttlSeconds)
        );
    }
}
