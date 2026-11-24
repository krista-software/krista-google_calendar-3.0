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
import com.google.api.services.calendar.model.ConferenceData;
import com.google.api.services.calendar.model.EntryPoint;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GoogleEventToMeetingDtoMapperTest {

    private GoogleEventToMeetingDtoMapper mapper;

    private static Event timedEvent(long startMs, long endMs) {
        return new Event()
                .setId("evt-1").setSummary("Standup").setICalUID("ical-1")
                .setStart(new EventDateTime().setDateTime(new DateTime(startMs)))
                .setEnd(new EventDateTime().setDateTime(new DateTime(endMs)));
    }

    @BeforeEach
    void setUp() {
        mapper = new GoogleEventToMeetingDtoMapper();
    }

    @Test
    void noLinkRequiresAnotherPersonBeforeMailboxEnrichment() {
        Event event = new Event().setOrganizer(new Event.Organizer().setEmail("owner@example.com"));
        assertFalse(mapper.isEligibleCalendarEvent(event));
        event.setAttendees(List.of(new EventAttendee().setEmail("owner@example.com")));
        assertFalse(mapper.isEligibleCalendarEvent(event));
        event.setAttendees(List.of(new EventAttendee().setEmail("other@example.com")));
        assertTrue(mapper.isEligibleCalendarEvent(event));
        event.getAttendees().get(0).setResource(true);
        assertFalse(mapper.isEligibleCalendarEvent(event));
        event.setHangoutLink("https://meet.google.com/abc-defg-hij");
        assertTrue(mapper.isEligibleCalendarEvent(event));
    }

    @Test
    void map_nullEvent_returnsNull() {
        assertNull(mapper.map(null));
    }

    @Test
    void map_missingStartOrEnd_returnsNull() {
        assertNull(mapper.map(new Event().setId("e").setSummary("no times")));
    }

    @Test
    void map_endNotAfterStart_returnsNull() {
        long t = 1_800_000_000_000L;
        assertNull(mapper.map(timedEvent(t, t))); // equal -> not after
    }

    @Test
    void map_validTimedEvent_returnsDto() {
        long start = 1_800_000_000_000L;
        Event e = timedEvent(start, start + 3_600_000L);
        e.setOrganizer(new Event.Organizer().setEmail("org@x.com").setDisplayName("Org"));
        e.setAttendees(List.of(new EventAttendee().setEmail("a1@x.com").setDisplayName("A1")));
        e.setHangoutLink("https://meet.google.com/abc-defg-hij");
        e.setDescription("Weekly sync");

        CreateMeetingRequestDto dto = mapper.map(e);

        assertNotNull(dto);
        assertNotNull(dto.getOrganizer());
        assertEquals("org@x.com", dto.getOrganizer().getEmail());
        assertFalse(dto.getAttendees().isEmpty());
    }

    @Test
    void extractMeetingUrl_prefersConferenceDataVideoEntryPoint() {
        Event e = timedEvent(1_800_000_000_000L, 1_800_000_100_000L);
        EntryPoint ep = new EntryPoint().setEntryPointType("video").setUri("https://zoom.us/j/123");
        e.setConferenceData(new ConferenceData().setEntryPoints(List.of(ep)));
        assertEquals("https://zoom.us/j/123", mapper.extractMeetingUrl(e));
    }

    @Test
    void extractMeetingUrl_fallsBackToHangoutLink() {
        Event e = timedEvent(1L, 2L).setHangoutLink("https://meet.google.com/xyz");
        assertEquals("https://meet.google.com/xyz", mapper.extractMeetingUrl(e));
    }

    @Test
    void extractMeetingUrl_fromLocationUrl() {
        Event e = timedEvent(1L, 2L).setLocation("https://teams.microsoft.com/l/meetup/1");
        assertEquals("https://teams.microsoft.com/l/meetup/1", mapper.extractMeetingUrl(e));
    }

    @Test
    void extractMeetingUrl_noneReturnsNull() {
        assertNull(mapper.extractMeetingUrl(new Event().setId("e")));
    }

    @Test
    void detectPlatformFromUrl_variousUrls_doNotThrow() {
        for (String url : new String[]{
                "https://meet.google.com/abc", "https://zoom.us/j/1",
                "https://teams.microsoft.com/l/1", "https://example.com/x"}) {
            assertDoesNotThrow(() -> mapper.detectPlatformFromUrl(url));
        }
    }

    @Test
    void mapWithCalendarOwner_injectsOwnerEmailWhenAbsent() {
        long start = 1_800_000_000_000L;
        Event e = timedEvent(start, start + 3_600_000L);
        e.setAttendees(List.of(new EventAttendee().setEmail("a1@x.com")));

        CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(e, "owner@x.com", "admin@corp.com");

        assertNotNull(dto);
        boolean ownerPresent = dto.getAttendees().stream()
                .anyMatch(p -> p.getEmail() != null && p.getEmail().equalsIgnoreCase("owner@x.com"));
        assertTrue(ownerPresent);
    }

    @Test
    void mapWithCalendarOwner_nullEvent_returnsNull() {
        assertNull(mapper.mapWithCalendarOwner(null, "owner@x.com"));
    }
}
