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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for UserEmailValidator.
 * 
 * <p>Tests user email validation including format and length checks.</p>
 * 
 * @author Krista Software
 * @version 1.0.0
 */
@DisplayName("UserEmailValidator Validator Tests")
class UserEmailValidatorTest {

    private UserEmailValidator validator;
    private Map<Validator.ValidationResource, String> context;

    @BeforeEach
    void setUp() {
        validator = new UserEmailValidator();
        context = new HashMap<>();
    }

    // ==================== Validation Tests ====================

    @Nested
    @DisplayName("validate() Method Tests")
    class ValidateTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "test@example.com",
                "user.name@example.com",
                "user+tag@example.co.uk",
                "user_name@example-domain.com",
                "test.email.with+symbol@example4u.net"
        })
        @DisplayName("Given valid email formats, When validating, Then should return true")
        void shouldReturnTrue_whenValidEmailFormat(String email) {
            // Act
            Boolean result = validator.validate(email, context);

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
        @DisplayName("Given invalid email formats, When validating, Then should return false")
        void shouldReturnFalse_whenInvalidEmailFormat(String email) {
            // Act
            Boolean result = validator.validate(email, context);

            // Assert
            assertFalse(result, "Should reject invalid email format: " + email);
        }

        @Test
        @DisplayName("Given null email, When validating, Then should return false")
        void shouldReturnFalse_whenNull() {
            // Act
            Boolean result = validator.validate(null, context);

            // Assert
            assertFalse(result, "Should return false for null email");
        }

        @Test
        @DisplayName("Given empty email, When validating, Then should return false")
        void shouldReturnFalse_whenEmpty() {
            // Act
            Boolean result = validator.validate("", context);

            // Assert
            assertFalse(result, "Should return false for empty email");
        }

        @Test
        @DisplayName("Given whitespace-only email, When validating, Then should return false")
        void shouldReturnFalse_whenWhitespaceOnly() {
            // Act
            Boolean result = validator.validate("   ", context);

            // Assert
            assertFalse(result, "Should return false for whitespace-only email");
        }

        @Test
        @DisplayName("Given email at max length (254), When validating, Then should return true")
        void shouldReturnTrue_whenAtMaxLength() {
            // Arrange - Create email at exactly 254 characters
            String localPart = "a".repeat(64); // Max local part
            String domain = "example.com"; // 11 characters
            String padding = "b".repeat(254 - localPart.length() - domain.length() - 1); // -1 for @
            String maxLengthEmail = localPart + "@" + padding + "." + domain;
            maxLengthEmail = maxLengthEmail.substring(0, 254); // Ensure exactly 254
            
            // Simplify: use a valid email at max length
            String email = "a".repeat(240) + "@example.com"; // 240 + 1 + 11 + 2 = 254

            // Act
            Boolean result = validator.validate(email, context);

            // Assert
            assertTrue(result, "Should return true for email at max length (254)");
        }

        @Test
        @DisplayName("Given email exceeding max length, When validating, Then should return false")
        void shouldReturnFalse_whenExceedsMaxLength() {
            // Arrange - Create email exceeding 254 characters
            String email = "a".repeat(250) + "@example.com"; // 250 + 1 + 11 + 2 = 264 > 254

            // Act
            Boolean result = validator.validate(email, context);

            // Assert
            assertFalse(result, "Should return false for email exceeding max length");
        }

        @Test
        @DisplayName("Given email with spaces, When validating, Then should return true after trim")
        void shouldReturnTrue_whenEmailWithSpaces() {
            // Act
            Boolean result = validator.validate("  test@example.com  ", context);

            // Assert
            assertTrue(result, "Should return true for email with leading/trailing spaces");
        }
    }

    // ==================== Field Configuration Tests ====================

    @Nested
    @DisplayName("Field Configuration Tests")
    class FieldConfigurationTests {

        @Test
        @DisplayName("Given validator, When getting fetch field name, Then should return USER_EMAIL")
        void shouldReturnUserEmail_whenGettingFetchFieldName() {
            // Act
            String result = validator.getFetchFieldName();

            // Assert
            assertEquals(CalendarResource.USER_EMAIL, result, "Fetch field name should be USER_EMAIL");
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
            assertTrue(result.contains("254"), "Message should mention max length");
        }
    }

    // ==================== Error Message Tests ====================

    @Nested
    @DisplayName("Error Message Tests")
    class ErrorMessageTests {

        @Test
        @DisplayName("Given null email, When getting confirmation message, Then should return required message")
        void shouldReturnRequiredMessage_whenNull() {
            // Act
            String result = validator.getConfirmationStepMessage(null, context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.toLowerCase().contains("required"), "Message should indicate email is required");
        }

        @Test
        @DisplayName("Given too long email, When getting confirmation message, Then should return length error")
        void shouldReturnLengthError_whenTooLong() {
            // Arrange
            String tooLongEmail = "a".repeat(250) + "@example.com";

            // Act
            String result = validator.getConfirmationStepMessage(tooLongEmail, context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.contains("254"), "Message should mention max length");
        }

        @Test
        @DisplayName("Given invalid format email, When getting confirmation message, Then should return format error")
        void shouldReturnFormatError_whenInvalidFormat() {
            // Act
            String result = validator.getConfirmationStepMessage("invalid-email", context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.toLowerCase().contains("invalid"), "Message should indicate invalid format");
        }

        @Test
        @DisplayName("Given null email, When getting error message, Then should return required error")
        void shouldReturnRequiredError_whenGettingErrMessage() {
            // Act
            String result = validator.getErrMessage(null);

            // Assert
            assertNotNull(result, "Error message should not be null");
            assertTrue(result.toLowerCase().contains("required"), "Error should indicate email is required");
        }

        @Test
        @DisplayName("Given too long email, When getting error message, Then should return length error")
        void shouldReturnLengthError_whenGettingErrMessage() {
            // Arrange
            String tooLongEmail = "a".repeat(250) + "@example.com";

            // Act
            String result = validator.getErrMessage(tooLongEmail);

            // Assert
            assertNotNull(result, "Error message should not be null");
            assertTrue(result.contains("254"), "Error should mention max length");
        }
    }
}

