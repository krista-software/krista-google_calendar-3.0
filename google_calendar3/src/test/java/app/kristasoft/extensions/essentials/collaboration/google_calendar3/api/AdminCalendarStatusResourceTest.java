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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatus;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.SendEventsToMAService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.ws.rs.core.Response;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminCalendarStatusResource} — the admin endpoint that
 * force-sets / reads a user's Google Calendar connection status. Mirrors the KIP
 * (Teams Transcribe) {@code forceCalendarStatus} admin contract.
 */
class AdminCalendarStatusResourceTest {

    private static final String INVOKER_ID = "INV_google-workspace-calendar-acme";
    private static final String WORKSPACE_ID = "ws-acme";
    private static final String ADMIN_KEY = "Theja";
    private static final String USER_KEY = "wsContact_0338ba14-7b9b-4da4-b6a9-cc3e6088a145";
    private static final String EMAIL = "nakul.mathur@kristasoft.info";
    private static final String FORCE_ERROR_CODE = "ADMIN_FORCE_SET";
    private static final String FORCE_ERROR_MESSAGE = "Forced from Google Calendar admin API";

    private CalendarConnectionStatusStore store;
    private RefreshTokenStore refreshTokenStore;
    private SendEventsToMAService sendEventsToMAService;
    private Invoker invoker;
    private AdminCalendarStatusResource resource;

    @BeforeEach
    void setUp() {
        store = mock(CalendarConnectionStatusStore.class);
        refreshTokenStore = mock(RefreshTokenStore.class);
        sendEventsToMAService = mock(SendEventsToMAService.class);
        invoker = mock(Invoker.class);
        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        lenient().when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        resource = new AdminCalendarStatusResource(store, refreshTokenStore, sendEventsToMAService, invoker);
    }

    // ---- auth gate -------------------------------------------------------

