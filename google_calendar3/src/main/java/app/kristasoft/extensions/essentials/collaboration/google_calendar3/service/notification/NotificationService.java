/*
 * Google Calendar 3.0 Extension for Krista
 * Copyright (C) 2025 Krista Software
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.notification;

import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared notification service for calendar reconnect reminder emails (Google
 * Calendar variant).
 *
 * <p><b>KIM-492 WP-E16 split this class in two, and the owner's 2026-09-11 ruling removed the
 * second half.</b> The rate-limit state stayed; the sending is gone. {@link #reserveSendSlot} is
 * the only path — it applies the limit, reserves the slot and answers, and Meeting Agent sends the
 * mail itself off the back of the reminder-DECIDE reply on
 * {@code ext.calendar.reminder.decide.google}. The {@code sendReconnectReminder} method that made a
 * blocking HTTP callback from inside a JMS consumer thread went with the uncommissioned
 * {@code caTransport} attribute and {@code HttpCaGateway}.
 *
 * <p>This class therefore makes NO remote call of any kind. It is rate-limit bookkeeping and
 * nothing else; Meeting Agent owns the email body and the relay.
 *
 * <p>Rate limiting: a rolling {@link #RATE_WINDOW_MILLIS} window per user, capped at
 * {@link #MAX_NOTIFICATIONS} sends. {@link #reserveSendSlot} has no bypass, because nothing asks it
 * for one — the {@code bypassMaxCount} flag existed only for the deleted admin send path.
 *
 * @since 3.0.3
 */
@Service
@Singleton
public class NotificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);

    public enum Outcome {
        SENT,
        RATE_LIMITED,
        FAILED
    }

    /** Maximum auto-triggered reminders per user inside the rolling window (spec §C.2). */
    public static final int MAX_NOTIFICATIONS = 3;

    /** Rolling window over which {@link #MAX_NOTIFICATIONS} applies (spec §C.2). */
    public static final long RATE_WINDOW_MILLIS = Duration.ofDays(7).toMillis();

    /**
     * Dedup window for inbound CalendarReminderSend ESB commands keyed by
     * correlationId. Long enough to absorb CA-side retries; short enough that
     * a fresh reminder a day later still goes through.
     */
    public static final long CORRELATION_DEDUP_MILLIS = Duration.ofHours(1).toMillis();

    /**
     * Per-user send-timestamp history (millis since epoch). Used to enforce the
     * rolling {@link #RATE_WINDOW_MILLIS} window.
     */
    private final ConcurrentHashMap<String, Deque<Long>> sendHistory;

    private final java.util.function.LongSupplier clock;

    @Inject
    public NotificationService() {
        this(new ConcurrentHashMap<>(), System::currentTimeMillis);
    }

    /**
     * Constructor for tests: injects the send history and the clock.
     *
     * <p>Public rather than package-private since KIM-492 WP-E16, because the reminder DECISION now
     * lives in {@code service.calendar} — the tests that must drive the rolling window without
     * sleeping for seven days are no longer in this package.
     */
    public NotificationService(ConcurrentHashMap<String, Deque<Long>> sendHistory,
                        java.util.function.LongSupplier clock) {
        this.sendHistory = sendHistory;
        this.clock = clock;
    }

    /**
     * Applies the rate limit and RESERVES a slot, without sending anything (KIM-492 WP-E16).
     *
     * <p>This is the half of the old {@code sendReconnectReminder} the request/reply decide
     * consumer needs, and since 2026-09-11 it is the only half left: Meeting Agent sends the mail,
     * so this extension's only job is to answer "should you?" and to remember that it said yes.
     *
     * <p><b>A reservation, not a check.</b> The slot is consumed the moment {@code SENT} is
     * returned, because MA will act on that answer and nothing comes back to tell us whether it
     * did. Over-counting costs one missed nudge inside a seven-day window; under-counting mails
     * somebody three times a day about a calendar they already know is broken.
     *
     * <p>It makes no HTTP call at all — that is the nested-call-inside-an-ESB-consumer shape this
     * migration removes.
     *
     * @param workspaceId    workspace the user belongs to (informational)
     * @param email          recipient email address
     * @param calendarSystem calendar system identifier (informational)
     * @return {@link Outcome#SENT} when MA may send, {@link Outcome#RATE_LIMITED} when it may not,
     *         {@link Outcome#FAILED} when the address is unusable
     */
    public Outcome reserveSendSlot(String workspaceId, String email, String calendarSystem) {
        if (email == null || email.isBlank()) {
            LOGGER.warn("reserveSendSlot called with empty email — ws={}", workspaceId);
            return Outcome.FAILED;
        }

        String userKey = email.toLowerCase(java.util.Locale.ROOT);
        long now = clock.getAsLong();
        Deque<Long> history = sendHistory.computeIfAbsent(userKey, k -> new ArrayDeque<>());

        synchronized (history) {
            evictOldEntries(history, now);
            if (history.size() >= MAX_NOTIFICATIONS) {
                LOGGER.debug("Rate limit reached for email={} (sent={} in last {}ms)",
                        email, history.size(), RATE_WINDOW_MILLIS);
                return Outcome.RATE_LIMITED;
            }
            history.addLast(now);
        }

        LOGGER.info("Reconnect reminder slot reserved: email={} ws={} system={} — Meeting Agent sends",
                email, workspaceId, calendarSystem);
        return Outcome.SENT;
    }

    /**
     * How long until this user's oldest reminder falls out of the rolling window.
     *
     * <p>Surfaced to Meeting Agent as {@code retryAfterSeconds} on a {@code RATE_LIMITED} verdict,
     * so the sweeper can say when rather than only that it refused.
     *
     * @param email recipient email address
     * @return seconds until a slot frees, or 0 when one is free now
     */
    public long secondsUntilSlotFree(String email) {
        if (email == null || email.isBlank()) {
            return 0;
        }
        Deque<Long> history = sendHistory.get(email.toLowerCase(java.util.Locale.ROOT));
        if (history == null) {
            return 0;
        }
        long now = clock.getAsLong();
        synchronized (history) {
            evictOldEntries(history, now);
            if (history.size() < MAX_NOTIFICATIONS || history.isEmpty()) {
                return 0;
            }
            long oldestExpiresAt = history.peekFirst() + RATE_WINDOW_MILLIS;
            long remainingMillis = oldestExpiresAt - now;
            return remainingMillis <= 0 ? 0 : (remainingMillis + 999) / 1000;
        }
    }

    /** Resets the per-user send history when they reconnect successfully. */
    public void resetCount(String email) {
        if (email != null && !email.isBlank()) {
            sendHistory.remove(email.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** Effective send count inside the rolling window (for tests / diagnostics). */
    public int getCount(String email) {
        if (email == null || email.isBlank()) return 0;
        Deque<Long> history = sendHistory.get(email.toLowerCase(java.util.Locale.ROOT));
        if (history == null) return 0;
        synchronized (history) {
            evictOldEntries(history, clock.getAsLong());
            return history.size();
        }
    }

    private static void evictOldEntries(Deque<Long> history, long now) {
        long cutoff = now - RATE_WINDOW_MILLIS;
        while (!history.isEmpty() && history.peekFirst() < cutoff) {
            history.pollFirst();
        }
    }
}
