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

import app.krista.conversationagent.module.calendar.esb.CalendarSeatRegisteredCommandProto;
import app.krista.extension.executor.Invoker;
import app.krista.infra.esb.ServiceBusConfig;
import app.krista.infra.esb.service.ApiDefinition;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.GoogleExtensionConfig;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarSyncTelemetryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.SendEventsToMAService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarWatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KIM-474 / EF-14 — the licensed-seat notification consumer.
 *
 * <h2>What was broken</h2>
 *
 * <p>Meeting Agent has been dispatching a seat-registration event to
 * {@code ext.calendar.seat.registered.google} for every bound Google workspace, and this extension had
 * no consumer at all. The queue is durable, so the events accumulated on the broker with nothing
 * reading them, and a newly-licensed Google user stayed invisible to this extension until the next
 * scheduled roster refresh — up to six hours, against fifteen minutes on the Teams side.
 *
 * <h2>What this class must get right</h2>
 *
 * <p>Three properties, each of which fails in a way that is hard to see from outside:
 * <ul>
 *   <li>a FOREIGN workspace's event is ignored and reported, not acted on (teams-transcribe does not
 *       make this comparison — S-20);</li>
 *   <li>NOTHING escapes, including an {@code Error} — the queue is durable and manual-ack, so an
 *       escaping throwable redelivers the same event against the same broken conditions forever
 *       (teams-transcribe catches only {@code RuntimeException} — EF-27);</li>
 *   <li>an opt-in workspace does no work at all, so the fast path cannot widen capture.</li>
 * </ul>
 */
@DisplayName("Seat-registered consumer (KIM-474 / EF-14)")
class GoogleCalendarSeatRegisteredHandlerTest {

    private static final String OWN_WORKSPACE = "ws-gcal-1";

    private CalendarWatchService watchService;
    private SendEventsToMAService sendEventsToMAService;
    private CalendarDiscoveryService discoveryService;
    private CalendarSyncTelemetryService telemetryService;
    private Invoker invoker;
    private GoogleCalendarSeatRegisteredHandler handler;

    @BeforeEach
    void setUp() {
        watchService = mock(CalendarWatchService.class);
        sendEventsToMAService = mock(SendEventsToMAService.class);
        discoveryService = mock(CalendarDiscoveryService.class);
        telemetryService = mock(CalendarSyncTelemetryService.class);
        invoker = mock(Invoker.class);
        lenient().when(invoker.getWorkspaceId()).thenReturn(OWN_WORKSPACE);
        lenient().when(watchService.reconcileRegisteredSeatsNow()).thenReturn(true);
        lenient().when(discoveryService.isSyncable(anyString())).thenReturn(true);
        handler = new GoogleCalendarSeatRegisteredHandler(watchService, sendEventsToMAService,
                discoveryService, telemetryService, invoker);
    }

    /** An event that asks for a back-fill. */
    private static CalendarSeatRegisteredCommandProto backFillEvent(String workspaceId,
            Boolean sync, Integer days) {
        CalendarSeatRegisteredCommandProto command = event(workspaceId);
        command.setSyncCalendar(sync);
        command.setSyncDays(days);
        return command;
    }

    private static CalendarSeatRegisteredCommandProto event(String workspaceId) {
        CalendarSeatRegisteredCommandProto command = new CalendarSeatRegisteredCommandProto();
        command.setWorkspaceId(workspaceId);
        command.setUserEmail("newseat@acme.example");
        command.setReason("REGISTERED");
        command.setCorrelationId("corr-1");
        return command;
    }

    // ── the happy path ────────────────────────────────────────────────────

    @Test
    @DisplayName("an event for this workspace reconciles the seat scan set immediately")
    void reconcilesOnOurOwnEvent() {
        handler.googleCalendarSeatRegistered(event(OWN_WORKSPACE));

        verify(watchService).reconcileRegisteredSeatsNow();
        verify(telemetryService).recordApiCall("ma", "seat_registered_reconciled", true);
    }

    @Test
    @DisplayName("an opt-in workspace records that it did nothing, rather than nothing at all")
    void optInWorkspaceIsRecorded() {
        // reconcileRegisteredSeatsNow returns false when Always On is off. The distinction matters on
        // the metric: "we were told about a seat and deliberately did nothing" is a different fact
        // from "we were never told", and only one of them is a reason to look at the producer.
        when(watchService.reconcileRegisteredSeatsNow()).thenReturn(false);

        handler.googleCalendarSeatRegistered(event(OWN_WORKSPACE));

        verify(telemetryService).recordApiCall("ma", "seat_registered_skipped_not_always_on", true);
    }

