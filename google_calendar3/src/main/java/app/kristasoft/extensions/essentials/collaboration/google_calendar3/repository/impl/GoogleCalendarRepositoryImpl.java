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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.repository.impl;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.repository.CalendarRepository;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;

/**
 * Implementation of CalendarRepository using Google Calendar API.
 *
 * <p>This repository class encapsulates all direct interactions with the Google Calendar API,
 * providing a clean abstraction layer for data access operations. It handles authentication,
 * API calls, and API-specific error handling, ensuring that the service layer remains
 * independent of Google Calendar API implementation details.</p>
 *
 * <h2>Design Pattern: Repository/DAO Pattern Implementation</h2>
 * <ul>
 *   <li><b>Data Access Abstraction:</b> All Google Calendar API calls are centralized here</li>
 *   <li><b>Authentication Management:</b> Uses GoogleProvider for OAuth2 authentication</li>
 *   <li><b>Error Handling:</b> Propagates IOExceptions for service layer to handle</li>
 *   <li><b>Logging:</b> Comprehensive debug logging for all operations with entry/exit markers</li>
 * </ul>
 *
 * <h2>Responsibilities:</h2>
 * <ul>
 *   <li>Execute CRUD operations on Google Calendar events</li>
 *   <li>Manage authentication through GoogleProvider</li>
 *   <li>Handle API-specific errors and exceptions</li>
 *   <li>Log all data access operations with entry (→) and exit (←) markers</li>
 *   <li>Provide clean API abstraction to service layer</li>
 * </ul>
 *
 * <h2>Thread Safety:</h2>
 * This class is thread-safe. The GoogleProvider is obtained per-request and
 * the Google Calendar API client is stateless. No mutable state is maintained.
 *
 * @author Krista Software
 * @version 3.0.0
 * @since 3.0.0
 * @see CalendarRepository
 * @see GoogleProvider
 * @see GoogleProviderFactory
 */
