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

import app.krista.conversationagent.module.calendar.esb.CalendarWorkspaceMessagesProto;
import app.krista.extension.executor.Invoker;
import app.krista.infra.esb.ServiceBusConfig;
import app.krista.infra.esb.service.ApiDefinition;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.GoogleExtensionConfig;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarSyncTelemetryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.WorkspaceMessagesStore;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KIM-492 — the in-call messages consumer.
 *
 * <h2>What this closes</h2>
 *
 * <p>Meeting Agent broadcasts a workspace's two in-call messages — the standard Krista welcome and the
 * customer's compliance notice — to {@code ext.calendar.workspace.messages.google} on every admin save
 * of the messages settings and for every workspace at its own boot. Google Meet gives this extension no
 * in-call chat surface (the Krista Bot presents both from its {@code BOT_CONFIG}), so there is nothing
 * to render — but the destination is DURABLE and addressed per bound calendar system, so with no
 * consumer every Google-bound workspace's broadcasts accumulate on the broker indefinitely.
 *
 * <h2>What this class must get right</h2>
 *
 * <ul>
 *   <li>a FOREIGN workspace's broadcast is ignored and reported, never stored — the failure it prevents
 *       is one customer's legal copy landing in another customer's store, which a successful write
 *       cannot distinguish itself from;</li>
 *   <li>an EMPTY field CLEARS. Meeting Agent dispatches a withdrawn compliance notice as an empty one on
 *       purpose, and "no change" is the one reading that keeps presenting copy a customer has
 *       retracted;</li>
 *   <li>NOTHING escapes, including an {@code Error} — the queue is durable and manual-ack, so an
 *       escaping throwable redelivers the same broadcast against the same fault forever, on a queue
 *       whose whole purpose here is to be drained;</li>
 *   <li>the message TEXT never reaches a log line.</li>
 * </ul>
 */
@DisplayName("In-call messages consumer (KIM-492)")
class GoogleCalendarWorkspaceMessagesHandlerTest {

    private static final String OWN_WORKSPACE = "ws-gcal-1";
    private static final String WELCOME = "Krista is taking notes for this meeting.";
    private static final String COMPLIANCE = "Acme records meetings for quality assurance.";

    private WorkspaceMessagesStore messagesStore;
    private CalendarSyncTelemetryService telemetryService;
    private Invoker invoker;
    private GoogleCalendarWorkspaceMessagesHandler handler;

    @BeforeEach
    void setUp() {
        messagesStore = mock(WorkspaceMessagesStore.class);
        telemetryService = mock(CalendarSyncTelemetryService.class);
        invoker = mock(Invoker.class);
        lenient().when(invoker.getWorkspaceId()).thenReturn(OWN_WORKSPACE);
        lenient().when(messagesStore.store(anyString(), anyString()))
                .thenAnswer(call -> new WorkspaceMessagesStore.Messages(
                        call.getArgument(0), call.getArgument(1), 1_700_000_000_000L));
        handler = new GoogleCalendarWorkspaceMessagesHandler(
                messagesStore, telemetryService, invoker);
    }

    private static CalendarWorkspaceMessagesProto broadcast(String workspaceId,
                                                            String welcome,
                                                            String compliance) {
        CalendarWorkspaceMessagesProto command = new CalendarWorkspaceMessagesProto();
        command.setWorkspaceId(workspaceId);
        command.setWelcomeMessage(welcome);
        command.setComplianceNotice(compliance);
        command.setCorrelationId("corr-1");
        command.setEmittedAt("2026-09-13T10:15:30Z");
        return command;
    }

    // ── the happy path ────────────────────────────────────────────────────

    @Test
    @DisplayName("a broadcast for this workspace is stored verbatim")
    void storesOurOwnBroadcast() {
        handler.googleCalendarWorkspaceMessages(broadcast(OWN_WORKSPACE, WELCOME, COMPLIANCE));

        verify(messagesStore).store(WELCOME, COMPLIANCE);
        verify(telemetryService).recordApiCall("ma", "workspace_messages_stored", true);
    }

