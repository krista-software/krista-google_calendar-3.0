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
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.ConferenceData;
import com.google.api.services.calendar.model.EntryPoint;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link GoogleEventToMeetingDtoMapper#mapWithCalendarOwner}.
 *
 * <p>These tests guard against the bug where webhook incremental sync events are
 * rejected by MA with {@code NO_REGISTERED_USER} when the authenticated Google
 * Calendar user is not listed as an organizer or attendee in the Google event data.
 *
 * <p>Root cause (from log 2026-06-04):
 * <ul>
 *   <li>requestedUserId = wsContact_1cf0d9c1-... (Krista user)</li>
 *   <li>clientGoogleAccount = rakesh.pethani@kristasw.com (registered MA user)</li>
 *   <li>primaryCalendarId = rocky.pethani@gmail.com (actual Google account)</li>
 *   <li>Webhook event "Dummy test 5" had 2 attendees, neither = rakesh.pethani@kristasw.com</li>
 *   <li>CA returned HTTP 400 NO_REGISTERED_USER</li>
 * </ul>
 *
 * Fix: inject calendarOwnerEmail into attendees when absent so MA finds the
 * registered user without false-positive D-05 drops.
 */
@DisplayName("GoogleEventToMeetingDtoMapper — mapWithCalendarOwner regression")
class GoogleEventToMeetingDtoMapperCalendarOwnerTest {

    private static final String OWNER_EMAIL = "rakesh.pethani@kristasw.com";
    private static final String OTHER_EMAIL = "colleague@example.com";
    private static final String ANOTHER_EMAIL = "another@example.com";

    private GoogleEventToMeetingDtoMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new GoogleEventToMeetingDtoMapper();
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private Event buildEventWithAttendees(String... attendeeEmails) {
        Event event = new Event();
        event.setId("evt-001");
        event.setSummary("Dummy test 5");
        event.setICalUID("ical-001");

        EventDateTime start = new EventDateTime().setDateTime(new DateTime("2026-06-10T10:00:00Z"));
        EventDateTime end = new EventDateTime().setDateTime(new DateTime("2026-06-10T11:00:00Z"));
        event.setStart(start);
        event.setEnd(end);

        // Add a Google Meet URL so the mapper doesn't drop the event
        ConferenceData conf = new ConferenceData();
        EntryPoint ep = new EntryPoint().setEntryPointType("video").setUri("https://meet.google.com/abc-xyz");
        conf.setEntryPoints(List.of(ep));
        event.setConferenceData(conf);

        if (attendeeEmails != null && attendeeEmails.length > 0) {
            List<EventAttendee> attendees = java.util.Arrays.stream(attendeeEmails)
                    .map(e -> new EventAttendee().setEmail(e))
                    .toList();
            event.setAttendees(attendees);
        }
        return event;
    }

    private Event buildEventWithOrganizer(String organizerEmail, String... attendeeEmails) {
        Event event = buildEventWithAttendees(attendeeEmails);
        Event.Organizer organizer = new Event.Organizer().setEmail(organizerEmail);
        event.setOrganizer(organizer);
        return event;
    }

    // ─── REGRESSION: owner absent from event data ───────────────────────────

    @Nested
    @DisplayName("REGRESSION — owner absent → must be injected")
    class OwnerAbsent {

        @Test
        @DisplayName("owner injected into attendees when not in attendee list")
        void ownerInjectedWhenAbsentFromAttendees() {
            Event event = buildEventWithAttendees(OTHER_EMAIL, ANOTHER_EMAIL);

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(event, OWNER_EMAIL);

            assertNotNull(dto);
            boolean ownerPresent = dto.getAttendees().stream()
                    .anyMatch(p -> OWNER_EMAIL.equalsIgnoreCase(p.getEmail()));
            assertTrue(ownerPresent,
                    "calendarOwnerEmail must appear in attendees when absent from Google event data");
        }

