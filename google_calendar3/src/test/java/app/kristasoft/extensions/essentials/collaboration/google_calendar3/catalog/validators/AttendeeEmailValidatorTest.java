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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for AttendeeEmailValidator.
 * 
 * <p>Tests email format validation and attendee existence checks.</p>
 * 
 * @author Krista Software
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttendeeEmailValidator Validator Tests")
class AttendeeEmailValidatorTest {

    private static final String VALID_EMAIL = "test@example.com";
    private static final String VALID_EVENT_ID = "event-123";

    @Mock
    private CalendarService mockCalendarService;

    private AttendeeEmailValidator validator;
    private Map<Validator.ValidationResource, String> context;

    @BeforeEach
    void setUp() {
        validator = new AttendeeEmailValidator(mockCalendarService);
        context = new HashMap<>();
        context.put(Validator.ValidationResource.EVENT_ID, VALID_EVENT_ID);
    }

    // ==================== Email Format Validation Tests ====================

    @Nested
    @DisplayName("Email Format Validation Tests")
    class EmailFormatTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "test@example.com",
                "user.name@example.com",
                "user+tag@example.co.uk",
                "user_name@example-domain.com",
                "123@example.com",
                "test.email.with+symbol@example4u.net"
        })
        @DisplayName("Given valid email formats, When checking format, Then should return true")
        void shouldReturnTrue_whenValidEmailFormat(String email) {
            // Act
            boolean result = validator.isValidEmail(email);

            // Assert
            assertTrue(result, "Should accept valid email format: " + email);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "invalid.email",
                "@example.com",
                "user@",
                "user name@example.com",
                ""
        })
        @DisplayName("Given invalid email formats, When checking format, Then should return false")
        void shouldReturnFalse_whenInvalidEmailFormat(String email) {
            // Act
            boolean result = validator.isValidEmail(email);

            // Assert
            assertFalse(result, "Should reject invalid email format: " + email);
        }

        @Test
        @DisplayName("Given null email, When checking format, Then should return false")
        void shouldReturnFalse_whenNullEmail() {
            // Act
            boolean result = validator.isValidEmail(null);

            // Assert
            assertFalse(result, "Should return false for null email");
        }
    }

    // ==================== Validation Tests ====================

    @Nested
    @DisplayName("validate() Method Tests")
    class ValidateTests {

        @Test
        @DisplayName("Given valid email in event contacts, When validating, Then should return true")
        void shouldReturnTrue_whenValidEmailInContacts() {
            // Arrange
            EventDetails mockEvent = new EventDetails();
            mockEvent.eventId = VALID_EVENT_ID;
            mockEvent.contacts = Arrays.asList(VALID_EMAIL, "other@example.com");
            when(mockCalendarService.getEvent(VALID_EVENT_ID)).thenReturn(mockEvent);

            // Act
            Boolean result = validator.validate(VALID_EMAIL, context);

            // Assert
            assertTrue(result, "Should return true when email is in event contacts");
            verify(mockCalendarService).getEvent(VALID_EVENT_ID);
        }

        @Test
        @DisplayName("Given invalid email format, When validating, Then should return false")
        void shouldReturnFalse_whenInvalidEmailFormat() {
            // Act
            Boolean result = validator.validate("invalid-email", context);

            // Assert
            assertFalse(result, "Should return false for invalid email format");
            verify(mockCalendarService, never()).getEvent(anyString());
        }

        @Test
        @DisplayName("Given valid email not in contacts, When validating, Then should return false")
        void shouldReturnFalse_whenEmailNotInContacts() {
            // Arrange
            EventDetails mockEvent = new EventDetails();
            mockEvent.eventId = VALID_EVENT_ID;
            mockEvent.contacts = Arrays.asList("other@example.com");
            when(mockCalendarService.getEvent(VALID_EVENT_ID)).thenReturn(mockEvent);

            // Act
            Boolean result = validator.validate(VALID_EMAIL, context);

            // Assert
            assertFalse(result, "Should return false when email not in contacts");
        }

        @Test
        @DisplayName("Given event with null contacts, When validating, Then should return false")
        void shouldReturnFalse_whenEventHasNullContacts() {
            // Arrange
            EventDetails mockEvent = new EventDetails();
            mockEvent.eventId = VALID_EVENT_ID;
            mockEvent.contacts = null;
            when(mockCalendarService.getEvent(VALID_EVENT_ID)).thenReturn(mockEvent);

            // Act
            Boolean result = validator.validate(VALID_EMAIL, context);

            // Assert
            assertFalse(result, "Should return false when event has null contacts");
        }

        @Test
        @DisplayName("Given event not found, When validating, Then should return false")
        void shouldReturnFalse_whenEventNotFound() {
            // Arrange
            when(mockCalendarService.getEvent(VALID_EVENT_ID)).thenReturn(null);

            // Act
            Boolean result = validator.validate(VALID_EMAIL, context);

            // Assert
            assertFalse(result, "Should return false when event not found");
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
                    () -> validator.validate(VALID_EMAIL, context),
                    "Should throw MustAuthorizeException for authorization errors"
            );
        }

        @Test
        @DisplayName("Given service throws exception, When validating, Then should return false")
        void shouldReturnFalse_whenServiceThrowsException() {
            // Arrange
            when(mockCalendarService.getEvent(anyString()))
                    .thenThrow(new RuntimeException("Service error"));

            // Act
            Boolean result = validator.validate(VALID_EMAIL, context);

            // Assert
            assertFalse(result, "Should return false when service throws exception");
        }
    }

    // ==================== Field Configuration Tests ====================

    @Nested
    @DisplayName("Field Configuration Tests")
    class FieldConfigurationTests {

        @Test
        @DisplayName("Given validator, When getting fetch field name, Then should return ATTENDEE_EMAIL")
        void shouldReturnAttendeeEmail_whenGettingFetchFieldName() {
            // Act
            String result = validator.getFetchFieldName();

            // Assert
            assertEquals(CalendarResource.ATTENDEE_EMAIL, result, "Fetch field name should be ATTENDEE_EMAIL");
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
            assertTrue(result.contains("Attendee Email"), "Message should mention Attendee Email");
        }

        @Test
        @DisplayName("Given invalid email, When getting confirmation message, Then should return error message")
        void shouldReturnConfirmationMessage_whenGettingConfirmationStepMessage() {
            // Act
            String result = validator.getConfirmationStepMessage("invalid@test.com", context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.contains("invalid@test.com"), "Message should contain the email");
        }

        @Test
        @DisplayName("Given invalid email, When getting error message, Then should return formatted error")
        void shouldReturnErrorMessage_whenGettingErrMessage() {
            // Act
            String result = validator.getErrMessage("invalid@test.com");

            // Assert
            assertNotNull(result, "Error message should not be null");
            assertTrue(result.contains("invalid@test.com"), "Error message should contain the email");
        }
    }
}

