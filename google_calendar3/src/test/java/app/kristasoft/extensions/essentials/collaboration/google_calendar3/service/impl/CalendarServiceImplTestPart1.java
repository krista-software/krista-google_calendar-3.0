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
 * Comprehensive test suite for CalendarServiceImpl - Part 1: Invitation & Attendee Operations.
 * 
 * <p>This test class validates invitation management and attendee operations including:
 * accept, decline, tentative invitations, and add/remove attendees.</p>
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
@DisplayName("CalendarServiceImpl - Part 1: Invitation & Attendee Operations")
class CalendarServiceImplTestPart1 {

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

    // ==================== Accept Invitation Tests ====================

    @Nested
    @DisplayName("Accept Invitation Business Logic Tests")
    class AcceptInvitationTests {

        @Test
        @DisplayName("Given valid event with attendee, When accepting invitation, Then should update status to accepted")
        void shouldAcceptInvitation_whenValidEventAndAttendee() throws IOException {
            // Arrange
            EventDetails expectedResult = new EventDetails();
            expectedResult.eventId = TEST_EVENT_ID;
            when(mockAttendeeService.acceptInvitation(TEST_EVENT_ID, TEST_EMAIL))
                    .thenReturn(expectedResult);

            // Act
            EventDetails result = calendarService.acceptInvitation(TEST_EVENT_ID, TEST_EMAIL);

            // Assert
            assertNotNull(result, "Result should not be null");
            assertEquals(TEST_EVENT_ID, result.eventId);
            verify(mockAttendeeService).acceptInvitation(TEST_EVENT_ID, TEST_EMAIL);
        }

        @Test
        @DisplayName("Given event with multiple attendees, When accepting invitation, Then should only update specified attendee")
        void shouldAcceptInvitation_whenMultipleAttendees_thenUpdateOnlySpecifiedAttendee() throws IOException {
            // Arrange
            EventDetails expectedResult = new EventDetails();
            expectedResult.eventId = TEST_EVENT_ID;
            when(mockAttendeeService.acceptInvitation(TEST_EVENT_ID, TEST_EMAIL))
                    .thenReturn(expectedResult);

            // Act
            EventDetails result = calendarService.acceptInvitation(TEST_EVENT_ID, TEST_EMAIL);

            // Assert
            assertNotNull(result);
            verify(mockAttendeeService).acceptInvitation(TEST_EVENT_ID, TEST_EMAIL);
        }

        @Test
        @DisplayName("Given IOException from repository, When accepting invitation, Then should throw IllegalStateException")
        void shouldThrowIllegalStateException_whenRepositoryThrowsIOException() throws IOException {
            // Arrange
            when(mockAttendeeService.acceptInvitation(TEST_EVENT_ID, TEST_EMAIL))
                    .thenThrow(new IllegalStateException("Failed to accept invitation: API Error"));

            // Act & Assert
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> calendarService.acceptInvitation(TEST_EVENT_ID, TEST_EMAIL),
                    "Should throw IllegalStateException when service fails"
            );

            assertTrue(exception.getMessage().contains("Failed to accept invitation"),
                    "Exception message should indicate accept invitation failure");
            verify(mockAttendeeService).acceptInvitation(TEST_EVENT_ID, TEST_EMAIL);
        }

        @Test
        @DisplayName("Given event without target attendee, When accepting invitation, Then should throw IllegalArgumentException")
        void shouldThrowIllegalArgumentException_whenAttendeeNotFound() throws IOException {
            // Arrange
            when(mockAttendeeService.acceptInvitation(TEST_EVENT_ID, TEST_EMAIL))
                    .thenThrow(new IllegalArgumentException("Attendee not found in event: " + TEST_EMAIL));

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> calendarService.acceptInvitation(TEST_EVENT_ID, TEST_EMAIL),
                    "Should throw IllegalArgumentException when attendee not found"
            );

