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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook;

import app.krista.extension.executor.Invoker;
import app.krista.extension.request.RoutingInfo;
import app.krista.extensions.util.KeyValueStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarAuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS;
import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.ENROLLED_USERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KIM-474 / EF-07: a user that leaves the scan set must have their watch channel revoked, not merely
 * abandoned.
 *
 * <p>Every other sweep in {@link CalendarWatchService} iterates the users currently IN scope, so one
 * that LEAVES was never visited again: nothing renewed their channel and nothing stopped it either.
 * It ran until Google expired it ~5 days later, notifying us the whole time about a calendar we had
 * already decided not to scan — and the local store kept a key nothing would revisit.
 *
 * <p>The two guards are the point of the test as much as the revoke is. This method's failure mode is
 * asymmetric: missing a departure costs one stale channel for five days, while revoking on a bad
 * input tears down the whole workspace's push pipeline and is recoverable only by a full
 * re-subscribe.
 */
@DisplayName("CalendarWatchService - out-of-scope revoke (EF-07)")
class OutOfScopeRevokeTest {

    /**
     * Flat in-memory {@link KeyValueStore}, deliberately holding keys from OTHER stores too: the real
     * namespace is shared, and the reconcile must not mistake a sync token for a mailbox.
     */
    private static final class InMemoryKeyValueStore implements KeyValueStore {

        private final Map<String, Object> values = new HashMap<>();
        private boolean keysUnreadable;

        @Override
        public Object get(String key) {
            return values.get(key);
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public void put(String key, Object value) {
            values.put(key, value);
        }

        @Override
        public boolean supports(Class<?> type) {
            return true;
        }

        @Override
        public Set<String> getKeys() {
            if (keysUnreadable) {
                throw new IllegalStateException("key-value store unavailable");
            }
            return new LinkedHashSet<>(values.keySet());
        }

        @Override
        public Iterator<Map.Entry<String, Object>> iterator() {
            return values.entrySet().iterator();
        }
    }

    private InMemoryKeyValueStore keyValueStore;
    private CalendarWatchStore watchStore;
    private CalendarDiscoveryService discoveryService;
    private GoogleProviderFactory googleProviderFactory;
    private GoogleProvider googleProvider;
    private CalendarWatchService watchService;

    @BeforeEach
    void setUp() {
        keyValueStore = new InMemoryKeyValueStore();
        watchStore = new CalendarWatchStore(keyValueStore);
        discoveryService = mock(CalendarDiscoveryService.class);
        googleProviderFactory = mock(GoogleProviderFactory.class);
        googleProvider = mock(GoogleProvider.class);

        Invoker invoker = mock(Invoker.class);
        RoutingInfo routingInfo = mock(RoutingInfo.class);
        lenient().when(routingInfo.getRoutingURL(anyString(), any())).thenReturn("https://ext.example");
        lenient().when(invoker.getRoutingInfo()).thenReturn(routingInfo);
        lenient().when(invoker.getInvokerId()).thenReturn("INV_google_calendar");
        lenient().when(googleProviderFactory.create()).thenReturn(googleProvider);
        // Nobody in these tests can build a real client, so every Google stop fails. That is the
        // realistic shape of a departed-mailbox revoke — the credential that created the channel is
        // usually exactly what is gone — and it proves the LOCAL teardown is what the method relies
        // on, not a cooperative Google.
        lenient().when(googleProvider.getGoogleClientForUser(anyString()))
                .thenThrow(new IllegalStateException("no stored token"));
        lenient().when(discoveryService.sourceFor(anyString())).thenReturn(CONSENTED_USERS);

        watchService = new CalendarWatchService(googleProviderFactory,
                mock(GoogleCalendarAuthenticationService.class), watchStore, invoker,
                mock(UserKeyIndexStore.class), discoveryService);
    }

    private void hold(String userId) {
        watchStore.put(userId, "channel-" + userId, "resource-" + userId,
                System.currentTimeMillis() + (4L * 24 * 60 * 60 * 1000));
        watchStore.putChannelUser("channel-" + userId, userId);
    }

    private static CalendarDiscoveryService.UserSet enrolled(String... seats) {
        List<String> users = List.of(seats);
        return new CalendarDiscoveryService.UserSet(ENROLLED_USERS, users, Set.copyOf(users), null);
    }

    // ── the revoke itself ─────────────────────────────────────────────────

    @Test
    @DisplayName("a user that left the scan set is revoked and dropped from the store")
    void revokesDepartedMailbox() {
        hold("alice@acme.example");
        hold("bob@acme.example");
        hold("gone@acme.example");

        int revoked = watchService.revokeSubscriptionsOutOfScope(
                enrolled("alice@acme.example", "bob@acme.example"), "test");

        assertEquals(1, revoked);
        assertNull(watchStore.getChannelId("gone@acme.example"),
                "the departed user's watch row must go, or nothing ever revisits it");
        assertNull(watchStore.getUserByChannelId("channel-gone@acme.example"),
                "the reverse index entry must go with it");
        // The two still in scope must be left alone — this sweep revokes, it does not re-subscribe.
        assertNotNull(watchStore.getChannelId("alice@acme.example"));
        assertNotNull(watchStore.getChannelId("bob@acme.example"));
    }