    /**
     * The whole point of consuming a queue this extension cannot present from: Meeting Agent's DELETE
     * arrives as an EMPTY field, and the handler must hand it straight to the store rather than
     * suppressing it as "nothing to do". Suppression is what keeps presenting a compliance notice the
     * customer has withdrawn.
     */
    @ParameterizedTest(name = "complianceNotice = \"{0}\"")
    @ValueSource(strings = {"", "   "})
    @DisplayName("an EMPTY compliance notice is passed through as a CLEAR, not skipped")
    void emptyComplianceNoticeClears(String cleared) {
        when(messagesStore.store(WELCOME, cleared))
                .thenReturn(new WorkspaceMessagesStore.Messages(WELCOME, null, 1L));

        handler.googleCalendarWorkspaceMessages(broadcast(OWN_WORKSPACE, WELCOME, cleared));

        verify(messagesStore).store(WELCOME, cleared);
        verify(telemetryService).recordApiCall("ma", "workspace_messages_stored", true);
    }

    @Test
    @DisplayName("a broadcast that clears BOTH messages is recorded distinctly from one that sets them")
    void clearingBothIsRecordedDistinctly() {
        // "the tenant now presents nothing" and "the tenant presents two messages" are different
        // facts, and only one of them is a reason to look at the Settings card.
        when(messagesStore.store("", ""))
                .thenReturn(new WorkspaceMessagesStore.Messages(null, null, 1L));

        handler.googleCalendarWorkspaceMessages(broadcast(OWN_WORKSPACE, "", ""));

        verify(messagesStore).store("", "");
        verify(telemetryService).recordApiCall("ma", "workspace_messages_stored_cleared", true);
    }

    /**
     * Meeting Agent populates {@code workspaceId} today. If it ever stops, refusing the broadcast would
     * freeze this store on whatever it last held, with nothing anywhere to re-send it — a worse outcome
     * than storing an unattributed broadcast on a single-tenant pod.
     */
    @ParameterizedTest(name = "workspaceId = \"{0}\"")
    @ValueSource(strings = {"", "   "})
    @DisplayName("an unattributed broadcast is stored rather than dropped")
    void unattributedBroadcastIsStored(String workspaceId) {
        handler.googleCalendarWorkspaceMessages(broadcast(workspaceId, WELCOME, COMPLIANCE));

        verify(messagesStore).store(WELCOME, COMPLIANCE);
    }

    @Test
    @DisplayName("an invoker that cannot report its own workspace still stores the broadcast")
    void unknownOwnWorkspaceStillStores() {
        when(invoker.getWorkspaceId()).thenThrow(new IllegalStateException("no request context"));

        handler.googleCalendarWorkspaceMessages(broadcast(OWN_WORKSPACE, WELCOME, COMPLIANCE));

        verify(messagesStore).store(WELCOME, COMPLIANCE);
    }

    // ── isolation ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a FOREIGN workspace's broadcast is ignored, reported, and acknowledged")
    void foreignBroadcastIsIgnored() {
        handler.googleCalendarWorkspaceMessages(
                broadcast("ws-somebody-else", "their welcome", "their legal notice"));

        verify(messagesStore, never()).store(anyString(), anyString());
        verify(telemetryService)
                .recordApiCall("ma", "workspace_messages_ignored_foreign_workspace", true);
    }

    @Test
    @DisplayName("workspace matching ignores case and surrounding whitespace")
    void workspaceMatchingIsForgiving() {
        handler.googleCalendarWorkspaceMessages(broadcast("  WS-GCAL-1  ", WELCOME, COMPLIANCE));

        verify(messagesStore).store(WELCOME, COMPLIANCE);
    }

    // ── nothing escapes ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Nothing escapes onto a durable manual-ack queue")
    class NothingEscapes {

        @Test
        @DisplayName("a failed store is acknowledged, not rethrown")
        void storeFailureIsSwallowed() {
            when(messagesStore.store(anyString(), anyString()))
                    .thenThrow(new IllegalStateException("key-value store unavailable"));

            assertDoesNotThrow(() -> handler.googleCalendarWorkspaceMessages(
                    broadcast(OWN_WORKSPACE, WELCOME, COMPLIANCE)));

            verify(telemetryService).recordApiCall("ma", "workspace_messages_store_failed", false);
        }

        /**
         * An {@code Error} redelivers exactly like an exception on this queue, and the realistic one — a
         * {@code NoClassDefFoundError} from a mis-staged contract jar — fails identically on every
         * redelivery. Catching only {@code RuntimeException} turns a drain into an unbounded loop.
         */
        @Test
        @DisplayName("an Error is caught too, so a mis-staged jar cannot loop forever")
        void errorsAreCaught() {
            when(messagesStore.store(anyString(), anyString()))
                    .thenThrow(new NoClassDefFoundError(
                            "app/krista/conversationagent/module/calendar/esb/"
                                    + "CalendarWorkspaceMessagesProto"));

            assertDoesNotThrow(() -> handler.googleCalendarWorkspaceMessages(
                            broadcast(OWN_WORKSPACE, WELCOME, COMPLIANCE)),
                    "an escaping Error redelivers on a durable manual-ack queue against conditions "
                            + "that will fail every time");
        }

