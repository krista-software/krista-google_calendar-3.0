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
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WP-DL — a person invited through a distribution list reaches Meeting Agent as an attendee of their
 * own, tagged with the group they came through.
 *
 * <p>The tag is what makes the difference between "the calendar says engineering@acme.example was
 * invited" and "these fourteen people were invited, via engineering@acme.example". Meeting Agent's
 * dashboard visibility, its {@code INTERNAL_ONLY} / {@code MEETING_AGENT_USERS} recipient scopes and
 * its enrolment nudge all key on explicit attendee rows, so without the expansion a DL member cannot
 * see the meeting and does not receive its report — while the tag keeps the report able to say they
 * were reached through a list rather than named personally.
 *
 * <p>Assertions are made on the serialised JSON wherever the wire contract is what matters: a field
 * set on the object but dropped by Gson would look correct here and be absent at Meeting Agent.
 */
@DisplayName("Distribution-list attendees carry viaGroup (WP-DL)")
class ViaGroupAttendeeTest {

    private static final String GROUP = "engineering@acme.example";

    private final Gson gson = new Gson();

    private static Event eventWithAttendees(String... attendeeEmails) {
        long start = 1_900_000_000_000L;
        Event event = new Event()
                .setId("evt-1")
                .setSummary("Standup")
                .setICalUID("ical-1")
                .setHangoutLink("https://meet.google.com/abc-defg-hij")
                .setStart(new EventDateTime().setDateTime(new DateTime(start)))
                .setEnd(new EventDateTime().setDateTime(new DateTime(start + 3_600_000L)));
        event.setOrganizer(new Event.Organizer().setEmail("organizer@acme.example"));
        event.setAttendees(Arrays.stream(attendeeEmails)
                .map(email -> new EventAttendee().setEmail(email))
                .toList());
        return event;
    }

    private static GoogleEventToMeetingDtoMapper mapperExpanding(Map<String, List<String>> expansions) {
        GroupExpansionService expansion = mock(GroupExpansionService.class);
        when(expansion.expand(any())).thenReturn(expansions);
        return new GoogleEventToMeetingDtoMapper(expansion);
    }

    private static Optional<Person> attendee(CreateMeetingRequestDto dto, String email) {
        return dto.getAttendees().stream()
                .filter(Objects::nonNull)
                .filter(person -> email.equalsIgnoreCase(person.getEmail()))
                .findFirst();
    }

    // ── tagging ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("The tag")
    class Tagging {

        @Test
        @DisplayName("Members are added as attendees tagged with the group")
        void membersAreTagged() {
            GoogleEventToMeetingDtoMapper mapper = mapperExpanding(
                    Map.of(GROUP, List.of("alice@acme.example", "bob@acme.example")));

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(
                    eventWithAttendees(GROUP), null, null);

            assertEquals(GROUP, attendee(dto, "alice@acme.example").orElseThrow().getViaGroup());
            assertEquals(GROUP, attendee(dto, "bob@acme.example").orElseThrow().getViaGroup());
        }

        /**
         * Meeting Agent needs both rows. The group row is what the calendar actually says; the member
         * rows are what its visibility rules can act on. Dropping the group would rewrite history.
         */
        @Test
        @DisplayName("The group address itself stays in the attendee list")
        void groupAddressIsKept() {
            GoogleEventToMeetingDtoMapper mapper =
                    mapperExpanding(Map.of(GROUP, List.of("alice@acme.example")));

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(
                    eventWithAttendees(GROUP), null, null);

            assertTrue(attendee(dto, GROUP).isPresent());
            assertNull(attendee(dto, GROUP).orElseThrow().getViaGroup(),
                    "the list was invited directly; it did not reach itself through itself");
        }

        /**
         * The rule that keeps a real invitation from being demoted. Someone named on the invitation
         * AND in the list was invited personally, and Meeting Agent must not report them as reached
         * second-hand.
         */
        @Test
        @DisplayName("A directly-invited person keeps viaGroup null, even if the group also lists them")
        void directAttendeeKeepsNullTag() {
            GoogleEventToMeetingDtoMapper mapper = mapperExpanding(
                    Map.of(GROUP, List.of("alice@acme.example", "bob@acme.example")));

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(
                    eventWithAttendees(GROUP, "alice@acme.example"), null, null);

            assertNull(attendee(dto, "alice@acme.example").orElseThrow().getViaGroup());
            assertEquals(GROUP, attendee(dto, "bob@acme.example").orElseThrow().getViaGroup());
            assertEquals(1, dto.getAttendees().stream()
                            .filter(p -> "alice@acme.example".equals(p.getEmail())).count(),
                    "and they appear exactly once");
        }

