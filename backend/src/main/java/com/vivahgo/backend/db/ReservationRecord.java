package com.vivahgo.backend.db;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Immutable domain record representing a reservation entry in the ACID PostgreSQL vault.
 */
public record ReservationRecord(
        UUID id,
        String vendorId,
        String vendorName,
        String vendorCategory,
        String weddingId,
        String userId,
        LocalDate eventDate,
        String status,
        Instant holdExpiresAt,
        BigDecimal depositAmount,
        String currency,
        String metadata,
        Instant createdAt
) {
    public static final String STATUS_HOLD_ACTIVE = "HOLD_ACTIVE";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_RELEASED = "RELEASED";
    public static final String STATUS_EXPIRED = "EXPIRED";
}
