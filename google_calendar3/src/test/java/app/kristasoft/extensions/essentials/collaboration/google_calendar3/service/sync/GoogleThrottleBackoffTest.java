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

import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KIM-474 (scale): rate-limit backoff for tenant-wide Google API calls.
 *
 * <p>The Google-specific hazard, and the reason this is not a copy of the Microsoft version: the
 * Calendar and Admin SDK APIs signal a quota refusal as <b>403 with a rate-limit reason</b> at least
 * as often as they signal it as 429. A bare 403 therefore decides nothing on its own, and guessing
 * is expensive in both directions — retry a genuine "your super-admin has not authorised this client
 * id" three times per mailbox and one clear configuration error becomes a flood; refuse to retry a
 * real quota refusal and a large account loses whichever mailboxes were unlucky. Both directions are
 * asserted here, against real {@code HttpResponseException}s built from canned responses so the
 * parsing is exercised on the shape the Google client actually produces.
 */
@DisplayName("GoogleThrottleBackoff (KIM-474)")
class GoogleThrottleBackoffTest {

    /**
     * Builds a real {@link HttpResponseException} for the given status, headers and body, by driving
     * the actual Google HTTP client over a canned transport. Nothing here is a stand-in: the
     * exception, its header parsing and its content are exactly what a live call would produce.
     */
    private static HttpResponseException googleError(int status, Map<String, String> headers, String body)
            throws IOException {
        MockLowLevelHttpResponse response = new MockLowLevelHttpResponse()
                .setStatusCode(status)
                .setContentType("application/json")
                .setContent(body);
        headers.forEach(response::addHeader);

        MockHttpTransport transport = new MockHttpTransport() {
            @Override
            public MockLowLevelHttpRequest buildRequest(String method, String url) {
                return new MockLowLevelHttpRequest() {
                    @Override
                    public MockLowLevelHttpResponse execute() {
                        return response;
                    }
                };
            }
        };

        HttpRequestFactory factory = transport.createRequestFactory();
        HttpRequest request = factory.buildGetRequest(new GenericUrl("https://www.googleapis.com/test"));
        request.setThrowExceptionOnExecuteError(true);
        request.setNumberOfRetries(0);
        // HttpResponseException, not its GoogleJsonResponseException subclass: that subclass is
        // produced only by the Google-specific request initializer. The distinction does not matter
        // to what is under test — the backoff classifies on HttpResponseException, which is the
        // supertype every Google client failure arrives as.
        return assertThrows(HttpResponseException.class, request::execute);
    }

    private static HttpResponseException rateLimited403() throws IOException {
        return googleError(403, Map.of(), "{\"error\":{\"code\":403,\"errors\":"
                + "[{\"reason\":\"rateLimitExceeded\",\"message\":\"Rate Limit Exceeded\"}]}}");
    }

    private static HttpResponseException forbidden403() throws IOException {
        return googleError(403, Map.of(), "{\"error\":{\"code\":403,\"errors\":"
                + "[{\"reason\":\"forbidden\",\"message\":\"Not Authorized to access this resource\"}]}}");
    }

    // ── classification ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Deciding what is worth waiting for")
    class Classification {

        @Test
        @DisplayName("429 and the transient 5xx family are retryable")
        void obviousThrottlesAreRetryable() throws IOException {
            for (int status : List.of(429, 500, 502, 503, 504)) {
                assertTrue(GoogleThrottleBackoff.isThrottled(googleError(status, Map.of(), "{}")),
                        "HTTP " + status + " should be retried");
            }
        }

        /**
         * Google's own way of saying "slow down" on the Calendar and Admin SDK APIs.
         */
        @Test
        @DisplayName("403 IS retried when its body names a rate-limit reason")
        void rateLimited403IsRetryable() throws IOException {
            assertTrue(GoogleThrottleBackoff.isThrottled(rateLimited403()));
            assertTrue(GoogleThrottleBackoff.isThrottled(googleError(403, Map.of(),
                    "{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}")));
            assertTrue(GoogleThrottleBackoff.isThrottled(googleError(403, Map.of(),
                    "{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}]}}")));
        }

