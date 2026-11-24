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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar;

import app.krista.conversationagent.module.calendar.esb.dto.CalendarReminderDecideReply;
import app.krista.conversationagent.module.calendar.esb.dto.CalendarReminderDecideRequest;
import app.krista.infra.esb.ServiceBusConfig;
import app.krista.infra.esb.ServiceIdentity;
import app.krista.infra.esb.service.ApiDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reminder-DECIDE service's wire contract — the names that must agree, and the queue
 * properties the reminder loop depends on.
 *
 * <h2>Why this needs a test at all</h2>
 *
 * <p>Neither the interface nor the definition has any behaviour to exercise, which is why both were
 * uncovered. But between them they carry several separately-written strings that must line up for a
 * message to arrive: the identity's versioned service name, its base destination, the key in the
 * definition's {@code apis} map, and the interface's method name. The annotation processors cannot
 * run in this build ({@code ExtensionAnnotationProcessor} claims all annotations), so every service
 * definition here is hand-written — nothing derives one string from another and nothing checks them.
 *
 * <p>A mismatch does not fail to start. The consumer binds, the queue exists, Meeting Agent produces
 * to it, and the message is never dispatched to a handler — so the reminder loop times out, Meeting
 * Agent falls back to the one-way queue, and (before EF-08) the drain consumer destroyed it. An
 * invisible failure assembled from a typo, which is what makes it worth pinning.
 */
@DisplayName("Reminder-decide service — wire contract")
class GoogleCalendarReminderDecideServiceContractTest {

    /** The one API name, written once here and compared against all four places it appears. */
    private static final String API_NAME = "googleCalendarReminderDecide";

    /** The unique service name Meeting Agent addresses. Changing it is a breaking change. */
    private static final String SERVICE_NAME = "GoogleCalendarReminderDecide";

    @Test
    @DisplayName("the interface declares exactly one operation, named for the API")
    void interfaceDeclaresTheApi() throws Exception {
        Method[] declared = GoogleCalendarReminderDecideService.class.getDeclaredMethods();
        assertEquals(1, declared.length,
                "a second operation on this interface needs a matching ApiDefinition, or it is "
                        + "unreachable over the bus");

        Method decide = GoogleCalendarReminderDecideService.class
                .getMethod(API_NAME, CalendarReminderDecideRequest.class);
        assertEquals(CalendarReminderDecideReply.class, decide.getReturnType(),
                "a void return makes this one-way again, which is the shape WP-E16 replaced — the "
                        + "reply is how Meeting Agent learns the verdict");
    }

    /**
     * The {@code @KristaService} annotation itself is deliberately NOT asserted here.
     * {@code app.krista.infra.esb.annotation.KristaService} lives in the
     * {@code esb-annotation-processor} artifact, which this build declares {@code compileOnly} — so it
     * is absent from the test classpath by design, and widening a build dependency to reach an
     * annotation would be a larger change than the assertion is worth. What IS asserted is the pair
     * that decides delivery at runtime: the identity the consumer registers under, and the interface
     * method the definition maps.
     */
    @Test
    @DisplayName("the identity's service name and base destination are the expected wire values")
    void namesAgree() {
        ServiceIdentity identity = GoogleCalendarReminderDecideServiceDefinition.identity();

        // The two are NOT the same string, and which is which matters: serviceName() is the
        // VERSIONED dispatch key the consumer registers the handler under (svc.addService) and that
        // Meeting Agent puts on the message; baseDestination() is the unversioned queue base name.
        // Swapping them binds a queue nobody produces to and registers a handler nobody addresses,
        // and neither failure errors — both go silent.
        assertEquals(SERVICE_NAME + "-1", identity.serviceName(),
                "serviceName is the versioned dispatch key: a different value registers the handler "
                        + "under a name no message carries, so the consumer starts, the queue fills, "
                        + "and nothing is ever handled");
        assertEquals(SERVICE_NAME, identity.baseDestination(),
                "renaming the base destination is a breaking change against every deployed Meeting "
                        + "Agent producer, and it fails by going silent rather than by erroring");
    }

    @Test
    @DisplayName("the definition maps the API name to Meeting Agent's own request and reply types")
    void definitionMapsTheApi() {
        ApiDefinition decide =
                new GoogleCalendarReminderDecideServiceDefinition().getApiDefinition(API_NAME);

        assertNotNull(decide,
                "the apis map key must equal the interface method name — krista-infra dispatches by "
                        + "the API name on the message, and an unmapped name is silently undelivered");
        assertEquals(CalendarReminderDecideRequest.class, decide.requestDTOClass());
        assertEquals(CalendarReminderDecideReply.class, decide.replyDTOClass());
        assertTrue(CalendarReminderDecideRequest.class.getName()
                        .startsWith("app.krista.conversationagent."),
                "the wire types must be Meeting Agent's, from the staged contract jar — a local copy "
                        + "is a second definition of one wire shape and they drift");
    }

    @Test
    @DisplayName("serviceAvailable stays mapped, so the health probe is answerable")
    void serviceAvailableIsMapped() {
        assertNotNull(new GoogleCalendarReminderDecideServiceDefinition()
                        .getApiDefinition("serviceAvailable"),
                "krista-infra probes this before treating the consumer as reachable");
    }

    @Test
    @DisplayName("an unknown API name maps to nothing rather than to a default")
    void unknownApiIsNotMapped() {
        assertFalse(new GoogleCalendarReminderDecideServiceDefinition()
                .getApiDefinition("somethingElse") != null);
    }

    /**
     * The reminder loop depends on both of these. A non-durable queue loses Meeting Agent's question
     * across an extension restart — and the reminder with it, because Meeting Agent has already
     * stamped the attempt. Auto-acknowledge would ack before the verdict is computed, so a handler
     * fault would consume the question and answer nothing.
     */
    @Test
    @DisplayName("the queue is durable and acknowledgement is manual")
    void queuePropertiesAreDeliberate() {
        ServiceBusConfig config = GoogleCalendarReminderDecideServiceDefinition.config();

        assertTrue(config.isQueueDurable(),
                "a non-durable queue drops MA's question on an extension restart, and MA has already "
                        + "stamped the reminder as attempted");
        assertFalse(config.isProcessorAutoAcknowledge(),
                "auto-ack would acknowledge before the verdict is computed, so a handler fault eats "
                        + "the question and sends nothing");
        assertEquals(1, config.version());
        assertNotNull(config.destinationBaseName());
        assertEquals(GoogleCalendarReminderDecideServiceDefinition.identity().baseDestination(),
                config.destinationBaseName(),
                "the config must bind the identity's destination, not a second literal");
    }
}