        @Test
        @DisplayName("The organizer is never tagged, even when the group contains them")
        void organizerIsNeverTagged() {
            GoogleEventToMeetingDtoMapper mapper = mapperExpanding(
                    Map.of(GROUP, List.of("organizer@acme.example", "alice@acme.example")));

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(
                    eventWithAttendees(GROUP), null, null);

            assertNull(dto.getOrganizer().getViaGroup());
            assertTrue(attendee(dto, "organizer@acme.example").isEmpty(),
                    "the organizer is already on the meeting; adding them again as a DL member "
                            + "would double-count them and imply they were not named personally");
        }

        /**
         * The injections run first precisely so this holds: the mailbox whose calendar produced the
         * meeting was read directly, and must not be reported as reached through a list.
         */
        @Test
        @DisplayName("The injected calendar owner is not tagged")
        void injectedOwnerIsNotTagged() {
            GoogleEventToMeetingDtoMapper mapper = mapperExpanding(
                    Map.of(GROUP, List.of("owner@acme.example", "alice@acme.example")));

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(
                    eventWithAttendees(GROUP), "owner@acme.example", null);

            assertNull(attendee(dto, "owner@acme.example").orElseThrow().getViaGroup());
            assertEquals(GROUP, attendee(dto, "alice@acme.example").orElseThrow().getViaGroup());
        }

        @Test
        @DisplayName("Someone in two groups is tagged with the first, and appears once")
        void firstGroupWins() {
            GoogleEventToMeetingDtoMapper mapper = mapperExpanding(new java.util.LinkedHashMap<>(
                    Map.of(GROUP, List.of("alice@acme.example"))) {{
                put("design@acme.example", List.of("alice@acme.example"));
            }});

            CreateMeetingRequestDto dto = mapper.mapWithCalendarOwner(
                    eventWithAttendees(GROUP, "design@acme.example"), null, null);

            assertEquals(1, dto.getAttendees().stream()
                    .filter(p -> "alice@acme.example".equals(p.getEmail())).count());
            assertEquals(GROUP, attendee(dto, "alice@acme.example").orElseThrow().getViaGroup());
        }
    }

    // ── the wire ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("On the wire")
    class Wire {

        @Test
        @DisplayName("viaGroup reaches Meeting Agent as a JSON string, not merely on the DTO")
        void tagSurvivesSerialisation() {
            GoogleEventToMeetingDtoMapper mapper =
                    mapperExpanding(Map.of(GROUP, List.of("alice@acme.example")));

            String body = gson.toJson(mapper.mapWithCalendarOwner(eventWithAttendees(GROUP), null, null));

            assertTrue(body.contains("\"email\":\"alice@acme.example\""), body);
            assertTrue(body.contains("\"viaGroup\":\"" + GROUP + "\""), body);
        }

        /**
         * The compatibility guarantee. A meeting with no distribution list must serialise exactly as
         * it did before WP-DL — Gson omits nulls — so an older Meeting Agent, and every existing
         * assertion about this payload, sees no change at all.
         */
        @Test
        @DisplayName("A meeting with no group carries no viaGroup key anywhere")
        void untaggedPayloadIsUnchanged() {
            GoogleEventToMeetingDtoMapper mapper = mapperExpanding(Map.of());

            String body = gson.toJson(mapper.mapWithCalendarOwner(
                    eventWithAttendees("alice@acme.example"), null, null));

            assertFalse(body.contains("viaGroup"),
                    "an ordinary meeting's payload must be byte-identical to the previous release: "
                            + body);
        }

        @Test
        @DisplayName("A mapper built without the expansion service maps exactly as before")
        void noExpansionServiceIsInert() {
            CreateMeetingRequestDto dto = new GoogleEventToMeetingDtoMapper()
                    .mapWithCalendarOwner(eventWithAttendees(GROUP), null, null);

            assertNotNull(dto);
            assertEquals(1, dto.getAttendees().size());
            assertFalse(gson.toJson(dto).contains("viaGroup"));
        }
    }

    // ── failure ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("When expansion fails")
    class Failure {

