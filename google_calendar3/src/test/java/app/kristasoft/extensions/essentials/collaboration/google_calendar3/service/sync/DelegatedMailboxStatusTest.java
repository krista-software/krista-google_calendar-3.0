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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationException;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an Always On DISCONNECTED carries, and that publishing it can never fail a sweep.
 */
@DisplayName("Always On mailbox status")
class DelegatedMailboxStatusTest {

    private static GoogleJsonResponseException apiError(int status, String reason, String message) {
        GoogleJsonError details = new GoogleJsonError();
        details.setCode(status);
        details.setMessage(message);
        GoogleJsonError.ErrorInfo info = new GoogleJsonError.ErrorInfo();
        info.setReason(reason);
        info.setMessage(message);
        details.setErrors(List.of(info));
        HttpResponseException.Builder builder =
                new HttpResponseException.Builder(status, "Forbidden", new HttpHeaders());
        return new GoogleJsonResponseException(builder, details);
    }

    @Test
    @DisplayName("A Calendar API error carries Google's own reason and message, through any wrapping")
    void apiErrorCarriesGooglesReason() {
        Exception wrapped = new RuntimeException("Failed to create watch channel",
                apiError(403, "forbidden", "Not Authorized to access this resource/api"));

        DelegatedMailboxStatus.ProviderError error = DelegatedMailboxStatus.describe(wrapped);

        assertEquals("forbidden", error.code());
        assertEquals("Not Authorized to access this resource/api", error.message());
    }

    @Test
    @DisplayName("A token-endpoint refusal quoted by the auth library carries its OAuth error")
    void tokenRefusalCarriesTheOauthError() {
        Exception wrapped = new RuntimeException("Failed to create watch channel", new IOException(
                "Error getting access token for service account: 401 Unauthorized POST "
                        + "https://oauth2.googleapis.com/token {\"error\":\"unauthorized_client\","
                        + "\"error_description\":\"Client is unauthorized to retrieve access tokens\"}"));

        assertEquals("unauthorized_client", DelegatedMailboxStatus.describe(wrapped).code());
    }

    @Test
    @DisplayName("Anything else carries the deepest failure's type and message")
    void otherFailuresCarryTheDeepestCause() {
        Exception wrapped = new RuntimeException("outer", new IllegalStateException("Could not build",
                new DomainWideDelegationException("the service-account key is unreadable", null)));

        DelegatedMailboxStatus.ProviderError error = DelegatedMailboxStatus.describe(wrapped);

        assertEquals("DomainWideDelegationException", error.code());
        assertEquals("the service-account key is unreadable", error.message());
    }

    @Test
    @DisplayName("A long provider message is bounded")
    void longMessageIsBounded() {
        String message = "x".repeat(DelegatedMailboxStatus.MAX_ERROR_MESSAGE_LENGTH * 3);

        String carried = DelegatedMailboxStatus.describe(new IOException(message)).message();

        assertTrue(carried.length() <= DelegatedMailboxStatus.MAX_ERROR_MESSAGE_LENGTH + 1, carried);
    }

    @Test
    @DisplayName("No cause still produces a code, never a null")
    void nullCauseHasACode() {
        assertEquals("UNKNOWN", DelegatedMailboxStatus.describe(null).code());
    }

    @Test
    @DisplayName("Unwired statuses are a no-op, and a failing store never escapes")
    void neverThrows() {
        Invoker invoker = mock(Invoker.class);
        when(invoker.getInvokerId()).thenReturn("INV-1");
        when(invoker.getWorkspaceId()).thenReturn("ws-1");

        DelegatedMailboxStatus unwired = new DelegatedMailboxStatus(null, invoker);
        assertDoesNotThrow(() -> unwired.unreachable("a@acme.example", new IOException("x")));

        CalendarConnectionStatusStore failing = mock(CalendarConnectionStatusStore.class);
        when(failing.recordDelegatedConnected(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("kv down"));
        when(failing.recordDelegatedUnreachable(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("kv down"));
        DelegatedMailboxStatus status = new DelegatedMailboxStatus(failing, invoker);

        assertDoesNotThrow(() -> status.reachable("a@acme.example"));
        assertDoesNotThrow(() -> status.unreachable("a@acme.example", new IOException("x")));
        assertDoesNotThrow(() -> status.leftScanSet(List.of("b@acme.example")));
    }

    @Test
    @DisplayName("A blank mailbox key is ignored rather than published")
    void blankKeyIsIgnored() {
        CalendarConnectionStatusStore store = mock(CalendarConnectionStatusStore.class);
        DelegatedMailboxStatus status = new DelegatedMailboxStatus(store, mock(Invoker.class));

        status.reachable(" ");
        status.unreachable(null, new IOException("x"));

        verify(store, never()).recordDelegatedConnected(any(), any(), any(), any());
        verify(store, never()).recordDelegatedUnreachable(any(), any(), any(), any(), any(), any());
    }
}
