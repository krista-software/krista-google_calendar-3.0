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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the root cause of the "No marshaller registered for ...CalendarConnectionStatusChangedProto"
 * publish failure: the extension's proto marshallers MUST be registered onto {@link ProtoWire}'s
 * shared serialization context — the same context {@code ProtoWire.encode()} consults on the
 * publish path ({@code EventProducer -> QRequester -> MessageDTOConverter -> ProtoWire.encode}).
 *
 * <p>Registering onto a throwaway {@code ProtobufUtil.newSerializationContext()} (the old behavior)
 * leaves {@code ProtoWire.context()} empty, so {@code encode()} throws at runtime.
 */
class EsbProtoRegistrarTest {

    @Test
    void ensureRegistered_putsCalendarMarshallersOnProtoWireSharedContext() {
        EsbProtoRegistrar.ensureRegistered();

        assertTrue(ProtoWire.context().canMarshall(CalendarConnectionStatusChangedProto.class),
                "CalendarConnectionStatusChangedProto must be marshallable via ProtoWire's shared context");
        assertTrue(ProtoWire.context().canMarshall(CalendarReminderSendProto.class),
                "CalendarReminderSendProto must be marshallable via ProtoWire's shared context");

        // The auth-initiate pair is NOT this initializer's any more (KIM-492 WP-E15): it ships in
        // the staged contract jar under this very same protostream package, so declaring it here
        // too would make protostream reject the duplicate and the consumer would never start.
        // CalendarAuthInitiateWireTest is what proves the jar's copy registers and round trips.
        assertFalse(EsbProtoRegistrar.class.getResource("/proto/calendar-reconnect.proto") == null,
                "the extension's own schema must still ship");
    }

    @Test
    void connectionStatusProto_roundTripsThroughProtoWire() throws Exception {
        EsbProtoRegistrar.ensureRegistered();

        CalendarConnectionStatusChangedProto event = new CalendarConnectionStatusChangedProto();
        event.setWorkspaceId("ws_1fe38f34");
        event.setEmail("shuakki.vr@gmail.com");
        event.setCalendarSystem("GOOGLE");
        event.setStatus("CONNECTED");
        event.setOccurredAtEpochMillis(1780991786446L);
        event.setCorrelationId("70a796ac-6629-4d8f-b5c7-b04d567ebb04");

        byte[] bytes = ProtoWire.encode(event);
        CalendarConnectionStatusChangedProto back =
                ProtoWire.decode(bytes, CalendarConnectionStatusChangedProto.class);

        assertEquals("ws_1fe38f34", back.getWorkspaceId());
        assertEquals("shuakki.vr@gmail.com", back.getEmail());
        assertEquals("GOOGLE", back.getCalendarSystem());
        assertEquals("CONNECTED", back.getStatus());
        assertEquals(1780991786446L, back.getOccurredAtEpochMillis());
        assertEquals("70a796ac-6629-4d8f-b5c7-b04d567ebb04", back.getCorrelationId());
    }
}
