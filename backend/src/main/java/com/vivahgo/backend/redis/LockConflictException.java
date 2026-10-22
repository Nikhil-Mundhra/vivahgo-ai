package com.vivahgo.backend.redis;

/**
 * Thrown when an agent attempts to place a hold on an asset (vendor/venue for a specific date)
 * that is already held by another session/couple.
 *
 * This exception bubbles up through the MCP layer as a typed conflict signal,
 * allowing the AI supervisor to dynamically pivot and propose an alternative asset.
 */
public class LockConflictException extends RuntimeException {
    private final String lockKey;
    private final String currentHolder;
    private final long remainingTtlSeconds;

    public LockConflictException(String lockKey, String currentHolder, long remainingTtlSeconds) {
        super(String.format("Asset '%s' is temporarily held by '%s'. Hold expires in %d seconds.",
                lockKey, currentHolder, remainingTtlSeconds));
        this.lockKey = lockKey;
        this.currentHolder = currentHolder;
        this.remainingTtlSeconds = remainingTtlSeconds;
    }

    public String getLockKey() {
        return lockKey;
    }

    public String currentHolder() {
        return currentHolder;
    }

    public long getRemainingTtlSeconds() {
        return remainingTtlSeconds;
    }
}
