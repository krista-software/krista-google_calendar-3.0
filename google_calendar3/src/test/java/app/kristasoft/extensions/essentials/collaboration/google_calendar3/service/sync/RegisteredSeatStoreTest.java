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
import app.krista.extensions.util.KeyValueStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.EsbCaGateway;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * WP-G1 — the roster of registered Meeting Agent users that Always On scans.
 *
 * <p>What is worth testing here is not the JSON parsing; it is the three decisions the store makes
 * that change what a customer's sweep reads:
 *
 * <ul>
 *   <li><strong>What a failure costs.</strong> A refresh that fails keeps the previous roster.
 *       Losing it would demote a working Always On tenant to its consented users for the length of
 *       an unrelated outage — capture narrowing for a reason nobody would connect to it.</li>
 *   <li><strong>"Never fetched" versus "fetched, and empty".</strong> One is a missing signal, on
 *       which discovery degrades; the other is an answer, on which a workspace with no licensed
 *       seats correctly scans nobody. Collapsing them would either scan calendars nobody enrolled,
 *       or stop capture the first time Meeting Agent was slow to come up.</li>
 *   <li><strong>Freshness and survival.</strong> A six-hourly refresh, and a roster that outlives
 *       the process so a restart mid-outage does not start from nothing.</li>
 * </ul>
 *
 * <p><b>Who counts as a seat is NOT tested here any more.</b> The endpoint, the paging and the
 * two-condition {@code status == ACTIVE && licensedSeat} filter live behind {@code CaGateway}, and
 * they are covered against the real wire shapes by {@code EsbCaGatewayMappingTest} ("C1 listUsers").
 * This class previously drove that filter through {@code HttpCaGateway}, which was deleted with the
 * uncommissioned {@code caTransport} attribute on 2026-09-11; re-asserting the filter against a
 * mock here would only test the mock.
 */
@DisplayName("Registered Meeting Agent seats (WP-G1)")
class RegisteredSeatStoreTest {

    private static final String WORKSPACE = "ws-acme";
    private static final String SERVICE_URL = "https://ma.example";

    /** An in-memory {@link KeyValueStore}, so persistence and restore are exercised for real. */
    private static final class FakeKeyValueStore implements KeyValueStore {

        private final Map<String, Object> entries = new LinkedHashMap<>();
        private boolean failWrites;

        @Override
        public Object get(String key) {
            return entries.get(key);
        }

        @Override
        public void remove(String key) {
            entries.remove(key);
        }

        @Override
        public boolean contains(String key) {
            return entries.containsKey(key);
        }

        @Override
        public void put(String key, Object value) {
            if (failWrites) {
                throw new IllegalStateException("key-value store unavailable");
            }
            entries.put(key, value);
        }

        @Override
        public boolean supports(Class<?> type) {
            return true;
        }

        @Override
        public Set<String> getKeys() {
            return entries.keySet();
        }

        @Override
        public Iterator<Map.Entry<String, Object>> iterator() {
            return entries.entrySet().iterator();
        }
    }

    /**
     * A {@link RegisteredSeatStore} over a gateway that answers with canned rosters, one per call,
     * and counts how many times it was asked.
     *
     * <p>The call count is what replaces the old list of requested URLs. "No call was made" is a
     * real assertion for this class — a fresh roster must not be re-fetched, and a restore from the
     * key-value store must not reach Meeting Agent — and it is now the only thing about the call
     * this store can meaningfully observe.
     */
    private static final class StubbedStore {

        private final AtomicInteger calls = new AtomicInteger();
        private final List<Optional<Set<String>>> answers = new java.util.ArrayList<>();

        private final RegisteredSeatStore store;

        StubbedStore(Invoker invoker, KeyValueStore keyValueStore) {
            EsbCaGateway gateway = mock(EsbCaGateway.class);
            lenient().when(gateway.listLicensedSeatEmails()).thenAnswer(invocation -> {
                int index = calls.getAndIncrement();
                return index < answers.size() ? answers.get(index) : Optional.of(Set.<String>of());
            });
            this.store = new RegisteredSeatStore(invoker, keyValueStore, gateway);
        }

        /** Queues one successful answer per refresh, in order. */
        StubbedStore answering(Set<String>... rosters) {
            for (Set<String> roster : rosters) {
                answers.add(Optional.of(new LinkedHashSet<>(roster)));
            }
            return this;
        }

        /** Queues a failed read — the gateway's "any page failed" answer. */
        StubbedStore failing() {
            answers.add(Optional.empty());
            return this;
        }

