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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarConnectionReconciliationServiceTest {

    private static final String INVOKER_ID = "INV_google";
    private static final String WORKSPACE_ID = "ws_google";

    @Mock
    private GoogleCalendarAuthenticationService authenticationService;
    @Mock
    private GoogleProviderFactory googleProviderFactory;
    @Mock
    private UserKeyIndexStore userKeyIndexStore;
    @Mock
    private Invoker invoker;
    @Mock
    private GoogleProvider googleProvider;
    @Mock
    private CalendarDiscoveryService discoveryService;

    private CalendarConnectionReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        reconciliationService = new CalendarConnectionReconciliationService(
                authenticationService, googleProviderFactory, userKeyIndexStore, invoker, discoveryService);
    }

    @Test
    void validatesPlainEmailTokenBeforeReassertingConnected() {
        when(authenticationService.getAuthenticatedUsers()).thenReturn(List.of("User@Example.com"));
        when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(googleProviderFactory.createForInvoker(INVOKER_ID)).thenReturn(googleProvider);
        when(googleProvider.getPrimaryCalendarEmail("User@Example.com"))
                .thenReturn("user@example.com");

        assertEquals(1, reconciliationService.reassertConnectedUsers());

        verify(authenticationService).registerUser(
                "User@Example.com", INVOKER_ID, WORKSPACE_ID, "user@example.com");
        verify(userKeyIndexStore).put(INVOKER_ID, "user@example.com", "User@Example.com");
    }

    @Test
    void validatesOpaqueUserEvenWhenDurableReverseIndexExists() {
        String userKey = "wsContact_1234";
        when(authenticationService.getAuthenticatedUsers()).thenReturn(List.of(userKey));
        when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(googleProviderFactory.createForInvoker(INVOKER_ID)).thenReturn(googleProvider);
        when(googleProvider.getPrimaryCalendarEmail(userKey)).thenReturn("client@example.com");

        assertEquals(1, reconciliationService.reassertConnectedUsers());

        verify(authenticationService).registerUser(
                userKey, INVOKER_ID, WORKSPACE_ID, "client@example.com");
    }

    @Test
    void resolvesOpaqueUserFromGoogleWhenIndexIsMissing() {
        String userKey = "wsContact_1234";
        when(authenticationService.getAuthenticatedUsers()).thenReturn(List.of(userKey));
        when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(googleProviderFactory.createForInvoker(INVOKER_ID)).thenReturn(googleProvider);
        when(googleProvider.getPrimaryCalendarEmail(userKey)).thenReturn("Client@Example.com");

        assertEquals(1, reconciliationService.reassertConnectedUsers());

        verify(userKeyIndexStore).put(INVOKER_ID, "client@example.com", userKey);
        verify(authenticationService).registerUser(
                userKey, INVOKER_ID, WORKSPACE_ID, "client@example.com");
    }

    @Test
    void invalidOrMissingTokenIsNotReassertedAsConnected() {
        String userId = "user@example.com";
        when(authenticationService.getAuthenticatedUsers()).thenReturn(List.of(userId));
        when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(googleProviderFactory.createForInvoker(INVOKER_ID)).thenReturn(googleProvider);
        when(googleProvider.getPrimaryCalendarEmail(userId)).thenReturn(null);

        assertEquals(0, reconciliationService.reassertConnectedUsers());

        verify(authenticationService, never()).registerUser(
                userId, INVOKER_ID, WORKSPACE_ID, null);
    }

    @Test
    void noAuthenticatedUsersIsANoop() {
        when(authenticationService.getAuthenticatedUsers()).thenReturn(List.of());

        assertEquals(0, reconciliationService.reassertConnectedUsers());

        verifyNoInteractions(googleProviderFactory, userKeyIndexStore, invoker);
    }

    /**
     * The personal-token health check is a calendar operation on a credential Always On does not
     * use, and its refresh failures would publish REAUTH_REQUIRED for seats nobody can
     * re-authenticate. Under Always On it does not run at all.
     */
    @Test
    void alwaysOnRunsNoPersonalTokenHealthCheck() {
        when(discoveryService.isAlwaysOn()).thenReturn(true);

        assertEquals(0, reconciliationService.reassertConnectedUsers());

        verifyNoInteractions(authenticationService, googleProviderFactory, userKeyIndexStore, invoker);
    }
}
