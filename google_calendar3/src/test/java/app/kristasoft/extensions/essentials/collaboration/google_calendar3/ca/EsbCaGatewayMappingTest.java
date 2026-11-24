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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca;

import app.krista.conversationagent.module.calendar.esb.ExtensionCalendarServiceClient;
import app.krista.conversationagent.module.calendar.esb.dto.BulkUpsertMeetingsReply;
import app.krista.conversationagent.module.calendar.esb.dto.BulkUpsertMeetingsRequest;
import app.krista.conversationagent.module.calendar.esb.dto.CancelMeetingReply;
import app.krista.conversationagent.module.calendar.esb.dto.CancelMeetingRequest;
import app.krista.conversationagent.module.calendar.esb.dto.FindMeetingsReply;
import app.krista.conversationagent.module.calendar.esb.dto.FindMeetingsRequest;
import app.krista.conversationagent.module.calendar.esb.dto.UpsertMeetingReply;
import app.krista.conversationagent.module.calendar.esb.dto.UpsertMeetingRequest;
import app.krista.conversationagent.module.shared.esb.ExtErrorCodes;
import app.krista.conversationagent.module.shared.esb.ext.ExtensionIdentityServiceClient;
import app.krista.conversationagent.module.shared.esb.ext.dto.ListUsersReply;
import app.krista.conversationagent.module.shared.esb.ext.dto.ListUsersRequest;
import app.krista.conversationagent.module.shared.esb.ext.dto.ScannedMailboxSnapshotReply;
import app.krista.conversationagent.module.shared.esb.ext.dto.ScannedMailboxSnapshotRequest;
import app.krista.conversationagent.module.shared.proto.ScannedMailboxProto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.ScannedMailboxSnapshot;
import app.krista.conversationagent.module.shared.proto.MeetingViewProto;
import app.krista.conversationagent.module.shared.proto.NativeMeetingProto;
import app.krista.conversationagent.module.shared.proto.PersonProto;
import app.krista.conversationagent.module.shared.proto.UpsertResultProto;
import app.krista.conversationagent.module.shared.proto.UserViewProto;
import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model.CreateMeetingRequestDto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model.Person;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Request/reply mapping for every operation this extension calls over the ESB (KIM-492 WP-E16),
 * with no broker and no Meeting Agent.
 *
 * <h2>The rule these tests exist to pin</h2>
 *
 * <p>A reply is read by its {@code errorCode} and by nothing else, in three states that are easy
 * to get backwards:
 * <ul>
 *   <li><b>blank</b> — success. {@code ReplyBase.ok} cannot be used: it carries no
 *       {@code @ProtoField}, never crosses the wire and reads {@code true} on every decoded reply
 *       regardless of what MA decided.</li>
 *   <li><b>populated</b> — that {@code ExtErrorCodes} constant is MA's answer. Never
 *       string-matched out of {@code errorMessage}, which is empty on every remote reply.</li>
 *   <li><b>null</b> — the reply was never decoded at all, i.e. the call did not reach MA. The
 *       generated marshaller fills absent strings with {@code ""}, so null is unreachable from the
 *       wire. Collapsing it into blank would read every timeout as a successful upsert.</li>
 * </ul>
 */
@DisplayName("ESB gateway — request/reply mapping")
class EsbCaGatewayMappingTest {

    private static final String WORKSPACE = "ws-gcal-1";
    private static final String INVOKER_ID = "INV_gcal";

    /** A code this build has never heard of — the safe reading of which is "this did not work". */
    private static final String UNKNOWN_FUTURE_CODE = "SOME_FUTURE_CODE";

    /** Captures what was sent and replies with whatever the test supplies. */
    private static final class StubGateway extends EsbCaGateway {

        private final ExtensionCalendarServiceClient calendar =
                mock(ExtensionCalendarServiceClient.class);
        private final ExtensionIdentityServiceClient identity =
                mock(ExtensionIdentityServiceClient.class);

        StubGateway(Invoker invoker) {
            super(invoker);
        }

        @Override
        protected ExtensionCalendarServiceClient calendarClient(long replyBudgetMillis) {
            return calendar;
        }

        @Override
        protected ExtensionIdentityServiceClient identityClient(long replyBudgetMillis) {
            return identity;
        }
    }

    /**
     * Every code {@code ExtErrorCodes} declares, read off the class itself.
     *
     * <p>Hand-written code lists go stale silently: MA adds a twelfth constant, nothing here
     * mentions it, and the case that has never been exercised is exactly the one that ships. Read
     * reflectively, a new constant is covered by every test below on the day it lands in the
     * staged contract jar.
     *
     * @return the declared constant VALUES, in declaration order
     */
    private static Stream<String> declaredErrorCodes() {
        return Arrays.stream(ExtErrorCodes.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> field.getType() == String.class)
                .filter(field -> Modifier.isStatic(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers()))
                .map(field -> {
                    try {
                        return (String) field.get(null);
                    }
                    catch (IllegalAccessException unreadable) {
                        throw new AssertionError(
                                "ExtErrorCodes." + field.getName() + " is not readable", unreadable);
                    }
                })
                .filter(Objects::nonNull);
    }

