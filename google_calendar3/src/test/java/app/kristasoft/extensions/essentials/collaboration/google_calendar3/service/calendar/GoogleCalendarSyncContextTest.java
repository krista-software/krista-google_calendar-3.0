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

import app.krista.conversationagent.module.calendar.esb.CalendarDailySyncCommandProto;
import app.krista.conversationagent.module.calendar.esb.CalendarSeatRegisteredCommandProto;
import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.LiveGoogleCalendarConfiguration;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.Hk2ContextHolder;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.*;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarWatchService;
import com.kristasoft.common.holders.ThreadLocalProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the production constructors on workers without platform invoker context. */
class GoogleCalendarSyncContextTest {
    private static final String WORKSPACE = "ws-google-test";
    private static final String EMAIL = "seat@example.com";
    private final CalendarWatchService watches = mock(CalendarWatchService.class);
    private final SendEventsToMAService sync = mock(SendEventsToMAService.class);
    private final CalendarSyncTelemetryService telemetry = mock(CalendarSyncTelemetryService.class);
    private final RegisteredSeatStore seats = mock(RegisteredSeatStore.class);
    private final Invoker invoker = mock(Invoker.class);
    private final Hk2ContextHolder holder = new Hk2ContextHolder();
    private Map<Class<?>, Object> original;
    private GoogleCalendarSeatRegisteredHandler registration;
    private GoogleCalendarDailySyncHandler daily;
    private CalendarDiscoveryService discovery;
    private final AtomicBoolean rosterRefreshed = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        original = ThreadLocalProxy.getAll();
        ThreadLocalProxy.removeAll();
        when(invoker.getWorkspaceId()).thenAnswer(call -> {
            requireInvokerContext();
            return WORKSPACE;
        });
        when(invoker.getAttributes()).thenAnswer(call -> {
            requireInvokerContext();
            return Map.of(Constants.ALWAYS_ON, false);
        });
        LiveGoogleCalendarConfiguration live = new LiveGoogleCalendarConfiguration(invoker);
        // A successful conversational save occurs on a request thread with Invoker context;
        // capture its committed baseline before exercising context-less ESB workers below.
        ThreadLocalProxy.setAll(Map.of(Invoker.class, invoker));
        live.applySaved(Map.of(Constants.ALWAYS_ON, true,
                Constants.SERVICE_ACCOUNT_KEY, "test-key-not-used-for-network",
                Constants.TENANT_ADMIN_EMAIL, "admin@example.com"));
        discovery = new CalendarDiscoveryService(invoker, mock(GoogleCalendarAuthenticationService.class),
                mock(GoogleProviderFactory.class), new DomainWideDelegationProvider(live),
                mock(TenantUserDirectory.class), seats, live);
        when(seats.seats()).thenAnswer(call -> Optional.of(rosterRefreshed.get() ? Set.of(EMAIL) : Set.of()));
        when(seats.isRegisteredSeat(EMAIL)).thenAnswer(call -> rosterRefreshed.get());
        when(seats.refresh()).thenAnswer(call -> {
            requireInvokerContext();
            rosterRefreshed.set(true);
            return true;
        });
        when(watches.reconcileRegisteredSeatsNow()).thenAnswer(call -> {
            assertTrue(discovery.isAlwaysOn(), "ESB reconciliation must read committed Always On settings");
            discovery.primeRegisteredSeats();
            return true;
        });
        ThreadLocalProxy.setAll(Map.of(Invoker.class, invoker));
        assertTrue(holder.captureFromCurrentThread());
        ThreadLocalProxy.removeAll();
        registration = new GoogleCalendarSeatRegisteredHandler(watches, sync, discovery, telemetry, invoker, holder);
        daily = new GoogleCalendarDailySyncHandler(sync, telemetry, invoker, holder);
    }

    @AfterEach
    void restoreContext() {
        ThreadLocalProxy.removeAll();
        ThreadLocalProxy.setAll(original == null ? Map.of() : original);
    }

    @Test
    void registrationOnRealWorkerRefreshesSeatsBeforeExplicitBackfill() throws Exception {
        assertFalse(discovery.isSyncable(EMAIL), "reproduces the context-less ESB failure");
        doAnswer(call -> {
            requireInvokerContext();
            assertTrue(discovery.isSyncable(EMAIL));
            return null;
        }).when(sync).sendEventsToMAByUser(EMAIL, 14L);
        onWorker(() -> registration.googleCalendarSeatRegistered(seatEvent(WORKSPACE, true)));
        var order = inOrder(watches, seats, sync);
        order.verify(watches).reconcileRegisteredSeatsNow();
        order.verify(seats).refresh();
        order.verify(sync).sendEventsToMAByUser(EMAIL, 14L);
    }

    @Test
    void registrationWithoutSyncFlagRemainsWatchOnly() throws Exception {
        onWorker(() -> registration.googleCalendarSeatRegistered(seatEvent(WORKSPACE, false)));
        verify(watches).reconcileRegisteredSeatsNow();
        verify(seats).refresh();
        verifyNoInteractions(sync);
    }

    @Test
    void unregisteredUserIsNotBackfilled() throws Exception {
        CalendarSeatRegisteredCommandProto event = seatEvent(WORKSPACE, true);
        event.setUserEmail("unlicensed@example.com");
        onWorker(() -> registration.googleCalendarSeatRegistered(event));
        verify(seats).refresh();
        verifyNoInteractions(sync);
    }

    @Test
    void foreignWorkspaceCannotReconcileOrSyncEitherHandler() throws Exception {
        onWorker(() -> {
            registration.googleCalendarSeatRegistered(seatEvent("ws-other", true));
            daily.googleCalendarDailySync(dailyEvent("ws-other"));
        });
        verifyNoInteractions(watches, seats, sync);
    }

    @Test
    void missingLifecycleSnapshotCannotUseForeignWorkerContext() {
        Hk2ContextHolder empty = new Hk2ContextHolder();
        ThreadLocalProxy.setAll(Map.of(String.class, "foreign-worker"));
        new GoogleCalendarSeatRegisteredHandler(watches, sync, discovery, telemetry, invoker, empty)
                .googleCalendarSeatRegistered(seatEvent(WORKSPACE, true));
        new GoogleCalendarDailySyncHandler(sync, telemetry, invoker, empty)
                .googleCalendarDailySync(dailyEvent(WORKSPACE));
        verifyNoInteractions(watches, seats, sync);
        assertEquals(Map.of(String.class, "foreign-worker"), ThreadLocalProxy.getAll());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", WORKSPACE})
    void unresolvedLocalWorkspaceFailsClosedEvenForUnattributedMessages(String incoming) throws Exception {
        doReturn(null).when(invoker).getWorkspaceId();
        onWorker(() -> {
            registration.googleCalendarSeatRegistered(seatEvent(incoming, true));
            daily.googleCalendarDailySync(dailyEvent(incoming));
        });
        verifyNoInteractions(watches, seats, sync);
    }

    @Test
    void dailySweepRestoresContextAndKeepsDebounce() throws Exception {
        doAnswer(call -> {
            requireInvokerContext();
            assertTrue(discovery.isAlwaysOn());
            return null;
        }).when(sync).sendEventsToMA(21L);
        onWorker(() -> {
            daily.googleCalendarDailySync(dailyEvent(WORKSPACE));
            daily.googleCalendarDailySync(dailyEvent(WORKSPACE));
        });
        verify(sync, times(1)).sendEventsToMA(21L);
    }

    @Test
    void syncFailuresDoNotLeakContextOrEscapeToBroker() throws Exception {
        doThrow(new IllegalStateException("Google temporarily unavailable"))
                .when(sync).sendEventsToMAByUser(EMAIL, 14L);
        doThrow(new IllegalStateException("Google temporarily unavailable"))
                .when(sync).sendEventsToMA(21L);
        onWorker(() -> {
            registration.googleCalendarSeatRegistered(seatEvent(WORKSPACE, true));
            daily.googleCalendarDailySync(dailyEvent(WORKSPACE));
        });
        verify(telemetry).recordApiCall("ma", "seat_registered_backfill_failed", false);
        verify(telemetry).recordApiCall("ma", "daily_sync_sweep_failed", false);
    }

    private void requireInvokerContext() {
        if (ThreadLocalProxy.getAll().get(Invoker.class) != invoker) {
            throw new IllegalStateException("invoker context missing or belongs to another workspace");
        }
    }

    private void onWorker(Runnable operation) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                ThreadLocalProxy.removeAll();
                ThreadLocalProxy.setAll(Map.of(String.class, "worker-original"));
                try {
                    operation.run();
                    assertEquals(Map.of(String.class, "worker-original"), ThreadLocalProxy.getAll());
                } finally {
                    ThreadLocalProxy.removeAll();
                }
            }).get(10, TimeUnit.SECONDS);
        }
    }

    private CalendarSeatRegisteredCommandProto seatEvent(String workspace, boolean syncRequested) {
        CalendarSeatRegisteredCommandProto command = new CalendarSeatRegisteredCommandProto();
        command.setWorkspaceId(workspace);
        command.setUserEmail(EMAIL);
        command.setSyncCalendar(syncRequested);
        command.setSyncDays(14);
        return command;
    }

    private CalendarDailySyncCommandProto dailyEvent(String workspace) {
        CalendarDailySyncCommandProto command = new CalendarDailySyncCommandProto();
        command.setWorkspaceId(workspace);
        command.setSyncDays(21);
        return command;
    }
}
