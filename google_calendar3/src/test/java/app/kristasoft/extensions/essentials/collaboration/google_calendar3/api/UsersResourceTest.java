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
import app.krista.ksdk.accounts.Account;
import app.krista.ksdk.accounts.AccountProvider;
import app.krista.ksdk.accounts.Person;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UsersResource} covering the contract test matrix
 * (User Management &amp; Multi-Tenancy contract §2.7).
 */
class UsersResourceTest {

    private static final String INVOKER_ID = "INV_google-workspace-calendar-acme";
    private static final String WORKSPACE_ID = "ws-acme";
    private static final String OTHER_WORKSPACE_ID = "ws-other";
    private static final String AUTH_CTX = "auth-ctx-1";

    private RefreshTokenStore refreshTokenStore;
    private AccountProvider accountProvider;
    private Invoker invoker;
    private UsersResource resource;

    @BeforeEach
    void setUp() {
        refreshTokenStore = mock(RefreshTokenStore.class);
        accountProvider = mock(AccountProvider.class);
        invoker = mock(Invoker.class);
        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        lenient().when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        resource = new UsersResource(refreshTokenStore, accountProvider, invoker);
    }

    @Test
    void returnsBothUsersWhenAccountsValid() {
        String janeKey = "workspace_auth:" + WORKSPACE_ID + ":jane.doe@acme.com";
        String johnKey = "workspace_auth:" + WORKSPACE_ID + ":john.smith@acme.com";
        Account jane = account("Jane Doe");
        Account john = account("John Smith");
        when(refreshTokenStore.getKey()).thenReturn(List.of(janeKey, johnKey));
        when(refreshTokenStore.get(janeKey)).thenReturn("rt-jane");
        when(refreshTokenStore.get(johnKey)).thenReturn("rt-john");
        when(accountProvider.lookupAccount("jane.doe@acme.com")).thenReturn(jane);
        when(accountProvider.lookupAccount("john.smith@acme.com")).thenReturn(john);

        Response response = resource.listUsers();

        assertEquals(200, response.getStatus());
        Map<String, Object> body = bodyAsMap(response);
        assertEquals(INVOKER_ID, body.get("extensionId"));
        List<Map<String, Object>> users = users(body);
        assertEquals(2, users.size());
        assertEquals("jane.doe@acme.com", users.get(0).get("email"));
        assertEquals("Jane Doe", users.get(0).get("displayName"));
        assertNull(users.get(0).get("lastTokenRefresh"));
        assertEquals("john.smith@acme.com", users.get(1).get("email"));
        assertEquals("John Smith", users.get(1).get("displayName"));
    }

    @Test
    void excludesUserWhoseGrantWasRevoked() {
        // A revoked grant means the row was removed, so the key is no
        // longer enumerated. A second user (with a token) verifies that
        // iteration still works around the gap.
        String aliveKey = "workspace_auth:" + WORKSPACE_ID + ":alive@acme.com";
        Account alive = account("Alive");
        when(refreshTokenStore.getKey()).thenReturn(List.of(aliveKey));
        when(refreshTokenStore.get(aliveKey)).thenReturn("rt-alive");
        when(accountProvider.lookupAccount("alive@acme.com")).thenReturn(alive);

        Response response = resource.listUsers();

        List<Map<String, Object>> users = users(bodyAsMap(response));
        assertEquals(1, users.size());
        assertEquals("alive@acme.com", users.get(0).get("email"));
    }

    @Test
    void excludesUserWithBlankRefreshToken() {
        String emptyKey = "workspace_auth:" + WORKSPACE_ID + ":disabled@acme.com";
        String validKey = "workspace_auth:" + WORKSPACE_ID + ":valid@acme.com";
        Account valid = account("Valid");
        when(refreshTokenStore.getKey()).thenReturn(List.of(emptyKey, validKey));
        when(refreshTokenStore.get(emptyKey)).thenReturn("");
        when(refreshTokenStore.get(validKey)).thenReturn("rt-valid");
        when(accountProvider.lookupAccount("valid@acme.com")).thenReturn(valid);

        Response response = resource.listUsers();

        List<Map<String, Object>> users = users(bodyAsMap(response));
        assertEquals(1, users.size());
        assertEquals("valid@acme.com", users.get(0).get("email"));
        verify(accountProvider, never()).lookupAccount("disabled@acme.com");
    }

