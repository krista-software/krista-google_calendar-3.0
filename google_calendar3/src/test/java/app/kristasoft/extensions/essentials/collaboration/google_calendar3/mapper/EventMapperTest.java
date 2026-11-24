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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.mapper;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.entity.EventDetails;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for EventMapper.
 * 
 * <p>This test class validates all mapper operations for transforming between
 * Google Calendar Event objects and EventDetails DTOs.</p>
 * 
 * <h2>Testing Strategy:</h2>
 * <ul>
 *   <li><b>Arrange-Act-Assert (AAA):</b> Clear separation of setup, execution, and validation</li>
 *   <li><b>Descriptive Names:</b> Behavior-driven test naming (shouldX_whenY)</li>
 *   <li><b>Positive & Negative:</b> Both success and failure scenarios covered</li>
 *   <li><b>Edge Cases:</b> Null, empty, and boundary conditions tested</li>
 * </ul>
 * 
 * @author Krista Software
 * @version 1.0.0
 */
@DisplayName("EventMapper Mapper Layer Tests")
class EventMapperTest {

    private static final String TEST_EVENT_ID = "test-event-123";
    private static final String TEST_TITLE = "Test Meeting";
    private static final String TEST_LOCATION = "Conference Room A";
    private static final String TEST_DETAILS = "Test meeting details";
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_EMAIL_2 = "attendee2@example.com";
    private static final Long TEST_DATE_FROM = 1704067200000L; // 2024-01-01 00:00:00
    private static final Long TEST_DATE_TO = 1704070800000L;   // 2024-01-01 01:00:00

    // ==================== To EventDetails Tests ====================

    @Nested
    @DisplayName("toEventDetails Mapping Tests")
    class ToEventDetailsTests {

        @Test
        @DisplayName("Given complete event, When mapping to EventDetails, Then should map all fields correctly")
        void shouldMapAllFields_whenCompleteEvent() {
            // Arrange
            Event event = createCompleteEvent();

            // Act
            EventDetails result = EventMapper.toEventDetails(event);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertEquals(TEST_EVENT_ID, result.eventId, "Event ID should match");
            assertEquals(TEST_TITLE, result.title, "Title should match");
            assertEquals(TEST_LOCATION, result.location, "Location should match");
            assertEquals(TEST_DETAILS, result.details, "Details should match");
            assertEquals(TEST_DATE_FROM, result.dateFrom, "Start date should match");
            assertEquals(TEST_DATE_TO, result.dateTo, "End date should match");
            assertNotNull(result.contacts, "Contacts should not be null");
            assertEquals(2, result.contacts.size(), "Should have 2 contacts");
            assertTrue(result.contacts.contains(TEST_EMAIL), "Should contain first email");
            assertTrue(result.contacts.contains(TEST_EMAIL_2), "Should contain second email");
        }

        @Test
        @DisplayName("Given event with null optional fields, When mapping, Then should handle gracefully")
        void shouldHandleNullOptionalFields_whenMapping() {
            // Arrange
            Event event = new Event();
            event.setId(TEST_EVENT_ID);
            event.setSummary(TEST_TITLE);
            // location, description, dates, attendees are null

            // Act
            EventDetails result = EventMapper.toEventDetails(event);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertEquals(TEST_EVENT_ID, result.eventId, "Event ID should match");
            assertEquals(TEST_TITLE, result.title, "Title should match");
            assertNull(result.location, "Location should be null");
            assertNull(result.details, "Details should be null");
            assertNull(result.dateFrom, "Start date should be null");
            assertNull(result.dateTo, "End date should be null");
            assertNull(result.contacts, "Contacts should be null");
        }

        @Test
        @DisplayName("Given event with empty attendees, When mapping, Then should handle gracefully")
        void shouldHandleEmptyAttendees_whenMapping() {
            // Arrange
            Event event = new Event();
            event.setId(TEST_EVENT_ID);
            event.setSummary(TEST_TITLE);
            event.setAttendees(Collections.emptyList());

            // Act
            EventDetails result = EventMapper.toEventDetails(event);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertNull(result.contacts, "Contacts should be null for empty attendees");
        }