        @Test
        @DisplayName("owner injected when attendee list is empty")
        void ownerInjectedWhenAttendeeListEmpty() {
            Event event = buildEventWithAttendees(); // no attendees

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(event, OWNER_EMAIL);

            assertNotNull(dto);
            boolean ownerPresent = dto.getAttendees() != null && dto.getAttendees().stream()
                    .anyMatch(p -> OWNER_EMAIL.equalsIgnoreCase(p.getEmail()));
            assertTrue(ownerPresent,
                    "calendarOwnerEmail must appear in attendees even when event has no attendees");
        }

        @Test
        @DisplayName("owner injected when attendee list is null")
        void ownerInjectedWhenAttendeeListNull() {
            Event event = buildEventWithAttendees(); // will produce null/empty attendees list
            event.setAttendees(null);

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(event, OWNER_EMAIL);

            assertNotNull(dto);
            boolean ownerPresent = dto.getAttendees() != null && dto.getAttendees().stream()
                    .anyMatch(p -> OWNER_EMAIL.equalsIgnoreCase(p.getEmail()));
            assertTrue(ownerPresent);
        }

        @Test
        @DisplayName("existing attendees are preserved after owner injection")
        void existingAttendeesPreservedAfterInjection() {
            Event event = buildEventWithAttendees(OTHER_EMAIL, ANOTHER_EMAIL);

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(event, OWNER_EMAIL);

            assertNotNull(dto);
            List<String> emails = dto.getAttendees().stream()
                    .map(Person::getEmail)
                    .toList();
            assertTrue(emails.contains(OTHER_EMAIL),   "existing attendee must be preserved");
            assertTrue(emails.contains(ANOTHER_EMAIL), "existing attendee must be preserved");
            assertTrue(emails.stream().anyMatch(OWNER_EMAIL::equalsIgnoreCase), "owner must be present");
        }
    }

    // ─── NO injection when owner already present ────────────────────────────

    @Nested
    @DisplayName("owner already present → no duplicate injection")
    class OwnerAlreadyPresent {

        @Test
        @DisplayName("no duplicate when owner is already an attendee (exact match)")
        void noInjectWhenOwnerAlreadyAttendee() {
            Event event = buildEventWithAttendees(OWNER_EMAIL, OTHER_EMAIL);

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(event, OWNER_EMAIL);

            assertNotNull(dto);
            long ownerCount = dto.getAttendees().stream()
                    .filter(p -> OWNER_EMAIL.equalsIgnoreCase(p.getEmail()))
                    .count();
            assertEquals(1, ownerCount, "owner must appear exactly once — no duplicate injection");
        }

        @Test
        @DisplayName("no duplicate when owner is already an attendee (case-insensitive)")
        void noInjectWhenOwnerAlreadyAttendeeIgnoringCase() {
            Event event = buildEventWithAttendees(OWNER_EMAIL.toUpperCase(), OTHER_EMAIL);

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(event, OWNER_EMAIL);

            assertNotNull(dto);
            long ownerCount = dto.getAttendees().stream()
                    .filter(p -> OWNER_EMAIL.equalsIgnoreCase(p.getEmail()))
                    .count();
            assertEquals(1, ownerCount, "case-insensitive check must prevent duplicate injection");
        }

        @Test
        @DisplayName("no injection when owner is already the organizer")
        void noInjectWhenOwnerIsOrganizer() {
            Event event = buildEventWithOrganizer(OWNER_EMAIL, OTHER_EMAIL);

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(event, OWNER_EMAIL);

            assertNotNull(dto);
            // Owner in organizer: attendees should NOT be modified
            boolean ownerInAttendees = dto.getAttendees().stream()
                    .anyMatch(p -> OWNER_EMAIL.equalsIgnoreCase(p.getEmail()));
            assertFalse(ownerInAttendees,
                    "when owner is already the organizer, they must not be added to attendees");
        }
    }

    // ─── Null / blank calendarOwnerEmail ───────────────────────────────────

    @Nested
    @DisplayName("null / blank calendarOwnerEmail → graceful no-op")
    class NullOrBlankOwner {

