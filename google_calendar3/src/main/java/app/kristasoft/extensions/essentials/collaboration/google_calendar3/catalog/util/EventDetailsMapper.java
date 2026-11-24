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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.util;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.entity.EventDetails;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.stream.Collectors;

/**
 * Utility class for mapping Google Calendar Event objects to EventDetails entities.
 * 
 * <p>This class provides static utility methods for converting Google Calendar API
 * Event objects into the application's EventDetails entity format. It handles
 * extraction of event properties including dates, attendees, and metadata.</p>
 * 
 * <h2>Key Responsibilities:</h2>
 * <ul>
 *   <li>Convert Google Calendar Event to EventDetails entity</li>
 *   <li>Extract and normalize date/time values from EventDateTime</li>
 *   <li>Process attendee lists and extract email addresses</li>
 *   <li>Handle null values and provide safe defaults</li>
 * </ul>
 * 
 * <h2>Design Pattern: Utility/Helper Pattern</h2>
 * <ul>
 *   <li><b>Static Methods:</b> All methods are static for easy access</li>
 *   <li><b>Stateless:</b> No instance state, purely functional transformations</li>
 *   <li><b>Single Responsibility:</b> Focused on Event to EventDetails mapping</li>
 * </ul>
 * 
 * @author Krista Software
 * @version 3.0.0
 * @since 3.0.0
 * @see EventDetails
 * @see Event
 */
public final class EventDetailsMapper {

    /** Logger instance for event mapping operations. */
    private static final Logger LOGGER = LoggerFactory.getLogger(EventDetailsMapper.class);

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private EventDetailsMapper() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Converts a Google Calendar Event object to an EventDetails entity.
     * 
     * <p>This method extracts all relevant information from the Google Calendar Event
     * and maps it to the application's EventDetails entity format. It handles:</p>
     * <ul>
     *   <li>Event ID, title, location, and description</li>
     *   <li>Start and end date/time extraction</li>
     *   <li>Attendee email list processing</li>
     *   <li>Null value handling with safe defaults</li>
     * </ul>
     * 
     * @param event the Google Calendar Event object to convert, must not be null
     * @return EventDetails entity populated with event data
     * @throws NullPointerException if event is null
     * 
     * @see #extractDateValue(EventDateTime)
     */
    public static EventDetails mapEventToEventDetails(Event event) {
        if (event == null) {
            throw new NullPointerException("Event cannot be null");
        }

        LOGGER.info("→ mapEventToEventDetails: Processing event {}", Constants.GSON.toJson(event));
        
        EventDetails details = new EventDetails();
        details.eventId = event.getId();
        details.dateFrom = extractDateValue(event.getStart());
        details.dateTo = extractDateValue(event.getEnd());
        details.details = event.getDescription();
        details.title = event.getSummary() != null ? event.getSummary() : "";
        details.location = event.getLocation();
        
        if (event.getAttendees() != null) {
            details.contacts = event.getAttendees().stream()
                    .map(EventAttendee::getEmail)
                    .filter(java.util.Objects::nonNull)  // Filter out null email addresses
                    .collect(Collectors.toList());
            LOGGER.info("→ Contacts: {} attendees found", details.contacts.size());
        } else {
            details.contacts = Collections.emptyList();
            LOGGER.debug("→ No attendees found for event {}", event.getId());
        }
        
        return details;
    }

    /**
     * Extracts the date/time value from a Google Calendar EventDateTime object.
     * 
     * <p>Google Calendar EventDateTime can contain either a DateTime (for events with
     * specific times) or a Date (for all-day events). This method handles both cases
     * and extracts the appropriate timestamp value.</p>
     * 
     * <h3>Extraction Logic:</h3>
     * <ol>
     *   <li>If EventDateTime is null → return null</li>
     *   <li>If EventDateTime.getDateTime() is not null → return DateTime.getValue()</li>
     *   <li>If EventDateTime.getDate() is not null → return Date.getValue()</li>
     *   <li>Otherwise → return null</li>
     * </ol>
     * 
     * <h3>Date Types:</h3>
     * <ul>
     *   <li><b>DateTime:</b> Used for events with specific start/end times (e.g., "2024-01-15T14:30:00Z")</li>
     *   <li><b>Date:</b> Used for all-day events (e.g., "2024-01-15")</li>
     * </ul>
     * 
     * @param eventDateTime the EventDateTime object to extract value from, can be null
     * @return the timestamp value in milliseconds since epoch, or null if no date is available
     * 
     * @see EventDateTime
     * @see com.google.api.client.util.DateTime
     */
    public static Long extractDateValue(EventDateTime eventDateTime) {
        if (eventDateTime == null) {
            LOGGER.debug("→ extractDateValue: EventDateTime is null");
            return null;
        }
        
        if (eventDateTime.getDateTime() != null) {
            Long value = eventDateTime.getDateTime().getValue();
            LOGGER.debug("→ extractDateValue: Extracted DateTime value: {}", value);
            return value;
        }
        
        if (eventDateTime.getDate() != null) {
            Long value = eventDateTime.getDate().getValue();
            LOGGER.debug("→ extractDateValue: Extracted Date value: {}", value);
            return value;
        }
        
        LOGGER.debug("→ extractDateValue: No DateTime or Date found");
        return null;
    }

}

