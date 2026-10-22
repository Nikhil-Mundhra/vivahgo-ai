package com.vivahgo.backend.db;

import com.vivahgo.backend.redis.HoldResult;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Type-safe Repository utilizing jOOQ DSLContext for PostgreSQL operations.
 */
public class ReservationRepository {
    private static final Logger log = LoggerFactory.getLogger(ReservationRepository.class);

    private final DSLContext dsl;

    public ReservationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public ReservationRecord recordHold(HoldResult hold, String vendorName, String vendorCategory, String userId) {
        UUID id = UUID.randomUUID();
        LocalDate eventDate = LocalDate.parse(hold.eventDate());
        Timestamp expiresAt = Timestamp.from(hold.expiresAt());

        dsl.insertInto(DSL.table("vendor_reservations"))
                .columns(
                        DSL.field("id"),
                        DSL.field("vendor_id"),
                        DSL.field("vendor_name"),
                        DSL.field("vendor_category"),
                        DSL.field("wedding_id"),
                        DSL.field("user_id"),
                        DSL.field("event_date"),
                        DSL.field("status"),
                        DSL.field("hold_expires_at"),
                        DSL.field("deposit_amount"),
                        DSL.field("currency")
                )
                .values(
                        id,
                        hold.vendorId(),
                        vendorName,
                        vendorCategory,
                        hold.weddingId(),
                        userId,
                        java.sql.Date.valueOf(eventDate),
                        ReservationRecord.STATUS_HOLD_ACTIVE,
                        expiresAt,
                        BigDecimal.ZERO,
                        "INR"
                )
                .execute();

        log.info("Recorded temporary hold in PostgreSQL: id={}, vendor={}, date={}", id, hold.vendorId(), eventDate);
        return findById(id).orElseThrow();
    }

    public Optional<ReservationRecord> findById(UUID id) {
        Record record = dsl.select()
                .from(DSL.table("vendor_reservations"))
                .where(DSL.field("id").eq(id))
                .fetchOne();

        return Optional.ofNullable(record).map(this::mapRecord);
    }

    public Optional<ReservationRecord> findActiveHold(String vendorId, LocalDate eventDate) {
        Record record = dsl.select()
                .from(DSL.table("vendor_reservations"))
                .where(DSL.field("vendor_id").eq(vendorId))
                .and(DSL.field("event_date").eq(java.sql.Date.valueOf(eventDate)))
                .and(DSL.field("status").eq(ReservationRecord.STATUS_HOLD_ACTIVE))
                .fetchOne();

        return Optional.ofNullable(record).map(this::mapRecord);
    }

    public List<ReservationRecord> findByWeddingId(String weddingId) {
        return dsl.select()
                .from(DSL.table("vendor_reservations"))
                .where(DSL.field("wedding_id").eq(weddingId))
                .fetch()
                .map(this::mapRecord);
    }

    public boolean confirmReservation(UUID reservationId, BigDecimal depositAmount) {
        int updated = dsl.update(DSL.table("vendor_reservations"))
                .set(DSL.field("status"), ReservationRecord.STATUS_CONFIRMED)
                .set(DSL.field("deposit_amount"), depositAmount)
                .set(DSL.field("updated_at"), DSL.currentTimestamp())
                .where(DSL.field("id").eq(reservationId))
                .and(DSL.field("status").eq(ReservationRecord.STATUS_HOLD_ACTIVE))
                .execute();

        return updated > 0;
    }

    public boolean releaseReservation(UUID reservationId) {
        int updated = dsl.update(DSL.table("vendor_reservations"))
                .set(DSL.field("status"), ReservationRecord.STATUS_RELEASED)
                .set(DSL.field("updated_at"), DSL.currentTimestamp())
                .where(DSL.field("id").eq(reservationId))
                .execute();

        return updated > 0;
    }

    public void logOperation(String agentId, String weddingId, String action, String targetResource, String status, String details) {
        dsl.insertInto(DSL.table("operational_logs"))
                .columns(
                        DSL.field("id"),
                        DSL.field("agent_id"),
                        DSL.field("wedding_id"),
                        DSL.field("action"),
                        DSL.field("target_resource"),
                        DSL.field("status"),
                        DSL.field("details")
                )
                .values(
                        UUID.randomUUID(),
                        agentId,
                        weddingId,
                        action,
                        targetResource,
                        status,
                        details
                )
                .execute();
    }

    private ReservationRecord mapRecord(Record r) {
        return new ReservationRecord(
                r.get("id", UUID.class),
                r.get("vendor_id", String.class),
                r.get("vendor_name", String.class),
                r.get("vendor_category", String.class),
                r.get("wedding_id", String.class),
                r.get("user_id", String.class),
                r.get("event_date", java.sql.Date.class).toLocalDate(),
                r.get("status", String.class),
                r.get("hold_expires_at", Timestamp.class) != null ? r.get("hold_expires_at", Timestamp.class).toInstant() : null,
                r.get("deposit_amount", BigDecimal.class),
                r.get("currency", String.class),
                r.get("metadata", String.class),
                r.get("created_at", Timestamp.class) != null ? r.get("created_at", Timestamp.class).toInstant() : null
        );
    }
}
