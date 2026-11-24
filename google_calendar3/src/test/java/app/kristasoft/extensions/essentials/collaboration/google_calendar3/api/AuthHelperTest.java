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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link AuthHelper#getInvokerId(String)} — extracts the invokerId carried in the
 * reconnect OAuth {@code state} (shape {@code key#invokerId_<id>#forwardPath}) so the forwarded
 * callback can load the OAuth client without relying on the forwarded-thread {@code Invoker}.
 */
class AuthHelperTest {

    @Test
    @DisplayName("getInvokerId extracts the id between the marker and the next '#'")
    void getInvokerId_extractsFromState() {
        String state = "wsContact_1ba35a88#invokerId_INV-1#https://ext/extension/api/tok/draft/rest/googlecalendar/v3/oauth/callback";

        assertEquals("INV-1", AuthHelper.getInvokerId(state));
    }

    @Test
    @DisplayName("getInvokerId handles an invokerId that itself contains underscores (INV_<uuid>)")
    void getInvokerId_handlesUnderscoreId() {
        String state = "wsContact_1ba35a88#invokerId_INV_32d3d914-8545#https://ext/.../callback";

        assertEquals("INV_32d3d914-8545", AuthHelper.getInvokerId(state));
    }

    @Test
    @DisplayName("getInvokerId returns the id when it is the last segment (no forwardPath)")
    void getInvokerId_lastSegment() {
        assertEquals("INV-1", AuthHelper.getInvokerId("wsContact_1ba35a88#invokerId_INV-1"));
    }

    @Test
    @DisplayName("getInvokerId returns null when no invokerId marker is present")
    void getInvokerId_absent() {
        assertNull(AuthHelper.getInvokerId("wsContact_1ba35a88#https://ext/.../callback"));
        assertNull(AuthHelper.getInvokerId("wsContact_1ba35a88#authContextId_abc#https://ext/.../callback"));
    }

    @Test
    @DisplayName("getInvokerId returns null for null/empty state")
    void getInvokerId_nullOrEmpty() {
        assertNull(AuthHelper.getInvokerId(null));
        assertNull(AuthHelper.getInvokerId(""));
    }

    @Test
    @DisplayName("getAuthContextId strips account metadata from the auth context segment")
    void getAuthContextId_stripsAccountMetadata() {
        String state = "user@example.com_client_Private#authContextId_abc|kristaAccountId=wsContact_123#https://ext/.../callback";

        assertEquals("authContextId_abc", AuthHelper.getAuthContextId(state));
    }
}
