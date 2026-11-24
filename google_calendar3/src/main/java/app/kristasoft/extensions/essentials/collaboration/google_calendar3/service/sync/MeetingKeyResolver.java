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

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.CaGateway;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.EsbCaGateway;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/**
 * Resolves the Meeting Agent meeting key for a cancelled Google Calendar event whose key this
 * extension did not record locally.
 *
 * <h2>This has never worked, and KIM-492 WP-E16 is what fixes it</h2>
 *
 * <p>Before KIM-492 this class issued {@code GET /api/v1/meetings/lookup?meetingUrl=…&startTime=…}.
 * <b>Meeting Agent has never served that route.</b> {@code MeetingResource} is mounted at
 * {@code /api/v1/meetings} and its only GET routes are the list and {@code /{meetingKey}}, so the
 * request matched {@code getMeeting} with {@code meetingKey="lookup"}, passed the non-empty key
 * validation, and came back 404 every single time. This class logged a WARN, returned null, and the
 * caller skipped the delete — so a cancelled Google meeting whose key was not in the local tracking
 * store was never removed from MA (spec §6 R14).
 *
 * <p><b>Release note:</b> the first sync after this change will delete meetings whose calendar
 * event disappeared months ago. That burst is the backlog draining, not a fault.
 *
 * <p>The ESB operation (A5) fixes it twice over: it exists, and it accepts {@code iCalUId} — the
 * stable identifier the HTTP query string had no parameter for. MA applies every supplied criterion
 * in one request, in the precedence {@code iCalUId} → {@code (meetingUrl, startTime)} →
 * {@code (meetingUrl, search)}, and returns the first non-empty match set.
 *
 * <p>There is no transport choice to make: {@link EsbCaGateway} is the only implementation, and the
 * 404-ing HTTP route described above no longer exists in this extension at all.
 */
@Service
public class MeetingKeyResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(MeetingKeyResolver.class);

    private final CalendarSyncTelemetryService telemetryService;
    private final EsbCaGateway caGateway;

    @Inject
    public MeetingKeyResolver(CalendarSyncTelemetryService telemetryService,
                              EsbCaGateway caGateway) {
        this.telemetryService = telemetryService;
        this.caGateway = caGateway;
    }

    /**
     * Resolves the MA-assigned meetingKey for a cancelled calendar event.
     *
     * <p>Every criterion the caller holds is supplied at once. Supplying more than one is not
     * belt-and-braces: MA walks them in a documented precedence and reports which rule fired, so a
     * weaker criterion is used only when the stronger one matched nothing.
     *
     * <p>{@code search} is deliberately NOT supplied. A title search is a series-cancellation tool;
     * running it for a single cancelled occurrence would risk deleting a sibling occurrence that is
     * still in the calendar.
     *
     * @param iCalUId    the calendar event's stable identifier; the strongest key, and the one the
     *                   HTTP route could not accept at all
     * @param meetingUrl the join URL
     * @param startTime  the occurrence's start (ISO 8601 UTC)
     * @param userEmail  scopes the search to one participant; may be null. Anything that is not an
     *                   email address is DROPPED here rather than sent — see
     *                   {@link #participantFilter(String)}
     * @return the meetingKey, or null when nothing matched
     */
    public String resolveMeetingKey(String iCalUId, String meetingUrl, String startTime,
                                    String userEmail) {
        if ((iCalUId == null || iCalUId.isBlank()) && (meetingUrl == null || meetingUrl.isBlank())) {
            LOGGER.warn("Cannot resolve a meeting key: neither iCalUId nor meetingUrl is available");
            return null;
        }

        CaGateway.MeetingKeyMatch match = caGateway.findMeetingKeys(new CaGateway.MeetingLookupCriteria(
                iCalUId, meetingUrl, startTime, null, participantFilter(userEmail)));

        if ("FAILED".equals(match.matchedBy())) {
            throw new IllegalStateException("Meeting Agent lookup failed; cancellation must be retried");
        }
        if (match.isEmpty()) {
            telemetryService.recordApiCall("ma", "lookup_meeting_key", false);
            return null;
        }

        if (match.meetingKeys().size() > 1) {
            throw new IllegalStateException("Ambiguous cancellation: Meeting Agent matched multiple meetings");
        }
        String meetingKey = match.firstKey();
        telemetryService.recordApiCall("ma", "lookup_meeting_key", true);
        return meetingKey;
    }

    /**
     * EF-12 — the last gate before a scan-set user id is used as Meeting Agent's participant filter.
     *
     * <p>Meeting Agent applies {@code userEmail} as a <b>hard</b> filter on every branch of its
     * lookup precedence ({@code ExtensionCalendarServiceImpl}), and a scan-set user id is not always
     * an email. On the consented path it can be an opaque {@code wsContact_<uuid>} client key, which
     * matches no attendee anywhere — so the whole lookup returned zero matches, the caller logged
     * "could not resolve a meetingKey" and skipped the delete. The cancellation fix that WP-E16
     * shipped was therefore <em>inert for every client user</em>: exactly the population whose keys
     * are not addresses. A null filter widens the search to the workspace, where {@code iCalUId}
     * (the strongest criterion, and workspace-scoped MA-side already) is doing the real work.
     *
     * <p>Written as a guard here, at the one place the field is populated, rather than at each call
     * site: the failure is silent — a lookup that finds nothing looks exactly like a meeting Meeting
     * Agent does not hold — so a caller that forgets costs a cancellation nobody notices.
     *
     * <p>The test is "contains an {@code @}" rather than "does not start with {@code wsContact}". The
     * store holds several key shapes ({@code email#suffix}, {@code wsContact_<uuid>},
     * {@code wsContact_<invoker>_<email>}) and the question that matters is not which prefix a key
     * carries but whether Meeting Agent could ever match it against an attendee address. Only an
     * address can.
     *
     * @param userId the scan-set user id, or a resolved calendar-owner address
     * @return the address to filter on, or null when the id is not an address
     */
    static String participantFilter(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        String trimmed = userId.trim();
        if (trimmed.indexOf('@') > 0) {
            return trimmed;
        }
        LOGGER.debug("Not scoping the meeting lookup to '{}': it is not an email address (client "
                + "keys such as {}_<uuid> match no attendee, so MA's hard userEmail filter would "
                + "return nothing). Looking up on iCalUId and meetingUrl alone.",
                trimmed, Constants.WS_CONTACT);
        return null;
    }
}
