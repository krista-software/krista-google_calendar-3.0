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

import app.krista.extensions.util.KeyValueStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatus;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * Tests that the deliberate-disconnect chokepoint {@code unregisterUser} stamps
 * {@code reason=USER_INITIATED} (proto field 9) on the DISCONNECTED status it records.
 */
@ExtendWith(MockitoExtension.class)
class GoogleCalendarAuthenticationServiceTest {

    @Mock
    private KeyValueStore keyValueStore;

    @Mock
    private CalendarConnectionStatusStore connectionStatusStore;

    @Test
    void registerUserPersistsForSyncAndPublishesConnectedToCa() {
        when(keyValueStore.get("gcal_sync_authenticated_users")).thenReturn(null);
        GoogleCalendarAuthenticationService svc = new GoogleCalendarAuthenticationService(keyValueStore);
        svc.setConnectionStatusStore(connectionStatusStore);

        svc.registerUser("user@example.com", "INV_abc", "ws_real", "user@example.com");

        verify(keyValueStore).put("gcal_sync_authenticated_users", "[\"user@example.com\"]");
        verify(connectionStatusStore).recordConnected(
                "user@example.com", "INV_abc", "ws_real", "user@example.com");
    }

    /**
     * The stored list is written verbatim at registration, while Meeting Agent's seat roster — the
     * other side of the same credential decision — lower-cases every email it holds. An exact
     * comparison across that asymmetry reads a plainly authenticated user as unauthenticated and
     * reads their calendar through the domain-wide-delegation service account instead of their own
     * token, leaving a valid token unread.
     */
    @Test
    void isUserAuthenticatedIgnoresCase() {
        when(keyValueStore.get("gcal_sync_authenticated_users"))
                .thenReturn("[\"Ana@Contoso.com\"]");
        GoogleCalendarAuthenticationService svc = new GoogleCalendarAuthenticationService(keyValueStore);

        assertTrue(svc.isUserAuthenticated("ana@contoso.com"),
                "the same person, addressed as Meeting Agent stores them");
        assertTrue(svc.isUserAuthenticated("  Ana@Contoso.com  "));
    }

    @Test
    void isUserAuthenticatedStillRejectsAnotherUser() {
        when(keyValueStore.get("gcal_sync_authenticated_users"))
                .thenReturn("[\"Ana@Contoso.com\"]");
        GoogleCalendarAuthenticationService svc = new GoogleCalendarAuthenticationService(keyValueStore);

        assertFalse(svc.isUserAuthenticated("bob@contoso.com"));
        assertFalse(svc.isUserAuthenticated(null));
        assertFalse(svc.isUserAuthenticated("   "));
    }

    /**
     * A disconnect that leaves the person registered keeps them in every sweep. Their token is
     * revoked, so the sync then fails with invalid_grant and Meeting Agent nags them to reconnect
     * the calendar they deliberately disconnected.
     */
    @Test
    void unregisterUserRemovesEveryCaseFormOfThatPerson() {
        when(keyValueStore.get("gcal_sync_authenticated_users"))
                .thenReturn("[\"Ana@Contoso.com\",\"bob@contoso.com\"]");
        GoogleCalendarAuthenticationService svc = new GoogleCalendarAuthenticationService(keyValueStore);
        svc.setConnectionStatusStore(connectionStatusStore);

        svc.unregisterUser("ana@contoso.com", "INV_abc", "ws_real");

        // Bob is untouched; Ana is gone whichever case she was stored under.
        verify(keyValueStore).put("gcal_sync_authenticated_users", "[\"bob@contoso.com\"]");
    }

