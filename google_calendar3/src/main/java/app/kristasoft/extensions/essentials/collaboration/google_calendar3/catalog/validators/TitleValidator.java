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
 * Validator for meeting title field.
 * Validates that the title meets the following requirements:
 * - Max length: 255 characters
 * - No newline characters allowed
 * - No control characters (< ASCII 32) allowed
 */
public class TitleValidator implements Validator {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(TitleValidator.class);
    
    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MIN_ASCII_PRINTABLE = 32; // Space character
    
    public TitleValidator() {
    }
    
    @Override
    public Boolean validate(String resourceId, Map<ValidationResource, String> context) {
        try {
            LOGGER.info("Validating title: {}", resourceId);
            
            if (resourceId == null || resourceId.trim().isEmpty()) {
                LOGGER.warn("Title is null or empty");
                return false;
            }
            
            // Check max length
            if (resourceId.length() > MAX_TITLE_LENGTH) {
                LOGGER.warn("Title exceeds maximum length of {} characters: {}", MAX_TITLE_LENGTH, resourceId.length());
                return false;
            }
            
            // Check for newline characters
            if (resourceId.contains("\n") || resourceId.contains("\r")) {
                LOGGER.warn("Title contains newline characters");
                return false;
            }
            
            // Check for control characters (< ASCII 32)
            for (char c : resourceId.toCharArray()) {
                if (c < MIN_ASCII_PRINTABLE) {
                    LOGGER.warn("Title contains control character with ASCII value: {}", (int) c);
                    return false;
                }
            }
            
            LOGGER.info("Title validation passed");
            return true;
            
        } catch (Exception cause) {
            LOGGER.error("Failed to validate title: {}", cause.getMessage(), cause);
            return false;
        }
    }
    
    @Override
    public String getFetchFieldName() {
        return CalendarResource.TITLE;
    }
    
    @Override
    public String getFieldType() {
        return FieldTypes.TEXT_FIELD;
    }
    
    @Override
    public String getFetchStepMessage() {
        return "Please enter a valid title (max 255 characters, no newlines or control characters).";
    }
    
    @Override
    public String getConfirmationStepMessage(String resourceId, Map<ValidationResource, String> context) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return "The title is required. Please provide a valid title.";
        } else if (resourceId.length() > MAX_TITLE_LENGTH) {
            return String.format("The provided title exceeds the maximum length of %d characters (%d characters provided). Please provide a shorter title.", 
                    MAX_TITLE_LENGTH, resourceId.length());
        } else if (resourceId.contains("\n") || resourceId.contains("\r")) {
            return "The provided title contains newline characters which are not allowed. Please provide a title without newlines.";
        } else {
            return "The provided title contains invalid control characters. Please provide a title with only printable characters.";
        }
    }
    
    @Override
    public String getErrMessage(String resourceId) {
        if (resourceId == null || resourceId.trim().isEmpty()) {
            return "Title is required.";
        } else if (resourceId.length() > MAX_TITLE_LENGTH) {
            return String.format("Title exceeds maximum length of %d characters.", MAX_TITLE_LENGTH);
        } else if (resourceId.contains("\n") || resourceId.contains("\r")) {
            return "Title contains newline characters.";
        } else {
            return "Title contains invalid control characters.";
        }
    }
}
