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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store;

/**
 * Represents the last-known connection state of a user's Google Calendar OAuth token.
 *
 * <p>Set by {@link CalendarConnectionStatusStore} when the OAuth flow completes
 * or when a token refresh fails. Surfaced to CA Service via ESB so the frontend
 * and reconciliation tooling can guide users through reconnection.
 *
 * @since 3.0.2
 */
public enum CalendarConnectionStatus {

    /** OAuth token is valid; last operation succeeded. */
    CONNECTED,

    /**
     * OAuth refresh token is expired or user consent is required.
     * The user must re-authenticate via the Google OAuth flow.
     */
    REAUTH_REQUIRED,

    /** A token refresh or API call failed for a transient or general reason. */
    RENEWAL_FAILED,

    /** The user was explicitly disconnected / their token was removed. */
    DISCONNECTED;

    /**
     * Returns {@code true} when the status indicates the connection is broken and
     * the user cannot currently sync calendar events.
     */
    public boolean isBroken() {
        return this == REAUTH_REQUIRED || this == RENEWAL_FAILED || this == DISCONNECTED;
    }
}
