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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors;

import app.krista.ksdk.context.AuthorizationContext;
import app.krista.ksdk.context.RequestContext;
import app.krista.extension.authorization.MustAuthorizeException;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.GoogleAttributes;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatus;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import com.github.scribejava.core.oauth.OAuth20Service;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GoogleProvider#recordRefreshFailureStatus(String, Exception)} —
 * the token-refresh failure classifier. Mirrors KIP's {@code recordFailureStatus}:
 * an {@code invalid_grant} (expired/revoked refresh token) records {@code REAUTH_REQUIRED};
 * any other refresh failure records {@code RENEWAL_FAILED}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoogleProviderTest {

    @Mock private RefreshTokenStore refreshTokenStore;
    @Mock private RequestContext requestContext;
    @Mock private AuthorizationContext authorizationContext;
    @Mock private CalendarConnectionStatusStore connectionStatusStore;

    private final GoogleAttributes attributes = mock(GoogleAttributes.class);
    private GoogleProvider provider;

    @BeforeEach
    void setUp() {
        when(requestContext.getInvokerId()).thenReturn("INV-1");
        when(requestContext.getWorkspaceId()).thenReturn("ws_1");
        provider = new GoogleProvider(refreshTokenStore, attributes, requestContext, authorizationContext, "authCtx");
        provider.setConnectionStatusStore(connectionStatusStore);
    }

    @Test
    @DisplayName("invalid_grant records REAUTH_REQUIRED with errorCode=invalid_grant")
    void invalidGrantRecordsReauthRequired() {
        IOException cause = new IOException("400 Bad Request\n{\"error\":\"invalid_grant\"}");

        provider.recordRefreshFailureStatus("userX", cause);

        verify(connectionStatusStore).put(eq("userX"), eq("INV-1"),
                eq(CalendarConnectionStatus.REAUTH_REQUIRED), eq("ws_1"),
                eq("invalid_grant"), eq(cause.getMessage()));
    }

    @Test
    @DisplayName("invalid_grant nested in the cause chain still records REAUTH_REQUIRED")
    void nestedInvalidGrantRecordsReauthRequired() {
        Exception cause = new RuntimeException("refresh failed",
                new IOException("token response: invalid_grant"));

        provider.recordRefreshFailureStatus("userZ", cause);

        verify(connectionStatusStore).put(eq("userZ"), eq("INV-1"),
                eq(CalendarConnectionStatus.REAUTH_REQUIRED), eq("ws_1"),
                eq("invalid_grant"), eq(cause.getMessage()));
    }

    @Test
    @DisplayName("a transient (non-invalid_grant) failure records RENEWAL_FAILED with the exception class as errorCode")
    void transientFailureRecordsRenewalFailed() {
        IOException cause = new IOException("503 Service Unavailable");

        provider.recordRefreshFailureStatus("userY", cause);

        verify(connectionStatusStore).put(eq("userY"), eq("INV-1"),
                eq(CalendarConnectionStatus.RENEWAL_FAILED), eq("ws_1"),
                eq("IOException"), eq("503 Service Unavailable"));
    }

    /**
     * Either/or, end to end through the real store: while Always On is on, a personal-token refresh
     * failure records and publishes nothing — no REAUTH_REQUIRED for a seat that nobody can
     * re-authenticate, and no local write the next delegated comparison would read.
     */
    @Test
    @DisplayName("Always On: a refresh failure publishes no REAUTH_REQUIRED and writes nothing")
    void alwaysOnRefreshFailurePublishesNothing() {
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        app.krista.extensions.util.KeyValueStore kv = mock(app.krista.extensions.util.KeyValueStore.class);
        when(kv.get(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> values.get(call.<String>getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> values.put(call.getArgument(0), call.getArgument(1)))
                .when(kv).put(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        CalendarConnectionStatusStore realStore = new CalendarConnectionStatusStore(kv);
        java.util.List<String> published = new java.util.ArrayList<>();
        realStore.setPublisher(new app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb
                .CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                published.add(event.toString());
            }
        });
        realStore.setAlwaysOnMode(() -> true);
        provider.setConnectionStatusStore(realStore);

        provider.recordRefreshFailureStatus("seat@example.com",
                new IOException("400 Bad Request\n{\"error\":\"invalid_grant\"}"));
        provider.recordRefreshFailureStatus("seat@example.com", new IOException("503 Service Unavailable"));

        org.junit.jupiter.api.Assertions.assertTrue(published.isEmpty(), published.toString());
        org.junit.jupiter.api.Assertions.assertTrue(values.isEmpty(), values.toString());
    }

    @Test
    @DisplayName("best-effort: no connection-status store wired → no throw")
    void noStoreIsBestEffort() {
        GoogleProvider noStore = new GoogleProvider(
                refreshTokenStore, attributes, requestContext, authorizationContext, "authCtx");
        // setConnectionStatusStore NOT called

        assertDoesNotThrow(() -> noStore.recordRefreshFailureStatus("u", new IOException("invalid_grant")));
    }

    @Test
    @DisplayName("conversation authorization preserves the studio refresh-token key for OAuth callback")
    void authorizationForLogicalUserCarriesStudioTokenStoreKey() {
        when(attributes.getClientId()).thenReturn("cid");
        when(attributes.getAuthType()).thenReturn("Public");

        MustAuthorizeException exception =
                provider.createMustAuthorizationExceptionForUser("user@example.com", true);

        assertEquals("user@example.com_cid_Public", exception.getDetails().getFirst().getValue());
    }

    @Test
    @DisplayName("invalid_grant in admin/test-connection context prompts reauthorization instead of generic unauthorized")
    void invalidGrantInAdminContextPromptsReauthorization() {
        when(requestContext.invokeAsUser()).thenReturn(false);
        IOException cause = new IOException("400 Bad Request\n{\"error\":\"invalid_grant\"}");

        assertThrows(MustAuthorizeException.class,
                () -> provider.handleRefreshFailure("user@example.com", cause));

        verify(connectionStatusStore).put(eq("user@example.com"), eq("INV-1"),
                eq(CalendarConnectionStatus.REAUTH_REQUIRED), eq("ws_1"),
                eq("invalid_grant"), eq(cause.getMessage()));
    }

    @Test
    @DisplayName("transient refresh failure in admin context remains a normal validation failure")
    void transientFailureInAdminContextRemainsValidationFailure() {
        when(requestContext.invokeAsUser()).thenReturn(false);
        IOException cause = new IOException("503 Service Unavailable");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> provider.handleRefreshFailure("user@example.com", cause));

        assertSame(cause, exception.getCause());
        verify(connectionStatusStore).put(eq("user@example.com"), eq("INV-1"),
                eq(CalendarConnectionStatus.RENEWAL_FAILED), eq("ws_1"),
                eq("IOException"), eq(cause.getMessage()));
    }

    @Test
    @DisplayName("revokeStoredToken revokes at Google then removes the token under the studio-user key")
    void revokeStoredToken_revokesAndRemoves() throws Exception {
        OAuth20Service oauth = mock(OAuth20Service.class);
        when(attributes.getClientId()).thenReturn("cid");
        when(attributes.getAuthType()).thenReturn("atype");
        when(attributes.getOAuth20Service()).thenReturn(oauth);
        // studio user → key is userId_clientId_authType (NOT the raw userId)
        String key = "user@example.com_cid_atype";
        when(refreshTokenStore.get(key)).thenReturn("stored-refresh-token");

        boolean removed = provider.revokeStoredToken("user@example.com");

        assertTrue(removed);
        verify(oauth).revokeToken("stored-refresh-token");
        verify(refreshTokenStore).remove(key);
    }

    @Test
    @DisplayName("revokeStoredToken is a no-op (no revoke, no remove) when no token is stored")
    void revokeStoredToken_noToken_isNoOp() {
        when(attributes.getClientId()).thenReturn("cid");
        when(attributes.getAuthType()).thenReturn("atype");
        when(refreshTokenStore.get("user@example.com_cid_atype")).thenReturn(null);

        boolean removed = provider.revokeStoredToken("user@example.com");

        assertFalse(removed);
        verify(refreshTokenStore, never()).remove(anyString());
        verify(attributes, never()).getOAuth20Service();
    }

    @Test
    @DisplayName("forgetStoredToken removes under the correct studio-user key WITHOUT a Google revoke")
    void forgetStoredToken_removesUnderCorrectKey_noRevoke() {
        when(attributes.getClientId()).thenReturn("cid");
        when(attributes.getAuthType()).thenReturn("atype");
        // the bug being fixed: the key is userId_clientId_authType, NOT the raw userId
        when(refreshTokenStore.get("user@example.com_cid_atype")).thenReturn("stale-token");

        boolean removed = provider.forgetStoredToken("user@example.com");

        assertTrue(removed);
        verify(refreshTokenStore).remove("user@example.com_cid_atype");
        verify(attributes, never()).getOAuth20Service();
    }
}
