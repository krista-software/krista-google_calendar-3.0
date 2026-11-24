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
 * Validator for to date field.
 * Validates that the to date meets the following requirements:
 * - Not null
 * - Valid timestamp format
 * - Not in the far future (within 5 years)
 * - After from date if both are provided
 */
public class ToDateValidator implements Validator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToDateValidator.class);
    
    private static final long MAX_FUTURE_YEARS = 5;
    private static final long MAX_FUTURE_MILLIS = MAX_FUTURE_YEARS * 365 * 24 * 60 * 60 * 1000L;

    @Override
    public Boolean validate(String resourceId, Map<ValidationResource, String> context) {
        try {
            LOGGER.info("Validating to date: {}", resourceId);
            
            if (resourceId == null || resourceId.trim().isEmpty()) {
                LOGGER.warn("To date is null or empty");
                return false;
            }
            
            try {
                long timestamp = Long.parseLong(resourceId.trim());
                long currentTime = System.currentTimeMillis();
                
                // Check if date is not too far in the future
                if (timestamp > currentTime + MAX_FUTURE_MILLIS) {
                    LOGGER.warn("To date is too far in the future: {}", timestamp);
                    return false;
                }
                
                // Validate that it's a reasonable timestamp (after year 1970)
                if (timestamp < 0) {
                    LOGGER.warn("To date timestamp is negative: {}", timestamp);
                    return false;
                }
                
                // Check if to date is after from date
                String fromDateStr = context.get(ValidationResource.FROM_DATE);
                if (fromDateStr != null && !fromDateStr.trim().isEmpty()) {
                    try {
                        long fromTimestamp = Long.parseLong(fromDateStr.trim());
                        if (timestamp <= fromTimestamp) {
                            LOGGER.warn("To date {} is not after from date {}", timestamp, fromTimestamp);
                            return false;
                        }
                    } catch (NumberFormatException e) {
                        LOGGER.warn("From date is not a valid timestamp for comparison: {}", fromDateStr);
                        // Continue validation without from date comparison
                    }
                }
                
                LOGGER.info("To date validation passed");
                return true;
                
            } catch (NumberFormatException e) {
                LOGGER.warn("To date is not a valid timestamp: {}", resourceId);
                return false;
            }
            
        } catch (Exception cause) {
            LOGGER.error("Failed to validate to date: {}", cause.getMessage(), cause);
            return false;
        }
    }

    @Override
    public String getFetchFieldName() {
        return CalendarResource.TO;
    }

    @Override
    public String getFieldType() {
        return FieldTypes.DATE_FIELD;
    }

    @Override
    public String getFetchStepMessage() {
        return "Please enter a valid to date.";
    }

    @Override
    public String getConfirmationStepMessage(String resourceId, Map<ValidationResource, String> context) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return "To date is required. Please provide a valid date.";
        } else {
            try {
                long timestamp = Long.parseLong(resourceId.trim());
                long currentTime = System.currentTimeMillis();
                
                if (timestamp > currentTime + MAX_FUTURE_MILLIS) {
                    return "The provided to date is too far in the future. Please select a date within the next 5 years.";
                } else if (timestamp < 0) {
                    return "The provided to date is invalid. Please select a valid date.";
                }
                
                // Check if to date is after from date
                String fromDateStr = context.get(ValidationResource.FROM_DATE);
                if (fromDateStr != null && !fromDateStr.trim().isEmpty()) {
                    try {
                        long fromTimestamp = Long.parseLong(fromDateStr.trim());
                        if (timestamp <= fromTimestamp) {
                            return "The to date must be after the from date. Please select a later date.";
                        }
                    } catch (NumberFormatException e) {
                        // Continue without from date comparison
                    }
                }
                
            } catch (NumberFormatException e) {
                return "The provided to date format is invalid. Please provide a valid date.";
            }
        }
        return "The provided to date is invalid. Please provide a valid date.";
    }

    @Override
    public String getErrMessage(String resourceId) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return "To date is required.";
        } else {
            try {
                long timestamp = Long.parseLong(resourceId.trim());
                long currentTime = System.currentTimeMillis();
                
                if (timestamp > currentTime + MAX_FUTURE_MILLIS) {
                    return "To date is too far in the future.";
                } else if (timestamp < 0) {
                    return "To date timestamp is invalid.";
                }
            } catch (NumberFormatException e) {
                return "To date is not a valid timestamp.";
            }
        }
        return "To date is invalid.";
    }
}
