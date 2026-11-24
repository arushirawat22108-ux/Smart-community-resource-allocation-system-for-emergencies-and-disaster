package com.cras.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection: after N consecutive failures for an email, further attempts are
 * refused for a lockout period (HTTP 429). A success clears the counter. In-memory, per instance.
 */
@Component
public class LoginThrottle {

    public static class TooManyAttemptsException extends RuntimeException {
        private final long retryAfterSeconds;
        public TooManyAttemptsException(long retryAfterSeconds) {
            super("Too many failed sign-in attempts. Try again in " + retryAfterSeconds + " s.");
            this.retryAfterSeconds = retryAfterSeconds;
        }
        public long getRetryAfterSeconds() { return retryAfterSeconds; }
    }

    private static final class State {
        int fails;
        long lockedUntil;
        long lastFail;
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final long lockMs;

    public LoginThrottle(@Value("${cras.security.max-login-attempts:5}") int maxAttempts,
                         @Value("${cras.security.lockout-seconds:60}") long lockoutSeconds) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.lockMs = Math.max(1, lockoutSeconds) * 1000L;
    }

    public void check(String key) {
        State s = states.get(key);
        long now = System.currentTimeMillis();
        if (s != null && s.lockedUntil > now) {
            throw new TooManyAttemptsException((s.lockedUntil - now + 999) / 1000);
        }
    }

    /** Records a failure and returns how many attempts are left before lockout. */
    public int fail(String key) {
        State s = states.computeIfAbsent(key, k -> new State());
        synchronized (s) {
            long now = System.currentTimeMillis();
            if (now - s.lastFail > 10 * 60_000L) s.fails = 0;     // stale failures expire
            s.lastFail = now;
            s.fails++;
            if (s.fails >= maxAttempts) {
                s.lockedUntil = now + lockMs;
                s.fails = 0;
                return 0;
            }
            return maxAttempts - s.fails;
        }
    }

    public void success(String key) {
        states.remove(key);
    }
}
