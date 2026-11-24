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

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model.CreateMeetingRequestDto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model.Person;
import com.google.api.services.calendar.model.ConferenceData;
import com.google.api.services.calendar.model.EntryPoint;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GoogleEventToMeetingDtoMapper {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleEventToMeetingDtoMapper.class);
    // Emit a trailing 'Z' so the value is an unambiguous UTC instant. The times are already
    // converted to UTC (withZoneSameInstant(UTC)); without the 'Z' the string carries no zone
    // info, and CA's TimestampParser re-applies the DTO's `timezone` field (the ORIGINAL event
    // zone) — double-converting and shifting the meeting earlier (e.g. -5:30 for IST), which can
    // push a future meeting into the past and trigger MA's "end time in the past" 400 rejection.
    private static final DateTimeFormatter UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /** Matches an http(s) URL embedded in free text (event location or description body). */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>)\\]}]+");

    /**
     * WP-DL: resolves a Google Group attendee into the people it invited, or null when this mapper
     * was built without one.
     *
     * <p>Null is the unit-test construction ({@link #GoogleEventToMeetingDtoMapper()}) and means "no
     * expansion" — every existing test that maps an event keeps producing exactly the payload it did
     * before, and a mapper built by hand cannot silently acquire a Google credential. HK2 always uses
     * the injecting constructor, so both ingest paths get the real service.
     */
    private final GroupExpansionService groupExpansionService;

    /**
     * The mapper as it was before WP-DL: no group expansion.
     *
     * <p>Retained for callers and tests that map an event without an admin credential in play. HK2
     * prefers the {@code @Inject} constructor below, so nothing in production reaches this one.
     */
    public GoogleEventToMeetingDtoMapper() {
        this.groupExpansionService = null;
    }

    @Inject
    public GoogleEventToMeetingDtoMapper(GroupExpansionService groupExpansionService) {
        this.groupExpansionService = groupExpansionService;
    }

    /**
     * Maps a Google Calendar Event to a CreateMeetingRequestDto for MA, ensuring the MAILBOX OWNER —
     * the person whose calendar this event was read from — is present in the payload.
     *
     * <p>When a sweep or a webhook delivers an event on which the mailbox owner is not explicitly
     * listed as organizer or attendee — a seat invited through a distribution list is the ordinary
     * case — Meeting Agent sees an unregistered organizer and a group address, finds no registered
     * participant, and drops the meeting the extension deliberately fetched. Injecting the owner is
     * what Meeting Agent's developer guide requires of every calendar extension
     * ({@code docs/always-on-developer-guide.md} §7.3, "Mailbox-owner injection").
     *
     * <h2>S-14: the configured ADMIN email is no longer injected</h2>
     *
     * <p>This method used to inject a second address as well — {@code gmailAttributes.getMailId()},
     * the Google Workspace admin or service account configured on the invoker — on the stated
     * reasoning that Meeting Agent's gate is a composite needing BOTH an internal-domain participant
     * and a licensed seat, with the admin address supplying the first.
     *
     * <p><b>That reasoning does not match Meeting Agent's gate.</b>
     * {@code WorkspaceQualificationPolicy.decide} is
     * {@code activeExtension && (seat || (tenantWideCapture && internalDomainMatch))} — the seat limb
     * "carries no internal-domain check" by explicit design (CON-002), and the domain limb is reachable
     * only when {@code tenantWideCapture} is true, which under the owner's SEAT capture scope
     * (KIM-492 FR-012) is <b>false for every workspace</b>. So the internal-domain condition the admin
     * address was injected to satisfy is not evaluated on any live path, and the mailbox owner — a
     * registered, licensed seat — satisfies the limb that IS evaluated on its own.
     *
     * <p>What the injection did instead was add the admin account as an attendee of <b>every meeting
     * this extension forwards</b>. Meeting Agent's visibility rules and recipient scopes key on the
     * attendee row, so that account gained dashboard visibility of every captured meeting in the
     * workspace and became a recipient candidate for every ATTENDEES-scoped action — an access widening
     * nobody asked for, granted to a credential that is not a participant in anything. The Teams
     * extension refuses the equivalent injection ({@code MailboxOwnerAttendee}); this one now matches.
     *
     * <p>The parameter is kept on the signature and IGNORED rather than removed, so that a caller
     * that still passes an admin address is a compile-time no-op instead of a silent behaviour change
     * in a file nobody reopened. {@link #mapWithCalendarOwner(Event, String)} is the shape to use.
     *
     * @param event              the Google Calendar event
     * @param calendarOwnerEmail the primary calendar id — the actual Google account whose mailbox was
     *                           read. This is the address Meeting Agent's seat limb matches
     * @param configuredEmail    <b>IGNORED since S-14.</b> Formerly the configured workspace/admin
     *                           email; injecting it widened access without affecting the gate
     * @return the enriched DTO, or null if the event cannot be mapped
     */
    public CreateMeetingRequestDto mapWithCalendarOwner(Event event,
                                                        String calendarOwnerEmail,
                                                        String configuredEmail) {
        CreateMeetingRequestDto dto = map(event);
        if (dto == null) return null;

        injectIfAbsent(dto, event, calendarOwnerEmail, "primaryCalendarId");
        // S-14: configuredEmail is deliberately NOT injected. See the javadoc above — it satisfied a
        // condition Meeting Agent's live gate does not evaluate, and made the admin account an
        // attendee of every meeting in the workspace.
        // WP-DL: last, so the injected owner above is already present and a group member who is also
        // the calendar owner keeps their untagged row rather than being tagged as reached through a
        // list.
        appendGroupMembers(dto, event);
        return dto;
    }

    /**
     * Maps an event and injects the mailbox owner — the whole of what this method now does, and the
     * shape callers should use.
     *
     * @param event              the Google Calendar event
     * @param calendarOwnerEmail the primary calendar id whose mailbox was read
     * @return the enriched DTO, or null if the event cannot be mapped
     */
    public CreateMeetingRequestDto mapWithCalendarOwner(Event event, String calendarOwnerEmail) {
        return mapWithCalendarOwner(event, calendarOwnerEmail, null);
    }

    /**
     * Injects {@code email} into the DTO's attendees list if it is not already
     * present as organizer or attendee. Silently skips null / blank emails.
     */
    private void injectIfAbsent(CreateMeetingRequestDto dto, Event event,
                                String email, String label) {
        if (email == null || email.isBlank()) return;
        String emailLower = email.trim().toLowerCase();

        Person organizer = dto.getOrganizer();
        if (organizer != null && organizer.getEmail() != null
                && emailLower.equals(organizer.getEmail().toLowerCase())) {
            return; // already organizer
        }

        List<Person> existing = dto.getAttendees();
        boolean present = existing != null && existing.stream()
                .filter(Objects::nonNull)
                .anyMatch(p -> p.getEmail() != null
                        && emailLower.equals(p.getEmail().toLowerCase()));
        if (present) return;

        List<Person> enriched = new ArrayList<>(existing != null ? existing : List.of());
        enriched.add(new Person(email.trim(), null));
        dto.setAttendees(enriched);
        LOGGER.info("[GCAL-OWNER-INJECT] event={} label={} email={} injected into attendees",
                safe(event.getId()), label, email.trim());
    }

    /**
     * WP-DL: adds the members of every Google Group on the attendee list as attendees of their own,
     * each tagged with the group they came through.
     *
     * <p>The group address itself stays exactly where it was. Meeting Agent needs both: the group
     * row is what the calendar actually says, and the member rows are what its visibility rules,
     * recipient scopes and enrolment nudge can act on.
     *
     * <p>Three ordering rules, all of which exist to keep a real invitation from being relabelled as
     * a second-hand one:
     * <ul>
     *   <li>somebody already on the event — organizer or attendee — is left alone, so a person
     *       invited personally <em>and</em> via a list keeps {@code viaGroup} null;</li>
     *   <li>this runs after the owner injections above, so the calendar owner is never tagged;</li>
     *   <li>when two groups contain the same person, the first group on the attendee list wins.
     *       Any answer here is arbitrary; a stable one at least reads the same on every sweep.</li>
     * </ul>
     *
     * <p>Never throws and never removes anything: a failure to expand costs the tags, never the
     * meeting.
     */
    private void appendGroupMembers(CreateMeetingRequestDto dto, Event event) {
        if (groupExpansionService == null) {
            return;
        }

        List<Person> attendees = dto.getAttendees();
        List<String> addresses = new ArrayList<>();
        Set<String> alreadyPresent = new HashSet<>();
        if (attendees != null) {
            for (Person attendee : attendees) {
                if (attendee == null || attendee.getEmail() == null || attendee.getEmail().isBlank()) {
                    continue;
                }
                String lower = attendee.getEmail().trim().toLowerCase();
                addresses.add(lower);
                alreadyPresent.add(lower);
            }
        }
        Person organizer = dto.getOrganizer();
        if (organizer != null && organizer.getEmail() != null && !organizer.getEmail().isBlank()) {
            alreadyPresent.add(organizer.getEmail().trim().toLowerCase());
        }
        if (addresses.isEmpty()) {
            return;
        }

        Map<String, List<String>> expansions;
        try {
            expansions = groupExpansionService.expand(addresses);
        }
        catch (Exception cause) {
            // The service is written not to throw; this is the belt to that braces. An upsert that
            // reached MA before this feature existed must still reach it now.
            LOGGER.warn("[GCAL-GROUPS] event={} group expansion failed; sending the meeting with its "
                    + "original attendees: {}", safe(event.getId()), cause.getMessage());
            return;
        }
        if (expansions == null || expansions.isEmpty()) {
            return;
        }

        List<Person> enriched = new ArrayList<>(attendees != null ? attendees : List.of());
        int added = 0;
        for (Map.Entry<String, List<String>> expansion : expansions.entrySet()) {
            String group = expansion.getKey();
            for (String member : expansion.getValue()) {
                if (member == null || member.isBlank() || !alreadyPresent.add(member)) {
                    continue;
                }
                enriched.add(new Person(member, null, group));
                added++;
            }
        }
        if (added == 0) {
            return;
        }
        dto.setAttendees(enriched);
        LOGGER.info("[GCAL-GROUPS] event={} groups={} membersAdded={} — distribution-list members "
                        + "added as attendees tagged with the group they were invited through",
                safe(event.getId()), expansions.keySet(), added);
    }

    /**
     * Maps a Google Calendar Event to a CreateMeetingRequestDto for MA.
     *
     * @param event the Google Calendar event
     * @return the DTO, or null if the event cannot be mapped (e.g., invalid dates)
     */
    public CreateMeetingRequestDto map(Event event) {
        if (event == null) return null;

        String startTimeUtc;
        String endTimeUtc;
        String timezone = "UTC";

        try {
            EventDateTime startEventDt = event.getStart();
            EventDateTime endEventDt = event.getEnd();

            if (startEventDt == null || endEventDt == null) {
                LOGGER.warn("Skipping event {}: missing start or end time", event.getId());
                return null;
            }

            String startTimeZone = startEventDt.getTimeZone();
            ZonedDateTime startZoned = parseEventDateTime(startEventDt, startTimeZone);
            ZonedDateTime endZoned = parseEventDateTime(endEventDt, startTimeZone);

            ZonedDateTime startUtc = startZoned.withZoneSameInstant(ZoneOffset.UTC);
            ZonedDateTime endUtc = endZoned.withZoneSameInstant(ZoneOffset.UTC);

            startTimeUtc = startUtc.format(UTC_FORMATTER);
            endTimeUtc = endUtc.format(UTC_FORMATTER);

            if (startTimeZone != null && !startTimeZone.isBlank()) {
                timezone = startTimeZone;
            } else {
                // Google only populates start.timeZone when the organizer explicitly chose one; on
                // most events it is null and the zone lives in the dateTime's OFFSET instead. This
                // used to fall through to the literal "UTC", which Meeting Agent then stored as the
                // organizer's timezone and stamped onto every transcript line — so a Chicago meeting
                // came back reading "[13:31:00 UTC]" when it happened at 08:31.
                //
                // parseEventDateTime has already applied that offset, so the zone it produced is the
                // right answer. It is an offset ("-05:00") rather than a named zone, which is a real
                // limitation — it cannot survive a DST boundary the way "America/Chicago" would — but
                // it is the correct local time for THIS event, which is what a transcript timestamp
                // needs. Reading the calendar's own default zone would be better still and needs a
                // Calendar API call this mapper does not have.
                timezone = startZoned.getZone().getId();
                LOGGER.debug("Event {} carries no start.timeZone; using the offset {} from its "
                        + "dateTime rather than defaulting to UTC", event.getId(), timezone);
            }

            if (!endUtc.toInstant().isAfter(startUtc.toInstant())) {
                LOGGER.warn("Skipping event {}: end time must be after start time", event.getId());
                return null;
            }
        } catch (DateTimeParseException e) {
            LOGGER.warn("Skipping event {}: invalid date format: {}", event.getId(), e.getMessage());
            return null;
        } catch (Exception e) {
            LOGGER.warn("Skipping event {}: unexpected error parsing dates: {}", event.getId(), e.getMessage());
            return null;
        }

        Person organizer = null;
        if (event.getOrganizer() != null) {
            organizer = new Person(
                    event.getOrganizer().getEmail(),
                    event.getOrganizer().getDisplayName());
        }

        List<Person> attendees = event.getAttendees() == null ? List.of()
                : event.getAttendees().stream()
                .filter(Objects::nonNull)
                .map(a -> new Person(a.getEmail(), a.getDisplayName()))
                .toList();

        boolean recurring = event.getRecurringEventId() != null;
        String recurringSeriesId = event.getRecurringEventId();

        String meetingUrl = extractMeetingUrl(event);
        String calendarSystem = detectPlatformFromUrl(meetingUrl);

        String body = event.getDescription();
        if (body != null && body.length() > 500) {
            body = body.substring(0, 500);
        }

        LOGGER.debug("Mapped event {} -> title='{}', start={}, end={}, meetingUrl={}, platform={}",
                safe(event.getId()), safe(event.getSummary()), startTimeUtc, endTimeUtc, meetingUrl, calendarSystem);

        return new CreateMeetingRequestDto(
                safe(event.getSummary()),
                startTimeUtc,
                endTimeUtc,
                timezone,
                meetingUrl,
                organizer,
                attendees,
                calendarSystem,
                recurring,
                recurringSeriesId,
                body,
                GoogleEventIdentity.meetingUid(event));
    }

    private ZonedDateTime parseEventDateTime(EventDateTime eventDateTime, String fallbackTimeZone) {
        // Google Calendar events may use dateTime (for timed events) or date (for all-day events)
        if (eventDateTime.getDateTime() != null) {
            long millis = eventDateTime.getDateTime().getValue();
            int offsetMinutes = eventDateTime.getDateTime().getTimeZoneShift();
            ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetMinutes * 60);
            Instant instant = Instant.ofEpochMilli(millis);
            OffsetDateTime odt = OffsetDateTime.ofInstant(instant, offset);

            // Use the event's timezone if available for a proper ZonedDateTime
            String tz = eventDateTime.getTimeZone();
            if (tz == null) tz = fallbackTimeZone;
            if (tz != null && !tz.isBlank()) {
                try {
                    return odt.atZoneSameInstant(ZoneId.of(tz));
                } catch (Exception ignored) {
                    // fall through to offset-based
                }
            }
            return odt.toZonedDateTime();
        } else if (eventDateTime.getDate() != null) {
            // All-day event: use start of day in the specified timezone
            long millis = eventDateTime.getDate().getValue();
            String tz = eventDateTime.getTimeZone();
            if (tz == null) tz = fallbackTimeZone;
            ZoneId zoneId = (tz != null && !tz.isBlank()) ? ZoneId.of(tz) : ZoneOffset.UTC;
            LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
            return ZonedDateTime.of(ldt, zoneId);
        }

        throw new DateTimeParseException("No dateTime or date present in EventDateTime", "", 0);
    }

    /**
     * Extracts a meeting URL from the event, checking multiple sources in priority order.
     */
    public String extractMeetingUrl(Event event) {
        // 1. Check conferenceData entry points (most reliable)
        ConferenceData confData = event.getConferenceData();
        if (confData != null && confData.getEntryPoints() != null) {
            for (EntryPoint ep : confData.getEntryPoints()) {
                if ("video".equals(ep.getEntryPointType()) && ep.getUri() != null) {
                    return ep.getUri();
                }
            }
            // Fallback: any URI from entry points
            for (EntryPoint ep : confData.getEntryPoints()) {
                if (ep.getUri() != null && ep.getUri().startsWith("http")) {
                    return ep.getUri();
                }
            }
        }

        // 2. Check hangoutLink (Google Meet shortcut, deprecated but still populated)
        if (event.getHangoutLink() != null && !event.getHangoutLink().isBlank()) {
            return event.getHangoutLink();
        }

        // 3. Check location — a bare URL, or a URL embedded in the location text
        //    (e.g. "Room 3B - https://zoom.us/j/123").
        if (event.getLocation() != null && !event.getLocation().isBlank()) {
            String location = event.getLocation().trim();
            if (location.startsWith("http://") || location.startsWith("https://")) {
                return location;
            }
            String urlInLocation = firstMeetingUrlInText(location, false);
            if (urlInLocation != null) {
                return urlInLocation;
            }
        }

        // 4. Description/body — scan for a recognized meeting-platform link.
        //    Teams/Outlook invites synced into Google Calendar commonly carry the
        //    join link here (often as an <a href> inside HTML) rather than in
        //    conferenceData. Restricted to known platforms to avoid grabbing
        //    unrelated links from the body.
        String urlInDescription = firstMeetingUrlInText(event.getDescription(), true);
        if (urlInDescription != null) {
            return urlInDescription;
        }

        return null;
    }

    /** Evaluate the original invitation before injecting the discovery mailbox owner. */
    public boolean isEligibleCalendarEvent(Event event) {
        if (event == null) return false;
        String url = extractMeetingUrl(event);
        if (url != null && !url.isBlank()) return true;
        String organizer = event.getOrganizer() == null ? null : event.getOrganizer().getEmail();
        return organizer != null && !organizer.isBlank() && event.getAttendees() != null
                && event.getAttendees().stream().filter(Objects::nonNull)
                .anyMatch(a -> a.getEmail() != null && a.getEmail().contains("@")
                        && !a.getEmail().equalsIgnoreCase(organizer)
                        && !Boolean.TRUE.equals(a.getResource()));
    }

    /**
     * Finds the first meeting URL in free text. A URL matching a known meeting
     * platform (Teams/Zoom/Meet/Webex/GoTo) is always preferred. When
     * {@code knownPlatformOnly} is {@code false}, the first http(s) URL found is
     * returned as a fallback; when {@code true}, returns {@code null} unless a
     * known-platform URL is present (used for the free-form description body to
     * avoid false positives).
     */
    private String firstMeetingUrlInText(String text, boolean knownPlatformOnly) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        String firstUrl = null;
        while (matcher.find()) {
            String url = cleanUrl(matcher.group());
            if (url.isEmpty()) {
                continue;
            }
            if (isKnownMeetingPlatform(url)) {
                return url;
            }
            if (firstUrl == null) {
                firstUrl = url;
            }
        }
        return knownPlatformOnly ? null : firstUrl;
    }

    /** Unescapes HTML ampersands and strips trailing punctuation from an extracted URL. */
    private static String cleanUrl(String url) {
        String cleaned = url.replace("&amp;", "&");
        return cleaned.replaceAll("[.,;:!?)\\]}>'\"]+$", "");
    }

    private static boolean isKnownMeetingPlatform(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase();
        return u.contains("meet.google.com")
                || u.contains("zoom.us") || u.contains("zoom.com")
                || u.contains("teams.microsoft.com") || u.contains("teams.live.com")
                || u.contains("webex.com")
                || u.contains("gotomeeting.com") || u.contains("gotomeet.me");
    }

    /**
     * Detects the meeting platform from the URL pattern.
     */
    public String detectPlatformFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return "google"; // Default for Google Calendar events
        }

        String lowerUrl = url.toLowerCase();

        if (lowerUrl.contains("meet.google.com")) {
            return "google";
        } else if (lowerUrl.contains("zoom.us") || lowerUrl.contains("zoom.com")) {
            return "zoom";
        } else if (lowerUrl.contains("teams.microsoft.com") || lowerUrl.contains("teams.live.com")) {
            return "outlook";
        } else if (lowerUrl.contains("webex.com")) {
            return "WEBEX";
        } else if (lowerUrl.contains("gotomeeting.com") || lowerUrl.contains("gotomeet.me")) {
            return "GOTO_MEETING";
        }

        return "google";
    }

    private String safe(String value) {
        return value != null ? value : "<null>";
    }
}
