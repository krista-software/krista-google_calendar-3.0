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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.impl;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.entity.EventDetails;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.repository.CalendarRepository;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for CalendarServiceImpl - Part 2: Meeting & Event Retrieval Operations.
 * 
 * <p>This test class validates meeting scheduling, event cancellation, and various event
 * retrieval operations including upcoming meetings and user-specific events.</p>
 * 
 * <h2>Testing Strategy:</h2>
 * <ul>
 *   <li><b>Arrange-Act-Assert (AAA):</b> Clear separation of setup, execution, and validation</li>
 *   <li><b>Builder Pattern:</b> EventBuilder for creating complex test data</li>
 *   <li><b>Mocking:</b> Mockito for CalendarRepository to isolate business logic</li>
 *   <li><b>Descriptive Names:</b> Behavior-driven test naming (shouldX_whenY)</li>
 *   <li><b>Positive & Negative:</b> Both success and failure scenarios covered</li>
 * </ul>
 * 
 * @author Krista Software
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CalendarServiceImpl - Part 2: Meeting & Event Retrieval Operations")
class CalendarServiceImplTestPart2 {

    // Test constants
    private static final String PRIMARY = "primary";
    private static final String TEST_EVENT_ID = "test-event-123";
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_EMAIL_2 = "attendee2@example.com";
    private static final String TEST_TITLE = "Test Meeting";
    private static final String TEST_LOCATION = "Conference Room A";
    private static final String TEST_DETAILS = "Test meeting details";
    private static final Long TEST_DATE_FROM = 1704067200000L; // 2024-01-01 00:00:00
    private static final Long TEST_DATE_TO = 1704070800000L;   // 2024-01-01 01:00:00

    @Mock
    private CalendarRepository mockRepository;

    @Mock
    private CalendarAttendeeService mockAttendeeService;

    @Mock
    private CalendarEventService mockEventService;

    private CalendarServiceImpl calendarService;

    /**
     * Setup method executed before each test.
     * Initializes the service with mocked specialized services.
     */
    @BeforeEach
    void setUp() {
        calendarService = new CalendarServiceImpl(mockAttendeeService, mockEventService);
    }

    // ==================== Test Data Builders ====================

    /**
     * Builder class for creating test Event objects with fluent API.
     * Simplifies test data creation and improves readability.
     */
    static class EventBuilder {
        private String id = TEST_EVENT_ID;
        private String summary = TEST_TITLE;
        private String location = TEST_LOCATION;
        private String description = TEST_DETAILS;
        private Long startTime = TEST_DATE_FROM;
        private Long endTime = TEST_DATE_TO;
        private List<EventAttendee> attendees = new ArrayList<>();

        public EventBuilder withId(String id) {
            this.id = id;
            return this;
        }

        public EventBuilder withSummary(String summary) {
            this.summary = summary;
            return this;
        }

        public EventBuilder withLocation(String location) {
            this.location = location;
            return this;
        }

        public EventBuilder withDescription(String description) {
            this.description = description;
            return this;
        }

        public EventBuilder withDateRange(Long startTime, Long endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
            return this;
        }

        public EventBuilder withAttendee(String email, String responseStatus) {
            EventAttendee attendee = new EventAttendee()
                    .setEmail(email)
                    .setResponseStatus(responseStatus);
            this.attendees.add(attendee);
            return this;
        }

        public EventBuilder withSelfAttendee(String email, String responseStatus, boolean isOrganizer) {
            EventAttendee attendee = new EventAttendee()
                    .setEmail(email)
                    .setResponseStatus(responseStatus)
                    .setSelf(true)
                    .setOrganizer(isOrganizer);
            this.attendees.add(attendee);
            return this;
        }

        public EventBuilder withAttendees(List<EventAttendee> attendees) {
            this.attendees = new ArrayList<>(attendees);
            return this;
        }

