package com.pms.auth.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

// The brake on password guessing (H-2): remembers, per e-mail, when recent failed sign-ins
// happened, and answers whether that address has failed too often lately. Called only by
// AuthService.login(), and isBlocked() runs before any DB read or BCrypt call, since BCrypt is
// slow on purpose and would otherwise let a flood saturate the server for everyone.
//
// Sliding window, not fixed buckets: each failure is timestamped, and isBlocked() discards
// anything older than WINDOW_SECONDS before counting — a fixed 15-minute bucket would let five
// attempts at :59 and five more at :00 slip through unblocked.
//
// Keyed by e-mail, not IP: an office behind one shared IP would otherwise all get locked out by
// one colleague's typo. The trade-off is that "password spraying" (one common password against
// many addresses) isn't caught here.
//
// Two known limits: (1) in-memory per JVM — behind a load balancer with several instances, an
// attacker gets MAX_ATTEMPTS tries per instance; fine for this project's single-instance
// deployment. (2) an entry is only ever removed by reset(), i.e. after a successful login —
// addresses that fail and are never retried keep a small entry until restart.

/**
 * H-2: Simple sliding-window login rate limiter (in-memory, per email).
 * Blocks after MAX_ATTEMPTS failures within WINDOW_SECONDS seconds.
 * Resets automatically once the window passes or on successful login.
 */
@Service
public class LoginAttemptTracker {

    // Package-private (not private): AuthService reads WINDOW_SECONDS to build the user-facing
    // "try again in 15 minutes" message, so it can never disagree with what's enforced here.
    static final int MAX_ATTEMPTS = 5;
    static final long WINDOW_SECONDS = 15 * 60; // 15 minutes

    // e-mail -> recent failure instants. ConcurrentHashMap because many requests hit this
    // concurrently; a plain HashMap resized by two threads at once can corrupt its table.
    // ConcurrentLinkedDeque so appending a new failure and trimming old ones never race.
    private final ConcurrentHashMap<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    /**
     * Says whether this e-mail is currently locked out (at least MAX_ATTEMPTS failures still
     * inside the window). Doesn't check whether the account exists first — that would itself be
     * a way to enumerate valid addresses.
     */
    public boolean isBlocked(String email) {
        Deque<Instant> deque = attempts.get(email);
        if (deque == null) return false;
        evictOld(deque);
        return deque.size() >= MAX_ATTEMPTS;
    }

    /**
     * Records one failed sign-in. AuthService calls this for a wrong password, an unknown
     * address and a disabled account alike, so none of the three becomes an unlimited guessing
     * channel. computeIfAbsent is atomic, so two concurrent failures for the same address can't
     * race and drop one.
     */
    public void recordFailure(String email) {
        attempts.computeIfAbsent(email, k -> new ConcurrentLinkedDeque<>()).addLast(Instant.now());
    }

    /**
     * Clears the history of this e-mail after a successful sign-in, so a few mistyped attempts
     * don't leave the account one failure away from a lockout later.
     */
    public void reset(String email) {
        attempts.remove(email);
    }

    /**
     * Drops every failure older than the window. Called only from isBlocked(), so cleanup
     * happens exactly when the answer is needed — no background task required.
     */
    private void evictOld(Deque<Instant> deque) {
        Instant cutoff = Instant.now().minusSeconds(WINDOW_SECONDS);
        deque.removeIf(t -> t.isBefore(cutoff));
    }
}
