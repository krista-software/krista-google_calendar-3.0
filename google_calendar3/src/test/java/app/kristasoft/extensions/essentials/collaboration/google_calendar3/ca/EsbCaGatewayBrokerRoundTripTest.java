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

import app.krista.conversationagent.module.calendar.esb.ExtensionCalendarService;
import app.krista.conversationagent.module.calendar.esb.ExtensionCalendarServiceClient;
import app.krista.conversationagent.module.calendar.esb.dto.BulkUpsertMeetingsReply;
import app.krista.conversationagent.module.calendar.esb.dto.BulkUpsertMeetingsRequest;
import app.krista.conversationagent.module.calendar.esb.dto.CancelMeetingReply;
import app.krista.conversationagent.module.calendar.esb.dto.CancelMeetingRequest;
import app.krista.conversationagent.module.calendar.esb.dto.CountMeetingsReply;
import app.krista.conversationagent.module.calendar.esb.dto.CountMeetingsRequest;
import app.krista.conversationagent.module.calendar.esb.dto.FindMeetingsReply;
import app.krista.conversationagent.module.calendar.esb.dto.FindMeetingsRequest;
import app.krista.conversationagent.module.calendar.esb.dto.GetEffectiveConfigsReply;
import app.krista.conversationagent.module.calendar.esb.dto.GetEffectiveConfigsRequest;
import app.krista.conversationagent.module.calendar.esb.dto.GetMeetingsReply;
import app.krista.conversationagent.module.calendar.esb.dto.GetMeetingsRequest;
import app.krista.conversationagent.module.calendar.esb.dto.MeetingQueryReply;
import app.krista.conversationagent.module.calendar.esb.dto.MeetingQueryRequest;
import app.krista.conversationagent.module.calendar.esb.dto.SetMeetingScheduledReply;
import app.krista.conversationagent.module.calendar.esb.dto.SetMeetingScheduledRequest;
import app.krista.conversationagent.module.calendar.esb.dto.UpdateMeetingPlatformReply;
import app.krista.conversationagent.module.calendar.esb.dto.UpdateMeetingPlatformRequest;
import app.krista.conversationagent.module.calendar.esb.dto.UpdateMeetingStatusReply;
import app.krista.conversationagent.module.calendar.esb.dto.UpdateMeetingStatusRequest;
import app.krista.conversationagent.module.calendar.esb.dto.UpdateMeetingTemplateReply;
import app.krista.conversationagent.module.calendar.esb.dto.UpdateMeetingTemplateRequest;
import app.krista.conversationagent.module.calendar.esb.dto.UpsertMeetingReply;
import app.krista.conversationagent.module.calendar.esb.dto.UpsertMeetingRequest;
import app.krista.conversationagent.module.shared.esb.ExtErrorCodes;
import app.krista.conversationagent.module.shared.esb.ext.ExtensionIdentityService;
import app.krista.conversationagent.module.shared.esb.ext.ExtensionIdentityServiceClient;
import app.krista.conversationagent.module.shared.esb.ext.dto.ListUsersReply;
import app.krista.conversationagent.module.shared.esb.ext.dto.ListUsersRequest;
import app.krista.conversationagent.module.shared.esb.ext.dto.RegisterUserReply;
import app.krista.conversationagent.module.shared.esb.ext.dto.RegisterUserRequest;
import app.krista.conversationagent.module.shared.esb.ext.dto.ScannedMailboxSnapshotReply;
import app.krista.conversationagent.module.shared.esb.ext.dto.ScannedMailboxSnapshotRequest;
import app.krista.conversationagent.module.shared.esb.ext.dto.UpdateUserReply;
import app.krista.conversationagent.module.shared.esb.ext.dto.UpdateUserRequest;
import app.krista.conversationagent.module.shared.proto.MeetingViewProto;
import app.krista.conversationagent.module.shared.proto.UpsertResultProto;
import app.krista.conversationagent.module.shared.proto.UserViewProto;
import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model.CreateMeetingRequestDto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model.Person;
import app.krista.infra.esb.ServiceBusConfig;
import app.krista.infra.esb.service.ServiceEndpoint;
import app.krista.infra.esb.service.ServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Every operation this extension calls, over a REAL Artemis broker, against a stub Meeting Agent
 * (KIM-492 WP-E16).
 *
 * <p>The mapping tests next door prove the request and reply shapes. This proves the thing they
 * cannot: that the generated client encodes, the broker carries, a consumer decodes, and the reply
 * comes back — the whole protostream registration and marshaller path that fails at the FIRST
 * customer sweep and nowhere earlier.
 *
 * <p><b>It self-skips when no broker is listening on {@code localhost:61616}</b>, and says so, so a
 * developer or CI box without one gets a clear skip rather than a confusing failure. It never
 * starts a broker of its own.
 *
 * <p>Every destination carries a per-run UUID, so a run can never touch a destination a real
 * deployment uses, and two runs cannot collide.
 */
