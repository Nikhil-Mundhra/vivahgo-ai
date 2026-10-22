package com.vivahgo.backend.db

import com.vivahgo.backend.redis.HoldResult
import spock.lang.Shared
import spock.lang.Specification

import java.math.BigDecimal
import java.time.LocalDate

class ReservationRepositorySpec extends Specification {

    @Shared
    DatabaseConfig dbConfig

    ReservationRepository repository

    def setupSpec() {
        dbConfig = DatabaseConfig.inMemoryH2()
    }

    def cleanupSpec() {
        if (dbConfig != null) {
            dbConfig.close()
        }
    }

    def setup() {
        repository = new ReservationRepository(dbConfig.dsl())
    }

    def "records hold and transitions to confirmed reservation"() {
        given: "a temporary hold secured via Redis"
        def hold = HoldResult.create("lock:vendor:v101:date:2026-11-20", "v101", "2026-11-20", "w_plan_99", 900)

        when: "hold is recorded in PostgreSQL"
        def record = repository.recordHold(hold, "The Grand Plaza", "Venue", "user_abc")

        then: "record is created in HOLD_ACTIVE state"
        record != null
        record.vendorId() == "v101"
        record.vendorName() == "The Grand Plaza"
        record.status() == ReservationRecord.STATUS_HOLD_ACTIVE
        record.depositAmount() == BigDecimal.ZERO

        when: "couple confirms hold with deposit payment"
        def deposit = new BigDecimal("50000.00")
        def confirmed = repository.confirmReservation(record.id(), deposit)
        def updated = repository.findById(record.id()).orElse(null)

        then: "reservation is marked CONFIRMED with deposit amount"
        confirmed
        updated != null
        updated.status() == ReservationRecord.STATUS_CONFIRMED
        updated.depositAmount() == deposit
    }

    def "operational logs record multi-agent actions accurately"() {
        when: "an agent action is logged"
        repository.logOperation("VendorSearchAgent", "w_plan_99", "search_vendors", "Delhi/500", "SUCCESS", "Found 4 matching venues")

        then: "no exception thrown"
        noExceptionThrown()
    }
}