    /**
     * The upsert path maps two codes to outcomes of their own — each has a dedicated test next to
     * this one — and reads every other code as a failure, including one no build has ever seen.
     *
     * @return the codes that must map to {@code FAILED}
     */
    static Stream<String> codesThatFailTheUpsert() {
        return Stream.concat(
                declaredErrorCodes().filter(code ->
                        !ExtErrorCodes.MEETING_SKIPPED_CANCELLED.equals(code)
                                && !ExtErrorCodes.NO_REGISTERED_USER.equals(code)),
                Stream.of(UNKNOWN_FUTURE_CODE));
    }

    /**
     * The cancel path special-cases nothing: a populated {@code errorCode} is a refusal, full stop
     * (see {@code EsbCaGateway.verdict}). So every declared code belongs here, the two the upsert
     * path reads specially included.
     *
     * @return the codes that must refuse a delete
     */
    static Stream<String> codesThatRefuseTheCancel() {
        return Stream.concat(declaredErrorCodes(), Stream.of(UNKNOWN_FUTURE_CODE));
    }

    @Test
    @DisplayName("the reflected code list is the real one — non-empty, and excludes only the two "
            + "codes with dedicated upsert tests")
    void theReflectedCodeListIsTheRealOne() {
        Set<String> declared = declaredErrorCodes().collect(Collectors.toSet());

        assertTrue(declared.size() >= 11,
                "Reflection found " + declared.size() + " codes on ExtErrorCodes. The contract"
                        + " declares at least eleven, so a smaller number means the filter is"
                        + " wrong and every parameterized test below is running on a short list.");
        assertTrue(declared.contains(ExtErrorCodes.MEETING_SKIPPED_CANCELLED)
                        && declared.contains(ExtErrorCodes.NO_REGISTERED_USER),
                "The two specially-mapped codes must be IN the reflected list; the upsert source"
                        + " subtracts them there rather than relying on reflection to miss them.");

        Set<String> failing = codesThatFailTheUpsert().collect(Collectors.toSet());
        assertFalse(failing.contains(ExtErrorCodes.MEETING_SKIPPED_CANCELLED));
        assertFalse(failing.contains(ExtErrorCodes.NO_REGISTERED_USER));
        assertTrue(failing.contains(UNKNOWN_FUTURE_CODE));
    }

    private static Invoker invoker() {
        return invoker(WORKSPACE);
    }

    private static Invoker invoker(String workspaceId) {
        Invoker invoker = mock(Invoker.class);
        lenient().when(invoker.getWorkspaceId()).thenReturn(workspaceId);
        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        return invoker;
    }

    private static CreateMeetingRequestDto meeting(String iCalUId) {
        CreateMeetingRequestDto dto = new CreateMeetingRequestDto();
        dto.setTitle("Weekly sync");
        dto.setMeetingUrl("https://meet.google.com/abc-defg-hij");
        dto.setStartTime("2026-09-12T09:00:00Z");
        dto.setEndTime("2026-09-12T09:30:00Z");
        dto.setTimezone("Europe/Amsterdam");
        dto.setOrganizer(new Person("organiser@acme.example", "Organiser"));
        dto.setAttendees(List.of(
                new Person("direct@acme.example", "Direct"),
                new Person("member@acme.example", "Member", "engineering@acme.example")));
        dto.setCalendarSystem("google");
        dto.setRecurring(true);
        dto.setRecurringSeriesId("series-1");
        dto.setBody("agenda");
        dto.setiCalUId(iCalUId);
        dto.setInvokerId(INVOKER_ID);
        return dto;
    }

    private static UpsertMeetingReply upsertReply(String errorCode, String meetingKey,
            String iCalUId, boolean created) {
        UpsertMeetingReply reply = new UpsertMeetingReply();
        reply.setErrorCode(errorCode);
        reply.setMeetingKey(meetingKey);
        reply.setICalUId(iCalUId);
        reply.setCreated(created);
        return reply;
    }

    // ── A1 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("A1 upsertMeeting")
    class Upsert {

        @Test
        @DisplayName("maps every calendar-native field onto the wire, viaGroup included")
        void mapsTheWholePayload() {
            NativeMeetingProto proto = EsbCaGateway.toNativeMeeting(meeting("ical-1"));

            assertEquals("Weekly sync", proto.title());
            assertEquals("https://meet.google.com/abc-defg-hij", proto.meetingUrl());
            assertEquals("2026-09-12T09:00:00Z", proto.startTime());
            assertEquals("2026-09-12T09:30:00Z", proto.endTime());
            assertEquals("Europe/Amsterdam", proto.timezone());
            assertEquals("google", proto.calendarSystem());
            assertTrue(proto.isRecurring());
            assertEquals("series-1", proto.recurringSeriesId());
            assertEquals("agenda", proto.body());
            assertEquals("ical-1", proto.iCalUId());
            assertEquals("organiser@acme.example", proto.organizer().email());
            assertEquals(2, proto.attendees().size());

            PersonProto viaGroup = proto.attendees().get(1);
            assertEquals("engineering@acme.example", viaGroup.viaGroup(),
                    "WP-DL provenance must survive the transport change: a member expanded out of a"
                            + " Google Group has to reach MA tagged, or a report implies they were"
                            + " named personally.");
        }