        /**
         * The one that matters most. An unauthorised domain-wide delegation answers 403 too, and it
         * will not clear on its own. Retrying it per mailbox across a whole Workspace account turns
         * one clear "authorise this client id" into a flood, and stops the discovery seam degrading
         * cleanly to the consented set.
         */
        @Test
        @DisplayName("403 is NOT retried when it is a genuine refusal")
        void forbidden403IsNotRetryable() throws IOException {
            assertFalse(GoogleThrottleBackoff.isThrottled(forbidden403()));
            assertFalse(GoogleThrottleBackoff.isThrottled(googleError(403, Map.of(),
                    "{\"error\":{\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}")));
        }

        @Test
        @DisplayName("401 — an unauthorised delegation — is never retried")
        void unauthorisedIsNotRetryable() throws IOException {
            assertFalse(GoogleThrottleBackoff.isThrottled(googleError(401, Map.of(),
                    "{\"error\":\"unauthorized_client\"}")));
        }

        @Test
        @DisplayName("404 and other client errors are not retried either")
        void otherClientErrorsAreNotRetried() throws IOException {
            assertFalse(GoogleThrottleBackoff.isThrottled(googleError(404, Map.of(), "{}")));
            assertFalse(GoogleThrottleBackoff.isThrottled(new IllegalStateException("nothing to see")));
            assertFalse(GoogleThrottleBackoff.isThrottled(null));
        }

        @Test
        @DisplayName("A wrapped rate limit is still recognised from its message")
        void wrappedThrottleRecognised() {
            assertTrue(GoogleThrottleBackoff.isThrottled(
                    new RuntimeException("outer", new RuntimeException("rateLimitExceeded"))));
            assertTrue(GoogleThrottleBackoff.isThrottled(new IOException("Too Many Requests")));
        }

        @Test
        @DisplayName("A self-referential cause chain terminates instead of hanging the sweep")
        void selfReferentialChainTerminates() {
            RuntimeException loop = new RuntimeException("boring") {
                @Override
                public synchronized Throwable getCause() {
                    return this;
                }
            };

            assertFalse(GoogleThrottleBackoff.isThrottled(loop));
            assertNull(GoogleThrottleBackoff.retryAfterMillis(loop));
        }
    }

    // ── Retry-After ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Honouring Retry-After")
    class RetryAfter {

        @Test
        @DisplayName("Delta-seconds are read")
        void deltaSecondsAreRead() throws IOException {
            assertEquals(30_000L, GoogleThrottleBackoff.retryAfterMillis(
                    googleError(429, Map.of("Retry-After", "30"), "{}")));
            assertEquals(5_000L, GoogleThrottleBackoff.retryAfterMillis(
                    googleError(403, Map.of("retry-after", "5"), "{}")));
        }

        @Test
        @DisplayName("The HTTP-date form is read as the interval from now")
        void httpDateFormIsRead() throws IOException {
            String httpDate = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(20)
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME);

            Long millis = GoogleThrottleBackoff.retryAfterMillis(
                    googleError(429, Map.of("Retry-After", httpDate), "{}"));

            assertNotNull(millis);
            assertTrue(millis > 10_000L && millis <= 20_000L, "expected ~20s, got " + millis + "ms");
        }

        @Test
        @DisplayName("An HTTP-date already in the past is zero, not negative")
        void pastHttpDateIsZero() throws IOException {
            String httpDate = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(5)
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME);

