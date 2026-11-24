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
 * Comprehensive test suite for ToDateValidator.
 * 
 * <p>Tests to date validation including timestamp format, future date limits, and from date comparison.</p>
 * 
 * @author Krista Software
 * @version 1.0.0
 */
@DisplayName("ToDateValidator Validator Tests")
class ToDateValidatorTest {

    private ToDateValidator validator;
    private Map<Validator.ValidationResource, String> context;

    @BeforeEach
    void setUp() {
        validator = new ToDateValidator();
        context = new HashMap<>();
    }

    // ==================== Validation Tests ====================

    @Nested
    @DisplayName("validate() Method Tests")
    class ValidateTests {

        @Test
        @DisplayName("Given valid current timestamp, When validating, Then should return true")
        void shouldReturnTrue_whenValidCurrentTimestamp() {
            // Arrange
            long currentTimestamp = Instant.now().toEpochMilli();

            // Act
            Boolean result = validator.validate(String.valueOf(currentTimestamp), context);

            // Assert
            assertTrue(result, "Should return true for valid current timestamp");
        }

        @Test
        @DisplayName("Given valid to date after from date, When validating, Then should return true")
        void shouldReturnTrue_whenToDateAfterFromDate() {
            // Arrange
            long fromTimestamp = Instant.now().plusSeconds(3600).toEpochMilli(); // 1 hour from now
            long toTimestamp = Instant.now().plusSeconds(7200).toEpochMilli(); // 2 hours from now
            context.put(Validator.ValidationResource.FROM_DATE, String.valueOf(fromTimestamp));

            // Act
            Boolean result = validator.validate(String.valueOf(toTimestamp), context);

            // Assert
            assertTrue(result, "Should return true when to date is after from date");
        }

        @Test
        @DisplayName("Given to date before from date, When validating, Then should return false")
        void shouldReturnFalse_whenToDateBeforeFromDate() {
            // Arrange
            long fromTimestamp = Instant.now().plusSeconds(7200).toEpochMilli(); // 2 hours from now
            long toTimestamp = Instant.now().plusSeconds(3600).toEpochMilli(); // 1 hour from now
            context.put(Validator.ValidationResource.FROM_DATE, String.valueOf(fromTimestamp));

            // Act
            Boolean result = validator.validate(String.valueOf(toTimestamp), context);

            // Assert
            assertFalse(result, "Should return false when to date is before from date");
        }

        @Test
        @DisplayName("Given to date equal to from date, When validating, Then should return false")
        void shouldReturnFalse_whenToDateEqualsFromDate() {
            // Arrange
            long timestamp = Instant.now().plusSeconds(3600).toEpochMilli();
            context.put(Validator.ValidationResource.FROM_DATE, String.valueOf(timestamp));

            // Act
            Boolean result = validator.validate(String.valueOf(timestamp), context);

            // Assert
            assertFalse(result, "Should return false when to date equals from date");
        }

        @Test
        @DisplayName("Given timestamp too far in future (>5 years), When validating, Then should return false")
        void shouldReturnFalse_whenTooFarInFuture() {
            // Arrange
            long farFutureTimestamp = Instant.now().plusSeconds(6L * 365 * 24 * 60 * 60).toEpochMilli(); // 6 years from now

            // Act
            Boolean result = validator.validate(String.valueOf(farFutureTimestamp), context);

            // Assert
            assertFalse(result, "Should return false for timestamp more than 5 years in future");
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
        @DisplayName("Given valid timestamp without from date context, When validating, Then should return true")
        void shouldReturnTrue_whenNoFromDateContext() {
            // Arrange
            long validTimestamp = Instant.now().plusSeconds(3600).toEpochMilli();

            // Act
            Boolean result = validator.validate(String.valueOf(validTimestamp), context);

            // Assert
            assertTrue(result, "Should return true for valid timestamp without from date context");
        }
    }

    // ==================== Field Configuration Tests ====================

    @Nested
    @DisplayName("Field Configuration Tests")
    class FieldConfigurationTests {

        @Test
        @DisplayName("Given validator, When getting fetch field name, Then should return TO")
        void shouldReturnTo_whenGettingFetchFieldName() {
            // Act
            String result = validator.getFetchFieldName();

            // Assert
            assertEquals(CalendarResource.TO, result, "Fetch field name should be TO");
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
            assertTrue(result.toLowerCase().contains("to"), "Message should mention to date");
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
        @DisplayName("Given to date before from date, When getting confirmation message, Then should return comparison error")
        void shouldReturnComparisonError_whenToBeforeFrom() {
            // Arrange
            long fromTimestamp = Instant.now().plusSeconds(7200).toEpochMilli();
            long toTimestamp = Instant.now().plusSeconds(3600).toEpochMilli();
            context.put(Validator.ValidationResource.FROM_DATE, String.valueOf(fromTimestamp));

            // Act
            String result = validator.getConfirmationStepMessage(String.valueOf(toTimestamp), context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.toLowerCase().contains("after"), "Message should indicate to must be after from");
        }

        @Test
        @DisplayName("Given timestamp too far in future, When getting confirmation message, Then should return future limit error")
        void shouldReturnFutureLimitError_whenTooFarInFuture() {
            // Arrange
            long farFutureTimestamp = Instant.now().plusSeconds(6L * 365 * 24 * 60 * 60).toEpochMilli();

            // Act
            String result = validator.getConfirmationStepMessage(String.valueOf(farFutureTimestamp), context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.contains("5 years"), "Message should mention 5 year limit");
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

        @Test
        @DisplayName("Given timestamp too far in future, When getting error message, Then should return future error")
        void shouldReturnFutureError_whenGettingErrMessage() {
            // Arrange
            long farFutureTimestamp = Instant.now().plusSeconds(6L * 365 * 24 * 60 * 60).toEpochMilli();

            // Act
            String result = validator.getErrMessage(String.valueOf(farFutureTimestamp));

            // Assert
            assertNotNull(result, "Error message should not be null");
            assertTrue(result.toLowerCase().contains("future"), "Error should mention future");
        }
    }
}