            assertTrue(exception.getMessage().contains("Attendee not found"),
                    "Exception message should indicate attendee not found");
            verify(mockAttendeeService).acceptInvitation(TEST_EVENT_ID, TEST_EMAIL);
        }
    }

    // ==================== Decline Invitation Tests ====================

    @Nested
    @DisplayName("Decline Invitation Business Logic Tests")
    class DeclineInvitationTests {

        @Test
        @DisplayName("Given valid event with attendee, When declining invitation, Then should update status to declined")
        void shouldDeclineInvitation_whenValidEventAndAttendee() throws IOException {
            // Arrange
            EventDetails expectedResult = new EventDetails();
            expectedResult.eventId = TEST_EVENT_ID;
            when(mockAttendeeService.declineInvitation(TEST_EVENT_ID, TEST_EMAIL))
                    .thenReturn(expectedResult);

            // Act
            EventDetails result = calendarService.declineInvitation(TEST_EVENT_ID, TEST_EMAIL);

            // Assert
            assertNotNull(result, "Result should not be null");
            verify(mockAttendeeService).declineInvitation(TEST_EVENT_ID, TEST_EMAIL);
        }

        @Test
        @DisplayName("Given IOException from repository, When declining invitation, Then should throw IllegalStateException")
        void shouldThrowIllegalStateException_whenRepositoryThrowsIOException() throws IOException {
            // Arrange
            when(mockAttendeeService.declineInvitation(TEST_EVENT_ID, TEST_EMAIL))
                    .thenThrow(new IllegalStateException("Failed to decline invitation: API Error"));

            // Act & Assert
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> calendarService.declineInvitation(TEST_EVENT_ID, TEST_EMAIL),
                    "Should throw IllegalStateException when service fails"
            );

            assertTrue(exception.getMessage().contains("Failed to decline invitation"),
                    "Exception message should indicate decline invitation failure");
        }

        @Test
        @DisplayName("Given event without target attendee, When declining invitation, Then should throw IllegalArgumentException")
        void shouldThrowIllegalArgumentException_whenAttendeeNotFound() throws IOException {
            // Arrange
            when(mockAttendeeService.declineInvitation(TEST_EVENT_ID, TEST_EMAIL))
                    .thenThrow(new IllegalArgumentException("Attendee not found in event: " + TEST_EMAIL));

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> calendarService.declineInvitation(TEST_EVENT_ID, TEST_EMAIL),
                    "Should throw IllegalArgumentException when attendee not found"
            );

            assertTrue(exception.getMessage().contains("Attendee not found"),
                    "Exception message should indicate attendee not found");
        }
    }

    // ==================== Tentative Invitation Tests ====================

    @Nested
    @DisplayName("Tentative Invitation Business Logic Tests")
    class TentativeInvitationTests {

        @Test
        @DisplayName("Given valid event with attendee, When setting tentative, Then should update status to tentative")
        void shouldSetTentative_whenValidAttendee() throws IOException {
            // Arrange
            EventDetails expectedResult = new EventDetails();
            expectedResult.eventId = TEST_EVENT_ID;
            when(mockAttendeeService.tentativeInvitation(TEST_EVENT_ID, TEST_EMAIL))
                    .thenReturn(expectedResult);

            // Act
            EventDetails result = calendarService.tentativeInvitation(TEST_EVENT_ID, TEST_EMAIL);

            // Assert
            assertNotNull(result, "Result should not be null");
            verify(mockAttendeeService).tentativeInvitation(TEST_EVENT_ID, TEST_EMAIL);
        }

        @Test
        @DisplayName("Given IOException from repository, When setting tentative, Then should throw IllegalStateException")
        void shouldThrowIllegalStateException_whenRepositoryThrowsIOException() throws IOException {
            // Arrange
            when(mockAttendeeService.tentativeInvitation(TEST_EVENT_ID, TEST_EMAIL))
                    .thenThrow(new IllegalStateException("Failed to mark invitation as tentative: API Error"));

            // Act & Assert
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> calendarService.tentativeInvitation(TEST_EVENT_ID, TEST_EMAIL),
                    "Should throw IllegalStateException when service fails"
            );

            assertTrue(exception.getMessage().contains("Failed to mark invitation as tentative"),
                    "Exception message should indicate tentative invitation failure");
        }

        @Test
        @DisplayName("Given event without target attendee, When setting tentative, Then should throw IllegalArgumentException")
        void shouldThrowIllegalArgumentException_whenAttendeeNotFound() throws IOException {
            // Arrange
            when(mockAttendeeService.tentativeInvitation(TEST_EVENT_ID, TEST_EMAIL))
                    .thenThrow(new IllegalArgumentException("Attendee not found in event: " + TEST_EMAIL));

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> calendarService.tentativeInvitation(TEST_EVENT_ID, TEST_EMAIL),
                    "Should throw IllegalArgumentException when attendee not found"
            );

            assertTrue(exception.getMessage().contains("Attendee not found"),
                    "Exception message should indicate attendee not found");
        }
    }

    // ==================== Add Attendees Tests ====================

    @Nested
    @DisplayName("Add Attendees Business Logic Tests")
    class AddAttendeesTests {

        @Test
        @DisplayName("Given valid event and new attendees, When adding attendees, Then should add to attendee list")
        void shouldAddAttendees_whenValidEventAndNewAttendees() throws IOException {
            // Arrange
            List<String> newAttendees = Arrays.asList(TEST_EMAIL_2, "attendee3@example.com");
            EventDetails expectedResult = new EventDetails();
            expectedResult.eventId = TEST_EVENT_ID;
            when(mockAttendeeService.addAttendees(TEST_EVENT_ID, newAttendees))
                    .thenReturn(expectedResult);

            // Act
            EventDetails result = calendarService.addAttendees(TEST_EVENT_ID, newAttendees);

            // Assert
            assertNotNull(result, "Result should not be null");
            verify(mockAttendeeService).addAttendees(TEST_EVENT_ID, newAttendees);
        }

        @Test
        @DisplayName("Given empty attendee list, When adding attendees, Then should handle gracefully")
        void shouldHandleGracefully_whenEmptyAttendeeList() throws IOException {
            // Arrange
            List<String> emptyList = Collections.emptyList();
            EventDetails expectedResult = new EventDetails();
            expectedResult.eventId = TEST_EVENT_ID;
            when(mockAttendeeService.addAttendees(TEST_EVENT_ID, emptyList))
                    .thenReturn(expectedResult);

            // Act
            EventDetails result = calendarService.addAttendees(TEST_EVENT_ID, emptyList);

            // Assert
            assertNotNull(result, "Result should not be null");
            verify(mockAttendeeService).addAttendees(TEST_EVENT_ID, emptyList);
        }

        @Test
        @DisplayName("Given IOException from repository, When adding attendees, Then should throw IllegalStateException")
        void shouldThrowIllegalStateException_whenRepositoryThrowsIOException() throws IOException {
            // Arrange
            List<String> newAttendees = List.of(TEST_EMAIL_2);
            when(mockAttendeeService.addAttendees(TEST_EVENT_ID, newAttendees))
                    .thenThrow(new IllegalStateException("Failed to add attendees: API Error"));

            // Act & Assert
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> calendarService.addAttendees(TEST_EVENT_ID, newAttendees),
                    "Should throw IllegalStateException when service fails"
            );

            assertTrue(exception.getMessage().contains("Failed to add attendees"),
                    "Exception message should indicate add attendees failure");
        }
    }

    // ==================== Remove Attendee Tests ====================

    @Nested
    @DisplayName("Remove Attendee Business Logic Tests")
    class RemoveAttendeeTests {

        @Test
        @DisplayName("Given valid event with attendee, When removing attendee, Then should remove from attendee list")
        void shouldRemoveAttendee_whenValidEventAndAttendee() throws IOException {
            // Arrange
            EventDetails expectedResult = new EventDetails();
            expectedResult.eventId = TEST_EVENT_ID;
            when(mockAttendeeService.removeAttendee(TEST_EVENT_ID, TEST_EMAIL))
                    .thenReturn(expectedResult);

            // Act
            EventDetails result = calendarService.removeAttendee(TEST_EVENT_ID, TEST_EMAIL);

            // Assert
            assertNotNull(result, "Result should not be null");
            verify(mockAttendeeService).removeAttendee(TEST_EVENT_ID, TEST_EMAIL);
        }

        @Test
        @DisplayName("Given event without target attendee, When removing attendee, Then should throw IllegalArgumentException")
        void shouldThrowIllegalArgumentException_whenAttendeeNotFound() throws IOException {
            // Arrange
            when(mockAttendeeService.removeAttendee(TEST_EVENT_ID, TEST_EMAIL))
                    .thenThrow(new IllegalArgumentException("Attendee not found in event: " + TEST_EMAIL));

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> calendarService.removeAttendee(TEST_EVENT_ID, TEST_EMAIL),
                    "Should throw IllegalArgumentException when attendee not found"
            );

            assertTrue(exception.getMessage().contains("Attendee not found"),
                    "Exception message should indicate attendee not found");
        }

        @Test
        @DisplayName("Given event with null attendee list, When removing attendee, Then should throw IllegalArgumentException")
        void shouldThrowIllegalArgumentException_whenAttendeeListIsNull() throws IOException {
            // Arrange
            when(mockAttendeeService.removeAttendee(TEST_EVENT_ID, TEST_EMAIL))
                    .thenThrow(new IllegalArgumentException("Event has no attendees"));

            // Act & Assert
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> calendarService.removeAttendee(TEST_EVENT_ID, TEST_EMAIL),
                    "Should throw IllegalArgumentException when attendee list is null"
            );

            assertTrue(exception.getMessage().contains("Event has no attendees"),
                    "Exception message should indicate event has no attendees");
        }

        @Test
        @DisplayName("Given IOException from repository, When removing attendee, Then should throw IllegalStateException")
        void shouldThrowIllegalStateException_whenRepositoryThrowsIOException() throws IOException {
            // Arrange
            when(mockAttendeeService.removeAttendee(TEST_EVENT_ID, TEST_EMAIL))
                    .thenThrow(new IllegalStateException("Failed to remove attendee: API Error"));

            // Act & Assert
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> calendarService.removeAttendee(TEST_EVENT_ID, TEST_EMAIL),
                    "Should throw IllegalStateException when service fails"
            );

            assertTrue(exception.getMessage().contains("Failed to remove attendee"),
                    "Exception message should indicate remove attendee failure");
        }
    }
}

