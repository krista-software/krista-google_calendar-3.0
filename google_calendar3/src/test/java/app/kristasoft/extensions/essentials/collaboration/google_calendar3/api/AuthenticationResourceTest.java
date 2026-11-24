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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.api;

import app.krista.extension.executor.Invoker;
import app.krista.extension.request.RoutingInfo;
import app.krista.extension.request.protos.http.HttpProtocol;
import app.krista.ksdk.authentication.AuthorizationListener;
import app.krista.ksdk.context.AuthorizationContext;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.GoogleAttributes;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.GoogleAttributeStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.DwdSetupStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.notification.NotificationService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarAuthenticationService;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.api.services.calendar.Calendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import javax.ws.rs.core.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationResourceTest {

    private static final String BASE_ROUTING_URL = "https://extension.example/extension/api/routing-id";
    private static final String INVOKER_ID = "INV_google_calendar";
    private static final String WORKSPACE_ID = "ws_google_calendar";
    private static final String USER_KEY = "wsContact_user_123";
    private static final String AUTH_CONTEXT_ID = "authContextId_test";
    private static final String EMAIL = "admin@example.com";

    @Mock
    private GoogleAttributeStore googleAttributeStore;
    @Mock
    private RefreshTokenStore refreshTokenStore;
    @Mock
    private GoogleProviderFactory providerFactory;
    @Mock
    private AuthorizationListener authorizationListener;
    @Mock
    private AuthorizationContext context;
    @Mock
    private Invoker invoker;
    @Mock
    private RoutingInfo routingInfo;
    @Mock
    private GoogleCalendarAuthenticationService authenticationService;
    @Mock
    private UserKeyIndexStore userKeyIndexStore;
    @Mock
    private NotificationService notificationService;
    @Mock
    private DwdSetupStore dwdSetupStore;
    @Mock
    private GoogleProvider googleProvider;
    @Mock
    private GoogleAttributes googleAttributes;
    @Mock
    private OAuth20Service oAuth20Service;

    private AuthenticationResource resource;

    @BeforeEach
    void setUp() {
        when(invoker.getRoutingInfo()).thenReturn(routingInfo);
        when(routingInfo.getRoutingURL(HttpProtocol.PROTOCOL_NAME, RoutingInfo.Type.APPLIANCE))
                .thenReturn(BASE_ROUTING_URL);
        when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);

        resource = new AuthenticationResource(
                googleAttributeStore,
                refreshTokenStore,
                providerFactory,
                authorizationListener,
                context,
                invoker,
                authenticationService,
                userKeyIndexStore,
                notificationService,
                dwdSetupStore);
    }

    @Test
    void dwdSetupContinuesPendingConversationAfterAdminReturns() {
        String token = "9ebc240b-71e8-4bb1-9ebc-12db15678fed";
        DwdSetupStore.PendingSetup pending = new DwdSetupStore.PendingSetup(
                "114009213202650966532", "wsContact_admin", System.currentTimeMillis() + 60_000);
        when(dwdSetupStore.find(INVOKER_ID, token)).thenReturn(pending);
        when(context.isAuthenticated()).thenReturn(true);
        when(dwdSetupStore.consume(INVOKER_ID, token)).thenReturn(true);

        Response page = resource.dwdSetup(token);
        assertEquals(200, page.getStatus());
        assertTrue(String.valueOf(page.getEntity()).contains("114009213202650966532"));

        Response continuation = resource.continueDwdSetup(token);
        assertEquals(200, continuation.getStatus());
        verify(authorizationListener).authorized();
    }

    @Test
    void dwdSetupDoesNotContinueWithoutKristaAuthentication() {
        String token = "9ebc240b-71e8-4bb1-9ebc-12db15678fed";
        when(dwdSetupStore.find(INVOKER_ID, token)).thenReturn(new DwdSetupStore.PendingSetup(
                "114009213202650966532", "wsContact_admin", System.currentTimeMillis() + 60_000));

        Response continuation = resource.continueDwdSetup(token);

        assertEquals(403, continuation.getStatus());
        verify(dwdSetupStore, never()).consume(INVOKER_ID, token);
        verify(authorizationListener, never()).authorized();
    }

    @Test
    void callbackNotifiesAuthorizationListenerWhenContextIsAuthenticated() throws Exception {
        String state = USER_KEY + Constants.HASH + AUTH_CONTEXT_ID + Constants.HASH
                + "https://extension.example/rest/googlecalendar/v3/oauth/callback";
        when(providerFactory.create(AUTH_CONTEXT_ID)).thenReturn(googleProvider);
        when(googleProvider.getGoogleAttributes()).thenReturn(googleAttributes);
        when(googleAttributes.getOAuth20Service()).thenReturn(oAuth20Service);
        when(oAuth20Service.getAccessToken("code"))
                .thenReturn(new OAuth2AccessToken("access", "Bearer", 3600, "refresh", null, null));
        when(refreshTokenStore.get(USER_KEY)).thenReturn(null);
        when(googleProvider.getPrimaryCalendarEmail(USER_KEY)).thenReturn(EMAIL);
        when(context.isAuthenticated()).thenReturn(true);

        String response = resource.getCallbackForV3Auth("code", state);

        assertEquals("User authenticated successfully.", response);
        verify(refreshTokenStore).put(USER_KEY, "refresh");
        verify(userKeyIndexStore).put(INVOKER_ID, EMAIL, USER_KEY);
        verify(authenticationService).registerUser(USER_KEY, INVOKER_ID, WORKSPACE_ID, EMAIL);
        verify(notificationService).resetCount(EMAIL);
        verify(authorizationListener).authorized();
        verify(googleAttributeStore).remove(AUTH_CONTEXT_ID);
    }

    @Test
    void saveConfigurationCallbackRegistersConfiguredEmailWithoutRecomposingTokenKey() throws Exception {
        String clientId = "google-client-id";
        String tokenKey = EMAIL + Constants.UNDER_SCORE + clientId
                + Constants.UNDER_SCORE + Constants.PUBLIC;
        String state = tokenKey + Constants.HASH + AUTH_CONTEXT_ID + Constants.HASH
                + "https://extension.example/rest/googlecalendar/v3/oauth/callback";

        Calendar calendar = org.mockito.Mockito.mock(Calendar.class);
        Calendar.Events events = org.mockito.Mockito.mock(Calendar.Events.class);
        Calendar.Events.List eventList = org.mockito.Mockito.mock(Calendar.Events.List.class);

        when(providerFactory.create(AUTH_CONTEXT_ID)).thenReturn(googleProvider);
        when(googleProvider.getGoogleAttributes()).thenReturn(googleAttributes);
        when(googleAttributes.getOAuth20Service()).thenReturn(oAuth20Service);
        when(googleAttributes.getMailId()).thenReturn(EMAIL);
        when(googleAttributes.getClientId()).thenReturn(clientId);
        when(googleAttributes.getAuthType()).thenReturn(Constants.PUBLIC);
        when(oAuth20Service.getAccessToken("code"))
                .thenReturn(new OAuth2AccessToken("access", "Bearer", 3600, "refresh", null, null));
        when(refreshTokenStore.get(tokenKey)).thenReturn(null);
        when(googleProvider.getGoogleClient()).thenReturn(calendar);
        when(calendar.events()).thenReturn(events);
        when(events.list("primary")).thenReturn(eventList);
        when(eventList.setMaxResults(1)).thenReturn(eventList);
        when(eventList.setTimeMin(any())).thenReturn(eventList);
        when(eventList.setOrderBy("startTime")).thenReturn(eventList);
        when(eventList.setSingleEvents(true)).thenReturn(eventList);
        when(googleProvider.getPrimaryCalendarEmail(EMAIL)).thenReturn(EMAIL);

        String response = resource.getCallbackForV3Auth("code", state);

        assertEquals("User authenticated successfully. Save the changes.", response);
        verify(refreshTokenStore).put(tokenKey, "refresh");
        verify(googleProvider).getPrimaryCalendarEmail(EMAIL);
        verify(userKeyIndexStore).put(INVOKER_ID, EMAIL, tokenKey);
        verify(authenticationService).registerUser(EMAIL, INVOKER_ID, WORKSPACE_ID, EMAIL);
        verify(notificationService).resetCount(EMAIL);
        verify(googleAttributeStore).remove(AUTH_CONTEXT_ID);
    }
}
