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

import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.TrackMeetingEventStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.testutil.TestCaGateway;
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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EF-12 at the call site: a CLIENT user's cancelled meeting must actually resolve.
 *
 * <p>{@link MeetingKeyResolverParticipantFilterTest} covers the guard. This covers what the caller
 * hands it, which is the half that was wrong: {@code handleCancelledEvent} passed the sweep's
 * {@code userId}, and on the consented path that is an opaque {@code wsContact_<uuid>} client key. It
 * now passes the mailbox owner's real address, resolved from the primary calendar for this sync — the
 * same value the create/update path already injects as the calendar owner.
 *
 * <p>The Google client is the real one over a canned transport, so the address under assertion is the
 * one Google would actually have reported rather than a value the test chose.
 */
@DisplayName("EF-12 — a client user's cancellation resolves against Meeting Agent")
class ClientUserCancellationTest {

    private static final String CLIENT_KEY = "wsContact_7f3c1e2a-9b4d-4a10-8f55-1c2d3e4f5a6b";
    private static final String OWNER_ADDRESS = "client.user@gmail.example";
    private static final String INVOKER_ID = "INV_google_calendar";
    private static final String ICAL = "ical-cancelled@google.com";
    private static final String EVENT_ID = "evt-1";

    /** Answers {@code calendars.get("primary")} with the owner address and nothing else. */
    private static final class PrimaryCalendarTransport extends MockHttpTransport {

