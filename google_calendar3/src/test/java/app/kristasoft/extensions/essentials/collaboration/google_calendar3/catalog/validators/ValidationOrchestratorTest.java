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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.validators;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.entity.EventDetails;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.CalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Comprehensive test suite for ValidationOrchestrator.
 * 
 * <p>Tests validator registration, orchestration, and validation result aggregation.</p>
 * 
 * @author Krista Software
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ValidationOrchestrator Tests")
class ValidationOrchestratorTest {

    @Mock
    private CalendarService mockCalendarService;

    private ValidationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ValidationOrchestrator(mockCalendarService);
    }

    // ==================== Validator Registration Tests ====================

    @Nested
    @DisplayName("Validator Registration Tests")
    class ValidatorRegistrationTests {

        @Test
        @DisplayName("Given orchestrator, When validating EVENT_ID, Then should use EventIdValidators")
        void shouldUseEventIdValidator_whenValidatingEventId() {
            // Arrange
            Map<Validator.ValidationResource, String> resources = new HashMap<>();
            resources.put(Validator.ValidationResource.EVENT_ID, "invalid-event-id");
            when(mockCalendarService.getEvent(anyString())).thenReturn(null);

            // Act
            List<ValidationOrchestrator.ValidationResult> results = orchestrator.validate(resources);

            // Assert
            assertFalse(results.isEmpty(), "Should return validation error for invalid event ID");
            assertEquals(1, results.size(), "Should have exactly one validation error");
        }

        @Test
        @DisplayName("Given orchestrator, When validating TITLE, Then should use TitleValidator")
        void shouldUseTitleValidator_whenValidatingTitle() {
            // Arrange
            Map<Validator.ValidationResource, String> resources = new HashMap<>();
            resources.put(Validator.ValidationResource.TITLE, ""); // Invalid: empty title

            // Act
            List<ValidationOrchestrator.ValidationResult> results = orchestrator.validate(resources);

            // Assert
            assertFalse(results.isEmpty(), "Should return validation error for empty title");
        }

        @Test
        @DisplayName("Given orchestrator, When validating NUMBER_OF_MEETINGS, Then should use NumberOfMeetingsValidator")
        void shouldUseNumberOfMeetingsValidator_whenValidatingNumberOfMeetings() {
            // Arrange
            Map<Validator.ValidationResource, String> resources = new HashMap<>();
            resources.put(Validator.ValidationResource.NUMBER_OF_MEETINGS, "0"); // Invalid: zero

            // Act
            List<ValidationOrchestrator.ValidationResult> results = orchestrator.validate(resources);

            // Assert
            assertFalse(results.isEmpty(), "Should return validation error for zero meetings");
        }
    }

    // ==================== Validation Tests ====================

    @Nested
    @DisplayName("validate() Method Tests")
    class ValidateTests {

        @Test
        @DisplayName("Given all valid resources, When validating, Then should return empty list")
        void shouldReturnEmptyList_whenAllResourcesValid() {
            // Arrange
            EventDetails mockEvent = new EventDetails();
            mockEvent.eventId = "valid-event-123";
            mockEvent.contacts = Arrays.asList("test@example.com");
            when(mockCalendarService.getEvent("valid-event-123")).thenReturn(mockEvent);

            Map<Validator.ValidationResource, String> resources = new HashMap<>();
            resources.put(Validator.ValidationResource.EVENT_ID, "valid-event-123");
            resources.put(Validator.ValidationResource.TITLE, "Valid Meeting Title");
            resources.put(Validator.ValidationResource.NUMBER_OF_MEETINGS, "5");

            // Act
            List<ValidationOrchestrator.ValidationResult> results = orchestrator.validate(resources);

            // Assert
            assertTrue(results.isEmpty(), "Should return empty list when all resources are valid");
        }

        @Test
        @DisplayName("Given one invalid resource, When validating, Then should return one error")
        void shouldReturnOneError_whenOneResourceInvalid() {
            // Arrange
            Map<Validator.ValidationResource, String> resources = new HashMap<>();
            resources.put(Validator.ValidationResource.TITLE, ""); // Invalid

            // Act
            List<ValidationOrchestrator.ValidationResult> results = orchestrator.validate(resources);

            // Assert
            assertEquals(1, results.size(), "Should return exactly one validation error");
        }

        @Test
        @DisplayName("Given multiple invalid resources, When validating, Then should return multiple errors")
        void shouldReturnMultipleErrors_whenMultipleResourcesInvalid() {
            // Arrange
            Map<Validator.ValidationResource, String> resources = new HashMap<>();
            resources.put(Validator.ValidationResource.TITLE, ""); // Invalid: empty
            resources.put(Validator.ValidationResource.NUMBER_OF_MEETINGS, "0"); // Invalid: zero
            resources.put(Validator.ValidationResource.USER_EMAIL, "invalid-email"); // Invalid: no @

            // Act
            List<ValidationOrchestrator.ValidationResult> results = orchestrator.validate(resources);

            // Assert
            assertEquals(3, results.size(), "Should return three validation errors");
        }

        @Test
        @DisplayName("Given empty resources map, When validating, Then should return empty list")
        void shouldReturnEmptyList_whenResourcesEmpty() {
            // Arrange
            Map<Validator.ValidationResource, String> resources = new HashMap<>();

            // Act
            List<ValidationOrchestrator.ValidationResult> results = orchestrator.validate(resources);

            // Assert
            assertTrue(results.isEmpty(), "Should return empty list for empty resources map");
        }

        @Test
        @DisplayName("Given validation failure, When validating, Then should return complete ValidationResult")
        void shouldReturnCompleteValidationResult_whenValidationFails() {
            // Arrange
            Map<Validator.ValidationResource, String> resources = new HashMap<>();
            resources.put(Validator.ValidationResource.TITLE, ""); // Invalid

            // Act
            List<ValidationOrchestrator.ValidationResult> results = orchestrator.validate(resources);

            // Assert
            assertEquals(1, results.size(), "Should have one validation result");
            ValidationOrchestrator.ValidationResult result = results.get(0);
            assertNotNull(result.confirmStepMessage(), "Confirmation message should not be null");
            assertNotNull(result.fetchFieldName(), "Fetch field name should not be null");
            assertNotNull(result.fetchStepMessage(), "Fetch step message should not be null");
            assertNotNull(result.errMessage(), "Error message should not be null");
            assertNotNull(result.fieldType(), "Field type should not be null");
        }
    }

    // ==================== Date Validation Tests ====================

    @Nested
    @DisplayName("Date Validation Tests")
    class DateValidationTests {

        @Test
        @DisplayName("Given valid date range, When validating, Then should return empty list")
        void shouldReturnEmptyList_whenValidDateRange() {
            // Arrange
            long fromTimestamp = Instant.now().plusSeconds(3600).toEpochMilli();
            long toTimestamp = Instant.now().plusSeconds(7200).toEpochMilli();

            Map<Validator.ValidationResource, String> resources = new HashMap<>();
            resources.put(Validator.ValidationResource.DATE_FROM, String.valueOf(fromTimestamp));
            resources.put(Validator.ValidationResource.DATE_TO, String.valueOf(toTimestamp));

            // Act
            List<ValidationOrchestrator.ValidationResult> results = orchestrator.validate(resources);

            // Assert
            assertTrue(results.isEmpty(), "Should return empty list for valid date range");
        }

        @Test
        @DisplayName("Given invalid date range (to before from), When validating, Then should return error")
        void shouldReturnError_whenToDateBeforeFromDate() {
            // Arrange
            long fromTimestamp = Instant.now().plusSeconds(7200).toEpochMilli();
            long toTimestamp = Instant.now().plusSeconds(3600).toEpochMilli();

            Map<Validator.ValidationResource, String> resources = new HashMap<>();
            resources.put(Validator.ValidationResource.DATE_FROM, String.valueOf(fromTimestamp));
            resources.put(Validator.ValidationResource.DATE_TO, String.valueOf(toTimestamp));

            // Act
            List<ValidationOrchestrator.ValidationResult> results = orchestrator.validate(resources);

            // Assert
            assertFalse(results.isEmpty(), "Should return validation error when to date is before from date");
        }

        @Test
        @DisplayName("Given valid from/to date range, When validating, Then should return empty list")
        void shouldReturnEmptyList_whenValidFromToDateRange() {
            // Arrange
            long fromTimestamp = Instant.now().toEpochMilli();
            long toTimestamp = Instant.now().plusSeconds(3600).toEpochMilli();

            Map<Validator.ValidationResource, String> resources = new HashMap<>();
            resources.put(Validator.ValidationResource.FROM_DATE, String.valueOf(fromTimestamp));
            resources.put(Validator.ValidationResource.TO_DATE, String.valueOf(toTimestamp));

            // Act
            List<ValidationOrchestrator.ValidationResult> results = orchestrator.validate(resources);

            // Assert
            assertTrue(results.isEmpty(), "Should return empty list for valid from/to date range");
        }
    }

    // ==================== Email Validation Tests ====================

    @Nested
    @DisplayName("Email Validation Tests")
    class EmailValidationTests {

        @Test
        @DisplayName("Given valid user email, When validating, Then should return empty list")
        void shouldReturnEmptyList_whenValidUserEmail() {
            // Arrange
            Map<Validator.ValidationResource, String> resources = new HashMap<>();
            resources.put(Validator.ValidationResource.USER_EMAIL, "test@example.com");

            // Act
            List<ValidationOrchestrator.ValidationResult> results = orchestrator.validate(resources);

            // Assert
            assertTrue(results.isEmpty(), "Should return empty list for valid user email");
        }

        @Test
        @DisplayName("Given valid attendee email list, When validating, Then should return empty list")
        void shouldReturnEmptyList_whenValidAttendeeEmailList() {
            // Arrange
            Map<Validator.ValidationResource, String> resources = new HashMap<>();
            resources.put(Validator.ValidationResource.ATTENDEE_EMAIL_LIST, "test1@example.com,test2@example.com");

            // Act
            List<ValidationOrchestrator.ValidationResult> results = orchestrator.validate(resources);

            // Assert
            assertTrue(results.isEmpty(), "Should return empty list for valid attendee email list");
        }

        @Test
        @DisplayName("Given invalid attendee email list, When validating, Then should return error")
        void shouldReturnError_whenInvalidAttendeeEmailList() {
            // Arrange
            Map<Validator.ValidationResource, String> resources = new HashMap<>();
            resources.put(Validator.ValidationResource.ATTENDEE_EMAIL_LIST, "invalid-email,test@example.com");

            // Act
            List<ValidationOrchestrator.ValidationResult> results = orchestrator.validate(resources);

            // Assert
            assertFalse(results.isEmpty(), "Should return validation error for invalid email in list");
        }
    }
}

