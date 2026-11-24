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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.config;

import app.krista.infra.esb.ServiceBusConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Contract test against the krista-infra version on THIS classpath: setting the
 * {@code broker.dest.namespace} system property (exactly what {@link EsbEndpointBinding}
 * does when the CA handshake reports {@code esbNamespace}) MUST prefix the physical
 * destination names {@code ServiceBusConfig} resolves.
 *
 * <p>krista-infra 1.1.432 silently ignored the property on the consumer queue-name
 * path — the extension bound its consumers to the un-prefixed queues of the shared
 * broker regardless of the handshake (KIM-451 preview-isolation failure, observed
 * live 2026-08-12). If this test fails, the infra pin has regressed that fix.
 */
class EsbNamespaceInfraContractTest {

    private static final String NAMESPACE_PROPERTY = "broker.dest.namespace";

    @AfterEach
    void clearNamespaceProperty() {
        System.clearProperty(NAMESPACE_PROPERTY);
    }

    @Test
    void systemPropertyNamespacePrefixesPhysicalQueueName() {
        System.setProperty(NAMESPACE_PROPERTY, "preview");

        ServiceBusConfig config = new ServiceBusConfig()
                .setDestinationBaseName("krista.calendar.auth.initiate.google")
                .setVersion(1);

        assertEquals("preview.krista.calendar.auth.initiate.google-1-IN", config.inQueueName(),
                "broker.dest.namespace set via System property must prefix the consumer queue name");
    }

    @Test
    void noNamespaceLeavesQueueNameUnchanged() {
        System.clearProperty(NAMESPACE_PROPERTY);

        ServiceBusConfig config = new ServiceBusConfig()
                .setDestinationBaseName("krista.calendar.auth.initiate.google")
                .setVersion(1);

        assertEquals("krista.calendar.auth.initiate.google-1-IN", config.inQueueName());
    }
}