        @Test
        @DisplayName("cancelled is always false — this extension deletes rather than upserting a cancellation")
        void cancelledIsAlwaysFalse() {
            assertFalse(EsbCaGateway.toNativeMeeting(meeting("ical-1")).isCancelled(),
                    "A cancelled Google event goes down the A3 delete path; there is no cancelled"
                            + " flag on CreateMeetingRequestDto to read, and inventing one here"
                            + " would make a cancellation look like an upsert.");
        }

        @Test
        @DisplayName("a blank errorCode is success and carries MA's key back")
        void blankErrorCodeIsSuccess() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.upsertMeeting(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(upsertReply("", "mk-1", "ical-1", true));

            CaGateway.UpsertOutcome outcome = gateway.upsertMeeting(meeting("ical-1"));

            assertEquals(CaGateway.UpsertStatus.UPSERTED, outcome.status());
            assertTrue(outcome.acceptedByCa());
            assertEquals("mk-1", outcome.meetingKey());
            assertEquals("ical-1", outcome.iCalUId());
            assertTrue(outcome.created());
            assertNull(outcome.errorCode());
        }

        @Test
        @DisplayName("MEETING_SKIPPED_CANCELLED is ACCEPTED — there is nothing to retry")
        void skippedCancelledIsAccepted() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.upsertMeeting(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(upsertReply(ExtErrorCodes.MEETING_SKIPPED_CANCELLED, null, null, false));

            CaGateway.UpsertOutcome outcome = gateway.upsertMeeting(meeting("ical-1"));

            assertEquals(CaGateway.UpsertStatus.SKIPPED_CANCELLED, outcome.status());
            assertTrue(outcome.acceptedByCa(),
                    "MA deliberately dropped a cancellation for a meeting it never captured. There"
                            + " is nothing to retry and nothing to keep tracking.");
        }

        @Test
        @DisplayName("NO_REGISTERED_USER is NOT accepted — the tracking entry must survive for the retry")
        void noRegisteredUserIsNotAccepted() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.upsertMeeting(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(upsertReply(ExtErrorCodes.NO_REGISTERED_USER, null, null, false));

            CaGateway.UpsertOutcome outcome = gateway.upsertMeeting(meeting("ical-1"));

            assertEquals(CaGateway.UpsertStatus.NO_REGISTERED_USER, outcome.status());
            assertFalse(outcome.acceptedByCa(),
                    "D-05 means nobody on this meeting has a seat YET. Treating it as accepted"
                            + " would discard the tracking entry the next sweep needs to re-offer"
                            + " the meeting once somebody registers.");
        }

