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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync;

import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.EsbCaGateway;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatus;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarWatchStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS;
import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.ENROLLED_USERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How one sweep's rows are built. The sweep-level outcomes are exercised through the real watch
 * service in {@code CalendarWatchServiceDiscoveryTest}; this covers what only shows up per row.
 */
@DisplayName("Scanned mailbox rows")
class ScannedMailboxReporterTest {

    private static final Instant SWEPT_AT = Instant.parse("2026-11-09T10:00:00Z");

    private final Invoker invoker = mock(Invoker.class);
    private final CalendarWatchStore watchStore = mock(CalendarWatchStore.class);
    private final UserKeyIndexStore index = mock(UserKeyIndexStore.class);
    private final EsbCaGateway gateway = mock(EsbCaGateway.class);
    private final CalendarConnectionStatusStore statuses = mock(CalendarConnectionStatusStore.class);

    private ScannedMailboxReporter reporter;

    @BeforeEach
    void setUp() {
        when(invoker.getInvokerId()).thenReturn("INV_1");
        reporter = new ScannedMailboxReporter(invoker, watchStore, index, gateway, statuses);
    }

    private ScannedMailboxSnapshot.Mailbox onlyRowFor(String userId) {
        CalendarDiscoveryService.UserSet set =
                new CalendarDiscoveryService.UserSet(ENROLLED_USERS, List.of(userId), Set.of(userId), null);
        return reporter.snapshot(set, new ScannedMailboxReporter.SweepOutcomes(), SWEPT_AT).mailboxes().get(0);
    }

    @Test
    @DisplayName("A client key reports the address recorded for it")
    void clientKeyUsesIndexedAddress() {
        when(index.getEmailByKey("INV_1", "wsContact_abc")).thenReturn("client@acme.example");

        assertEquals("client@acme.example", onlyRowFor("wsContact_abc").email());
    }

    @Test
    @DisplayName("A client key with no recorded address is still reported, under the key")
    void unaddressableKeyIsStillReported() {
        assertEquals("wsContact_abc", onlyRowFor("wsContact_abc").email());
    }

    @Test
    @DisplayName("The workspace-auth key form reports the address inside it")
    void workspaceAuthKeyReportsAddress() {
        assertEquals("ana@acme.example", onlyRowFor("workspace_auth:ws_x:Ana@Acme.Example").email());
    }

    @Test
    @DisplayName("An expired watch is not an active one")
    void expiredWatchIsPending() {
        when(watchStore.getExpiration("user@acme.example")).thenReturn(SWEPT_AT.toEpochMilli() - 1);

        ScannedMailboxSnapshot.Mailbox row = onlyRowFor("user@acme.example");

        assertEquals(ScannedMailboxReporter.PENDING, row.subscriptionState());
        assertNull(row.subscriptionId());
    }

    @Test
    @DisplayName("A watch store that fails reads as PENDING rather than losing the row")
    void storeFailureIsPending() {
        when(watchStore.getExpiration("user@acme.example")).thenThrow(new IllegalStateException("kv down"));

        assertEquals(ScannedMailboxReporter.PENDING, onlyRowFor("user@acme.example").subscriptionState());
    }

    @Test
    @DisplayName("Building the report failing costs only the report")
    void buildFailureNeverEscapes() {
        when(index.getEmailByKey(any(), any())).thenThrow(new IllegalStateException("kv down"));
        CalendarDiscoveryService.UserSet set = new CalendarDiscoveryService.UserSet(
                ENROLLED_USERS, List.of("wsContact_abc"), Set.of(), null);

        reporter.report(set, new ScannedMailboxReporter.SweepOutcomes(), "test");

        verify(gateway, never()).reportScannedMailboxes(any());
    }

    // ── last known state, for a mailbox this sweep did not touch ──────────

    @Test
    @DisplayName("Untouched: last delegated read succeeded and a watch is held — SUBSCRIBED")
    void untouchedConnectedWithWatchIsSubscribed() {
        when(statuses.get("user@acme.example", "INV_1")).thenReturn(CalendarConnectionStatus.CONNECTED);
        when(watchStore.getExpiration("user@acme.example")).thenReturn(SWEPT_AT.toEpochMilli() + 60_000);
        when(watchStore.getChannelId("user@acme.example")).thenReturn("channel-1");

        ScannedMailboxSnapshot.Mailbox row = onlyRowFor("user@acme.example");

        assertEquals(ScannedMailboxReporter.SUBSCRIBED, row.subscriptionState());
        assertEquals("channel-1", row.subscriptionId());
    }

    @Test
    @DisplayName("Untouched: last delegated attempt failed — FAILED, not flipped to PENDING")
    void untouchedDisconnectedIsFailed() {
        when(statuses.get("user@acme.example", "INV_1")).thenReturn(CalendarConnectionStatus.DISCONNECTED);

        assertEquals(ScannedMailboxReporter.FAILED, onlyRowFor("user@acme.example").subscriptionState());
    }

    @Test
    @DisplayName("Untouched: connected but holding no live watch — PENDING")
    void untouchedConnectedWithoutWatchIsPending() {
        when(statuses.get("user@acme.example", "INV_1")).thenReturn(CalendarConnectionStatus.CONNECTED);
        when(watchStore.getExpiration("user@acme.example")).thenReturn(0L);

        assertEquals(ScannedMailboxReporter.PENDING, onlyRowFor("user@acme.example").subscriptionState());
    }

    // ── when a snapshot is sent ───────────────────────────────────────────

    @Test
    @DisplayName("Opt-In sends no snapshot: Meeting Agent keeps no Always On inventory for it")
    void optInSendsNothing() {
        CalendarDiscoveryService.UserSet set = new CalendarDiscoveryService.UserSet(
                CONSENTED_USERS, List.of("consented@acme.example"), null);

        reporter.report(set, new ScannedMailboxReporter.SweepOutcomes(), "test");

        verify(gateway, never()).reportScannedMailboxes(any());
    }

    @Test
    @DisplayName("A degraded sweep sends an EMPTY snapshot carrying its reason")
    void degradedSweepSendsEmptySnapshotWithReason() {
        when(gateway.reportScannedMailboxes(any())).thenReturn(true);
        CalendarDiscoveryService.UserSet set = new CalendarDiscoveryService.UserSet(
                ENROLLED_USERS, List.of(), Set.of(), CalendarDiscoveryService.REGISTERED_SEATS_UNAVAILABLE);

        reporter.report(set, new ScannedMailboxReporter.SweepOutcomes(), "test");

        org.mockito.ArgumentCaptor<ScannedMailboxSnapshot> sent =
                org.mockito.ArgumentCaptor.forClass(ScannedMailboxSnapshot.class);
        verify(gateway).reportScannedMailboxes(sent.capture());
        assertTrue(sent.getValue().isEmpty());
        assertEquals("registered-seats-unavailable", sent.getValue().degradedReason());
        assertEquals("ENROLLED_USERS", sent.getValue().mode());
    }

    @Test
    @DisplayName("A healthy sweep with nothing in scope sends nothing")
    void healthyEmptySweepSendsNothing() {
        CalendarDiscoveryService.UserSet set = new CalendarDiscoveryService.UserSet(
                ENROLLED_USERS, List.of(), Set.of(), null);

        reporter.report(set, new ScannedMailboxReporter.SweepOutcomes(), "test");

        verify(gateway, never()).reportScannedMailboxes(any());
    }
}
