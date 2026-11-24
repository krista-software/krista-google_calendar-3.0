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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.handler;

import app.krista.extension.executor.ExtensionResponse;
import app.krista.ksdk.context.RequestContext;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarAuthenticationService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarWatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DisconnectHandler}: the deliberate disconnect revokes + forgets the grant,
 * stops the watch channel, and unregisters the user with the REAL workspaceId (so CA deletes the row).
 */
@ExtendWith(MockitoExtension.class)
class DisconnectHandlerTest {

    @Mock
    private GoogleProviderFactory googleProviderFactory;
    @Mock
    private RequestContext requestContext;
    @Mock
    private GoogleCalendarAuthenticationService authenticationService;
    @Mock
    private CalendarWatchService calendarWatchService;
    @Mock
    private GoogleProvider googleProvider;

    @Test
    void execute_revokesStopsAndUnregistersWithWorkspaceId() {
        when(requestContext.invokeAsUser()).thenReturn(true);
        when(requestContext.getInvokerId()).thenReturn("INV_abc");
        when(requestContext.getWorkspaceId()).thenReturn("ws_real");
        when(googleProviderFactory.create()).thenReturn(googleProvider);
        // invokeAsUser=true → getUserId(!true) = getUserId(false) → the user's account id
        when(googleProvider.getUserId(false)).thenReturn("user@example.com");

        DisconnectHandler handler = new DisconnectHandler(
                googleProviderFactory, requestContext, authenticationService, calendarWatchService);
        ExtensionResponse response = handler.execute();

        assertNotNull(response);
        verify(googleProvider).revokeStoredToken("user@example.com");
        verify(calendarWatchService).stopWatchChannel("user@example.com");
        // The authoritative step: publishes DISCONNECTED{USER_INITIATED} with the real workspaceId.
        verify(authenticationService).unregisterUser("user@example.com", "INV_abc", "ws_real");
    }
}