        @ParameterizedTest(name = "errorCode={0} -> FAILED")
        @MethodSource("app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca"
                + ".EsbCaGatewayMappingTest#codesThatFailTheUpsert")
        @DisplayName("every other code — including one this build has never heard of — is a failure")
        void everyOtherCodeFails(String errorCode) {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.upsertMeeting(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(upsertReply(errorCode, null, null, false));

            CaGateway.UpsertOutcome outcome = gateway.upsertMeeting(meeting("ical-1"));

            assertEquals(CaGateway.UpsertStatus.FAILED, outcome.status());
            assertEquals(errorCode, outcome.errorCode());
            assertFalse(outcome.acceptedByCa(),
                    "The safe reading of a code this build does not know is 'this did not work'.");
        }

        @Test
        @DisplayName("a NULL errorCode is a TRANSPORT failure, never a success")
        void nullErrorCodeIsATransportFailure() {
            StubGateway gateway = new StubGateway(invoker());
            // Exactly what the generated client returns when it catches its own exception: a
            // default-constructed reply. isOk() reads true on it, and errorCode is null.
            UpsertMeetingReply defaultConstructed = new UpsertMeetingReply();
            assertNull(defaultConstructed.getErrorCode());
            lenient().when(gateway.calendar.upsertMeeting(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(defaultConstructed);

            CaGateway.UpsertOutcome outcome = gateway.upsertMeeting(meeting("ical-1"));

            assertEquals(CaGateway.UpsertStatus.FAILED, outcome.status());
            assertNull(outcome.errorCode(),
                    "There is no MA error code, because MA never answered.");
            assertFalse(outcome.acceptedByCa());
        }

        @Test
        @DisplayName("a null reply — the client could not even be called — is also a failure")
        void nullReplyIsAFailure() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.upsertMeeting(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new IllegalStateException("broker down"));

            CaGateway.UpsertOutcome outcome = gateway.upsertMeeting(meeting("ical-1"));

            assertEquals(CaGateway.UpsertStatus.FAILED, outcome.status());
        }

        @Test
        @DisplayName("no workspace id — the call is refused rather than sent unattributed")
        void noWorkspaceIsRefused() {
            StubGateway gateway = new StubGateway(invoker(null));

            CaGateway.UpsertOutcome outcome = gateway.upsertMeeting(meeting("ical-1"));

            assertEquals(CaGateway.UpsertStatus.FAILED, outcome.status());
            assertEquals(ExtErrorCodes.VALIDATION_ERROR, outcome.errorCode());
        }

        @Test
        @DisplayName("the request carries workspaceId, a correlationId and the invoker id")
        void theRequestIsAttributed() {
            StubGateway gateway = new StubGateway(invoker());
            List<UpsertMeetingRequest> sent = capture(gateway,
                    request -> upsertReply("", "mk-1", "ical-1", true));

            gateway.upsertMeeting(meeting("ical-1"));

            assertEquals(1, sent.size());
            assertEquals(WORKSPACE, sent.get(0).getWorkspaceId());
            assertEquals(INVOKER_ID, sent.get(0).getInvokerId());
            assertNotNull(sent.get(0).getCorrelationId());
        }

        private List<UpsertMeetingRequest> capture(StubGateway gateway,
                Function<UpsertMeetingRequest, UpsertMeetingReply> answer) {
            List<UpsertMeetingRequest> sent = new ArrayList<>();
            lenient().when(gateway.calendar.upsertMeeting(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        UpsertMeetingRequest request = invocation.getArgument(0);
                        sent.add(request);
                        return answer.apply(request);
                    });
            return sent;
        }
    }

    // ── A2 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("A2 bulkUpsertMeetings")
    class BulkUpsert {

        private BulkUpsertMeetingsReply reply(String errorCode, UpsertResultProto... results) {
            BulkUpsertMeetingsReply reply = new BulkUpsertMeetingsReply();
            reply.setErrorCode(errorCode);
            reply.setResults(List.of(results));
            return reply;
        }

        private UpsertResultProto row(String meetingKey, String iCalUId, String errorCode) {
            UpsertResultProto result = new UpsertResultProto();
            result.setMeetingKey(meetingKey);
            result.setICalUId(iCalUId);
            result.setCreated(meetingKey != null);
            result.setErrorCode(errorCode);
            return result;
        }

        @Test
        @DisplayName("per-meeting outcomes come back keyed by iCalUId, mixed success and drop")
        void perMeetingOutcomes() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.bulkUpsertMeetings(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(reply("",
                            row("mk-1", "ical-1", ""),
                            row(null, "ical-2", ExtErrorCodes.NO_REGISTERED_USER)));

            List<CaGateway.UpsertOutcome> outcomes = gateway.bulkUpsertMeetings(
                    List.of(meeting("ical-1"), meeting("ical-2")));

            assertEquals(2, outcomes.size());
            assertEquals(CaGateway.UpsertStatus.UPSERTED, outcomes.get(0).status());
            assertEquals("mk-1", outcomes.get(0).meetingKey());
            assertEquals("ical-1", outcomes.get(0).iCalUId());
            assertEquals(CaGateway.UpsertStatus.NO_REGISTERED_USER, outcomes.get(1).status());
            assertEquals("ical-2", outcomes.get(1).iCalUId(),
                    "the caller keys its tracking store on iCalUId, so a dropped row still has to"
                            + " say WHICH meeting was dropped");
        }

        /**
         * EF-28 — EDITED. This test previously asserted the opposite: that a whole-batch rejection
         * returns an EMPTY list, on the reasoning that naming each meeting would "claim MA said
         * something about each, which it did not".
         *
         * <p>That reasoning is sound in isolation and wrong in context. A batch larger than the
         * chunk cap is several ESB calls, and the caller receives ONE concatenated list. With an
         * empty list per failed chunk, two healthy chunks made the result non-empty — so the caller's
         * only aggregate signal read a partially-delivered batch as a clean one, and the missing rows
         * showed up as "accepted &lt; sent", which its own log line attributes to MA's per-meeting
         * registration gate. A broker outage was reported as a customer registration problem.
         *
         * <p>The outcomes are FAILED, which is not {@code acceptedByCa()}, so nothing is tracked and
         * the next sweep re-offers these meetings: the behaviour is unchanged, what changed is that
         * the caller can now tell it happened.
         */
        @Test
        @DisplayName("EF-28: a whole-batch rejection names every meeting in the chunk")
        void aWholeBatchRejectionNamesItsMeetings() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.bulkUpsertMeetings(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(reply(ExtErrorCodes.VALIDATION_ERROR));

            List<CaGateway.UpsertOutcome> outcomes = gateway.bulkUpsertMeetings(
                    List.of(meeting("ical-1"), meeting("ical-2")));

            assertEquals(2, outcomes.size(),
                    "one outcome per input meeting, always — 'fewer outcomes than meetings' is the"
                            + " state that let a lost chunk hide behind its healthy neighbours");
            assertEquals(List.of("ical-1", "ical-2"),
                    outcomes.stream().map(CaGateway.UpsertOutcome::iCalUId).toList());
            for (CaGateway.UpsertOutcome outcome : outcomes) {
                assertEquals(CaGateway.UpsertStatus.FAILED, outcome.status());
                assertFalse(outcome.acceptedByCa(), "a refused meeting must not be tracked");
                assertEquals(ExtErrorCodes.VALIDATION_ERROR, outcome.errorCode());
                assertFalse(outcome.caUnreached(),
                        "MA answered — it refused. Reporting that as a transport failure would send"
                                + " someone to look at the broker.");
            }
        }

        /**
         * EF-28 — EDITED, same reasoning as above. The distinction this adds over a refusal is
         * {@link CaGateway.UpsertOutcome#caUnreached()}: MA never answered, so nothing about these
         * meetings has been decided by anybody.
         */
        @Test
        @DisplayName("EF-28: a transport failure names every meeting and reports itself as unreached")
        void aTransportFailureNamesItsMeetings() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.bulkUpsertMeetings(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new BulkUpsertMeetingsReply());

            List<CaGateway.UpsertOutcome> outcomes =
                    gateway.bulkUpsertMeetings(List.of(meeting("ical-1")));

            assertEquals(1, outcomes.size());
            assertEquals("ical-1", outcomes.get(0).iCalUId());
            assertFalse(outcomes.get(0).acceptedByCa());
            assertTrue(outcomes.get(0).caUnreached(),
                    "the caller's telemetry keys on this to tell an outage from MA's registration"
                            + " gate");
            assertNull(outcomes.get(0).errorCode(),
                    "there is no MA error code, because MA never answered");
        }

        /**
         * EF-28, the case that made the masking matter. One chunk of three fails; the other two
         * succeed. Before the fix the caller saw 100 outcomes for 150 meetings with no way to tell
         * that 50 of them were never offered.
         */
        @Test
        @DisplayName("EF-28: a failed chunk is visible beside its healthy neighbours")
        void oneFailedChunkIsNotMaskedByTheOthers() {
            StubGateway gateway = new StubGateway(invoker());
            List<CreateMeetingRequestDto> batch = new ArrayList<>();
            for (int i = 0; i < 150; i++) {
                batch.add(meeting("ical-" + i));
            }
            java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
            lenient().when(gateway.calendar.bulkUpsertMeetings(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        BulkUpsertMeetingsRequest request = invocation.getArgument(0);
                        // The SECOND of three chunks never reaches MA.
                        if (call.incrementAndGet() == 2) {
                            return new BulkUpsertMeetingsReply();
                        }
                        List<UpsertResultProto> rows = request.getMeetings().stream()
                                .map(m -> row("mk-" + m.iCalUId(), m.iCalUId(), ""))
                                .toList();
                        return reply("", rows.toArray(new UpsertResultProto[0]));
                    });

            List<CaGateway.UpsertOutcome> outcomes = gateway.bulkUpsertMeetings(batch);

            assertEquals(150, outcomes.size(), "every meeting is accounted for");
            assertEquals(100, outcomes.stream().filter(CaGateway.UpsertOutcome::acceptedByCa).count());
            assertEquals(50, outcomes.stream().filter(CaGateway.UpsertOutcome::caUnreached).count(),
                    "without this the caller sees only 'accepted 100 of 150' and blames MA's"
                            + " registration gate for a broker outage");
            // And the lost chunk is identifiable, not merely counted.
            List<String> lost = outcomes.stream()
                    .filter(CaGateway.UpsertOutcome::caUnreached)
                    .map(CaGateway.UpsertOutcome::iCalUId)
                    .toList();
            assertEquals("ical-50", lost.get(0));
            assertEquals("ical-99", lost.get(lost.size() - 1));
        }

        @Test
        @DisplayName("a batch over MA's cap of 50 is CHUNKED, not rejected whole")
        void oversizedBatchesAreChunked() {
            StubGateway gateway = new StubGateway(invoker());
            List<BulkUpsertMeetingsRequest> sent = new ArrayList<>();
            lenient().when(gateway.calendar.bulkUpsertMeetings(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        BulkUpsertMeetingsRequest request = invocation.getArgument(0);
                        sent.add(request);
                        List<UpsertResultProto> rows = request.getMeetings().stream()
                                .map(m -> row("mk-" + m.iCalUId(), m.iCalUId(), ""))
                                .toList();
                        return reply("", rows.toArray(new UpsertResultProto[0]));
                    });

            List<CreateMeetingRequestDto> batch = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                batch.add(meeting("ical-" + i));
            }

            List<CaGateway.UpsertOutcome> outcomes = gateway.bulkUpsertMeetings(batch);

            assertEquals(3, sent.size(), "120 meetings is three requests at MA's cap of 50");
            assertEquals(50, sent.get(0).getMeetings().size());
            assertEquals(50, sent.get(1).getMeetings().size());
            assertEquals(20, sent.get(2).getMeetings().size());
            assertEquals(120, outcomes.size(),
                    "an oversized page must not lose the surplus — MA rejects an over-cap batch"
                            + " WHOLE, so one bad request would drop every meeting on it");
        }

