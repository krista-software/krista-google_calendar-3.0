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

import org.infinispan.protostream.ProtobufUtil;
import org.infinispan.protostream.SerializationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wrapped-message round-trip tests for {@link CalendarConnectionStatusChangedProto}.
 *
 * <p>Registers the real {@code calendar-reconnect.proto} schema + hand-authored marshaller exactly
 * as {@link CalendarReconnectEsbProtoInitializerImpl} does in production, then serialises and
 * deserialises through protostream. Guards the hand-authored-marshaller hazard: if the marshaller's
 * read()/write() don't handle field 9, {@code reason} is silently dropped and {@link #reasonRoundTrips()}
 * fails.
 */
class CalendarConnectionStatusChangedProtoMarshallerTest {

    private SerializationContext ctx;

    @BeforeEach
    void setUp() {
        ctx = ProtobufUtil.newSerializationContext();
        CalendarReconnectEsbProtoInitializer init = new CalendarReconnectEsbProtoInitializerImpl();
        init.registerSchema(ctx);
        init.registerMarshallers(ctx);
    }

    @Test
    void reasonRoundTrips() throws Exception {
        CalendarConnectionStatusChangedProto src = new CalendarConnectionStatusChangedProto();
        src.setWorkspaceId("ws-1");
        src.setEmail("user@example.com");
        src.setCalendarSystem("GOOGLE");
        src.setStatus("DISCONNECTED");
        src.setLastErrorCode("code");
        src.setLastErrorMessage("msg");
        src.setOccurredAtEpochMillis(123L);
        src.setCorrelationId("corr");
        src.setReason("USER_INITIATED");

        byte[] bytes = ProtobufUtil.toWrappedByteArray(ctx, src);
        CalendarConnectionStatusChangedProto out = ProtobufUtil.fromWrappedByteArray(ctx, bytes);

        assertEquals("USER_INITIATED", out.getReason());
        assertEquals("DISCONNECTED", out.getStatus());
        assertEquals("ws-1", out.getWorkspaceId());
        assertEquals("user@example.com", out.getEmail());
        assertEquals("GOOGLE", out.getCalendarSystem());
        assertEquals("code", out.getLastErrorCode());
        assertEquals("msg", out.getLastErrorMessage());
        assertEquals(123L, out.getOccurredAtEpochMillis());
        assertEquals("corr", out.getCorrelationId());
    }

    @Test
    void absentReasonDecodesNull() throws Exception {
        CalendarConnectionStatusChangedProto src = new CalendarConnectionStatusChangedProto();
        src.setWorkspaceId("ws-1");
        src.setEmail("user@example.com");
        src.setStatus("CONNECTED");
        // reason left null — mirrors an older publisher that never sets field 9

        byte[] bytes = ProtobufUtil.toWrappedByteArray(ctx, src);
        CalendarConnectionStatusChangedProto out = ProtobufUtil.fromWrappedByteArray(ctx, bytes);

        assertNull(out.getReason());
        assertEquals("CONNECTED", out.getStatus());
        assertEquals("ws-1", out.getWorkspaceId());
    }
}
