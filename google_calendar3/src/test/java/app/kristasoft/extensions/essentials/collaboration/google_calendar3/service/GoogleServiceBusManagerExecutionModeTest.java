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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service;

import app.krista.infra.esb.ServiceBusConfig;
import app.krista.infra.esb.service.ServiceImpl;
import app.krista.ksdk.context.ExecutionMode;
import app.krista.ksdk.context.RequestContext;
import app.krista.ksdk.telemetry.TelemetryMetrics;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.GoogleExtensionConfig;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.*;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarConnectionReconciliationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleServiceBusManagerExecutionModeTest {
    private final GoogleExtensionConfig config = mock(GoogleExtensionConfig.class);
    private final RequestContext context = mock(RequestContext.class);
    private final GoogleCalendarSeatRegisteredHandler registration = mock(GoogleCalendarSeatRegisteredHandler.class);
    private final GoogleCalendarDailySyncHandler daily = mock(GoogleCalendarDailySyncHandler.class);
    private final TelemetryMetrics metrics = mock(TelemetryMetrics.class);
    private final CalendarConnectionReconciliationService reconciliation = mock(CalendarConnectionReconciliationService.class);
    private final GoogleServiceBusManager manager = new GoogleServiceBusManager(config,
            mock(GoogleCalendarAuthInitiateHandler.class), mock(GoogleCalendarReminderSendHandler.class),
            mock(GoogleCalendarReminderDecideHandler.class), registration, daily,
            mock(GoogleCalendarWorkspaceMessagesHandler.class),
            reconciliation, metrics, context);

    @ParameterizedTest
    @EnumSource(ExecutionMode.class)
    @NullSource
    void onlyLiveBindsSyncQueuesOnStartupAndReconnect(ExecutionMode mode) throws Exception {
        when(context.getExecutionMode()).thenReturn(mode);
        assertBindingsOnStartupAndReconnect(mode == ExecutionMode.LIVE);
        verify(context, times(mode == null ? 2 : 1)).getExecutionMode();
    }

    @Test
    void unavailableExecutionContextDoesNotBindSyncQueues() throws Exception {
        when(context.getExecutionMode()).thenThrow(new IllegalStateException("stage unavailable"));
        assertBindingsOnStartupAndReconnect(false);
        verify(context, times(2)).getExecutionMode();
        verify(metrics, atLeastOnce()).incrementCounter("google_calendar.esb.sync_binding.mode_unavailable", 1L);
        verify(metrics, atLeastOnce()).incrementCounter("google_calendar.esb.sync_binding.not_ready", 1L);
        verifyNoInteractions(reconciliation);
    }

    @Test
    void backgroundRebindUsesLifecycleModeNotMissingThreadContext() throws Exception {
        when(context.getExecutionMode()).thenReturn(ExecutionMode.LIVE);
        manager.captureExecutionMode();
        when(context.getExecutionMode()).thenThrow(new IllegalStateException("background thread has no context"));
        assertBindingsOnStartupAndReconnect(true);
        verify(context).getExecutionMode();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "all", "missing", "blank"})
    void unsafeWorkspaceNeverConstructsSyncConsumersAndCannotAppearReady(String kind) throws Exception {
        when(context.getExecutionMode()).thenReturn(ExecutionMode.LIVE);
        ServiceBusConfig unsafe = switch (kind) {
            case "null" -> null;
            case "all" -> queue("unsafe").setTenantFilterMode(ServiceBusConfig.TenantFilterMode.ALL);
            case "missing" -> new ServiceBusConfig().setDestinationBaseName("unsafe").setVersion(1)
                    .setDestinationType(ServiceBusConfig.DestinationType.Queue)
                    .setTenantFilterMode(ServiceBusConfig.TenantFilterMode.FIXED);
            default -> queue("unsafe").setFixedTenantId("   ");
        };
        stubConfigs(unsafe, unsafe);
        List<String> bound = new ArrayList<>();
        try (var construction = mockConstruction(ServiceImpl.class, (service, creation) ->
                bound.add(((ServiceBusConfig) creation.arguments().getFirst()).destinationBaseName()))) {
            manager.start();
            assertEquals(List.of("auth", "auth-legacy", "workspace"), bound);
            assertFalse(manager.reconfigureEsb(), "three unrelated consumers must not mask missing LIVE sync");
            assertEquals(List.of("auth", "auth-legacy", "workspace", "auth", "auth-legacy", "workspace"), bound);
            manager.stop();
        }
        verifyNoInteractions(reconciliation, registration, daily);
        verify(metrics, times(4)).incrementCounter("google_calendar.esb.sync_binding.workspace_refused", 1L);
        verify(metrics, atLeastOnce()).incrementCounter("google_calendar.esb.sync_binding.not_ready", 1L);
    }

    @Test
    void missingLiveConsumerIsRetriedEvenWhenEndpointDoesNotChange() throws Exception {
        when(context.getExecutionMode()).thenReturn(ExecutionMode.LIVE);
        stubConfigs(queue("seat"), queue("daily"));
        try (var construction = mockConstruction(ServiceImpl.class, (service, creation) -> {
            if (((ServiceBusConfig) creation.arguments().getFirst()).destinationBaseName().equals("daily")
                    && creation.getCount() == 4) {
                doThrow(new java.io.IOException("initial daily bind failed")).when(service).start();
            }
        })) {
            manager.start();
            verifyNoInteractions(reconciliation);
            assertTrue(manager.reconfigureEsb(), "a missing sync consumer must be recovered, not hidden by the baseline count");
            verify(reconciliation).reassertConnectedUsers();
            manager.stop();
        }
    }

    private void stubConfigs(ServiceBusConfig seat, ServiceBusConfig sweep) {
        when(config.getCalendarAuthInitiateConfig()).thenReturn(queue("auth"));
        when(config.getCalendarAuthInitiateLegacyConfig()).thenReturn(queue("auth-legacy"));
        when(config.getCalendarSeatRegisteredConfig()).thenReturn(seat);
        when(config.getCalendarDailySyncConfig()).thenReturn(sweep);
        when(config.getCalendarWorkspaceMessagesConfig()).thenReturn(queue("workspace"));
    }

    private void assertBindingsOnStartupAndReconnect(boolean expectSync) throws Exception {
        stubConfigs(queue("seat"), queue("daily"));
        when(config.refreshEsbEndpoint("https://ca.example", "test-key")).thenReturn(true);
        List<String> bound = new ArrayList<>();
        // Exercise the public lifecycle paths, including actual consumer selection, without JMS.
        try (var construction = mockConstruction(ServiceImpl.class, (service, creation) -> {
            ServiceBusConfig supplied = (ServiceBusConfig) creation.arguments().getFirst();
            bound.add(supplied.destinationBaseName());
        })) {
            manager.start();
            assertEquals(expectSync ? List.of("auth", "auth-legacy", "seat", "daily", "workspace")
                    : List.of("auth", "auth-legacy", "workspace"), bound);
            for (ServiceImpl service : construction.constructed()) {
                verify(service).start();
            }
            List<String> firstBindings = List.copyOf(bound);
            List<ServiceImpl> firstConsumers = List.copyOf(construction.constructed());
            manager.reconfigureEsb("https://ca.example", "test-key");
            assertEquals(firstBindings, bound.subList(firstBindings.size(), bound.size()),
                    "reconnect must not let draft/test stages acquire live queues");
            for (ServiceImpl service : firstConsumers) {
                verify(service).destroy();
            }
            manager.stop();
        }
        // Existing OAuth bindings stay available in every mode; only the two sync queues differ.
        if (!expectSync) {
            verifyNoInteractions(registration, daily);
        }
    }

    private ServiceBusConfig queue(String name) {
        return new ServiceBusConfig().setDestinationBaseName(name).setVersion(1)
                .setDestinationType(ServiceBusConfig.DestinationType.Queue)
                .setTenantFilterMode(ServiceBusConfig.TenantFilterMode.FIXED).setFixedTenantId("ws-test");
    }
}