        /**
         * The upsert is the product; the tag is an enhancement to it. A meeting that reached Meeting
         * Agent before this feature existed must still reach it now, whatever the Admin SDK does.
         */
        @Test
        @DisplayName("A throwing expansion service costs the tags, never the meeting")
        void expansionFailureDoesNotDropTheMeeting() {
            GroupExpansionService expansion = mock(GroupExpansionService.class);
            when(expansion.expand(any())).thenThrow(new IllegalStateException("Admin SDK exploded"));

            CreateMeetingRequestDto dto = new GoogleEventToMeetingDtoMapper(expansion)
                    .mapWithCalendarOwner(eventWithAttendees(GROUP), null, null);

            assertNotNull(dto);
            assertEquals("ical-1", dto.getiCalUId());
            assertEquals(1, dto.getAttendees().size());
        }

        @Test
        @DisplayName("A null or empty expansion leaves the attendee list untouched")
        void emptyExpansionChangesNothing() {
            GroupExpansionService expansion = mock(GroupExpansionService.class);
            when(expansion.expand(any())).thenReturn(null);

            CreateMeetingRequestDto dto = new GoogleEventToMeetingDtoMapper(expansion)
                    .mapWithCalendarOwner(eventWithAttendees(GROUP), null, null);

            assertEquals(1, dto.getAttendees().size());
        }
    }

    // ── both ingest paths ─────────────────────────────────────────────────

    /**
     * Neither ingest entry point can be driven in a unit test — the batch sync needs a live Google
     * client and the webhook path needs the full Jersey stack (see {@code UpsertInvokerIdTest} for
     * the same constraint). The guard therefore comes from the other side: BOTH paths must reach
     * Meeting Agent through {@code mapWithCalendarOwner}, which is the method the expansion hangs
     * off, and neither may map an event any other way.
     *
     * <p>Adding a third ingest path that calls {@code map(...)} directly would be silent — the
     * meetings would arrive with the group address as their only attendee, exactly as before the
     * feature, and nobody would know why one path tagged and another did not.
     */
    @Test
    @DisplayName("Both the sync and the webhook path map through the tagging method")
    void bothIngestPathsAreTagged() throws IOException {
        Path mainSources = Path.of("src/main/java");
        assertTrue(Files.isDirectory(mainSources),
                "Expected to run from the module directory; got " + Path.of(".").toAbsolutePath());

        Path sync = mainSources.resolve("app/kristasoft/extensions/essentials/collaboration/"
                + "google_calendar3/service/sync/SendEventsToMAService.java");
        Path webhook = mainSources.resolve("app/kristasoft/extensions/essentials/collaboration/"
                + "google_calendar3/service/webhook/CalendarEventToMAService.java");

        for (Path path : List.of(sync, webhook)) {
            String source = Files.readString(path);
            assertTrue(source.contains("mapWithCalendarOwner("),
                    path.getFileName() + " must map through mapWithCalendarOwner, which is where "
                            + "distribution-list members are added and tagged");
            assertFalse(source.contains("eventMapper.map("),
                    path.getFileName() + " maps an event directly through map(...), bypassing the "
                            + "group expansion — that path would send a distribution list as its own "
                            + "only attendee while the other path expanded it");
        }
    }

    /**
     * The expansion appends to the attendee list; it must run after the owner injection, or the
     * calendar owner could be added a second time as a tagged DL member. Ordering inside one method
     * is not something a behavioural test can pin without a live client, so it is pinned here.
     *
     * <p>Re-anchored for S-14: the anchor used to be the SECOND injection,
     * {@code injectIfAbsent(dto, event, configuredEmail…)}, which no longer exists — injecting the
     * configured admin address made that account an attendee of every meeting in the workspace while
     * satisfying a Meeting Agent condition that is not evaluated on any live path. The ordering rule
     * is unchanged and now anchors on the one injection that remains.
     */
    @Test
    @DisplayName("Expansion runs after the owner injection, not before")
    void expansionRunsLast() throws IOException {
        String mapper = Files.readString(Path.of("src/main/java/app/kristasoft/extensions/essentials/"
                + "collaboration/google_calendar3/service/sync/GoogleEventToMeetingDtoMapper.java"));

        int ownerInjection = mapper.indexOf("injectIfAbsent(dto, event, calendarOwnerEmail");
        int expansion = mapper.indexOf("appendGroupMembers(dto, event)");

        assertTrue(ownerInjection > 0 && expansion > 0, "both steps must still be present");
        assertTrue(expansion > ownerInjection,
                "appendGroupMembers must run after the owner injection, so the calendar owner is "
                        + "already an attendee and is never re-added as a tagged group member");
    }
}
