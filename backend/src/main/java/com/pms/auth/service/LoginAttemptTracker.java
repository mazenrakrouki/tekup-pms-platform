package com.pms.auth.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

// =============================================================================
// FILE: LoginAttemptTracker.java
//
// WHAT THIS FILE IS
//   The brake on password guessing. It remembers, for each e-mail address, when
//   the recent failed sign-ins happened, and answers whether that address has
//   failed too often lately.
//
// WHERE IT SITS IN THE FLOW
//   Called by:  AuthService.login() only, in three places - isBlocked() as the
//     very first line of the method, recordFailure() on every rejected attempt,
//     and reset() after a successful sign-in.
//   Calls:      nothing at all. No database, no cache, no clock service; just an
//     in-memory map and Instant.now().
//
// WHY IT EXISTS
//   Without it, POST /api/auth/login answers as fast as the network allows, and
//   a script can run a common-password list against one address for as long as
//   it likes. EXAMPLE of what that costs even when no password is found: every
//   attempt runs a BCrypt comparison at strength 12, which is slow ON PURPOSE,
//   so a few hundred attempts per second are enough to saturate the server for
//   everybody else. This is why AuthService calls isBlocked() BEFORE the
//   database read and before BCrypt: a flood then costs almost nothing.
//
// HOW THE SLIDING WINDOW WORKS
//   Each failure appends the current instant to a queue kept for that address.
//   Before answering, isBlocked() throws away every instant older than
//   WINDOW_SECONDS and compares what is left with MAX_ATTEMPTS. So the window
//   slides continuously instead of resetting on a fixed clock tick. EXAMPLE of
//   why that matters: with fixed fifteen-minute buckets, an attacker could make
//   five attempts at 14:59 and five more at 15:00 - ten inside one minute -
//   without ever being blocked.
//
// WHY THE KEY IS THE E-MAIL AND NOT THE IP ADDRESS
//   Inside a company everybody shares one public IP address, so counting per IP
//   would lock the whole office out as soon as one colleague mistyped their
//   password a few times. Counting per e-mail only hurts the account that is
//   actually under attack. The known trade-off is the mirror case, "password
//   spraying": one very common password tried against many different addresses
//   is not caught here.
//
// TWO KNOWN LIMITS, WORTH SAYING OUT LOUD TO A JURY
//   1. The map lives in this one JVM. Behind a load balancer with several
//      instances, each would count separately and an attacker would get
//      MAX_ATTEMPTS tries per instance. A shared store (Redis, or a database
//      table) would be the next step. For the single-instance deployment this
//      project ships, the in-memory version is enough and adds no
//      infrastructure.
//   2. An entry is removed from the map only by reset(), that is, only after a
//      SUCCESSFUL sign-in. Addresses that fail and are never tried again keep
//      their small entry until the application restarts.
// =============================================================================

/**
 * H-2: Simple sliding-window login rate limiter (in-memory, per email).
 * Blocks after MAX_ATTEMPTS failures within WINDOW_SECONDS seconds.
 * Resets automatically once the window passes or on successful login.
 */
// @Service makes this a Spring bean, so AuthService receives the SAME instance
// on every request. That is the whole point: a new object per call would forget
// every earlier failure and the limit would never trigger.
@Service
public class LoginAttemptTracker {

    // Five failures inside the window are tolerated; the sixth attempt is
    // refused.
    // They are package-private and not private on purpose: AuthService reads
    // WINDOW_SECONDS to build the message shown to the user ("try again in 15
    // minutes"), so the number in the message can never disagree with the number
    // actually enforced.
    // Why 5 and 15 minutes: enough room for somebody who genuinely forgot which
    // of their passwords they used here, short enough that a guessing script is
    // reduced to about twenty tries an hour.
    static final int MAX_ATTEMPTS = 5;
    static final long WINDOW_SECONDS = 15 * 60; // 15 minutes

