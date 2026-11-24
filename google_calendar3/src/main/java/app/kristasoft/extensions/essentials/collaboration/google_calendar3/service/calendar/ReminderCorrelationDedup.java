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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.notification.NotificationService;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Remembers which calendar-reminder correlation ids have already been answered, so an MA-side retry
 * after a crash or timeout does not produce a second email (spec §C.2).
 *
 * <p>Extracted from {@code CalendarReminderSendListener} in KIM-492 WP-E16, when the reminder loop
 * briefly had two inbound paths. It has one now: the request/reply {@code decide} consumer, which
 * is the only place a correlation id is answered. The legacy one-way {@code send} listener merely
 * drains its durable queue and no longer touches this memory, so a retry of the same decide request
 * is the only duplicate left to catch — which is exactly what this class is for.
 *
 * <p>Kept as a singleton rather than folded back into the decide consumer: the dedup window
 * outlives any one message, and a per-consumer map would forget every id on a broker reconnect.
 *
 * <p>Thread-safe: a {@link ConcurrentHashMap} plus an amortised eviction counter.
 *
 * @since 3.1.0
 */
@Service
@Singleton
public class ReminderCorrelationDedup {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReminderCorrelationDedup.class);

    /** Hard cap on dedup cache size, to bound memory under correlationId fanout. */
    static final int DEDUP_CACHE_MAX_ENTRIES = 10_000;

    /**
     * Eviction runs every Nth dispatch so the per-event cost stays O(1) amortised instead of O(n)
     * on the full {@code seenCorrelationIds} map.
     */
    static final int EVICT_EVERY_N_EVENTS = 256;

    /** correlationId -&gt; first-seen epoch millis */
    private final ConcurrentHashMap<String, Long> seenCorrelationIds = new ConcurrentHashMap<>();
    private final AtomicInteger sinceLastEvict = new AtomicInteger();
    private final LongSupplier clock;

    public ReminderCorrelationDedup() {
        this(System::currentTimeMillis);
    }

    /**
     * @param clock injectable time source, so the dedup WINDOW can be tested without sleeping
     */
    public ReminderCorrelationDedup(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Records the id and reports whether it had already been seen inside the dedup window.
     *
     * @param correlationId the id MA stamped on the request; may be null
     * @return true when this id has already been answered and the caller must not answer again
     */
    public boolean isDuplicate(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            // No correlationId — cannot dedup. Fail open; the rate limiter still applies.
            return false;
        }
        long now = clock.getAsLong();
        maybeEvict(now);
        Long firstSeen = seenCorrelationIds.putIfAbsent(correlationId, now);
        if (firstSeen == null) {
            return false;
        }
        // Time-check guards stale-but-not-yet-evicted entries (eviction is amortised).
        if (now - firstSeen <= NotificationService.CORRELATION_DEDUP_MILLIS) {
            return true;
        }
        seenCorrelationIds.replace(correlationId, firstSeen, now);
        return false;
    }

    private void maybeEvict(long now) {
        boolean overCap = seenCorrelationIds.size() > DEDUP_CACHE_MAX_ENTRIES;
        if (!overCap && sinceLastEvict.incrementAndGet() < EVICT_EVERY_N_EVENTS) {
            return;
        }
        sinceLastEvict.set(0);
        long cutoff = now - NotificationService.CORRELATION_DEDUP_MILLIS;
        seenCorrelationIds.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        if (seenCorrelationIds.size() > DEDUP_CACHE_MAX_ENTRIES) {
            LOGGER.warn("Dedup cache still over {} after sweep; clearing as safety net",
                    DEDUP_CACHE_MAX_ENTRIES);
            seenCorrelationIds.clear();
        }
    }
}
