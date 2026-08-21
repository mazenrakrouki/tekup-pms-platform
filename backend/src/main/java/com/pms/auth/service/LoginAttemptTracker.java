package com.pms.auth.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * H-2: Simple sliding-window login rate limiter (in-memory, per email).
 * Blocks after MAX_ATTEMPTS failures within WINDOW_SECONDS seconds.
 * Resets automatically once the window passes or on successful login.
 */
@Service
public class LoginAttemptTracker {

    static final int MAX_ATTEMPTS = 5;
    static final long WINDOW_SECONDS = 15 * 60; // 15 minutes

    private final ConcurrentHashMap<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String email) {
        Deque<Instant> deque = attempts.get(email);
        if (deque == null) return false;
        evictOld(deque);
        return deque.size() >= MAX_ATTEMPTS;
    }

    public void recordFailure(String email) {
        attempts.computeIfAbsent(email, k -> new ConcurrentLinkedDeque<>()).addLast(Instant.now());
    }

    public void reset(String email) {
        attempts.remove(email);
    }

    private void evictOld(Deque<Instant> deque) {
        Instant cutoff = Instant.now().minusSeconds(WINDOW_SECONDS);
        deque.removeIf(t -> t.isBefore(cutoff));
    }
}
