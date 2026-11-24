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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CalendarConnectionStatusEventPublisher} transition-gating logic.
 *
 * <p>These tests exercise only the guard conditions (no-op skip, missing fields, real
 * transition) without connecting to an actual ESB broker — the ESB call itself is
 * guarded by the fact that the test will fail with a connection exception if it
 * reaches {@code publishProtoEventSync}. Tests that must NOT reach the broker are
 * verified to return {@code false} before any publish attempt.
 */
class CalendarConnectionStatusEventPublisherTest {

    /**
     * Subclass that overrides the publish to avoid requiring an ESB connection in tests.
     */
    private static class TestablePublisher extends CalendarConnectionStatusEventPublisher {
        int publishCount = 0;

        TestablePublisher() {
        }

        CalendarConnectionStatusChangedProto lastEvent;

        @Override
        protected void publishProtoEventSync(String queueName, String workspaceId,
                                              app.krista.infra.esb.client.Input event, String apiName) {
            publishCount++;
            lastEvent = (CalendarConnectionStatusChangedProto) event;
            // no-op: skip the real ESB call in unit tests
        }
    }

    private TestablePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new TestablePublisher();
    }

    @Test
    void publishOnTransition_skipsWhenStatusUnchanged() {
        boolean result = publisher.publishOnTransition(
                "ws-1", "user@example.com", "CONNECTED", "CONNECTED", null, null, 1L);

        assertFalse(result, "Expected false when prior equals next");
        assertEquals(0, publisher.publishCount, "Should not publish when status is unchanged");
    }

    @Test
    void publishOnTransition_skipsWhenWorkspaceIdMissing() {
        boolean result = publisher.publishOnTransition(
                null, "user@example.com", "CONNECTED", "REAUTH_REQUIRED", null, null, 1L);

        assertFalse(result);
        assertEquals(0, publisher.publishCount);
    }

    @Test
    void publishOnTransition_skipsWhenEmailMissing() {
        boolean result = publisher.publishOnTransition(
                "ws-1", "", "CONNECTED", "REAUTH_REQUIRED", null, null, 1L);

        assertFalse(result);
        assertEquals(0, publisher.publishCount);
    }

    @Test
    void publishOnTransition_skipsWhenNextStatusMissing() {
        boolean result = publisher.publishOnTransition(
                "ws-1", "user@example.com", "CONNECTED", null, null, null, 1L);

        assertFalse(result);
        assertEquals(0, publisher.publishCount);
    }

    @Test
    void publishOnTransition_publishesOnRealTransition() {
        boolean result = publisher.publishOnTransition(
                "ws-1", "user@example.com", "CONNECTED", "REAUTH_REQUIRED",
                "TOKEN_EXPIRED", "Refresh token expired", 1L);

        assertTrue(result, "Expected true when status actually changes");
        assertEquals(1, publisher.publishCount, "Should publish exactly once on transition");
        assertNull(publisher.lastEvent.getReason(), "7-arg overload must leave reason null (involuntary default)");
    }

    @Test
    void publishOnTransition_carriesReason_field9() {
        boolean result = publisher.publishOnTransition(
                "ws-1", "user@example.com", "CONNECTED", "DISCONNECTED",
                null, null, 1L, "USER_INITIATED");

        assertTrue(result);
        assertEquals(1, publisher.publishCount);
        assertEquals("DISCONNECTED", publisher.lastEvent.getStatus());
        assertEquals("USER_INITIATED", publisher.lastEvent.getReason());
    }

    @Test
    void publishOnTransition_publishesWhenPriorIsNull() {
        boolean result = publisher.publishOnTransition(
                "ws-1", "user@example.com", null, "CONNECTED", null, null, 1L);

        assertTrue(result, "Expected true for first-time status (null prior → CONNECTED)");
        assertEquals(1, publisher.publishCount);
    }

    @Test
    void publish_alwaysSendsEvenWhenStatusUnchanged() {
        // publish() is NOT transition-gated (used for auth-success re-registration):
        // it must send even when prior == next, so re-authenticating an already-CONNECTED
        // user still re-registers the calendar system with CA.
        boolean result = publisher.publish(
                "ws-1", "user@example.com", "CONNECTED", "CONNECTED", null, null, 1L);

        assertTrue(result, "Expected true — publish() ignores transition gating");
        assertEquals(1, publisher.publishCount, "Should publish even when prior equals next");
    }

    @Test
    void publish_skipsWhenRequiredFieldMissing() {
        assertFalse(publisher.publish(null, "user@example.com", null, "CONNECTED", null, null, 1L));
        assertFalse(publisher.publish("ws-1", "", null, "CONNECTED", null, null, 1L));
        assertFalse(publisher.publish("ws-1", "user@example.com", null, null, null, null, 1L));
        assertEquals(0, publisher.publishCount, "Validation failures must not reach the broker");
    }
}