        @Test
        @DisplayName("an empty or all-null batch makes no call at all")
        void emptyBatchMakesNoCall() {
            StubGateway gateway = new StubGateway(invoker());

            assertTrue(gateway.bulkUpsertMeetings(List.of()).isEmpty());
            assertTrue(gateway.bulkUpsertMeetings(null).isEmpty());
        }
    }

    // ── A3 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("A3 cancelMeeting")
    class Cancel {

        private CancelMeetingReply reply(String errorCode, boolean deleted, boolean botCancelInitiated) {
            CancelMeetingReply reply = new CancelMeetingReply();
            reply.setErrorCode(errorCode);
            reply.setDeleted(deleted);
            reply.setBotCancelInitiated(botCancelInitiated);
            return reply;
        }

        @Test
        @DisplayName("a deleted meeting is success")
        void deletedIsSuccess() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.cancelMeeting(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(reply("", true, false));

            assertTrue(gateway.deleteMeeting("mk-1", "ical-1", "cancelled"));
        }

        @Test
        @DisplayName("NOT FOUND — blank errorCode with deleted=false — is ALSO success")
        void notFoundIsSuccess() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.cancelMeeting(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(reply("", false, false));

            assertTrue(gateway.deleteMeeting("mk-1", "ical-1", "cancelled"),
                    "There was nothing to delete, which is the state the caller wanted — exactly"
                            + " what HTTP 404 already meant on this path.");
        }

