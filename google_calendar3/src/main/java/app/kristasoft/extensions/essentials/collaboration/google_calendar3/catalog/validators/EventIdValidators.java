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

/**
 * Validator for Event ID field.
 * Validates that the event ID exists and is accessible in Google Calendar.
 */
public class EventIdValidators implements Validator {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventIdValidators.class);
    private final CalendarService calendarService;

    public EventIdValidators(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @Override
    public Boolean validate(String resourceId, Map<ValidationResource, String> context) {
        try {
            LOGGER.debug("Validating event ID: {}", resourceId);
            EventDetails event = calendarService.getEvent(resourceId);
            return event != null;
        } catch (MustAuthorizeException cause) {
            LOGGER.error("Failed to validate event id due to authorization error: {}", cause.getMessage(), cause);
            throw cause;
        } catch (Exception cause) {
            LOGGER.error("Failed to validate event id: {}", cause.getMessage(), cause);
            return false;
        }
    }

    @Override
    public String getFetchFieldName() {
        return CalendarResource.EVENT_ID;
    }

    @Override
    public String getFieldType() {
        return FieldTypes.TEXT_FIELD;
    }

    @Override
    public String getFetchStepMessage() {
        return "Please enter a valid Event Id.";
    }

    @Override
    public String getConfirmationStepMessage(String resourceId, Map<ValidationResource, String> context) {
        return String.format("The provided Event Id: %s is invalid. Please provide a valid Event Id.", resourceId);
    }

    @Override
    public String getErrMessage(String resourceId) {
        return String.format("Invalid Event Id: %s.", resourceId);
    }
}
