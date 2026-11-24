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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarSyncTelemetryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link CalendarReminderSendListener} (Google variant).
 *
 * <p>This listener is a DRAIN. The reminder loop is a request/reply exchange on
 * {@code ext.calendar.reminder.decide.google} in which Meeting Agent sends the mail itself; the
 * {@code caTransport=http} branch that used to act on this legacy one-way queue went with the
 * uncommissioned {@code caTransport} attribute (owner ruling, 2026-09-11). The queue stays bound
 * because it is DURABLE and MA still falls back to producing on it when a decide request times out
 * — unconsumed, those messages would accumulate on the broker forever.
 *
 * <p>So the contract under test is a negative one, and it is the whole point: this class must hold
 * NO collaborator it could send with, and handling a message must do nothing but acknowledge. The
 * structural assertions below are not padding — the regression this guards against is somebody
 * "restoring" the send path by re-injecting {@code NotificationService}, which would double-send
 * every reminder MA had already sent off the decide reply.
 */
@DisplayName("Legacy reminder queue — drain and drop")
class CalendarReminderSendListenerTest {

    private final CalendarSyncTelemetryService telemetry = mock(CalendarSyncTelemetryService.class);
    private final CalendarReminderSendListener listener = new CalendarReminderSendListener(telemetry);

    /**
     * The ONLY collaborator types this drain may hold.
     *
     * <p>EF-08 gave it two: a counter and a metrics recorder. Neither can send anything — which is
     * the property the assertion below actually needs, and which "zero fields" was a proxy for. An
     * allow-list keeps the guard sharp where a field count would now just be a number somebody bumps.
     */
    private static final java.util.Set<Class<?>> PERMITTED_COLLABORATORS = java.util.Set.of(
            java.util.concurrent.atomic.AtomicLong.class,
            CalendarSyncTelemetryService.class);

    private static CalendarReminderSendProto event(String correlationId) {
        CalendarReminderSendProto evt = new CalendarReminderSendProto();
        evt.setWorkspaceId("ws1");
        evt.setEmail("user@example.com");
        evt.setCalendarSystem("GOOGLE");
        evt.setCorrelationId(correlationId);
        return evt;
    }

    /**
     * EDITED for EF-08. This previously asserted ZERO instance fields and a zero-argument
     * constructor. That was a proxy for the real property — "holds nothing it could send WITH" — and
     * the proxy stopped being accurate when the drain gained a discard counter and a metrics
     * recorder. Asserting the proxy would have forced the counter to be a static, which is worse
     * (shared across invoker generations) for no benefit. The allow-list states the real rule.
     */
    @Test
    @DisplayName("the listener holds NOTHING it could send a reminder with")
    void holdsNoSendingCollaborator() {
        for (java.lang.reflect.Field field : CalendarReminderSendListener.class.getDeclaredFields()) {
            if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertTrue(PERMITTED_COLLABORATORS.contains(field.getType()),
                    "field '" + field.getName() + "' of type " + field.getType().getName()
                            + " is new state on a drain. If it can send, deliver or enqueue anything,"
                            + " it double-sends every reminder MA already sent off the decide reply."
                            + " If it genuinely cannot, add it to PERMITTED_COLLABORATORS with the"
                            + " reason.");
        }

        assertEquals(1, CalendarReminderSendListener.class.getDeclaredConstructors().length);
        for (Class<?> parameter :
                CalendarReminderSendListener.class.getDeclaredConstructors()[0].getParameterTypes()) {
            assertTrue(PERMITTED_COLLABORATORS.contains(parameter),
                    "an injected " + parameter.getName() + " could be something to send WITH");
        }
    }

    @Test
    @DisplayName("EF-08: every discard is counted, so 'the decide consumer is not answering' is "
            + "visible rather than one WARN among many")
    void discardsAreCounted() {
        // One discarded reminder reads as a blip. A running total is what distinguishes a decide
        // timeout from a decide consumer that is not running at all - which is the state that
        // destroyed 100% of reconnect reminders and left no trace anywhere.
        assertEquals(0, listener.discardedCount());

        listener.handle(event("corr-1"));
        listener.handle(event("corr-2"));

        assertEquals(2, listener.discardedCount());
        verify(telemetry, times(2)).recordApiCall("ma", "reminder_send_discarded", false);
    }

    @Test
    @DisplayName("a null event is not counted as a discarded reminder")
    void nullIsNotADiscardedReminder() {
        listener.handle(null);

        assertEquals(0, listener.discardedCount(),
                "a malformed message is not a reminder somebody did not receive; counting it would "
                        + "inflate the one number an operator uses to decide the decide path is down");
        verifyNoInteractions(telemetry);
    }

    @Test
    @DisplayName("an unavailable metrics backend still acknowledges the message")
    void metricsFailureDoesNotRedeliver() {
        // This listener's whole job is to ACK so a durable queue drains. Letting a metrics fault
        // propagate would turn every discard into a redelivery loop on that queue.
        org.mockito.Mockito.doThrow(new IllegalStateException("metrics down"))
                .when(telemetry).recordApiCall(anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());

        assertDoesNotThrow(() -> listener.handle(event("corr-1")));
        assertEquals(1, listener.discardedCount());
    }

    @Test
    @DisplayName("handle returns nothing and reports no outcome — there is no send to report on")
    void handleIsVoid() {
        Method handle = Arrays.stream(CalendarReminderSendListener.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("handle"))
                .findFirst()
                .orElseThrow();

        assertEquals(void.class, handle.getReturnType(),
                "a return value would imply the caller can act on an outcome; it cannot");
    }

    @Test
    @DisplayName("a message is acknowledged, not acted on")
    void acceptsAndDiscards() {
        assertDoesNotThrow(() -> listener.handle(event("corr-1")));
    }

    @Test
    @DisplayName("the SAME correlationId is accepted again — dedup belongs to the decide consumer")
    void doesNotDedup() {
        // Deliberately NOT deduped here. Every message on this queue is discarded, so a second copy
        // costs nothing; consuming a dedup slot would instead make the decide consumer treat MA's
        // real request as a duplicate and skip a reminder the user needed.
        assertDoesNotThrow(() -> {
            listener.handle(event("corr-repeat"));
            listener.handle(event("corr-repeat"));
        });
    }

    @Test
    @DisplayName("a null event, and an event with nothing populated, are both no-ops")
    void toleratesEmptyInput() {
        assertAll(
                () -> assertDoesNotThrow(() -> listener.handle(null)),
                () -> assertDoesNotThrow(() -> listener.handle(new CalendarReminderSendProto())),
                () -> assertDoesNotThrow(() -> listener.handle(event(null))));
    }

    @Test
    @DisplayName("the listener no longer references NotificationService at all")
    void noNotificationServiceReference() {
        // The class-level guarantee behind every assertion above, stated once against the compiled
        // form: no method on this class can take, return or construct the rate-limited sender.
        boolean mentionsNotificationService = Arrays.stream(
                        CalendarReminderSendListener.class.getDeclaredMethods())
                .anyMatch(method -> method.getReturnType().getName().contains("NotificationService")
                        || Arrays.stream(method.getParameterTypes())
                                .anyMatch(type -> type.getName().contains("NotificationService")));

        assertFalse(mentionsNotificationService,
                "re-introducing NotificationService here double-sends every reminder MA already"
                        + " sent off the decide reply");
    }
}
