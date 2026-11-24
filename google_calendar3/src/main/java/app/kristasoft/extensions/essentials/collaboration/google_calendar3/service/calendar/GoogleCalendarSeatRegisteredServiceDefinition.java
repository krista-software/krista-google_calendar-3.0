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

import app.krista.conversationagent.module.calendar.esb.CalendarSeatRegisteredCommandProto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.CaContractProtoRegistration;
import app.krista.infra.esb.ServiceBusConfig;
import app.krista.infra.esb.ServiceIdentity;
import app.krista.infra.esb.service.ApiDefinition;
import app.krista.infra.esb.service.ServiceDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * Service definition for the licensed-seat notification consumer (KIM-474 / EF-14).
 *
 * <p>Manually authored, like every service definition in this extension: the protostream and ESB
 * annotation processors cannot run under Gradle here ({@code ExtensionAnnotationProcessor} claims all
 * annotations). Mirrors teams-transcribe's {@code KipCalendarSeatRegisteredServiceDefinition} so both
 * extensions' halves of one Meeting Agent feature have the same shape.
 *
 * <p>The command type comes from the staged contract jar, so there is exactly one definition of this
 * wire shape and it is Meeting Agent's. Its marshaller is registered through
 * {@link CaContractProtoRegistration} rather than by ServiceLoader, because this module's shaded jar
 * excludes every dependency's {@code META-INF/services} initializer file — see that class for the
 * full reason. That matters more here than elsewhere: the seat-registered schema sits OUTSIDE the
 * fingerprinted contract set, so the startup handshake cannot report it missing.
 *
 * <p>The reply type is {@code null}: this is a one-way command and Meeting Agent does not wait.
 *
 * @since 3.1.0
 */
public class GoogleCalendarSeatRegisteredServiceDefinition implements ServiceDefinition {

    private static final ServiceIdentity IDENTITY = ServiceIdentity.create(
            GoogleCalendarSeatRegisteredService.class,
            "GoogleCalendarSeatRegistered",
            "GoogleCalendarSeatRegistered-1",
            1);

    private static final Map<String, ApiDefinition> APIS = new HashMap<>();

    public static ServiceIdentity identity() {
        return IDENTITY;
    }

    public static String routingBase() {
        return IDENTITY.baseDestination();
    }

    /**
     * Durable and manual-acknowledge, matching every other consumer here.
     *
     * <p>Durable because the event is Meeting Agent's only push signal about a new seat: dropping it
     * on an extension restart puts the seat back on the scheduled-refresh window, which is the delay
     * this consumer exists to remove. Manual ack because auto-ack would acknowledge before the
     * reconcile has run — though note the handler acknowledges a FAILED reconcile deliberately (see
     * {@code GoogleCalendarSeatRegisteredHandler}), so this setting buys ordering, not retries.
     *
     * @return the consumer config
     */
    public static ServiceBusConfig config() {
        return new ServiceBusConfig()
                .ensureDestinationBaseName(IDENTITY.baseDestination())
                .setVersion(1)
                .setQueueDurable(true)
                .setProcessorAutoAcknowledge(false);
    }

    static {
        CaContractProtoRegistration.registerAll();
        APIS.put("googleCalendarSeatRegistered",
                new ApiDefinition("googleCalendarSeatRegistered", IDENTITY.serviceName(),
                        CalendarSeatRegisteredCommandProto.class, null));
        APIS.put("serviceAvailable",
                new ApiDefinition("serviceAvailable", IDENTITY.serviceName(),
                        app.krista.infra.esb.client.ServiceAvailableInput.class,
                        app.krista.infra.esb.service.ServiceAvailableReply.class));
    }

    @Override
    public ApiDefinition getApiDefinition(String apiName) {
        return APIS.get(apiName);
    }
}
