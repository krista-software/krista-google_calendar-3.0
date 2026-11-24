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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KIM-492 / EF-02 — how long one Meeting Agent ESB call is allowed to wait for its reply.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Before WP-E16 every Meeting Agent call was HTTP: with Meeting Agent down the socket refused
 * immediately and the sweep moved on. On the ESB the request is <em>enqueued</em> and the caller
 * waits for a reply on a temporary queue, and the shipped broker default
 * ({@code broker.response.timeout}) is <b>ten minutes</b>. Nothing in this extension narrowed it, so
 * a Meeting Agent outage turned a calendar sweep into a ten-minute stall per call — and GLE's
 * catalog sweeps run on the Studio request thread, which is where that stall is visible to a person.
 *
 * <h2>How the budget is applied — and why NOT {@code Input.setTimeout}</h2>
 *
 * <p>{@code app.krista.infra.esb.client.Input} declares {@code setTimeout(long)} /
 * {@code getTimeout()} and {@code QueueClient.executeInternal} does honour a positive
 * {@code getTimeout()} by installing a reply-timeout override. <b>But both methods are
 * {@code default} methods whose bodies are a no-op and a {@code return 0}, and Meeting Agent's
 * generated request DTOs — {@code UpsertMeetingRequest} and its siblings, which implement
 * {@code Input} directly — do not override either one.</b> Calling {@code request.setTimeout(30_000)}
 * on a CA contract request therefore stores nothing and changes nothing: verified by {@code javap}
 * against the staged {@code conversation-agent-contracts-2.2.0.jar}. Writing it anyway would have
 * produced a green assertion over a value nothing reads and left the ten-minute wait in place, which
 * is a worse outcome than the bug.
 *
 * <p>So the budget is applied where the wait is actually read. {@code QRequester.receive()} waits
 * {@code replyOverride != null ? replyOverride : config.getResponseTimeout()}, and
 * {@code QRequester.producerGateBudget} falls through to the same config value — so a per-call
 * {@code ServiceBusConfig} copy carrying a narrowed {@code responseTimeout} bounds both halves of
 * the call. That is entirely extension-side: no CA contract change, no Studio attribute.
 *
 * <h2>Configuration</h2>
 *
 * <p>Two keys in {@code esb/esb_default.properties}, copied into system properties by
 * {@code GoogleExtensionConfig.loadEsbPropertiesEarly()} before any client is built:
 * <ul>
 *   <li>{@value #READ_TIMEOUT_KEY} — default {@value #DEFAULT_READ_MILLIS} ms. Single-meeting
 *       upsert, cancel, and the cancellation lookup: one row in, one row out.</li>
 *   <li>{@value #BULK_TIMEOUT_KEY} — default {@value #DEFAULT_BULK_MILLIS} ms. The batched upsert
 *       (up to {@value #DEFAULT_MAX_MEETINGS_PER_BULK} meetings, each classified and persisted
 *       Meeting Agent-side) and one page of the seat roster (up to 1000 users).</li>
 * </ul>
 *
 * <p>And one shape key, EF-29, so Meeting Agent's batch cap stops being a constant in two
 * codebases: {@value #MAX_BULK_KEY}, default {@value #DEFAULT_MAX_MEETINGS_PER_BULK}.
 *
 * <p>Every value is clamped into [{@value #MIN_MILLIS}, {@value #MAX_MILLIS}] ms. The ceiling is the
 * point of the change — an operator who "fixes" a slow sweep by setting ten minutes has reinstated
 * the bug — and the floor stops a typo (a value meant as seconds) refusing every call before the
 * broker has had a chance to answer. An unparseable or absent value falls back to the default with
 * one WARN rather than failing construction: an unreadable tunable must not stop the extension from
 * talking to Meeting Agent at all.
 *
 * <p>Read live on every call rather than cached. These are read once per ESB round trip — a
 * {@code System.getProperty} beside a broker round trip costs nothing measurable — and reading live
 * means the value in force is the value configured, with no restart and no stale snapshot to explain.
 *
 * <p>Stateless and thread-safe.
 */
final class CaCallBudget {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaCallBudget.class);

    /** Reply budget for a single-row Meeting Agent call, in milliseconds. */
    static final String READ_TIMEOUT_KEY = "ca.esb.request.timeoutMs";

    /** Reply budget for a batched or paged Meeting Agent call, in milliseconds. */
    static final String BULK_TIMEOUT_KEY = "ca.esb.bulkRequest.timeoutMs";

    /** EF-29: Meeting Agent's cap on meetings per {@code bulkUpsertMeetings} request. */
    static final String MAX_BULK_KEY = "ca.esb.bulkRequest.maxMeetings";

    static final long DEFAULT_READ_MILLIS = 30_000L;
    static final long DEFAULT_BULK_MILLIS = 60_000L;

    /**
     * Meeting Agent's documented cap today. Over it the whole batch is rejected with
     * {@code VALIDATION_ERROR}, so this is a contract value rather than a preference — the key
     * exists so a Meeting Agent that raises its cap does not need an extension release.
     */
    static final int DEFAULT_MAX_MEETINGS_PER_BULK = 50;

    /**
     * Hard ceiling on any reply budget. Above this the call is no longer "slow", it is the
     * unbounded wait this class was written to remove.
     */
    static final long MAX_MILLIS = 120_000L;

    /** Hard floor. Below this a healthy broker round trip would be cancelled as a timeout. */
    static final long MIN_MILLIS = 1_000L;

    /** Floor on the batch cap: a batch of zero meetings is not a batch. */
    static final int MIN_MAX_MEETINGS_PER_BULK = 1;

    /**
     * Ceiling on the batch cap. Generous, because the value tracks Meeting Agent's cap rather than
     * this extension's taste, but finite so a typo cannot build a request no broker will carry.
     */
    static final int MAX_MAX_MEETINGS_PER_BULK = 1_000;

    private CaCallBudget() {
    }

    /**
     * @return the reply budget for a single-row Meeting Agent call, in milliseconds; always within
     *         [{@value #MIN_MILLIS}, {@value #MAX_MILLIS}]
     */
    static long readMillis() {
        return resolveMillis(READ_TIMEOUT_KEY, DEFAULT_READ_MILLIS);
    }

    /**
     * @return the reply budget for a batched or paged Meeting Agent call, in milliseconds; always
     *         within [{@value #MIN_MILLIS}, {@value #MAX_MILLIS}]
     */
    static long bulkMillis() {
        return resolveMillis(BULK_TIMEOUT_KEY, DEFAULT_BULK_MILLIS);
    }

    /**
     * @return how many meetings may ride in one {@code bulkUpsertMeetings} request; always within
     *         [{@value #MIN_MAX_MEETINGS_PER_BULK}, {@value #MAX_MAX_MEETINGS_PER_BULK}]
     */
    static int maxMeetingsPerBulk() {
        long resolved = resolve(MAX_BULK_KEY, DEFAULT_MAX_MEETINGS_PER_BULK,
                MIN_MAX_MEETINGS_PER_BULK, MAX_MAX_MEETINGS_PER_BULK);
        return (int) resolved;
    }

    /** Package-private for the budget test, which drives the clamp directly. */
    static long resolveMillis(String key, long fallback) {
        return resolve(key, fallback, MIN_MILLIS, MAX_MILLIS);
    }

    private static long resolve(String key, long fallback, long min, long max) {
        String raw;
        try {
            raw = System.getProperty(key);
        }
        catch (Exception unreadable) {
            LOGGER.warn("Could not read '{}'; using {}: {}", key, fallback, unreadable.getMessage());
            return fallback;
        }
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        long parsed;
        try {
            parsed = Long.parseLong(raw.trim());
        }
        catch (NumberFormatException notANumber) {
            LOGGER.warn("'{}' is set to '{}', which is not a number. Using {} instead.",
                    key, raw, fallback);
            return fallback;
        }

        if (parsed < min) {
            LOGGER.warn("'{}' is set to {}, below the floor of {}. Using {} instead — a smaller "
                    + "budget would cancel healthy calls.", key, parsed, min, min);
            return min;
        }
        if (parsed > max) {
            LOGGER.warn("'{}' is set to {}, above the ceiling of {}. Using {} instead — a larger "
                    + "budget reinstates the unbounded wait this setting exists to bound.",
                    key, parsed, max, max);
            return max;
        }
        return parsed;
    }
}
