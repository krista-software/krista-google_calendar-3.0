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
 * Comprehensive test suite for TitleValidator.
 * 
 * <p>Tests title validation including length, newlines, and control characters.</p>
 * 
 * @author Krista Software
 * @version 1.0.0
 */
@DisplayName("TitleValidator Validator Tests")
class TitleValidatorTest {

    private TitleValidator validator;
    private Map<Validator.ValidationResource, String> context;

    @BeforeEach
    void setUp() {
        validator = new TitleValidator();
        context = new HashMap<>();
    }

    // ==================== Validation Tests ====================

    @Nested
    @DisplayName("validate() Method Tests")
    class ValidateTests {

        @Test
        @DisplayName("Given valid title, When validating, Then should return true")
        void shouldReturnTrue_whenValidTitle() {
            // Arrange
            String validTitle = "Team Meeting";

            // Act
            Boolean result = validator.validate(validTitle, context);

            // Assert
            assertTrue(result, "Should return true for valid title");
        }

        @Test
        @DisplayName("Given title with max length, When validating, Then should return true")
        void shouldReturnTrue_whenTitleAtMaxLength() {
            // Arrange
            String maxLengthTitle = "A".repeat(255);

            // Act
            Boolean result = validator.validate(maxLengthTitle, context);

            // Assert
            assertTrue(result, "Should return true for title at max length (255)");
        }

        @Test
        @DisplayName("Given title exceeding max length, When validating, Then should return false")
        void shouldReturnFalse_whenTitleExceedsMaxLength() {
            // Arrange
            String tooLongTitle = "A".repeat(256);

            // Act
            Boolean result = validator.validate(tooLongTitle, context);

            // Assert
            assertFalse(result, "Should return false for title exceeding 255 characters");
        }

        @Test
        @DisplayName("Given null title, When validating, Then should return false")
        void shouldReturnFalse_whenTitleIsNull() {
            // Act
            Boolean result = validator.validate(null, context);

            // Assert
            assertFalse(result, "Should return false for null title");
        }

        @Test
        @DisplayName("Given empty title, When validating, Then should return false")
        void shouldReturnFalse_whenTitleIsEmpty() {
            // Act
            Boolean result = validator.validate("", context);

            // Assert
            assertFalse(result, "Should return false for empty title");
        }

        @Test
        @DisplayName("Given whitespace-only title, When validating, Then should return false")
        void shouldReturnFalse_whenTitleIsWhitespaceOnly() {
            // Act
            Boolean result = validator.validate("   ", context);

            // Assert
            assertFalse(result, "Should return false for whitespace-only title");
        }

        @Test
        @DisplayName("Given title with newline character, When validating, Then should return false")
        void shouldReturnFalse_whenTitleContainsNewline() {
            // Arrange
            String titleWithNewline = "Team\nMeeting";

            // Act
            Boolean result = validator.validate(titleWithNewline, context);

            // Assert
            assertFalse(result, "Should return false for title with newline");
        }

        @Test
        @DisplayName("Given title with carriage return, When validating, Then should return false")
        void shouldReturnFalse_whenTitleContainsCarriageReturn() {
            // Arrange
            String titleWithCR = "Team\rMeeting";

            // Act
            Boolean result = validator.validate(titleWithCR, context);

            // Assert
            assertFalse(result, "Should return false for title with carriage return");
        }

        @Test
        @DisplayName("Given title with control character, When validating, Then should return false")
        void shouldReturnFalse_whenTitleContainsControlCharacter() {
            // Arrange
            String titleWithControl = "Team\u0001Meeting"; // ASCII 1 (control character)

            // Act
            Boolean result = validator.validate(titleWithControl, context);

            // Assert
            assertFalse(result, "Should return false for title with control character");
        }

        @Test
        @DisplayName("Given title with special characters, When validating, Then should return true")
        void shouldReturnTrue_whenTitleContainsSpecialCharacters() {
            // Arrange
            String titleWithSpecial = "Team Meeting: Q1 2024 (Planning) - $100K Budget!";

            // Act
            Boolean result = validator.validate(titleWithSpecial, context);

            // Assert
            assertTrue(result, "Should return true for title with special printable characters");
        }

        @Test
        @DisplayName("Given title with unicode characters, When validating, Then should return true")
        void shouldReturnTrue_whenTitleContainsUnicode() {
            // Arrange
            String titleWithUnicode = "Team Meeting 会议 🎉";

            // Act
            Boolean result = validator.validate(titleWithUnicode, context);

            // Assert
            assertTrue(result, "Should return true for title with unicode characters");
        }
    }

    // ==================== Field Configuration Tests ====================

    @Nested
    @DisplayName("Field Configuration Tests")
    class FieldConfigurationTests {

        @Test
        @DisplayName("Given validator, When getting fetch field name, Then should return TITLE")
        void shouldReturnTitle_whenGettingFetchFieldName() {
            // Act
            String result = validator.getFetchFieldName();

            // Assert
            assertEquals(CalendarResource.TITLE, result, "Fetch field name should be TITLE");
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
            assertTrue(result.contains("255"), "Message should mention max length");
        }
    }

    // ==================== Error Message Tests ====================

    @Nested
    @DisplayName("Error Message Tests")
    class ErrorMessageTests {

        @Test
        @DisplayName("Given null title, When getting confirmation message, Then should return required message")
        void shouldReturnRequiredMessage_whenTitleIsNull() {
            // Act
            String result = validator.getConfirmationStepMessage(null, context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.toLowerCase().contains("required"), "Message should indicate title is required");
        }

        @Test
        @DisplayName("Given too long title, When getting confirmation message, Then should return length error")
        void shouldReturnLengthError_whenTitleTooLong() {
            // Arrange
            String tooLongTitle = "A".repeat(300);

            // Act
            String result = validator.getConfirmationStepMessage(tooLongTitle, context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.contains("255"), "Message should mention max length");
            assertTrue(result.contains("300"), "Message should mention actual length");
        }

        @Test
        @DisplayName("Given title with newline, When getting confirmation message, Then should return newline error")
        void shouldReturnNewlineError_whenTitleHasNewline() {
            // Arrange
            String titleWithNewline = "Team\nMeeting";

            // Act
            String result = validator.getConfirmationStepMessage(titleWithNewline, context);

            // Assert
            assertNotNull(result, "Confirmation message should not be null");
            assertTrue(result.toLowerCase().contains("newline"), "Message should mention newline");
        }

        @Test
        @DisplayName("Given null title, When getting error message, Then should return required error")
        void shouldReturnRequiredError_whenGettingErrMessage() {
            // Act
            String result = validator.getErrMessage(null);

            // Assert
            assertNotNull(result, "Error message should not be null");
            assertTrue(result.toLowerCase().contains("required"), "Error should indicate title is required");
        }

        @Test
        @DisplayName("Given too long title, When getting error message, Then should return length error")
        void shouldReturnLengthError_whenGettingErrMessage() {
            // Arrange
            String tooLongTitle = "A".repeat(300);

            // Act
            String result = validator.getErrMessage(tooLongTitle);

            // Assert
            assertNotNull(result, "Error message should not be null");
            assertTrue(result.contains("255"), "Error should mention max length");
        }
    }
}