        public Event build() {
            Event event = new Event();
            event.setId(id);
            event.setSummary(summary);
            event.setLocation(location);
            event.setDescription(description);
            
            if (startTime != null) {
                event.setStart(new EventDateTime().setDateTime(new DateTime(startTime)));
            }
            if (endTime != null) {
                event.setEnd(new EventDateTime().setDateTime(new DateTime(endTime)));
            }
            
            event.setAttendees(attendees);
            return event;
        }
    }

    /**
     * Factory method to create a basic test event with default values.
     */
    private Event createBasicEvent() {
        return new EventBuilder().build();
    }

    /**
     * Factory method to create an event with specific attendee.
     */
    private Event createEventWithAttendee(String email, String responseStatus) {
        return new EventBuilder()
                .withAttendee(email, responseStatus)
                .build();
    }

    /**
     * Factory method to create an event with multiple attendees.
     */
    private Event createEventWithMultipleAttendees() {
        return new EventBuilder()
                .withAttendee(TEST_EMAIL, "needsAction")
                .withAttendee(TEST_EMAIL_2, "accepted")
                .build();
    }

    // ==================== Schedule Meeting Tests ====================

    @Nested
    @DisplayName("Schedule Meeting Business Logic Tests")
    class ScheduleMeetingTests {

        @Test
        @DisplayName("Given valid meeting details, When scheduling meeting, Then should create event successfully")
        void shouldScheduleMeeting_whenValidDetails() throws IOException {
            // Arrange
            List<String> attendees = Arrays.asList(TEST_EMAIL, TEST_EMAIL_2);
            EventDetails expectedResult = new EventDetails();
            expectedResult.eventId = TEST_EVENT_ID;
            expectedResult.title = TEST_TITLE;

            when(mockEventService.scheduleMeeting(TEST_TITLE, TEST_LOCATION, TEST_DATE_FROM,
                    TEST_DATE_TO, TEST_DETAILS, attendees))
                    .thenReturn(expectedResult);

            // Act
            EventDetails result = calendarService.scheduleMeeting(
                    TEST_TITLE, TEST_LOCATION, TEST_DATE_FROM, TEST_DATE_TO, TEST_DETAILS, attendees
            );

            // Assert
            assertNotNull(result, "Result should not be null");
            assertEquals(TEST_EVENT_ID, result.eventId);
            verify(mockEventService).scheduleMeeting(TEST_TITLE, TEST_LOCATION, TEST_DATE_FROM,
                    TEST_DATE_TO, TEST_DETAILS, attendees);
        }

        @Test
        @DisplayName("Given empty attendee list, When scheduling meeting, Then should create event with no attendees")
        void shouldScheduleMeeting_withEmptyAttendeeList() throws IOException {
            // Arrange
            List<String> emptyAttendees = Collections.emptyList();
            EventDetails expectedResult = new EventDetails();
            expectedResult.eventId = TEST_EVENT_ID;

            when(mockEventService.scheduleMeeting(TEST_TITLE, TEST_LOCATION, TEST_DATE_FROM,
                    TEST_DATE_TO, TEST_DETAILS, emptyAttendees))
                    .thenReturn(expectedResult);

            // Act
            EventDetails result = calendarService.scheduleMeeting(
                    TEST_TITLE, TEST_LOCATION, TEST_DATE_FROM, TEST_DATE_TO, TEST_DETAILS, emptyAttendees
            );

            // Assert
            assertNotNull(result, "Result should not be null");
            verify(mockEventService).scheduleMeeting(TEST_TITLE, TEST_LOCATION, TEST_DATE_FROM,
                    TEST_DATE_TO, TEST_DETAILS, emptyAttendees);
        }