    @Test
    void forceRejectsMissingApiKey() {
        Response response = resource.forceCalendarStatus(null, USER_KEY, "CONNECTED", EMAIL);

        assertEquals(401, response.getStatus());
        assertEquals("Invalid or missing API key", bodyAsMap(response).get("error"));
        verify(store, never()).forceStatus(anyString(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void forceRejectsWrongApiKey() {
        Response response = resource.forceCalendarStatus("nope", USER_KEY, "CONNECTED", EMAIL);

        assertEquals(401, response.getStatus());
        assertEquals("Invalid or missing API key", bodyAsMap(response).get("error"));
        verify(store, never()).forceStatus(anyString(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void getRejectsWrongApiKey() {
        Response response = resource.getCalendarStatus("nope", USER_KEY);

        assertEquals(401, response.getStatus());
        verify(store, never()).get(anyString(), anyString());
    }

    // ---- force POST ------------------------------------------------------

    @Test
    void forceWithValidKeyAndStatus_callsStoreAndEchoesPublished() {
        when(store.forceStatus(USER_KEY, INVOKER_ID, CalendarConnectionStatus.REAUTH_REQUIRED,
                WORKSPACE_ID, EMAIL, FORCE_ERROR_CODE, FORCE_ERROR_MESSAGE)).thenReturn(true);

        Response response = resource.forceCalendarStatus(ADMIN_KEY, USER_KEY, "REAUTH_REQUIRED", EMAIL);

        assertEquals(200, response.getStatus());
        Map<String, Object> body = bodyAsMap(response);
        assertEquals(USER_KEY, body.get("userKey"));
        assertEquals("REAUTH_REQUIRED", body.get("status"));
        assertEquals(EMAIL, body.get("email"));
        assertEquals(true, body.get("published"));
        verify(store).forceStatus(USER_KEY, INVOKER_ID, CalendarConnectionStatus.REAUTH_REQUIRED,
                WORKSPACE_ID, EMAIL, FORCE_ERROR_CODE, FORCE_ERROR_MESSAGE);
    }

    @Test
    void forceReportsPublishedFalseWhenStoreSkips() {
        // e.g. wsContact key with no email — store persists but skips the publish.
        when(store.forceStatus(eq(USER_KEY), eq(INVOKER_ID), eq(CalendarConnectionStatus.CONNECTED),
                eq(WORKSPACE_ID), any(), eq(FORCE_ERROR_CODE), eq(FORCE_ERROR_MESSAGE))).thenReturn(false);

        Response response = resource.forceCalendarStatus(ADMIN_KEY, USER_KEY, "CONNECTED", null);

        assertEquals(200, response.getStatus());
        assertEquals(false, bodyAsMap(response).get("published"));
    }

    @Test
    void forceAcceptsAllFourStatusesCaseInsensitively() {
        when(store.forceStatus(anyString(), anyString(), any(), anyString(), any(), anyString(), anyString()))
                .thenReturn(true);

        for (CalendarConnectionStatus status : CalendarConnectionStatus.values()) {
            Response response = resource.forceCalendarStatus(ADMIN_KEY, USER_KEY,
                    status.name().toLowerCase(java.util.Locale.ROOT), EMAIL);

            assertEquals(200, response.getStatus(), "status " + status + " should be accepted");
            assertEquals(status.name(), bodyAsMap(response).get("status"));
            verify(store).forceStatus(USER_KEY, INVOKER_ID, status, WORKSPACE_ID, EMAIL,
                    FORCE_ERROR_CODE, FORCE_ERROR_MESSAGE);
        }
    }

    @Test
    void forceRejectsInvalidStatus() {
        Response response = resource.forceCalendarStatus(ADMIN_KEY, USER_KEY, "BOGUS", EMAIL);

        assertEquals(400, response.getStatus());
        assertEquals("Invalid or missing status", bodyAsMap(response).get("error"));
        verify(store, never()).forceStatus(anyString(), anyString(), any(), anyString(), any(),
                anyString(), anyString());
    }

    @Test
    void forceRejectsMissingStatus() {
        Response response = resource.forceCalendarStatus(ADMIN_KEY, USER_KEY, null, EMAIL);

        assertEquals(400, response.getStatus());
        verify(store, never()).forceStatus(anyString(), anyString(), any(), anyString(), any(),
                anyString(), anyString());
    }

    // ---- read GET --------------------------------------------------------

    @Test
    void getReturnsCurrentStatus() {
        when(store.get(USER_KEY, INVOKER_ID)).thenReturn(CalendarConnectionStatus.RENEWAL_FAILED);

        Response response = resource.getCalendarStatus(ADMIN_KEY, USER_KEY);

        assertEquals(200, response.getStatus());
        Map<String, Object> body = bodyAsMap(response);
        assertEquals(USER_KEY, body.get("userKey"));
        assertEquals("RENEWAL_FAILED", body.get("status"));
    }

    @Test
    void getReturnsUnknownWhenNoStatusRecorded() {
        when(store.get(USER_KEY, INVOKER_ID)).thenReturn(null);

        Response response = resource.getCalendarStatus(ADMIN_KEY, USER_KEY);

        assertEquals(200, response.getStatus());
        assertEquals("UNKNOWN", bodyAsMap(response).get("status"));
    }

    @SuppressWarnings("unchecked")
    // ---- simulate-expiry (test affordance for the genuine auto-detection path) ----

    @Test
    void simulateExpiryRejectsWrongApiKey() {
        Response response = resource.simulateTokenExpiry("nope", USER_KEY, 30L, true);

        assertEquals(401, response.getStatus());
        verify(refreshTokenStore, never()).put(anyString(), anyString());
        verify(sendEventsToMAService, never()).sendEventsToMAByUser(anyString(), anyLong());
    }

    @Test
    void simulateExpiryReturns404WhenNoToken() {
        when(refreshTokenStore.get(USER_KEY)).thenReturn(null);

        Response response = resource.simulateTokenExpiry(ADMIN_KEY, USER_KEY, 30L, true);

        assertEquals(404, response.getStatus());
        verify(refreshTokenStore, never()).put(anyString(), anyString());
        verify(sendEventsToMAService, never()).sendEventsToMAByUser(anyString(), anyLong());
    }

    @Test
    void simulateExpiryInvalidatesTokenAndTriggersSync() {
        when(refreshTokenStore.get(USER_KEY)).thenReturn("a-valid-refresh-token");

        Response response = resource.simulateTokenExpiry(ADMIN_KEY, USER_KEY, 30L, true);

        assertEquals(200, response.getStatus());
        Map<String, Object> body = bodyAsMap(response);
        assertEquals(USER_KEY, body.get("userKey"));
        assertEquals(true, body.get("simulatedExpiry"));
        assertEquals(true, body.get("syncTriggered"));
        // The stored token is overwritten with a non-blank, clearly-different value so the next
        // real sync produces a genuine invalid_grant (reconnect later restores a valid token)...
        verify(refreshTokenStore).put(eq(USER_KEY),
                argThat(v -> v != null && !v.isBlank() && !v.equals("a-valid-refresh-token")));
        // ...and the sync is triggered immediately so detection fires in this request.
        verify(sendEventsToMAService).sendEventsToMAByUser(USER_KEY, 30L);
    }

    @Test
    void simulateExpiryWithoutTriggerSync_invalidatesButDoesNotSync() {
        when(refreshTokenStore.get(USER_KEY)).thenReturn("a-valid-refresh-token");

        Response response = resource.simulateTokenExpiry(ADMIN_KEY, USER_KEY, 30L, false);

        assertEquals(200, response.getStatus());
        assertEquals(false, bodyAsMap(response).get("syncTriggered"));
        verify(refreshTokenStore).put(eq(USER_KEY), anyString());
        verify(sendEventsToMAService, never()).sendEventsToMAByUser(anyString(), anyLong());
    }

    private static Map<String, Object> bodyAsMap(Response response) {
        return (Map<String, Object>) response.getEntity();
    }
}