    @Test
    void registerUserDoesNotAddASecondEntryForTheSamePerson() {
        when(keyValueStore.get("gcal_sync_authenticated_users"))
                .thenReturn("[\"Ana@Contoso.com\"]");
        GoogleCalendarAuthenticationService svc = new GoogleCalendarAuthenticationService(keyValueStore);
        svc.setConnectionStatusStore(connectionStatusStore);

        svc.registerUser("ana@contoso.com", "INV_abc", "ws_real", "ana@contoso.com");

        // Two entries would survive a disconnect one at a time, leaving the list disagreeing with
        // itself about whether this person is connected.
        verify(keyValueStore, never()).put(eq("gcal_sync_authenticated_users"), anyString());
        // The CONNECTED re-assertion to CA still happens — registration is idempotent, not skipped.
        verify(connectionStatusStore).recordConnected(
                "ana@contoso.com", "INV_abc", "ws_real", "ana@contoso.com");
    }

    /**
     * The Meeting-Agent-driven reconnect flow stores the token under
     * {@code workspace_auth:{ws}:{email}}, while the seat roster and the webhooks address the same
     * person by bare email. No casing rule can bridge that — the strings differ by a prefix — so an
     * exact comparison read a reconnected user as unauthenticated and, under Always On, routed them
     * to the domain-wide-delegation service account while their own valid token sat unread.
     */
    @Test
    void isUserAuthenticatedMatchesAWorkspaceAuthKeyByEmail() {
        when(keyValueStore.get("gcal_sync_authenticated_users"))
                .thenReturn("[\"workspace_auth:ws_1fe38f34:ana@contoso.com\"]");
        GoogleCalendarAuthenticationService svc = new GoogleCalendarAuthenticationService(keyValueStore);

        assertTrue(svc.isUserAuthenticated("ana@contoso.com"));
        assertTrue(svc.isUserAuthenticated("Ana@Contoso.com"));
        assertFalse(svc.isUserAuthenticated("bob@contoso.com"));
    }

    @Test
    void isUserAuthenticatedMatchesTheLegacySuffixKeyForm() {
        when(keyValueStore.get("gcal_sync_authenticated_users"))
                .thenReturn("[\"ana@contoso.com#someclient\"]");
        GoogleCalendarAuthenticationService svc = new GoogleCalendarAuthenticationService(keyValueStore);

        assertTrue(svc.isUserAuthenticated("ana@contoso.com"));
    }

    /**
     * A platform account is not a person. {@code UsersResource.extractEmail} excludes these from the
     * user listing for the same reason, and inventing an email for one risks matching a platform
     * account to a human.
     */
    @Test
    void wsContactKeysAreNotMatchedToAnEmail() {
        when(keyValueStore.get("gcal_sync_authenticated_users"))
                .thenReturn("[\"wsContact_9f3c1a22\"]");
        GoogleCalendarAuthenticationService svc = new GoogleCalendarAuthenticationService(keyValueStore);

        assertFalse(svc.isUserAuthenticated("ana@contoso.com"));
        assertTrue(svc.isUserAuthenticated("wsContact_9f3c1a22"));
    }

    @Test
    void unregisterUserRemovesTheWorkspaceAuthFormWhenGivenTheEmail() {
        // The admin disconnect passes a bare email; leaving the workspace_auth entry behind keeps
        // the person in every sweep with a revoked token.
        when(keyValueStore.get("gcal_sync_authenticated_users"))
                .thenReturn("[\"workspace_auth:ws_1fe38f34:ana@contoso.com\",\"bob@contoso.com\"]");
        GoogleCalendarAuthenticationService svc = new GoogleCalendarAuthenticationService(keyValueStore);
        svc.setConnectionStatusStore(connectionStatusStore);

        svc.unregisterUser("ana@contoso.com", "INV_abc", "ws_real");

        verify(keyValueStore).put("gcal_sync_authenticated_users", "[\"bob@contoso.com\"]");
    }

    @Test
    void unregisterUser_stampsUserInitiatedReason() {
        GoogleCalendarAuthenticationService svc = new GoogleCalendarAuthenticationService(keyValueStore);
        svc.setConnectionStatusStore(connectionStatusStore);

        svc.unregisterUser("user@example.com", "INV_abc", "ws_real");

        verify(connectionStatusStore).put("user@example.com", "INV_abc",
                CalendarConnectionStatus.DISCONNECTED, "ws_real", "USER_INITIATED");
    }
}
