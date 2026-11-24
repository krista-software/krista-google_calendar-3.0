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

import app.krista.extension.authorization.MustAuthorizeException;
import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.TrackMeetingEventStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.testutil.TestCaGateway;

@ExtendWith(MockitoExtension.class)
class SendEventsToMAServiceAuthorizationTest {

    private static final String USER = "user@example.com";
    private static final String INVOKER_ID = "INV-1";

    @Mock private CalendarDiscoveryService discoveryService;
    @Mock private GoogleProviderFactory googleProviderFactory;
    @Mock private GoogleProvider googleProvider;
    @Mock private GoogleCalendarEventFetcher eventFetcher;
    @Mock private CalendarSyncTelemetryService telemetryService;
    @Mock private Invoker invoker;

    private SendEventsToMAService service;

    @BeforeEach
    void setUp() {
        service = new SendEventsToMAService(
                googleProviderFactory,
                eventFetcher,
                mock(GoogleEventToMeetingDtoMapper.class),
                invoker,
                mock(MeetingKeyResolver.class),
                mock(TrackMeetingEventStore.class),
                telemetryService,
                mock(UserKeyIndexStore.class),
                discoveryService,
                TestCaGateway.unusedGateway());
        lenient().when(telemetryService.recordSyncStart("single_user_sync"))
                .thenReturn(mock(CalendarSyncTelemetryService.TelemetryContext.class));
    }

    @Test
    void unauthenticatedUserThrowsMustAuthorizeForConversation() {
        MustAuthorizeException authorizationRequired =
                new MustAuthorizeException("Authorization required");
        when(discoveryService.isSyncable(USER)).thenReturn(false);

        service.sendEventsToMAByUser(USER, 30);

        verify(telemetryService).recordSyncFailure(any(), any(SecurityException.class));
        verifyNoInteractions(googleProviderFactory);
    }

    @Test
    void expiredTokenMustAuthorizeIsNotWrappedOrConvertedToZeroEvents() {
        MustAuthorizeException authorizationRequired =
                new MustAuthorizeException("Refresh Token Expired");
        when(discoveryService.isSyncable(USER)).thenReturn(true);
        when(discoveryService.sourceFor(USER))
                .thenReturn(CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS);
        when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        when(googleProviderFactory.createForInvoker(INVOKER_ID)).thenReturn(googleProvider);
        when(discoveryService.calendarClientFor(googleProvider, USER,
                CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS))
                .thenThrow(authorizationRequired);

        MustAuthorizeException thrown = assertThrows(MustAuthorizeException.class,
                () -> service.sendEventsToMAByUser(USER, 30));

        assertSame(authorizationRequired, thrown);
        verify(discoveryService).calendarClientFor(googleProvider, USER,
                CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS);
        verify(googleProviderFactory, never()).create();
    }

    @Test
    void adaptiveSyncRecordsUserTelemetryOnCallerThread() {
        Thread callerThread = Thread.currentThread();
        AtomicReference<Thread> telemetryThread = new AtomicReference<>();

        when(discoveryService.discover("full_sync"))
                .thenReturn(new CalendarDiscoveryService.UserSet(
                        CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS,
                        List.of(USER), null));
        when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        when(googleProviderFactory.createForInvoker(INVOKER_ID)).thenReturn(googleProvider);
        when(discoveryService.calendarClientFor(googleProvider, USER,
                CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS))
                .thenThrow(new IllegalStateException("Stop after provider creation"));
        when(telemetryService.recordSyncStart("full_sync"))
                .thenReturn(mock(CalendarSyncTelemetryService.TelemetryContext.class));
        doAnswer(invocation -> {
            telemetryThread.set(Thread.currentThread());
            return null;
        }).when(telemetryService).recordUserProcessing(eq(USER), eq(0), anyLong());

        service.sendEventsToMA(30);

        assertSame(callerThread, telemetryThread.get());
        verify(googleProviderFactory).createForInvoker(INVOKER_ID);
        verify(googleProviderFactory, never()).create();
    }
}
