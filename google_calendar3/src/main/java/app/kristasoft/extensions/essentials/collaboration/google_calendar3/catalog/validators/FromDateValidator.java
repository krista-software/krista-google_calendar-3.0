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

/**
 * Validator for from date field.
 * Validates that the from date meets the following requirements:
 * - Not null
 * - Valid timestamp format
 * - Not in the far future (within 5 years)
 */
public class FromDateValidator implements Validator {

    private static final Logger LOGGER = LoggerFactory.getLogger(FromDateValidator.class);
    
    private static final long MAX_FUTURE_YEARS = 5;
    private static final long MAX_FUTURE_MILLIS = MAX_FUTURE_YEARS * 365 * 24 * 60 * 60 * 1000L;

    @Override
    public Boolean validate(String resourceId, Map<ValidationResource, String> context) {
        try {
            LOGGER.info("Validating from date: {}", resourceId);
            
            if (resourceId == null || resourceId.trim().isEmpty()) {
                LOGGER.warn("From date is null or empty");
                return false;
            }
            
            try {
                long timestamp = Long.parseLong(resourceId.trim());
                long currentTime = System.currentTimeMillis();
                
                // Check if date is not too far in the future
                if (timestamp > currentTime + MAX_FUTURE_MILLIS) {
                    LOGGER.warn("From date is too far in the future: {}", timestamp);
                    return false;
                }
                
                // Validate that it's a reasonable timestamp (after year 1970)
                if (timestamp < 0) {
                    LOGGER.warn("From date timestamp is negative: {}", timestamp);
                    return false;
                }
                
                LOGGER.info("From date validation passed");
                return true;
                
            } catch (NumberFormatException e) {
                LOGGER.warn("From date is not a valid timestamp: {}", resourceId);
                return false;
            }
            
        } catch (Exception cause) {
            LOGGER.error("Failed to validate from date: {}", cause.getMessage(), cause);
            return false;
        }
    }

    @Override
    public String getFetchFieldName() {
        return CalendarResource.FROM;
    }

    @Override
    public String getFieldType() {
        return FieldTypes.DATE_FIELD;
    }

    @Override
    public String getFetchStepMessage() {
        return "Please enter a valid from date.";
    }

    @Override
    public String getConfirmationStepMessage(String resourceId, Map<ValidationResource, String> context) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return "From date is required. Please provide a valid date.";
        } else {
            try {
                long timestamp = Long.parseLong(resourceId.trim());
                long currentTime = System.currentTimeMillis();
                
                if (timestamp > currentTime + MAX_FUTURE_MILLIS) {
                    return "The provided from date is too far in the future. Please select a date within the next 5 years.";
                } else if (timestamp < 0) {
                    return "The provided from date is invalid. Please select a valid date.";
                }
            } catch (NumberFormatException e) {
                return "The provided from date format is invalid. Please provide a valid date.";
            }
        }
        return "The provided from date is invalid. Please provide a valid date.";
    }

    @Override
    public String getErrMessage(String resourceId) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return "From date is required.";
        } else {
            try {
                long timestamp = Long.parseLong(resourceId.trim());
                long currentTime = System.currentTimeMillis();
                
                if (timestamp > currentTime + MAX_FUTURE_MILLIS) {
                    return "From date is too far in the future.";
                } else if (timestamp < 0) {
                    return "From date timestamp is invalid.";
                }
            } catch (NumberFormatException e) {
                return "From date is not a valid timestamp.";
            }
        }
        return "From date is invalid.";
    }
}
