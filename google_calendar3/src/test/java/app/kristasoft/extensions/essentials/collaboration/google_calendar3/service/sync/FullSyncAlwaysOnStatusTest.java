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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.EsbCaGateway;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.TrackMeetingEventStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.testutil.TestCaGateway;
import com.google.api.services.calendar.Calendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS;
import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.ENROLLED_USERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The full sync is a sweep that resolves a scope, so under Always On it publishes each mailbox's
 * status and reports its snapshot like the watch sweeps do — and under Opt-In it does neither,
 * exactly as before Always On existed.
 */
@DisplayName("Full sync under Always On: statuses and snapshot")
class FullSyncAlwaysOnStatusTest {

    private static final String INVOKER_ID = "INV-1";
    private static final String WORKSPACE_ID = "ws-1";

    private final CalendarDiscoveryService discovery = mock(CalendarDiscoveryService.class);
    private final GoogleProviderFactory providers = mock(GoogleProviderFactory.class);
    private final GoogleProvider provider = mock(GoogleProvider.class);
    private final GoogleCalendarEventFetcher fetcher = mock(GoogleCalendarEventFetcher.class);
    private final CalendarSyncTelemetryService telemetry = mock(CalendarSyncTelemetryService.class);
    private final Invoker invoker = mock(Invoker.class);
    private final CalendarConnectionStatusStore statuses = mock(CalendarConnectionStatusStore.class);
    private final EsbCaGateway reportGateway = mock(EsbCaGateway.class);

    private SendEventsToMAService service;

    @BeforeEach
    void setUp() {
        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        lenient().when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        lenient().when(providers.createForInvoker(INVOKER_ID)).thenReturn(provider);
        lenient().when(telemetry.recordSyncStart(anyString()))
                .thenReturn(mock(CalendarSyncTelemetryService.TelemetryContext.class));
        lenient().when(reportGateway.reportScannedMailboxes(any())).thenReturn(true);

        ScannedMailboxReporter reporter = new ScannedMailboxReporter(invoker,
                mock(app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook
                        .CalendarWatchStore.class),
                mock(UserKeyIndexStore.class), reportGateway, statuses);
        TrackMeetingEventStore tracked = mock(TrackMeetingEventStore.class);
        lenient().when(tracked.getTrackedEventIds(anyString())).thenReturn(Set.of());
        lenient().when(tracked.getTrackedEventsMeta(anyString())).thenReturn(new HashMap<>());

        service = new SendEventsToMAService(providers, fetcher, mock(GoogleEventToMeetingDtoMapper.class),
                invoker, mock(MeetingKeyResolver.class), tracked, telemetry, mock(UserKeyIndexStore.class),
                discovery, TestCaGateway.unusedGateway(), reporter, statuses);
    }

    private void readable(String user) {
        Calendar client = mock(Calendar.class);
        when(discovery.calendarClientFor(provider, user, ENROLLED_USERS)).thenReturn(client);
        when(fetcher.fetchEventsForDays(eq(client), anyLong(), anyBoolean())).thenReturn(List.of());
    }

    private Map<String, String> reportedStates() {
        ArgumentCaptor<ScannedMailboxSnapshot> sent = ArgumentCaptor.forClass(ScannedMailboxSnapshot.class);
        verify(reportGateway).reportScannedMailboxes(sent.capture());
        Map<String, String> states = new HashMap<>();
        sent.getValue().mailboxes().forEach(row -> states.put(row.email(), row.subscriptionState()));
        return states;
    }