        @Test
        @DisplayName("an armed capture replies deleted=false, botCancelInitiated=true — still success")
        void anArmedCaptureIsSuccess() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.cancelMeeting(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(reply("", false, true));

            assertTrue(gateway.deleteMeeting("mk-1", "ical-1", "cancelled"),
                    "the teardown is under way; the caller has nothing more to do");
        }

        @ParameterizedTest(name = "errorCode={0} -> refused")
        @MethodSource("app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca"
                + ".EsbCaGatewayMappingTest#codesThatRefuseTheCancel")
        @DisplayName("a populated errorCode is a refusal — EVERY code, this path special-cases none")
        void populatedErrorCodeIsRefused(String errorCode) {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.cancelMeeting(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(reply(errorCode, false, false));

            assertFalse(gateway.deleteMeeting("mk-1", "ical-1", "cancelled"));
        }

        @Test
        @DisplayName("a null errorCode is a transport failure, not a successful no-op delete")
        void nullErrorCodeIsATransportFailure() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.cancelMeeting(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new CancelMeetingReply());

            assertFalse(gateway.deleteMeeting("mk-1", "ical-1", "cancelled"),
                    "A default-constructed reply reads deleted=false and isOk()=true. Reading that"
                            + " as 'nothing to delete' would silently drop every delete during an"
                            + " outage.");
        }

        @Test
        @DisplayName("the request carries both keys and the reason")
        void theRequestCarriesBothKeys() {
            StubGateway gateway = new StubGateway(invoker());
            List<CancelMeetingRequest> sent = new ArrayList<>();
            lenient().when(gateway.calendar.cancelMeeting(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        sent.add(invocation.getArgument(0));
                        return reply("", true, false);
                    });

            gateway.deleteMeeting("mk-1", "ical-1", "calendar event cancelled");

            assertEquals("mk-1", sent.get(0).getMeetingKey());
            assertEquals("ical-1", sent.get(0).getICalUId());
            assertEquals("calendar event cancelled", sent.get(0).getReason());
            assertEquals(WORKSPACE, sent.get(0).getWorkspaceId());
        }
    }

    // ── A5 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("A5 findMeetings")
    class Find {

        private FindMeetingsReply reply(String errorCode, String matchedBy, String... keys) {
            FindMeetingsReply reply = new FindMeetingsReply();
            reply.setErrorCode(errorCode);
            reply.setMatchedBy(matchedBy);
            List<MeetingViewProto> meetings = new ArrayList<>();
            for (String key : keys) {
                MeetingViewProto meeting = new MeetingViewProto();
                meeting.setMeetingKey(key);
                meetings.add(meeting);
            }
            reply.setMeetings(meetings);
            return reply;
        }

        @Test
        @DisplayName("ONE request carries every criterion — this is what replaces the 404-ing lookup")
        void oneRequestCarriesEveryCriterion() {
            StubGateway gateway = new StubGateway(invoker());
            List<FindMeetingsRequest> sent = new ArrayList<>();
            lenient().when(gateway.calendar.findMeetings(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        sent.add(invocation.getArgument(0));
                        return reply("", "ICAL_UID", "mk-1");
                    });

            gateway.findMeetingKeys(new CaGateway.MeetingLookupCriteria(
                    "ical-1", "https://meet.google.com/abc", "2026-09-12T09:00:00Z", null,
                    "user@acme.example"));

            assertEquals(1, sent.size(), "one round trip, not a client-side cascade");
            FindMeetingsRequest request = sent.get(0);
            assertEquals("ical-1", request.getICalUId(),
                    "iCalUId is the strongest key and the HTTP query string had no parameter for"
                            + " it at all — supplying it is the whole fix for spec §6 R14");
            assertEquals("https://meet.google.com/abc", request.getMeetingUrl());
            assertEquals("2026-09-12T09:00:00Z", request.getStartTime());
            assertEquals("user@acme.example", request.getUserEmail());
            assertEquals("SCHEDULED", request.getStatus());
            assertEquals(EsbCaGateway.PROJECTION_KEYS, request.getProjection(),
                    "the caller consumes only meetingKey, so KEYS is the whole payload it needs");
        }

        @Test
        @DisplayName("matchedBy is reported so a log line can say which rule fired")
        void matchedByIsReported() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.findMeetings(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(reply("", "URL_AND_START", "mk-1", "mk-2"));

            CaGateway.MeetingKeyMatch match = gateway.findMeetingKeys(
                    new CaGateway.MeetingLookupCriteria(null, "https://meet", null, null, null));

            assertEquals("URL_AND_START", match.matchedBy());
            assertEquals(Set.of("mk-1", "mk-2"), match.meetingKeys());
            assertEquals("mk-1", match.firstKey(), "first in MA's order");
        }

