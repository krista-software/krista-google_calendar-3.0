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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.CalendarService;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.List;

/**
 * Implementation of CalendarService that orchestrates calendar operations.
 *
 * <p>This service layer acts as the central orchestrator for all calendar-related business operations,
 * delegating to specialized services for attendee and event management. It provides a clean separation
 * between the presentation layer (MeetingsArea) and specialized service implementations.</p>
 *
 * <h2>Design Pattern: Facade Pattern + Delegation</h2>
 * <ul>
 *   <li><b>Facade:</b> Provides a unified interface to the CalendarService contract</li>
 *   <li><b>Delegation:</b> Delegates attendee operations to CalendarAttendeeService</li>
 *   <li><b>Delegation:</b> Delegates event operations to CalendarEventService</li>
 *   <li><b>Single Responsibility:</b> Each service handles its specific domain</li>
 * </ul>
 *
 * <h2>Responsibilities:</h2>
 * <ul>
 *   <li>Implement the CalendarService interface contract</li>
 *   <li>Delegate attendee operations to CalendarAttendeeService</li>
 *   <li>Delegate event operations to CalendarEventService</li>
 *   <li>Provide a unified entry point for all calendar operations</li>
 * </ul>
 *
 * <h2>Dependencies:</h2>
 * <ul>
 *   <li>{@link CalendarAttendeeService} - For attendee-related operations</li>
 *   <li>{@link CalendarEventService} - For event-related operations</li>
 * </ul>
 *
 * <h2>Thread Safety:</h2>
 * This class is thread-safe as it maintains no mutable state. All operations are stateless
 * and delegate to the injected specialized services.
 *
 * @author Krista Software
 * @version 3.0.0
 * @since 3.0.0
 * @see CalendarService
 * @see CalendarAttendeeService
 * @see CalendarEventService
 */