            assertEquals(0L, GoogleThrottleBackoff.retryAfterMillis(
                    googleError(429, Map.of("Retry-After", httpDate), "{}")));
        }

        @Test
        @DisplayName("An unparseable or absent header falls back to exponential backoff, not a failure")
        void unusableHeaderFallsBack() throws IOException {
            assertNull(GoogleThrottleBackoff.retryAfterMillis(googleError(429, Map.of(), "{}")));
            assertNull(GoogleThrottleBackoff.retryAfterMillis(
                    googleError(429, Map.of("Retry-After", "soon-ish"), "{}")));
            assertNull(GoogleThrottleBackoff.retryAfterMillis(
                    googleError(429, Map.of("Retry-After", "-4"), "{}")));
        }
    }

    // ── the retry loop ────────────────────────────────────────────────────

    @Nested
    @DisplayName("The retry loop")
    class RetryLoop {

        @Test
        @DisplayName("A call that succeeds first time is issued exactly once")
        void happyPathIssuesOneCall() throws IOException {
            AtomicInteger calls = new AtomicInteger();

            String result = GoogleThrottleBackoff.execute("op", () -> {
                calls.incrementAndGet();
                return "ok";
            });

            assertEquals("ok", result);
            assertEquals(1, calls.get());
        }

        @Test
        @DisplayName("A rate-limited call is retried and its eventual success returned")
        void retriesUntilItSucceeds() throws IOException {
            AtomicInteger calls = new AtomicInteger();
            HttpResponseException throttled = googleError(429, Map.of("Retry-After", "0"), "{}");

            String result = GoogleThrottleBackoff.execute("op", () -> {
                if (calls.incrementAndGet() < 3) {
                    throw throttled;
                }
                return "ok";
            });

            assertEquals("ok", result);
            assertEquals(3, calls.get());
        }

        @Test
        @DisplayName("The attempt cap is enforced and the original failure is rethrown")
        void attemptCapIsEnforced() throws IOException {
            AtomicInteger calls = new AtomicInteger();
            HttpResponseException throttled = googleError(429, Map.of("Retry-After", "0"), "{}");

            HttpResponseException thrown = assertThrows(HttpResponseException.class,
                    () -> GoogleThrottleBackoff.execute("op", () -> {
                        calls.incrementAndGet();
                        throw throttled;
                    }));

            assertSame(throttled, thrown, "the ORIGINAL exception must surface — callers classify by type");
            assertEquals(GoogleThrottleBackoff.MAX_ATTEMPTS, calls.get());
        }

        /**
         * Google is entitled to ask for an hour. Obeying that inside a sweep with thousands of
         * mailboxes still to visit would be worse than skipping this one.
         */
        @Test
        @DisplayName("A Retry-After larger than the ceiling ends the call instead of waiting")
        void oversizedRetryAfterDegradesRatherThanHangs() throws IOException {
            AtomicInteger calls = new AtomicInteger();
            HttpResponseException throttled = googleError(429, Map.of("Retry-After", "3600"), "{}");
            long startedAt = System.currentTimeMillis();

            assertThrows(HttpResponseException.class,
                    () -> GoogleThrottleBackoff.execute("op", () -> {
                        calls.incrementAndGet();
                        throw throttled;
                    }));

            assertEquals(1, calls.get(), "no retry is attempted once the wait exceeds the ceiling");
            assertTrue(System.currentTimeMillis() - startedAt < 5_000L,
                    "the call must return promptly rather than sleeping out the server's hour");
        }

        @Test
        @DisplayName("A genuine 403 refusal fails fast, with no waiting and no retry")
        void refusalFailsFast() throws IOException {
            AtomicInteger calls = new AtomicInteger();
            HttpResponseException denied = forbidden403();

            HttpResponseException thrown = assertThrows(HttpResponseException.class,
                    () -> GoogleThrottleBackoff.execute("op", () -> {
                        calls.incrementAndGet();
                        throw denied;
                    }));

            assertSame(denied, thrown);
            assertEquals(1, calls.get());
        }

        @Test
        @DisplayName("A rate-limiting 403 IS retried, unlike its refusing sibling")
        void rateLimited403IsRetried() throws IOException {
            AtomicInteger calls = new AtomicInteger();
            HttpResponseException throttled = rateLimited403();

            assertThrows(HttpResponseException.class,
                    () -> GoogleThrottleBackoff.execute("op", () -> {
                        calls.incrementAndGet();
                        throw throttled;
                    }));

            assertEquals(GoogleThrottleBackoff.MAX_ATTEMPTS, calls.get());
        }

        @Test
        @DisplayName("Exponential backoff stays inside both the per-wait and total ceilings")
        void ladderStaysWithinItsCeilings() {
            long total = 0;
            for (int attempt = 1; attempt < GoogleThrottleBackoff.MAX_ATTEMPTS; attempt++) {
                long delay = Math.min(GoogleThrottleBackoff.BASE_DELAY_MS << (attempt - 1),
                        GoogleThrottleBackoff.MAX_DELAY_MS);
                assertTrue(delay <= GoogleThrottleBackoff.MAX_DELAY_MS);
                total += delay;
            }
            assertTrue(total <= GoogleThrottleBackoff.MAX_TOTAL_DELAY_MS,
                    "a full exponential ladder (" + total + "ms) must fit the total budget of "
                            + GoogleThrottleBackoff.MAX_TOTAL_DELAY_MS + "ms");
        }
    }
}