    /**
     * Meeting Agent populates {@code workspaceId} today. If it ever stops, refusing the event would
     * silently disable the fast path — a worse outcome than one redundant roster refresh, since the
     * scheduled reconcile is the guarantee either way.
     */
    @ParameterizedTest(name = "workspaceId = \"{0}\"")
    @ValueSource(strings = {"", "   "})
    @DisplayName("an unattributed event is acted on rather than dropped")
    void unattributedEventIsActedOn(String workspaceId) {
        handler.googleCalendarSeatRegistered(event(workspaceId));

        verify(watchService).reconcileRegisteredSeatsNow();
    }

    @Test
    @DisplayName("an invoker that cannot report its own workspace still acts on the event")
    void unknownOwnWorkspaceStillActs() {
        when(invoker.getWorkspaceId()).thenThrow(new IllegalStateException("no request context"));

        handler.googleCalendarSeatRegistered(event(OWN_WORKSPACE));

        verify(watchService).reconcileRegisteredSeatsNow();
    }

    // ── isolation ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a FOREIGN workspace's event is ignored, reported, and acknowledged")
    void foreignEventIsIgnored() {
        handler.googleCalendarSeatRegistered(event("ws-somebody-else"));

        verify(watchService, never()).reconcileRegisteredSeatsNow();
        verify(telemetryService).recordApiCall("ma", "seat_registered_ignored_foreign_workspace", true);
    }

    @Test
    @DisplayName("workspace matching ignores case and surrounding whitespace")
    void workspaceMatchingIsForgiving() {
        handler.googleCalendarSeatRegistered(event("  WS-GCAL-1  "));

        verify(watchService).reconcileRegisteredSeatsNow();
    }

    // ── nothing escapes ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Nothing escapes onto a durable manual-ack queue")
    class NothingEscapes {

        @Test
        @DisplayName("a failed reconcile is acknowledged, not rethrown")
        void reconcileFailureIsSwallowed() {
            when(watchService.reconcileRegisteredSeatsNow())
                    .thenThrow(new IllegalStateException("Meeting Agent unreachable"));

            assertDoesNotThrow(() -> handler.googleCalendarSeatRegistered(event(OWN_WORKSPACE)));

            verify(telemetryService).recordApiCall("ma", "seat_registered_reconcile_failed", false);
        }

        /**
         * EF-27. An {@code Error} redelivers exactly like an exception on this queue, and the realistic
         * one — a {@code NoClassDefFoundError} from a mis-staged contract jar — fails identically on
         * every redelivery. Catching only {@code RuntimeException} turns that into an unbounded loop.
         */
        @Test
        @DisplayName("an Error is caught too, so a mis-staged jar cannot loop forever")
        void errorsAreCaught() {
            when(watchService.reconcileRegisteredSeatsNow())
                    .thenThrow(new NoClassDefFoundError(
                            "app/krista/conversationagent/module/calendar/esb/"
                                    + "CalendarSeatRegisteredCommandProto"));

            assertDoesNotThrow(() -> handler.googleCalendarSeatRegistered(event(OWN_WORKSPACE)),
                    "an escaping Error redelivers on a durable manual-ack queue against conditions "
                            + "that will fail every time");
        }

        @Test
        @DisplayName("an unavailable metrics backend does not fail the handler")
        void metricsFailureIsSurvivable() {
            doThrow(new IllegalStateException("metrics down"))
                    .when(telemetryService).recordApiCall(anyString(), anyString(), anyBoolean());

            assertDoesNotThrow(() -> handler.googleCalendarSeatRegistered(event(OWN_WORKSPACE)));
            verify(watchService).reconcileRegisteredSeatsNow();
        }

        @Test
        @DisplayName("a null event is acknowledged")
        void nullEventIsSurvivable() {
            assertDoesNotThrow(() -> handler.googleCalendarSeatRegistered(null));
            verify(watchService, never()).reconcileRegisteredSeatsNow();
        }
    }

    // ── the wire contract ─────────────────────────────────────────────────

    /**
     * The destination and the API name are what Meeting Agent produces to. Both are written on
     * Meeting Agent's side as well, and nothing derives one from the other across the repo boundary —
     * a mismatch binds a queue nobody writes to, or registers a handler no message addresses, and
     * neither errors.
     */
    @Nested
    @DisplayName("The wire contract Meeting Agent produces to")
    class WireContract {

