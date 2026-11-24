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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.CaContractProtoRegistration;
import app.krista.infra.esb.ServiceBusConfig;
import app.krista.infra.esb.ServiceIdentity;
import app.krista.infra.esb.service.ApiDefinition;
import app.krista.infra.esb.service.ServiceDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * Service definition for the calendar reminder-DECIDE ESB consumer (KIM-492 WP-E16).
 *
 * <p>Manually authored: the protostream and ESB annotation processors cannot run under Gradle here
 * ({@code ExtensionAnnotationProcessor} claims all annotations), which is why every service
 * definition in this extension is hand-written. Mirrors
 * {@link GoogleCalendarAuthInitiateServiceDefinition}, the other request/reply consumer.
 *
 * <p>The request and reply types come from the staged contract jar, so there is exactly one
 * definition of this wire shape and it is Meeting Agent's. Their marshallers are registered through
 * {@link CaContractProtoRegistration} rather than the usual
 * {@code ProtoWire.ensureRegisteredForService(...)} convention, because that convention derives
 * {@code <ServiceName>InitializerImpl} from the service interface and these DTOs live under MA's own
 * package with a generated initializer named for the SCHEMA, not for this service. See that class
 * for the full reason — including why the jar's {@code META-INF/services} entry does not survive
 * shading.
 *
 * @since 3.1.0
 */
public class GoogleCalendarReminderDecideServiceDefinition implements ServiceDefinition {

    private static final ServiceIdentity IDENTITY = ServiceIdentity.create(
            GoogleCalendarReminderDecideService.class,
            "GoogleCalendarReminderDecide",
            "GoogleCalendarReminderDecide-1",
            1);

    private static final Map<String, ApiDefinition> apis = new HashMap<>();

    public static ServiceIdentity identity() {
        return IDENTITY;
    }

    public static ServiceBusConfig config() {
        return new ServiceBusConfig()
                .ensureDestinationBaseName(IDENTITY.baseDestination())
                .setVersion(1)
                .setQueueDurable(true)
                .setProcessorAutoAcknowledge(false);
    }

    static {
        CaContractProtoRegistration.registerAll();
        apis.put("googleCalendarReminderDecide",
                new ApiDefinition("googleCalendarReminderDecide", IDENTITY.serviceName(),
                        CalendarReminderDecideRequest.class,
                        CalendarReminderDecideReply.class));
        apis.put("serviceAvailable",
                new ApiDefinition("serviceAvailable", IDENTITY.serviceName(),
                        app.krista.infra.esb.client.ServiceAvailableInput.class,
                        app.krista.infra.esb.service.ServiceAvailableReply.class));
    }

    @Override
    public ApiDefinition getApiDefinition(String apiName) {
        return apis.get(apiName);
    }
}
