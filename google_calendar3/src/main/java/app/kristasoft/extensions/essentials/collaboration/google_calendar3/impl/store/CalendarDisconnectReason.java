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
 * Disconnect-intent values for proto field 9 {@code reason} on
 * {@code CalendarConnectionStatusChangedProto}. Meaningful only when
 * {@code status=DISCONNECTED}; ignored by CA for every other status.
 *
 * <p>See the calendar-reconnect extension review checklist §A / A.3:
 * <ul>
 *   <li>{@link #USER_INITIATED} — the user deliberately disconnected their calendar.
 *       CA <em>deletes</em> the {@code user_calendar_connection} row and sends no reminder.</li>
 *   <li>{@link #ACCESS_REVOKED} — an involuntary disconnect (token revoked, admin pulled
 *       access). CA keeps the row as broken and the reminder flow runs. Absent / null is
 *       treated the same way (the safe involuntary default), so only {@link #USER_INITIATED}
 *       ever needs to be sent explicitly.</li>
 * </ul>
 */
public final class CalendarDisconnectReason {

    /** Deliberate user disconnect — CA deletes the row, no reminder. */
    public static final String USER_INITIATED = "USER_INITIATED";

    /** Involuntary disconnect — CA keeps the row as broken (same as omitting the field). */
    public static final String ACCESS_REVOKED = "ACCESS_REVOKED";

    private CalendarDisconnectReason() {
    }
}