        @Test
        @DisplayName("Given null event, When mapping, Then should return null")
        void shouldReturnNull_whenEventIsNull() {
            // Act
            EventDetails result = EventMapper.toEventDetails(null);

            // Assert
            assertNull(result, "Result should be null for null event");
        }

        @Test
        @DisplayName("Given event with date-only (all-day event), When mapping, Then should extract date value")
        void shouldExtractDateValue_whenAllDayEvent() {
            // Arrange
            Event event = new Event();
            event.setId(TEST_EVENT_ID);
            event.setSummary(TEST_TITLE);
            
            EventDateTime startDate = new EventDateTime();
            startDate.setDate(new DateTime(TEST_DATE_FROM));
            event.setStart(startDate);
            
            EventDateTime endDate = new EventDateTime();
            endDate.setDate(new DateTime(TEST_DATE_TO));
            event.setEnd(endDate);

            // Act
            EventDetails result = EventMapper.toEventDetails(event);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertEquals(TEST_DATE_FROM, result.dateFrom, "Start date should be extracted");
            assertEquals(TEST_DATE_TO, result.dateTo, "End date should be extracted");
        }

        @Test
        @DisplayName("Given event with single attendee, When mapping, Then should map attendee correctly")
        void shouldMapSingleAttendee_whenOneAttendee() {
            // Arrange
            Event event = new Event();
            event.setId(TEST_EVENT_ID);
            event.setSummary(TEST_TITLE);
            event.setAttendees(Arrays.asList(
                    new EventAttendee().setEmail(TEST_EMAIL)
            ));

            // Act
            EventDetails result = EventMapper.toEventDetails(event);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertNotNull(result.contacts, "Contacts should not be null");
            assertEquals(1, result.contacts.size(), "Should have 1 contact");
            assertEquals(TEST_EMAIL, result.contacts.get(0), "Email should match");
        }
    }

    // ==================== To EventDetails List Tests ====================

    @Nested
    @DisplayName("toEventDetailsList Mapping Tests")
    class ToEventDetailsListTests {

        @Test
        @DisplayName("Given list of events, When mapping to EventDetails list, Then should map all events")
        void shouldMapAllEvents_whenEventList() {
            // Arrange
            List<Event> events = Arrays.asList(
                    createEventWithId("event-1", "Event 1"),
                    createEventWithId("event-2", "Event 2"),
                    createEventWithId("event-3", "Event 3")
            );

            // Act
            List<EventDetails> result = EventMapper.toEventDetailsList(events);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertEquals(3, result.size(), "Should map all 3 events");
            assertEquals("event-1", result.get(0).eventId, "First event ID should match");
            assertEquals("event-2", result.get(1).eventId, "Second event ID should match");
            assertEquals("event-3", result.get(2).eventId, "Third event ID should match");
        }

        @Test
        @DisplayName("Given empty event list, When mapping, Then should return empty list")
        void shouldReturnEmptyList_whenEmptyEventList() {
            // Act
            List<EventDetails> result = EventMapper.toEventDetailsList(Collections.emptyList());

            // Assert
            assertNotNull(result, "Result should not be null");
            assertTrue(result.isEmpty(), "Result should be empty list");
        }

        @Test
        @DisplayName("Given null event list, When mapping, Then should return empty list")
        void shouldReturnEmptyList_whenNullEventList() {
            // Act
            List<EventDetails> result = EventMapper.toEventDetailsList(null);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertTrue(result.isEmpty(), "Result should be empty list");
        }
    }

    // ==================== To Google Event Tests ====================

    @Nested
    @DisplayName("toGoogleEvent Mapping Tests")
    class ToGoogleEventTests {

        @Test
        @DisplayName("Given complete event details, When creating Google Event, Then should map all fields")
        void shouldMapAllFields_whenCompleteDetails() {
            // Arrange
            List<String> attendees = Arrays.asList(TEST_EMAIL, TEST_EMAIL_2);

            // Act
            Event result = EventMapper.toGoogleEvent(TEST_TITLE, TEST_LOCATION, 
                    TEST_DATE_FROM, TEST_DATE_TO, TEST_DETAILS, attendees);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertEquals(TEST_TITLE, result.getSummary(), "Title should match");
            assertEquals(TEST_LOCATION, result.getLocation(), "Location should match");
            assertEquals(TEST_DETAILS, result.getDescription(), "Details should match");
            assertNotNull(result.getStart(), "Start date should not be null");
            assertNotNull(result.getEnd(), "End date should not be null");
            assertEquals(TEST_DATE_FROM, result.getStart().getDateTime().getValue(), "Start date value should match");
            assertEquals(TEST_DATE_TO, result.getEnd().getDateTime().getValue(), "End date value should match");
            assertNotNull(result.getAttendees(), "Attendees should not be null");
            assertEquals(2, result.getAttendees().size(), "Should have 2 attendees");
        }