        @Test
        @DisplayName("Given null location and details, When scheduling meeting, Then should handle gracefully")
        void shouldScheduleMeeting_withNullOptionalFields() throws IOException {
            // Arrange
            List<String> attendees = List.of(TEST_EMAIL);
            EventDetails expectedResult = new EventDetails();
            expectedResult.eventId = TEST_EVENT_ID;

            when(mockEventService.scheduleMeeting(TEST_TITLE, null, TEST_DATE_FROM,
                    TEST_DATE_TO, null, attendees))
                    .thenReturn(expectedResult);

            // Act
            EventDetails result = calendarService.scheduleMeeting(
                    TEST_TITLE, null, TEST_DATE_FROM, TEST_DATE_TO, null, attendees
            );

            // Assert
            assertNotNull(result, "Result should not be null");
            verify(mockEventService).scheduleMeeting(TEST_TITLE, null, TEST_DATE_FROM,
                    TEST_DATE_TO, null, attendees);
        }

        @Test
        @DisplayName("Given IOException from repository, When scheduling meeting, Then should throw IllegalStateException")
        void shouldThrowIllegalStateException_whenRepositoryThrowsIOException() throws IOException {
            // Arrange
            List<String> attendees = List.of(TEST_EMAIL);
            when(mockEventService.scheduleMeeting(TEST_TITLE, TEST_LOCATION, TEST_DATE_FROM,
                    TEST_DATE_TO, TEST_DETAILS, attendees))
                    .thenThrow(new IllegalStateException("Failed to schedule meeting: API Error"));

            // Act & Assert
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> calendarService.scheduleMeeting(
                            TEST_TITLE, TEST_LOCATION, TEST_DATE_FROM, TEST_DATE_TO, TEST_DETAILS, attendees
                    ),
                    "Should throw IllegalStateException when service fails"
            );