@Service
public class GoogleCalendarRepositoryImpl implements CalendarRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleCalendarRepositoryImpl.class);

    /** GoogleProvider for obtaining authenticated Calendar API clients */
    private final GoogleProvider googleProvider;

    /**
     * Constructs a new GoogleCalendarRepositoryImpl with the required dependencies.
     *
     * <p>This constructor is used by the HK2 dependency injection framework. The GoogleProviderFactory
     * is injected and used to create a GoogleProvider instance for API access.</p>
     *
     * @param factory the factory for creating GoogleProvider instances, must not be null
     * @throws NullPointerException if factory is null
     */
    @Inject
    public GoogleCalendarRepositoryImpl(GoogleProviderFactory factory) {
        this.googleProvider = factory.create();
    }
    
    /**
     * Retrieves a specific event from Google Calendar by its ID.
     *
     * <p>This method fetches complete event details including title, location, time,
     * attendees, description, and all other event properties.</p>
     *
     * @param calendarId the calendar ID (typically "primary"), must not be null
     * @param eventId the unique identifier of the event, must not be null
     * @return Event object from Google Calendar API with all event details
     * @throws IOException if the API call fails, event not found, or network error occurs
     */
    @Override
    public Event getEvent(String calendarId, String eventId) throws IOException {
        LOGGER.debug("→ Repository: Getting event - calendarId: {}, eventId: {}", calendarId, eventId);
        Calendar service = getCalendarService();
        Event event = service.events().get(calendarId, eventId).execute();
        LOGGER.debug("← Repository: Successfully retrieved event: {}", eventId);
        return event;
    }

    /**
     * Creates a new event in Google Calendar.
     *
     * <p>This method inserts a new event with all provided details. The Google Calendar API
     * will generate a unique event ID and return the complete event object.</p>
     *
     * @param calendarId the calendar ID (typically "primary"), must not be null
     * @param event the event to create with all required fields populated, must not be null
     * @return Created Event object with server-generated ID and other fields
     * @throws IOException if the API call fails or network error occurs
     */
    @Override
    public Event createEvent(String calendarId, Event event) throws IOException {
        LOGGER.debug("→ Repository: Creating event - calendarId: {}, summary: {}", calendarId, event.getSummary());
        Calendar service = getCalendarService();
        Event createdEvent = service.events().insert(calendarId, event).execute();
        LOGGER.debug("← Repository: Successfully created event with ID: {}", createdEvent.getId());
        return createdEvent;
    }

    /**
     * Performs a full update of an existing event in Google Calendar.
     *
     * <p>This method replaces all fields of the event. Use patchEvent for partial updates
     * to avoid overwriting fields that haven't changed.</p>
     *
     * @param calendarId the calendar ID (typically "primary"), must not be null
     * @param eventId the unique identifier of the event to update, must not be null
     * @param event the complete event data with all fields, must not be null
     * @return Updated Event object from the server
     * @throws IOException if the API call fails, event not found, or network error occurs
     * @see #patchEvent(String, String, Event)
     */
    @Override
    public Event updateEvent(String calendarId, String eventId, Event event) throws IOException {
        LOGGER.debug("→ Repository: Updating event - calendarId: {}, eventId: {}", calendarId, eventId);
        Calendar service = getCalendarService();
        Event updatedEvent = service.events().update(calendarId, eventId, event).execute();
        LOGGER.debug("← Repository: Successfully updated event: {}", eventId);
        return updatedEvent;
    }

    /**
     * Performs a partial update of an existing event in Google Calendar.
     *
     * <p>This method only updates the fields that are set in the event object.
     * Unset fields are not modified. This is more efficient than updateEvent for
     * small changes like updating attendee status.</p>
     *
     * @param calendarId the calendar ID (typically "primary"), must not be null
     * @param eventId the unique identifier of the event to update, must not be null
     * @param event the event with only the fields to update set, must not be null
     * @return Updated Event object from the server
     * @throws IOException if the API call fails, event not found, or network error occurs
     * @see #updateEvent(String, String, Event)
     */
    @Override
    public Event patchEvent(String calendarId, String eventId, Event event) throws IOException {
        LOGGER.debug("→ Repository: Patching event - calendarId: {}, eventId: {}", calendarId, eventId);
        Calendar service = getCalendarService();
        Event patchedEvent = service.events().patch(calendarId, eventId, event).execute();
        LOGGER.debug("← Repository: Successfully patched event: {}", eventId);
        return patchedEvent;
    }

    /**
     * Deletes an event from Google Calendar.
     *
     * <p><b>Warning:</b> This operation is irreversible. The event cannot be recovered after deletion.
     * All attendees will receive cancellation notifications.</p>
     *
     * @param calendarId the calendar ID (typically "primary"), must not be null
     * @param eventId the unique identifier of the event to delete, must not be null
     * @throws IOException if the API call fails, event not found, or network error occurs
     */
    @Override
    public void deleteEvent(String calendarId, String eventId) throws IOException {
        LOGGER.debug("→ Repository: Deleting event - calendarId: {}, eventId: {}", calendarId, eventId);
        Calendar service = getCalendarService();
        service.events().delete(calendarId, eventId).execute();
        LOGGER.debug("← Repository: Successfully deleted event: {}", eventId);
    }
    
    /**
     * Lists events from Google Calendar with optional filtering and sorting.
     *
     * <p>This method provides flexible event listing with support for time range filtering,
     * result limiting, recurring event expansion, and custom sorting.</p>
     *
     * @param calendarId the calendar ID (typically "primary"), must not be null
     * @param timeMin minimum start time in RFC3339 format (null for no lower bound)
     * @param timeMax maximum start time in RFC3339 format (null for no upper bound)
     * @param maxResults maximum number of events to return (null for API default of 250)
     * @param singleEvents whether to expand recurring events into instances (null for API default of false)
     * @param orderBy sort order, e.g., "startTime" (null for API default, requires singleEvents=true)
     * @return Events collection containing the list of events and pagination info
     * @throws IOException if the API call fails or network error occurs
     */
    @Override
    public Events listEvents(String calendarId, String timeMin, String timeMax,
                            Integer maxResults, Boolean singleEvents, String orderBy) throws IOException {
        LOGGER.debug("→ Repository: Listing events - calendarId: {}, timeMin: {}, timeMax: {}, maxResults: {}",
                    calendarId, timeMin, timeMax, maxResults);
        
        Calendar service = getCalendarService();
        Calendar.Events.List request = service.events().list(calendarId);
        
        if (timeMin != null) {
            request.setTimeMin(new com.google.api.client.util.DateTime(timeMin));
        }
        if (timeMax != null) {
            request.setTimeMax(new com.google.api.client.util.DateTime(timeMax));
        }
        if (maxResults != null) {
            request.setMaxResults(maxResults);
        }
        if (singleEvents != null) {
            request.setSingleEvents(singleEvents);
        }
        if (orderBy != null) {
            request.setOrderBy(orderBy);
        }
        
        Events events = request.execute();
        LOGGER.debug("← Repository: Successfully retrieved {} events", 
                    events.getItems() != null ? events.getItems().size() : 0);
        return events;
    }
    
    /**
     * Lists events for a specific user within a time range.
     *
     * <p><b>Note:</b> Currently uses the standard authenticated client method. For true user-specific
     * access, implement user impersonation or service account delegation.</p>
     *
     * @param timeMin minimum start time in RFC3339 format (null for no lower bound)
     * @param timeMax maximum start time in RFC3339 format (null for no upper bound)
     * @return Events collection containing the list of events
     * @throws IOException if the API call fails or network error occurs
     */
    @Override
    public Events listEventsForUser(String timeMin, String timeMax) throws IOException {
        LOGGER.debug("→ Repository: Listing events for user - timeMin: {}, timeMax: {}", timeMin, timeMax);

        // For now, use the standard getGoogleClient() method
        Calendar service = getCalendarService();
        Calendar.Events.List request = service.events().list("primary");

        if (timeMin != null) {
            request.setTimeMin(new com.google.api.client.util.DateTime(timeMin));
        }
        if (timeMax != null) {
            request.setTimeMax(new com.google.api.client.util.DateTime(timeMax));
        }

        Events events = request.execute();
        LOGGER.debug("← Repository: Successfully retrieved {} events for user",
                    events.getItems() != null ? events.getItems().size() : 0);
        return events;
    }
    
    /**
     * Obtains an authenticated Google Calendar API client.
     *
     * <p>This private helper method creates an authenticated Calendar service client
     * using the GoogleProvider. The client is configured with proper OAuth2 credentials
     * and ready for making API calls.</p>
     *
     * @return authenticated Calendar service client
     * @throws IOException if authentication fails or network error occurs
     */
    private Calendar getCalendarService() throws IOException {
        return googleProvider.getGoogleClient();
    }
}

