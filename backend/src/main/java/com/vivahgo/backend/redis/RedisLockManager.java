package com.vivahgo.backend.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

/**
 * Distributed Lock Manager using Redis atomic primitives.
 *
 * Implements the "Velvet Rope" architecture from Issue #1:
 * - 15-minute default lease (TTL) prevents the "Dead Agent" problem.
 * - Atomic SET NX EX guarantees only one agent secures the hold.
 * - Throws LockConflictException if an asset is already contested.
 * - Safe release via Lua script ensures an agent only releases its own hold.
 */
public class RedisLockManager {
    private static final Logger log = LoggerFactory.getLogger(RedisLockManager.class);
    public static final long DEFAULT_HOLD_TTL_SECONDS = 15 * 60; // 15 minutes

    // Atomic release Lua script: deletes the key only if the value matches the holder ID
    private static final String RELEASE_LOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    private final JedisPool jedisPool;

    public RedisLockManager(JedisPool jedisPool) {
        this.jedisPool = Objects.requireNonNull(jedisPool, "JedisPool must not be null");
    }

    public static String buildLockKey(String vendorId, String eventDate) {
        Objects.requireNonNull(vendorId, "vendorId is required");
        Objects.requireNonNull(eventDate, "eventDate is required");
        return String.format("lock:vendor:%s:date:%s", vendorId.trim(), eventDate.trim());
    }

    /**
     * Attempts to acquire a 15-minute temporary hold on a vendor for a specific date.
     *
     * @param vendorId The vendor ID
     * @param eventDate The event date in YYYY-MM-DD format
     * @param weddingId The identifier of the wedding/session claiming the hold
     * @return HoldResult if acquired
     * @throws LockConflictException if another session holds the lock
     */
    public HoldResult acquireHold(String vendorId, String eventDate, String weddingId) {
        return acquireHold(vendorId, eventDate, weddingId, DEFAULT_HOLD_TTL_SECONDS);
    }

    /**
     * Attempts to acquire a temporary hold with custom TTL.
     */
    public HoldResult acquireHold(String vendorId, String eventDate, String weddingId, long ttlSeconds) {
        String lockKey = buildLockKey(vendorId, eventDate);
        SetParams params = SetParams.setParams().nx().ex(ttlSeconds);

        try (Jedis jedis = jedisPool.getResource()) {
            String result = jedis.set(lockKey, weddingId, params);

            if ("OK".equalsIgnoreCase(result)) {
                log.info("Hold acquired: key={}, holder={}, ttl={}s", lockKey, weddingId, ttlSeconds);
                return HoldResult.create(lockKey, vendorId, eventDate, weddingId, ttlSeconds);
            }

            // Lock conflict! Fetch who holds it and remaining TTL
            String currentHolder = jedis.get(lockKey);
            long remainingTtl = jedis.ttl(lockKey);
            log.warn("Lock collision on key={}, heldBy={}, remainingTtl={}s, contestedBy={}",
                    lockKey, currentHolder, remainingTtl, weddingId);

            throw new LockConflictException(lockKey, currentHolder != null ? currentHolder : "unknown", Math.max(0, remainingTtl));
        }
    }

    /**
     * Atomically releases a hold, ensuring only the owner who acquired it can release it.
     */
    public boolean releaseHold(String vendorId, String eventDate, String weddingId) {
        String lockKey = buildLockKey(vendorId, eventDate);

        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(
                    RELEASE_LOCK_SCRIPT,
                    Collections.singletonList(lockKey),
                    Collections.singletonList(weddingId)
            );
            boolean released = Long.valueOf(1L).equals(result);
            if (released) {
                log.info("Hold safely released: key={}, holder={}", lockKey, weddingId);
            } else {
                log.warn("Failed to release hold: key={}, not held by requested holder={}", lockKey, weddingId);
            }
            return released;
        }
    }

    /**
     * Checks if a vendor slot is available (unlocked).
     */
    public boolean isAvailable(String vendorId, String eventDate) {
        String lockKey = buildLockKey(vendorId, eventDate);
        try (Jedis jedis = jedisPool.getResource()) {
            return !jedis.exists(lockKey);
        }
    }

    /**
     * Reads current hold details if active.
     */
    public Optional<String> getCurrentHolder(String vendorId, String eventDate) {
        String lockKey = buildLockKey(vendorId, eventDate);
        try (Jedis jedis = jedisPool.getResource()) {
            String holder = jedis.get(lockKey);
            return Optional.ofNullable(holder);
        }
    }

    /**
     * Reads remaining TTL in seconds. Returns -2 if key does not exist.
     */
    public long getRemainingTtl(String vendorId, String eventDate) {
        String lockKey = buildLockKey(vendorId, eventDate);
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.ttl(lockKey);
        }
    }
}
