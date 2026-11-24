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

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoogleEventIdentityTest {
    private Event occurrence(String start) {
        return new Event().setICalUID("series@google.com").setRecurringEventId("master")
                .setOriginalStartTime(new EventDateTime().setDateTime(new DateTime(start)));
    }

    @Test void siblingsHaveDifferentCaIdentities() {
        assertNotEquals(GoogleEventIdentity.meetingUid(occurrence("2026-09-16T10:00:00Z")),
                GoogleEventIdentity.meetingUid(occurrence("2026-09-17T10:00:00Z")));
    }

    @Test void movingOccurrencePreservesIdentityAcrossTimezonesAndCalendarCopies() {
        Event original = occurrence("2026-09-16T10:00:00Z").setId("copy-a");
        Event moved = occurrence("2026-09-16T15:30:00+05:30").setId("copy-b")
                .setStart(new EventDateTime().setDateTime(new DateTime("2026-09-20T12:00:00Z")));
        assertEquals(GoogleEventIdentity.meetingUid(original), GoogleEventIdentity.meetingUid(moved));
    }

    @Test void oneOffUidIsUnchanged() {
        assertEquals("one@google.com", GoogleEventIdentity.meetingUid(new Event().setICalUID("one@google.com")));
    }

    @Test void incompleteOccurrenceNeverUsesSeriesUid() {
        assertNull(GoogleEventIdentity.meetingUid(new Event().setICalUID("series@google.com").setRecurringEventId("master")));
    }

    @Test void allDayOccurrenceHasStableDateIdentity() {
        Event event = new Event().setICalUID("series@google.com").setRecurringEventId("master")
                .setOriginalStartTime(new EventDateTime().setDate(new DateTime("2026-09-16")));
        assertEquals("series@google.com#2026-09-16", GoogleEventIdentity.meetingUid(event));
    }
}
