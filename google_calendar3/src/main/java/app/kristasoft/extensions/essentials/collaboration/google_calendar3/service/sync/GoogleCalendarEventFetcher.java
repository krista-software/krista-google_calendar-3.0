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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
public class GoogleCalendarEventFetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleCalendarEventFetcher.class);
    private static final String PRIMARY_CALENDAR = "primary";

    private final CalendarSyncTelemetryService telemetryService;

    @Inject
    public GoogleCalendarEventFetcher(CalendarSyncTelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    /**
     * Fetches calendar events within the next N days from the current timestamp (UTC).
     *
     * @param calendarService authenticated Google Calendar client
     * @param numberOfDays    number of future days to fetch events for
     * @return list of events within the requested range (may be empty, never null)
     */
    public List<Event> fetchEventsForDays(Calendar calendarService, long numberOfDays) {
        return fetchEventsForDays(calendarService, numberOfDays, false);
    }

    /**
     * KIM-474 (scale): variant that can wait out Google's rate limiting.
     *
     * <p>A delegated (Always On) sweep reads a mailbox per registered user, which is what provokes
     * a quota refusal in the first place; losing a mailbox to a limit Google was willing to say how
     * long to wait for is a pure loss. The consented path — every tenant that has NOT enabled Always
     * On — passes {@code false} and reaches Google through exactly the call it always did, with no
     * retry and no wrapper.
     *
     * @param calendarService  authenticated Google Calendar client
     * @param numberOfDays     number of future days to fetch events for
     * @param waitOutRateLimit true only on the delegated path
     * @return list of events within the requested range (may be empty, never null)
     */
    public List<Event> fetchEventsForDays(Calendar calendarService, long numberOfDays,
                                          boolean waitOutRateLimit) {
        LOGGER.info("Fetching Google Calendar events for the next {} days", numberOfDays);

        OffsetDateTime startOfRange = OffsetDateTime.now(ZoneOffset.UTC)
                .withHour(0).withMinute(0).withSecond(0).withNano(0);
        if (numberOfDays <= 0) {
            throw new IllegalArgumentException("numberOfDays must be positive");
        }
        OffsetDateTime endOfRange = startOfRange.plusDays(numberOfDays);

        LOGGER.info("Fetch window: {} to {} (UTC)", startOfRange.toInstant(), endOfRange.toInstant());

        return fetchAndFilterEvents(calendarService, startOfRange, endOfRange, waitOutRateLimit);
    }

    private List<Event> fetchAndFilterEvents(Calendar calendarService,
                                             OffsetDateTime startDateTime,
                                             OffsetDateTime endDateTime,
                                             boolean waitOutRateLimit) {
        List<Event> events = new ArrayList<>();
        try {
            // Use Instant.toString() (full RFC3339, always includes seconds) rather than
            // OffsetDateTime.toString(), which omits seconds when zero (e.g. a midnight start
            // becomes "...T00:00Z") and is rejected by Google's RFC3339 parser.
            DateTime timeMin = new DateTime(startDateTime.toInstant().toString());
            DateTime timeMax = new DateTime(endDateTime.toInstant().toString());

            String nextPageToken = null;
            do {
                final String pageToken = nextPageToken;
                Events result = read(waitOutRateLimit, "events.list",
                    () -> calendarService.events().list(PRIMARY_CALENDAR)
                            .setTimeMin(timeMin)
                            .setTimeMax(timeMax)
                            .setSingleEvents(true)
                            .setOrderBy("startTime")
                            .setShowDeleted(true)
                            .setPageToken(pageToken)
                            .execute());
                if (result.getItems() != null) {
                    events.addAll(result.getItems());
                }
                telemetryService.recordApiCall("google", "fetch_page", true);
                // An empty page can still have a continuation token.
                nextPageToken = result.getNextPageToken();
            } while (nextPageToken != null);
        } catch (IOException e) {
            LOGGER.error("Error fetching events from Google Calendar API: {}", e.getMessage(), e);
            telemetryService.recordApiCall("google", "fetch_page", false);
            // A partial snapshot must never be interpreted as deleted calendar events.
            throw new IllegalStateException("Could not fetch a complete Google Calendar snapshot", e);
        }

        LOGGER.info("Total events fetched: {}", events.size());
        LOGGER.info("Successfully received {} events from Google Calendar for processing", events.size());
        return events;
    }

    /**
     * Issues one page request, through the rate-limit backoff only on the delegated path.
     *
     * @param waitOutRateLimit whether this sweep may wait out a quota refusal
     * @param operation        short label for the throttle log line
     * @param page             the page request
     * @return the fetched page
     * @throws IOException from the underlying call, unwrapped
     */
    private Events read(boolean waitOutRateLimit, String operation,
                        GoogleThrottleBackoff.GoogleCall<Events> page) throws IOException {
        return waitOutRateLimit ? GoogleThrottleBackoff.execute(operation, page) : page.execute();
    }
}
