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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for DateToValidator.
 * 
 * <p>Tests end date validation including timestamp format and date comparison.</p>
 * 
 * @author Krista Software
 * @version 1.0.0
 */
@DisplayName("DateToValidator Validator Tests")
class DateToValidatorTest {

    private DateToValidator validator;
    private Map<Validator.ValidationResource, String> context;

    @BeforeEach
    void setUp() {
        validator = new DateToValidator();
        context = new HashMap<>();
    }

    // ==================== Validation Tests ====================

    @Nested
    @DisplayName("validate() Method Tests")
    class ValidateTests {

        @Test
        @DisplayName("Given valid future timestamp, When validating, Then should return true")
        void shouldReturnTrue_whenValidFutureTimestamp() {
            // Arrange
            long futureTimestamp = Instant.now().plusSeconds(7200).toEpochMilli(); // 2 hours from now

            // Act
            Boolean result = validator.validate(String.valueOf(futureTimestamp), context);

            // Assert
            assertTrue(result, "Should return true for valid future timestamp");
        }

        @Test
        @DisplayName("Given valid end date after start date, When validating, Then should return true")
        void shouldReturnTrue_whenEndDateAfterStartDate() {
            // Arrange
            long startTimestamp = Instant.now().plusSeconds(3600).toEpochMilli(); // 1 hour from now
            long endTimestamp = Instant.now().plusSeconds(7200).toEpochMilli(); // 2 hours from now
            context.put(Validator.ValidationResource.DATE_FROM, String.valueOf(startTimestamp));

            // Act
            Boolean result = validator.validate(String.valueOf(endTimestamp), context);

            // Assert
            assertTrue(result, "Should return true when end date is after start date");
        }

        @Test
        @DisplayName("Given end date before start date, When validating, Then should return false")
        void shouldReturnFalse_whenEndDateBeforeStartDate() {
            // Arrange
            long startTimestamp = Instant.now().plusSeconds(7200).toEpochMilli(); // 2 hours from now
            long endTimestamp = Instant.now().plusSeconds(3600).toEpochMilli(); // 1 hour from now
            context.put(Validator.ValidationResource.DATE_FROM, String.valueOf(startTimestamp));

            // Act
            Boolean result = validator.validate(String.valueOf(endTimestamp), context);

            // Assert
            assertFalse(result, "Should return false when end date is before start date");
        }

        @Test
        @DisplayName("Given end date equal to start date, When validating, Then should return false")
        void shouldReturnFalse_whenEndDateEqualsStartDate() {
            // Arrange
            long timestamp = Instant.now().plusSeconds(3600).toEpochMilli();
            context.put(Validator.ValidationResource.DATE_FROM, String.valueOf(timestamp));

            // Act
            Boolean result = validator.validate(String.valueOf(timestamp), context);

            // Assert
            assertFalse(result, "Should return false when end date equals start date");
        }

        @Test
        @DisplayName("Given zero timestamp, When validating, Then should return false")
        void shouldReturnFalse_whenZeroTimestamp() {
            // Act
            Boolean result = validator.validate("0", context);

            // Assert
            assertFalse(result, "Should return false for zero timestamp");
        }

        @Test
        @DisplayName("Given negative timestamp, When validating, Then should return false")
        void shouldReturnFalse_whenNegativeTimestamp() {
            // Act
            Boolean result = validator.validate("-1000", context);

            // Assert
            assertFalse(result, "Should return false for negative timestamp");
        }

        @Test
        @DisplayName("Given non-numeric string, When validating, Then should return false")
        void shouldReturnFalse_whenNonNumeric() {
            // Act
            Boolean result = validator.validate("not-a-timestamp", context);

            // Assert
            assertFalse(result, "Should return false for non-numeric string");
        }

        @Test
        @DisplayName("Given null timestamp, When validating, Then should return false")
        void shouldReturnFalse_whenNull() {
            // Act
            Boolean result = validator.validate(null, context);

            // Assert
            assertFalse(result, "Should return false for null timestamp");
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
        @DisplayName("Given valid timestamp without start date context, When validating, Then should return true")
        void shouldReturnTrue_whenNoStartDateContext() {
            // Arrange
            long validTimestamp = Instant.now().plusSeconds(3600).toEpochMilli();

            // Act
            Boolean result = validator.validate(String.valueOf(validTimestamp), context);

            // Assert
            assertTrue(result, "Should return true for valid timestamp without start date context");
        }
    }

    // ==================== Field Configuration Tests ====================

    @Nested
    @DisplayName("Field Configuration Tests")
    class FieldConfigurationTests {

        @Test
        @DisplayName("Given validator, When getting fetch field name, Then should return DATE_TO")
        void shouldReturnDateTo_whenGettingFetchFieldName() {
            // Act
            String result = validator.getFetchFieldName();

            // Assert
            assertEquals(CalendarResource.DATE_TO, result, "Fetch field name should be DATE_TO");
        }

        @Test
        @DisplayName("Given validator, When getting field type, Then should return DATE_FIELD")
        void shouldReturnDateField_whenGettingFieldType() {
            // Act
            String result = validator.getFieldType();

            // Assert
            assertEquals(FieldTypes.DATE_FIELD, result, "Field type should be DATE_FIELD");
        }

        @Test
        @DisplayName("Given validator, When getting fetch step message, Then should return appropriate message")
        void shouldReturnFetchMessage_whenGettingFetchStepMessage() {
            // Act
            String result = validator.getFetchStepMessage();

            // Assert
            assertNotNull(result, "Fetch step message should not be null");
            assertTrue(result.toLowerCase().contains("end"), "Message should mention end date");
        }
    }

    // ==================== Error Message Tests ====================

    @Nested
    @DisplayName("Error Message Tests")
    class ErrorMessageTests {

        @Test
        @DisplayName("Given null timestamp, When getting confirmation message, Then should return required message")
        void shouldReturnRequiredMessage_whenNull() {
            // Act
            String result = validator.getConfirmationStepMessage(null, context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.toLowerCase().contains("required"), "Message should indicate value is required");
        }

        @Test
        @DisplayName("Given end date before start date, When getting confirmation message, Then should return comparison error")
        void shouldReturnComparisonError_whenEndBeforeStart() {
            // Arrange
            long startTimestamp = Instant.now().plusSeconds(7200).toEpochMilli();
            long endTimestamp = Instant.now().plusSeconds(3600).toEpochMilli();
            context.put(Validator.ValidationResource.DATE_FROM, String.valueOf(startTimestamp));

            // Act
            String result = validator.getConfirmationStepMessage(String.valueOf(endTimestamp), context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.toLowerCase().contains("after"), "Message should indicate end must be after start");
        }

        @Test
        @DisplayName("Given null timestamp, When getting error message, Then should return required error")
        void shouldReturnRequiredError_whenGettingErrMessage() {
            // Act
            String result = validator.getErrMessage(null);

            // Assert
            assertNotNull(result, "Error message should not be null");
            assertTrue(result.toLowerCase().contains("required"), "Error should indicate value is required");
        }
    }
}

