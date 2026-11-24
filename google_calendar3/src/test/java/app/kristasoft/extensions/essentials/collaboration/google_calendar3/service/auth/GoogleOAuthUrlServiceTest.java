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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.auth;

import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.GoogleAttributes;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.GoogleAttributeStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import com.github.scribejava.core.oauth.OAuth20Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GoogleOAuthUrlService#resolveStateKey(String)} - the reconnect
 * OAuth {@code state} must equal the existing {@code wsContact_{invokerId}_{email}} token
 * key so the callback overwrites the key the sync path reads (not the bare email).
 */
@ExtendWith(MockitoExtension.class)
class GoogleOAuthUrlServiceTest {

    @Mock private GoogleAttributeStore attributeStore;
    @Mock private RefreshTokenStore refreshTokenStore;
    @Mock private UserKeyIndexStore userKeyIndexStore;
    @Mock private Invoker invoker;

    private GoogleOAuthUrlService service;

    @BeforeEach
    void setUp() {
        service = new GoogleOAuthUrlService(attributeStore, refreshTokenStore, userKeyIndexStore, invoker);
    }

    @Test
    @DisplayName("returns the existing wsContact key for the email")
    void resolvesExistingWsContactKey() {
        when(refreshTokenStore.getKey()).thenReturn(List.of(
                "someUnrelatedKey",
                "wsContact_INV-1_user@example.com"));

        assertEquals("wsContact_INV-1_user@example.com",
                service.resolveStateKey("user@example.com"));
    }

    @Test
    @DisplayName("ignores non-wsContact keys that happen to contain the email")
    void ignoresNonWsContactKeys() {
        when(refreshTokenStore.getKey()).thenReturn(List.of(
                "user@example.com_client_AUTHTYPE",          // studio-style key, not wsContact
                "wsContact_INV-1_user@example.com"));

        assertEquals("wsContact_INV-1_user@example.com",
                service.resolveStateKey("user@example.com"));
    }

    @Test
    @DisplayName("matches emails whose local part contains an underscore")
    void matchesEmailWithUnderscoreLocalPart() {
        when(refreshTokenStore.getKey()).thenReturn(List.of(
                "wsContact_INV-1_foo_bar@example.com"));

        assertEquals("wsContact_INV-1_foo_bar@example.com",
                service.resolveStateKey("foo_bar@example.com"));
    }

    @Test
    @DisplayName("falls back to the email when no wsContact key matches")
    void fallsBackToEmailWhenNoMatch() {
        when(refreshTokenStore.getKey()).thenReturn(List.of(
                "wsContact_INV-1_other@example.com"));

        assertEquals("user@example.com", service.resolveStateKey("user@example.com"));
    }

    @Test
    @DisplayName("falls back to the email when the token store throws")
    void fallsBackToEmailOnLookupFailure() {
        when(refreshTokenStore.getKey()).thenThrow(new RuntimeException("store unavailable"));

        assertEquals("user@example.com", service.resolveStateKey("user@example.com"));
    }

    @Test
    @DisplayName("resolveStateKey prefers the indexed key (real wsContact_<UUID> has no email suffix)")
    void resolveStateKeyPrefersIndexedKey() {
        when(invoker.getInvokerId()).thenReturn("INV-1");
        service = new GoogleOAuthUrlService(attributeStore, refreshTokenStore, userKeyIndexStore, invoker);
        when(userKeyIndexStore.get("INV-1", "user@example.com")).thenReturn("wsContact_1ba35a88-5270-4261-a4ea-7e4a1bd1b1f3");

        assertEquals("wsContact_1ba35a88-5270-4261-a4ea-7e4a1bd1b1f3",
                service.resolveStateKey("user@example.com"));
        verify(refreshTokenStore, never()).getKey();
    }

