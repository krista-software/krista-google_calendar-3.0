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

import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;

/** Stable CA identity: Google's iCalUID identifies a series, not an occurrence. */
public final class GoogleEventIdentity {
    private GoogleEventIdentity() { }

    public static String meetingUid(Event event) {
        if (event == null || event.getICalUID() == null) return null;
        String uid = event.getICalUID();
        if (event.getRecurringEventId() == null) return uid;
        EventDateTime original = event.getOriginalStartTime();
        if (original == null) original = event.getStart();
        if (original != null && original.getDateTime() != null) {
            return uid + "#" + original.getDateTime().getValue();
        }
        if (original != null && original.getDate() != null) {
            return uid + "#" + original.getDate().toStringRfc3339();
        }
        // Never send a series-wide UID for an occurrence with incomplete identity.
        return null;
    }

    public static String originalStart(Event event) {
        EventDateTime start = event.getOriginalStartTime() != null
                ? event.getOriginalStartTime() : event.getStart();
        if (start == null) return null;
        return start.getDateTime() != null ? start.getDateTime().toStringRfc3339()
                : start.getDate() != null ? start.getDate().toStringRfc3339() : null;
    }
}
