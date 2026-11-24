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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb;

import app.krista.infra.esb.proto.ProtoWire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers this extension's protobuf schema + marshallers with the ESB's shared
 * serialization context so outbound proto events can be encoded on the publish path.
 *
 * <p>The ESB infrastructure ({@code app.krista.infra.esb}) keeps a single static
 * {@link org.infinispan.protostream.SerializationContext} inside
 * {@code app.krista.infra.esb.proto.ProtoWire}. That context — and only that context — is
 * what {@code ProtoWire.encode()} consults when serializing a request payload
 * ({@code EventProducer -> QRequester -> MessageDTOConverter -> ProtoWire.encode}). An
 * extension must therefore hand its initializer to {@link ProtoWire#registerInitializer} so
 * its marshallers land on that shared context. (Registering onto a fresh
 * {@code ProtobufUtil.newSerializationContext()} is a no-op for the publish path — that was
 * the cause of the "No marshaller registered for CalendarConnectionStatusChangedProto" failures.)
 *
 * <p>This mirrors KIP's {@code ApplicationInitializer.init()}, which calls
 * {@code ProtoWire.registerFromServiceLoader()} before starting its consumers. We register the
 * initializer instance directly instead of via ServiceLoader so registration does not depend on
 * the thread-context classloader (the extension constructor may run on threads whose TCCL is not
 * the extension classloader).
 */
public final class EsbProtoRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(EsbProtoRegistrar.class);

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private EsbProtoRegistrar() {
    }

    /**
     * Idempotent (once-per-classloader) registration of the calendar-reconnect proto schema and
     * marshallers onto {@link ProtoWire}'s shared serialization context. Guarded because the
     * extension constructor runs once per tenant in a multi-tenant pod.
     */
    public static void ensureRegistered() {
        if (REGISTERED.compareAndSet(false, true)) {
            ProtoWire.registerInitializer(new CalendarReconnectEsbProtoInitializerImpl());
            LOG.info("Registered calendar-reconnect proto marshallers with ESB ProtoWire context.");
        }
    }
}