        @Test
        @DisplayName("null calendarOwnerEmail falls back to plain map()")
        void nullOwnerFallsBackToPlainMap() {
            Event event = buildEventWithAttendees(OTHER_EMAIL);

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(event, null);

            assertNotNull(dto);
            assertEquals(1, dto.getAttendees().size(), "no injection when calendarOwnerEmail is null");
        }

        @Test
        @DisplayName("blank calendarOwnerEmail falls back to plain map()")
        void blankOwnerFallsBackToPlainMap() {
            Event event = buildEventWithAttendees(OTHER_EMAIL);

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(event, "   ");

            assertNotNull(dto);
            assertEquals(1, dto.getAttendees().size(), "no injection when calendarOwnerEmail is blank");
        }
    }

    // ─── Identity mismatch scenario from bug report ─────────────────────────

    @Nested
    @DisplayName("Identity mismatch scenario (wsContact / clientGoogleAccount / primaryCalendarId)")
    class IdentityMismatchScenario {

        private static final String WS_CONTACT_USER = "wsContact_1cf0d9c1-6fd9-40ad-8da8-30627683d21c";
        private static final String CLIENT_GOOGLE_ACCOUNT = "rakesh.pethani@kristasw.com";
        private static final String PRIMARY_CALENDAR_ID = "rocky.pethani@gmail.com";

        @Test
        @DisplayName("REGRESSION: webhook event without registered user is enriched with clientGoogleAccount")
        void webhookEventEnrichedWithClientGoogleAccount() {
            // "Dummy test 5" event as seen in the logs — attendees do NOT include the MA user
            Event event = buildEventWithAttendees("colleague1@example.com", "colleague2@example.com");

            // calendarOwnerEmail = gmailAttributes.getMailId() = rakesh.pethani@kristasw.com
            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(event, CLIENT_GOOGLE_ACCOUNT);

            assertNotNull(dto);
            assertTrue(dto.getAttendees().stream()
                            .anyMatch(p -> CLIENT_GOOGLE_ACCOUNT.equalsIgnoreCase(p.getEmail())),
                    "MA-registered email (clientGoogleAccount) must be injected so D-05 guard passes");
        }

        @Test
        @DisplayName("manual sync event with MA user already in attendees is not modified")
        void manualSyncEventWithRegisteredUserNotModified() {
            // An event that already has rakesh.pethani@kristasw.com — the batch sync success case
            Event event = buildEventWithAttendees(CLIENT_GOOGLE_ACCOUNT, "colleague@example.com");

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(event, CLIENT_GOOGLE_ACCOUNT);

            assertNotNull(dto);
            long count = dto.getAttendees().stream()
                    .filter(p -> CLIENT_GOOGLE_ACCOUNT.equalsIgnoreCase(p.getEmail()))
                    .count();
            assertEquals(1, count, "no duplicate injection for events already listing the MA user");
        }

        @Test
        @DisplayName("no token or secret values leak into the DTO")
        void noSecretLeakageIntoDto() {
            Event event = buildEventWithAttendees(OTHER_EMAIL);

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(event, CLIENT_GOOGLE_ACCOUNT);

            assertNotNull(dto);
            // The DTO must not carry wsContact IDs or internal identifiers
            dto.getAttendees().forEach(p -> {
                assertFalse(p.getEmail() != null && p.getEmail().startsWith("wsContact_"),
                        "wsContact IDs must not appear as attendee emails");
                assertNotEquals(WS_CONTACT_USER, p.getEmail(),
                        "wsContact user ID must not be exposed as an email");
            });
        }
    }

    // ─── S-14: the configured admin email is not an attendee ────────────────