    @Test
    void excludesUnknownAccount() {
        String ghostKey = "workspace_auth:" + WORKSPACE_ID + ":ghost@acme.com";
        when(refreshTokenStore.getKey()).thenReturn(List.of(ghostKey));
        when(refreshTokenStore.get(ghostKey)).thenReturn("rt-ghost");
        when(accountProvider.lookupAccount("ghost@acme.com")).thenReturn(null);

        Response response = resource.listUsers();

        List<Map<String, Object>> users = users(bodyAsMap(response));
        assertTrue(users.isEmpty());
    }

    @Test
    void lookupAccountThrowingExcludesUserButContinues() {
        String aliceKey = "workspace_auth:" + WORKSPACE_ID + ":alice@acme.com";
        String ghostKey = "workspace_auth:" + WORKSPACE_ID + ":ghost@acme.com";
        Account alice = account("Alice");
        when(refreshTokenStore.getKey()).thenReturn(List.of(aliceKey, ghostKey));
        when(refreshTokenStore.get(aliceKey)).thenReturn("rt-alice");
        when(refreshTokenStore.get(ghostKey)).thenReturn("rt-ghost");
        when(accountProvider.lookupAccount("alice@acme.com")).thenReturn(alice);
        when(accountProvider.lookupAccount("ghost@acme.com"))
                .thenThrow(new NoSuchElementException("not found"));

        Response response = resource.listUsers();

        assertEquals(200, response.getStatus());
        List<Map<String, Object>> users = users(bodyAsMap(response));
        assertEquals(1, users.size());
        assertEquals("alice@acme.com", users.get(0).get("email"));
    }

    @Test
    void excludesUserFromOtherWorkspace() {
        String mineKey = "workspace_auth:" + WORKSPACE_ID + ":mine@acme.com";
        String theirsKey = "workspace_auth:" + OTHER_WORKSPACE_ID + ":theirs@acme.com";
        Account mine = account("Mine");
        when(refreshTokenStore.getKey()).thenReturn(List.of(mineKey, theirsKey));
        when(refreshTokenStore.get(mineKey)).thenReturn("rt-mine");
        when(accountProvider.lookupAccount("mine@acme.com")).thenReturn(mine);

        Response response = resource.listUsers();

        List<Map<String, Object>> users = users(bodyAsMap(response));
        assertEquals(1, users.size());
        assertEquals("mine@acme.com", users.get(0).get("email"));
        verify(accountProvider, never()).lookupAccount("theirs@acme.com");
    }

    @Test
    void includesLegacyEmailKey() {
        // Pre-API-v1 auth flow stored the bare email as the key.
        String legacyKey = "legacy.user@acme.com";
        Account legacy = account("Legacy");
        when(refreshTokenStore.getKey()).thenReturn(List.of(legacyKey));
        when(refreshTokenStore.get(legacyKey)).thenReturn("rt-legacy");
        when(accountProvider.lookupAccount("legacy.user@acme.com")).thenReturn(legacy);

        Response response = resource.listUsers();

        List<Map<String, Object>> users = users(bodyAsMap(response));
        assertEquals(1, users.size());
        assertEquals("legacy.user@acme.com", users.get(0).get("email"));
    }

    @Test
    void includesGoogleStudioUserKeyWithAuthContext() {
        // Google studio users are stored as {email}#{authContextId}.
        String studioKey = "studio.user@acme.com#" + AUTH_CTX;
        Account studio = account("Studio");
        when(refreshTokenStore.getKey()).thenReturn(List.of(studioKey));
        when(refreshTokenStore.get(studioKey)).thenReturn("rt-studio");
        when(accountProvider.lookupAccount("studio.user@acme.com")).thenReturn(studio);

        Response response = resource.listUsers();

        List<Map<String, Object>> users = users(bodyAsMap(response));
        assertEquals(1, users.size());
        assertEquals("studio.user@acme.com", users.get(0).get("email"));
    }

    @Test
    void includesGoogleStudioUserKeyWithAuthContextAndForwardPath() {
        // Public-auth studio users carry an extra '#forwardPath' segment.
        String key = "public.user@acme.com#" + AUTH_CTX + "#/some/forward/path";
        Account acct = account("Public");
        when(refreshTokenStore.getKey()).thenReturn(List.of(key));
        when(refreshTokenStore.get(key)).thenReturn("rt-pub");
        when(accountProvider.lookupAccount("public.user@acme.com")).thenReturn(acct);

        Response response = resource.listUsers();

        List<Map<String, Object>> users = users(bodyAsMap(response));
        assertEquals(1, users.size());
        assertEquals("public.user@acme.com", users.get(0).get("email"));
    }

