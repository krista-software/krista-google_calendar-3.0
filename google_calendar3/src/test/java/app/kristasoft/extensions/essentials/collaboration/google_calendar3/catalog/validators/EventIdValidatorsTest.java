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

import app.krista.extension.authorization.MustAuthorizeException;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.entity.EventDetails;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.CalendarResource;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.FieldTypes;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.CalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for EventIdValidators.
 * 
 * <p>Tests validation logic for event ID including existence checks and error handling.</p>
 * 
 * @author Krista Software
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventIdValidators Validator Tests")
class EventIdValidatorsTest {

    private static final String VALID_EVENT_ID = "valid-event-123";
    private static final String INVALID_EVENT_ID = "invalid-event-456";

    @Mock
    private CalendarService mockCalendarService;

    private EventIdValidators validator;
    private Map<Validator.ValidationResource, String> context;

    @BeforeEach
    void setUp() {
        validator = new EventIdValidators(mockCalendarService);
        context = new HashMap<>();
    }

    // ==================== Validation Tests ====================

    @Nested
    @DisplayName("validate() Method Tests")
    class ValidateTests {

        @Test
        @DisplayName("Given valid event ID, When validating, Then should return true")
        void shouldReturnTrue_whenValidEventId() {
            // Arrange
            EventDetails mockEvent = new EventDetails();
            mockEvent.eventId = VALID_EVENT_ID;
            when(mockCalendarService.getEvent(VALID_EVENT_ID)).thenReturn(mockEvent);

            // Act
            Boolean result = validator.validate(VALID_EVENT_ID, context);

            // Assert
            assertTrue(result, "Should return true for valid event ID");
            verify(mockCalendarService).getEvent(VALID_EVENT_ID);
        }

        @Test
        @DisplayName("Given non-existent event ID, When validating, Then should return false")
        void shouldReturnFalse_whenEventNotFound() {
            // Arrange
            when(mockCalendarService.getEvent(INVALID_EVENT_ID)).thenReturn(null);

            // Act
            Boolean result = validator.validate(INVALID_EVENT_ID, context);

            // Assert
            assertFalse(result, "Should return false when event not found");
            verify(mockCalendarService).getEvent(INVALID_EVENT_ID);
        }

        @Test
        @DisplayName("Given service throws exception, When validating, Then should return false")
        void shouldReturnFalse_whenServiceThrowsException() {
            // Arrange
            when(mockCalendarService.getEvent(anyString()))
                    .thenThrow(new RuntimeException("Service error"));

            // Act
            Boolean result = validator.validate(INVALID_EVENT_ID, context);

            // Assert
            assertFalse(result, "Should return false when service throws exception");
        }

        @Test
        @DisplayName("Given authorization error, When validating, Then should throw MustAuthorizeException")
        void shouldThrowMustAuthorizeException_whenAuthorizationFails() {
            // Arrange
            when(mockCalendarService.getEvent(anyString()))
                    .thenThrow(new MustAuthorizeException("Authorization required"));

            // Act & Assert
            assertThrows(
                    MustAuthorizeException.class,
                    () -> validator.validate(VALID_EVENT_ID, context),
                    "Should throw MustAuthorizeException for authorization errors"
            );
        }
    }

    // ==================== Field Configuration Tests ====================

    @Nested
    @DisplayName("Field Configuration Tests")
    class FieldConfigurationTests {

        @Test
        @DisplayName("Given validator, When getting fetch field name, Then should return EVENT_ID")
        void shouldReturnEventId_whenGettingFetchFieldName() {
            // Act
            String result = validator.getFetchFieldName();

            // Assert
            assertEquals(CalendarResource.EVENT_ID, result, "Fetch field name should be EVENT_ID");
        }

        @Test
        @DisplayName("Given validator, When getting field type, Then should return TEXT_FIELD")
        void shouldReturnTextField_whenGettingFieldType() {
            // Act
            String result = validator.getFieldType();

            // Assert
            assertEquals(FieldTypes.TEXT_FIELD, result, "Field type should be TEXT_FIELD");
        }

        @Test
        @DisplayName("Given validator, When getting fetch step message, Then should return appropriate message")
        void shouldReturnFetchMessage_whenGettingFetchStepMessage() {
            // Act
            String result = validator.getFetchStepMessage();

            // Assert
            assertNotNull(result, "Fetch step message should not be null");
            assertTrue(result.contains("Event Id"), "Message should mention Event Id");
        }

        @Test
        @DisplayName("Given invalid event ID, When getting confirmation message, Then should return error message")
        void shouldReturnConfirmationMessage_whenGettingConfirmationStepMessage() {
            // Act
            String result = validator.getConfirmationStepMessage(INVALID_EVENT_ID, context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.contains(INVALID_EVENT_ID), "Message should contain the event ID");
            assertTrue(result.contains("invalid"), "Message should indicate invalidity");
        }

        @Test
        @DisplayName("Given invalid event ID, When getting error message, Then should return formatted error")
        void shouldReturnErrorMessage_whenGettingErrMessage() {
            // Act
            String result = validator.getErrMessage(INVALID_EVENT_ID);

            // Assert
            assertNotNull(result, "Error message should not be null");
            assertTrue(result.contains(INVALID_EVENT_ID), "Error message should contain the event ID");
            assertTrue(result.contains("Invalid"), "Error message should indicate invalidity");
        }
    }
}