    // e-mail -> the instants of that address's recent failures.
    //
    // ConcurrentHashMap and not a plain HashMap: several HTTP requests reach
    // this object at the same time, each on its own thread. A plain HashMap
    // resized by two threads at once can corrupt its internal table and loop
    // for ever on a later read - a hang that is almost impossible to reproduce.
    //
    // Deque<Instant> as the value type: a queue with two usable ends. New
    // failures are added at the back and expired ones are dropped from the
    // front, which is exactly the shape of a sliding window. The object actually
    // created below is a ConcurrentLinkedDeque, so a thread appending a failure
    // and a thread trimming old ones cannot break each other.
    //
    // The generic types <String, Deque<Instant>> are what let the compiler
    // refuse a wrong key or a wrong value here, at compile time, instead of
    // letting a ClassCastException appear during a sign-in.
    private final ConcurrentHashMap<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    /**
     * Says whether this e-mail is currently locked out.
     *
     * <p>Gives back true when at least MAX_ATTEMPTS failures are still inside
     * the window. AuthService turns that into HTTP 429 Too Many Requests.
     *
     * <p>Why it takes the e-mail that was TYPED, without first checking that
     * such an account exists: answering differently for a known and an unknown
     * address would turn this method into a way of discovering who works here -
     * exactly the leak that the decoy hash in AuthService closes elsewhere.
     */
    public boolean isBlocked(String email) {
        Deque<Instant> deque = attempts.get(email);
        // null means nothing was ever recorded for this address. It is the normal
        // case, and it is answered without touching anything else.
        if (deque == null) return false;
        // Trim first, then count. WITHOUT the trim, the five failures of somebody
        // who mistyped their password last week would still be in the queue and
        // that person would be refused for ever.
        evictOld(deque);
        return deque.size() >= MAX_ATTEMPTS;
    }

    /**
     * Records one failed sign-in for this e-mail.
     *
     * <p>Returns nothing. AuthService calls it for a wrong password, for an
     * unknown address and for a disabled account alike: all three must count,
     * otherwise each of them would be a free, unlimited guessing channel.
     *
     * <p>computeIfAbsent(...) creates the queue only the first time this address
     * fails, and does it atomically. WITH a plain
     * "if (map.get(k) == null) map.put(k, new ...)", two threads recording a
     * failure for the same address at the same moment could each build a queue,
     * and one of the two failures would land in the queue that is thrown away -
     * so the counter would drift below reality, which is precisely what a
     * parallel attack would produce.
     */
    public void recordFailure(String email) {
        attempts.computeIfAbsent(email, k -> new ConcurrentLinkedDeque<>()).addLast(Instant.now());
    }

    /**
     * Clears the history of this e-mail after a successful sign-in.
     *
     * <p>Returns nothing. Why it is needed: somebody who mistypes their password
     * four times and then gets it right must start again from zero, not stay one
     * mistake away from a fifteen-minute lockout for the rest of the window.
     *
     * <p>It is also the only thing that ever removes an entry from the map -
     * see the second known limit in the file header.
     */
    public void reset(String email) {
        attempts.remove(email);
    }

    /**
     * Drops every failure that is older than the window.
     *
     * <p>Returns nothing; it changes the queue in place. It is called from
     * isBlocked() only, so the cleanup happens exactly when the answer is about
     * to be read: no background task, no scheduler, nothing to deploy.
     *
     * <p>cutoff is computed once, before the walk, rather than inside it:
     * calling Instant.now() for each element would move the frontier slightly
     * between two comparisons.
     *
     * <p>removeIf on a ConcurrentLinkedDeque walks the queue and unlinks the
     * matching elements without locking it, so a thread recording a new failure
     * at the same moment is neither blocked nor able to cause a
     * ConcurrentModificationException - which a plain for-each calling remove()
     * would throw on an ordinary collection.
     */
    private void evictOld(Deque<Instant> deque) {
        Instant cutoff = Instant.now().minusSeconds(WINDOW_SECONDS);
        deque.removeIf(t -> t.isBefore(cutoff));
    }
}