    @Test
    @DisplayName("nothing is revoked when every held user is still in the scan set")
    void noopWhenAllInScope() {
        hold("alice@acme.example");
        hold("bob@acme.example");

        assertEquals(0, watchService.revokeSubscriptionsOutOfScope(
                enrolled("alice@acme.example", "bob@acme.example"), "test"));

        assertNotNull(watchStore.getChannelId("alice@acme.example"));
        assertNotNull(watchStore.getChannelId("bob@acme.example"));
    }

    /**
     * Seats reach this extension lower-cased from Meeting Agent, while a consented-era watch row
     * carries whatever case the user typed when they authorised. A case-sensitive comparison would
     * read the SAME mailbox as departed and tear down a live channel.
     */
    @Test
    @DisplayName("case differences between the roster and the store are not departures")
    void caseInsensitiveMatching() {
        hold("Alice@Acme.Example");

        assertEquals(0, watchService.revokeSubscriptionsOutOfScope(
                enrolled("alice@acme.example"), "test"));

        assertNotNull(watchStore.getChannelId("Alice@Acme.Example"));
    }

    /**
     * The key-value namespace is flat and shared with every other store in the extension. An
     * unfiltered enumerator (EF-26, the shape of {@code RefreshTokenStore.getKey()}) hands the
     * reconcile keys that are not mailboxes — and the reconcile revokes what it does not recognise.
     */
    @Test
    @DisplayName("foreign keys in the shared namespace are not mistaken for mailboxes")
    void ignoresKeysBelongingToOtherStores() {
        hold("alice@acme.example");
        keyValueStore.put("gcal_sync_token:alice@acme.example", "sync-1");
        keyValueStore.put("gcal_refresh:bob@acme.example", "token");
        keyValueStore.put("gcal_seats:INV_google_calendar", "{}");
        keyValueStore.put("gcal_user_key_index:INV_google_calendar", "{}");

        assertEquals(Set.of("alice@acme.example"), watchStore.subscribedUserIds());
        assertEquals(0, watchService.revokeSubscriptionsOutOfScope(
                enrolled("alice@acme.example"), "test"));
        assertNotNull(watchStore.getChannelId("alice@acme.example"));
    }

    // ── the two guards ────────────────────────────────────────────────────

    @Test
    @DisplayName("an EMPTY scan set revokes nothing — an unlicensed workspace must not be torn down")
    void emptyScanSetRevokesNothing() {
        // The most dangerous input this method can receive, and under the owner's SEAT-scope ruling
        // (2026-09-12) it is also a LEGITIMATE steady state: a workspace with no licensed Meeting
        // Agent seat scans nobody. It must stop RENEWING those channels, which happens by not
        // visiting them — it must never actively stop every channel it holds, because a sweep that
        // failed before resolving anybody produces the identical input.
        hold("alice@acme.example");
        hold("bob@acme.example");

        CalendarDiscoveryService.UserSet empty = enrolled();
        assertTrue(empty.isEmpty());
        assertFalse(empty.degraded(), "an empty seat roster is an answer, not a degradation");

        assertEquals(0, watchService.revokeSubscriptionsOutOfScope(empty, "test"));

        assertNotNull(watchStore.getChannelId("alice@acme.example"));
        assertNotNull(watchStore.getChannelId("bob@acme.example"));
        verify(googleProviderFactory, never()).create();
        verify(discoveryService, never()).sourceFor(anyString());
    }

    @Test
    @DisplayName("a DEGRADED scan set revokes nothing — one transient fault must not unsubscribe the "
            + "workspace")
    void degradedScanSetRevokesNothing() {
        // The input that turns a missing service-account key or a refused domain-wide delegation into
        // a workspace-wide push outage. Discovery's Always On scan failed, so it fell back to the
        // CONSENTED set and stamped a degradedReason. The fallback is NON-EMPTY, so the empty guard
        // does not catch it, and on an Always On workspace the held keys (registered seats) and the
        // fallback set (whoever authorised individually) are disjoint — WITHOUT the degraded guard
        // every single held channel reads as departed. 200 held keys stands in for the workspace.
        for (int i = 0; i < 200; i++) {
            hold("seat-" + i + "@acme.example");
        }
        CalendarDiscoveryService.UserSet degraded = new CalendarDiscoveryService.UserSet(
                CONSENTED_USERS, List.of("wsContact_abc", "personal@gmail.example"),
                "Always On is enabled but the 'serviceAccountKey' is not configured");

        assertFalse(degraded.isEmpty(), "a degraded set must be non-empty to exercise the guard");
        assertTrue(degraded.degraded(), "the set must report itself degraded");
        assertTrue(Collections.disjoint(watchStore.subscribedUserIds(), degraded.userIds()),
                "held keys must be disjoint from the fallback set, or the test passes for the wrong "
                        + "reason");

        assertEquals(0, watchService.revokeSubscriptionsOutOfScope(degraded, "test"));

        assertEquals(200, watchStore.subscribedUserIds().size());
        verify(discoveryService, never()).sourceFor(anyString());
    }

