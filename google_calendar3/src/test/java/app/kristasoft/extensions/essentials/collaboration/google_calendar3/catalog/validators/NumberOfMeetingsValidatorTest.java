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
 * Comprehensive test suite for NumberOfMeetingsValidator.
 * 
 * <p>Tests number validation including format, range, and whole number checks.</p>
 * 
 * @author Krista Software
 * @version 1.0.0
 */
@DisplayName("NumberOfMeetingsValidator Validator Tests")
class NumberOfMeetingsValidatorTest {

    private NumberOfMeetingsValidator validator;
    private Map<Validator.ValidationResource, String> context;

    @BeforeEach
    void setUp() {
        validator = new NumberOfMeetingsValidator();
        context = new HashMap<>();
    }

    // ==================== Validation Tests ====================

    @Nested
    @DisplayName("validate() Method Tests")
    class ValidateTests {

        @ParameterizedTest
        @ValueSource(strings = {"1", "5", "10", "50", "100"})
        @DisplayName("Given valid number of meetings, When validating, Then should return true")
        void shouldReturnTrue_whenValidNumber(String number) {
            // Act
            Boolean result = validator.validate(number, context);

            // Assert
            assertTrue(result, "Should return true for valid number: " + number);
        }

        @Test
        @DisplayName("Given minimum valid number (1), When validating, Then should return true")
        void shouldReturnTrue_whenMinimumNumber() {
            // Act
            Boolean result = validator.validate("1", context);

            // Assert
            assertTrue(result, "Should return true for minimum number (1)");
        }

        @Test
        @DisplayName("Given maximum valid number (100), When validating, Then should return true")
        void shouldReturnTrue_whenMaximumNumber() {
            // Act
            Boolean result = validator.validate("100", context);

            // Assert
            assertTrue(result, "Should return true for maximum number (100)");
        }

        @Test
        @DisplayName("Given zero, When validating, Then should return false")
        void shouldReturnFalse_whenZero() {
            // Act
            Boolean result = validator.validate("0", context);

            // Assert
            assertFalse(result, "Should return false for zero");
        }

        @Test
        @DisplayName("Given negative number, When validating, Then should return false")
        void shouldReturnFalse_whenNegativeNumber() {
            // Act
            Boolean result = validator.validate("-5", context);

            // Assert
            assertFalse(result, "Should return false for negative number");
        }

        @Test
        @DisplayName("Given number below minimum (0), When validating, Then should return false")
        void shouldReturnFalse_whenBelowMinimum() {
            // Act
            Boolean result = validator.validate("0", context);

            // Assert
            assertFalse(result, "Should return false for number below minimum");
        }

        @Test
        @DisplayName("Given number above maximum (101), When validating, Then should return false")
        void shouldReturnFalse_whenAboveMaximum() {
            // Act
            Boolean result = validator.validate("101", context);

            // Assert
            assertFalse(result, "Should return false for number above maximum (100)");
        }

        @Test
        @DisplayName("Given decimal number, When validating, Then should return false")
        void shouldReturnFalse_whenDecimalNumber() {
            // Act
            Boolean result = validator.validate("5.5", context);

            // Assert
            assertFalse(result, "Should return false for decimal number");
        }

        @Test
        @DisplayName("Given non-numeric string, When validating, Then should return false")
        void shouldReturnFalse_whenNonNumeric() {
            // Act
            Boolean result = validator.validate("abc", context);

            // Assert
            assertFalse(result, "Should return false for non-numeric string");
        }

        @Test
        @DisplayName("Given null value, When validating, Then should return false")
        void shouldReturnFalse_whenNull() {
            // Act
            Boolean result = validator.validate(null, context);

            // Assert
            assertFalse(result, "Should return false for null value");
        }

        @Test
        @DisplayName("Given empty string, When validating, Then should return false")
        void shouldReturnFalse_whenEmpty() {
            // Act
            Boolean result = validator.validate("", context);

            // Assert
            assertFalse(result, "Should return false for empty string");
        }

        @Test
        @DisplayName("Given whitespace-only string, When validating, Then should return false")
        void shouldReturnFalse_whenWhitespaceOnly() {
            // Act
            Boolean result = validator.validate("   ", context);

            // Assert
            assertFalse(result, "Should return false for whitespace-only string");
        }

        @Test
        @DisplayName("Given number with leading/trailing spaces, When validating, Then should return true")
        void shouldReturnTrue_whenNumberWithSpaces() {
            // Act
            Boolean result = validator.validate("  10  ", context);

            // Assert
            assertTrue(result, "Should return true for number with leading/trailing spaces");
        }

