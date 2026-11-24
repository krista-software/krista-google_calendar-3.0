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

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.CalendarReminderSendProto;
import app.krista.infra.esb.ServiceBusConfig;
import app.krista.infra.esb.ServiceIdentity;
import app.krista.infra.esb.service.ApiDefinition;
import app.krista.infra.esb.service.ServiceDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * Service definition for the Google Calendar reminder-send ESB consumer.
 *
 * <p>Manually authored (annotation processor cannot run under Gradle).
 * Mirrors the structure of {@code GoogleCalendarAuthInitiateServiceDefinition}
 * and {@code KipCalendarReminderSendServiceDefinition}.
 *
 * @since 3.0.3
 */
public class GoogleCalendarReminderSendServiceDefinition implements ServiceDefinition {

    private static final ServiceIdentity IDENTITY = ServiceIdentity.create(
            GoogleCalendarReminderSendService.class,
            "GoogleCalendarReminderSend",
            "GoogleCalendarReminderSend-1",
            1);

    private static final Map<String, ApiDefinition> apis = new HashMap<>();

    public static ServiceIdentity identity() { return IDENTITY; }

    public static ServiceBusConfig config() {
        return new ServiceBusConfig()
                .ensureDestinationBaseName(IDENTITY.baseDestination())
                .setVersion(1)
                .setQueueDurable(true)
                .setProcessorAutoAcknowledge(false);
    }

    static {
        app.krista.infra.esb.proto.ProtoWire.ensureRegisteredForService(
                GoogleCalendarReminderSendService.class);
        apis.put("googleCalendarReminderSend",
                new ApiDefinition("googleCalendarReminderSend", IDENTITY.serviceName(),
                        CalendarReminderSendProto.class,
                        null));
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