    @Test
    @DisplayName("an unreadable store skips the revoke rather than emptying itself")
    void storeFailureIsSurvivable() {
        hold("alice@acme.example");
        keyValueStore.keysUnreadable = true;

        assertEquals(0, watchService.revokeSubscriptionsOutOfScope(
                enrolled("bob@acme.example"), "test"));

        keyValueStore.keysUnreadable = false;
        assertNotNull(watchStore.getChannelId("alice@acme.example"),
                "a store failure means 'we do not know what we hold', never 'everyone has left'");
    }

    @Test
    @DisplayName("a null scan set revokes nothing")
    void nullScanSetRevokesNothing() {
        hold("alice@acme.example");

        assertEquals(0, watchService.revokeSubscriptionsOutOfScope(null, "test"));

        assertNotNull(watchStore.getChannelId("alice@acme.example"));
    }

    // ── wiring: the reconcile must actually run on a sweep ────────────────

    /**
     * The method existing is worth nothing if no sweep calls it — which is exactly the state EF-07
     * found GLE in. The renewal pass is the only sweep guaranteed to run on a schedule, so it is the
     * only place a departure can be noticed at all.
     */
    @Test
    @DisplayName("the renewal sweep reconciles held channels against the live scan set")
    void renewalSweepReconciles() {
        hold("alice@acme.example");
        hold("gone@acme.example");
        when(discoveryService.discover("watch_renewal")).thenReturn(enrolled("alice@acme.example"));

        watchService.renewExpiringChannels();

        assertNull(watchStore.getChannelId("gone@acme.example"),
                "without the reconcile on a scheduled sweep the departed channel lapses silently "
                        + "days later and nothing ever records that it happened");
        assertNotNull(watchStore.getChannelId("alice@acme.example"));
    }

    // ── EF-23: the seat reconcile ─────────────────────────────────────────

    /**
     * The fast path EF-14's consumer drives, and the cadence EF-23 asked to align with
     * teams-transcribe. Before this, the seat roster was refreshed only when a sweep happened to call
     * discovery — and the only scheduled sweep is the six-hourly renewal, so a newly-licensed Google
     * user could wait six hours to be watched at all.
     */
    @Test
    @DisplayName("EF-23: an immediate reconcile refreshes the roster and applies the new scan set")
    void reconcileRefreshesAndApplies() {
        when(discoveryService.isAlwaysOn()).thenReturn(true);
        hold("gone@acme.example");
        when(discoveryService.discover("watch_subscribe")).thenReturn(enrolled("alice@acme.example"));
        when(discoveryService.calendarClientFor(anyString(), any()))
                .thenThrow(new IllegalStateException("no delegated client in this test"));

        assertTrue(watchService.reconcileRegisteredSeatsNow());

        // A FORCED refresh, not the staleness-gated one: a caller reaching this method either knows
        // the roster changed or is the timer whose job is to notice that it did, and a TTL check
        // would answer "not yet" to both.
        verify(discoveryService).primeRegisteredSeats();
        verify(discoveryService).discover("watch_subscribe");
        // And the new set is APPLIED, which is what also retires a revoked seat's channel — Meeting
        // Agent emits no revocation signal, so this sweep is the only thing that can notice one.
        assertNull(watchStore.getChannelId("gone@acme.example"));
    }

    @Test
    @DisplayName("EF-23: an opt-in workspace does no work at all — the fast path cannot widen capture")
    void reconcileIsANoOpWhenAlwaysOnIsOff() {
        when(discoveryService.isAlwaysOn()).thenReturn(false);
        hold("consented@acme.example");

        assertFalse(watchService.reconcileRegisteredSeatsNow());

        verify(discoveryService, never()).primeRegisteredSeats();
        verify(discoveryService, never()).discover(anyString());
        assertNotNull(watchStore.getChannelId("consented@acme.example"),
                "an opt-in workspace's channels are untouched by a seat notification");
    }

    @Test
    @DisplayName("the subscribe sweep reconciles too, so a restart converges immediately")
    void subscribeSweepReconciles() {
        hold("gone@acme.example");
        when(discoveryService.discover("watch_subscribe")).thenReturn(enrolled("alice@acme.example"));
        when(discoveryService.calendarClientFor(anyString(), any()))
                .thenThrow(new IllegalStateException("no delegated client in this test"));

        watchService.subscribeAllUsers();

        assertNull(watchStore.getChannelId("gone@acme.example"));
    }
}