        @Test
        @DisplayName("the destination is ext.calendar.seat.registered.google")
        void destinationMatchesMeetingAgent() {
            assertEquals("ext.calendar.seat.registered.google",
                    GoogleExtensionConfig.QUEUE_CALENDAR_SEAT_REGISTERED,
                    "Meeting Agent dispatches per BOUND calendar system. The .google suffix is what "
                            + "keeps a Google workspace's events off the .microsoft sibling.");
            assertTrue(GoogleExtensionConfig.QUEUE_CALENDAR_SEAT_REGISTERED.startsWith("ext."),
                    "this extension is the CONSUMER, which is the ext.* family (spec §3.3)");
        }

        /**
         * Meeting Agent's {@code esb_api} for this event is {@code "google" + "CalendarSeatRegistered"}
         * ({@code CalendarEsbApiNames.seatRegistered(GOOGLE)}). The interface method name and the
         * definition's map key must both equal it.
         */
        @Test
        @DisplayName("the API name matches the one Meeting Agent puts on the message")
        void apiNameMatchesMeetingAgent() throws Exception {
            String apiName = "googleCalendarSeatRegistered";

            Method declared = GoogleCalendarSeatRegisteredService.class
                    .getMethod(apiName, CalendarSeatRegisteredCommandProto.class);
            assertEquals(void.class, declared.getReturnType(),
                    "the event is fire-and-forget; a reply would make MA's seat registration depend "
                            + "on this extension being reachable");

            ApiDefinition definition =
                    new GoogleCalendarSeatRegisteredServiceDefinition().getApiDefinition(apiName);
            assertNotNull(definition,
                    "krista-infra dispatches by the API name on the message — an unmapped name is "
                            + "silently undelivered");
            assertEquals(CalendarSeatRegisteredCommandProto.class, definition.requestDTOClass());
            assertEquals(null, definition.replyDTOClass(), "one-way: there is no reply type");
        }

        @Test
        @DisplayName("the queue is durable, so a restart does not drop Meeting Agent's only push signal")
        void queueIsDurable() {
            ServiceBusConfig config = GoogleCalendarSeatRegisteredServiceDefinition.config();

            assertTrue(config.isQueueDurable(),
                    "dropping the event on a restart puts the seat back on the scheduled-refresh "
                            + "window, which is the delay this consumer exists to remove");
            assertFalse(config.isProcessorAutoAcknowledge());
            assertEquals(GoogleCalendarSeatRegisteredServiceDefinition.identity().baseDestination(),
                    config.destinationBaseName());
        }
    }

    // ── back-fill (syncCalendar / syncDays) ───────────────────────────────
    //
    // A watch channel only delivers on CHANGE, so a meeting already sitting on a newly seated user's
    // calendar that nobody edits is never seen. createWatchChannel even walks the calendar to get a
    // baseline sync token — and keeps only the token. These tests pin the decisions that make the
    // back-fill close that gap rather than appear to.

    @Nested
    @DisplayName("back-fill")
    class BackFill {

        @Test
        @DisplayName("no back-fill is requested — the calendar is never read")
        void absentRequestDoesNotSync() {
            // The pre-existing behaviour, and what an event from a Meeting Agent that predates the
            // field looks like on the wire. A back-fill is a Google read per seat; only on request.
            handler.googleCalendarSeatRegistered(backFillEvent(OWN_WORKSPACE, null, null));

            verify(sendEventsToMAService, never()).sendEventsToMAByUser(anyString(), anyLong());
            verify(watchService).reconcileRegisteredSeatsNow();
        }

        @Test
        @DisplayName("an explicit false is not a back-fill either")
        void explicitFalseDoesNotSync() {
            handler.googleCalendarSeatRegistered(backFillEvent(OWN_WORKSPACE, Boolean.FALSE, 30));

            verify(sendEventsToMAService, never()).sendEventsToMAByUser(anyString(), anyLong());
        }

        @Test
        @DisplayName("a requested back-fill reads THIS event's calendar over the requested window")
        void requestedBackFillSyncsTheUser() {
            handler.googleCalendarSeatRegistered(backFillEvent(OWN_WORKSPACE, Boolean.TRUE, 14));

            verify(sendEventsToMAService).sendEventsToMAByUser("newseat@acme.example", 14L);
        }

