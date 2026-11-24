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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook;

import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.TrackMeetingEventStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleEventToMeetingDtoMapper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.MeetingKeyResolver;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The webhook notification handler — previously untested (teams-transcribe has a counterpart; this
 * had none).
 *
 * <h2>Why it is worth covering now</h2>
 *
 * <p>EF-34 reported {@code MeetingKeyResolver} as an unused injection here. An unused collaborator is
 * normally clutter; this one was a behaviour gap with its own fix sitting next to it. A cancellation
 * whose key was in neither local store was <b>abandoned</b> — logged and returned — so a meeting that
 * reached Meeting Agent by another path, or whose local mapping had been evicted, stayed on the
 * dashboard forever. The batch-sync path has asked Meeting Agent since WP-E16 (spec §6 R14); the
 * webhook did not, and the webhook is the path most cancellations arrive on.
 *
 * <p>The cancellation path is also where EF-12 bites: Meeting Agent applies {@code userEmail} as a
 * hard filter, so the lookup has to be scoped by the mailbox owner's address and never by the
 * scan-set user id, which on the consented path can be an opaque {@code wsContact_} key.
 */
@DisplayName("Webhook notification handler")
class CalendarNotificationHandlerTest {

    private static final String USER_ID = "wsContact_7f3c1e2a-9b4d-4a10-8f55-1c2d3e4f5a6b";
    private static final String OWNER_ADDRESS = "client.user@gmail.example";
    private static final String INVOKER_ID = "INV_google_calendar";
    private static final String EVENT_ID = "evt-1";
    private static final String ICAL = "ical-cancelled@google.com";
    private static final String MEETING_URL = "https://meet.google.com/abc-defg-hij";

    /**
     * Answers the two calls the handler makes through the real Google client: {@code calendars.get}
     * for the primary calendar id, and {@code events.list} for the incremental sync.
     */
    private static final class SyncTransport extends MockHttpTransport {

        private final String eventsJson;
        private final List<String> requestedUrls = new ArrayList<>();

        private SyncTransport(String eventsJson) {
            this.eventsJson = eventsJson;
        }

        @Override
        public LowLevelHttpRequest buildRequest(String method, String url) {
            requestedUrls.add(url);
            final String body = url.contains("/events")
                    ? eventsJson
                    : "{\"kind\":\"calendar#calendar\",\"id\":\"" + OWNER_ADDRESS + "\"}";
            return new MockLowLevelHttpRequest() {
                @Override
                public LowLevelHttpResponse execute() throws IOException {
                    return new MockLowLevelHttpResponse()
                            .setStatusCode(200)
                            .setContentType("application/json")
                            .setContent(body);
                }
            };
        }
    }

    /** One cancelled event, as Google delivers it on an incremental sync. */
    private static String cancelledEventPage() {
        return "{\"kind\":\"calendar#events\",\"nextSyncToken\":\"sync-2\",\"items\":["
                + "{\"id\":\"" + EVENT_ID + "\",\"iCalUID\":\"" + ICAL + "\","
                + "\"status\":\"cancelled\",\"summary\":\"Weekly sync\","
                + "\"start\":{\"dateTime\":\"2026-09-15T10:00:00Z\"},"
                + "\"end\":{\"dateTime\":\"2026-09-15T11:00:00Z\"}}]}";
    }

    private CalendarEventToMAService maService;
    private CalendarWatchStore watchStore;
    private CalendarEventMappingStore eventMappingStore;
    private TrackMeetingEventStore trackMeetingEventStore;
    private GoogleEventToMeetingDtoMapper eventMapper;
    private MeetingKeyResolver meetingKeyResolver;
    private CalendarDiscoveryService discoveryService;
    private GoogleProvider googleProvider;
    private CalendarNotificationHandler handler;

