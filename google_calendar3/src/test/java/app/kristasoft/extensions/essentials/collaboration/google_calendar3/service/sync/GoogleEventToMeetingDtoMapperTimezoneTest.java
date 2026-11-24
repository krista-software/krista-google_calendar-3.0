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

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model.CreateMeetingRequestDto;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Which timezone this mapper reports as the organizer's.
 *
 * <p>Meeting Agent stores whatever lands here as {@code meeting.timezone} and stamps it onto every
 * transcript line, so a wrong answer is not a metadata nicety — it is the difference between a
 * transcript that reads {@code [08:31:00 UTC-5]} and one that reads {@code [13:31:00 UTC]} for a
 * meeting that happened at half past eight in the morning.
 */
@DisplayName("GoogleEventToMeetingDtoMapper — organizer timezone")
class GoogleEventToMeetingDtoMapperTimezoneTest {

    private final GoogleEventToMeetingDtoMapper mapper = new GoogleEventToMeetingDtoMapper();

    /** An event whose dateTime carries an offset, with an optional explicit zone. */
    private static Event event(String zone, int offsetMinutes) {
        long startMs = 1_757_000_000_000L;
        EventDateTime start = new EventDateTime()
                .setDateTime(new DateTime(startMs, offsetMinutes));
        EventDateTime end = new EventDateTime()
                .setDateTime(new DateTime(startMs + 3_600_000L, offsetMinutes));
        if (zone != null) {
            start.setTimeZone(zone);
            end.setTimeZone(zone);
        }
        return new Event()
                .setId("evt-1")
                .setSummary("Standup")
                .setStart(start)
                .setEnd(end);
    }

    @Test
    @DisplayName("an explicit start.timeZone is used as-is")
    void explicitZoneWins() {
        CreateMeetingRequestDto dto = mapper.map(event("America/Chicago", -300));

        assertNotNull(dto);
        assertEquals("America/Chicago", dto.getTimezone());
    }

    @Test
    @DisplayName("with NO start.timeZone the event's own offset is used, NOT a hard-coded UTC")
    void absentZoneFallsBackToTheEventOffset() {
        // Google only populates start.timeZone when the organizer explicitly picked one, so on most
        // events it is null. This used to fall through to the literal "UTC" — which Meeting Agent
        // dutifully stored as the organizer's timezone and stamped onto every transcript line, so a
        // Chicago meeting came back reading 13:31 instead of 08:31.
        //
        // The offset is a real limitation (it cannot survive a DST boundary the way a named zone
        // would) but it is the correct local time for THIS event, which is what a timestamp needs.
        CreateMeetingRequestDto dto = mapper.map(event(null, -300));

        assertNotNull(dto);
        assertNotEquals("UTC", dto.getTimezone(),
                "an absent start.timeZone must not be reported as the organizer being on UTC");
        assertEquals("-05:00", dto.getTimezone());
    }

    @Test
    @DisplayName("an event genuinely on UTC still reports UTC")
    void genuineUtcStillReportsUtc() {
        // The fallback must not overshoot: a zero offset really is UTC, and reporting it is correct.
        CreateMeetingRequestDto dto = mapper.map(event(null, 0));

        assertNotNull(dto);
        assertEquals("Z", dto.getTimezone(),
                "a zero offset is UTC; 'Z' is the ZoneOffset id for it and resolves as UTC");
    }
}
