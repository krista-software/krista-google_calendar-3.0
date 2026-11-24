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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service;

import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model.CreateMeetingRequestDto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.TrackMeetingEventStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarSyncTelemetryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarEventFetcher;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleEventToMeetingDtoMapper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.MeetingKeyResolver;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.SendEventsToMAService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarEventMappingStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarEventToMAService;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.testutil.TestCaGateway;

/**
 * KIM-474 (FR-002) — every meeting upsert carries the reporting invoker's id.
 *
 * <p>Meeting Agent uses it to attribute a captured meeting to the extension instance that reported
 * it, and to warn when a workspace is being fed by an invoker other than the one registered for it.
 * It is never a capture gate, which is precisely why it must never be allowed to fail an upsert.
 *
 * <p>This extension has two upsert paths — the Google push-notification (webhook) path and the
 * batch calendar sync — and both are covered the same way: the service's own stamping helper and
 * its own {@link Gson} produce the payload, and the assertion is made on the serialised JSON rather
 * than on the DTO, because a field set on the object but dropped by Gson would look correct and be
 * absent on the wire. Neither entry point can be driven directly in a unit test (see
 * {@link WebhookPath} and {@link BatchPath}), so the structural guard at the bottom of this class is
 * what pins the stamp to every call site.
 */
@DisplayName("Meeting upsert carries invokerId (KIM-474 FR-002)")
class UpsertInvokerIdTest {

    private static final String INVOKER_ID = "INV_google_calendar";
    private static final String WORKSPACE_ID = "ws_google_calendar";

    private final Gson gson = new Gson();

    private static Event meetingEvent() {
        long start = 1_900_000_000_000L;
        return new Event()
                .setId("evt-1")
                .setSummary("Standup")
                .setICalUID("ical-1")
                .setHangoutLink("https://meet.google.com/abc-defg-hij")
                .setStart(new EventDateTime().setDateTime(new DateTime(start)))
                .setEnd(new EventDateTime().setDateTime(new DateTime(start + 3_600_000L)));
    }

    private static Invoker invokerReporting(String invokerId, String serviceUrl) {
        Invoker invoker = mock(Invoker.class);
        when(invoker.getInvokerId()).thenReturn(invokerId);
        when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(invoker.getAttributes()).thenReturn(Map.of(
                Constants.SERVICE_URL, serviceUrl,
                Constants.SERVICE_API_KEY, "ma-api-key-test"));
        return invoker;
    }

    // ── webhook path ──────────────────────────────────────────────────────

    /**
     * Exercised through the service's stamping helper and Gson rather than by driving
     * {@code sendEventToMA} against a local HTTP server: the JAX-RS client this extension posts
     * with cannot bootstrap its HK2 injection manager on the module's TEST classpath (it works in
     * the invoker container, which carries the full Jersey stack), and adding runtime plumbing to
     * the test configuration purely to observe a JSON body would buy nothing this does not already
     * prove. The body asserted here is byte-for-byte what {@code sendEventToMA} serialises — it
     * uses the same DTO and the same {@link Gson} — and the structural guard at the bottom of this
     * class is what pins the stamp to the call site.
     */
    @Nested
    @DisplayName("Google push-notification path (POST /api/v1/meetings)")
    class WebhookPath {

        private CreateMeetingRequestDto upsertPayload(Invoker invoker) throws Exception {
            CalendarEventToMAService service = new CalendarEventToMAService(invoker,
                    new GoogleEventToMeetingDtoMapper(),
                    mock(CalendarEventMappingStore.class),
                    mock(TrackMeetingEventStore.class),
                    mock(CalendarSyncTelemetryService.class),
                    TestCaGateway.unusedGateway());

            GoogleEventToMeetingDtoMapper mapper = new GoogleEventToMeetingDtoMapper();
            CreateMeetingRequestDto mapped = mapper.mapWithCalendarOwner(
                    meetingEvent(), "owner@example.com", "admin@acme.com");

            Method stamper = CalendarEventToMAService.class
                    .getDeclaredMethod("stampInvokerId", CreateMeetingRequestDto.class);
            stamper.setAccessible(true);
            return (CreateMeetingRequestDto) stamper.invoke(service, mapped);
        }

        @Test
        @DisplayName("invokerId reaches MA on the wire, not merely on the DTO")
        void invokerIdIsOnTheWire() throws Exception {
            String body = gson.toJson(upsertPayload(invokerReporting(INVOKER_ID, "https://ma.example")));

            assertTrue(body.contains("\"invokerId\":\"" + INVOKER_ID + "\""),
                    "The upsert must carry the reporting invoker's id. Got: " + body);
        }

        @Test
        @DisplayName("The rest of the payload is unchanged by the new field")
        void payloadOtherwiseUnchanged() throws Exception {
            String body = gson.toJson(upsertPayload(invokerReporting(INVOKER_ID, "https://ma.example")));

            assertTrue(body.contains("\"iCalUId\":\"ical-1\""), body);
            assertTrue(body.contains("\"title\":\"Standup\""), body);
            assertTrue(body.contains("\"meetingUrl\":\"https://meet.google.com/abc-defg-hij\""), body);
        }