        @Override
        public LowLevelHttpRequest buildRequest(String method, String url) {
            final String body = "{\"kind\":\"calendar#calendar\",\"id\":\"" + OWNER_ADDRESS + "\"}";
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

    private MeetingKeyResolver meetingKeyResolver;
    private GoogleCalendarEventFetcher eventFetcher;
    private TrackMeetingEventStore trackMeetingEventStore;
    private GoogleEventToMeetingDtoMapper eventMapper;
    private SendEventsToMAService service;
    private app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.EsbCaGateway gateway;

    @BeforeEach
    void setUp() {
        CalendarDiscoveryService discoveryService = mock(CalendarDiscoveryService.class);
        GoogleProviderFactory googleProviderFactory = mock(GoogleProviderFactory.class);
        GoogleProvider googleProvider = mock(GoogleProvider.class);
        CalendarSyncTelemetryService telemetryService = mock(CalendarSyncTelemetryService.class);
        Invoker invoker = mock(Invoker.class);
        meetingKeyResolver = mock(MeetingKeyResolver.class);
        eventFetcher = mock(GoogleCalendarEventFetcher.class);
        trackMeetingEventStore = mock(TrackMeetingEventStore.class);
        eventMapper = mock(GoogleEventToMeetingDtoMapper.class);

        Calendar calendar = new Calendar.Builder(new PrimaryCalendarTransport(),
                GsonFactory.getDefaultInstance(), null).setApplicationName("test").build();

        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        lenient().when(googleProviderFactory.createForInvoker(INVOKER_ID)).thenReturn(googleProvider);
        lenient().when(discoveryService.isSyncable(CLIENT_KEY)).thenReturn(true);
        lenient().when(discoveryService.sourceFor(CLIENT_KEY))
                .thenReturn(CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS);
        lenient().when(discoveryService.calendarClientFor(eq(googleProvider), eq(CLIENT_KEY), any()))
                .thenReturn(calendar);
        lenient().when(telemetryService.recordSyncStart(anyString()))
                .thenReturn(mock(CalendarSyncTelemetryService.TelemetryContext.class));
        // A cancelled event has no meeting URL of its own in Google's payload, but this extension
        // filters on the link BEFORE the cancelled branch, so the mapper must report one.
        lenient().when(eventMapper.extractMeetingUrl(any()))
                .thenReturn("https://meet.google.com/abc-defg-hij");
        lenient().when(eventMapper.detectPlatformFromUrl(anyString())).thenReturn("google_meet");
        lenient().when(eventFetcher.fetchEventsForDays(any(), anyLong(), anyBoolean()))
                .thenReturn(List.of(cancelledEvent()));
        // Nothing tracked locally: this is exactly the branch that has to fall back to the MA lookup.
        lenient().when(trackMeetingEventStore.getMeetingKey(anyString(), anyString())).thenReturn(null);
        lenient().when(trackMeetingEventStore.getTrackedEventIds(anyString())).thenReturn(java.util.Set.of());
        lenient().when(trackMeetingEventStore.getTrackedEventsMeta(anyString())).thenReturn(java.util.Map.of());
        lenient().when(meetingKeyResolver.resolveMeetingKey(anyString(), anyString(), any(), any()))
                .thenReturn("mk-resolved");

        gateway = TestCaGateway.unusedGateway();
        lenient().when(gateway.deleteMeeting(anyString(), any(), any())).thenReturn(true);
        service = new SendEventsToMAService(googleProviderFactory, eventFetcher, eventMapper, invoker,
                meetingKeyResolver, trackMeetingEventStore, telemetryService,
                mock(UserKeyIndexStore.class), discoveryService, gateway);
    }

    private static Event cancelledEvent() {
        Event event = new Event();
        event.setId(EVENT_ID);
        event.setICalUID(ICAL);
        event.setStatus("cancelled");
        event.setSummary("Weekly sync");
        event.setStart(new EventDateTime().setDateTime(new DateTime("2026-09-15T10:00:00Z")));
        return event;
    }

    @Test
    @DisplayName("the lookup is scoped by the mailbox owner's address, never by the wsContact_ key")
    void lookupUsesTheCalendarOwnerAddress() {
        service.sendEventsToMAByUser(CLIENT_KEY, 30);

        org.mockito.ArgumentCaptor<String> userEmail = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(meetingKeyResolver).resolveMeetingKey(eq(ICAL), anyString(), any(), userEmail.capture());

        assertNotNull(userEmail.getValue(),
                "the owner address resolved from the primary calendar must reach the lookup");
        assertEquals(OWNER_ADDRESS, userEmail.getValue(),
                "passing the scan-set id made MA's hard userEmail filter match no attendee, so the "
                        + "cancellation fix WP-E16 shipped was inert for every client user — and it "
                        + "failed silently, because a lookup that finds nothing looks exactly like a "
                        + "meeting MA does not hold");
    }

    @Test
    @DisplayName("the resolved key is used to delete, and the local tracking entry is cleared")
    void resolvedKeyIsActedOn() {
        service.sendEventsToMAByUser(CLIENT_KEY, 30);

        // The tracking entry is keyed by the scan-set id — that store is ours and it is keyed by the
        // key we hold, which is precisely why the id is still the right argument HERE and the wrong
        // one for MA.
        verify(trackMeetingEventStore).removeTrackedEvent(CLIENT_KEY, EVENT_ID);
    }

    @Test
    void sparseCancelledOccurrenceNeedsNoMeetingLink() {
        when(eventMapper.extractMeetingUrl(any())).thenReturn(null);
        when(trackMeetingEventStore.getMeetingKey(CLIENT_KEY, EVENT_ID)).thenReturn("mk-local");
        service.sendEventsToMAByUser(CLIENT_KEY, 30);
        verify(gateway).deleteMeeting(eq("mk-local"), any(), any());
        verify(trackMeetingEventStore).removeTrackedEvent(CLIENT_KEY, EVENT_ID);
    }

    @Test
    void rejectedDeleteKeepsTracking() {
        when(trackMeetingEventStore.getMeetingKey(CLIENT_KEY, EVENT_ID)).thenReturn("mk-local");
        when(gateway.deleteMeeting(anyString(), any(), any())).thenReturn(false);
        service.sendEventsToMAByUser(CLIENT_KEY, 30);
        verify(trackMeetingEventStore, org.mockito.Mockito.never()).removeTrackedEvent(CLIENT_KEY, EVENT_ID);
    }
}
