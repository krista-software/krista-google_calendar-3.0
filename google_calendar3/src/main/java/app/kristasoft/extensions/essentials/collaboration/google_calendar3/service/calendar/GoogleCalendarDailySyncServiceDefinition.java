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

import app.krista.conversationagent.module.calendar.esb.CalendarDailySyncCommandProto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.CaContractProtoRegistration;
import app.krista.infra.esb.ServiceBusConfig;
import app.krista.infra.esb.ServiceIdentity;
import app.krista.infra.esb.service.ApiDefinition;
import app.krista.infra.esb.service.ServiceDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * Service definition for the nightly calendar-sweep consumer (KIM-492).
 *
 * <p>Manually authored, like every service definition in this extension: the protostream and ESB
 * annotation processors cannot run under Gradle here. Mirrors teams-transcribe's
 * {@code KipCalendarDailySyncServiceDefinition} so both extensions' halves of one Meeting Agent
 * feature have the same shape.
 *
 * <p>The command type comes from the staged contract jar, and its marshaller is registered through
 * {@link CaContractProtoRegistration} rather than by ServiceLoader — this module's shaded jar
 * excludes every dependency's {@code META-INF/services} initializer file. That matters here for the
 * same reason as the seat-registered schema: this one also sits OUTSIDE the fingerprinted contract
 * set, so the startup handshake cannot report it missing.
 *
 * @since 3.1.0
 */
public class GoogleCalendarDailySyncServiceDefinition implements ServiceDefinition {

    private static final ServiceIdentity IDENTITY = ServiceIdentity.create(
            GoogleCalendarDailySyncService.class,
            "GoogleCalendarDailySync",
            "GoogleCalendarDailySync-1",
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
     * <p>Durable because a missed nightly command is a missed night of reconciliation — the sweep is
     * the floor under the watch-channel path, so losing one on an extension restart is exactly when
     * it is most likely to have been needed. Note the handler acknowledges a FAILED sweep
     * deliberately (see {@code GoogleCalendarDailySyncHandler}), so this setting buys ordering, not
     * retries.
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
        APIS.put("googleCalendarDailySync",
                new ApiDefinition("googleCalendarDailySync", IDENTITY.serviceName(),
                        CalendarDailySyncCommandProto.class, null));
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