    @BeforeEach
    void setUp() {
        GoogleProviderFactory googleProviderFactory = mock(GoogleProviderFactory.class);
        googleProvider = mock(GoogleProvider.class);
        maService = mock(CalendarEventToMAService.class);
        watchStore = mock(CalendarWatchStore.class);
        eventMappingStore = mock(CalendarEventMappingStore.class);
        trackMeetingEventStore = mock(TrackMeetingEventStore.class);
        eventMapper = mock(GoogleEventToMeetingDtoMapper.class);
        meetingKeyResolver = mock(MeetingKeyResolver.class);
        discoveryService = mock(CalendarDiscoveryService.class);
        lenient().when(discoveryService.isSyncable(anyString())).thenReturn(true);
        lenient().when(maService.deleteEventFromMA(anyString())).thenReturn(true);
        lenient().when(maService.sendEventToMA(any(), anyString(), any(), any())).thenReturn(true);
        Invoker invoker = mock(Invoker.class);

        Calendar calendar = new Calendar.Builder(new SyncTransport(cancelledEventPage()),
                GsonFactory.getDefaultInstance(), null).setApplicationName("test").build();

        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        lenient().when(googleProviderFactory.createForInvoker(INVOKER_ID)).thenReturn(googleProvider);
        lenient().when(googleProviderFactory.create()).thenReturn(googleProvider);
        lenient().when(discoveryService.sourceFor(anyString()))
                .thenReturn(CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS);
        lenient().when(discoveryService.calendarClientFor(anyString(), any())).thenReturn(calendar);
        lenient().when(googleProvider.getGoogleClientForUser(anyString())).thenReturn(calendar);
        // An existing sync token, so the handler takes the incremental path rather than a baseline.
        lenient().when(watchStore.getSyncToken(USER_ID)).thenReturn("sync-1");
        lenient().when(eventMapper.extractMeetingUrl(any())).thenReturn(MEETING_URL);
        lenient().when(eventMapper.detectPlatformFromUrl(anyString())).thenReturn("google_meet");

        handler = new CalendarNotificationHandler(googleProviderFactory, maService, watchStore,
                eventMappingStore, trackMeetingEventStore, eventMapper, meetingKeyResolver, invoker,
                mock(UserKeyIndexStore.class), discoveryService);
    }

    // ── the resourceState gate ────────────────────────────────────────────

    @Test
    @DisplayName("the initial 'sync' confirmation does no work at all")
    void syncConfirmationIsSkipped() {
        // Google sends this once when a channel is created. Treating it as a change would make every
        // channel registration trigger a full pass over that calendar.
        handler.handleNotification(USER_ID, "channel-1", "sync");

        verify(discoveryService, never()).calendarClientFor(anyString(), any());
        verify(maService, never()).deleteEventFromMA(anyString());
    }

    /**
     * Either/or: a notification handled a moment after Always On was switched on must not read the
     * calendar with the personal token it would have used a moment before.
     */
    @Test
    @DisplayName("Always On: a stale personal-token source is refused before any token is touched")
    void alwaysOnRefusesThePersonalToken() {
        when(discoveryService.isAlwaysOn()).thenReturn(true);

        assertFalse(handler.handleNotification(USER_ID, "channel-1", "exists"));

        verify(googleProvider, never()).getGoogleClientForUser(anyString());
        verify(maService, never()).deleteEventFromMA(anyString());
    }

    // ── EF-34: the cancellation path asks Meeting Agent ───────────────────

    @Nested
    @DisplayName("A cancelled event with no local mapping (EF-34)")
    class CancellationWithNoLocalMapping {

        @BeforeEach
        void noLocalKey() {
            when(eventMappingStore.get(EVENT_ID)).thenReturn(null);
            when(trackMeetingEventStore.getMeetingKey(USER_ID, EVENT_ID)).thenReturn(null);
        }

