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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook;

import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.CaGateway;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.EsbCaGateway;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model.CreateMeetingRequestDto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.TrackMeetingEventStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarSyncTelemetryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleEventIdentity;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleEventToMeetingDtoMapper;
import com.google.api.services.calendar.model.Event;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/**
 * Service for sending individual calendar events to MA service in response to webhook notifications.
 * Handles create, update, and delete operations for single events.
 *
 * <p>Aligned with teams-transcribe's CalendarEventToCAService pattern.</p>
 */
@Service
public class CalendarEventToMAService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarEventToMAService.class);

    private final Invoker invoker;
    private final GoogleEventToMeetingDtoMapper eventMapper;
    private final CalendarEventMappingStore eventMappingStore;
    private final TrackMeetingEventStore trackMeetingEventStore;
    private final CalendarSyncTelemetryService telemetryService;
    private final EsbCaGateway caGateway;

    @Inject
    public CalendarEventToMAService(Invoker invoker,
                                    GoogleEventToMeetingDtoMapper eventMapper,
                                    CalendarEventMappingStore eventMappingStore,
                                    TrackMeetingEventStore trackMeetingEventStore,
                                    CalendarSyncTelemetryService telemetryService,
                                    EsbCaGateway caGateway) {
        this.invoker = invoker;
        this.eventMapper = eventMapper;
        this.eventMappingStore = eventMappingStore;
        this.trackMeetingEventStore = trackMeetingEventStore;
        this.telemetryService = telemetryService;
        this.caGateway = caGateway;
    }

    /**
     * Sends a created or updated event to MA service.
     *
     * <p>Delegates to {@link #sendEventToMA(Event, String, String)} with no
     * calendar-owner email override (backwards-compatible overload used by
     * paths that do not have the owner email available).</p>
     *
     * @param event  the Google Calendar event
     * @param userId the user ID (wsContact or email) who owns this event
     */
    public void sendEventToMA(Event event, String userId) {
        sendEventToMA(event, userId, null, null);
    }

    /** Single-email overload (backwards-compatible). */
    public void sendEventToMA(Event event, String userId, String calendarOwnerEmail) {
        sendEventToMA(event, userId, calendarOwnerEmail, null);
    }

    /**
     * Sends a created or updated event to MA service, injecting the
     * calendar owner's email into the attendees when it is absent from the
     * Google event data.
     *
     * <p>MA's D-05 guard requires at least one registered user to appear in
     * the organizer or attendees fields. When an event is delivered via webhook
     * incremental sync the authenticated user (the calendar owner) may not be
     * listed as an attendee — for example when they subscribe to a shared
     * calendar owned by a different Google account. Without injecting their
     * email the event would be rejected with {@code NO_REGISTERED_USER} even
     * though the user is registered.</p>
     *
     * @param event              the Google Calendar event
     * @param userId             the user ID (wsContact or email) who owns this event
     * @param calendarOwnerEmail the email from {@code gmailAttributes.getMailId()};
     *                           null means no injection is attempted
     */
    /** Two-email overload satisfying both D-05 conditions. */
    public boolean sendEventToMA(Event event, String userId,
                               String calendarOwnerEmail, String configuredEmail) {
        if (event == null) {
            LOGGER.warn("Cannot send null event to MA");
            return true;
        }

        try {
            LOGGER.info("Sending event to MA: subject='{}', id={}", safe(event.getSummary()), safe(event.getId()));

            CreateMeetingRequestDto dto = stampInvokerId(
                    eventMapper.mapWithCalendarOwner(event, calendarOwnerEmail, configuredEmail));
            if (dto == null) {
                LOGGER.warn("Event {} could not be mapped to DTO. Skipping.", safe(event.getId()));
                return false;
            }

            // KIM-492 WP-E16 (A1): one gateway call, no transport branch here. The selector
            // decides; this call site does not know or care which one it got.
            CaGateway.UpsertOutcome outcome = caGateway.upsertMeeting(dto);

            if (outcome.acceptedByCa()) {
                LOGGER.info("Successfully sent event to MA: {}", safe(event.getSummary()));
                telemetryService.recordApiCall("ma", "webhook_create", true);
                storeMeetingKeyMapping(event, userId, outcome);
                return true;
            } else {
                // NO_REGISTERED_USER is MA's deliberate D-05 drop, not a fault: nobody on this
                // meeting has a Meeting Agent seat yet. It is reported as a failed call because
                // that is what the HTTP path did (MA answered 400), and because the tracking entry
                // must NOT be written — the meeting is re-offered on the next sweep once somebody
                // on it registers.
                LOGGER.error("Failed to send event to MA. errorCode={}, message={}",
                        outcome.errorCode(), outcome.errorMessage());
                telemetryService.recordApiCall("ma", "webhook_create", false);
                // A deliberate eligibility drop is retried by seat registration/batch sync.
                return outcome.status() == CaGateway.UpsertStatus.NO_REGISTERED_USER;
            }

        } catch (Exception e) {
            LOGGER.error("Error sending event to MA: {}", e.getMessage(), e);
            telemetryService.recordApiCall("ma", "webhook_create", false);
            return false;
        }
    }

    /**
     * Deletes an event from MA service by meeting key.
     *
     * @param meetingKey the MA service meeting key
     */
    public boolean deleteEventFromMA(String meetingKey) {
        if (meetingKey == null || meetingKey.isBlank()) {
            LOGGER.warn("Cannot delete event with null/blank meetingKey");
            return false;
        }

        try {
            LOGGER.info("Deleting event from MA: meetingKey={}", meetingKey);

            // KIM-492 WP-E16 (A3). "Not found" counts as success — the meeting is already gone,
            // which is the state the caller wanted.
            boolean accepted = caGateway.deleteMeeting(meetingKey, null, "calendar event deleted");

            if (accepted) {
                LOGGER.info("Successfully deleted meeting from MA: {}", meetingKey);
                telemetryService.recordApiCall("ma", "webhook_delete", true);
            } else {
                LOGGER.error("Failed to delete event from MA: meetingKey={}", meetingKey);
                telemetryService.recordApiCall("ma", "webhook_delete", false);
            }
            return accepted;

        } catch (Exception e) {
            LOGGER.error("Error deleting event from MA: {}", e.getMessage(), e);
            telemetryService.recordApiCall("ma", "webhook_delete", false);
            return false;
        }
    }

    /**
     * Records {@code eventId -> meetingKey} so a later deletion can find the meeting.
     *
     * <p>Takes the gateway's outcome rather than a raw response body: {@code meetingKey} and
     * {@code iCalUId} are fields on the contract reply, so there is no JSON to pick apart and no
     * transport-specific parsing left at this call site.
     */
    private void storeMeetingKeyMapping(Event event, String userId, CaGateway.UpsertOutcome outcome) {
        try {
            String meetingKey = outcome.meetingKey();
            if (meetingKey == null || meetingKey.isBlank()) {
                LOGGER.warn("MA accepted event {} but returned no meetingKey; not tracking it.",
                        safe(event.getId()));
                return;
            }

            // Store in event mapping store (eventId -> meetingKey)
            eventMappingStore.put(event.getId(), meetingKey);
            LOGGER.info("Stored mapping: eventId={} -> meetingKey={}", event.getId(), meetingKey);

            // Also track in the TrackMeetingEventStore for sync compatibility
            String startTime = null;
            if (event.getStart() != null && event.getStart().getDateTime() != null) {
                startTime = event.getStart().getDateTime().toStringRfc3339();
            }
            // Keep occurrence identity stable even if an older MA deployment omits iCalUId in its
            // response. Google's raw iCalUID is shared by every instance in a recurring series.
            String iCalUId = outcome.iCalUId() != null
                    ? outcome.iCalUId() : GoogleEventIdentity.meetingUid(event);
            trackMeetingEventStore.trackEvent(userId, event.getId(), meetingKey, startTime, iCalUId);
            trackMeetingEventStore.trackSeries(userId, event.getId(), event.getRecurringEventId());
        } catch (Exception e) {
            LOGGER.warn("Could not record the meetingKey mapping: {}", e.getMessage());
        }
    }

    /**
     * KIM-474 (FR-002): stamps this invoker's id on an outbound upsert so Meeting Agent can
     * attribute a captured meeting to the extension instance that reported it.
     *
     * <p>Never throws. The id is informational on Meeting Agent's side — never a capture gate — so
     * an invoker that cannot report its own id (removed mid-notification, which makes every call on
     * it throw {@link IllegalStateException}) must leave the field null rather than drop the
     * meeting.
     *
     * @param dto the mapped upsert, or null when mapping failed
     * @return the same instance, for call-site chaining
     */
    private CreateMeetingRequestDto stampInvokerId(CreateMeetingRequestDto dto) {
        if (dto == null) {
            return null;
        }
        try {
            dto.setInvokerId(invoker.getInvokerId());
        } catch (Exception e) {
            LOGGER.warn("Could not read invokerId for the meeting upsert; sending without it: {}", e.getMessage());
        }
        return dto;
    }

    private String safe(String value) {
        return value != null ? value : "<null>";
    }
}