    /**
     * S-14. The three-argument overload used to inject a SECOND address — the Google Workspace admin
     * or service account configured on the invoker — on the reasoning that Meeting Agent's gate is a
     * composite needing both an internal-domain participant and a licensed seat.
     *
     * <p>That reasoning did not match Meeting Agent's gate.
     * {@code WorkspaceQualificationPolicy.decide} is
     * {@code activeExtension && (seat || (tenantWideCapture && internalDomainMatch))}; the seat limb
     * carries no internal-domain check by explicit design (CON-002), and the domain limb needs
     * {@code tenantWideCapture}, which under the owner's SEAT capture scope (KIM-492 FR-012) is false
     * for every workspace. The condition the admin address was injected to satisfy is not evaluated
     * anywhere, while the mailbox owner satisfies the limb that is.
     *
     * <p>What the injection DID do was make that account an attendee of every meeting this extension
     * forwards. Meeting Agent's visibility rules and recipient scopes key on the attendee row, so the
     * admin account gained dashboard visibility of every captured meeting in the workspace and became
     * a recipient candidate for every ATTENDEES-scoped action. The Teams extension refuses the
     * equivalent injection; this one now matches.
     */
    @Nested
    @DisplayName("S-14 — the configured admin email is NOT injected")
    class ConfiguredAdminEmailIsNotInjected {

        private static final String ADMIN_EMAIL = "workspace-admin@kristasw.com";

        @Test
        @DisplayName("the admin address does not appear as an attendee")
        void adminEmailIsNotAnAttendee() {
            Event event = buildEventWithAttendees(OTHER_EMAIL);

            CreateMeetingRequestDto dto =
                    mapper.mapWithCalendarOwner(event, OWNER_EMAIL, ADMIN_EMAIL);

            assertNotNull(dto);
            assertTrue(dto.getAttendees().stream()
                            .noneMatch(p -> p.getEmail() != null
                                    && ADMIN_EMAIL.equalsIgnoreCase(p.getEmail())),
                    "The configured admin account is not a participant in this meeting. As an "
                            + "attendee row it gains dashboard visibility of every captured meeting "
                            + "and becomes a recipient candidate for ATTENDEES-scoped actions, "
                            + "without affecting Meeting Agent's gate at all. Attendees were: "
                            + dto.getAttendees().stream().map(Person::getEmail).toList());
        }

        /**
         * The half that must NOT change. Dropping the admin injection is only safe because the
         * mailbox owner is still injected — that is the address Meeting Agent's seat limb matches,
         * and it is what the developer guide requires of every calendar extension (§7.3). Remove it
         * and a seat invited through a distribution list arrives as an unregistered organizer plus a
         * group address, and the meeting is dropped.
         */
        @Test
        @DisplayName("the MAILBOX OWNER is still injected — that is the address the gate matches")
        void ownerIsStillInjected() {
            Event event = buildEventWithAttendees(OTHER_EMAIL);

            CreateMeetingRequestDto dto =
                    mapper.mapWithCalendarOwner(event, OWNER_EMAIL, ADMIN_EMAIL);

            assertNotNull(dto);
            assertTrue(dto.getAttendees().stream()
                            .anyMatch(p -> p.getEmail() != null
                                    && OWNER_EMAIL.equalsIgnoreCase(p.getEmail())),
                    "Without the mailbox owner, a seat invited through a distribution list reaches "
                            + "Meeting Agent with no registered participant and the meeting the "
                            + "extension deliberately fetched is dropped on arrival.");
        }

        @Test
        @DisplayName("passing an admin address produces exactly the same DTO as passing none")
        void threeArgAndTwoArgAgree() {
            // The parameter is kept and ignored rather than removed, so a caller that still passes
            // one is a no-op. This is the assertion that makes "ignored" mean ignored.
            CreateMeetingRequestDto withAdmin = mapper.mapWithCalendarOwner(
                    buildEventWithAttendees(OTHER_EMAIL), OWNER_EMAIL, ADMIN_EMAIL);
            CreateMeetingRequestDto withoutAdmin = mapper.mapWithCalendarOwner(
                    buildEventWithAttendees(OTHER_EMAIL), OWNER_EMAIL);

            assertNotNull(withAdmin);
            assertNotNull(withoutAdmin);
            assertEquals(withoutAdmin.getAttendees().stream().map(Person::getEmail).toList(),
                    withAdmin.getAttendees().stream().map(Person::getEmail).toList());
        }
    }
}
