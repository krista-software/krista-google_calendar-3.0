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

import app.krista.conversationagent.module.calendar.esb.dto.CalendarReminderDecideReply;
import app.krista.conversationagent.module.calendar.esb.dto.CalendarReminderDecideRequest;
import app.krista.conversationagent.module.shared.esb.ExtErrorCodes;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.notification.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * All four verdicts of the KIM-492 WP-E16 reminder-DECIDE consumer (spec §3.10).
 *
 * <p>The handler's job is to ANSWER, never to send: Meeting Agent sends the mail on {@code SEND}
 * and does nothing on the other three. The old one-way listener applied the same two rules and then
 * made a blocking HTTP callback from inside the JMS consumer thread — the nested-HTTP-in-an-ESB-
 * consumer shape this migration exists to remove. That callback is now gone from the codebase
 * entirely, so the tests below assert the stronger, structural form: this path holds nothing it
 * could call Meeting Agent WITH.
 */
@DisplayName("Calendar reminder decision")
class GoogleCalendarReminderDecideHandlerTest {

    private static final String WORKSPACE = "ws-1";
    private static final String EMAIL = "user@acme.example";

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final NotificationService notifications = new NotificationService(
            new java.util.concurrent.ConcurrentHashMap<>(), clock::get);

    private GoogleCalendarReminderDecideHandler handler(ReminderCorrelationDedup dedup) {
        return new GoogleCalendarReminderDecideHandler(notifications, dedup);
    }

    private GoogleCalendarReminderDecideHandler handler() {
        return handler(new ReminderCorrelationDedup(clock::get));
    }

    private static CalendarReminderDecideRequest request(String email, String correlationId) {
        CalendarReminderDecideRequest request = new CalendarReminderDecideRequest();
        request.setWorkspaceId(WORKSPACE);
        request.setEmail(email);
        request.setCalendarSystem("GOOGLE");
        request.setCorrelationId(correlationId);
        return request;
    }

    @Nested
    @DisplayName("SEND")
    class Send {

        @Test
        @DisplayName("a fresh request for a user under the limit answers SEND with a blank errorCode")
        void freshRequestSends() {
            CalendarReminderDecideReply reply =
                    handler().googleCalendarReminderDecide(request(EMAIL, "corr-1"));

            assertEquals(CalendarReminderDecideReply.SEND, reply.getDecision());
            assertEquals("", reply.getErrorCode(),
                    "blank, not null: blank is the contract's success signal, and MA reads a null"
                            + " errorCode as 'this reply never came off the wire'");
            assertEquals("corr-1", reply.getCorrelationId());
            assertEquals(0, reply.getRetryAfterSeconds());
            assertNoRemoteCollaborator();
        }

        @Test
        @DisplayName("SEND RESERVES the slot — MA acts on the answer and nothing reports back")
        void sendReservesTheSlot() {
            GoogleCalendarReminderDecideHandler handler = handler();

            for (int i = 1; i <= NotificationService.MAX_NOTIFICATIONS; i++) {
                assertEquals(CalendarReminderDecideReply.SEND,
                        handler.googleCalendarReminderDecide(request(EMAIL, "corr-" + i)).getDecision());
            }

            assertEquals(CalendarReminderDecideReply.RATE_LIMITED,
                    handler.googleCalendarReminderDecide(request(EMAIL, "corr-over")).getDecision(),
                    "Over-counting costs one missed nudge in a seven-day window; under-counting"
                            + " mails somebody three times a day about a calendar they already know"
                            + " is broken.");
        }
    }

    @Nested
    @DisplayName("DEDUPED")
    class Deduped {

        @Test
        @DisplayName("the same correlationId inside the window is DEDUPED, not re-answered")
        void repeatedCorrelationIdIsDeduped() {
            GoogleCalendarReminderDecideHandler handler = handler();

            assertEquals(CalendarReminderDecideReply.SEND,
                    handler.googleCalendarReminderDecide(request(EMAIL, "corr-dup")).getDecision());

            CalendarReminderDecideReply second =
                    handler.googleCalendarReminderDecide(request(EMAIL, "corr-dup"));

            assertEquals(CalendarReminderDecideReply.DEDUPED, second.getDecision());
            assertEquals("", second.getErrorCode(), "a dedup is a normal answer, not an error");
            assertNotNull(second.getReason());
        }