    @Test
    @DisplayName("A read mailbox is CONNECTED, a failed one goes down the DISCONNECTED path, and the sweep reports")
    void alwaysOnFullSyncPublishesAndReports() {
        when(discovery.discover("full_sync")).thenReturn(new CalendarDiscoveryService.UserSet(ENROLLED_USERS,
                List.of("alice@acme.example", "denied@acme.example"),
                Set.of("alice@acme.example", "denied@acme.example"), null,
                List.of("ghost@acme.example"), List.of()));
        readable("alice@acme.example");
        when(discovery.calendarClientFor(provider, "denied@acme.example", ENROLLED_USERS))
                .thenThrow(new IllegalStateException("403 delegation revoked"));

        service.sendEventsToMA(30);

        verify(statuses).recordDelegatedConnected("alice@acme.example", INVOKER_ID, WORKSPACE_ID, null);
        verify(statuses).recordDelegatedUnreachable(eq("denied@acme.example"), eq(INVOKER_ID),
                eq(WORKSPACE_ID), isNull(), eq("IllegalStateException"), eq("403 delegation revoked"));
        verify(statuses).recordDelegatedUnreachable(eq("ghost@acme.example"), eq(INVOKER_ID),
                eq(WORKSPACE_ID), isNull(), eq(DelegatedMailboxStatus.NOT_IN_DIRECTORY), anyString());
        assertEquals(Map.of("alice@acme.example", "SUBSCRIBED", "denied@acme.example", "FAILED",
                "ghost@acme.example", "NOT_IN_DIRECTORY"), reportedStates());
    }

    @Test
    @DisplayName("A degraded sync reads nothing, publishes nothing, and reports an empty snapshot with its reason")
    void degradedFullSyncReportsOnlyTheReason() {
        when(discovery.discover("full_sync")).thenReturn(new CalendarDiscoveryService.UserSet(
                ENROLLED_USERS, List.of(), Set.of(), "registered-seats-unavailable"));

        service.sendEventsToMA(30);

        verifyNoInteractions(statuses, providers, fetcher);
        ArgumentCaptor<ScannedMailboxSnapshot> sent = ArgumentCaptor.forClass(ScannedMailboxSnapshot.class);
        verify(reportGateway).reportScannedMailboxes(sent.capture());
        assertTrue(sent.getValue().isEmpty());
        assertEquals("registered-seats-unavailable", sent.getValue().degradedReason());
    }

    @Test
    @DisplayName("Opt-In: no status from the sync and no snapshot, as before Always On existed")
    void optInFullSyncIsUnchanged() {
        when(discovery.discover("full_sync")).thenReturn(new CalendarDiscoveryService.UserSet(
                CONSENTED_USERS, List.of("consented@acme.example"), null));
        Calendar client = mock(Calendar.class);
        when(discovery.calendarClientFor(provider, "consented@acme.example", CONSENTED_USERS)).thenReturn(client);
        when(fetcher.fetchEventsForDays(eq(client), anyLong(), anyBoolean())).thenReturn(List.of());

        service.sendEventsToMA(30);

        verifyNoInteractions(statuses);
        verify(reportGateway, never()).reportScannedMailboxes(any());
    }

    @Test
    @DisplayName("Single-user Always On sync: a failed read goes down the delegated DISCONNECTED path")
    void singleUserDelegatedFailurePublishes() {
        when(discovery.isSyncable("alice@acme.example")).thenReturn(true);
        when(discovery.sourceFor("alice@acme.example")).thenReturn(ENROLLED_USERS);
        Calendar client = mock(Calendar.class);
        when(discovery.calendarClientFor(provider, "alice@acme.example", ENROLLED_USERS)).thenReturn(client);
        when(fetcher.fetchEventsForDays(eq(client), anyLong(), anyBoolean()))
                .thenThrow(new IllegalStateException("events.list refused"));

        assertThrows(RuntimeException.class, () -> service.sendEventsToMAByUser("alice@acme.example", 30));

        verify(statuses).recordDelegatedUnreachable(eq("alice@acme.example"), eq(INVOKER_ID),
                eq(WORKSPACE_ID), isNull(), eq("IllegalStateException"), eq("events.list refused"));
        verify(statuses, never()).recordDelegatedConnected(any(), any(), any(), any());
        verify(reportGateway, never()).reportScannedMailboxes(any());
    }

    @Test
    @DisplayName("Single-user Always On sync: a read mailbox is CONNECTED")
    void singleUserDelegatedSuccessPublishes() {
        when(discovery.isSyncable("alice@acme.example")).thenReturn(true);
        when(discovery.sourceFor("alice@acme.example")).thenReturn(ENROLLED_USERS);
        readable("alice@acme.example");

        service.sendEventsToMAByUser("alice@acme.example", 30);

        verify(statuses).recordDelegatedConnected("alice@acme.example", INVOKER_ID, WORKSPACE_ID, null);
    }
}