        @Test
        @DisplayName("an unavailable metrics backend does not fail the handler")
        void metricsFailureIsSurvivable() {
            doThrow(new IllegalStateException("metrics down"))
                    .when(telemetryService).recordApiCall(anyString(), anyString(), anyBoolean());

            assertDoesNotThrow(() -> handler.googleCalendarWorkspaceMessages(
                    broadcast(OWN_WORKSPACE, WELCOME, COMPLIANCE)));
            verify(messagesStore).store(WELCOME, COMPLIANCE);
        }

        @Test
        @DisplayName("a null broadcast is acknowledged")
        void nullBroadcastIsSurvivable() {
            assertDoesNotThrow(() -> handler.googleCalendarWorkspaceMessages(null));
            verify(messagesStore, never()).store(any(), any());
        }
    }

    // ── the wire contract ─────────────────────────────────────────────────

    /**
     * The destination and the API name are what Meeting Agent produces to. Both are written on Meeting
     * Agent's side as well, and nothing derives one from the other across the repo boundary — a mismatch
     * binds a queue nobody writes to, or registers a handler no message addresses, and neither errors.
     */
    @Nested
    @DisplayName("The wire contract Meeting Agent produces to")
    class WireContract {

        @Test
        @DisplayName("the destination is ext.calendar.workspace.messages.google")
        void destinationMatchesMeetingAgent() {
            assertEquals("ext.calendar.workspace.messages.google",
                    GoogleExtensionConfig.QUEUE_CALENDAR_WORKSPACE_MESSAGES,
                    "Meeting Agent broadcasts per BOUND calendar system"
                            + " (CalendarEsbApiNames.workspaceMessagesQueue). The .google suffix is"
                            + " what keeps a Google workspace's broadcasts off the .microsoft"
                            + " sibling, where nothing would ever drain them.");
            assertTrue(GoogleExtensionConfig.QUEUE_CALENDAR_WORKSPACE_MESSAGES.startsWith("ext."),
                    "this extension is the CONSUMER, which is the ext.* family (spec §3.3)");
        }

        /**
         * Meeting Agent's {@code esb_api} for this broadcast is
         * {@code "google" + "CalendarWorkspaceMessages"}
         * ({@code CalendarEsbApiNames.workspaceMessages(GOOGLE)}). The interface method name and the
         * definition's map key must both equal it.
         */
        @Test
        @DisplayName("the API name matches the one Meeting Agent puts on the message")
        void apiNameMatchesMeetingAgent() throws Exception {
            String apiName = "googleCalendarWorkspaceMessages";

            Method declared = GoogleCalendarWorkspaceMessagesService.class
                    .getMethod(apiName, CalendarWorkspaceMessagesProto.class);
            assertEquals(void.class, declared.getReturnType(),
                    "the broadcast is fire-and-forget; a reply would make an admin's settings save "
                            + "depend on this extension being reachable");

            ApiDefinition definition =
                    new GoogleCalendarWorkspaceMessagesServiceDefinition().getApiDefinition(apiName);
            assertNotNull(definition,
                    "krista-infra dispatches by the API name on the message — an unmapped name is "
                            + "silently undelivered, and the durable queue then never drains");
            assertEquals(CalendarWorkspaceMessagesProto.class, definition.requestDTOClass());
            assertEquals(null, definition.replyDTOClass(), "one-way: there is no reply type");
        }

        @Test
        @DisplayName("the queue is durable, so a restart does not drop a settings save")
        void queueIsDurable() {
            ServiceBusConfig config = GoogleCalendarWorkspaceMessagesServiceDefinition.config();

            assertTrue(config.isQueueDurable(),
                    "nothing else re-sends a broadcast: dropping one leaves a withdrawn compliance "
                            + "notice in force until the next admin save, which may be never");
            assertFalse(config.isProcessorAutoAcknowledge());
            assertEquals(
                    GoogleCalendarWorkspaceMessagesServiceDefinition.identity().baseDestination(),
                    config.destinationBaseName());
        }
    }
}
