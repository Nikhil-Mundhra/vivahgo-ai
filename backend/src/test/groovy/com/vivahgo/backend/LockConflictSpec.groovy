package com.vivahgo.backend

import com.github.fppt.jedismock.RedisServer
import com.vivahgo.backend.redis.HoldResult
import com.vivahgo.backend.redis.LockConflictException
import com.vivahgo.backend.redis.RedisLockManager
import redis.clients.jedis.JedisPool
import spock.lang.Shared
import spock.lang.Specification

/**
 * Spock specifications validating the Redis Distributed Locking ("Velvet Rope") mechanics
 * described in Issue #1 and Issue #3.
 */
class LockConflictSpec extends Specification {

    @Shared
    RedisServer mockRedisServer

    @Shared
    JedisPool jedisPool

    RedisLockManager lockManager

    def setupSpec() {
        mockRedisServer = RedisServer.newRedisServer().start()
        jedisPool = new JedisPool(mockRedisServer.getHost(), mockRedisServer.getBindPort())
    }

    def cleanupSpec() {
        if (jedisPool != null) {
            jedisPool.close()
        }
        if (mockRedisServer != null) {
            mockRedisServer.stop()
        }
    }

    def setup() {
        lockManager = new RedisLockManager(jedisPool)
        // Flush mock redis for each test
        jedisPool.getResource().withCloseable { jedis ->
            jedis.flushAll()
        }
    }

    def "agent A successfully secures a 15-minute hold on a venue slot"() {
        given: "a venue ID, event date, and wedding plan ID for Couple A"
        def venueId = "venue_grand_plaza_101"
        def eventDate = "2026-11-14"
        def weddingA = "wedding_plan_couple_a"

        when: "Couple A places a temporary hold"
        HoldResult hold = lockManager.acquireHold(venueId, eventDate, weddingA)

        then: "a 15-minute lease is created with correct metadata"
        hold != null
        hold.vendorId() == venueId
        hold.eventDate() == eventDate
        hold.weddingId() == weddingA
        hold.ttlSeconds() == 900
        hold.lockKey() == "lock:vendor:venue_grand_plaza_101:date:2026-11-14"

        and: "the slot is no longer marked available"
        !lockManager.isAvailable(venueId, eventDate)
        lockManager.getCurrentHolder(venueId, eventDate).orElse(null) == weddingA
    }

    def "agent B suffers a LockConflictException when racing for an actively held venue slot"() {
        given: "Couple A has already placed a hold on The Grand Plaza for 2026-11-14"
        def venueId = "venue_grand_plaza_101"
        def eventDate = "2026-11-14"
        def weddingA = "wedding_plan_couple_a"
        def weddingB = "wedding_plan_couple_b"
        lockManager.acquireHold(venueId, eventDate, weddingA)

        when: "Couple B simultaneously attempts to hold the same slot"
        lockManager.acquireHold(venueId, eventDate, weddingB)

        then: "Redis denies the request with a typed LockConflictException"
        def ex = thrown(LockConflictException)
        ex.lockKey == "lock:vendor:venue_grand_plaza_101:date:2026-11-14"
        ex.currentHolder() == weddingA
        ex.remainingTtlSeconds > 0
    }

    def "only the rightful holder can release a temporary hold"() {
        given: "Couple A holds the venue slot"
        def venueId = "venue_grand_plaza_101"
        def eventDate = "2026-11-14"
        def weddingA = "wedding_plan_couple_a"
        def weddingC = "intruder_or_unrelated_wedding"
        lockManager.acquireHold(venueId, eventDate, weddingA)

        when: "an unrelated agent attempts to release the hold"
        def unauthorizedReleased = lockManager.releaseHold(venueId, eventDate, weddingC)

        then: "the release fails and the hold remains intact"
        !unauthorizedReleased
        !lockManager.isAvailable(venueId, eventDate)
        lockManager.getCurrentHolder(venueId, eventDate).orElse(null) == weddingA

        when: "Couple A releases the hold"
        def authorizedReleased = lockManager.releaseHold(venueId, eventDate, weddingA)

        then: "the hold is released and the venue becomes available again"
        authorizedReleased
        lockManager.isAvailable(venueId, eventDate)
    }

    def "after hold expiration or release, another agent can successfully acquire the slot"() {
        given: "Couple A held the slot and then released it"
        def venueId = "venue_grand_plaza_101"
        def eventDate = "2026-11-14"
        def weddingA = "wedding_plan_couple_a"
        def weddingB = "wedding_plan_couple_b"

        lockManager.acquireHold(venueId, eventDate, weddingA)
        lockManager.releaseHold(venueId, eventDate, weddingA)

        when: "Couple B tries to acquire the slot"
        HoldResult holdB = lockManager.acquireHold(venueId, eventDate, weddingB)

        then: "Couple B succeeds"
        holdB != null
        holdB.weddingId() == weddingB
        lockManager.getCurrentHolder(venueId, eventDate).orElse(null) == weddingB
    }
}
