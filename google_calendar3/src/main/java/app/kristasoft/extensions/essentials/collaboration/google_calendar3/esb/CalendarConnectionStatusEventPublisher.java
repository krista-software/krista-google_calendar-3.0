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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.publishers.EventProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Publishes {@link CalendarConnectionStatusChangedProto} to CA Service so the
 * Conversation Agent can populate {@code UserDto.calendarSystems} and the
 * calendars API for the authenticated user.
 *
 * <p>Mirrors the KIP (Microsoft Teams) extension contract on
 * {@code feature/KIM-311-KIP}: events are published <strong>unconditionally</strong>
 * — there is no extension-side feature flag. CA gates consumption on its own side.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #publish} — always sends (subject only to required-field validation).
 *       Used for the auth-success registration, which must re-assert the calendar
 *       system on <em>every</em> authentication so a user who was already CONNECTED
 *       in our store still gets registered with CA.</li>
 *   <li>{@link #publishOnTransition} — sends only when the status actually changes.
 *       Used by token-failure paths so a broken token being retried does not
 *       republish {@code REAUTH_REQUIRED} on every failed refresh.</li>
 * </ul>
 *
 * <p>Queue: {@code events.calendar.connection.status.changed}
 *
 * <p><b>Renamed in KIM-492 WP-E15</b> from {@code krista.calendar.connection.status.changed}
 * (spec §3.3 rename row 3). This is an extension → Meeting Agent fire-and-forget event, so the
 * family is {@code events.*}; {@code krista.*} is not one of the five sanctioned families.
 *
 * <p><b>This publishes to the NEW name only — a single publish, not both.</b> Unlike the
 * auth-initiate CONSUMER, which binds two destinations because it cannot know which name the MA
 * opposite it produces to, a producer knows exactly where it is sending. Publishing to both would
 * double-deliver every status change to an MA consuming both names, which MA does from Stage 1.
 * The ordering constraint that makes the single publish safe is therefore a DEPLOY-ORDER one:
 * <b>MA's Stage-1 build (WP-E7), which consumes both names, must be deployed before this
 * extension jar.</b> Against an older MA this event is published into a destination nothing
 * consumes, and calendar connection status silently stops reaching MA.
 *
 * @since 3.0.2
 */
public class CalendarConnectionStatusEventPublisher extends EventProducer {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarConnectionStatusEventPublisher.class);

    /** CA-side API name on the listener handler. */
    private static final String API_NAME = "handleCalendarConnectionStatusChanged";

    /** Queue address — must match MA's listener registration. See the class javadoc on ordering. */
    public static final String QUEUE = "events.calendar.connection.status.changed";

    /** Calendar system identifier sent in every event (hardcoded for the Google module). */
    private static final String CALENDAR_SYSTEM = "GOOGLE";

    public CalendarConnectionStatusEventPublisher() {
    }

    /**
     * Publishes a status-changed event <strong>unconditionally</strong>, subject only to
     * required-field validation (workspaceId, email, next status). Used for the
     * auth-success registration so re-authenticating an already-connected user still
     * re-registers the calendar system with CA.
     *
     * @param workspaceId           workspace the user belongs to
     * @param email                 user email address
     * @param prior                 the previous connection status string (may be {@code null}; logged only)
     * @param next                  the new connection status string
     * @param errorCode             optional error code (may be {@code null})
     * @param errorMessage          optional error message (may be {@code null})
     * @param occurredAtEpochMillis transition time at the source (spec §A.4)
     * @return {@code true} if the event was sent; {@code false} if validation failed or the send errored
     */
    public boolean publish(String workspaceId, String email,
                           String prior, String next,
                           String errorCode, String errorMessage,
                           long occurredAtEpochMillis) {
        return publish(workspaceId, email, prior, next, errorCode, errorMessage, occurredAtEpochMillis, null);
    }

    /**
     * Same as the 7-arg {@link #publish}, carrying the optional disconnect-intent {@code reason}
     * (proto field 9). The {@code reason} is meaningful only when {@code next=DISCONNECTED}; CA
     * ignores it for every other status. {@code null} is the safe involuntary default.
     */
    public boolean publish(String workspaceId, String email,
                           String prior, String next,
                           String errorCode, String errorMessage,
                           long occurredAtEpochMillis, String reason) {
        if (workspaceId == null || workspaceId.isBlank()) {
            LOGGER.debug("Skipping status publish — workspaceId is missing");
            return false;
        }
        if (email == null || email.isBlank()) {
            LOGGER.debug("Skipping status publish — email is missing");
            return false;
        }
        if (next == null || next.isBlank()) {
            LOGGER.debug("Skipping status publish — next status is missing");
            return false;
        }

        String correlationId = UUID.randomUUID().toString();

        CalendarConnectionStatusChangedProto event = new CalendarConnectionStatusChangedProto();
        event.setWorkspaceId(workspaceId);
        event.setEmail(email);
        event.setCalendarSystem(CALENDAR_SYSTEM);
        event.setStatus(next);
        event.setLastErrorCode(errorCode);
        event.setLastErrorMessage(errorMessage);
        event.setOccurredAtEpochMillis(occurredAtEpochMillis);
        event.setCorrelationId(correlationId);
        event.setReason(reason);

        try {
            LOGGER.info("Publishing CalendarConnectionStatusChanged: ws={} email={} system={} {}->{} occurredAt={} correlationId={} reason={}",
                    workspaceId, email, CALENDAR_SYSTEM, prior, next, occurredAtEpochMillis, correlationId, reason);
            publishProtoEventSync(QUEUE, workspaceId, event, API_NAME);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to publish CalendarConnectionStatusChanged: ws={} email={} next={}",
                    workspaceId, email, next, e);
            return false;
        }
    }

    /**
     * Publishes only when the status actually transitions ({@code prior != next}).
     * Returns {@code false} (and skips the send) for no-op writes — used by token-failure
     * paths to avoid republishing on every failed refresh (spec §A.3).
     *
     * @return {@code true} if the event was sent; {@code false} if it was skipped
     */
    public boolean publishOnTransition(String workspaceId, String email,
                                        String prior, String next,
                                        String errorCode, String errorMessage,
                                        long occurredAtEpochMillis) {
        return publishOnTransition(workspaceId, email, prior, next, errorCode, errorMessage, occurredAtEpochMillis, null);
    }

    /**
     * Same as the 7-arg {@link #publishOnTransition}, carrying the optional disconnect-intent
     * {@code reason} (proto field 9). {@code null} is the safe involuntary default.
     */
    public boolean publishOnTransition(String workspaceId, String email,
                                        String prior, String next,
                                        String errorCode, String errorMessage,
                                        long occurredAtEpochMillis, String reason) {
        if (next != null && next.equals(prior)) {
            LOGGER.debug("Skipping status publish — no transition ({}={})", prior, next);
            return false;
        }
        return publish(workspaceId, email, prior, next, errorCode, errorMessage, occurredAtEpochMillis, reason);
    }

}
