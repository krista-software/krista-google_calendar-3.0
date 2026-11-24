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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.mapper;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.entity.EventDetails;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Mapper class for transforming between Google Calendar Event objects and EventDetails DTOs.
 * 
 * Design Pattern: Data Transfer Object (DTO) Pattern + Mapper Pattern
 * - Separates external API models from internal domain models
 * - Provides centralized transformation logic
 * - Makes it easier to handle API changes
 * - Enables consistent data mapping across the application
 */
public class EventMapper {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(EventMapper.class);
    
    /**
     * Convert Google Calendar Event to EventDetails DTO
     * 
     * @param event Google Calendar Event object
     * @return EventDetails DTO
     */
    public static EventDetails toEventDetails(Event event) {
        if (event == null) {
            LOGGER.warn("Attempted to map null Event to EventDetails");
            return null;
        }
        
        LOGGER.debug("Mapping Event to EventDetails - eventId: {}, summary: {}", 
                    event.getId(), event.getSummary());
        
        try {
            EventDetails eventDetails = new EventDetails();
            eventDetails.eventId = event.getId();
            eventDetails.title = event.getSummary();
            eventDetails.location = event.getLocation();
            eventDetails.details = event.getDescription();
            
            // Map start date/time
            if (event.getStart() != null) {
                eventDetails.dateFrom = extractDateValue(event.getStart());
            }
            
            // Map end date/time
            if (event.getEnd() != null) {
                eventDetails.dateTo = extractDateValue(event.getEnd());
            }
            
            // Map attendees
            if (event.getAttendees() != null && !event.getAttendees().isEmpty()) {
                eventDetails.contacts = event.getAttendees().stream()
                        .map(EventAttendee::getEmail)
                        .filter(java.util.Objects::nonNull)  // Filter out null email addresses
                        .collect(Collectors.toList());
            }
            
            LOGGER.debug("Successfully mapped Event to EventDetails - eventId: {}", event.getId());
            return eventDetails;
            
        } catch (Exception e) {
            LOGGER.error("Error mapping Event to EventDetails - eventId: {}, error: {}", 
                        event.getId(), e.getMessage(), e);
            throw new IllegalStateException("Failed to map Event to EventDetails", e);
        }
    }
    
    /**
     * Convert list of Google Calendar Events to list of EventDetails DTOs
     * 
     * @param events List of Google Calendar Event objects
     * @return List of EventDetails DTOs
     */
    public static List<EventDetails> toEventDetailsList(List<Event> events) {
        if (events == null || events.isEmpty()) {
            LOGGER.debug("No events to map");
            return List.of();
        }
        
        LOGGER.debug("Mapping {} events to EventDetails list", events.size());
        return events.stream()
                .map(EventMapper::toEventDetails)
                .collect(Collectors.toList());
    }
    
    /**
     * Create Google Calendar Event from EventDetails DTO
     * 
     * @param title Event title
     * @param location Event location
     * @param dateFrom Start date/time in milliseconds
     * @param dateTo End date/time in milliseconds
     * @param details Event description
     * @param attendeeEmails List of attendee email addresses
     * @return Google Calendar Event object
     */
    public static Event toGoogleEvent(String title, String location, Long dateFrom, 
                                     Long dateTo, String details, List<String> attendeeEmails) {
        LOGGER.debug("Creating Google Event - title: {}, dateFrom: {}, dateTo: {}", 
                    title, dateFrom, dateTo);
        
        Event event = new Event();
        event.setSummary(title);
        event.setLocation(location);
        event.setDescription(details);
        
        // Set start time
        if (dateFrom != null) {
            EventDateTime start = new EventDateTime()
                    .setDateTime(new com.google.api.client.util.DateTime(dateFrom));
            event.setStart(start);
        }
        
        // Set end time
        if (dateTo != null) {
            EventDateTime end = new EventDateTime()
                    .setDateTime(new com.google.api.client.util.DateTime(dateTo));
            event.setEnd(end);
        }
        
        // Set attendees
        if (attendeeEmails != null && !attendeeEmails.isEmpty()) {
            List<EventAttendee> attendees = attendeeEmails.stream()
                    .map(email -> new EventAttendee().setEmail(email))
                    .collect(Collectors.toList());
            event.setAttendees(attendees);
        }
        
        LOGGER.debug("Successfully created Google Event");
        return event;
    }
    
    /**
     * Extract date value from EventDateTime, handling both timed and all-day events
     * 
     * @param eventDateTime EventDateTime object from Google Calendar API
     * @return Date value in milliseconds, or null if not available
     */
    private static Long extractDateValue(EventDateTime eventDateTime) {
        if (eventDateTime == null) {
            return null;
        }
        
        // Try to get date-time first (for timed events)
        if (eventDateTime.getDateTime() != null) {
            return eventDateTime.getDateTime().getValue();
        }
        
        // Fall back to date (for all-day events)
        if (eventDateTime.getDate() != null) {
            return eventDateTime.getDate().getValue();
        }
        
        return null;
    }
    
    /**
     * Create list of EventAttendee objects from email addresses
     * 
     * @param emails List of email addresses
     * @return List of EventAttendee objects
     */
    public static List<EventAttendee> toEventAttendees(List<String> emails) {
        if (emails == null || emails.isEmpty()) {
            return List.of();
        }
        
        return emails.stream()
                .map(email -> new EventAttendee().setEmail(email))
                .collect(Collectors.toList());
    }
}