        /**
         * A removed invoker throws on every call. The id is informational, so losing it must cost
         * the field — never the meeting.
         */
        @Test
        @DisplayName("An unreadable invoker id still upserts the meeting, just without the field")
        void unreadableInvokerIdStillUpserts() throws Exception {
            Invoker removed = mock(Invoker.class);
            when(removed.getInvokerId()).thenThrow(new IllegalStateException("invoker was removed"));

            CreateMeetingRequestDto dto = upsertPayload(removed);

            assertNotNull(dto, "The meeting must still be upserted without the informational id");
            assertNull(dto.getInvokerId());

            String body = gson.toJson(dto);
            assertTrue(body.contains("\"iCalUId\":\"ical-1\""), body);
            assertTrue(!body.contains("invokerId"),
                    "Gson drops nulls; MA reads an absent field as 'not reported'. Got: " + body);
        }
    }

    // ── batch sync path ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Batch calendar sync (POST /api/v1/meetings/batch)")
    class BatchPath {

        private CreateMeetingRequestDto stamp(Invoker invoker, CreateMeetingRequestDto dto) throws Exception {
            SendEventsToMAService service = new SendEventsToMAService(
                    mock(GoogleProviderFactory.class),
                    mock(GoogleCalendarEventFetcher.class),
                    new GoogleEventToMeetingDtoMapper(),
                    invoker,
                    mock(MeetingKeyResolver.class),
                    mock(TrackMeetingEventStore.class),
                    mock(CalendarSyncTelemetryService.class),
                    mock(UserKeyIndexStore.class),
                    mock(CalendarDiscoveryService.class),
                    TestCaGateway.unusedGateway());

            Method stamper = SendEventsToMAService.class
                    .getDeclaredMethod("stampInvokerId", CreateMeetingRequestDto.class);
            stamper.setAccessible(true);
            return (CreateMeetingRequestDto) stamper.invoke(service, dto);
        }

        @Test
        @DisplayName("Stamps invokerId on a mapped meeting")
        void stampsInvokerId() throws Exception {
            CreateMeetingRequestDto mapped = new GoogleEventToMeetingDtoMapper().map(meetingEvent());
            assertNotNull(mapped);

            CreateMeetingRequestDto stamped = stamp(invokerReporting(INVOKER_ID, "https://ma.example"), mapped);

            assertEquals(INVOKER_ID, stamped.getInvokerId());
            assertTrue(gson.toJson(stamped).contains("\"invokerId\":\"" + INVOKER_ID + "\""),
                    "The field must survive serialisation, not just be set on the DTO");
        }

        @Test
        @DisplayName("A meeting that could not be mapped stays null — no NPE on the stamp")
        void nullDtoStaysNull() throws Exception {
            assertNull(stamp(invokerReporting(INVOKER_ID, "https://ma.example"), null));
        }

        @Test
        @DisplayName("An unreadable invoker id degrades to null rather than dropping the meeting")
        void unreadableInvokerIdDegrades() throws Exception {
            Invoker removed = mock(Invoker.class);
            when(removed.getInvokerId()).thenThrow(new IllegalStateException("invoker was removed"));
            CreateMeetingRequestDto mapped = new GoogleEventToMeetingDtoMapper().map(meetingEvent());

            CreateMeetingRequestDto stamped = stamp(removed, mapped);

            assertNotNull(stamped, "The meeting must still be upserted without the informational id");
            assertNull(stamped.getInvokerId());
            assertEquals("Standup", stamped.getTitle());
        }
    }

    // ── structural guard ──────────────────────────────────────────────────

    /**
     * The batch sync's own entry point needs a live Google client, so its stamping call site cannot
     * be driven cheaply. This guard covers it from the other side: every place that maps a Google
     * event into an upsert DTO must hand the result straight to {@code stampInvokerId}. Adding a
     * third upsert path and forgetting the stamp is silent — MA simply records the meeting with no
     * reporting invoker — so the guard fails the build instead.
     */
    @Test
    @DisplayName("No mapping call site can produce an unstamped upsert")
    void everyMappingCallSiteIsStamped() throws IOException {
        Path mainSources = Path.of("src/main/java");
        assertTrue(Files.isDirectory(mainSources),
                "Expected to run from the module directory; got " + Path.of(".").toAbsolutePath());

        List<String> unstamped = new ArrayList<>();

        try (Stream<Path> files = Files.walk(mainSources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (file.getFileName().toString().equals("GoogleEventToMeetingDtoMapper.java")) {
                    continue; // the mapper declares the method; it does not call it
                }
                Matcher m = Pattern.compile("mapWithCalendarOwner\\s*\\(").matcher(source);
                while (m.find()) {
                    String before = source.substring(Math.max(0, m.start() - 200), m.start());
                    if (!before.contains("stampInvokerId(")) {
                        unstamped.add(file.getFileName() + " at offset " + m.start());
                    }
                }
            }
        }

        assertTrue(unstamped.isEmpty(),
                "Every event-to-upsert mapping must be wrapped in stampInvokerId(...) so the upsert "
                        + "carries the reporting invoker. Unstamped call sites: " + unstamped);
    }
}
