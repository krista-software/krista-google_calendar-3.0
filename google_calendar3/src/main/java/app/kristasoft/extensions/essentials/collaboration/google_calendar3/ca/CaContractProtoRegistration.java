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

import app.krista.infra.esb.proto.ProtoWire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Registers the staged contract jar's ProtoStream initializers with {@code ProtoWire}
 * (KIM-492 WP-E15).
 *
 * <h2>Why this exists rather than ServiceLoader discovery</h2>
 *
 * <p>The contract jar ships a {@code META-INF/services/…SerializationContextInitializer} naming
 * every initializer it contains. That is not enough here, and the reason is in this module's own
 * {@code jar} task: the shaded jar is assembled with {@code duplicatesStrategy = "exclude"} AND it
 * explicitly excludes the dependency copies of that services file, so of the two files named
 * {@code META-INF/services/org.infinispan.protostream.SerializationContextInitializer} — this
 * extension's own and the contract jar's — exactly ONE survives, and it is this extension's,
 * deliberately (see the {@code jar} task comment in {@code build.gradle}). Nothing on the contract
 * jar's list is discovered at runtime.
 *
 * <p>The failure that causes is quiet and late. Nothing complains at build time; the classes are
 * all present; the consumer binds its queue and reports itself healthy; and the first request from
 * Meeting Agent fails to decode with "no ProtoStream marshaller registered for input" — which a
 * calendar reconnect surfaces as an HTTP 503 {@code EXT_TIMEOUT} on MA's side rather than as
 * anything naming this extension.
 *
 * <p>The generated client's own {@code ProtoWire.ensureRegisteredForService(...)} does not save us
 * either: it derives the initializer name from the service's simple name
 * ({@code <ServiceSimpleName>InitializerImpl}), and the real classes are named for their schema
 * rather than their service. That lookup misses, and a genuine miss is (correctly) treated as
 * benign absence.
 *
 * <p>So registration is explicit, by name, from the one list below.
 * {@code ensureRegisteredByName} is idempotent and guarded per FQCN, so calling this repeatedly
 * costs nothing — which matters because this extension's config is constructed once per tenant in
 * a multi-tenant pod.
 *
 * <h2>What is registered</h2>
 *
 * <p>Only the leaves this extension speaks. Each generated initializer registers its own
 * dependencies first, so shared wire types ({@code PersonProto}, {@code NativeMeetingProto},
 * {@code MeetingViewProto}, {@code UserViewProto}, …) arrive transitively and must not be listed
 * again here.
 *
 * <ul>
 *   <li><b>{@code CalendarAuthInitiateProtoInitializerImpl}</b> — the
 *       {@code ext.calendar.auth.initiate.google} door MA knocks on. This is the entry that is
 *       easiest to forget, because a missing one costs nothing at build time and nothing at
 *       startup. It is also invisible in CI: the test classpath is NOT shaded, so ServiceLoader
 *       still finds the contract jar's services file there and quietly supplies what a deployed
 *       jar would be missing. {@code CalendarAuthInitiateWireTest} therefore does not trust the
 *       shared registry — it seeds a fresh context from this one initializer alone.</li>
 *   <li><b>{@code ExtensionCalendarEsbProtoInitializerImpl}</b> ({@code ca.calendar-1-IN}) and
 *       <b>{@code ExtensionIdentityEsbProtoInitializerImpl}</b> ({@code ca.identity-1-IN}) — the
 *       two services this extension CALLS (WP-E16). Their generated initializers each pull the
 *       shared wire schema in as a dependency, so {@code MeetingViewProto}, {@code PersonProto},
 *       {@code NativeMeetingProto}, {@code UserViewProto} and friends arrive with them and must
 *       NOT be listed again here.</li>
 *   <li><b>{@code CalendarReminderDecideProtoInitializerImpl}</b>
 *       ({@code ext.calendar.reminder.decide.google}) — the second request/reply door MA knocks
 *       on, added in WP-E16.</li>
 *   <li><b>{@code CalendarSeatRegisteredProtoInitializerImpl}</b>
 *       ({@code ext.calendar.seat.registered.google}) — the licensed-seat notification, EF-14. Its
 *       schema sits OUTSIDE the fingerprinted set (esb-transport-progress-report.md:147), so the
 *       contract handshake cannot tell you it is missing; this list and
 *       {@code CaContractProtoRegistrationTest} are the only things that can.</li>
 *   <li><b>{@code CalendarWorkspaceMessagesProtoInitializerImpl}</b>
 *       ({@code ext.calendar.workspace.messages.google}) — the in-call messages broadcast, KIM-492.
 *       Outside the fingerprinted set for the same reason as its seat-registered sibling, so the same
 *       two guards are the only ones that can report it absent.</li>
 * </ul>
 *
 * @since 3.1.0
 */
public final class CaContractProtoRegistration {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaContractProtoRegistration.class);

    /**
     * The contract jar's leaf initializers, by fully-qualified name.
     *
     * <p>Named as strings on purpose: this class must not hard-link the generated types, so a
     * contract refresh that renames one produces a clear startup WARN naming the missing class
     * rather than a compile error in an unrelated file.
     */
    private static final List<String> INITIALIZERS = List.of(
            "app.krista.conversationagent.module.calendar.esb.ExtensionCalendarEsbProtoInitializerImpl",
            "app.krista.conversationagent.module.shared.esb.ext.ExtensionIdentityEsbProtoInitializerImpl",
            "app.krista.conversationagent.module.calendar.esb.CalendarReminderDecideProtoInitializerImpl",
            "app.krista.conversationagent.module.calendar.esb.CalendarAuthInitiateProtoInitializerImpl",
            "app.krista.conversationagent.module.calendar.esb.CalendarSeatRegisteredProtoInitializerImpl",
            "app.krista.conversationagent.module.calendar.esb.CalendarWorkspaceMessagesProtoInitializerImpl",
            "app.krista.conversationagent.module.calendar.esb.CalendarDailySyncProtoInitializerImpl");

    private CaContractProtoRegistration() {
    }

    /**
     * Registers every contract initializer, idempotently.
     *
     * <p>Safe to call from several places and on several threads — {@code ProtoWire} guards each
     * FQCN with its own latch. A genuinely absent class is logged at WARN and skipped: that means
     * the staged jar is missing or older than this build expects, which the contract handshake
     * reports far more usefully than an exception here would.
     *
     * @return the number of initializers that are now registered
     */
    public static int registerAll() {
        int registered = 0;
        for (String fqcn : INITIALIZERS) {
            try {
                if (ProtoWire.ensureRegisteredByName(fqcn)) {
                    registered++;
                }
                else {
                    LOGGER.warn("Contract ProtoStream initializer {} is not on the classpath. Every ESB"
                            + " message that needs it will fail to decode or encode. Check that"
                            + " libs/conversation-agent-contracts-*.jar is on the runtime classpath.", fqcn);
                }
            }
            catch (RuntimeException failure) {
                // Present but unregisterable — almost always a protostream version split. Surface
                // it and keep going: the remaining initializers may still be usable, and the
                // per-operation failure message names the exact type that could not be handled.
                LOGGER.error("Contract ProtoStream initializer {} could not be registered: {}",
                        fqcn, failure.getMessage(), failure);
            }
        }
        LOGGER.info("CA contract ProtoStream initializers registered: {}/{}", registered, INITIALIZERS.size());
        return registered;
    }

    /** @return the initializer list, for the registration test */
    public static List<String> initializers() {
        return INITIALIZERS;
    }
}