        @Test
        @DisplayName("Meeting Agent is asked, and the resolved key is deleted")
        void resolvesThroughMeetingAgentAndDeletes() {
            when(meetingKeyResolver.resolveMeetingKey(eq(ICAL), eq(MEETING_URL), any(), any()))
                    .thenReturn("mk-resolved");

            handler.handleNotification(USER_ID, "channel-1", "exists");

            verify(maService).deleteEventFromMA("mk-resolved");
            verify(eventMappingStore).remove(EVENT_ID);
            verify(trackMeetingEventStore).removeTrackedEvent(USER_ID, EVENT_ID);
        }

        /**
         * EF-12. Meeting Agent applies {@code userEmail} as a hard filter on every branch of its
         * lookup precedence, and on the consented path the scan-set id is an opaque
         * {@code wsContact_} key that matches no attendee — so passing it would make the lookup this
         * change adds find nothing, and the fix would ship inert for exactly the client users it was
         * meant to help.
         */
        @Test
        @DisplayName("the lookup is scoped by the mailbox owner's address, not the wsContact_ key")
        void lookupIsScopedByTheCalendarOwner() {
            when(meetingKeyResolver.resolveMeetingKey(anyString(), anyString(), any(), any()))
                    .thenReturn("mk-resolved");

            handler.handleNotification(USER_ID, "channel-1", "exists");

            org.mockito.ArgumentCaptor<String> userEmail =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            verify(meetingKeyResolver)
                    .resolveMeetingKey(eq(ICAL), anyString(), any(), userEmail.capture());
            assertEquals(OWNER_ADDRESS, userEmail.getValue(),
                    "the address resolved from the primary calendar is what Meeting Agent can match; "
                            + "a wsContact_ key matches no attendee anywhere");
        }

        @Test
        @DisplayName("nothing is deleted when Meeting Agent matches nothing either")
        void noDeleteWhenNothingMatches() {
            when(meetingKeyResolver.resolveMeetingKey(anyString(), anyString(), any(), any()))
                    .thenReturn(null);

            handler.handleNotification(USER_ID, "channel-1", "exists");

            verify(maService, never()).deleteEventFromMA(anyString());
        }
    }

    /**
     * The ordering that must not change: a key we recorded ourselves is stronger evidence than a
     * remote lookup, and asking Meeting Agent when we already know the answer is a round trip on the
     * hot webhook path.
     */
    @Nested
    @DisplayName("A cancelled event WITH a local mapping")
    class CancellationWithLocalMapping {