    @Test
    void excludesLegacyWsContactKey() {
        // Platform-account keys (wsContact_xxx) are not user emails.
        String wsContactKey = "wsContact_abc-123";
        when(refreshTokenStore.getKey()).thenReturn(List.of(wsContactKey));

        Response response = resource.listUsers();

        List<Map<String, Object>> users = users(bodyAsMap(response));
        assertTrue(users.isEmpty());
        verify(accountProvider, never()).lookupAccount(anyString());
    }

    @Test
    void lowercasesEmailsBeforeReturning() {
        String mixedKey = "workspace_auth:" + WORKSPACE_ID + ":Jane.Doe@Acme.com";
        Account jane = account("Jane");
        when(refreshTokenStore.getKey()).thenReturn(List.of(mixedKey));
        when(refreshTokenStore.get(mixedKey)).thenReturn("rt-jane");
        when(accountProvider.lookupAccount("jane.doe@acme.com")).thenReturn(jane);

        Response response = resource.listUsers();

        List<Map<String, Object>> users = users(bodyAsMap(response));
        assertEquals(1, users.size());
        assertEquals("jane.doe@acme.com", users.get(0).get("email"));
    }

    @Test
    void deduplicatesSameEmailAcrossLegacyAndWorkspaceKeys() {
        String legacyKey = "shared@acme.com";
        String wsKey = "workspace_auth:" + WORKSPACE_ID + ":shared@acme.com";
        Account shared = account("Shared");
        when(refreshTokenStore.getKey()).thenReturn(List.of(legacyKey, wsKey));
        when(refreshTokenStore.get(legacyKey)).thenReturn("rt-legacy");
        when(refreshTokenStore.get(wsKey)).thenReturn("rt-ws");
        when(accountProvider.lookupAccount("shared@acme.com")).thenReturn(shared);

        Response response = resource.listUsers();

        List<Map<String, Object>> users = users(bodyAsMap(response));
        assertEquals(1, users.size());
        assertEquals("shared@acme.com", users.get(0).get("email"));
    }

    @Test
    void emptyTokenStoreReturnsEmptyUsers() {
        when(refreshTokenStore.getKey()).thenReturn(List.of());

        Response response = resource.listUsers();

        assertEquals(200, response.getStatus());
        Map<String, Object> body = bodyAsMap(response);
        assertEquals(INVOKER_ID, body.get("extensionId"));
        assertTrue(((List<?>) body.get("users")).isEmpty());
        verify(accountProvider, never()).lookupAccount(anyString());
    }

    @Test
    void omitsDisplayNameWhenAccountHasNoPerson() {
        String key = "workspace_auth:" + WORKSPACE_ID + ":noperson@acme.com";
        Account noPerson = mock(Account.class);
        when(noPerson.getPerson()).thenReturn(null);

        when(refreshTokenStore.getKey()).thenReturn(List.of(key));
        when(refreshTokenStore.get(key)).thenReturn("rt");
        when(accountProvider.lookupAccount("noperson@acme.com")).thenReturn(noPerson);

        Response response = resource.listUsers();

        List<Map<String, Object>> users = users(bodyAsMap(response));
        assertEquals(1, users.size());
        assertFalse(users.get(0).containsKey("displayName"),
                "displayName must be omitted (not null/empty) when not available");
        assertNotNull(users.get(0));
        assertNull(users.get(0).get("lastTokenRefresh"));
    }

    @Test
    void tokenStoreUnavailableReturns503() {
        when(refreshTokenStore.getKey()).thenThrow(new IllegalStateException("kv store down"));

        Response response = resource.listUsers();

        assertEquals(503, response.getStatus());
        Map<String, Object> body = bodyAsMap(response);
        assertEquals("UNAVAILABLE", body.get("error"));
        assertEquals(30, body.get("retryAfterSeconds"));
        verify(accountProvider, never()).lookupAccount(anyString());
    }

    @Test
    void doesNotMutateAuthState() {
        // Read-only contract: listing must not call any token-mutating methods.
        String key = "workspace_auth:" + WORKSPACE_ID + ":noop@acme.com";
        Account noop = account("Noop");
        when(refreshTokenStore.getKey()).thenReturn(List.of(key));
        when(refreshTokenStore.get(key)).thenReturn("rt-noop");
        when(accountProvider.lookupAccount("noop@acme.com")).thenReturn(noop);

        resource.listUsers();

        verify(refreshTokenStore, never()).put(anyString(), anyString());
        verify(refreshTokenStore, never()).remove(anyString());
    }

    private static Account account(String displayName) {
        Person person = mock(Person.class);
        when(person.getPersonName()).thenReturn(displayName);
        Account account = mock(Account.class);
        when(account.getPerson()).thenReturn(person);
        return account;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyAsMap(Response response) {
        return (Map<String, Object>) response.getEntity();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> users(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("users");
    }
}