        @Test
        @DisplayName("Given null optional fields, When creating Google Event, Then should handle gracefully")
        void shouldHandleNullOptionalFields_whenCreating() {
            // Act
            Event result = EventMapper.toGoogleEvent(TEST_TITLE, null, null, null, null, null);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertEquals(TEST_TITLE, result.getSummary(), "Title should match");
            assertNull(result.getLocation(), "Location should be null");
            assertNull(result.getDescription(), "Description should be null");
            assertNull(result.getStart(), "Start should be null");
            assertNull(result.getEnd(), "End should be null");
            assertNull(result.getAttendees(), "Attendees should be null");
        }

        @Test
        @DisplayName("Given empty attendee list, When creating Google Event, Then should not set attendees")
        void shouldNotSetAttendees_whenEmptyList() {
            // Act
            Event result = EventMapper.toGoogleEvent(TEST_TITLE, TEST_LOCATION, 
                    TEST_DATE_FROM, TEST_DATE_TO, TEST_DETAILS, Collections.emptyList());

            // Assert
            assertNotNull(result, "Result should not be null");
            assertNull(result.getAttendees(), "Attendees should be null for empty list");
        }
    }

    // ==================== To Event Attendees Tests ====================

    @Nested
    @DisplayName("toEventAttendees Mapping Tests")
    class ToEventAttendeesTests {

        @Test
        @DisplayName("Given list of emails, When creating EventAttendees, Then should map all emails")
        void shouldMapAllEmails_whenEmailList() {
            // Arrange
            List<String> emails = Arrays.asList(TEST_EMAIL, TEST_EMAIL_2, "attendee3@example.com");

            // Act
            List<EventAttendee> result = EventMapper.toEventAttendees(emails);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertEquals(3, result.size(), "Should have 3 attendees");
            assertEquals(TEST_EMAIL, result.get(0).getEmail(), "First email should match");
            assertEquals(TEST_EMAIL_2, result.get(1).getEmail(), "Second email should match");
            assertEquals("attendee3@example.com", result.get(2).getEmail(), "Third email should match");
        }

        @Test
        @DisplayName("Given empty email list, When creating EventAttendees, Then should return empty list")
        void shouldReturnEmptyList_whenEmptyEmailList() {
            // Act
            List<EventAttendee> result = EventMapper.toEventAttendees(Collections.emptyList());

            // Assert
            assertNotNull(result, "Result should not be null");
            assertTrue(result.isEmpty(), "Result should be empty list");
        }

        @Test
        @DisplayName("Given null email list, When creating EventAttendees, Then should return empty list")
        void shouldReturnEmptyList_whenNullEmailList() {
            // Act
            List<EventAttendee> result = EventMapper.toEventAttendees(null);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertTrue(result.isEmpty(), "Result should be empty list");
        }
    }

    // ==================== Helper Methods ====================

    private Event createCompleteEvent() {
        Event event = new Event();
        event.setId(TEST_EVENT_ID);
        event.setSummary(TEST_TITLE);
        event.setLocation(TEST_LOCATION);
        event.setDescription(TEST_DETAILS);
        
        EventDateTime start = new EventDateTime();
        start.setDateTime(new DateTime(TEST_DATE_FROM));
        event.setStart(start);
        
        EventDateTime end = new EventDateTime();
        end.setDateTime(new DateTime(TEST_DATE_TO));
        event.setEnd(end);
        
        event.setAttendees(Arrays.asList(
                new EventAttendee().setEmail(TEST_EMAIL),
                new EventAttendee().setEmail(TEST_EMAIL_2)
        ));
        
        return event;
    }

    private Event createEventWithId(String id, String summary) {
        Event event = new Event();
        event.setId(id);
        event.setSummary(summary);
        return event;
    }
}

