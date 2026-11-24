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

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.CalendarResource;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.FieldTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for AttendeeEmailListValidator.
 * 
 * <p>Tests email list validation including format checks and list parsing.</p>
 * 
 * @author Krista Software
 * @version 1.0.0
 */
@DisplayName("AttendeeEmailListValidator Validator Tests")
class AttendeeEmailListValidatorTest {

    private AttendeeEmailListValidator validator;
    private Map<Validator.ValidationResource, String> context;

    @BeforeEach
    void setUp() {
        validator = new AttendeeEmailListValidator();
        context = new HashMap<>();
    }

    // ==================== Validation Tests ====================

    @Nested
    @DisplayName("validate() Method Tests")
    class ValidateTests {

        @Test
        @DisplayName("Given single valid email, When validating, Then should return true")
        void shouldReturnTrue_whenSingleValidEmail() {
            // Act
            Boolean result = validator.validate("test@example.com", context);

            // Assert
            assertTrue(result, "Should return true for single valid email");
        }

        @Test
        @DisplayName("Given multiple valid emails, When validating, Then should return true")
        void shouldReturnTrue_whenMultipleValidEmails() {
            // Act
            Boolean result = validator.validate("test1@example.com,test2@example.com,test3@example.com", context);

            // Assert
            assertTrue(result, "Should return true for multiple valid emails");
        }

        @Test
        @DisplayName("Given emails with spaces, When validating, Then should return true")
        void shouldReturnTrue_whenEmailsWithSpaces() {
            // Act
            Boolean result = validator.validate("test1@example.com, test2@example.com, test3@example.com", context);

            // Assert
            assertTrue(result, "Should return true for emails with spaces after commas");
        }

        @Test
        @DisplayName("Given list with one invalid email, When validating, Then should return false")
        void shouldReturnFalse_whenOneInvalidEmail() {
            // Act
            Boolean result = validator.validate("test1@example.com,invalid-email,test3@example.com", context);

            // Assert
            assertFalse(result, "Should return false when one email is invalid");
        }

        @Test
        @DisplayName("Given null email list, When validating, Then should return false")
        void shouldReturnFalse_whenNull() {
            // Act
            Boolean result = validator.validate(null, context);

            // Assert
            assertFalse(result, "Should return false for null email list");
        }

        @Test
        @DisplayName("Given empty email list, When validating, Then should return false")
        void shouldReturnFalse_whenEmpty() {
            // Act
            Boolean result = validator.validate("", context);

            // Assert
            assertFalse(result, "Should return false for empty email list");
        }

        @Test
        @DisplayName("Given whitespace-only email list, When validating, Then should return false")
        void shouldReturnFalse_whenWhitespaceOnly() {
            // Act
            Boolean result = validator.validate("   ", context);

            // Assert
            assertFalse(result, "Should return false for whitespace-only email list");
        }

        @Test
        @DisplayName("Given email without TLD, When validating, Then should return false")
        void shouldReturnFalse_whenEmailWithoutTLD() {
            // Act
            Boolean result = validator.validate("test@example", context);

            // Assert
            assertFalse(result, "Should return false for email without TLD");
        }

        @Test
        @DisplayName("Given email with invalid characters, When validating, Then should return false")
        void shouldReturnFalse_whenInvalidCharacters() {
            // Act
            Boolean result = validator.validate("test user@example.com", context);

            // Assert
            assertFalse(result, "Should return false for email with spaces");
        }

        @Test
        @DisplayName("Given complex valid emails, When validating, Then should return true")
        void shouldReturnTrue_whenComplexValidEmails() {
            // Act
            Boolean result = validator.validate("user.name+tag@example.co.uk,test_user@domain-name.com", context);

            // Assert
            assertTrue(result, "Should return true for complex valid email formats");
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
        @DisplayName("Given validator, When getting field type, Then should return LIST_FIELD")
        void shouldReturnListField_whenGettingFieldType() {
            // Act
            String result = validator.getFieldType();

            // Assert
            assertEquals(FieldTypes.LIST_FIELD, result, "Field type should be LIST_FIELD");
        }

        @Test
        @DisplayName("Given validator, When getting fetch step message, Then should return appropriate message")
        void shouldReturnFetchMessage_whenGettingFetchStepMessage() {
            // Act
            String result = validator.getFetchStepMessage();

            // Assert
            assertNotNull(result, "Fetch step message should not be null");
            assertTrue(result.toLowerCase().contains("comma"), "Message should mention comma-separated");
        }

        @Test
        @DisplayName("Given invalid email list, When getting confirmation message, Then should return error message")
        void shouldReturnConfirmationMessage_whenGettingConfirmationStepMessage() {
            // Arrange
            String invalidList = "invalid-email,test@example.com";

            // Act
            String result = validator.getConfirmationStepMessage(invalidList, context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.contains(invalidList), "Message should contain the email list");
        }

        @Test
        @DisplayName("Given invalid email list, When getting error message, Then should return formatted error")
        void shouldReturnErrorMessage_whenGettingErrMessage() {
            // Arrange
            String invalidList = "invalid-email";

            // Act
            String result = validator.getErrMessage(invalidList);

            // Assert
            assertNotNull(result, "Error message should not be null");
            assertTrue(result.contains(invalidList), "Error message should contain the email list");
        }
    }
}