        @Test
        @DisplayName("no match is an empty result, not an error")
        void noMatchIsEmpty() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.calendar.findMeetings(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(reply("", null));

            CaGateway.MeetingKeyMatch match = gateway.findMeetingKeys(
                    new CaGateway.MeetingLookupCriteria("ical-x", null, null, null, null));

            assertTrue(match.isEmpty());
            assertNull(match.firstKey());
        }

        @Test
        @DisplayName("a refusal and a transport failure both yield an empty match")
        void failuresYieldEmpty() {
            StubGateway refused = new StubGateway(invoker());
            lenient().when(refused.calendar.findMeetings(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(reply(ExtErrorCodes.VALIDATION_ERROR, null));
            assertTrue(refused.findMeetingKeys(
                    new CaGateway.MeetingLookupCriteria("ical-1", null, null, null, null)).isEmpty());

            StubGateway unreached = new StubGateway(invoker());
            lenient().when(unreached.calendar.findMeetings(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new FindMeetingsReply());
            assertTrue(unreached.findMeetingKeys(
                    new CaGateway.MeetingLookupCriteria("ical-1", null, null, null, null)).isEmpty());
        }

        @Test
        @DisplayName("null criteria are refused without a call")
        void nullCriteriaAreRefused() {
            assertTrue(new StubGateway(invoker()).findMeetingKeys(null).isEmpty());
        }
    }

    // ── C1 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("C1 listUsers")
    class Seats {

        private ListUsersReply reply(String errorCode, int nextOffset, UserViewProto... users) {
            ListUsersReply reply = new ListUsersReply();
            reply.setErrorCode(errorCode);
            reply.setNextOffset(nextOffset);
            reply.setUsers(List.of(users));
            return reply;
        }

        private UserViewProto user(String email, String status, boolean licensedSeat) {
            UserViewProto user = new UserViewProto();
            user.setEmail(email);
            user.setStatus(status);
            user.setLicensedSeat(licensedSeat);
            return user;
        }

        @Test
        @DisplayName("only ACTIVE licensed seats count, lower-cased")
        void onlyActiveLicensedSeats() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.identity.listUsers(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(reply("", -1,
                            user("Alice@Acme.Example", "ACTIVE", true),
                            user("observer@acme.example", "ACTIVE", false),
                            user("gone@acme.example", "DEREGISTERED", true)));

            Optional<Set<String>> seats = gateway.listLicensedSeatEmails();

            assertTrue(seats.isPresent());
            assertEquals(Set.of("alice@acme.example"), seats.get(),
                    "The two conditions are re-applied per row on purpose: MA's default status"
                            + " filter is EVERY status, and a filter that silently stopped"
                            + " narrowing must not widen what this tenant scans.");
        }

        @Test
        @DisplayName("the request asks for ACTIVE explicitly and pages by nextOffset")
        void pagesByNextOffset() {
            StubGateway gateway = new StubGateway(invoker());
            List<ListUsersRequest> sent = new ArrayList<>();
            lenient().when(gateway.identity.listUsers(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        ListUsersRequest request = invocation.getArgument(0);
                        sent.add(request);
                        return sent.size() == 1
                                ? reply("", 1, user("a@acme.example", "ACTIVE", true))
                                : reply("", -1, user("b@acme.example", "ACTIVE", true));
                    });

            Optional<Set<String>> seats = gateway.listLicensedSeatEmails();

            assertEquals(2, sent.size());
            assertEquals("ACTIVE", sent.get(0).getStatus());
            assertEquals(0, sent.get(0).getOffset());
            assertEquals(1, sent.get(1).getOffset());
            assertEquals(Set.of("a@acme.example", "b@acme.example"), seats.orElseThrow());
        }

        @Test
        @DisplayName("a fetched EMPTY roster is present — an answer, not an absence")
        void fetchedEmptyIsPresent() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.identity.listUsers(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(reply("", -1));

            Optional<Set<String>> seats = gateway.listLicensedSeatEmails();

            assertTrue(seats.isPresent(), "this workspace has bought no seats — that IS the answer");
            assertTrue(seats.get().isEmpty());
        }

        @Test
        @DisplayName("ANY page failing empties the whole result — a partial roster is dangerous")
        void anyPageFailingEmptiesTheResult() {
            StubGateway refused = new StubGateway(invoker());
            lenient().when(refused.identity.listUsers(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(reply(ExtErrorCodes.WORKSPACE_NOT_REGISTERED, -1));
            assertTrue(refused.listLicensedSeatEmails().isEmpty());

            StubGateway unreached = new StubGateway(invoker());
            lenient().when(unreached.identity.listUsers(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new ListUsersReply());
            assertTrue(unreached.listLicensedSeatEmails().isEmpty(),
                    "A partial roster is indistinguishable from a shrunken one, and acting on it"
                            + " would drop real seats and silently stop capturing their meetings.");
        }

        @Test
        @DisplayName("a cursor that does not advance is stopped, and the roster is discarded")
        void aStuckCursorIsStopped() {
            StubGateway gateway = new StubGateway(invoker());
            lenient().when(gateway.identity.listUsers(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(reply("", 0, user("a@acme.example", "ACTIVE", true)));

            assertTrue(gateway.listLicensedSeatEmails().isEmpty(),
                    "nextOffset=0 at offset=0 would page forever; the result is INCOMPLETE and"
                            + " must not replace a good roster");
        }
    }

    // ── C4 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("C4 reportScannedMailboxes")
    class ReportScannedMailboxes {

        private ScannedMailboxSnapshot snapshot() {
            return new ScannedMailboxSnapshot("ENROLLED_USERS", "why not", "2026-11-09T10:00:00Z", List.of(
                    new ScannedMailboxSnapshot.Mailbox("a@acme.example", "GOOGLE", "ENROLLED_USER",
                            "SUBSCRIBED", "ch-1", "a@acme.example"),
                    new ScannedMailboxSnapshot.Mailbox("ghost@acme.example", "GOOGLE", "ENROLLED_USER",
                            "NOT_IN_DIRECTORY", null, "ghost@acme.example")));
        }

        @Test
        @DisplayName("maps every field and reads success from a blank errorCode")
        void mapsAndSucceeds() {
            StubGateway gateway = new StubGateway(invoker());
            ScannedMailboxSnapshotReply reply = new ScannedMailboxSnapshotReply();
            reply.setErrorCode("");
            when(gateway.identity.reportScannedMailboxes(org.mockito.ArgumentMatchers.any())).thenReturn(reply);

            assertTrue(gateway.reportScannedMailboxes(snapshot()));

            ArgumentCaptor<ScannedMailboxSnapshotRequest> sent =
                    ArgumentCaptor.forClass(ScannedMailboxSnapshotRequest.class);
            org.mockito.Mockito.verify(gateway.identity).reportScannedMailboxes(sent.capture());
            ScannedMailboxSnapshotRequest request = sent.getValue();
            assertEquals(WORKSPACE, request.getWorkspaceId());
            assertEquals(INVOKER_ID, request.getInvokerId());
            assertEquals("ENROLLED_USERS", request.getMode());
            assertEquals("why not", request.getDegradedReason());
            assertEquals("2026-11-09T10:00:00Z", request.getSweptAt());
            assertEquals(2, request.getMailboxes().size());
            ScannedMailboxProto row = request.getMailboxes().get(0);
            assertEquals("a@acme.example", row.getEmail());
            assertEquals("GOOGLE", row.getCalendarSystem());
            assertEquals("ENROLLED_USER", row.getScopeReason());
            assertEquals("SUBSCRIBED", row.getSubscriptionState());
            assertEquals("ch-1", row.getSubscriptionId());
            assertEquals("a@acme.example", row.getDisplayLabel());
            assertEquals("NOT_IN_DIRECTORY", request.getMailboxes().get(1).getSubscriptionState());
            assertNull(request.getMailboxes().get(1).getSubscriptionId());
        }

        @Test
        @DisplayName("a refusal and an unanswered call both report false, without throwing")
        void failuresReportFalse() {
            StubGateway refused = new StubGateway(invoker());
            ScannedMailboxSnapshotReply reply = new ScannedMailboxSnapshotReply();
            reply.setErrorCode(UNKNOWN_FUTURE_CODE);
            when(refused.identity.reportScannedMailboxes(org.mockito.ArgumentMatchers.any())).thenReturn(reply);
            assertFalse(refused.reportScannedMailboxes(snapshot()));

            StubGateway unanswered = new StubGateway(invoker());
            when(unanswered.identity.reportScannedMailboxes(org.mockito.ArgumentMatchers.any())).thenReturn(null);
            assertFalse(unanswered.reportScannedMailboxes(snapshot()));
        }

        @Test
        @DisplayName("an empty snapshot without a reason is never sent")
        void emptyIsNotSent() {
            StubGateway gateway = new StubGateway(invoker());

            assertFalse(gateway.reportScannedMailboxes(
                    new ScannedMailboxSnapshot("ENROLLED_USERS", null, "2026-11-09T10:00:00Z", List.of())));
            assertFalse(gateway.reportScannedMailboxes(
                    new ScannedMailboxSnapshot("ENROLLED_USERS", "  ", "2026-11-09T10:00:00Z", List.of())));

            org.mockito.Mockito.verifyNoInteractions(gateway.identity);
        }

        /**
         * A degraded Always On sweep scanned nothing, so its snapshot is empty; the reason is the
         * whole message. Meeting Agent stores it and prunes nothing.
         */
        @Test
        @DisplayName("an empty snapshot carrying a degraded reason IS sent")
        void emptyDegradedIsSent() {
            StubGateway gateway = new StubGateway(invoker());
            ScannedMailboxSnapshotReply reply = new ScannedMailboxSnapshotReply();
            reply.setErrorCode("");
            when(gateway.identity.reportScannedMailboxes(org.mockito.ArgumentMatchers.any())).thenReturn(reply);

            assertTrue(gateway.reportScannedMailboxes(new ScannedMailboxSnapshot("ENROLLED_USERS",
                    "registered-seats-unavailable", "2026-11-10T10:00:00Z", List.of())));

            ArgumentCaptor<ScannedMailboxSnapshotRequest> sent =
                    ArgumentCaptor.forClass(ScannedMailboxSnapshotRequest.class);
            org.mockito.Mockito.verify(gateway.identity).reportScannedMailboxes(sent.capture());
            assertEquals("registered-seats-unavailable", sent.getValue().getDegradedReason());
            assertTrue(sent.getValue().getMailboxes().isEmpty());
        }
    }
}