        boolean refresh() {
            return store.refresh();
        }

        boolean refreshIfStale() {
            return store.refreshIfStale();
        }

        Optional<Set<String>> seats() {
            return store.seats();
        }

        boolean isRegisteredSeat(String email) {
            return store.isRegisteredSeat(email);
        }

        int calls() {
            return calls.get();
        }
    }

    private static Invoker invoker(String workspaceId, String serviceUrl) {
        Invoker invoker = mock(Invoker.class);
        lenient().when(invoker.getInvokerId()).thenReturn("INV_gcal");
        lenient().when(invoker.getWorkspaceId()).thenReturn(workspaceId);
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (serviceUrl != null) {
            attributes.put(Constants.SERVICE_URL, serviceUrl);
        }
        attributes.put(Constants.SERVICE_API_KEY, "ma-api-key-test");
        lenient().when(invoker.getAttributes()).thenReturn(attributes);
        return invoker;
    }

    private static StubbedStore store(KeyValueStore kv) {
        return new StubbedStore(invoker(WORKSPACE, SERVICE_URL), kv);
    }

    // ── what the store holds ──────────────────────────────────────────────

    @Nested
    @DisplayName("Holding the roster")
    class Holding {

        @Test
        @DisplayName("The roster the gateway answered is what the store holds")
        void holdsWhatTheGatewayAnswered() {
            StubbedStore store = store(new FakeKeyValueStore())
                    .answering(Set.of("alice@acme.example", "bob@acme.example"));

            assertTrue(store.refresh());

            assertEquals(Set.of("alice@acme.example", "bob@acme.example"), store.seats().orElseThrow());
        }

        /**
         * The seat lookup is on the push-notification and single-user-sync paths, where the address
         * arrives in whatever case the calendar system used. Matching case-sensitively there would
         * silently stop capturing a seat whose Google profile capitalises their name.
         */
        @Test
        @DisplayName("Seat lookup is case-insensitive and rejects blanks")
        void seatLookupIsCaseInsensitive() {
            StubbedStore store = store(new FakeKeyValueStore()).answering(Set.of("alice@acme.example"));

            store.refresh();

            assertTrue(store.isRegisteredSeat("ALICE@Acme.Example"));
            assertFalse(store.isRegisteredSeat("bob@acme.example"));
            assertFalse(store.isRegisteredSeat(null));
            assertFalse(store.isRegisteredSeat("  "));
        }

        /**
         * One refresh, one call. The store must not page, retry or re-ask on its own: the gateway
         * already walks every page and treats a partial roster as a failure, so a second ask here
         * could only ever overwrite a complete answer with a different one.
         */
        @Test
        @DisplayName("One refresh asks Meeting Agent exactly once")
        void refreshAsksOnce() {
            StubbedStore store = store(new FakeKeyValueStore()).answering(Set.of("alice@acme.example"));

            store.refresh();

            assertEquals(1, store.calls());
        }
    }

    // ── failure ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("When Meeting Agent cannot be read")
    class Failure {

        /**
         * The behaviour the whole store exists for. An MA outage must cost freshness, never scope:
         * demoting an Always On tenant to its consented users mid-outage would stop capturing most
         * of the workspace, and would look like a discovery bug rather than an outage.
         */
        @Test
        @DisplayName("A failed refresh keeps the last good roster")
        void failedRefreshKeepsLastGoodSet() {
            FakeKeyValueStore kv = new FakeKeyValueStore();
            store(kv).answering(Set.of("alice@acme.example")).refresh();

            StubbedStore failing = store(kv).failing();
            assertFalse(failing.refresh(), "a failed refresh reports failure");

            assertEquals(Set.of("alice@acme.example"), failing.seats().orElseThrow(),
                    "the roster persisted by the healthy fetch is still in force");
        }

        /**
         * The distinction discovery keys its degradation on. Nothing has ever been read, so there is
         * no answer to give — and inventing "no seats" would silently stop capture altogether.
         */
        @Test
        @DisplayName("A roster that was never fetched is absent, not empty")
        void neverFetchedIsAbsent() {
            StubbedStore store = store(new FakeKeyValueStore()).failing();

            assertFalse(store.refresh());

            assertEquals(Optional.empty(), store.seats());
            assertFalse(store.isRegisteredSeat("alice@acme.example"));
        }