        @Test
        @DisplayName("the webhook mapping wins and Meeting Agent is not asked")
        void webhookMappingWins() {
            when(eventMappingStore.get(EVENT_ID)).thenReturn("mk-local");

            handler.handleNotification(USER_ID, "channel-1", "exists");

            verify(maService).deleteEventFromMA("mk-local");
            verify(meetingKeyResolver, never())
                    .resolveMeetingKey(anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("the batch-sync store is the second source, still ahead of Meeting Agent")
        void trackingStoreIsSecond() {
            when(eventMappingStore.get(EVENT_ID)).thenReturn(null);
            when(trackMeetingEventStore.getMeetingKey(USER_ID, EVENT_ID)).thenReturn("mk-tracked");

            handler.handleNotification(USER_ID, "channel-1", "exists");

            verify(maService).deleteEventFromMA("mk-tracked");
            verify(meetingKeyResolver, never())
                    .resolveMeetingKey(anyString(), anyString(), any(), any());
        }
    }

    // ── containment ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a failing Meeting Agent lookup costs the cancellation, not the notification")
    void lookupFailureIsContained() {
        when(eventMappingStore.get(EVENT_ID)).thenReturn(null);
        when(trackMeetingEventStore.getMeetingKey(USER_ID, EVENT_ID)).thenReturn(null);
        when(meetingKeyResolver.resolveMeetingKey(anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("Meeting Agent unreachable"));

        assertDoesNotThrow(() -> handler.handleNotification(USER_ID, "channel-1", "exists"));
        verify(maService, never()).deleteEventFromMA(anyString());
    }

    @Test
    @DisplayName("an unbuildable Google client does not propagate out of the webhook")
    void clientFailureIsContained() {
        when(discoveryService.calendarClientFor(anyString(), any()))
                .thenThrow(new IllegalStateException("no stored token"));

        assertDoesNotThrow(() -> handler.handleNotification(USER_ID, "channel-1", "exists"));
    }

    /**
     * The sync token is what makes the next notification incremental. Losing it turns every
     * subsequent notification into a full pass over the calendar.
     */
    @Test
    @DisplayName("the refreshed sync token is stored after a successful incremental sync")
    void syncTokenIsAdvanced() {
        when(eventMappingStore.get(EVENT_ID)).thenReturn("mk-local");

        handler.handleNotification(USER_ID, "channel-1", "exists");

        verify(watchStore).putSyncToken(USER_ID, "sync-2");
    }

    @Test
    void failedDeleteKeepsMappingAndCursorForRetry() {
        when(eventMappingStore.get(EVENT_ID)).thenReturn("mk-local");
        when(maService.deleteEventFromMA("mk-local")).thenReturn(false);
        org.junit.jupiter.api.Assertions.assertFalse(handler.handleNotification(USER_ID, "channel-1", "exists"));
        verify(watchStore, never()).putSyncToken(anyString(), anyString());
        verify(eventMappingStore, never()).remove(EVENT_ID);
        verify(trackMeetingEventStore, never()).removeTrackedEvent(USER_ID, EVENT_ID);
    }

    @Test
    void missingCursorProcessesEventsBeforeCommittingBaseline() {
        when(watchStore.getSyncToken(USER_ID)).thenReturn(null);
        when(eventMappingStore.get(EVENT_ID)).thenReturn("mk-local");
        assertTrue(handler.handleNotification(USER_ID, "channel-1", "exists"));
        var order = org.mockito.Mockito.inOrder(maService, watchStore);
        order.verify(maService).deleteEventFromMA("mk-local");
        order.verify(watchStore).putSyncToken(USER_ID, "sync-2");
    }

    @Test
    void revokedSeatNotificationCannotReadCalendar() {
        when(discoveryService.isSyncable(USER_ID)).thenReturn(false);
        assertTrue(handler.handleNotification(USER_ID, "channel-1", "exists"));
        verify(watchStore, never()).getSyncToken(anyString());
        verify(maService, never()).deleteEventFromMA(anyString());
    }

    /**
     * EF-34's other half, asserted structurally: the collaborator is no longer merely injected. A
     * behavioural test can prove it is called on the paths above, but this is what fails if somebody
     * "simplifies" the constructor by removing an argument they believe is unused.
     */
    @Test
    @DisplayName("MeetingKeyResolver is a used collaborator, not an unused injection")
    void resolverIsActuallyUsed() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/app/kristasoft/extensions/essentials/collaboration/google_calendar3/"
                        + "service/webhook/CalendarNotificationHandler.java"));

        assertTrue(source.contains("meetingKeyResolver.resolveMeetingKey("),
                "EF-34: this class held MeetingKeyResolver as an injected field and never called it, "
                        + "while the cancellation path it belongs to abandoned any event whose key "
                        + "was in neither local store. Either use it or drop it — do not leave it "
                        + "looking like the gap is covered.");
    }

    /** Kept so the transport's event JSON stays parseable by the real client, not by our assumptions. */
    @Test
    @DisplayName("the canned page really does decode as one cancelled event")
    void cannedPageIsRealistic() throws Exception {
        Event event = GsonFactory.getDefaultInstance()
                .createJsonParser(cancelledEventPage())
                .parseAndClose(com.google.api.services.calendar.model.Events.class)
                .getItems().get(0);

        assertEquals("cancelled", event.getStatus());
        assertEquals(ICAL, event.getICalUID());
        EventDateTime start = event.getStart();
        assertEquals(new DateTime("2026-09-15T10:00:00Z").getValue(),
                start.getDateTime().getValue());
    }
}
