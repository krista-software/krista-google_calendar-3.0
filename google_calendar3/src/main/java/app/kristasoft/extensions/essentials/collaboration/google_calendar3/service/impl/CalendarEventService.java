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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.impl;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.entity.EventDetails;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.mapper.EventMapper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.repository.CalendarRepository;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.Events;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing calendar event CRUD operations.
 *
 * <p>This service handles all event-related operations including creating, reading,
 * updating, and deleting calendar events. It focuses on event lifecycle management
 * rather than attendee-specific operations.</p>
 *
 * <h2>Responsibilities:</h2>
 * <ul>
 *   <li>Schedule new meetings and events</li>
 *   <li>Retrieve event details (single or multiple)</li>
 *   <li>Query upcoming meetings and unaccepted events</li>
 *   <li>Cancel and delete events</li>
 *   <li>Retrieve events for specific users</li>
 * </ul>
 *
 * @author Krista Software
 * @version 3.0.0
 * @since 3.0.0
 * @see CalendarAttendeeService
 */
@Service
public class CalendarEventService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarEventService.class);

    /** The primary calendar identifier used for most calendar operations */
    private static final String PRIMARY_CALENDAR = "primary";

    /** Repository for accessing Google Calendar API */
    private final CalendarRepository calendarRepository;

    /**
     * Constructs a new CalendarEventService with the required dependencies.
     *
     * @param calendarRepository the repository for Google Calendar API access, must not be null
     */
    @Inject
    public CalendarEventService(CalendarRepository calendarRepository) {
        this.calendarRepository = calendarRepository;
    }

    /**
     * Creates and schedules a new meeting in Google Calendar.
     *
     * @param title the meeting title/summary, must not be null or empty
     * @param location the meeting location (can be null for virtual meetings)
     * @param dateFrom the meeting start time in milliseconds since epoch, must not be null
     * @param dateTo the meeting end time in milliseconds since epoch, must not be null
     * @param details the meeting description/agenda (can be null)
     * @param attendeeEmails list of attendee email addresses (can be empty but not null)
     * @return EventDetails containing the created event information including the generated event ID
     * @throws IllegalStateException if the Google Calendar API call fails
     */
    public EventDetails scheduleMeeting(String title, String location, Long dateFrom, Long dateTo,
                                       String details, List<String> attendeeEmails) {
        LOGGER.info("→ Service: Scheduling meeting - title: {}, dateFrom: {}, dateTo: {}",
                   title, dateFrom, dateTo);

        try {
            Event event = EventMapper.toGoogleEvent(title, location, dateFrom, dateTo, details, attendeeEmails);
            Event createdEvent = calendarRepository.createEvent(PRIMARY_CALENDAR, event);

            LOGGER.info("← Service: Successfully scheduled meeting - eventId: {}", createdEvent.getId());
            return EventMapper.toEventDetails(createdEvent);

        } catch (IOException e) {
            LOGGER.error("Failed to schedule meeting - title: {}, error: {}", title, e.getMessage(), e);
            throw new IllegalStateException("Failed to schedule meeting: " + e.getMessage(), e);
        }
    }

    /**
     * Cancels and deletes a calendar event.
     *
     * @param eventId the unique identifier of the event to cancel, must not be null or empty
     * @return EventDetails containing the event information before cancellation
     * @throws IllegalStateException if the Google Calendar API call fails
     */
    public EventDetails cancelEvent(String eventId) {
        LOGGER.info("→ Service: Canceling event - eventId: {}", eventId);

        try {
            Event event = calendarRepository.getEvent(PRIMARY_CALENDAR, eventId);
            calendarRepository.deleteEvent(PRIMARY_CALENDAR, eventId);

            LOGGER.info("← Service: Successfully canceled event - eventId: {}", eventId);
            return EventMapper.toEventDetails(event);

        } catch (IOException e) {
            LOGGER.error("Failed to cancel event - eventId: {}, error: {}", eventId, e.getMessage(), e);
            throw new IllegalStateException("Failed to cancel event: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves detailed information about a specific calendar event.
     *
     * @param eventId the unique identifier of the event to retrieve, must not be null or empty
     * @return EventDetails containing all event information
     * @throws IllegalStateException if the event is not found or API call fails
     */
    public EventDetails getEvent(String eventId) {
        LOGGER.info("→ Service: Getting event - eventId: {}", eventId);

        try {
            Event event = calendarRepository.getEvent(PRIMARY_CALENDAR, eventId);

            LOGGER.info("← Service: Successfully retrieved event - eventId: {}", eventId);
            return EventMapper.toEventDetails(event);

        } catch (IOException e) {
            LOGGER.error("Failed to get event - eventId: {}, error: {}", eventId, e.getMessage(), e);
            throw new IllegalStateException("Failed to get event: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves the next N upcoming meetings from the calendar.
     *
     * @param numberOfMeetings the maximum number of meetings to retrieve (defaults to 10 if null)
     * @return list of EventDetails for upcoming meetings, ordered by start time
     * @throws IllegalStateException if the Google Calendar API call fails
     */
    public List<EventDetails> getNextUpcomingMeetings(Double numberOfMeetings) {
        LOGGER.info("→ Service: Getting next {} upcoming meetings", numberOfMeetings);

        try {
            int maxResults = 10; // default
            if (numberOfMeetings != null && !numberOfMeetings.isNaN() && !numberOfMeetings.isInfinite()) {
                maxResults = Math.max(1, Math.min(numberOfMeetings.intValue(), 2500)); // Google API limit
            }
            DateTime now = new DateTime(System.currentTimeMillis());

            Events events = calendarRepository.listEvents(
                PRIMARY_CALENDAR,
                now.toStringRfc3339(),
                null,
                maxResults,
                true,
                "startTime"
            );

            List<Event> eventList = events.getItems() != null ? events.getItems() : List.of();

            LOGGER.info("← Service: Successfully retrieved {} upcoming meetings", eventList.size());
            return EventMapper.toEventDetailsList(eventList);

        } catch (IOException e) {
            LOGGER.error("Failed to get upcoming meetings - error: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to get upcoming meetings: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves all upcoming events that have unaccepted invitations.
     *
     * <p>This method fetches all future events and filters them to include only those where
     * at least one attendee has a "needsAction" response status, indicating pending invitations.</p>
     *
     * @return list of EventDetails for events with unaccepted invitations (may be empty but never null)
     * @throws IllegalStateException if the Google Calendar API call fails
     */
    public List<EventDetails> getAllUnacceptedEvents() {
        LOGGER.info("→ Service: Getting all unaccepted events");

        try {
            DateTime now = new DateTime(System.currentTimeMillis());

            Events events = calendarRepository.listEvents(
                PRIMARY_CALENDAR,
                now.toStringRfc3339(),
                null,
                null,
                true,
                "startTime"
            );

            List<Event> eventList = events.getItems() != null ? events.getItems() : List.of();

            // Filter for unaccepted events
            List<Event> unacceptedEvents = eventList.stream()
                .filter(this::hasUnacceptedInvitation)
                .collect(Collectors.toList());

            LOGGER.info("← Service: Successfully retrieved {} unaccepted events", unacceptedEvents.size());
            return EventMapper.toEventDetailsList(unacceptedEvents);

        } catch (IOException e) {
            LOGGER.error("Failed to get unaccepted events - error: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to get unaccepted events: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves calendar events for a specific user within a date range.
     *
     * <p>This method fetches events from the specified user's calendar within the given
     * time range. If no time range is specified, all events are returned.</p>
     *
     * @param userEmail the email address of the user whose events to retrieve, must not be null or empty
     * @param from the start time in milliseconds since epoch (null for no lower bound)
     * @param to the end time in milliseconds since epoch (null for no upper bound)
     * @return list of EventDetails for the user's events within the time range (may be empty but never null)
     * @throws IllegalStateException if the Google Calendar API call fails
     */
    public List<EventDetails> getEventsForUser(String userEmail, Long from, Long to) {
        LOGGER.info("→ Service: Getting events for user - email: {}, from: {}, to: {}",
                   userEmail, from, to);

        try {
            String timeMin = from != null ? new DateTime(from).toStringRfc3339() : null;
            String timeMax = to != null ? new DateTime(to).toStringRfc3339() : null;

            Events events = calendarRepository.listEventsForUser(timeMin, timeMax);

            List<Event> eventList = events.getItems() != null ? events.getItems() : List.of();

            LOGGER.info("← Service: Successfully retrieved {} events for user {}",
                       eventList.size(), userEmail);
            return EventMapper.toEventDetailsList(eventList);

        } catch (IOException e) {
            LOGGER.error("Failed to get events for user - email: {}, error: {}",
                        userEmail, e.getMessage(), e);
            throw new IllegalStateException("Failed to get events for user: " + e.getMessage(), e);
        }
    }

    /**
     * Checks if an event has any unaccepted invitations.
     *
     * <p>This is a private helper method that examines all attendees in an event to determine
     * if any have a "needsAction" response status, indicating they haven't responded to the invitation.</p>
     *
     * @param event the event to check, must not be null
     * @return true if at least one attendee has "needsAction" status, false otherwise
     */
    private boolean hasUnacceptedInvitation(Event event) {
        List<EventAttendee> attendees = event.getAttendees();
        if (attendees == null || attendees.isEmpty()) {
            return false;
        }

        return attendees.stream()
            .anyMatch(attendee -> "needsAction".equals(attendee.getResponseStatus()));
    }
}

