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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync;

import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * KIM-474: {@code Retry-After}-aware backoff for the delegated (Always On) Google API calls.
 *
 * <h3>Why this exists, and why only on the delegated path</h3>
 * With Always On off, a sweep touches the handful of users who authorised their own calendar; Google
 * never rate-limits that. With Always On on, one sweep issues an Admin SDK enumeration plus an
 * {@code events.list} and up to three watch-channel calls <em>per mailbox</em> across every
 * registered user of the workspace. Google answers with a quota refusal long before a large
 * workspace finishes, and without this the sweep simply loses every rate-limited mailbox for that
 * run with nothing in the log to say why.
 *
 * <p>Every entry point is reached only when the caller has already established it is on the
 * delegated path. That is deliberate: the Always-On-off path must stay exactly what it was, and
 * adding a retry there — however benign — would change how an existing tenant behaves under a fault,
 * which is not what this change is authorised to do.
 *
 * <h3>Recognising a Google rate limit is not the same as recognising a Microsoft one</h3>
 * Graph answers 429. Google's Calendar and Admin SDK APIs answer <b>403 with a reason of
 * {@code rateLimitExceeded}, {@code userRateLimitExceeded} or {@code quotaExceeded}</b> at least as
 * often as they answer 429. A bare 403 is therefore NOT enough to decide either way — and getting it
 * wrong is expensive in both directions:
 * <ul>
 *   <li>treat every 403 as a rate limit and an unauthorised domain-wide delegation is retried three
 *       times per mailbox across the whole account, turning one clear "your super-admin has not
 *       authorised this client id" into a flood — and the discovery seam's delegation-denied
 *       classifier never gets to see it and degrade cleanly;</li>
 *   <li>treat every 403 as a refusal and a genuinely throttled sweep drops mailboxes it was told how
 *       long to wait for.</li>
 * </ul>
 * So a 403 is retried only when its body names a rate-limit reason, and never otherwise.
 *
 * <h3>The contract</h3>
 * <ul>
 *   <li>Honours {@code Retry-After} when Google sends one, in either documented form — delta-seconds
 *       or an HTTP-date.</li>
 *   <li>Falls back to bounded exponential backoff ({@link #BASE_DELAY_MS} doubling) when there is
 *       none, which is the shape the sibling Teams extension already uses.</li>
 *   <li><b>Degrades, never hangs.</b> At most {@link #MAX_ATTEMPTS} attempts, no single wait longer
 *       than {@link #MAX_DELAY_MS}, and no more than {@link #MAX_TOTAL_DELAY_MS} of waiting in
 *       total. A {@code Retry-After} bigger than either ceiling is not obeyed <em>and not clamped
 *       down either</em>: retrying earlier than Google said it would be ready buys a refusal we
 *       already know is coming, on top of the wait spent getting there. The call gives up and the
 *       caller skips that mailbox.</li>
 *   <li>Logs at WARN whenever a wait is taken, naming the operation, the attempt and where the delay
 *       came from, so an operator can see throttling rather than infer it from a slow sweep.</li>
 *   <li>Rethrows the ORIGINAL exception, never a wrapper. Callers classify Google failures by type
 *       and by cause chain; wrapping would silently break both.</li>
 * </ul>
 *
 * <p>Stateless and thread-safe; safe to call from the virtual-thread fan-out.
 */
public final class GoogleThrottleBackoff {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleThrottleBackoff.class);

    /** Total attempts including the first, so at most three retries. */
    static final int MAX_ATTEMPTS = 4;

    /** First backoff when Google sends no {@code Retry-After}; doubles per attempt. */
    static final long BASE_DELAY_MS = 1_000L;

    /** Ceiling on any single wait, however large a {@code Retry-After} Google asks for. */
    static final long MAX_DELAY_MS = 60_000L;

    /** Ceiling on the total time one call may spend waiting before it gives up. */
    static final long MAX_TOTAL_DELAY_MS = 120_000L;

    /** Bound on cause-chain walks; a self-referential chain must not hang a sweep. */
    private static final int MAX_CAUSE_DEPTH = 6;

    /** Status codes that always mean "come back later". */
    private static final int[] ALWAYS_RETRYABLE = {429, 500, 502, 503, 504};

    /**
     * The reasons that turn a 403 from "you may not" into "not right now". Matched against the
     * response body, which is where the Google JSON error carries its {@code reason}.
     */
    private static final String[] RATE_LIMIT_REASONS = {
            "ratelimitexceeded",
            "userratelimitexceeded",
            "quotaexceeded",
            "backenderror"
    };

    /**
     * Message fragments that mean "rate limited" when no typed response is available, which happens
     * on the paths that wrap the fault. Deliberately narrow: no bare status numbers, because those
     * also appear in request ids and trace identifiers.
     */
    private static final String[] THROTTLE_MARKERS = {
            "too many requests",
            "rate limit",
            "ratelimitexceeded",
            "userratelimitexceeded",
            "quotaexceeded"
    };

    /**
     * A Google API call.
     *
     * @param <T> the call's result type
     */
    @FunctionalInterface
    public interface GoogleCall<T> {

        /**
         * Issues the call.
         *
         * @return the call's result
         * @throws IOException as every Google client {@code execute()} does
         */
        T execute() throws IOException;
    }

    private GoogleThrottleBackoff() {
    }

    /**
     * Runs a delegated Google call, waiting out rate-limit responses.
     *
     * @param operation short label for the log line, e.g. {@code "watch channel for a@b.example"}
     * @param call      the Google call to issue
     * @param <T>       the call's result type
     * @return the call's result
     * @throws IOException the original failure, unwrapped, once the call is not retryable or the
     *                     retry budget is spent
     */
    public static <T> T execute(String operation, GoogleCall<T> call) throws IOException {
        long spentMillis = 0L;
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.execute();
            }
            catch (IOException | RuntimeException failure) {
                lastFailure = failure;
                if (!isThrottled(failure)) {
                    throw failure;
                }
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }

                Long serverDelay = retryAfterMillis(failure);
                long delay;
                if (serverDelay != null) {
                    if (serverDelay > MAX_DELAY_MS) {
                        LOGGER.error("[GCAL-THROTTLE] Google is rate-limiting '{}' and asks for {} ms, longer than "
                                + "the {} ms this sweep is willing to wait on one call. Giving up on it so the "
                                + "sweep keeps moving; the next sweep retries it.",
                                operation, serverDelay, MAX_DELAY_MS);
                        break;
                    }
                    delay = serverDelay;
                }
                else {
                    delay = Math.min(BASE_DELAY_MS << (attempt - 1), MAX_DELAY_MS);
                }

                if (spentMillis + delay > MAX_TOTAL_DELAY_MS) {
                    LOGGER.error("[GCAL-THROTTLE] Google is rate-limiting '{}' and the {} ms wait does not fit the "
                            + "remaining {} ms retry budget. Giving up on this call so the sweep keeps moving; "
                            + "the next sweep retries it.",
                            operation, delay, MAX_TOTAL_DELAY_MS - spentMillis);
                    break;
                }

                LOGGER.warn("[GCAL-THROTTLE] Google rate-limited '{}' (attempt {}/{}). Waiting {} ms ({}) before "
                        + "retrying.", operation, attempt, MAX_ATTEMPTS, delay,
                        serverDelay != null ? "honouring the Retry-After header" : "exponential backoff, no header");
                if (!sleep(delay, operation)) {
                    throw failure;
                }
                spentMillis += delay;
            }
        }

        LOGGER.error("[GCAL-THROTTLE] Google kept rate-limiting '{}' after {} attempt(s) and {} ms of waiting. "
                + "Reporting the failure so the caller can skip this target.",
                operation, MAX_ATTEMPTS, spentMillis);
        if (lastFailure instanceof IOException ioFailure) {
            throw ioFailure;
        }
        throw (RuntimeException) lastFailure;
    }

    /**
     * Classifies a Google failure as "come back later".
     *
     * <p>Public so a caller that wraps this backoff in a retry loop of its own can tell "the backoff
     * already spent its budget on a rate limit" from "something else failed" — otherwise the two
     * ceilings multiply, per mailbox, across every registered user of the workspace.
     *
     * @param error the failure to classify; may be null
     * @return true when waiting and retrying is the right response
     */
    public static boolean isThrottled(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            if (current instanceof HttpResponseException response) {
                int status = response.getStatusCode();
                for (int retryable : ALWAYS_RETRYABLE) {
                    if (status == retryable) {
                        return true;
                    }
                }
                // A 403 is a rate limit only when it says so. See the class comment: guessing either
                // way here is expensive.
                if (status == 403 || status == 401) {
                    return status == 403 && namesARateLimit(response.getContent());
                }
                return false;
            }
            if (matchesThrottleMarker(current.getMessage())) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Extracts the {@code Retry-After} Google asked for.
     *
     * <p>Both documented forms are handled: delta-seconds, and an HTTP-date (whose remaining
     * interval is measured from now, and clamped at zero if it is already past).
     *
     * @param error the failure carrying the response; may be null
     * @return the requested wait in milliseconds, or null when Google sent no usable header
     */
    public static Long retryAfterMillis(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            if (current instanceof HttpResponseException response) {
                HttpHeaders headers = response.getHeaders();
                if (headers != null) {
                    Long parsed = parseRetryAfterValue(headers.getRetryAfter());
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Parses one {@code Retry-After} value. Never throws: an unparseable header simply means "fall
     * back to exponential backoff", which is strictly better than failing the call over a header.
     */
    private static Long parseRetryAfterValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? null : seconds * 1_000L;
        }
        catch (NumberFormatException notDeltaSeconds) {
            // Fall through to the HTTP-date form.
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
            long millis = Duration.between(ZonedDateTime.now(when.getZone()), when).toMillis();
            return Math.max(0L, millis);
        }
        catch (RuntimeException notAnHttpDate) {
            LOGGER.debug("Ignoring an unparseable Retry-After value: {}", value);
            return null;
        }
    }

    private static boolean namesARateLimit(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return false;
        }
        String lower = responseBody.toLowerCase(Locale.ROOT);
        for (String reason : RATE_LIMIT_REASONS) {
            if (lower.contains(reason)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesThrottleMarker(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String marker : THROTTLE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Waits, preserving interruption.
     *
     * @return false when the wait was interrupted, in which case the caller must abandon the retry
     */
    private static boolean sleep(long millis, String operation) {
        try {
            Thread.sleep(millis);
            return true;
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("[GCAL-THROTTLE] Backoff for '{}' was interrupted; abandoning the retry.", operation);
            return false;
        }
    }
}