        @ParameterizedTest
        @ValueSource(strings = {"1.5", "10.1", "99.9"})
        @DisplayName("Given various decimal numbers, When validating, Then should return false")
        void shouldReturnFalse_whenVariousDecimals(String number) {
            // Act
            Boolean result = validator.validate(number, context);

            // Assert
            assertFalse(result, "Should return false for decimal number: " + number);
        }
    }

    // ==================== Field Configuration Tests ====================

    @Nested
    @DisplayName("Field Configuration Tests")
    class FieldConfigurationTests {

        @Test
        @DisplayName("Given validator, When getting fetch field name, Then should return NUMBER_OF_MEETINGS")
        void shouldReturnNumberOfMeetings_whenGettingFetchFieldName() {
            // Act
            String result = validator.getFetchFieldName();

            // Assert
            assertEquals(CalendarResource.NUMBER_OF_MEETINGS, result, 
                    "Fetch field name should be NUMBER_OF_MEETINGS");
        }

        @Test
        @DisplayName("Given validator, When getting field type, Then should return NUMBER_FIELD")
        void shouldReturnNumberField_whenGettingFieldType() {
            // Act
            String result = validator.getFieldType();

            // Assert
            assertEquals(FieldTypes.NUMBER_FIELD, result, "Field type should be NUMBER_FIELD");
        }

        @Test
        @DisplayName("Given validator, When getting fetch step message, Then should return appropriate message")
        void shouldReturnFetchMessage_whenGettingFetchStepMessage() {
            // Act
            String result = validator.getFetchStepMessage();

            // Assert
            assertNotNull(result, "Fetch step message should not be null");
            assertTrue(result.contains("1"), "Message should mention minimum (1)");
            assertTrue(result.contains("100"), "Message should mention maximum (100)");
        }
    }

    // ==================== Error Message Tests ====================

    @Nested
    @DisplayName("Error Message Tests")
    class ErrorMessageTests {

        @Test
        @DisplayName("Given null value, When getting confirmation message, Then should return required message")
        void shouldReturnRequiredMessage_whenNull() {
            // Act
            String result = validator.getConfirmationStepMessage(null, context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.toLowerCase().contains("required"), "Message should indicate value is required");
        }

        @Test
        @DisplayName("Given negative number, When getting confirmation message, Then should return positive error")
        void shouldReturnPositiveError_whenNegative() {
            // Act
            String result = validator.getConfirmationStepMessage("-5", context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.toLowerCase().contains("positive"), "Message should indicate must be positive");
        }

        @Test
        @DisplayName("Given out of range number, When getting confirmation message, Then should return range error")
        void shouldReturnRangeError_whenOutOfRange() {
            // Act
            String result = validator.getConfirmationStepMessage("200", context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.contains("1"), "Message should mention minimum");
            assertTrue(result.contains("100"), "Message should mention maximum");
        }

        @Test
        @DisplayName("Given decimal number, When getting confirmation message, Then should return whole number error")
        void shouldReturnWholeNumberError_whenDecimal() {
            // Act
            String result = validator.getConfirmationStepMessage("5.5", context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.toLowerCase().contains("whole"), "Message should indicate must be whole number");
        }

        @Test
        @DisplayName("Given non-numeric string, When getting confirmation message, Then should return format error")
        void shouldReturnFormatError_whenNonNumeric() {
            // Act
            String result = validator.getConfirmationStepMessage("abc", context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.contains("abc"), "Message should contain the invalid value");
            assertTrue(result.toLowerCase().contains("valid"), "Message should indicate invalid format");
        }

        @Test
        @DisplayName("Given null value, When getting error message, Then should return required error")
        void shouldReturnRequiredError_whenGettingErrMessage() {
            // Act
            String result = validator.getErrMessage(null);

            // Assert
            assertNotNull(result, "Error message should not be null");
            assertTrue(result.toLowerCase().contains("required"), "Error should indicate value is required");
        }

        @Test
        @DisplayName("Given negative number, When getting error message, Then should return positive error")
        void shouldReturnPositiveError_whenGettingErrMessage() {
            // Act
            String result = validator.getErrMessage("-5");

            // Assert
            assertNotNull(result, "Error message should not be null");
            assertTrue(result.toLowerCase().contains("positive"), "Error should indicate must be positive");
        }
    }
}

