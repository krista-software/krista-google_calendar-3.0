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

import app.krista.extension.request.protos.http.HttpRequest;
import app.krista.ksdk.accounts.Account;
import app.krista.ksdk.accounts.AccountProvider;
import app.krista.ksdk.context.AuthorizationContext;
import app.krista.model.field.NamedValuedField;
import app.krista.extension.authorization.MustAuthorizeException;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.DwdSetupStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.GoogleAttributeStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleCalendarAuthenticatorTest {

    @Test
    void dwdAuthorizationLinkKeepsConversationAccountForContinuation() {
        DwdSetupStore setupStore = mock(DwdSetupStore.class);
        AuthorizationContext context = mock(AuthorizationContext.class);
        Account account = mock(Account.class);
        when(context.getAuthorizedAccount()).thenReturn(account);
        when(account.getAccountId()).thenReturn("wsContact_admin");
        String token = "9ebc240b-71e8-4bb1-9ebc-12db15678fed";
        String clientId = "114009213202650966532";
        when(setupStore.create("INV_google_calendar", clientId, "wsContact_admin"))
                .thenReturn(token);
        GoogleCalendarAuthenticator authenticator = new GoogleCalendarAuthenticator(
                mock(GoogleAttributeStore.class), "INV_google_calendar", null, context,
                setupStore, "https://extension.example/extension/api/routing-id");
        MustAuthorizeException cause = new MustAuthorizeException("Complete DWD setup", java.util.List.of(
                new NamedValuedField(Constants.USER_ID, Constants.TEXT, "admin@example.com",
                        new java.util.HashMap<>(), new java.util.HashMap<>()),
                new NamedValuedField(Constants.DWD_SETUP_FIELD, Constants.TEXT, "true",
                        new java.util.HashMap<>(), new java.util.HashMap<>()),
                new NamedValuedField(Constants.DWD_CLIENT_ID_FIELD, Constants.TEXT, clientId,
                        new java.util.HashMap<>(), new java.util.HashMap<>())));

        var response = authenticator.getMustAuthorizeResponse(cause);

        assertEquals("https://extension.example/extension/api/routing-id"
                + "/rest/googlecalendar/dwd/setup?token=" + token, response.getLocation());
        verify(setupStore).create("INV_google_calendar", clientId, "wsContact_admin");

        when(setupStore.find("INV_google_calendar", token)).thenReturn(
                new DwdSetupStore.PendingSetup(clientId, "wsContact_admin",
                        System.currentTimeMillis() + 60_000));
        HttpRequest continuation = HttpRequest.create(
                URI.create("https://extension.example/extension/api/routing-id"
                        + "/rest/googlecalendar/dwd/continue?token=" + token),
                "POST", Map.of(), new ByteArrayInputStream(new byte[0]));
        assertEquals("wsContact_admin", authenticator.getAuthenticatedAccountId(continuation));
    }

    @Test
    void getAuthenticatedAccountIdRecognizesPlatformCallbackPath() {
        GoogleCalendarAuthenticator authenticator = new GoogleCalendarAuthenticator(
                mock(GoogleAttributeStore.class),
                "INV_google_calendar");
        String state = "admin@example.com" + Constants.HASH + "authContextId_test"
                + Constants.HASH + "https://extension.example/rest/googlecalendar/v3/oauth/callback";
        String encodedState = URLEncoder.encode(state, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.create(
                URI.create("https://extension.example/extension/api/rest/v3/oauth/callback?state=" + encodedState),
                "GET",
                Map.of(),
                new ByteArrayInputStream(new byte[0]));

        String accountId = authenticator.getAuthenticatedAccountId(request);

        assertEquals("admin@example.com", accountId);
    }

    @Test
    void getAuthenticatedAccountIdResolvesEmailStateToKristaAccountId() {
        AccountProvider accountProvider = mock(AccountProvider.class);
        Account account = mock(Account.class);
        when(accountProvider.lookupAccount("admin@example.com")).thenReturn(account);
        when(account.getAccountId()).thenReturn("wsContact_123");
        GoogleCalendarAuthenticator authenticator = new GoogleCalendarAuthenticator(
                mock(GoogleAttributeStore.class),
                "INV_google_calendar",
                accountProvider);
        String state = "admin@example.com" + Constants.HASH + "authContextId_test"
                + Constants.HASH + "https://extension.example/rest/googlecalendar/v3/oauth/callback";
        String encodedState = URLEncoder.encode(state, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.create(
                URI.create("https://extension.example/extension/api/rest/v3/oauth/callback?state=" + encodedState),
                "GET",
                Map.of(),
                new ByteArrayInputStream(new byte[0]));

        String accountId = authenticator.getAuthenticatedAccountId(request);

        assertEquals("wsContact_123", accountId);
    }

    @Test
    void getAuthenticatedAccountIdResolvesCompoundTokenKeyStateToKristaAccountId() {
        String authContextId = "authContextId_test";
        String clientId = "36969988243-7489n0ru7o8qqnqg6pr9vqfm5eidv2rr.apps.googleusercontent.com";
        GoogleAttributeStore googleAttributeStore = mock(GoogleAttributeStore.class);
        GoogleAttributes googleAttributes = mock(GoogleAttributes.class);
        when(googleAttributeStore.load(authContextId)).thenReturn(googleAttributes);
        when(googleAttributes.getClientId()).thenReturn(clientId);
        when(googleAttributes.getAuthType()).thenReturn(Constants.PRIVATE);
        AccountProvider accountProvider = mock(AccountProvider.class);
        Account account = mock(Account.class);
        when(accountProvider.lookupAccount("shivam.banyal@kristasw.com")).thenReturn(account);
        when(account.getAccountId()).thenReturn("wsContact_456");
        GoogleCalendarAuthenticator authenticator = new GoogleCalendarAuthenticator(
                googleAttributeStore,
                "INV_google_calendar",
                accountProvider);
        String tokenKey = "shivam.banyal@kristasw.com" + Constants.UNDER_SCORE + clientId
                + Constants.UNDER_SCORE + Constants.PRIVATE;
        String state = tokenKey + Constants.HASH + authContextId
                + Constants.HASH + "https://extension.example/rest/googlecalendar/v3/oauth/callback";
        String encodedState = URLEncoder.encode(state, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.create(
                URI.create("https://extension.example/extension/api/rest/v3/oauth/callback?state=" + encodedState),
                "GET",
                Map.of(),
                new ByteArrayInputStream(new byte[0]));

        String accountId = authenticator.getAuthenticatedAccountId(request);

        assertEquals("wsContact_456", accountId);
    }

    @Test
    void getAuthenticatedAccountIdPrefersKristaAccountIdCarriedInState() {
        AccountProvider accountProvider = mock(AccountProvider.class);
        GoogleCalendarAuthenticator authenticator = new GoogleCalendarAuthenticator(
                mock(GoogleAttributeStore.class),
                "INV_google_calendar",
                accountProvider);
        String tokenKey = "shivam.banyal@kristasw.com_client-id.apps.googleusercontent.com_Private";
        String state = tokenKey + Constants.HASH + "authContextId_test"
                + Constants.AUTHENTICATED_ACCOUNT_ID_STATE_MARKER + "wsContact_original"
                + Constants.HASH + "https://extension.example/rest/googlecalendar/v3/oauth/callback";
        String encodedState = URLEncoder.encode(state, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.create(
                URI.create("https://extension.example/extension/api/rest/v3/oauth/callback?state=" + encodedState),
                "GET",
                Map.of(),
                new ByteArrayInputStream(new byte[0]));

        String accountId = authenticator.getAuthenticatedAccountId(request);

        assertEquals("wsContact_original", accountId);
        verifyNoInteractions(accountProvider);
    }
}
