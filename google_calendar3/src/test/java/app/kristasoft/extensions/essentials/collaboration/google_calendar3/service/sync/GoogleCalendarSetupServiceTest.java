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

import app.krista.conversationagent.module.calendar.esb.CalendarSeatRegisteredCommandProto;
import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.LiveGoogleCalendarConfiguration;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarWatchService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarSeatRegisteredHandler;
import com.kristasoft.common.holders.ThreadLocalProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleCalendarSetupServiceTest {
    private final Invoker invoker = mock(Invoker.class);
    private final LiveGoogleCalendarConfiguration configuration = new LiveGoogleCalendarConfiguration(invoker);
    private final CalendarDiscoveryService discovery = mock(CalendarDiscoveryService.class);
    private final CalendarWatchService watches = mock(CalendarWatchService.class);
    private final SendEventsToMAService sync = mock(SendEventsToMAService.class);
    private GoogleCalendarSetupService setup;
    private Map<Class<?>, Object> originalContext;

    @BeforeEach
    void setUp() {
        originalContext = ThreadLocalProxy.getAll();
        ThreadLocalProxy.removeAll();
        setup = new GoogleCalendarSetupService(configuration, discovery, watches, Runnable::run);
    }

    @AfterEach
    void restoreContext() {
        ThreadLocalProxy.removeAll();
        ThreadLocalProxy.setAll(originalContext == null ? Map.of() : originalContext);
    }

    private void enable() {
        when(discovery.isAlwaysOn()).thenAnswer(ignored -> Constants.readAlwaysOn(configuration.attributes()));
    }

    @Test
    void saveEstablishesWatchesWithoutSyncingEvents() {
        enable();
        setup.applySavedConfiguration(Map.of(Constants.ALWAYS_ON, true));
        verify(watches).reconcileRegisteredSeatsNow();
        verify(discovery, times(2)).isAlwaysOn();
        verifyNoMoreInteractions(discovery);
        assertTrue(Constants.readAlwaysOn(configuration.attributes()));
        assertTrue(Arrays.stream(GoogleCalendarSetupService.class.getDeclaredFields())
                .noneMatch(field -> field.getType() == SendEventsToMAService.class),
                "Setup must not acquire a sync collaborator: event sync is driven by CA ESB requests");
    }

    @Test
    void optInDoesNotSubscribeOrSync() {
        setup.applySavedConfiguration(Map.of(Constants.ALWAYS_ON, false));
        verify(discovery).isAlwaysOn();
        verifyNoMoreInteractions(discovery);
        verifyNoInteractions(watches, sync);
    }

    @Test
    void watchFailureKeepsCommittedSettingsAndDoesNotStartSyncFallback() {
        enable();
        when(watches.reconcileRegisteredSeatsNow()).thenThrow(new IllegalStateException("watch unavailable"));
        assertDoesNotThrow(() -> setup.applySavedConfiguration(Map.of(Constants.ALWAYS_ON, true)));
        assertTrue(Constants.readAlwaysOn(configuration.attributes()));
        verifyNoInteractions(sync);
        verify(discovery, times(2)).isAlwaysOn();
        verifyNoMoreInteractions(discovery);
    }

    @Test
    void eventsSyncOnlyWhenCaEsbCommandRequestsItAndUseRequestedWindow() {
        enable();
        when(invoker.getWorkspaceId()).thenReturn("ws_google");
        GoogleCalendarSeatRegisteredHandler handler = new GoogleCalendarSeatRegisteredHandler(
                watches, sync, discovery, mock(CalendarSyncTelemetryService.class), invoker);
        setup.applySavedConfiguration(Map.of(Constants.ALWAYS_ON, true));
        verifyNoInteractions(sync);
        CalendarSeatRegisteredCommandProto command = new CalendarSeatRegisteredCommandProto();
        command.setWorkspaceId("ws_google");
        command.setUserEmail("employee@example.com");
        command.setSyncCalendar(false);
        handler.googleCalendarSeatRegistered(command);
        verifyNoInteractions(sync);
        when(discovery.isSyncable("employee@example.com")).thenReturn(true);
        command.setSyncCalendar(true);
        command.setSyncDays(14);
        handler.googleCalendarSeatRegistered(command);
        verify(sync).sendEventsToMAByUser("employee@example.com", 14);
        verifyNoMoreInteractions(sync);
    }

    @Test
    void queuedInitializationDoesNotRunAfterAlwaysOnIsDisabled() {
        enable();
        List<Runnable> queued = new ArrayList<>();
        setup = new GoogleCalendarSetupService(configuration, discovery, watches, queued::add);
        setup.applySavedConfiguration(Map.of(Constants.ALWAYS_ON, true));
        setup.applySavedConfiguration(Map.of(Constants.ALWAYS_ON, false));
        queued.forEach(Runnable::run);
        verifyNoInteractions(watches, sync);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void asyncSetupReplacesForeignWorkerContextAndRestoresItOnSuccessOrFailure(boolean fail) {
        enable();
        List<Runnable> queued = new ArrayList<>();
        setup = new GoogleCalendarSetupService(configuration, discovery, watches, queued::add);
        ThreadLocalProxy.setAll(Map.of(Invoker.class, invoker));
        when(watches.reconcileRegisteredSeatsNow()).thenAnswer(call -> {
            assertSame(invoker, ThreadLocalProxy.getAll().get(Invoker.class));
            assertFalse(ThreadLocalProxy.getAll().containsKey(String.class));
            ThreadLocalProxy.setAll(Map.of(Integer.class, 1));
            if (fail) {
                throw new IllegalStateException("watch failed");
            }
            return true;
        });
        setup.applySavedConfiguration(Map.of(Constants.ALWAYS_ON, true));
        ThreadLocalProxy.removeAll();
        ThreadLocalProxy.setAll(Map.of(String.class, "worker-original"));
        queued.getFirst().run();
        assertEquals(Map.of(String.class, "worker-original"), ThreadLocalProxy.getAll());
        verify(watches).reconcileRegisteredSeatsNow();
        verifyNoInteractions(sync);
    }
}
