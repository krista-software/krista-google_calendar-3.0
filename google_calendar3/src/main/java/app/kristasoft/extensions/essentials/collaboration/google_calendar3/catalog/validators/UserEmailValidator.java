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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validator for user email field.
 * Validates that the email meets the following requirements:
 * - Not null or empty
 * - Valid email format
 * - Max length: 254 characters (RFC 5321)
 */
public class UserEmailValidator implements Validator {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserEmailValidator.class);
    
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
    );

    @Override
    public Boolean validate(String resourceId, Map<ValidationResource, String> context) {
        try {
            LOGGER.info("Validating user email: {}", resourceId);
            
            if (resourceId == null || resourceId.trim().isEmpty()) {
                LOGGER.warn("User email is null or empty");
                return false;
            }
            
            String email = resourceId.trim();
            
            // Check max length
            if (email.length() > MAX_EMAIL_LENGTH) {
                LOGGER.warn("User email exceeds maximum length of {} characters: {}", MAX_EMAIL_LENGTH, email.length());
                return false;
            }
            
            // Check email format
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                LOGGER.warn("User email has invalid format: {}", email);
                return false;
            }
            
            LOGGER.info("User email validation passed");
            return true;
            
        } catch (Exception cause) {
            LOGGER.error("Failed to validate user email: {}", cause.getMessage(), cause);
            return false;
        }
    }

    @Override
    public String getFetchFieldName() {
        return CalendarResource.USER_EMAIL;
    }

    @Override
    public String getFieldType() {
        return FieldTypes.TEXT_FIELD;
    }

    @Override
    public String getFetchStepMessage() {
        return "Please enter a valid user email address (max 254 characters).";
    }

    @Override
    public String getConfirmationStepMessage(String resourceId, Map<ValidationResource, String> context) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return "User email is required. Please provide a valid email address.";
        } else if (resourceId.length() > MAX_EMAIL_LENGTH) {
            return String.format("The provided user email exceeds the maximum length of %d characters. Please provide a shorter email address.", MAX_EMAIL_LENGTH);
        } else {
            return "The provided user email format is invalid. Please provide a valid email address.";
        }
    }

    @Override
    public String getErrMessage(String resourceId) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return "User email is required.";
        } else if (resourceId.length() > MAX_EMAIL_LENGTH) {
            return String.format("User email exceeds maximum length of %d characters.", MAX_EMAIL_LENGTH);
        } else {
            return "User email has invalid format.";
        }
    }
}
