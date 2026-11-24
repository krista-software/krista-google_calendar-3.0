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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.config;

import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarAuthenticationService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.RegisteredSeatStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.TenantUserDirectory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveGoogleCalendarConfigurationTest {
    @Test
    void studioOffAndCredentialChangesInvalidateSavedSnapshotWithoutReload() {
        Invoker invoker = mock(Invoker.class);
        Map<String, Object> before = new HashMap<>(Map.of(Constants.ALWAYS_ON, false));
        when(invoker.getAttributes()).thenReturn(before);
        LiveGoogleCalendarConfiguration live = new LiveGoogleCalendarConfiguration(invoker);
        Map<String, Object> saved = Map.of(Constants.ALWAYS_ON, true,
                Constants.SERVICE_ACCOUNT_KEY, "saved-key", Constants.TENANT_ADMIN_EMAIL, "saved@example.com");
        live.applySaved(saved);
        assertTrue(Constants.readAlwaysOn(live.attributes()));

        // Model the platform refreshing even the same mutable Invoker map after persistence.
        before.putAll(saved);
        assertTrue(Constants.readAlwaysOn(live.attributes()));
        before.put(Constants.ALWAYS_ON, false);
        before.put(Constants.SERVICE_ACCOUNT_KEY, "studio-key");
        before.put(Constants.TENANT_ADMIN_EMAIL, "studio@example.com");
        assertFalse(Constants.readAlwaysOn(live.attributes()));
        DomainWideDelegationProvider delegation = new DomainWideDelegationProvider(live);
        assertEquals("studio@example.com", delegation.getAdminEmail());
        assertEquals("studio-key", live.attributes().get(Constants.SERVICE_ACCOUNT_KEY));
    }

    @Test
    void duplicateSetupApplicationDoesNotHideAConcurrentStudioChange() {
        Invoker invoker = mock(Invoker.class);
        when(invoker.getAttributes()).thenReturn(Map.of(Constants.ALWAYS_ON, false));
        LiveGoogleCalendarConfiguration live = new LiveGoogleCalendarConfiguration(invoker);
        Map<String, Object> saved = Map.of(Constants.ALWAYS_ON, true);
        live.applySaved(saved);
        when(invoker.getAttributes()).thenReturn(Map.of(Constants.ALWAYS_ON, false, Constants.TENANT_DOMAIN, "studio.example"));
        live.applySaved(saved);
        assertFalse(Constants.readAlwaysOn(live.attributes()));
        assertEquals("studio.example", live.attributes().get(Constants.TENANT_DOMAIN));
    }

    @Test
    void committedSaveUpdatesDiscoveryAndCredentialsDespiteStaleInvoker() {
        Invoker invoker = mock(Invoker.class);
        when(invoker.getAttributes()).thenReturn(Map.of(Constants.ALWAYS_ON, false));
        LiveGoogleCalendarConfiguration live = new LiveGoogleCalendarConfiguration(invoker);
        DomainWideDelegationProvider delegation = new DomainWideDelegationProvider(live);
        CalendarDiscoveryService discovery = new CalendarDiscoveryService(invoker,
                mock(GoogleCalendarAuthenticationService.class), mock(GoogleProviderFactory.class),
                delegation, mock(TenantUserDirectory.class), mock(RegisteredSeatStore.class), live);

        assertFalse(discovery.isAlwaysOn());
        assertFalse(delegation.isConfigured());
        live.applySaved(Map.of(Constants.ALWAYS_ON, true,
                Constants.SERVICE_ACCOUNT_KEY, "configured-service-account",
                Constants.TENANT_ADMIN_EMAIL, "admin@example.com"));

        assertTrue(discovery.isAlwaysOn());
        assertTrue(delegation.isConfigured());
        assertEquals("admin@example.com", delegation.getAdminEmail());
        assertEquals(false, invoker.getAttributes().get(Constants.ALWAYS_ON));
    }

    @Test
    void loadResetReadsCommittedInvokerRatherThanEarlierSavedValue() {
        Invoker invoker = mock(Invoker.class);
        when(invoker.getAttributes()).thenReturn(Map.of(Constants.ALWAYS_ON, false));
        LiveGoogleCalendarConfiguration live = new LiveGoogleCalendarConfiguration(invoker);
        live.applySaved(Map.of(Constants.ALWAYS_ON, true));
        live.clearSaved();
        assertFalse(Constants.readAlwaysOn(live.attributes()));
        when(invoker.getAttributes()).thenReturn(Map.of(Constants.ALWAYS_ON, true));
        assertTrue(Constants.readAlwaysOn(live.attributes()));
    }

    @Test
    void unreadableInvokerDoesNotKeepDelegationEnabledFromSavedSnapshot() {
        Invoker invoker = mock(Invoker.class);
        LiveGoogleCalendarConfiguration live = new LiveGoogleCalendarConfiguration(invoker);
        live.applySaved(Map.of(Constants.SERVICE_ACCOUNT_KEY, "configured-service-account",
                Constants.TENANT_ADMIN_EMAIL, "admin@example.com"));
        when(invoker.getAttributes()).thenThrow(new IllegalStateException("invoker removed"));
        assertFalse(new DomainWideDelegationProvider(live).isConfigured());
    }

    @Test
    void snapshotsCannotBeMutatedOrLeakIntoAnotherInvoker() {
        Invoker other = mock(Invoker.class);
        when(other.getAttributes()).thenReturn(Map.of(Constants.ALWAYS_ON, false));
        LiveGoogleCalendarConfiguration first = new LiveGoogleCalendarConfiguration(mock(Invoker.class));
        Map<String, Object> saved = new HashMap<>(Map.of(Constants.ALWAYS_ON, true));
        first.applySaved(saved);
        saved.put(Constants.ALWAYS_ON, false);
        assertTrue(Constants.readAlwaysOn(first.attributes()));
        assertThrows(UnsupportedOperationException.class,
                () -> first.attributes().put(Constants.ALWAYS_ON, false));
        assertFalse(Constants.readAlwaysOn(new LiveGoogleCalendarConfiguration(other).attributes()));
    }
}
