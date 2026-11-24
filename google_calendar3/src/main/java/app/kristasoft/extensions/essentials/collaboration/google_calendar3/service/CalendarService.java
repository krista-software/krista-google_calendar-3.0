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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.entity.EventDetails;
import org.jvnet.hk2.annotations.Contract;

import java.util.List;

/**
 * Service interface for Calendar operations.
 * This layer contains business logic and orchestrates operations between different components.
 *
 * Design Pattern: Service Layer Pattern
 * - Separates business logic from presentation layer (MeetingsArea)
 * - Provides a clear API for calendar operations
 * - Enables easier testing and maintenance
 *
 * Note: Interfaces should use @Contract, not @Service. Only implementations use @Service.
 */
@Contract
public interface CalendarService {
    
    /**
     * Accept a meeting invitation for a specific attendee
     * 
     * @param eventId The unique identifier of the event
     * @param attendeeEmail The email of the attendee accepting the invitation
     * @return EventDetails with updated acceptance status
     */
    EventDetails acceptInvitation(String eventId, String attendeeEmail);
    
    /**
     * Decline a meeting invitation for a specific attendee
     * 
     * @param eventId The unique identifier of the event
     * @param attendeeEmail The email of the attendee declining the invitation
     * @return EventDetails with updated decline status
     */
    EventDetails declineInvitation(String eventId, String attendeeEmail);
    
    /**
     * Mark invitation as tentative for a specific attendee
     * 
     * @param eventId The unique identifier of the event
     * @param attendeeEmail The email of the attendee responding tentatively
     * @return EventDetails with updated tentative status
     */
    EventDetails tentativeInvitation(String eventId, String attendeeEmail);
    
    /**
     * Add attendees to an existing event
     * 
     * @param eventId The unique identifier of the event
     * @param attendeeEmails List of email addresses to add
     * @return EventDetails with updated attendee list
     */
    EventDetails addAttendees(String eventId, List<String> attendeeEmails);
    
    /**
     * Remove an attendee from an event
     * 
     * @param eventId The unique identifier of the event
     * @param attendeeEmail The email of the attendee to remove
     * @return EventDetails with updated attendee list
     */
    EventDetails removeAttendee(String eventId, String attendeeEmail);
    
    /**
     * Schedule a new meeting
     * 
     * @param title Meeting title
     * @param location Meeting location (optional)
     * @param dateFrom Start date/time in milliseconds
     * @param dateTo End date/time in milliseconds
     * @param details Meeting description
     * @param attendeeEmails List of attendee email addresses
     * @return EventDetails of the created event
     */
    EventDetails scheduleMeeting(String title, String location, Long dateFrom, Long dateTo, 
                                 String details, List<String> attendeeEmails);
    
    /**
     * Cancel an event
     * 
     * @param eventId The unique identifier of the event to cancel
     * @return EventDetails of the cancelled event
     */
    EventDetails cancelEvent(String eventId);
    
    /**
     * Get details of a specific event
     * 
     * @param eventId The unique identifier of the event
     * @return EventDetails of the requested event
     */
    EventDetails getEvent(String eventId);
    
    /**
     * Get next N upcoming meetings
     * 
     * @param numberOfMeetings Number of meetings to retrieve
     * @return List of EventDetails for upcoming meetings
     */
    List<EventDetails> getNextUpcomingMeetings(Double numberOfMeetings);
    
    /**
     * Get all unaccepted events
     * 
     * @return List of EventDetails for unaccepted events
     */
    List<EventDetails> getAllUnacceptedEvents();
    
    /**
     * Get events for a specific user within a date range
     * 
     * @param userEmail Email of the user
     * @param from Start date/time in milliseconds
     * @param to End date/time in milliseconds
     * @return List of EventDetails for the user
     */
    List<EventDetails> getEventsForUser(String userEmail, Long from, Long to);
}

