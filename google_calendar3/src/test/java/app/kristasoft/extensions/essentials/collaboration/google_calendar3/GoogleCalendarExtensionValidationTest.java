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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3;

import app.krista.extension.executor.Invoker;
import app.krista.extension.request.RoutingInfo;
import app.krista.ksdk.accounts.AccountProvider;
import app.krista.ksdk.context.AuthorizationContext;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.LiveGoogleCalendarConfiguration;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.*;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.GoogleServiceBusManager;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.Hk2ContextHolder;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarAuthenticationService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarWatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleCalendarExtensionValidationTest {
    private final Invoker invoker = mock(Invoker.class);
    private final GoogleServiceBusManager manager = mock(GoogleServiceBusManager.class);
    private final CalendarDiscoveryService discovery = mock(CalendarDiscoveryService.class);
    private final CalendarWatchService watches = mock(CalendarWatchService.class);
    private final Hk2ContextHolder context = mock(Hk2ContextHolder.class);
    private final LiveGoogleCalendarConfiguration live = new LiveGoogleCalendarConfiguration(invoker);
    private GoogleCalendarExtension extension;

    @BeforeEach
    void setUp() {
        when(invoker.getRoutingInfo()).thenReturn(mock(RoutingInfo.class));
        when(invoker.getAttributes()).thenReturn(Map.of(Constants.ALWAYS_ON, false));
        live.applySaved(Map.of(Constants.ALWAYS_ON, true,
                Constants.SERVICE_URL, "https://committed.example"));
        extension = new GoogleCalendarExtension(invoker, mock(GoogleAttributeStore.class),
                mock(AccountProvider.class), mock(AuthorizationContext.class), watches,
                mock(CalendarConnectionStatusStore.class), mock(GoogleCalendarAuthenticationService.class),
                mock(GoogleProviderFactory.class), manager, mock(UserKeyIndexStore.class),
                context, discovery, mock(DwdSetupStore.class));
        extension.setLiveConfiguration(live);
        clearInvocations(watches);
    }

    /**
     * Left unwired, the personal-token status writers would keep publishing under Always On —
     * REAUTH_REQUIRED for seats nobody can re-authenticate. The mode must be the discovery seam's
     * live reading, not a value captured at construction.
     */
    @Test
    void alwaysOnModeIsWiredToTheDiscoverySeam() {
        CalendarConnectionStatusStore statusStore = mock(CalendarConnectionStatusStore.class);
        when(discovery.isAlwaysOn()).thenReturn(false, true);

        new GoogleCalendarExtension(invoker, mock(GoogleAttributeStore.class),
                mock(AccountProvider.class), mock(AuthorizationContext.class), watches,
                statusStore, mock(GoogleCalendarAuthenticationService.class),
                mock(GoogleProviderFactory.class), manager, mock(UserKeyIndexStore.class), context,
                discovery, mock(DwdSetupStore.class));

        org.mockito.ArgumentCaptor<java.util.function.BooleanSupplier> mode =
                org.mockito.ArgumentCaptor.forClass(java.util.function.BooleanSupplier.class);
        verify(statusStore).setAlwaysOnMode(mode.capture());
        assertFalse(mode.getValue().getAsBoolean());
        assertTrue(mode.getValue().getAsBoolean(), "read live on every write");
    }

    @Test
    void validatingUnsavedDraftRefreshesOnlyCommittedEsbSettingsWithoutActivatingDraft() throws Exception {
        Map<String, Object> draft = Map.of(Constants.ALWAYS_ON, false,
                Constants.SERVICE_URL, "https://draft.example");
        extension.validateAttributes(draft);
        assertCommittedSettingsUnchanged();
        verify(discovery).validateDelegatedAccessIfAlwaysOn(draft);
        verify(manager).reconfigureEsb();
        verifyNoMoreInteractions(manager);
        verifyNoInteractions(watches);
    }

    @Test
    void failedValidationAlsoKeepsCommittedSettings() {
        assertThrows(IllegalArgumentException.class, () -> extension.validateAttributes(
                Map.of(Constants.SERVICE_URL, "not-an-absolute-url")));
        assertCommittedSettingsUnchanged();
        verifyNoInteractions(manager, watches, discovery);
    }

    private void assertCommittedSettingsUnchanged() {
        assertTrue(Constants.readAlwaysOn(live.attributes()));
        assertEquals("https://committed.example", live.attributes().get(Constants.SERVICE_URL));
    }

    @Test
    void committedStudioChangesAreVisibleAndReboundWithoutReload() {
        when(invoker.getAttributes()).thenReturn(Map.of(Constants.ALWAYS_ON, false,
                Constants.SERVICE_URL, "https://studio.example"));
        extension.validateAttributes(Map.of(Constants.ALWAYS_ON, false,
                Constants.SERVICE_URL, "https://studio.example"));
        assertFalse(Constants.readAlwaysOn(live.attributes()));
        assertEquals("https://studio.example", live.attributes().get(Constants.SERVICE_URL));
        verify(manager).reconfigureEsb();
        verifyNoInteractions(watches);
    }

    @Test
    void brokerRefreshFailureDoesNotFailValidationOrApplyCandidate() {
        when(manager.reconfigureEsb()).thenThrow(new IllegalStateException("broker unavailable"));
        assertDoesNotThrow(() -> extension.validateAttributes(Map.of(Constants.SERVICE_URL, "https://draft.example")));
        assertCommittedSettingsUnchanged();
    }

    @Test
    void startupCapturesContextBeforeQueuedEsbMessagesCanArrive() {
        extension.invokerLoaded();
        var order = inOrder(context, manager, discovery, watches);
        order.verify(context).captureFromCurrentThread();
        order.verify(manager).captureExecutionMode();
        order.verify(manager).start();
        order.verify(discovery).primeRegisteredSeats();
        order.verify(watches).subscribeAllUsers();
        order.verify(watches).startRenewalScheduler();
    }
}
