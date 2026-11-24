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

import app.krista.conversationagent.module.calendar.esb.CalendarAuthInitiateCommandProto;
import app.krista.conversationagent.module.calendar.esb.CalendarAuthInitiateReplyProto;
import app.krista.conversationagent.module.calendar.esb.CalendarSeatRegisteredCommandProto;
import app.krista.conversationagent.module.calendar.esb.CalendarWorkspaceMessagesProto;
import app.krista.conversationagent.module.calendar.esb.dto.BulkUpsertMeetingsReply;
import app.krista.conversationagent.module.calendar.esb.dto.BulkUpsertMeetingsRequest;
import app.krista.conversationagent.module.calendar.esb.dto.CalendarReminderDecideReply;
import app.krista.conversationagent.module.calendar.esb.dto.CalendarReminderDecideRequest;
import app.krista.conversationagent.module.calendar.esb.dto.CancelMeetingReply;
import app.krista.conversationagent.module.calendar.esb.dto.CancelMeetingRequest;
import app.krista.conversationagent.module.calendar.esb.dto.FindMeetingsReply;
import app.krista.conversationagent.module.calendar.esb.dto.FindMeetingsRequest;
import app.krista.conversationagent.module.calendar.esb.dto.UpsertMeetingReply;
import app.krista.conversationagent.module.calendar.esb.dto.UpsertMeetingRequest;
import app.krista.conversationagent.module.shared.esb.ext.dto.ListUsersReply;
import app.krista.conversationagent.module.shared.esb.ext.dto.ListUsersRequest;
import app.krista.conversationagent.module.shared.esb.ext.dto.ScannedMailboxSnapshotReply;
import app.krista.conversationagent.module.shared.esb.ext.dto.ScannedMailboxSnapshotRequest;
import app.krista.conversationagent.module.shared.proto.MeetingViewProto;
import app.krista.conversationagent.module.shared.proto.NativeMeetingProto;
import app.krista.conversationagent.module.shared.proto.PersonProto;
import app.krista.conversationagent.module.shared.proto.UpsertResultProto;
import app.krista.conversationagent.module.shared.proto.UserViewProto;
import org.infinispan.protostream.ProtobufUtil;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.protostream.SerializationContextInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KIM-492 — {@link CaContractProtoRegistration}'s list is COMPLETE: every wire type this extension
 * encodes or decodes has a marshaller once, and only once, that list has been applied.
 *
 * <h2>The hole this closes</h2>
 *
 * <p>That list is the only thing that registers the contract jar's ProtoStream initializers at
 * runtime — this module's {@code jar} task excludes every dependency's
 * {@code META-INF/services/…SerializationContextInitializer} file on purpose (see the task comment
 * in {@code build.gradle}), so ServiceLoader discovers none of them in the shipped artifact. Until
 * this test, nothing guarded the list: a CTO review deleted the
 * {@code CalendarReminderDecideProtoInitializerImpl} line and the whole suite — including the live
 * broker round trip — stayed green. In a deployed jar that deletion costs the reminder-decide
 * consumer every request Meeting Agent sends it, decoded as "no ProtoStream marshaller registered
 * for input" and surfaced on MA's side as a transport failure that names nothing.
 *
 * <h2>Why the assertions run against an ISOLATED context</h2>
 *
 * <p>The test classpath is NOT shaded. The contract jar's services file is still present here, and
 * anything that runs {@code ProtoWire.registerFromServiceLoader()} — or any generated client's own
 * static bootstrap — quietly supplies what a deployed jar would be missing. An assertion against
 * the shared {@code ProtoWire} context can therefore pass for the wrong reason, which is precisely
 * how the deleted line went unnoticed.
 *
 * <p>So {@link #buildIsolatedContext(List)} seeds a fresh {@link SerializationContext} from exactly
 * the FQCNs {@link CaContractProtoRegistration#initializers()} names and from nothing else. It
 * never consults ServiceLoader and never touches the shared registry, so it mirrors the shaded jar
 * and cannot pass by accident.
 *
 * <h2>What is asserted</h2>
 *
 * <ul>
 *   <li>{@link #hasAMarshaller(Class)} — every wire type resolves. Deleting a list entry makes at
 *       least one of them unresolvable and this test red.</li>
 *   <li>{@link #everyListedInitializerIsLoadBearing(String)} — and every entry EARNS its place:
 *       dropping any one of them breaks at least one wire type. A list nobody can shorten is a
 *       list nobody has to audit.</li>
 * </ul>
 */
@DisplayName("CA contract ProtoStream registration — the list is complete")
class CaContractProtoRegistrationTest {

    /**
     * Every type that crosses the wire in either direction: the four operations this extension
     * CALLS (A1 upsert, A2 bulk upsert, A3 cancel, A5 find), the identity call behind the seat
     * list, the two doors Meeting Agent knocks on, and the shared messages nested inside them.
     *
     * <p>The nested shared types are listed explicitly even though they arrive transitively as
     * each leaf initializer's {@code dep0}: they are the ones a contract refresh is most likely to
     * drop silently, because nothing in this extension names their initializer directly.
     */
    static Stream<Class<?>> wireTypes() {
        return Stream.of(
                // A1 — upsertMeeting
                UpsertMeetingRequest.class, UpsertMeetingReply.class,
                // A2 — bulkUpsertMeetings
                BulkUpsertMeetingsRequest.class, BulkUpsertMeetingsReply.class,
                // A3 — cancelMeeting
                CancelMeetingRequest.class, CancelMeetingReply.class,
                // A5 — findMeetings
                FindMeetingsRequest.class, FindMeetingsReply.class,
                // ca.identity — the licensed-seat list the Always On sweep reads
                ListUsersRequest.class, ListUsersReply.class,
                // ca.identity — the scan inventory reported after each sweep
                ScannedMailboxSnapshotRequest.class, ScannedMailboxSnapshotReply.class,
                // §3.10 — the reminder-decide loop; this extension is the CONSUMER, so both ends
                // of the exchange have to encode here
                CalendarReminderDecideRequest.class, CalendarReminderDecideReply.class,
                // ext.calendar.auth.initiate.google — the reconnect door MA knocks on
                CalendarAuthInitiateCommandProto.class, CalendarAuthInitiateReplyProto.class,
                // ext.calendar.seat.registered.google — EF-14. This one is the most exposed of the
                // lot: its schema sits OUTSIDE the fingerprinted contract set, so the startup
                // handshake cannot report it missing and the staged jar can lack it entirely while
                // reporting a matching fingerprint. That is exactly how GLE's jar came to be five
                // entries short of KIP's under an identical .fingerprint.
                CalendarSeatRegisteredCommandProto.class,
                // ext.calendar.workspace.messages.google — KIM-492. Outside the fingerprinted set
                // for exactly the same reason as its seat-registered sibling above, so the staged
                // jar can lack it entirely under a matching .fingerprint. Google presents neither
                // message (the Krista Bot carries both in its BOT_CONFIG), but an unregistered
                // marshaller means the consumer cannot DECODE, so it never drains the durable
                // queue it exists to drain — and it stays healthy-looking while it fails.
                CalendarWorkspaceMessagesProto.class,
                // the shared wire types nested inside the messages above
                NativeMeetingProto.class, PersonProto.class, MeetingViewProto.class,
                UserViewProto.class, UpsertResultProto.class);
    }

    /**
     * Seeds a fresh context from the named initializers and nothing else — the shaded-jar
     * condition, in which ServiceLoader finds none of the contract jar's initializers.
     *
     * @param initializerFqcns the initializer class names to apply, in order
     * @return a context carrying exactly what those initializers register
     */
    private static SerializationContext buildIsolatedContext(List<String> initializerFqcns) {
        SerializationContext isolated = ProtobufUtil.newSerializationContext();
        for (String fqcn : initializerFqcns) {
            try {
                SerializationContextInitializer initializer = (SerializationContextInitializer)
                        Class.forName(fqcn).getDeclaredConstructor().newInstance();
                initializer.registerSchema(isolated);
                initializer.registerMarshallers(isolated);
            }
            catch (ReflectiveOperationException absent) {
                throw new AssertionError("CaContractProtoRegistration names an initializer that is"
                        + " not on the classpath at all: " + fqcn + ". The staged contract jar is"
                        + " missing or older than this build expects.", absent);
            }
        }
        return isolated;
    }

    @ParameterizedTest(name = "{0} has a marshaller")
    @MethodSource("wireTypes")
    @DisplayName("every wire type resolves from the registration list ALONE — no ServiceLoader")
    void hasAMarshaller(Class<?> wireType) {
        SerializationContext isolated =
                buildIsolatedContext(CaContractProtoRegistration.initializers());

        assertTrue(isolated.canMarshall(wireType),
                wireType.getSimpleName() + " has no marshaller once CaContractProtoRegistration's"
                        + " list is applied. In the shaded jar that list is the ONLY registration"
                        + " path — ServiceLoader finds none of the contract jar's initializers"
                        + " there — so every ESB message carrying this type fails to encode or"
                        + " decode, the consumer still binds, still reports itself healthy, and"
                        + " Meeting Agent sees a nameless transport failure. Add the owning"
                        + " initializer to CaContractProtoRegistration.INITIALIZERS.");
    }

    @ParameterizedTest(name = "{0} is load-bearing")
    @ValueSource(strings = {
            "app.krista.conversationagent.module.calendar.esb.ExtensionCalendarEsbProtoInitializerImpl",
            "app.krista.conversationagent.module.shared.esb.ext.ExtensionIdentityEsbProtoInitializerImpl",
            "app.krista.conversationagent.module.calendar.esb.CalendarReminderDecideProtoInitializerImpl",
            "app.krista.conversationagent.module.calendar.esb.CalendarAuthInitiateProtoInitializerImpl",
            "app.krista.conversationagent.module.calendar.esb.CalendarSeatRegisteredProtoInitializerImpl",
            "app.krista.conversationagent.module.calendar.esb.CalendarWorkspaceMessagesProtoInitializerImpl"})
    @DisplayName("dropping any listed initializer costs at least one wire type")
    void everyListedInitializerIsLoadBearing(String initializerFqcn) {
        List<String> listed = CaContractProtoRegistration.initializers();
        assertTrue(listed.contains(initializerFqcn),
                "CaContractProtoRegistration no longer names " + initializerFqcn + ". If the"
                        + " contract jar renamed it, update this test AND the production list"
                        + " together — the list is what registers it at runtime.");

        List<String> without = listed.stream().filter(fqcn -> !fqcn.equals(initializerFqcn)).toList();
        SerializationContext degraded = buildIsolatedContext(without);

        assertTrue(wireTypes().anyMatch(type -> !degraded.canMarshall(type)),
                "Removing " + initializerFqcn + " from CaContractProtoRegistration left every wire"
                        + " type still resolvable, so nothing in this suite would go red if the"
                        + " entry were deleted. Either the entry is genuinely redundant and should"
                        + " go, or wireTypes() is missing the type it exists to carry.");
    }

    @Test
    @DisplayName("the shared ProtoWire context is seeded too — the path production actually encodes on")
    void registerAllSeedsTheSharedContext() {
        int registered = CaContractProtoRegistration.registerAll();

        assertFalse(CaContractProtoRegistration.initializers().isEmpty(),
                "An empty list registers nothing and would make every assertion above vacuous.");
        assertEquals(CaContractProtoRegistration.initializers().size(), registered,
                "registerAll() reported fewer initializers registered than it names. A shortfall"
                        + " means a class is absent from the classpath, which in a deployed jar is"
                        + " a staged contract jar that does not match this build.");
    }
}
