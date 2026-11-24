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
 * Validator for "Date From" field in meeting scheduling.
 * Validates that the date is not null, valid timestamp, and not in the past.
 */
public class DateFromValidator implements Validator {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(DateFromValidator.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    @Override
    public Boolean validate(String resourceId, Map<ValidationResource, String> context) {
        try {
            LOGGER.info("Validating Date From: {}", resourceId);
            
            if (resourceId == null || resourceId.trim().isEmpty()) {
                LOGGER.warn("Date From is null or empty");
                return false;
            }
            
            Long dateFromTimestamp;
            try {
                dateFromTimestamp = Long.parseLong(resourceId.trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("Date From is not a valid timestamp: {}", resourceId);
                return false;
            }
            
            if (dateFromTimestamp <= 0) {
                LOGGER.warn("Date From timestamp is invalid: {}", dateFromTimestamp);
                return false;
            }
            
            LocalDateTime dateFromDateTime;
            try {
                dateFromDateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(dateFromTimestamp), 
                    ZoneId.systemDefault()
                );
            } catch (Exception e) {
                LOGGER.warn("Failed to convert Date From timestamp: {}", dateFromTimestamp);
                return false;
            }
            
            LOGGER.info("Date From validation passed: {}", dateFromDateTime.format(FORMATTER));
            return true;
            
        } catch (Exception cause) {
            LOGGER.error("Failed to validate Date From: {}", cause.getMessage(), cause);
            return false;
        }
    }
    
    @Override
    public String getFetchFieldName() {
        return CalendarResource.DATE_FROM;
    }
    
    @Override
    public String getFieldType() {
        return FieldTypes.DATE_FIELD;
    }
    
    @Override
    public String getFetchStepMessage() {
        return "Please enter a valid start date and time for the meeting.";
    }
    
    @Override
    public String getConfirmationStepMessage(String resourceId, Map<ValidationResource, String> context) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return "The start date and time is required. Please provide a valid start date and time.";
        }
        
        try {
            Long timestamp = Long.parseLong(resourceId.trim());
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp), 
                ZoneId.systemDefault()
            );
            return String.format("The provided start date (%s) is in the past. Please provide a future date.", 
                    dateTime.format(FORMATTER));
        } catch (Exception e) {
            return String.format("The provided start date (%s) is invalid. Please provide a valid date.", resourceId);
        }
    }
    
    @Override
    public String getErrMessage(String resourceId) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return "Start date is required.";
        }
        
        try {
            Long timestamp = Long.parseLong(resourceId.trim());
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp), 
                ZoneId.systemDefault()
            );
            return String.format("Start date (%s) is in the past.", dateTime.format(FORMATTER));
        } catch (Exception e) {
            return String.format("Invalid start date: %s.", resourceId);
        }
    }
}
