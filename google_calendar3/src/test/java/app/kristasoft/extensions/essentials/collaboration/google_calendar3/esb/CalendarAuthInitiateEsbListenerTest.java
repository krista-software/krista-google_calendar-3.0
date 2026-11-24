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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb;

import app.krista.conversationagent.module.calendar.esb.CalendarAuthInitiateCommandProto;
import app.krista.conversationagent.module.calendar.esb.CalendarAuthInitiateReplyProto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.auth.GoogleOAuthUrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CalendarAuthInitiateEsbListener} (Google variant).
 */
@ExtendWith(MockitoExtension.class)
class CalendarAuthInitiateEsbListenerTest {

    @Mock
    private GoogleOAuthUrlService oauthUrlService;

    private CalendarAuthInitiateEsbListener listener;

    @BeforeEach
    void setUp() {
        listener = new CalendarAuthInitiateEsbListener(oauthUrlService);
    }

    @Test
    @DisplayName("handle returns ok=true with Google authUrl on success")
    void successPath() {
        when(oauthUrlService.buildAuthUrl("ws-1", "user@example.com"))
                .thenReturn(new GoogleOAuthUrlService.AuthUrlResult(
                        "https://accounts.google.com/o/oauth2/v2/auth?client_id=x", 180));

        CalendarAuthInitiateCommandProto cmd = new CalendarAuthInitiateCommandProto();
        cmd.setWorkspaceId("ws-1");
        cmd.setUserEmail("user@example.com");
        cmd.setCalendarSystem("GOOGLE");
        cmd.setCorrelationId("corr-1");

        CalendarAuthInitiateReplyProto reply = listener.handle(cmd);

        assertTrue(reply.isOk());
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth?client_id=x", reply.getAuthUrl());
        assertTrue(reply.getAuthInitiatedAtEpochMillis() > 0);
        assertTrue(reply.getExpiresAtEpochMillis() > reply.getAuthInitiatedAtEpochMillis());
    }

    @Test
    @DisplayName("calendarSystem is matched case-insensitively")
    void acceptsLowercaseCalendarSystem() {
        when(oauthUrlService.buildAuthUrl(anyString(), anyString()))
                .thenReturn(new GoogleOAuthUrlService.AuthUrlResult(
                        "https://accounts.google.com/o/oauth2/v2/auth?x=1", 180));

        CalendarAuthInitiateCommandProto cmd = new CalendarAuthInitiateCommandProto();
        cmd.setWorkspaceId("ws-1");
        cmd.setUserEmail("user@example.com");
        cmd.setCalendarSystem("google"); // lowercase
        cmd.setCorrelationId("corr-1");

        CalendarAuthInitiateReplyProto reply = listener.handle(cmd);

        assertTrue(reply.isOk());
    }

    @Test
    @DisplayName("rejects SYSTEM_MISMATCH when calendarSystem doesn't match (§B.2)")
    void rejectsSystemMismatch() {
        CalendarAuthInitiateCommandProto cmd = new CalendarAuthInitiateCommandProto();
        cmd.setWorkspaceId("ws-1");
        cmd.setUserEmail("user@example.com");
        cmd.setCalendarSystem("MICROSOFT");
        cmd.setCorrelationId("corr-1");

        CalendarAuthInitiateReplyProto reply = listener.handle(cmd);

        assertFalse(reply.isOk());
        assertEquals(CalendarAuthInitiateEsbListener.ERROR_SYSTEM_MISMATCH, reply.getErrorCode());
        verifyNoInteractions(oauthUrlService);
    }

    @Test
    @DisplayName("rejects SYSTEM_MISMATCH when calendarSystem is null")
    void rejectsNullSystem() {
        CalendarAuthInitiateCommandProto cmd = new CalendarAuthInitiateCommandProto();
        cmd.setWorkspaceId("ws-1");
        cmd.setUserEmail("user@example.com");
        cmd.setCalendarSystem(null);

        CalendarAuthInitiateReplyProto reply = listener.handle(cmd);

        assertFalse(reply.isOk());
        assertEquals(CalendarAuthInitiateEsbListener.ERROR_SYSTEM_MISMATCH, reply.getErrorCode());
    }

    @Test
    @DisplayName("returns NOT_REGISTERED when no OAuth configuration exists (§B.3)")
    void notRegisteredForUnknownWorkspace() {
        when(oauthUrlService.buildAuthUrl(anyString(), anyString()))
                .thenThrow(new NoSuchElementException("No Google OAuth configuration for workspace ws-1"));

        CalendarAuthInitiateCommandProto cmd = new CalendarAuthInitiateCommandProto();
        cmd.setWorkspaceId("ws-1");
        cmd.setUserEmail("user@example.com");
        cmd.setCalendarSystem("GOOGLE");
        cmd.setCorrelationId("corr-1");

        CalendarAuthInitiateReplyProto reply = listener.handle(cmd);

        assertFalse(reply.isOk());
        assertEquals(CalendarAuthInitiateEsbListener.ERROR_NOT_REGISTERED, reply.getErrorCode());
    }

    @Test
    @DisplayName("returns INVALID_REQUEST when userEmail is rejected by the URL service")
    void invalidRequest() {
        when(oauthUrlService.buildAuthUrl(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("userEmail is required"));

        CalendarAuthInitiateCommandProto cmd = new CalendarAuthInitiateCommandProto();
        cmd.setWorkspaceId("ws-1");
        cmd.setUserEmail("");
        cmd.setCalendarSystem("GOOGLE");

        CalendarAuthInitiateReplyProto reply = listener.handle(cmd);

        assertFalse(reply.isOk());
        assertEquals(CalendarAuthInitiateEsbListener.ERROR_INVALID_REQUEST, reply.getErrorCode());
    }

    @Test
    @DisplayName("returns INTERNAL_ERROR on unexpected exceptions")
    void unexpectedFailure() {
        when(oauthUrlService.buildAuthUrl(anyString(), anyString()))
                .thenThrow(new RuntimeException("kaboom"));

        CalendarAuthInitiateCommandProto cmd = new CalendarAuthInitiateCommandProto();
        cmd.setWorkspaceId("ws-1");
        cmd.setUserEmail("user@example.com");
        cmd.setCalendarSystem("GOOGLE");

        CalendarAuthInitiateReplyProto reply = listener.handle(cmd);

        assertFalse(reply.isOk());
        assertEquals(CalendarAuthInitiateEsbListener.ERROR_INTERNAL, reply.getErrorCode());
    }
}
