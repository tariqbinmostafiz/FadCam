package com.fadcam.streaming.util;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-IP login throttling for the Remote server.
 * First {@link #FREE_ATTEMPTS} failures are free; after that the IP is locked
 * out with exponential back-off (30s, 60s, 120s ... capped at 15 min).
 * State is in-memory only (resets when the process restarts).
 */
public final class LoginRateLimiter {

    private static final int FREE_ATTEMPTS = 5;
    private static final long BASE_LOCK_MS = 30_000L;
    private static final long MAX_LOCK_MS = 15 * 60_000L;
    private static final long STALE_AFTER_MS = 60 * 60_000L;

    private static final class Entry {
        int failures;
        long lockedUntil;
        long lastFailure;
    }

    private final Map<String, Entry> entries = new HashMap<>();

    /** @return remaining lock time in ms, or 0 when the IP may try. */
    public synchronized long remainingLockMs(String ip) {
        Entry e = entries.get(key(ip));
        if (e == null) return 0L;
        long left = e.lockedUntil - System.currentTimeMillis();
        return Math.max(0L, left);
    }

    public synchronized void onFailure(String ip) {
        long now = System.currentTimeMillis();
        purgeStale(now);
        Entry e = entries.get(key(ip));
        if (e == null) {
            e = new Entry();
            entries.put(key(ip), e);
        }
        e.failures++;
        e.lastFailure = now;
        if (e.failures >= FREE_ATTEMPTS) {
            int shift = Math.min(e.failures - FREE_ATTEMPTS, 6);
            e.lockedUntil = now + Math.min(BASE_LOCK_MS << shift, MAX_LOCK_MS);
        }
    }

    public synchronized void onSuccess(String ip) {
        entries.remove(key(ip));
    }

    private void purgeStale(long now) {
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (now - e.lastFailure > STALE_AFTER_MS && e.lockedUntil < now) it.remove();
        }
    }

    private static String key(String ip) {
        return ip == null ? "unknown" : ip;
    }
}