        @Test
        @DisplayName("a failing back-fill is ACKNOWLEDGED — the handler returns rather than rethrowing")
        void failingBackFillIsAcknowledged() {
            // Same durable manual-ack reasoning as the reconcile: rethrowing redelivers into the same
            // failure forever.
            doThrow(new RuntimeException("quota exhausted"))
                    .when(sendEventsToMAService).sendEventsToMAByUser(anyString(), anyLong());

            assertDoesNotThrow(() -> handler.googleCalendarSeatRegistered(
                    backFillEvent(OWN_WORKSPACE, Boolean.TRUE, 30)));

            verify(telemetryService).recordApiCall("ma", "seat_registered_backfill_failed", false);
        }

        @Test
        @DisplayName("an Error in the back-fill is acknowledged too — a mis-staged jar must not loop")
        void errorInBackFillIsAcknowledged() {
            doThrow(new NoClassDefFoundError("contract jar"))
                    .when(sendEventsToMAService).sendEventsToMAByUser(anyString(), anyLong());

            assertDoesNotThrow(() -> handler.googleCalendarSeatRegistered(
                    backFillEvent(OWN_WORKSPACE, Boolean.TRUE, 30)));
        }

        @Test
        @DisplayName("a failing RECONCILE still lets the back-fill run")
        void reconcileFailureDoesNotSuppressBackFill() {
            // The two halves are separate Google operations and fail independently. A channel call
            // that fails must not also cost the seat its back-fill — especially as the reconcile has
            // a scheduled retry behind it and the back-fill has none.
            doThrow(new IllegalStateException("google down"))
                    .when(watchService).reconcileRegisteredSeatsNow();

            handler.googleCalendarSeatRegistered(backFillEvent(OWN_WORKSPACE, Boolean.TRUE, 30));

            verify(sendEventsToMAService).sendEventsToMAByUser("newseat@acme.example", 30L);
        }

        @Test
        @DisplayName("a non-syncable user is reported, not silently treated as an empty calendar")
        void nonSyncableUserIsReported() {
            // sendEventsToMAByUser returns VOID and returns quietly for a user it cannot reach, which
            // is indistinguishable from a successful back-fill of an empty diary. Asking first is what
            // makes the difference visible in the log.
            when(discoveryService.isSyncable("newseat@acme.example")).thenReturn(false);

            handler.googleCalendarSeatRegistered(backFillEvent(OWN_WORKSPACE, Boolean.TRUE, 30));

            verify(sendEventsToMAService, never()).sendEventsToMAByUser(anyString(), anyLong());
            verify(telemetryService).recordApiCall("ma", "seat_registered_backfill_not_syncable", false);
        }

        @Test
        @DisplayName("a back-fill with no userEmail reads nothing rather than guessing a calendar")
        void backFillWithoutEmailReadsNothing() {
            CalendarSeatRegisteredCommandProto command =
                    backFillEvent(OWN_WORKSPACE, Boolean.TRUE, 30);
            command.setUserEmail("   ");

            handler.googleCalendarSeatRegistered(command);

            verify(sendEventsToMAService, never()).sendEventsToMAByUser(anyString(), anyLong());
        }

        @Test
        @DisplayName("an out-of-range window is clamped, never allowed to cost the seat its back-fill")
        void outOfRangeWindowIsClamped() {
            // Meeting Agent already 422s these. Clamped anyway because the producer is a separately
            // deployed service across a wire contract, and a window this extension will not serve is
            // better turned into a sane one than into an exception that skips the back-fill.
            handler.googleCalendarSeatRegistered(backFillEvent(OWN_WORKSPACE, Boolean.TRUE, 10_000));
            verify(sendEventsToMAService).sendEventsToMAByUser("newseat@acme.example",
                    GoogleCalendarSeatRegisteredHandler.MAX_BACK_FILL_DAYS);

            handler.googleCalendarSeatRegistered(backFillEvent(OWN_WORKSPACE, Boolean.TRUE, 0));
            verify(sendEventsToMAService).sendEventsToMAByUser("newseat@acme.example",
                    GoogleCalendarSeatRegisteredHandler.DEFAULT_BACK_FILL_DAYS);
        }

        @Test
        @DisplayName("a foreign-workspace event never back-fills — the calendar is another tenant's")
        void foreignWorkspaceNeverBackFills() {
            // The isolation argument that applies to reconciling applies with more force here: this
            // would read another tenant's calendar on another tenant's signal.
            handler.googleCalendarSeatRegistered(backFillEvent("ws-someone-else", Boolean.TRUE, 30));

            verify(sendEventsToMAService, never()).sendEventsToMAByUser(anyString(), anyLong());
            verify(watchService, never()).reconcileRegisteredSeatsNow();
        }
    }
}
