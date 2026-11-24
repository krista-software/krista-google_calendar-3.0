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
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing calendar event attendee operations.
 *
 * <p>This service handles all attendee-related operations including managing
 * invitation responses (accept, decline, tentative) and attendee list management
 * (add, remove). It focuses on attendee-specific operations rather than general
 * event lifecycle management.</p>
 *
 * <h2>Responsibilities:</h2>
 * <ul>
 *   <li>Accept, decline, or tentatively respond to meeting invitations</li>
 *   <li>Add new attendees to existing events</li>
 *   <li>Remove attendees from events</li>
 *   <li>Update attendee response statuses</li>
 * </ul>
 *
 * @author Krista Software
 * @version 3.0.0
 * @since 3.0.0
 * @see CalendarEventService
 */
@Service
public class CalendarAttendeeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarAttendeeService.class);

    /** The primary calendar identifier used for most calendar operations */
    private static final String PRIMARY_CALENDAR = "primary";

    /** Repository for accessing Google Calendar API */
    private final CalendarRepository calendarRepository;

    /**
     * Constructs a new CalendarAttendeeService with the required dependencies.
     *
     * @param calendarRepository the repository for Google Calendar API access, must not be null
     */
    @Inject
    public CalendarAttendeeService(CalendarRepository calendarRepository) {
        this.calendarRepository = calendarRepository;
    }

    /**
     * Accepts a meeting invitation on behalf of a specific attendee.
     *
     * @param eventId the unique identifier of the event, must not be null or empty
     * @param attendeeEmail the email address of the attendee accepting the invitation
     * @return EventDetails containing the updated event information with accepted status
     * @throws IllegalArgumentException if attendee not found in event
     * @throws IllegalStateException if the Google Calendar API call fails
     */
    public EventDetails acceptInvitation(String eventId, String attendeeEmail) {
        LOGGER.info("→ Service: Accepting invitation - eventId: {}, attendeeEmail: {}", eventId, attendeeEmail);

        try {
            Event event = calendarRepository.getEvent(PRIMARY_CALENDAR, eventId);

            boolean attendeeFound = updateAttendeeStatus(event, attendeeEmail, "accepted");
            if (!attendeeFound) {
                throw new IllegalArgumentException("Attendee not found in event: " + attendeeEmail);
            }

            Event updatedEvent = calendarRepository.patchEvent(PRIMARY_CALENDAR, eventId, event);

            LOGGER.info("← Service: Successfully accepted invitation - eventId: {}", eventId);
            return EventMapper.toEventDetails(updatedEvent);

        } catch (IOException e) {
            LOGGER.error("Failed to accept invitation - eventId: {}, error: {}", eventId, e.getMessage(), e);
            throw new IllegalStateException("Failed to accept invitation: " + e.getMessage(), e);
        }
    }

    /**
     * Declines a meeting invitation on behalf of a specific attendee.
     *
     * @param eventId the unique identifier of the event, must not be null or empty
     * @param attendeeEmail the email address of the attendee declining the invitation
     * @return EventDetails containing the updated event information with declined status
     * @throws IllegalArgumentException if attendee not found in event
     * @throws IllegalStateException if the Google Calendar API call fails
     */
    public EventDetails declineInvitation(String eventId, String attendeeEmail) {
        LOGGER.info("→ Service: Declining invitation - eventId: {}, attendeeEmail: {}", eventId, attendeeEmail);

        try {
            Event event = calendarRepository.getEvent(PRIMARY_CALENDAR, eventId);

            boolean attendeeFound = updateAttendeeStatus(event, attendeeEmail, "declined");
            if (!attendeeFound) {
                throw new IllegalArgumentException("Attendee not found in event: " + attendeeEmail);
            }

            Event updatedEvent = calendarRepository.patchEvent(PRIMARY_CALENDAR, eventId, event);

            LOGGER.info("← Service: Successfully declined invitation - eventId: {}", eventId);
            return EventMapper.toEventDetails(updatedEvent);

        } catch (IOException e) {
            LOGGER.error("Failed to decline invitation - eventId: {}, error: {}", eventId, e.getMessage(), e);
            throw new IllegalStateException("Failed to decline invitation: " + e.getMessage(), e);
        }
    }

    /**
     * Marks a meeting invitation as tentative on behalf of a specific attendee.
     *
     * @param eventId the unique identifier of the event, must not be null or empty
     * @param attendeeEmail the email address of the attendee responding tentatively
     * @return EventDetails containing the updated event information with tentative status
     * @throws IllegalArgumentException if attendee not found in event
     * @throws IllegalStateException if the Google Calendar API call fails
     */
    public EventDetails tentativeInvitation(String eventId, String attendeeEmail) {
        LOGGER.info("→ Service: Marking invitation as tentative - eventId: {}, attendeeEmail: {}",
                   eventId, attendeeEmail);

        try {
            Event event = calendarRepository.getEvent(PRIMARY_CALENDAR, eventId);

            boolean attendeeFound = updateAttendeeStatus(event, attendeeEmail, "tentative");
            if (!attendeeFound) {
                throw new IllegalArgumentException("Attendee not found in event: " + attendeeEmail);
            }

            Event updatedEvent = calendarRepository.patchEvent(PRIMARY_CALENDAR, eventId, event);

            LOGGER.info("← Service: Successfully marked invitation as tentative - eventId: {}", eventId);
            return EventMapper.toEventDetails(updatedEvent);

        } catch (IOException e) {
            LOGGER.error("Failed to mark invitation as tentative - eventId: {}, error: {}",
                        eventId, e.getMessage(), e);
            throw new IllegalStateException("Failed to mark invitation as tentative: " + e.getMessage(), e);
        }
    }

    /**
     * Adds new attendees to an existing calendar event.
     *
     * @param eventId the unique identifier of the event, must not be null or empty
     * @param attendeeEmails list of attendee email addresses to add
     * @return EventDetails containing the updated event information with new attendees
     * @throws IllegalStateException if the Google Calendar API call fails
     */
    public EventDetails addAttendees(String eventId, List<String> attendeeEmails) {
        LOGGER.info("→ Service: Adding {} attendees to event - eventId: {}",
                   attendeeEmails != null ? attendeeEmails.size() : 0, eventId);

        try {
            Event event = calendarRepository.getEvent(PRIMARY_CALENDAR, eventId);

            List<EventAttendee> existingAttendees = event.getAttendees();
            if (existingAttendees == null) {
                existingAttendees = new ArrayList<>();
                event.setAttendees(existingAttendees);
            }

            List<EventAttendee> newAttendees = EventMapper.toEventAttendees(attendeeEmails);
            existingAttendees.addAll(newAttendees);

            Event updatedEvent = calendarRepository.patchEvent(PRIMARY_CALENDAR, eventId, event);

            LOGGER.info("← Service: Successfully added attendees - eventId: {}", eventId);
            return EventMapper.toEventDetails(updatedEvent);

        } catch (IOException e) {
            LOGGER.error("Failed to add attendees - eventId: {}, error: {}", eventId, e.getMessage(), e);
            throw new IllegalStateException("Failed to add attendees: " + e.getMessage(), e);
        }
    }

    /**
     * Removes a specific attendee from a calendar event.
     *
     * @param eventId the unique identifier of the event, must not be null or empty
     * @param attendeeEmail the email address of the attendee to remove
     * @return EventDetails containing the updated event information without the removed attendee
     * @throws IllegalArgumentException if attendee not found or event has no attendees
     * @throws IllegalStateException if the Google Calendar API call fails
     */
    public EventDetails removeAttendee(String eventId, String attendeeEmail) {
        LOGGER.info("→ Service: Removing attendee from event - eventId: {}, attendeeEmail: {}",
                   eventId, attendeeEmail);

        try {
            Event event = calendarRepository.getEvent(PRIMARY_CALENDAR, eventId);

            List<EventAttendee> attendees = event.getAttendees();
            if (attendees != null) {
                boolean removed = attendees.removeIf(attendee ->
                    attendee.getEmail() != null && attendee.getEmail().equals(attendeeEmail));

                if (!removed) {
                    throw new IllegalArgumentException("Attendee not found in event: " + attendeeEmail);
                }
            } else {
                throw new IllegalArgumentException("Event has no attendees");
            }

            Event updatedEvent = calendarRepository.patchEvent(PRIMARY_CALENDAR, eventId, event);

            LOGGER.info("← Service: Successfully removed attendee - eventId: {}", eventId);
            return EventMapper.toEventDetails(updatedEvent);

        } catch (IOException e) {
            LOGGER.error("Failed to remove attendee - eventId: {}, error: {}", eventId, e.getMessage(), e);
            throw new IllegalStateException("Failed to remove attendee: " + e.getMessage(), e);
        }
    }

    /**
     * Updates the response status of a specific attendee in an event.
     *
     * @param event the event containing the attendee list, must not be null
     * @param attendeeEmail the email address of the attendee to update
     * @param status the new response status ("accepted", "declined", "tentative", or "needsAction")
     * @return true if the attendee was found and updated, false if attendee not found
     */
    private boolean updateAttendeeStatus(Event event, String attendeeEmail, String status) {
        List<EventAttendee> attendees = event.getAttendees();
        if (attendees == null) {
            return false;
        }

        boolean found = false;
        for (EventAttendee attendee : attendees) {
            if (attendee.getEmail() != null && attendee.getEmail().equals(attendeeEmail)) {
                attendee.setResponseStatus(status);
                found = true;
                break;
            }
        }

        return found;
    }
}

