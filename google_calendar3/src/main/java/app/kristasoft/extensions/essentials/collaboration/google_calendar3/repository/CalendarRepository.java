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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.repository;

import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;
import org.jvnet.hk2.annotations.Contract;

import java.io.IOException;

/**
 * Repository interface for Calendar data access operations.
 * This layer handles all direct interactions with the Google Calendar API.
 *
 * Design Pattern: Repository/DAO Pattern
 * - Abstracts data access logic from business logic
 * - Provides a clean API for CRUD operations
 * - Makes it easier to mock for testing
 * - Centralizes API-specific error handling
 *
 * Note: Interfaces should use @Contract, not @Service. Only implementations use @Service.
 */
@Contract
public interface CalendarRepository {
    
    /**
     * Retrieve an event by its ID
     * 
     * @param calendarId The calendar ID (typically "primary")
     * @param eventId The unique identifier of the event
     * @return Event object from Google Calendar API
     * @throws IOException if API call fails
     */
    Event getEvent(String calendarId, String eventId) throws IOException;
    
    /**
     * Create a new event
     * 
     * @param calendarId The calendar ID (typically "primary")
     * @param event The event to create
     * @return Created Event object with ID assigned
     * @throws IOException if API call fails
     */
    Event createEvent(String calendarId, Event event) throws IOException;
    
    /**
     * Update an existing event
     * 
     * @param calendarId The calendar ID (typically "primary")
     * @param eventId The unique identifier of the event
     * @param event The updated event data
     * @return Updated Event object
     * @throws IOException if API call fails
     */
    Event updateEvent(String calendarId, String eventId, Event event) throws IOException;
    
    /**
     * Partially update an event (patch operation)
     * 
     * @param calendarId The calendar ID (typically "primary")
     * @param eventId The unique identifier of the event
     * @param event The event with fields to update
     * @return Updated Event object
     * @throws IOException if API call fails
     */
    Event patchEvent(String calendarId, String eventId, Event event) throws IOException;
    
    /**
     * Delete an event
     * 
     * @param calendarId The calendar ID (typically "primary")
     * @param eventId The unique identifier of the event to delete
     * @throws IOException if API call fails
     */
    void deleteEvent(String calendarId, String eventId) throws IOException;
    
    /**
     * List events with optional filters
     * 
     * @param calendarId The calendar ID (typically "primary")
     * @param timeMin Minimum start time (RFC3339 timestamp)
     * @param timeMax Maximum start time (RFC3339 timestamp)
     * @param maxResults Maximum number of events to return
     * @param singleEvents Whether to expand recurring events
     * @param orderBy Sort order (e.g., "startTime")
     * @return Events collection from Google Calendar API
     * @throws IOException if API call fails
     */
    Events listEvents(String calendarId, String timeMin, String timeMax, 
                     Integer maxResults, Boolean singleEvents, String orderBy) throws IOException;
    
    /**
     * List events for a specific user
     * 
     * @param timeMin Minimum start time (RFC3339 timestamp)
     * @param timeMax Maximum start time (RFC3339 timestamp)
     * @return Events collection from Google Calendar API
     * @throws IOException if API call fails
     */
    Events listEventsForUser(String timeMin, String timeMax) throws IOException;
}

