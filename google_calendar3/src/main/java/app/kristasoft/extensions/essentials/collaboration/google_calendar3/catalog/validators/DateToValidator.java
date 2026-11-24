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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Validator for "Date To" field in meeting scheduling.
 * Validates that the end date is valid and after the start date.
 */
public class DateToValidator implements Validator {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(DateToValidator.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    @Override
    public Boolean validate(String resourceId, Map<ValidationResource, String> context) {
        try {
            LOGGER.info("Validating Date To: {}", resourceId);
            
            if (resourceId == null || resourceId.trim().isEmpty()) {
                LOGGER.warn("Date To is null or empty");
                return false;
            }
            
            Long dateToTimestamp;
            try {
                dateToTimestamp = Long.parseLong(resourceId.trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("Date To is not a valid timestamp: {}", resourceId);
                return false;
            }
            
            if (dateToTimestamp <= 0) {
                LOGGER.warn("Date To timestamp is invalid: {}", dateToTimestamp);
                return false;
            }
            
            LocalDateTime dateToDateTime;
            try {
                dateToDateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(dateToTimestamp), 
                    ZoneId.systemDefault()
                );
            } catch (Exception e) {
                LOGGER.warn("Failed to convert Date To timestamp: {}", dateToTimestamp);
                return false;
            }
            
            // Check if Date From is provided in context for comparison
            String dateFromStr = context.get(ValidationResource.DATE_FROM);
            if (dateFromStr != null && !dateFromStr.trim().isEmpty()) {
                try {
                    Long dateFromTimestamp = Long.parseLong(dateFromStr.trim());
                    LocalDateTime dateFromDateTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(dateFromTimestamp), 
                        ZoneId.systemDefault()
                    );
                    
                    if (dateToDateTime.isBefore(dateFromDateTime) || dateToDateTime.isEqual(dateFromDateTime)) {
                        LOGGER.warn("Date To ({}) is not after Date From ({})", 
                            dateToDateTime.format(FORMATTER), dateFromDateTime.format(FORMATTER));
                        return false;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to parse Date From for comparison: {}", dateFromStr);
                }
            }
            
            LOGGER.info("Date To validation passed: {}", dateToDateTime.format(FORMATTER));
            return true;
            
        } catch (Exception cause) {
            LOGGER.error("Failed to validate Date To: {}", cause.getMessage(), cause);
            return false;
        }
    }
    
    @Override
    public String getFetchFieldName() {
        return CalendarResource.DATE_TO;
    }
    
    @Override
    public String getFieldType() {
        return FieldTypes.DATE_FIELD;
    }
    
    @Override
    public String getFetchStepMessage() {
        return "Please enter a valid end date and time for the meeting.";
    }
    
    @Override
    public String getConfirmationStepMessage(String resourceId, Map<ValidationResource, String> context) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return "The end date and time is required. Please provide a valid end date and time.";
        }
        
        String dateFromStr = context.get(ValidationResource.DATE_FROM);
        if (dateFromStr != null && !dateFromStr.trim().isEmpty()) {
            return "The end date must be after the start date. Please provide a valid end date.";
        }
        
        return "The provided end date is invalid. Please provide a valid end date.";
    }
    
    @Override
    public String getErrMessage(String resourceId) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return "End date is required.";
        }
        return "End date must be after start date.";
    }
}