@DisplayName("ESB gateway — round trip over a live broker")
class EsbCaGatewayBrokerRoundTripTest {

    private static final String HOST = "localhost";
    private static final int PORT = 61616;
    private static final String USER = "artemis";
    private static final String PASS = "artemis";

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String WORKSPACE = "ws-rt-" + RUN;
    private static final String INVOKER_ID = "INV_rt_" + RUN;

    private static final String CALENDAR_DEST = "rt.ca.calendar." + RUN;
    private static final String IDENTITY_DEST = "rt.ca.identity." + RUN;

    private static boolean brokerUp;
    private static ServiceImpl calendarService;
    private static ServiceImpl identityService;

    /** What the stub MA should answer next; each test sets it before calling. */
    private static final AtomicReference<Object> NEXT_REPLY = new AtomicReference<>();

    /** What the stub MA last received, so a test can assert the wire carried its fields. */
    private static final AtomicReference<Object> LAST_REQUEST = new AtomicReference<>();

    @BeforeAll
    static void startStubMeetingAgent() throws Exception {
        brokerUp = reachable();
        assumeTrue(brokerUp, "SKIPPED: no Artemis broker on " + HOST + ":" + PORT
                + " — the ESB round trip needs one and this test never starts its own.");

        System.setProperty("broker.primary.host", HOST);
        System.setProperty("broker.primary.port", String.valueOf(PORT));
        System.setProperty("broker.user.name", USER);
        System.setProperty("broker.user.pwd", PASS);
        System.setProperty("krista.esb.host", HOST);
        System.setProperty("krista.esb.port", String.valueOf(PORT));
        System.setProperty("krista.esb.username", USER);
        System.setProperty("krista.esb.password", PASS);
        System.setProperty("krista.monitor.resource.enabled", "false");
        System.setProperty("krista.monitoring.enabled", "false");
        System.setProperty("krista.diagnostics.enabled", "false");

        CaContractProtoRegistration.registerAll();

        calendarService = new ServiceImpl(consumerConfig(CALENDAR_DEST),
                new app.krista.conversationagent.module.calendar.esb.ExtensionCalendarServiceDefinition());
        calendarService.addService(
                app.krista.conversationagent.module.calendar.esb.ExtensionCalendarServiceDefinition
                        .identity().serviceName(), new StubCalendar());
        calendarService.start();

        identityService = new ServiceImpl(consumerConfig(IDENTITY_DEST),
                new app.krista.conversationagent.module.shared.esb.ext.ExtensionIdentityServiceDefinition());
        identityService.addService(
                app.krista.conversationagent.module.shared.esb.ext.ExtensionIdentityServiceDefinition
                        .identity().serviceName(), new StubIdentity());
        identityService.start();
    }

