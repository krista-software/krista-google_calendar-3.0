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
 * Validator for number of meetings field.
 * Validates that the number meets the following requirements:
 * - Not null or empty
 * - Valid numeric format
 * - Positive number (> 0)
 * - Within reasonable bounds (1-100)
 */
public class NumberOfMeetingsValidator implements Validator {

    private static final Logger LOGGER = LoggerFactory.getLogger(NumberOfMeetingsValidator.class);
    
    private static final int MIN_MEETINGS = 1;
    private static final int MAX_MEETINGS = 100;
    
    @Override
    public Boolean validate(String resourceId, Map<ValidationResource, String> context) {
        try {
            LOGGER.info("Validating number of meetings: {}", resourceId);
            
            if (resourceId == null || resourceId.trim().isEmpty()) {
                LOGGER.warn("Number of meetings is null or empty");
                return false;
            }
            
            Double numberOfMeetings;
            try {
                numberOfMeetings = Double.parseDouble(resourceId.trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("Number of meetings is not a valid number: {}", resourceId);
                return false;
            }
            
            // Check if it's a positive number
            if (numberOfMeetings <= 0) {
                LOGGER.warn("Number of meetings must be positive: {}", numberOfMeetings);
                return false;
            }
            
            // Check if it's within reasonable bounds
            if (numberOfMeetings < MIN_MEETINGS || numberOfMeetings > MAX_MEETINGS) {
                LOGGER.warn("Number of meetings is out of bounds ({}): {}", MIN_MEETINGS + "-" + MAX_MEETINGS, numberOfMeetings);
                return false;
            }
            
            // Check if it's a whole number (no decimals)
            if (numberOfMeetings % 1 != 0) {
                LOGGER.warn("Number of meetings must be a whole number: {}", numberOfMeetings);
                return false;
            }
            
            LOGGER.info("Number of meetings validation passed: {}", numberOfMeetings.intValue());
            return true;
            
        } catch (Exception cause) {
            LOGGER.error("Failed to validate number of meetings: {}", cause.getMessage(), cause);
            return false;
        }
    }
    
    @Override
    public String getFetchFieldName() {
        return CalendarResource.NUMBER_OF_MEETINGS;
    }
    
    @Override
    public String getFieldType() {
        return FieldTypes.NUMBER_FIELD;
    }
    
    @Override
    public String getFetchStepMessage() {
        return String.format("Please enter a valid number of meetings (between %d and %d).", MIN_MEETINGS, MAX_MEETINGS);
    }
    
    @Override
    public String getConfirmationStepMessage(String resourceId, Map<ValidationResource, String> context) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return "The number of meetings is required. Please provide a valid number.";
        }
        
        try {
            Double numberOfMeetings = Double.parseDouble(resourceId.trim());
            if (numberOfMeetings <= 0) {
                return "The number of meetings must be a positive number. Please provide a valid number.";
            } else if (numberOfMeetings < MIN_MEETINGS || numberOfMeetings > MAX_MEETINGS) {
                return String.format("The number of meetings must be between %d and %d. Please provide a valid number.", MIN_MEETINGS, MAX_MEETINGS);
            } else if (numberOfMeetings % 1 != 0) {
                return "The number of meetings must be a whole number. Please provide a valid number.";
            }
        } catch (NumberFormatException e) {
            return String.format("The provided number of meetings (%s) is not a valid number. Please provide a valid number.", resourceId);
        }
        
        return "The provided number of meetings is invalid. Please provide a valid number.";
    }
    
    @Override
    public String getErrMessage(String resourceId) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return "Number of meetings is required.";
        }
        
        try {
            Double numberOfMeetings = Double.parseDouble(resourceId.trim());
            if (numberOfMeetings <= 0) {
                return "Number of meetings must be positive.";
            } else if (numberOfMeetings < MIN_MEETINGS || numberOfMeetings > MAX_MEETINGS) {
                return String.format("Number of meetings must be between %d and %d.", MIN_MEETINGS, MAX_MEETINGS);
            } else if (numberOfMeetings % 1 != 0) {
                return "Number of meetings must be a whole number.";
            }
        } catch (NumberFormatException e) {
            return String.format("Invalid number format: %s.", resourceId);
        }
        
        return "Invalid number of meetings.";
    }
}
