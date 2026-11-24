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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * KIM-492 — the in-call messages this extension is broadcast but does not present.
 *
 * <p>What is worth testing here is not the JSON; it is the one distinction the store exists to keep,
 * because getting it wrong is invisible from the outside:
 *
 * <ul>
 *   <li><strong>Blank CLEARS, and a cleared message is not the same as an absent store.</strong>
 *       Meeting Agent dispatches a withdrawn compliance notice as an EMPTY field rather than omitting
 *       it, precisely so it stops being presented at once. A store that read blank as "leave the
 *       previous value alone" would keep presenting legal copy the customer had retracted — the one
 *       direction in this feature whose staleness has consequences outside the product. But "received
 *       and cleared" still has to be distinguishable from "never received": the first means the tenant
 *       has nothing to say, the second means we have not been told yet, and a future Google-side
 *       presenter needs to tell them apart.</li>
 *   <li><strong>The snapshot outlives the process.</strong> Meeting Agent re-broadcasts at ITS boot,
 *       not at this extension's, so a container restart between two Meeting Agent boots would
 *       otherwise leave this extension with no messages at all until the next admin save.</li>
 *   <li><strong>Nothing throws.</strong> The only caller is a consumer on a durable manual-ack queue,
 *       where an escaping failure redelivers the same broadcast against the same fault forever.</li>
 * </ul>
 */
@DisplayName("Workspace in-call messages store (KIM-492)")
class WorkspaceMessagesStoreTest {

    private static final String WORKSPACE = "ws-acme";
    private static final String INVOKER = "inv-gcal-1";
    private static final String STORE_KEY = "gcal_workspace_messages:" + INVOKER;

    private static final String WELCOME = "Krista is taking notes for this meeting.";
    private static final String COMPLIANCE = "Acme records meetings for quality assurance.";

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