@Service
public class CalendarServiceImpl implements CalendarService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarServiceImpl.class);

    /** Service for managing attendee-related operations */
    private final CalendarAttendeeService attendeeService;

    /** Service for managing event-related operations */
    private final CalendarEventService eventService;

    /**
     * Constructs a new CalendarServiceImpl with the required dependencies.
     *
     * <p>This constructor is used by the HK2 dependency injection framework to create
     * instances of this service. The specialized services are automatically injected.</p>
     *
     * @param attendeeService the service for attendee operations, must not be null
     * @param eventService the service for event operations, must not be null
     * @throws NullPointerException if any parameter is null
     */
    @Inject
    public CalendarServiceImpl(CalendarAttendeeService attendeeService,
                               CalendarEventService eventService) {
        this.attendeeService = attendeeService;
        this.eventService = eventService;
    }

    /**
     * Accepts a meeting invitation on behalf of a specific attendee.
     *
     * <p>Delegates to {@link CalendarAttendeeService} for attendee-specific operations.</p>
     *
     * @param eventId the unique identifier of the event, must not be null or empty
     * @param attendeeEmail the email address of the attendee accepting the invitation, must not be null or empty
     * @return EventDetails containing the updated event information with accepted status
     * @throws IllegalArgumentException if eventId or attendeeEmail is null/empty, or if attendee not found in event
     * @throws IllegalStateException if the Google Calendar API call fails
     * @see CalendarAttendeeService#acceptInvitation(String, String)
     */
    @Override
    public EventDetails acceptInvitation(String eventId, String attendeeEmail) {
        LOGGER.debug("Delegating acceptInvitation to CalendarAttendeeService");
        return attendeeService.acceptInvitation(eventId, attendeeEmail);
    }

    /**
     * Declines a meeting invitation on behalf of a specific attendee.
     *
     * <p>Delegates to {@link CalendarAttendeeService} for attendee-specific operations.</p>
     *
     * @param eventId the unique identifier of the event, must not be null or empty
     * @param attendeeEmail the email address of the attendee declining the invitation, must not be null or empty
     * @return EventDetails containing the updated event information with declined status
     * @throws IllegalArgumentException if eventId or attendeeEmail is null/empty, or if attendee not found in event
     * @throws IllegalStateException if the Google Calendar API call fails
     * @see CalendarAttendeeService#declineInvitation(String, String)
     */
    @Override
    public EventDetails declineInvitation(String eventId, String attendeeEmail) {
        LOGGER.debug("Delegating declineInvitation to CalendarAttendeeService");
        return attendeeService.declineInvitation(eventId, attendeeEmail);
    }

    /**
     * Marks a meeting invitation as tentative on behalf of a specific attendee.
     *
     * <p>Delegates to {@link CalendarAttendeeService} for attendee-specific operations.</p>
     *
     * @param eventId the unique identifier of the event, must not be null or empty
     * @param attendeeEmail the email address of the attendee responding tentatively, must not be null or empty
     * @return EventDetails containing the updated event information with tentative status
     * @throws IllegalArgumentException if eventId or attendeeEmail is null/empty, or if attendee not found in event
     * @throws IllegalStateException if the Google Calendar API call fails
     * @see CalendarAttendeeService#tentativeInvitation(String, String)
     */
    @Override
    public EventDetails tentativeInvitation(String eventId, String attendeeEmail) {
        LOGGER.debug("Delegating tentativeInvitation to CalendarAttendeeService");
        return attendeeService.tentativeInvitation(eventId, attendeeEmail);
    }

    /**
     * Adds new attendees to an existing calendar event.
     *
     * <p>Delegates to {@link CalendarAttendeeService} for attendee-specific operations.</p>
     *
     * @param eventId the unique identifier of the event, must not be null or empty
     * @param attendeeEmails list of attendee email addresses to add
     * @return EventDetails containing the updated event information with new attendees
     * @throws IllegalStateException if the Google Calendar API call fails
     * @see CalendarAttendeeService#addAttendees(String, List)
     */
    @Override
    public EventDetails addAttendees(String eventId, List<String> attendeeEmails) {
        LOGGER.debug("Delegating addAttendees to CalendarAttendeeService");
        return attendeeService.addAttendees(eventId, attendeeEmails);
    }

    /**
     * Removes a specific attendee from a calendar event.
     *
     * <p>Delegates to {@link CalendarAttendeeService} for attendee-specific operations.</p>
     *
     * @param eventId the unique identifier of the event, must not be null or empty
     * @param attendeeEmail the email address of the attendee to remove, must not be null or empty
     * @return EventDetails containing the updated event information without the removed attendee
     * @throws IllegalArgumentException if eventId or attendeeEmail is null/empty, attendee not found, or event has no attendees
     * @throws IllegalStateException if the Google Calendar API call fails
     * @see CalendarAttendeeService#removeAttendee(String, String)
     */
    @Override
    public EventDetails removeAttendee(String eventId, String attendeeEmail) {
        LOGGER.debug("Delegating removeAttendee to CalendarAttendeeService");
        return attendeeService.removeAttendee(eventId, attendeeEmail);
    }

    /**
     * Creates and schedules a new meeting in Google Calendar.
     *
     * <p>Delegates to {@link CalendarEventService} for event-specific operations.</p>
     *
     * @param title the meeting title/summary, must not be null or empty
     * @param location the meeting location (can be null for virtual meetings)
     * @param dateFrom the meeting start time in milliseconds since epoch, must not be null
     * @param dateTo the meeting end time in milliseconds since epoch, must not be null
     * @param details the meeting description/agenda (can be null)
     * @param attendeeEmails list of attendee email addresses (can be empty but not null)
     * @return EventDetails containing the created event information including the generated event ID
     * @throws IllegalStateException if the Google Calendar API call fails
     * @see CalendarEventService#scheduleMeeting(String, String, Long, Long, String, List)
     */
    @Override
    public EventDetails scheduleMeeting(String title, String location, Long dateFrom, Long dateTo,
                                       String details, List<String> attendeeEmails) {
        LOGGER.debug("Delegating scheduleMeeting to CalendarEventService");
        return eventService.scheduleMeeting(title, location, dateFrom, dateTo, details, attendeeEmails);
    }

    /**
     * Cancels and deletes a calendar event.
     *
     * <p>Delegates to {@link CalendarEventService} for event-specific operations.</p>
     *
     * @param eventId the unique identifier of the event to cancel, must not be null or empty
     * @return EventDetails containing the event information before cancellation
     * @throws IllegalStateException if the Google Calendar API call fails
     * @see CalendarEventService#cancelEvent(String)
     */
    @Override
    public EventDetails cancelEvent(String eventId) {
        LOGGER.debug("Delegating cancelEvent to CalendarEventService");
        return eventService.cancelEvent(eventId);
    }

    /**
     * Retrieves detailed information about a specific calendar event.
     *
     * <p>Delegates to {@link CalendarEventService} for event-specific operations.</p>
     *
     * @param eventId the unique identifier of the event to retrieve, must not be null or empty
     * @return EventDetails containing all event information
     * @throws IllegalStateException if the event is not found or API call fails
     * @see CalendarEventService#getEvent(String)
     */
    @Override
    public EventDetails getEvent(String eventId) {
        LOGGER.debug("Delegating getEvent to CalendarEventService");
        return eventService.getEvent(eventId);
    }

    /**
     * Retrieves the next N upcoming meetings from the calendar.
     *
     * <p>Delegates to {@link CalendarEventService} for event-specific operations.</p>
     *
     * @param numberOfMeetings the maximum number of meetings to retrieve (defaults to 10 if null)
     * @return list of EventDetails for upcoming meetings, ordered by start time (may be empty but never null)
     * @throws IllegalStateException if the Google Calendar API call fails
     * @see CalendarEventService#getNextUpcomingMeetings(Double)
     */
    @Override
    public List<EventDetails> getNextUpcomingMeetings(Double numberOfMeetings) {
        LOGGER.debug("Delegating getNextUpcomingMeetings to CalendarEventService");
        return eventService.getNextUpcomingMeetings(numberOfMeetings);
    }

    /**
     * Retrieves all upcoming events that have unaccepted invitations.
     *
     * <p>Delegates to {@link CalendarEventService} for event-specific operations.</p>
     *
     * @return list of EventDetails for events with unaccepted invitations (may be empty but never null)
     * @throws IllegalStateException if the Google Calendar API call fails
     * @see CalendarEventService#getAllUnacceptedEvents()
     */
    @Override
    public List<EventDetails> getAllUnacceptedEvents() {
        LOGGER.debug("Delegating getAllUnacceptedEvents to CalendarEventService");
        return eventService.getAllUnacceptedEvents();
    }

    /**
     * Retrieves calendar events for a specific user within a date range.
     *
     * <p>Delegates to {@link CalendarEventService} for event-specific operations.</p>
     *
     * @param userEmail the email address of the user whose events to retrieve, must not be null or empty
     * @param from the start time in milliseconds since epoch (null for no lower bound)
     * @param to the end time in milliseconds since epoch (null for no upper bound)
     * @return list of EventDetails for the user's events within the time range (may be empty but never null)
     * @throws IllegalStateException if the Google Calendar API call fails
     * @see CalendarEventService#getEventsForUser(String, Long, Long)
     */
    @Override
    public List<EventDetails> getEventsForUser(String userEmail, Long from, Long to) {
        LOGGER.debug("Delegating getEventsForUser to CalendarEventService");
        return eventService.getEventsForUser(userEmail, from, to);
    }
}