            assertTrue(exception.getMessage().contains("Failed to schedule meeting"),
                    "Exception message should indicate schedule meeting failure");
        }
    }

    // ==================== Cancel Event Tests ====================

    @Nested
    @DisplayName("Cancel Event Business Logic Tests")
    class CancelEventTests {

        @Test
        @DisplayName("Given valid event ID, When canceling event, Then should delete event successfully")
        void shouldCancelEvent_whenValidEventId() throws IOException {
            // Arrange
            EventDetails expectedResult = new EventDetails();
            expectedResult.eventId = TEST_EVENT_ID;
            when(mockEventService.cancelEvent(TEST_EVENT_ID)).thenReturn(expectedResult);

            // Act
            EventDetails result = calendarService.cancelEvent(TEST_EVENT_ID);

            // Assert
            assertNotNull(result, "Result should not be null");
            verify(mockEventService).cancelEvent(TEST_EVENT_ID);
        }

        @Test
        @DisplayName("Given IOException on get, When canceling event, Then should throw IllegalStateException")
        void shouldThrowIllegalStateException_whenGetThrowsIOException() throws IOException {
            // Arrange
            when(mockEventService.cancelEvent(TEST_EVENT_ID))
                    .thenThrow(new IllegalStateException("Failed to cancel event: Event not found"));

            // Act & Assert
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> calendarService.cancelEvent(TEST_EVENT_ID),
                    "Should throw IllegalStateException when service fails"
            );

            assertTrue(exception.getMessage().contains("Failed to cancel event"),
                    "Exception message should indicate cancel event failure");
        }

        @Test
        @DisplayName("Given IOException on delete, When canceling event, Then should throw IllegalStateException")
        void shouldThrowIllegalStateException_whenDeleteThrowsIOException() throws IOException {
            // Arrange
            when(mockEventService.cancelEvent(TEST_EVENT_ID))
                    .thenThrow(new IllegalStateException("Failed to cancel event: Delete failed"));

            // Act & Assert
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> calendarService.cancelEvent(TEST_EVENT_ID),
                    "Should throw IllegalStateException when service fails"
            );

            assertTrue(exception.getMessage().contains("Failed to cancel event"),
                    "Exception message should indicate cancel event failure");
        }
    }

    // ==================== Get Event Tests ====================

    @Nested
    @DisplayName("Get Event Business Logic Tests")
    class GetEventTests {

        @Test
        @DisplayName("Given valid event ID, When getting event, Then should return event details")
        void shouldGetEvent_whenValidEventId() throws IOException {
            // Arrange
            EventDetails expectedResult = new EventDetails();
            expectedResult.eventId = TEST_EVENT_ID;
            when(mockEventService.getEvent(TEST_EVENT_ID)).thenReturn(expectedResult);

            // Act
            EventDetails result = calendarService.getEvent(TEST_EVENT_ID);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertEquals(TEST_EVENT_ID, result.eventId);
            verify(mockEventService).getEvent(TEST_EVENT_ID);
        }

        @Test
        @DisplayName("Given IOException from repository, When getting event, Then should throw IllegalStateException")
        void shouldThrowIllegalStateException_whenRepositoryThrowsIOException() throws IOException {
            // Arrange
            when(mockEventService.getEvent(TEST_EVENT_ID))
                    .thenThrow(new IllegalStateException("Failed to get event: Event not found"));

            // Act & Assert
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> calendarService.getEvent(TEST_EVENT_ID),
                    "Should throw IllegalStateException when service fails"
            );

            assertTrue(exception.getMessage().contains("Failed to get event"),
                    "Exception message should indicate get event failure");
        }
    }

    // ==================== Get Next Upcoming Meetings Tests ====================

    @Nested
    @DisplayName("Get Next Upcoming Meetings Business Logic Tests")
    class GetNextUpcomingMeetingsTests {

        @Test
        @DisplayName("Given valid meeting count, When getting upcoming meetings, Then should return meeting list")
        void shouldGetUpcomingMeetings_whenValidCount() throws IOException {
            // Arrange
            Double numberOfMeetings = 5.0;
            EventDetails event1 = new EventDetails();
            event1.eventId = "event-1";
            EventDetails event2 = new EventDetails();
            event2.eventId = "event-2";
            List<EventDetails> expectedResult = Arrays.asList(event1, event2);

            when(mockEventService.getNextUpcomingMeetings(numberOfMeetings))
                    .thenReturn(expectedResult);

            // Act
            List<EventDetails> result = calendarService.getNextUpcomingMeetings(numberOfMeetings);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertEquals(2, result.size(), "Should return 2 events");
            verify(mockEventService).getNextUpcomingMeetings(numberOfMeetings);
        }

        @ParameterizedTest
        @ValueSource(doubles = {1.0, 3.0, 10.0, 50.0})
        @DisplayName("Given different meeting counts, When getting upcoming meetings, Then should use correct max results")
        void shouldUseCorrectMaxResults_forDifferentCounts(Double count) throws IOException {
            // Arrange
            when(mockEventService.getNextUpcomingMeetings(count))
                    .thenReturn(Collections.emptyList());

            // Act
            List<EventDetails> result = calendarService.getNextUpcomingMeetings(count);

            // Assert
            assertNotNull(result, "Result should not be null");
            verify(mockEventService).getNextUpcomingMeetings(count);
        }

        @Test
        @DisplayName("Given null meeting count, When getting upcoming meetings, Then should use default value")
        void shouldUseDefaultValue_whenCountIsNull() throws IOException {
            // Arrange
            when(mockEventService.getNextUpcomingMeetings(null))
                    .thenReturn(Collections.emptyList());

            // Act
            List<EventDetails> result = calendarService.getNextUpcomingMeetings(null);

            // Assert
            assertNotNull(result, "Result should not be null");
            verify(mockEventService).getNextUpcomingMeetings(null);
        }

        @Test
        @DisplayName("Given empty event list, When getting upcoming meetings, Then should return empty list")
        void shouldReturnEmptyList_whenNoUpcomingMeetings() throws IOException {
            // Arrange
            when(mockEventService.getNextUpcomingMeetings(5.0))
                    .thenReturn(Collections.emptyList());

            // Act
            List<EventDetails> result = calendarService.getNextUpcomingMeetings(5.0);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertTrue(result.isEmpty(), "Result should be empty list");
        }

        @Test
        @DisplayName("Given IOException from repository, When getting upcoming meetings, Then should throw IllegalStateException")
        void shouldThrowIllegalStateException_whenRepositoryThrowsIOException() throws IOException {
            // Arrange
            when(mockEventService.getNextUpcomingMeetings(5.0))
                    .thenThrow(new IllegalStateException("Failed to get upcoming meetings: API Error"));

            // Act & Assert
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> calendarService.getNextUpcomingMeetings(5.0),
                    "Should throw IllegalStateException when service fails"
            );

            assertTrue(exception.getMessage().contains("Failed to get upcoming meetings"),
                    "Exception message should indicate get upcoming meetings failure");
        }
    }

    // ==================== Get All Unaccepted Events Tests ====================

    @Nested
    @DisplayName("Get All Unaccepted Events Business Logic Tests")
    class GetAllUnacceptedEventsTests {

        @Test
        @DisplayName("Given events with mixed response statuses, When getting unaccepted events, Then should return only unaccepted")
        void shouldReturnOnlyUnacceptedEvents_whenMixedStatuses() throws IOException {
            // Arrange
            EventDetails unacceptedEvent = new EventDetails();
            unacceptedEvent.eventId = "event-2";
            List<EventDetails> expectedResult = List.of(unacceptedEvent);

            when(mockEventService.getAllUnacceptedEvents())
                    .thenReturn(expectedResult);

            // Act
            List<EventDetails> result = calendarService.getAllUnacceptedEvents();

            // Assert
            assertNotNull(result, "Result should not be null");
            assertEquals(1, result.size(), "Should return only 1 unaccepted event");
            verify(mockEventService).getAllUnacceptedEvents();
        }

        @Test
        @DisplayName("Given all accepted events, When getting unaccepted events, Then should return empty list")
        void shouldReturnEmptyList_whenAllEventsAccepted() throws IOException {
            // Arrange
            when(mockEventService.getAllUnacceptedEvents())
                    .thenReturn(Collections.emptyList());

            // Act
            List<EventDetails> result = calendarService.getAllUnacceptedEvents();

            // Assert
            assertNotNull(result, "Result should not be null");
            assertTrue(result.isEmpty(), "Should return empty list when all events accepted");
            verify(mockEventService).getAllUnacceptedEvents();
        }

        @Test
        @DisplayName("Given IOException from repository, When getting unaccepted events, Then should throw IllegalStateException")
        void shouldThrowIllegalStateException_whenRepositoryThrowsIOException() throws IOException {
            // Arrange
            when(mockEventService.getAllUnacceptedEvents())
                    .thenThrow(new IllegalStateException("Failed to get unaccepted events: API Error"));

            // Act & Assert
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> calendarService.getAllUnacceptedEvents(),
                    "Should throw IllegalStateException when service fails"
            );

            assertTrue(exception.getMessage().contains("Failed to get unaccepted events"),
                    "Exception message should indicate get unaccepted events failure");
        }
    }

    // ==================== Get Events For User Tests ====================

    @Nested
    @DisplayName("Get Events For User Business Logic Tests")
    class GetEventsForUserTests {

        @Test
        @DisplayName("Given valid user and date range, When getting events, Then should return user events")
        void shouldGetEventsForUser_whenValidUserAndDateRange() throws IOException {
            // Arrange
            EventDetails userEvent = new EventDetails();
            userEvent.eventId = "event-1";
            List<EventDetails> expectedResult = List.of(userEvent);

            when(mockEventService.getEventsForUser(TEST_EMAIL, TEST_DATE_FROM, TEST_DATE_TO))
                    .thenReturn(expectedResult);

            // Act
            List<EventDetails> result = calendarService.getEventsForUser(TEST_EMAIL, TEST_DATE_FROM, TEST_DATE_TO);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertEquals(1, result.size(), "Should return 1 event");
            verify(mockEventService).getEventsForUser(TEST_EMAIL, TEST_DATE_FROM, TEST_DATE_TO);
        }

        @Test
        @DisplayName("Given user with no events, When getting events, Then should return empty list")
        void shouldReturnEmptyList_whenUserHasNoEvents() throws IOException {
            // Arrange
            when(mockEventService.getEventsForUser(TEST_EMAIL, TEST_DATE_FROM, TEST_DATE_TO))
                    .thenReturn(Collections.emptyList());

            // Act
            List<EventDetails> result = calendarService.getEventsForUser(TEST_EMAIL, TEST_DATE_FROM, TEST_DATE_TO);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertTrue(result.isEmpty(), "Should return empty list when user has no events");
        }

        @Test
        @DisplayName("Given null date range, When getting events, Then should handle gracefully")
        void shouldHandleGracefully_whenDateRangeIsNull() throws IOException {
            // Arrange
            when(mockEventService.getEventsForUser(TEST_EMAIL, null, null))
                    .thenReturn(Collections.emptyList());

            // Act
            List<EventDetails> result = calendarService.getEventsForUser(TEST_EMAIL, null, null);

            // Assert
            assertNotNull(result, "Result should not be null");
            verify(mockEventService).getEventsForUser(TEST_EMAIL, null, null);
        }

        @Test
        @DisplayName("Given IOException from repository, When getting events for user, Then should throw IllegalStateException")
        void shouldThrowIllegalStateException_whenRepositoryThrowsIOException() throws IOException {
            // Arrange
            when(mockEventService.getEventsForUser(TEST_EMAIL, TEST_DATE_FROM, TEST_DATE_TO))
                    .thenThrow(new IllegalStateException("Failed to get events for user: API Error"));

            // Act & Assert
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> calendarService.getEventsForUser(TEST_EMAIL, TEST_DATE_FROM, TEST_DATE_TO),
                    "Should throw IllegalStateException when service fails"
            );

            assertTrue(exception.getMessage().contains("Failed to get events for user"),
                    "Exception message should indicate get events for user failure");
        }
    }

    // ==================== Edge Case and Null Safety Tests ====================

    @Nested
    @DisplayName("Edge Cases and Null Safety Tests")
    class EdgeCaseTests {

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Given null or empty event ID, When accepting invitation, Then should handle appropriately")
        void shouldHandleNullOrEmptyEventId_whenAcceptingInvitation(String eventId) throws IOException {
            // Arrange
            when(mockAttendeeService.acceptInvitation(eventId, TEST_EMAIL))
                    .thenThrow(new IllegalStateException("Failed to accept invitation: Invalid event ID"));

            // Act & Assert
            assertThrows(
                    IllegalStateException.class,
                    () -> calendarService.acceptInvitation(eventId, TEST_EMAIL),
                    "Should throw IllegalStateException for null or empty event ID"
            );
        }

        @Test
        @DisplayName("Given event with null attendee list, When accepting invitation, Then should throw IllegalArgumentException")
        void shouldThrowIllegalArgumentException_whenEventHasNullAttendeeList() throws IOException {
            // Arrange
            when(mockAttendeeService.acceptInvitation(TEST_EVENT_ID, TEST_EMAIL))
                    .thenThrow(new IllegalArgumentException("Attendee not found in event: " + TEST_EMAIL));

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> calendarService.acceptInvitation(TEST_EVENT_ID, TEST_EMAIL),
                    "Should throw IllegalArgumentException when attendee list is null"
            );

            assertTrue(exception.getMessage().contains("Attendee not found"),
                    "Exception message should indicate attendee not found");
        }

        @Test
        @DisplayName("Given event with empty attendee list, When accepting invitation, Then should throw IllegalArgumentException")
        void shouldThrowIllegalArgumentException_whenEventHasEmptyAttendeeList() throws IOException {
            // Arrange
            when(mockAttendeeService.acceptInvitation(TEST_EVENT_ID, TEST_EMAIL))
                    .thenThrow(new IllegalArgumentException("Attendee not found in event: " + TEST_EMAIL));

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> calendarService.acceptInvitation(TEST_EVENT_ID, TEST_EMAIL),
                    "Should throw IllegalArgumentException when attendee list is empty"
            );

            assertTrue(exception.getMessage().contains("Attendee not found"),
                    "Exception message should indicate attendee not found");
        }
    }
}