    @Test
    @DisplayName("buildAuthUrl appends the resolved key + invokerId + a CANONICAL (stage-stripped) forwardPath")
    void buildAuthUrlUsesIndexedKeyAndCanonicalForwardPath() {
        when(invoker.getInvokerId()).thenReturn("INV-1");
        service = new GoogleOAuthUrlService(attributeStore, refreshTokenStore, userKeyIndexStore, invoker);

        String resolvedKey = "wsContact_1ba35a88-5270-4261-a4ea-7e4a1bd1b1f3";
        GoogleAttributes attrs = mock(GoogleAttributes.class);
        when(attrs.getClientId()).thenReturn("cid");
        when(attrs.getClientSecret()).thenReturn("secret");
        when(attributeStore.load("INV-1")).thenReturn(attrs);
        when(userKeyIndexStore.get("INV-1", "user@example.com")).thenReturn(resolvedKey);
        OAuth20Service oauth = mock(OAuth20Service.class);
        when(attrs.getOAuth20Service()).thenReturn(oauth);
        // The building stage's forwardPath carries its own /draft segment...
        when(attrs.getForwardPath())
                .thenReturn("https://ext/extension/api/tok/draft/rest/googlecalendar/v3/oauth/callback");
        ArgumentCaptor<String> authUrlState = ArgumentCaptor.forClass(String.class);
        when(oauth.getAuthorizationUrl(authUrlState.capture())).thenReturn("https://accounts.google.com/o/oauth2/auth");

        service.buildAuthUrl("ws_1", "User@Example.com");

        // ...which is stripped so the callback routes to the live stage; the state still carries the
        // resolved wsContact key + the invokerId. No temp authContext, appendForwardPath not used.
        assertEquals(resolvedKey + "#invokerId_INV-1#https://ext/extension/api/tok/rest/googlecalendar/v3/oauth/callback",
                authUrlState.getValue());
        verify(attrs, never()).appendForwardPath(anyString());
        verify(attributeStore, never()).save(any(GoogleAttributes.class));
    }

    @Test
    @DisplayName("buildAuthUrl falls back to the bare email and appends the canonical forwardPath")
    void buildAuthUrlAppendsForwardPathOnEmailFallback() {
        GoogleAttributes attrs = mock(GoogleAttributes.class);
        when(attrs.getClientId()).thenReturn("cid");
        when(attrs.getClientSecret()).thenReturn("secret");
        when(attributeStore.load(any())).thenReturn(attrs);
        when(refreshTokenStore.getKey()).thenReturn(List.of()); // no wsContact key; index unstubbed -> null
        OAuth20Service oauth = mock(OAuth20Service.class);
        when(attrs.getOAuth20Service()).thenReturn(oauth);
        when(attrs.getForwardPath())
                .thenReturn("https://ext/extension/api/tok/test/rest/googlecalendar/v3/oauth/callback");
        ArgumentCaptor<String> authUrlState = ArgumentCaptor.forClass(String.class);
        when(oauth.getAuthorizationUrl(authUrlState.capture())).thenReturn("https://auth");

        service.buildAuthUrl("ws_1", "user@example.com"); // invoker.getInvokerId() unstubbed -> null, so no invokerId segment

        assertEquals("user@example.com#https://ext/extension/api/tok/rest/googlecalendar/v3/oauth/callback",
                authUrlState.getValue());
        verify(attributeStore, never()).save(any(GoogleAttributes.class));
    }

    @Test
    @DisplayName("canonicalizeForwardPath strips a /draft or /test stage segment, leaves the live path untouched")
    void canonicalizeForwardPath_stripsStageSegment() {
        assertEquals("https://ext/extension/api/tok/rest/googlecalendar/v3/oauth/callback",
                GoogleOAuthUrlService.canonicalizeForwardPath("https://ext/extension/api/tok/draft/rest/googlecalendar/v3/oauth/callback"));
        assertEquals("https://ext/extension/api/tok/rest/googlecalendar/v3/oauth/callback",
                GoogleOAuthUrlService.canonicalizeForwardPath("https://ext/extension/api/tok/test/rest/googlecalendar/v3/oauth/callback"));
        // already live (no stage segment) — unchanged
        String live = "https://ext/extension/api/tok/rest/googlecalendar/v3/oauth/callback";
        assertEquals(live, GoogleOAuthUrlService.canonicalizeForwardPath(live));
        // null/blank safe
        assertNull(GoogleOAuthUrlService.canonicalizeForwardPath(null));
    }
}