        @Test
        @DisplayName("dedup runs BEFORE the rate limit, so a retry storm cannot burn the allowance")
        void dedupRunsBeforeTheRateLimit() {
            GoogleCalendarReminderDecideHandler handler = handler();

            handler.googleCalendarReminderDecide(request(EMAIL, "corr-x"));
            for (int i = 0; i < 20; i++) {
                handler.googleCalendarReminderDecide(request(EMAIL, "corr-x"));
            }

            // One slot consumed, not twenty-one. Two more fresh ids must still get SEND.
            assertEquals(CalendarReminderDecideReply.SEND,
                    handler.googleCalendarReminderDecide(request(EMAIL, "corr-y")).getDecision());
            assertEquals(CalendarReminderDecideReply.SEND,
                    handler.googleCalendarReminderDecide(request(EMAIL, "corr-z")).getDecision());
        }

        @Test
        @DisplayName("the window expires and the same id is answered again")
        void theWindowExpires() {
            GoogleCalendarReminderDecideHandler handler = handler();

            assertEquals(CalendarReminderDecideReply.SEND,
                    handler.googleCalendarReminderDecide(request(EMAIL, "corr-w")).getDecision());
            clock.addAndGet(NotificationService.CORRELATION_DEDUP_MILLIS + 1);

            assertEquals(CalendarReminderDecideReply.SEND,
                    handler.googleCalendarReminderDecide(request(EMAIL, "corr-w")).getDecision());
        }

        @Test
        @DisplayName("a missing correlationId fails OPEN — the rate limiter is the remaining guard")
        void missingCorrelationIdFailsOpen() {
            GoogleCalendarReminderDecideHandler handler = handler();

            assertEquals(CalendarReminderDecideReply.SEND,
                    handler.googleCalendarReminderDecide(request(EMAIL, null)).getDecision());
            assertEquals(CalendarReminderDecideReply.SEND,
                    handler.googleCalendarReminderDecide(request(EMAIL, "")).getDecision());
        }
    }

    @Nested
    @DisplayName("RATE_LIMITED")
    class RateLimited {

        @Test
        @DisplayName("past the limit the verdict is RATE_LIMITED with a usable retryAfterSeconds")
        void pastTheLimitSaysWhen() {
            GoogleCalendarReminderDecideHandler handler = handler();
            for (int i = 1; i <= NotificationService.MAX_NOTIFICATIONS; i++) {
                handler.googleCalendarReminderDecide(request(EMAIL, "corr-" + i));
            }

            CalendarReminderDecideReply reply =
                    handler.googleCalendarReminderDecide(request(EMAIL, "corr-over"));

            assertEquals(CalendarReminderDecideReply.RATE_LIMITED, reply.getDecision());
            assertEquals("", reply.getErrorCode(), "a rate limit is a decision, not a fault");
            assertTrue(reply.getRetryAfterSeconds() > 0,
                    "the sweeper should be able to say WHEN, not only that it refused");
            assertTrue(reply.getRetryAfterSeconds()
                            <= NotificationService.RATE_WINDOW_MILLIS / 1000 + 1,
                    "retryAfter cannot exceed the rolling window");
        }

        @Test
        @DisplayName("the limit is per user, not global")
        void theLimitIsPerUser() {
            GoogleCalendarReminderDecideHandler handler = handler();
            for (int i = 1; i <= NotificationService.MAX_NOTIFICATIONS; i++) {
                handler.googleCalendarReminderDecide(request(EMAIL, "corr-" + i));
            }

            assertEquals(CalendarReminderDecideReply.SEND,
                    handler.googleCalendarReminderDecide(
                            request("someone.else@acme.example", "corr-other")).getDecision());
        }
    }

    @Nested
    @DisplayName("REFUSED")
    class Refused {