        /**
         * The other half of that distinction. A workspace with no licensed seats has answered; it
         * has simply enrolled nobody, and Always On correctly scans nobody rather than falling back
         * to calendars the customer never asked to capture.
         */
        @Test
        @DisplayName("A fetched empty roster is an answer, and is present")
        void fetchedEmptyIsPresent() {
            StubbedStore store = store(new FakeKeyValueStore()).answering(Set.of());

            assertTrue(store.refresh());

            assertTrue(store.seats().isPresent());
            assertTrue(store.seats().orElseThrow().isEmpty());
        }

        @Test
        @DisplayName("A key-value store that cannot be written still leaves the roster in force")
        void unwritableStoreDoesNotLoseTheRoster() {
            FakeKeyValueStore kv = new FakeKeyValueStore();
            kv.failWrites = true;
            StubbedStore store = store(kv).answering(Set.of("alice@acme.example"));

            assertTrue(store.refresh(), "losing the persisted copy costs a restart's head start, "
                    + "not this process's roster");
            assertEquals(Set.of("alice@acme.example"), store.seats().orElseThrow());
        }
    }

    // ── freshness ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Freshness")
    class Freshness {

        private void seed(FakeKeyValueStore kv, long fetchedAt, String... emails) {
            StringBuilder json = new StringBuilder("{\"emails\":[");
            for (int i = 0; i < emails.length; i++) {
                json.append(i == 0 ? "" : ",").append('"').append(emails[i]).append('"');
            }
            json.append("],\"fetchedAt\":").append(fetchedAt).append('}');
            kv.put("gcal_registered_seats:INV_gcal", json.toString());
        }

        @Test
        @DisplayName("A roster read inside the refresh interval is left alone")
        void freshRosterIsNotRefetched() {
            FakeKeyValueStore kv = new FakeKeyValueStore();
            seed(kv, System.currentTimeMillis(), "alice@acme.example");
            StubbedStore store = store(kv);

            assertFalse(store.refreshIfStale());

            assertEquals(0, store.calls());
            assertEquals(Set.of("alice@acme.example"), store.seats().orElseThrow());
        }

        @Test
        @DisplayName("A roster older than the refresh interval is re-read")
        void staleRosterIsRefetched() {
            FakeKeyValueStore kv = new FakeKeyValueStore();
            seed(kv, System.currentTimeMillis() - RegisteredSeatStore.REFRESH_INTERVAL_MILLIS - 1_000,
                    "alice@acme.example");
            StubbedStore store = store(kv).answering(Set.of("bob@acme.example"));

            assertTrue(store.refreshIfStale());

            assertEquals(Set.of("bob@acme.example"), store.seats().orElseThrow());
        }

        @Test
        @DisplayName("The interval is six hours, as agreed for both extensions")
        void intervalIsSixHours() {
            assertEquals(6L * 60 * 60 * 1000, RegisteredSeatStore.REFRESH_INTERVAL_MILLIS);
        }

        /**
         * A restart during a Meeting Agent outage is exactly when the in-memory set is empty and the
         * fetch is failing — the moment a demotion to the consented users would be least welcome and
         * hardest to explain.
         */
        @Test
        @DisplayName("A cold process restores the roster a previous one persisted")
        void coldProcessRestoresPersistedRoster() {
            FakeKeyValueStore kv = new FakeKeyValueStore();
            store(kv).answering(Set.of("alice@acme.example")).refresh();

            StubbedStore afterRestart = store(kv);

            assertEquals(Set.of("alice@acme.example"), afterRestart.seats().orElseThrow());
            assertTrue(afterRestart.isRegisteredSeat("alice@acme.example"));
            assertEquals(0, afterRestart.calls(), "restoring must not issue a call");
        }

        @Test
        @DisplayName("A corrupt persisted roster is discarded, not fatal")
        void corruptPersistedRosterIsDiscarded() {
            FakeKeyValueStore kv = new FakeKeyValueStore();
            kv.put("gcal_registered_seats:INV_gcal", "not json");
            StubbedStore store = store(kv);

            assertEquals(Optional.empty(), store.seats());
            assertTrue(store.refreshIfStale());
        }
    }

    // ── logging helper ────────────────────────────────────────────────────

    @Test
    @DisplayName("A skipped population is named up to a bound, then summarised")
    void sampleIsBounded() {
        Set<String> many = new LinkedHashSet<>();
        for (int i = 0; i < 14; i++) {
            many.add("user" + i + "@acme.example");
        }

        String rendered = RegisteredSeatStore.sample(many);

        assertTrue(rendered.startsWith("[user0@acme.example, "), rendered);
        assertTrue(rendered.endsWith("+4 more]"), rendered);
        assertEquals("[]", RegisteredSeatStore.sample(Set.of()));
    }
}