    @AfterAll
    static void stopStubMeetingAgent() {
        if (calendarService != null) {
            calendarService.destroy();
        }
        if (identityService != null) {
            identityService.destroy();
        }
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 2000);
            return true;
        }
        catch (Exception unreachable) {
            return false;
        }
    }

    private static ServiceBusConfig consumerConfig(String destination) {
        return new ServiceBusConfig()
                .setDestinationBaseName(destination)
                .setVersion(1)
                .setDestinationType(ServiceBusConfig.DestinationType.Queue)
                .setTenantFilterMode(ServiceBusConfig.TenantFilterMode.FIXED)
                .setFixedTenantId(WORKSPACE);
    }

    private static ServiceBusConfig clientConfig(String destination) {
        return new ServiceBusConfig()
                .setDestinationBaseName(destination)
                .setVersion(1)
                .setDestinationType(ServiceBusConfig.DestinationType.Queue);
    }

    /** The gateway under test, pointed at the throwaway destinations. */
    private static EsbCaGateway gateway() {
        Invoker invoker = mock(Invoker.class);
        lenient().when(invoker.getWorkspaceId()).thenReturn(WORKSPACE);
        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        return new EsbCaGateway(invoker) {
            @Override
            protected ExtensionCalendarServiceClient calendarClient(long replyBudgetMillis) {
                // EF-02: the budget is threaded through the real production helper so this
                // round trip runs under the same bounded reply wait a deployed call gets.
                return new ExtensionCalendarServiceClient(
                        withReplyBudget(clientConfig(CALENDAR_DEST), replyBudgetMillis));
            }

            @Override
            protected ExtensionIdentityServiceClient identityClient(long replyBudgetMillis) {
                return new ExtensionIdentityServiceClient(
                        withReplyBudget(clientConfig(IDENTITY_DEST), replyBudgetMillis));
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T answerWith(Object reply) {
        NEXT_REPLY.set(reply);
        LAST_REQUEST.set(null);
        return (T) reply;
    }

    private static CreateMeetingRequestDto meeting(String iCalUId) {
        CreateMeetingRequestDto dto = new CreateMeetingRequestDto();
        dto.setTitle("Round trip " + iCalUId);
        dto.setMeetingUrl("https://meet.google.com/round-trip");
        dto.setStartTime("2026-09-12T09:00:00Z");
        dto.setEndTime("2026-09-12T09:30:00Z");
        dto.setTimezone("Europe/Amsterdam");
        dto.setOrganizer(new Person("organiser@acme.example", "Organiser"));
        dto.setAttendees(List.of(new Person("member@acme.example", "Member",
                "engineering@acme.example")));
        dto.setCalendarSystem("google");
        dto.setiCalUId(iCalUId);
        dto.setInvokerId(INVOKER_ID);
        return dto;
    }

    // ── the operations ────────────────────────────────────────────────────

    @Test
    @DisplayName("A1 upsertMeeting encodes, carries and decodes")
    void a1UpsertMeeting() {
        UpsertMeetingReply reply = new UpsertMeetingReply();
        reply.setErrorCode("");
        reply.setMeetingKey("mk-rt-1");
        reply.setICalUId("ical-rt-1");
        reply.setCreated(true);
        answerWith(reply);

        CaGateway.UpsertOutcome outcome = gateway().upsertMeeting(meeting("ical-rt-1"));

        assertEquals(CaGateway.UpsertStatus.UPSERTED, outcome.status());
        assertEquals("mk-rt-1", outcome.meetingKey());
        assertTrue(outcome.created());

        UpsertMeetingRequest received = (UpsertMeetingRequest) LAST_REQUEST.get();
        assertNotNull(received, "the stub never saw the request");
        assertEquals(WORKSPACE, received.getWorkspaceId());
        assertEquals(INVOKER_ID, received.getInvokerId());
        assertEquals("ical-rt-1", received.getMeeting().iCalUId());
        assertEquals("engineering@acme.example",
                received.getMeeting().attendees().get(0).viaGroup(),
                "viaGroup must survive the actual wire, not only the mapper");
    }

    @Test
    @DisplayName("A1 a populated errorCode survives the wire as that code, not as a success")
    void a1ErrorCodeSurvivesTheWire() {
        UpsertMeetingReply reply = new UpsertMeetingReply();
        reply.setErrorCode(ExtErrorCodes.NO_REGISTERED_USER);
        answerWith(reply);

        CaGateway.UpsertOutcome outcome = gateway().upsertMeeting(meeting("ical-rt-2"));

        assertEquals(CaGateway.UpsertStatus.NO_REGISTERED_USER, outcome.status());
        assertFalse(outcome.acceptedByCa());
    }

    @Test
    @DisplayName("A2 bulkUpsertMeetings carries per-meeting rows back")
    void a2BulkUpsertMeetings() {
        BulkUpsertMeetingsReply reply = new BulkUpsertMeetingsReply();
        reply.setErrorCode("");
        UpsertResultProto ok = new UpsertResultProto();
        ok.setMeetingKey("mk-rt-a");
        ok.setICalUId("ical-rt-a");
        ok.setCreated(true);
        ok.setErrorCode("");
        UpsertResultProto dropped = new UpsertResultProto();
        dropped.setICalUId("ical-rt-b");
        dropped.setErrorCode(ExtErrorCodes.NO_REGISTERED_USER);
        reply.setResults(List.of(ok, dropped));
        answerWith(reply);

        List<CaGateway.UpsertOutcome> outcomes = gateway().bulkUpsertMeetings(
                List.of(meeting("ical-rt-a"), meeting("ical-rt-b")));

        assertEquals(2, outcomes.size());
        assertEquals(CaGateway.UpsertStatus.UPSERTED, outcomes.get(0).status());
        assertEquals(CaGateway.UpsertStatus.NO_REGISTERED_USER, outcomes.get(1).status());
        assertEquals("ical-rt-b", outcomes.get(1).iCalUId());
    }

    @Test
    @DisplayName("A3 cancelMeeting — 'nothing to delete' comes back as success")
    void a3CancelMeeting() {
        CancelMeetingReply reply = new CancelMeetingReply();
        reply.setErrorCode("");
        reply.setDeleted(false);
        answerWith(reply);

        assertTrue(gateway().deleteMeeting("mk-rt-1", "ical-rt-1", "cancelled"));

        CancelMeetingRequest received = (CancelMeetingRequest) LAST_REQUEST.get();
        assertEquals("mk-rt-1", received.getMeetingKey());
        assertEquals("ical-rt-1", received.getICalUId());
        assertEquals("cancelled", received.getReason());
    }

    @Test
    @DisplayName("A5 findMeetings — one request carrying every criterion, matchedBy on the way back")
    void a5FindMeetings() {
        FindMeetingsReply reply = new FindMeetingsReply();
        reply.setErrorCode("");
        reply.setMatchedBy("ICAL_UID");
        MeetingViewProto view = new MeetingViewProto();
        view.setMeetingKey("mk-rt-found");
        reply.setMeetings(List.of(view));
        answerWith(reply);

        CaGateway.MeetingKeyMatch match = gateway().findMeetingKeys(
                new CaGateway.MeetingLookupCriteria("ical-rt-1", "https://meet.google.com/round-trip",
                        "2026-09-12T09:00:00Z", null, "user@acme.example"));

        assertEquals(Set.of("mk-rt-found"), match.meetingKeys());
        assertEquals("ICAL_UID", match.matchedBy());

        FindMeetingsRequest received = (FindMeetingsRequest) LAST_REQUEST.get();
        assertEquals("ical-rt-1", received.getICalUId());
        assertEquals("https://meet.google.com/round-trip", received.getMeetingUrl());
        assertEquals("2026-09-12T09:00:00Z", received.getStartTime());
        assertEquals("user@acme.example", received.getUserEmail());
        assertEquals(EsbCaGateway.PROJECTION_KEYS, received.getProjection());
    }

    @Test
    @DisplayName("C1 listUsers — the seat filter is applied to what the wire actually delivered")
    void c1ListUsers() {
        ListUsersReply reply = new ListUsersReply();
        reply.setErrorCode("");
        reply.setNextOffset(-1);
        UserViewProto seat = new UserViewProto();
        seat.setEmail("Alice@Acme.Example");
        seat.setStatus("ACTIVE");
        seat.setLicensedSeat(true);
        UserViewProto observer = new UserViewProto();
        observer.setEmail("observer@acme.example");
        observer.setStatus("ACTIVE");
        observer.setLicensedSeat(false);
        reply.setUsers(List.of(seat, observer));
        answerWith(reply);

        Optional<Set<String>> seats = gateway().listLicensedSeatEmails();

        assertTrue(seats.isPresent());
        assertEquals(Set.of("alice@acme.example"), seats.get());

        ListUsersRequest received = (ListUsersRequest) LAST_REQUEST.get();
        assertEquals(WORKSPACE, received.getWorkspaceId());
        assertEquals("ACTIVE", received.getStatus());
    }

    // ── the stub Meeting Agent ────────────────────────────────────────────

    /**
     * Answers whatever {@link #NEXT_REPLY} holds and records the request.
     *
     * <p>Public and static because the ESB dispatcher invokes the API method reflectively; a local
     * or anonymous class is not accessible to it.
     */
    public static class StubCalendar implements ExtensionCalendarService, ServiceEndpoint {

        @SuppressWarnings("unchecked")
        private static <R> R answer(Object request) {
            LAST_REQUEST.set(request);
            return (R) NEXT_REPLY.get();
        }

        @Override
        public UpsertMeetingReply upsertMeeting(UpsertMeetingRequest request) {
            return answer(request);
        }

        @Override
        public BulkUpsertMeetingsReply bulkUpsertMeetings(BulkUpsertMeetingsRequest request) {
            return answer(request);
        }

        @Override
        public CancelMeetingReply cancelMeeting(CancelMeetingRequest request) {
            return answer(request);
        }

        @Override
        public SetMeetingScheduledReply setMeetingScheduled(SetMeetingScheduledRequest request) {
            return answer(request);
        }

        @Override
        public FindMeetingsReply findMeetings(FindMeetingsRequest request) {
            return answer(request);
        }

        @Override
        public GetMeetingsReply getMeetings(GetMeetingsRequest request) {
            return answer(request);
        }

        @Override
        public GetEffectiveConfigsReply getEffectiveConfigs(GetEffectiveConfigsRequest request) {
            return answer(request);
        }

        @Override
        public MeetingQueryReply queryMeetings(MeetingQueryRequest request) {
            return answer(request);
        }

        @Override
        public CountMeetingsReply countMeetings(CountMeetingsRequest request) {
            return answer(request);
        }

        @Override
        public UpdateMeetingStatusReply updateMeetingStatus(UpdateMeetingStatusRequest request) {
            return answer(request);
        }

        @Override
        public UpdateMeetingPlatformReply updateMeetingPlatform(UpdateMeetingPlatformRequest request) {
            return answer(request);
        }

        @Override
        public UpdateMeetingTemplateReply updateMeetingTemplate(UpdateMeetingTemplateRequest request) {
            return answer(request);
        }
    }

    /** @see StubCalendar */
    public static class StubIdentity implements ExtensionIdentityService, ServiceEndpoint {

        @SuppressWarnings("unchecked")
        private static <R> R answer(Object request) {
            LAST_REQUEST.set(request);
            return (R) NEXT_REPLY.get();
        }

        @Override
        public ListUsersReply listUsers(ListUsersRequest request) {
            return answer(request);
        }

        @Override
        public RegisterUserReply registerUser(RegisterUserRequest request) {
            return answer(request);
        }

        @Override
        public UpdateUserReply updateUser(UpdateUserRequest request) {
            return answer(request);
        }

        @Override
        public ScannedMailboxSnapshotReply reportScannedMailboxes(
                ScannedMailboxSnapshotRequest request) {
            return answer(request);
        }
    }
}
