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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.CalendarConnectionStatusChangedProto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.CalendarReminderSendProto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarAuthInitiateServiceDefinition;
import app.krista.infra.esb.proto.ProtoWire;
import app.krista.infra.esb.service.ApiDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KIM-492 WP-E15 — the calendar auth-initiate consumer still encodes and decodes, now against the
 * staged contract jar's proto rather than a hand-authored copy of it.
 *
 * <p><b>What moved.</b> Meeting Agent ships {@code CalendarAuthInitiateCommandProto} /
 * {@code CalendarAuthInitiateReplyProto} in its {@code :contracts} project, so the staged jar
 * carries the schema ({@code proto/ext/calendar/calendar-auth-initiate.proto}). This extension's
 * hand-authored pair, their two marshallers, and the one-line subclass that used to satisfy
 * {@code Class.forName(dtoClass)} under MA's FQN were all deleted in the same change.
 *
 * <p><b>The two failures this pins, neither of which the compiler can see.</b>
 *
 * <ol>
 *   <li><b>A duplicate message name.</b> This extension's {@code calendar-reconnect.proto}
 *       declares its messages under MA's protostream package, deliberately, for wire
 *       compatibility. The jar's auth-initiate schema declares that same package and the same two
 *       message names, so registering both while a local copy still existed would make protostream
 *       reject the duplicate and the consumer would never start. {@link #registerBothSchemas()}
 *       puts BOTH initializers into one context — the production condition — so a reintroduced
 *       copy fails here rather than on a customer's first reconnect.</li>
 *   <li><b>A marshaller nobody registered.</b> The jar's {@code META-INF/services} entry does not
 *       survive this module's shaded {@code jar} task (which excludes every dependency's copy on
 *       purpose), so the auth-initiate initializer reaches the runtime only because
 *       {@link CaContractProtoRegistration} names it explicitly. Drop it from that list and
 *       everything still compiles, the consumer still binds, and the first request from MA fails
 *       to decode. CI cannot see that on its own: the test classpath is NOT shaded, so
 *       ServiceLoader would quietly supply what a deployed jar lacks — which is why
 *       {@link #theRegistrationListNamesTheAuthInitiateInitializer()} asserts the LIST rather
 *       than trusting the shared registry.</li>
 * </ol>
 *
 * <p>The wire itself did not move: field numbers, names and types were diffed against the deleted
 * hand-authored pair before the swap, and {@link #theWireTypeNameIsTheOneCaStamps()} pins the one
 * name both sides have to agree on.
 */
@DisplayName("Calendar auth-initiate — wire contract from the staged jar")
class CalendarAuthInitiateWireTest {

    /** The protostream type name and the {@code dtoClass} header value are the same string. */
    private static final String WIRE_TYPE_NAME =
            "app.krista.conversationagent.module.calendar.esb.CalendarAuthInitiateCommandProto";

    @BeforeAll
    static void registerBothSchemas() {
        CaContractProtoRegistration.registerAll();

        // This extension's own calendar schema shares the protostream package with the jar's.
        // Registering it into the same context is exactly what happens at startup, and is the
        // collision check.
        assertTrue(ProtoWire.ensureRegisteredByName(
                        "app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb"
                                + ".CalendarReconnectEsbProtoInitializerImpl"),
                "This extension's calendar-reconnect schema could not be registered alongside the"
                        + " contract jar's auth-initiate schema — the two declare the same"
                        + " protostream package, so a duplicate message name is the likely cause.");
    }

    @Test
    @DisplayName("the command MA sends round-trips through the jar's marshaller")
    void commandRoundTrips() throws Exception {
        CalendarAuthInitiateCommandProto sent = new CalendarAuthInitiateCommandProto();
        sent.setWorkspaceId("ws-1");
        sent.setUserEmail("user@example.com");
        sent.setCalendarSystem("GOOGLE");
        sent.setCorrelationId("corr-42");

        CalendarAuthInitiateCommandProto received =
                ProtoWire.decode(ProtoWire.encode(sent), CalendarAuthInitiateCommandProto.class);

        assertEquals("ws-1", received.getWorkspaceId());
        assertEquals("user@example.com", received.getUserEmail());
        assertEquals("GOOGLE", received.getCalendarSystem());
        assertEquals("corr-42", received.getCorrelationId());
    }

    @Test
    @DisplayName("the reply this extension returns round-trips through the jar's marshaller")
    void replyRoundTrips() throws Exception {
        CalendarAuthInitiateReplyProto sent = new CalendarAuthInitiateReplyProto();
        sent.setOk(true);
        sent.setAuthUrl("https://accounts.google.com/o/oauth2/auth");
        sent.setAuthInitiatedAtEpochMillis(1_757_000_000_000L);
        sent.setExpiresAtEpochMillis(1_757_000_600_000L);

        CalendarAuthInitiateReplyProto received =
                ProtoWire.decode(ProtoWire.encode(sent), CalendarAuthInitiateReplyProto.class);

        assertTrue(received.isOk());
        assertEquals("https://accounts.google.com/o/oauth2/auth", received.getAuthUrl());
        assertEquals(1_757_000_000_000L, received.getAuthInitiatedAtEpochMillis());
        assertEquals(1_757_000_600_000L, received.getExpiresAtEpochMillis());
    }

    @Test
    @DisplayName("an error reply carries its code and message back")
    void errorReplyRoundTrips() throws Exception {
        CalendarAuthInitiateReplyProto sent = new CalendarAuthInitiateReplyProto();
        sent.setOk(false);
        sent.setErrorCode("NOT_REGISTERED");
        sent.setErrorMessage("no Google OAuth config for workspace ws-1");

        CalendarAuthInitiateReplyProto received =
                ProtoWire.decode(ProtoWire.encode(sent), CalendarAuthInitiateReplyProto.class);

        assertFalse(received.isOk());
        assertEquals("NOT_REGISTERED", received.getErrorCode());
        assertEquals("no Google OAuth config for workspace ws-1", received.getErrorMessage());
    }

    @Test
    @DisplayName("the wire type name is the FQN MA stamps on dtoClass")
    void theWireTypeNameIsTheOneCaStamps() {
        assertEquals(WIRE_TYPE_NAME, CalendarAuthInitiateCommandProto.class.getName(),
                "MA's RequestReplyClient sends dtoClass = command.getClass().getName() and this"
                        + " extension resolves it with Class.forName, so the class it compiles"
                        + " against must sit at exactly that FQN.");
        assertEquals(WIRE_TYPE_NAME, ProtoWire.getMessageTypeName(CalendarAuthInitiateCommandProto.class),
                "The protostream message name must match MA's too — a mismatch decodes as a"
                        + " missing type rather than as wrong data, but only at request time.");
    }

    @Test
    @DisplayName("the consumer's ApiDefinition names the contract types, not a local copy")
    void theServiceDefinitionNamesTheContractTypes() {
        ApiDefinition api = new GoogleCalendarAuthInitiateServiceDefinition()
                .getApiDefinition("googleCalendarAuthInitiate");

        assertNotNull(api, "googleCalendarAuthInitiate is the API name MA sends; without a"
                + " definition for it the consumer cannot dispatch the request at all.");
        assertSame(CalendarAuthInitiateCommandProto.class, api.requestDTOClass(),
                "Class.forName(dtoClass) resolves the jar's type, so declaring anything else here"
                        + " means the dispatcher expects a type the converter will never produce.");
        assertSame(CalendarAuthInitiateReplyProto.class, api.replyDTOClass());
    }

    @Test
    @DisplayName("the registration list names the auth-initiate initializer explicitly")
    void theRegistrationListNamesTheAuthInitiateInitializer() {
        assertTrue(CaContractProtoRegistration.initializers().contains(
                        "app.krista.conversationagent.module.calendar.esb"
                                + ".CalendarAuthInitiateProtoInitializerImpl"),
                "The shaded jar drops the contract jar's META-INF/services file, so this list is"
                        + " the ONLY thing that registers the auth-initiate marshallers in a"
                        + " deployed extension. The test classpath is not shaded, so removing the"
                        + " entry would still pass every round-trip test above.");
    }

    @Test
    @DisplayName("this extension's remaining calendar protos still resolve")
    void remainingCalendarProtosStillResolve() {
        assertNotNull(ProtoWire.getMessageTypeName(CalendarReminderSendProto.class),
                "CalendarReminderSendProto stays hand-authored here until Stage 3 — MA keeps it in"
                        + " its :calendar project, so the contract jar does not supply it.");
        assertNotNull(ProtoWire.getMessageTypeName(CalendarConnectionStatusChangedProto.class),
                "CalendarConnectionStatusChangedProto is published by this extension and is not in"
                        + " the contract jar either.");
    }
}