        @Test
        @DisplayName("a null request is REFUSED with VALIDATION_ERROR, never an exception")
        void nullRequestIsRefused() {
            CalendarReminderDecideReply reply = handler().googleCalendarReminderDecide(null);

            assertEquals(CalendarReminderDecideReply.REFUSED, reply.getDecision());
            assertEquals(ExtErrorCodes.VALIDATION_ERROR, reply.getErrorCode());
            assertNull(reply.getCorrelationId());
        }

        @Test
        @DisplayName("a blank recipient is REFUSED with VALIDATION_ERROR, and echoes the correlationId")
        void blankEmailIsRefused() {
            CalendarReminderDecideReply reply =
                    handler().googleCalendarReminderDecide(request("   ", "corr-blank"));

            assertEquals(CalendarReminderDecideReply.REFUSED, reply.getDecision());
            assertEquals(ExtErrorCodes.VALIDATION_ERROR, reply.getErrorCode());
            assertEquals("corr-blank", reply.getCorrelationId(),
                    "MA correlates the answer; a refusal it cannot match is a refusal it cannot log");
        }

        @Test
        @DisplayName("an internal failure still REPLIES — MA is blocking on this call")
        void anInternalFailureStillReplies() {
            ReminderCorrelationDedup exploding = new ReminderCorrelationDedup() {
                @Override
                public boolean isDuplicate(String correlationId) {
                    throw new IllegalStateException("dedup store unavailable");
                }
            };

            CalendarReminderDecideReply reply =
                    handler(exploding).googleCalendarReminderDecide(request(EMAIL, "corr-boom"));

            assertEquals(CalendarReminderDecideReply.REFUSED, reply.getDecision());
            assertEquals(ExtErrorCodes.INTERNAL_ERROR, reply.getErrorCode());
            assertEquals("corr-boom", reply.getCorrelationId());
            // Letting the exception out would leave MA waiting for its full timeout and then
            // sending nothing at all — a silent non-nudge instead of a logged one.
        }
    }

    @Test
    @DisplayName("no verdict ever sends an email or calls Meeting Agent")
    void noVerdictEverSends() {
        GoogleCalendarReminderDecideHandler handler = handler();

        // Every shape the handler can be handed, including the two malformed ones: a duplicate, a
        // run past the rate limit, an empty address and a null request.
        handler.googleCalendarReminderDecide(request(EMAIL, "c1"));
        handler.googleCalendarReminderDecide(request(EMAIL, "c1"));
        for (int i = 0; i < 10; i++) {
            handler.googleCalendarReminderDecide(request(EMAIL, "c" + i));
        }
        handler.googleCalendarReminderDecide(request("", "c-bad"));
        handler.googleCalendarReminderDecide(null);

        assertNoRemoteCollaborator();
    }

    @Test
    @DisplayName("reserveSendSlot decides and reserves — it has nothing to call with")
    void reserveMakesNoCall() {
        assertEquals(NotificationService.Outcome.SENT,
                notifications.reserveSendSlot(WORKSPACE, EMAIL, "GOOGLE"));
        assertEquals(1, notifications.getCount(EMAIL), "the slot is consumed, because MA will act");

        assertNoRemoteCollaborator();
    }

    /**
     * The structural form of "no HTTP call was made", and strictly stronger than verifying a mock:
     * it asserts there is no collaborator to make one WITH.
     *
     * <p>{@code NotificationService} is rate-limit bookkeeping and nothing else — its only fields
     * are the send history and the clock. A field of any other type is how the blocking callback
     * would come back: somebody re-injects a sender "just for the admin path", and the reminder
     * loop is once again making a remote call from inside a JMS consumer thread.
     */
    private static void assertNoRemoteCollaborator() {
        for (java.lang.reflect.Field field : NotificationService.class.getDeclaredFields()) {
            if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            assertTrue(type == java.util.concurrent.ConcurrentHashMap.class
                            || type == java.util.function.LongSupplier.class,
                    "NotificationService gained the field '" + field.getName() + "' of type "
                            + type.getName() + "; it must hold no collaborator it could send with");
        }
    }
}
