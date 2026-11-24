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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.CalendarResource;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.FieldTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validator for attendee email lists in the context of adding attendees to events.
 * Validates that all emails in the list are valid email addresses and that the event exists.
 */
public class AttendeeEmailListValidator implements Validator {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(AttendeeEmailListValidator.class);

    private static final String EMAIL_PATTERN =
            "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@" +
            "(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$";
    private final Pattern pattern = Pattern.compile(EMAIL_PATTERN);

    public AttendeeEmailListValidator() {
    }

    @Override
    public Boolean validate(String resourceId, Map<ValidationResource, String> context) {
        try {
            LOGGER.info("Validating attendee email list: {}", resourceId);
            
            // Parse the list of emails from the resourceId (assuming comma-separated)
            if (resourceId == null || resourceId.trim().isEmpty()) {
                LOGGER.warn("Attendee email list is null or empty");
                return false;
            }
            
            // Split by comma and validate each email
            String[] emails = resourceId.split(",");
            if (emails.length == 0) {
                LOGGER.warn("No emails found in the attendee email list");
                return false;
            }
            
            // Validate each email in the list
            for (String email : emails) {
                String trimmedEmail = email.trim();
                if (!isValidEmail(trimmedEmail)) {
                    LOGGER.warn("Invalid email format: {}", trimmedEmail);
                    return false;
                }
            }
            LOGGER.info("All attendee emails validated successfully");
            return true;
            
        } catch (MustAuthorizeException cause) {
            LOGGER.error("Failed to validate attendee email list due to authorization error: {}", cause.getMessage(), cause);
            throw cause;
        } catch (Exception cause) {
            LOGGER.error("Failed to validate attendee email list: {}", cause.getMessage(), cause);
            return false;
        }
    }

    /**
     * Validates email format using regex pattern
     */
    private boolean isValidEmail(String email) {
        if (email == null) {
            return false;
        }
        Matcher matcher = pattern.matcher(email);
        return matcher.matches();
    }

    @Override
    public String getFetchFieldName() {
        return CalendarResource.ATTENDEE_EMAIL;
    }

    @Override
    public String getFieldType() {
        return FieldTypes.LIST_FIELD;
    }

    @Override
    public String getFetchStepMessage() {
        return "Please enter valid attendee email addresses (comma-separated).";
    }

    @Override
    public String getConfirmationStepMessage(String resourceId, Map<ValidationResource, String> context) {
        return String.format("The provided attendee email list: %s contains invalid emails or emails that are already attendees. Please provide valid attendee emails.", resourceId);
    }

    @Override
    public String getErrMessage(String resourceId) {
        return String.format("Invalid attendee email list: %s. Please ensure all emails are valid and not already attendees.", resourceId);
    }
}