    private static Invoker invoker() {
        Invoker invoker = mock(Invoker.class);
        lenient().when(invoker.getWorkspaceId()).thenReturn(WORKSPACE);
        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER);
        return invoker;
    }

    private static WorkspaceMessagesStore storeOver(KeyValueStore kv) {
        return new WorkspaceMessagesStore(invoker(), kv);
    }

    // ── never received ────────────────────────────────────────────────────

    @Test
    @DisplayName("a store that was never broadcast to holds NO snapshot, which is not 'nothing to say'")
    void neverBroadcastHoldsNoSnapshot() {
        WorkspaceMessagesStore store = storeOver(new FakeKeyValueStore());

        assertTrue(store.messages().isEmpty(),
                "Optional.empty() is the absence of a signal. A presenter that read it as 'this "
                        + "tenant presents nothing' would silently suppress a welcome message the "
                        + "workspace does have, for as long as Meeting Agent had not broadcast yet.");
        assertTrue(store.welcomeMessage().isEmpty());
        assertTrue(store.complianceNotice().isEmpty());
        assertEquals(0L, store.storedAt());
    }

    // ── storing ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("both messages are stored and read back")
    void storesBothMessages() {
        WorkspaceMessagesStore store = storeOver(new FakeKeyValueStore());

        store.store(WELCOME, COMPLIANCE);

        assertEquals(Optional.of(WELCOME), store.welcomeMessage());
        assertEquals(Optional.of(COMPLIANCE), store.complianceNotice());
        assertTrue(store.storedAt() > 0L);
        assertFalse(store.messages().orElseThrow().isEmpty());
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed — a textarea's trailing newline is not a message")
    void messagesAreTrimmed() {
        WorkspaceMessagesStore store = storeOver(new FakeKeyValueStore());

        store.store("  " + WELCOME + "\n", "\t" + COMPLIANCE + "  ");

        assertEquals(Optional.of(WELCOME), store.welcomeMessage());
        assertEquals(Optional.of(COMPLIANCE), store.complianceNotice());
    }

    @Test
    @DisplayName("a later broadcast REPLACES the previous one rather than merging with it")
    void aLaterBroadcastReplaces() {
        WorkspaceMessagesStore store = storeOver(new FakeKeyValueStore());

        store.store(WELCOME, COMPLIANCE);
        store.store("A new welcome", "");

        assertEquals(Optional.of("A new welcome"), store.welcomeMessage());
        assertTrue(store.complianceNotice().isEmpty(),
                "Meeting Agent sends the full current state on every broadcast, so a field it sends "
                        + "empty has been withdrawn. Merging would resurrect the previous notice.");
    }

    // ── blank clears ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Blank CLEARS — it never means 'no change'")
    class BlankClears {

        @ParameterizedTest(name = "complianceNotice = {0}")
        @NullSource
        @ValueSource(strings = {"", "   ", "\n"})
        @DisplayName("a blank compliance notice clears the one previously held")
        void blankComplianceNoticeClears(String cleared) {
            WorkspaceMessagesStore store = storeOver(new FakeKeyValueStore());
            store.store(WELCOME, COMPLIANCE);

            store.store(WELCOME, cleared);

            assertTrue(store.complianceNotice().isEmpty(),
                    "Meeting Agent dispatches a DELETE as an EMPTY field on purpose. Treating it as "
                            + "'leave the previous value alone' keeps presenting legal copy the "
                            + "customer has retracted.");
            assertEquals(Optional.of(WELCOME), store.welcomeMessage(),
                    "clearing one message must not disturb the other");
        }

        @Test
        @DisplayName("a broadcast that clears BOTH is still a snapshot, not an absent one")
        void clearingBothIsStillASnapshot() {
            WorkspaceMessagesStore store = storeOver(new FakeKeyValueStore());
            store.store(WELCOME, COMPLIANCE);

            store.store("", "");

            assertTrue(store.messages().isPresent(),
                    "'received and cleared' and 'never received' are different facts. Collapsing "
                            + "them would make a tenant that deliberately presents nothing "
                            + "indistinguishable from one Meeting Agent has not reached yet.");
            assertTrue(store.messages().orElseThrow().isEmpty());
            assertTrue(store.welcomeMessage().isEmpty());
            assertTrue(store.complianceNotice().isEmpty());
        }
    }

    // ── surviving a restart ───────────────────────────────────────────────

    @Nested
    @DisplayName("The snapshot outlives the process")
    class SurvivesRestart {

        @Test
        @DisplayName("a fresh process restores the previous one's snapshot from the key-value store")
        void restoresFromTheKeyValueStore() {
            FakeKeyValueStore kv = new FakeKeyValueStore();
            storeOver(kv).store(WELCOME, COMPLIANCE);

            WorkspaceMessagesStore restarted = storeOver(kv);

            assertEquals(Optional.of(WELCOME), restarted.welcomeMessage(),
                    "Meeting Agent re-broadcasts at ITS boot, not at this extension's. Without the "
                            + "persisted copy, a container restart leaves this extension with no "
                            + "messages until the next admin save — which may be never.");
            assertEquals(Optional.of(COMPLIANCE), restarted.complianceNotice());
            assertTrue(restarted.storedAt() > 0L, "the recorded time must survive too");
        }

        @Test
        @DisplayName("a CLEARED message restores as cleared, not as absent-and-therefore-unknown")
        void clearedStateSurvivesARestart() {
            FakeKeyValueStore kv = new FakeKeyValueStore();
            storeOver(kv).store(WELCOME, "");

            WorkspaceMessagesStore restarted = storeOver(kv);

            assertTrue(restarted.messages().isPresent(),
                    "the persisted shape has to carry the same distinction the record does");
            assertEquals(Optional.of(WELCOME), restarted.welcomeMessage());
            assertTrue(restarted.complianceNotice().isEmpty());
        }

        @Test
        @DisplayName("the snapshot is persisted under this invoker's own key")
        void persistsUnderTheInvokerKey() {
            FakeKeyValueStore kv = new FakeKeyValueStore();

            storeOver(kv).store(WELCOME, COMPLIANCE);

            assertTrue(kv.contains(STORE_KEY),
                    "a key shared across invokers would let one tenant's pod restore another's "
                            + "compliance notice. Persisted keys: " + kv.getKeys());
        }
    }

    // ── nothing throws ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Nothing throws — the caller is a durable manual-ack consumer")
    class NothingThrows {

        @Test
        @DisplayName("a key-value store that refuses writes still leaves the snapshot in force")
        void failedPersistIsSurvivable() {
            FakeKeyValueStore kv = new FakeKeyValueStore();
            kv.failWrites = true;
            WorkspaceMessagesStore store = storeOver(kv);

            assertDoesNotThrow(() -> store.store(WELCOME, COMPLIANCE));

            assertEquals(Optional.of(WELCOME), store.welcomeMessage(),
                    "the in-memory snapshot is already in force; losing the persisted copy costs "
                            + "only the head start after a restart");
        }

        @Test
        @DisplayName("a corrupt persisted entry reads as 'never received' rather than failing")
        void corruptEntryIsSurvivable() {
            FakeKeyValueStore kv = new FakeKeyValueStore();
            kv.put(STORE_KEY, "{not json");

            WorkspaceMessagesStore store = storeOver(kv);

            assertTrue(assertDoesNotThrow(() -> store.messages()).isEmpty(),
                    "this read is on the consumer path; the next broadcast replaces the entry");
        }

        @Test
        @DisplayName("an invoker that cannot report itself still stores, under a fallback key")
        void unreadableInvokerIsSurvivable() {
            Invoker broken = mock(Invoker.class);
            lenient().when(broken.getInvokerId())
                    .thenThrow(new IllegalStateException("no request context"));
            lenient().when(broken.getWorkspaceId())
                    .thenThrow(new IllegalStateException("no request context"));
            FakeKeyValueStore kv = new FakeKeyValueStore();
            WorkspaceMessagesStore store = new WorkspaceMessagesStore(broken, kv);

            assertDoesNotThrow(() -> store.store(WELCOME, COMPLIANCE));

            assertEquals(Optional.of(WELCOME), store.welcomeMessage());
        }
    }
}
