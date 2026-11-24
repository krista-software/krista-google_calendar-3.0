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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validator for Attendee Email field.
 * Validates that the email format is valid and the attendee exists in the specified event.
 */
public class AttendeeEmailValidator implements Validator {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttendeeEmailValidator.class);

    private static final String EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$"; // Simple regex, covers most cases
    private static final Pattern pattern = Pattern.compile(EMAIL_REGEX);

    private final CalendarService calendarService;

    public AttendeeEmailValidator(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @Override
    public Boolean validate(String resourceId, Map<ValidationResource, String> context) {
        try {
            LOGGER.debug("Validating attendee email: {}", resourceId);

            if (!isValidEmail(resourceId)) {
                LOGGER.warn("Invalid email format: {}", resourceId);
                return false;
            }

            String eventId = context.get(ValidationResource.EVENT_ID);
            EventDetails event = calendarService.getEvent(eventId);

            if (event == null || event.contacts == null) {
                LOGGER.warn("Event not found or has no contacts: {}", eventId);
                return false;
            }

            for (String contact : event.contacts) {
                if (contact.equals(resourceId)) {
                    LOGGER.debug("Attendee email found in event contacts");
                    return true;
                }
            }

            LOGGER.warn("Attendee email not found in event contacts: {}", resourceId);
            return false;
        } catch (MustAuthorizeException cause) {
            LOGGER.error("Failed to validate attendee email due to authorization error: {}", cause.getMessage(), cause);
            throw cause;
        } catch (Exception cause) {
            LOGGER.error("Failed to validate attendee email: {}", cause.getMessage(), cause);
            return false;
        }
    }

    public boolean isValidEmail(String email) {
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
        return FieldTypes.TEXT_FIELD;
    }

    @Override
    public String getFetchStepMessage() {
        return "Please enter a valid Attendee Email.";
    }

    @Override
    public String getConfirmationStepMessage(String resourceId, Map<ValidationResource, String> context) {
        return String.format("The provided Attendee Email: %s is invalid. Please provide a valid Attendee Email.", resourceId);
    }

    @Override
    public String getErrMessage(String resourceId) {
        return String.format("Invalid Attendee Email: %s.", resourceId);
    }
}
