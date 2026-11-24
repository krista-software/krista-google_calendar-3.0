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

import app.krista.extensions.util.KeyValueStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatus;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CalendarConnectionStatusStore} transition-gating and
 * publisher delegation.
 */
@ExtendWith(MockitoExtension.class)
class CalendarConnectionStatusStoreTest {

    @Mock
    private KeyValueStore keyValueStore;

    private CalendarConnectionStatusStore store;

    @BeforeEach
    void setUp() {
        store = new CalendarConnectionStatusStore(keyValueStore);
    }

    @Test
    void put_writesToKeyValueStore() {
        when(keyValueStore.get(anyString())).thenReturn(null);

        store.put("user@example.com", "ws-1", CalendarConnectionStatus.CONNECTED, "ws_real");

        verify(keyValueStore).put(eq("gcal_conn_status:user@example.com:ws-1"),
                eq("CONNECTED"));
    }

    @Test
    void put_withPublisher_callsPublishOnTransition() {
        when(keyValueStore.get("gcal_conn_status:user@example.com:ws-1")).thenReturn("REAUTH_REQUIRED");

        int[] callCount = {0};
        final String[] eventWorkspaceId = {null};
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                callCount[0]++;
                eventWorkspaceId[0] = ((CalendarConnectionStatusChangedProto) event).getWorkspaceId();
            }
        };

        store.setPublisher(mockPublisher);
        store.put("user@example.com", "ws-1", CalendarConnectionStatus.CONNECTED, "ws_real");

        assertEquals(1, callCount[0], "Publisher should be called on transition REAUTH_REQUIRED -> CONNECTED");
        assertEquals("ws_real", eventWorkspaceId[0], "put() must publish the workspace id, not the invoker id");
    }

    @Test
    void put_withReason_publishesUserInitiatedDisconnect() {
        when(keyValueStore.get("gcal_conn_status:user@example.com:ws-1")).thenReturn("CONNECTED");

        final String[] eventStatus = {null};
        final String[] eventReason = {null};
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                CalendarConnectionStatusChangedProto e = (CalendarConnectionStatusChangedProto) event;
                eventStatus[0] = e.getStatus();
                eventReason[0] = e.getReason();
            }
        };

        store.setPublisher(mockPublisher);
        store.put("user@example.com", "ws-1", CalendarConnectionStatus.DISCONNECTED, "ws_real", "USER_INITIATED");

        assertEquals("DISCONNECTED", eventStatus[0]);
        assertEquals("USER_INITIATED", eventReason[0], "field 9 reason must reach the published event");
    }

    @Test
    void put_withPublisher_skipsPublishWhenStatusUnchanged() {
        when(keyValueStore.get("gcal_conn_status:user@example.com:ws-1")).thenReturn("CONNECTED");

        int[] callCount = {0};
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                callCount[0]++;
            }
        };

        store.setPublisher(mockPublisher);
        store.put("user@example.com", "ws-1", CalendarConnectionStatus.CONNECTED, "ws_real");

        assertEquals(0, callCount[0], "Publisher should NOT be called when status is unchanged");
    }

    @Test
    void recordConnected_publishesEvenWhenAlreadyConnected() {
        // A user already CONNECTED in the store (e.g. authed before CA consumed the event).
        // recordConnected must re-assert CONNECTED to CA anyway — unlike transition-gated put().
        when(keyValueStore.get("gcal_conn_status:user@example.com:ws-1")).thenReturn("CONNECTED");

        int[] callCount = {0};
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                callCount[0]++;
            }
        };

        store.setPublisher(mockPublisher);
        store.recordConnected("user@example.com", "ws-1", "ws_real", null);

        verify(keyValueStore).put(eq("gcal_conn_status:user@example.com:ws-1"), eq("CONNECTED"));
        assertEquals(1, callCount[0], "recordConnected must publish even when status is already CONNECTED");
    }

    @Test
    void recordConnected_publishesRealWorkspaceId_notInvokerId() {
        // Regression: the event (and esb_tenantId) must carry the workspace id (ws_…), NOT the
        // invoker id (INV_…). CA keys the user/tenant on workspaceId; an invoker id makes it drop
        // the event, so the calendar system never registers. invokerId only scopes the KV key.
        when(keyValueStore.get(anyString())).thenReturn(null);

        final String[] tenantArg = {null};
        final String[] eventWorkspaceId = {null};
        final int[] callCount = {0};
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                callCount[0]++;
                tenantArg[0] = workspaceId;
                eventWorkspaceId[0] = ((CalendarConnectionStatusChangedProto) event).getWorkspaceId();
            }
        };

        store.setPublisher(mockPublisher);
        store.recordConnected("user@example.com", "INV_abc", "ws_real", null);

        assertEquals(1, callCount[0]);
        assertEquals("ws_real", eventWorkspaceId[0], "Event must carry the workspace id, not the invoker id");
        assertEquals("ws_real", tenantArg[0], "esb_tenantId (the publishProtoEventSync workspaceId arg) must be the workspace id");
        assertNotEquals("INV_abc", eventWorkspaceId[0]);
        // KV key stays scoped by the invoker id (unchanged behaviour).
        verify(keyValueStore).put(eq("gcal_conn_status:user@example.com:INV_abc"), eq("CONNECTED"));
    }

    @Test
    void recordConnected_withExplicitEmail_publishesThatEmailEvenWhenKeyHasNoEmail() {
        // Client (wsContact_<UUID>) keys carry no email, so resolveEmail() returns null and an
        // email-less recordConnected would skip the publish. With an explicit email it must publish
        // the supplied (normalized) email so CA registers the calendar system.
        String wsContactKey = "wsContact_1ba35a88-5270-4261-a4ea-7e4a1bd1b1f3";
        when(keyValueStore.get(anyString())).thenReturn(null);

        final String[] publishedEmail = {null};
        final int[] callCount = {0};
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                callCount[0]++;
                publishedEmail[0] = ((CalendarConnectionStatusChangedProto) event).getEmail();
            }
        };

        store.setPublisher(mockPublisher);
        store.recordConnected(wsContactKey, "ws-1", "ws_real", "Shuakki.VR@gmail.com");

        assertEquals(1, callCount[0], "Must publish using the explicit email even though the key has no email");
        assertEquals("shuakki.vr@gmail.com", publishedEmail[0], "Explicit email should be normalized and published");
    }

    @Test
    void recordConnected_withoutEmail_andUnresolvableKey_skipsPublish() {
        // Guard: with no explicit email, recordConnected still depends on resolveEmail, which cannot
        // derive an email from a wsContact_<UUID> key, so it skips (no regression).
        when(keyValueStore.get(anyString())).thenReturn(null);

        int[] callCount = {0};
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                callCount[0]++;
            }
        };

        store.setPublisher(mockPublisher);
        store.recordConnected("wsContact_1ba35a88-5270-4261-a4ea-7e4a1bd1b1f3", "ws-1", "ws_real", null);

        assertEquals(0, callCount[0], "No email resolvable from the key -> publish skipped");
    }

    @Test
    void forceStatus_publishesEvenWhenStatusUnchanged() {
        // Admin force is UNCONDITIONAL — unlike put(), it must re-publish even when the
        // stored status already equals the forced status (so testers can re-trigger).
        when(keyValueStore.get("gcal_conn_status:user@example.com:ws-1")).thenReturn("REAUTH_REQUIRED");

        int[] callCount = {0};
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                callCount[0]++;
            }
        };

        store.setPublisher(mockPublisher);
        boolean published = store.forceStatus("user@example.com", "ws-1",
                CalendarConnectionStatus.REAUTH_REQUIRED, "ws_real", null, "ADMIN_FORCE_SET", "forced");

        assertTrue(published, "forceStatus must report it published");
        assertEquals(1, callCount[0], "forceStatus must publish even when the status is unchanged");
    }

    @Test
    void forceStatus_persistsStatusToKvAndPublishesWorkspaceIdAndEmail() {
        when(keyValueStore.get(anyString())).thenReturn(null);

        final String[] eventStatus = {null};
        final String[] eventEmail = {null};
        final String[] eventWorkspaceId = {null};
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                CalendarConnectionStatusChangedProto proto = (CalendarConnectionStatusChangedProto) event;
                eventStatus[0] = proto.getStatus();
                eventEmail[0] = proto.getEmail();
                eventWorkspaceId[0] = proto.getWorkspaceId();
            }
        };

        store.setPublisher(mockPublisher);
        boolean published = store.forceStatus("user@example.com", "INV_abc",
                CalendarConnectionStatus.DISCONNECTED, "ws_real", null, "ADMIN_FORCE_SET", "forced");

        assertTrue(published);
        verify(keyValueStore).put(eq("gcal_conn_status:user@example.com:INV_abc"), eq("DISCONNECTED"));
        assertEquals("DISCONNECTED", eventStatus[0]);
        assertEquals("user@example.com", eventEmail[0]);
        assertEquals("ws_real", eventWorkspaceId[0], "force must publish the workspace id, not the invoker id");
    }

    @Test
    void forceStatus_wsContactKeyWithExplicitEmail_publishesNormalizedEmail() {
        // Client (wsContact_<UUID>) keys carry no email; the admin endpoint supplies it.
        String wsContactKey = "wsContact_0338ba14-7b9b-4da4-b6a9-cc3e6088a145";
        when(keyValueStore.get(anyString())).thenReturn(null);

        final String[] publishedEmail = {null};
        final int[] callCount = {0};
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                callCount[0]++;
                publishedEmail[0] = ((CalendarConnectionStatusChangedProto) event).getEmail();
            }
        };

        store.setPublisher(mockPublisher);
        boolean published = store.forceStatus(wsContactKey, "ws-1",
                CalendarConnectionStatus.CONNECTED, "ws_real", "Nakul.Mathur@kristasoft.info",
                "ADMIN_FORCE_SET", "forced");

        assertTrue(published);
        assertEquals(1, callCount[0]);
        assertEquals("nakul.mathur@kristasoft.info", publishedEmail[0],
                "Explicit email should be normalized and published");
    }

    @Test
    void forceStatus_wsContactKeyWithoutEmail_skipsPublishAndReturnsFalse() {
        when(keyValueStore.get(anyString())).thenReturn(null);

        int[] callCount = {0};
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                callCount[0]++;
            }
        };

        store.setPublisher(mockPublisher);
        boolean published = store.forceStatus("wsContact_0338ba14-7b9b-4da4-b6a9-cc3e6088a145", "ws-1",
                CalendarConnectionStatus.CONNECTED, "ws_real", null, "ADMIN_FORCE_SET", "forced");

        assertFalse(published, "No email resolvable from the key and none supplied -> publish skipped");
        assertEquals(0, callCount[0]);
        // The status is still persisted locally even when the publish is skipped.
        verify(keyValueStore).put(eq("gcal_conn_status:wsContact_0338ba14-7b9b-4da4-b6a9-cc3e6088a145:ws-1"),
                eq("CONNECTED"));
    }

    @Test
    void forceStatus_carriesErrorCodeAndMessage() {
        when(keyValueStore.get(anyString())).thenReturn(null);

        final String[] errorCode = {null};
        final String[] errorMessage = {null};
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                CalendarConnectionStatusChangedProto proto = (CalendarConnectionStatusChangedProto) event;
                errorCode[0] = proto.getLastErrorCode();
                errorMessage[0] = proto.getLastErrorMessage();
            }
        };

        store.setPublisher(mockPublisher);
        store.forceStatus("user@example.com", "ws-1", CalendarConnectionStatus.RENEWAL_FAILED,
                "ws_real", null, "ADMIN_FORCE_SET", "Forced from Google Calendar admin API");

        assertEquals("ADMIN_FORCE_SET", errorCode[0]);
        assertEquals("Forced from Google Calendar admin API", errorMessage[0]);
    }

    @Test
    void forceStatus_withoutPublisher_stillPersistsAndReturnsFalse() {
        when(keyValueStore.get(anyString())).thenReturn(null);

        boolean published = store.forceStatus("user@example.com", "ws-1",
                CalendarConnectionStatus.CONNECTED, "ws_real", null, "ADMIN_FORCE_SET", "forced");

        assertFalse(published, "No publisher configured -> nothing published");
        verify(keyValueStore).put(eq("gcal_conn_status:user@example.com:ws-1"), eq("CONNECTED"));
    }

    @Test
    void get_returnsNullWhenNotPresent() {
        when(keyValueStore.get(anyString())).thenReturn(null);

        CalendarConnectionStatus result = store.get("user@example.com", "ws-1");

        assertNull(result);
    }

    @Test
    void get_returnsParsedStatus() {
        when(keyValueStore.get("gcal_conn_status:user@example.com:ws-1")).thenReturn("REAUTH_REQUIRED");

        CalendarConnectionStatus result = store.get("user@example.com", "ws-1");

        assertEquals(CalendarConnectionStatus.REAUTH_REQUIRED, result);
    }

    @Test
    void put_wsContactKey_resolvesEmailFromReverseIndex_andPublishes() {
        // Auto-detected expiry path: GoogleProvider.recordRefreshFailureStatus passes the opaque
        // wsContact_<UUID> userId (no email) and no explicit email. resolveEmail() returns null, so
        // the publish would be skipped — UNLESS the reverse key->email index (written while healthy)
        // supplies the email. This is the fix that makes auto-detected REAUTH_REQUIRED reach CA.
        String wsContactKey = "wsContact_1ba35a88-5270-4261-a4ea-7e4a1bd1b1f3";
        when(keyValueStore.get("gcal_conn_status:" + wsContactKey + ":INV-1")).thenReturn("CONNECTED");

        UserKeyIndexStore index = mock(UserKeyIndexStore.class);
        when(index.getEmailByKey("INV-1", wsContactKey)).thenReturn("shuakki.vr@gmail.com");

        final String[] publishedEmail = {null};
        final int[] callCount = {0};
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                callCount[0]++;
                publishedEmail[0] = ((CalendarConnectionStatusChangedProto) event).getEmail();
            }
        };

        store.setPublisher(mockPublisher);
        store.setUserKeyIndexStore(index);
        store.put(wsContactKey, "INV-1", CalendarConnectionStatus.REAUTH_REQUIRED,
                "ws_real", "invalid_grant", "token revoked");

        assertEquals(1, callCount[0], "Reverse-index email must let the auto-detected publish through");
        assertEquals("shuakki.vr@gmail.com", publishedEmail[0]);
    }

    @Test
    void put_wsContactKey_noReverseEntry_skipsPublish() {
        String wsContactKey = "wsContact_1ba35a88-5270-4261-a4ea-7e4a1bd1b1f3";
        when(keyValueStore.get("gcal_conn_status:" + wsContactKey + ":INV-1")).thenReturn("CONNECTED");

        UserKeyIndexStore index = mock(UserKeyIndexStore.class);
        when(index.getEmailByKey("INV-1", wsContactKey)).thenReturn(null); // never recorded while healthy

        int[] callCount = {0};
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
                callCount[0]++;
            }
        };

        store.setPublisher(mockPublisher);
        store.setUserKeyIndexStore(index);
        store.put(wsContactKey, "INV-1", CalendarConnectionStatus.REAUTH_REQUIRED,
                "ws_real", "invalid_grant", "token revoked");

        assertEquals(0, callCount[0], "No reverse-index email and none resolvable -> publish skipped");
        // Status still persisted locally.
        verify(keyValueStore).put(eq("gcal_conn_status:" + wsContactKey + ":INV-1"), eq("REAUTH_REQUIRED"));
    }

    @Test
    void put_resolvableEmailKey_doesNotConsultReverseIndex() {
        // When the userId is a plain email, resolveEmail() succeeds and the reverse index is never touched.
        when(keyValueStore.get("gcal_conn_status:user@example.com:INV-1")).thenReturn("CONNECTED");

        UserKeyIndexStore index = mock(UserKeyIndexStore.class);
        CalendarConnectionStatusEventPublisher mockPublisher = new CalendarConnectionStatusEventPublisher() {
            @Override
            protected void publishProtoEventSync(String queueName, String workspaceId,
                                                  app.krista.infra.esb.client.Input event, String apiName) {
            }
        };

        store.setPublisher(mockPublisher);
        store.setUserKeyIndexStore(index);
        store.put("user@example.com", "INV-1", CalendarConnectionStatus.REAUTH_REQUIRED,
                "ws_real", "invalid_grant", "token revoked");

        verify(index, never()).getEmailByKey(anyString(), anyString());
    }

    @Test
    void isBroken_trueForReauthRequired() {
        assertTrue(CalendarConnectionStatus.REAUTH_REQUIRED.isBroken());
        assertTrue(CalendarConnectionStatus.RENEWAL_FAILED.isBroken());
        assertTrue(CalendarConnectionStatus.DISCONNECTED.isBroken());
        assertFalse(CalendarConnectionStatus.CONNECTED.isBroken());
    }

    // ── Always On either/or ───────────────────────────────────────────────

    /** Publisher that records every event, so a test can assert what would reach the wire. */
    private static final class CapturingPublisher extends CalendarConnectionStatusEventPublisher {

        private final java.util.List<CalendarConnectionStatusChangedProto> events = new java.util.ArrayList<>();

        @Override
        protected void publishProtoEventSync(String queueName, String workspaceId,
                                              app.krista.infra.esb.client.Input event, String apiName) {
            events.add((CalendarConnectionStatusChangedProto) event);
        }

        private CalendarConnectionStatusChangedProto last() {
            return events.get(events.size() - 1);
        }
    }

    /** A real map behind the store, so prior-status comparisons run against what was written. */
    private static KeyValueStore inMemoryKeyValueStore() {
        java.util.Map<String, Object> values = new java.util.concurrent.ConcurrentHashMap<>();
        KeyValueStore kv = mock(KeyValueStore.class);
        lenient().when(kv.get(anyString())).thenAnswer(call -> values.get(call.<String>getArgument(0)));
        lenient().doAnswer(call -> values.put(call.getArgument(0), call.getArgument(1)))
                .when(kv).put(anyString(), any());
        return kv;
    }

    private static final String KEY_INVOKER = "INV_1";

    private CalendarConnectionStatusStore alwaysOnStore(boolean alwaysOn, CapturingPublisher publisher) {
        CalendarConnectionStatusStore real = new CalendarConnectionStatusStore(inMemoryKeyValueStore());
        real.setPublisher(publisher);
        real.setAlwaysOnMode(() -> alwaysOn);
        return real;
    }

    @Test
    void alwaysOn_personalTokenWritersNeitherWriteNorPublish() {
        CapturingPublisher publisher = new CapturingPublisher();
        CalendarConnectionStatusStore real = alwaysOnStore(true, publisher);

        real.recordConnected("user@example.com", KEY_INVOKER, "ws_real", "user@example.com");
        real.put("user@example.com", KEY_INVOKER, CalendarConnectionStatus.REAUTH_REQUIRED, "ws_real",
                "invalid_grant", "revoked");
        real.put("user@example.com", KEY_INVOKER, CalendarConnectionStatus.RENEWAL_FAILED, "ws_real",
                "user@example.com", "IOException", "reset");
        real.put("user@example.com", KEY_INVOKER, CalendarConnectionStatus.DISCONNECTED, "ws_real",
                "USER_INITIATED");

        assertTrue(publisher.events.isEmpty(),
                "Always On never publishes a status from a personal token, and never REAUTH_REQUIRED");
        assertNull(real.get("user@example.com", KEY_INVOKER),
                "nor does it write one locally, or the next delegated comparison would read it");
    }

    @Test
    void optIn_personalTokenWritersAreUnchanged() {
        CapturingPublisher publisher = new CapturingPublisher();
        CalendarConnectionStatusStore real = alwaysOnStore(false, publisher);

        real.recordConnected("user@example.com", KEY_INVOKER, "ws_real", "user@example.com");
        real.put("user@example.com", KEY_INVOKER, CalendarConnectionStatus.REAUTH_REQUIRED, "ws_real",
                "invalid_grant", "revoked");

        assertEquals(java.util.List.of("CONNECTED", "REAUTH_REQUIRED"),
                publisher.events.stream().map(CalendarConnectionStatusChangedProto::getStatus).toList());
    }

    @Test
    void anUnreadableMode_readsAsOptIn() {
        CapturingPublisher publisher = new CapturingPublisher();
        CalendarConnectionStatusStore real = new CalendarConnectionStatusStore(inMemoryKeyValueStore());
        real.setPublisher(publisher);
        real.setAlwaysOnMode(() -> {
            throw new IllegalStateException("invoker removed");
        });

        real.recordConnected("user@example.com", KEY_INVOKER, "ws_real", "user@example.com");

        assertEquals(1, publisher.events.size());
    }

    @Test
    void delegatedConnected_isPublishedEverySweep_evenUnderAlwaysOn() {
        CapturingPublisher publisher = new CapturingPublisher();
        CalendarConnectionStatusStore real = alwaysOnStore(true, publisher);

        assertTrue(real.recordDelegatedConnected("seat@example.com", KEY_INVOKER, "ws_real", null));
        assertTrue(real.recordDelegatedConnected("seat@example.com", KEY_INVOKER, "ws_real", null));

        assertEquals(2, publisher.events.size(), "re-asserted every sweep; Meeting Agent de-duplicates");
        assertEquals("CONNECTED", publisher.last().getStatus());
        assertEquals("seat@example.com", publisher.last().getEmail());
        assertEquals(CalendarConnectionStatus.CONNECTED, real.get("seat@example.com", KEY_INVOKER));
    }

    @Test
    void delegatedUnreachable_afterConnected_publishesDisconnectedAccessRevokedWithTheProviderError() {
        CapturingPublisher publisher = new CapturingPublisher();
        CalendarConnectionStatusStore real = alwaysOnStore(true, publisher);
        real.recordDelegatedConnected("seat@example.com", KEY_INVOKER, "ws_real", null);

        assertTrue(real.recordDelegatedUnreachable("seat@example.com", KEY_INVOKER, "ws_real", null,
                "forbidden", "Not Authorized to access this resource/api"));

        CalendarConnectionStatusChangedProto event = publisher.last();
        assertEquals("DISCONNECTED", event.getStatus());
        assertEquals("ACCESS_REVOKED", event.getReason());
        assertEquals("forbidden", event.getLastErrorCode());
        assertEquals("Not Authorized to access this resource/api", event.getLastErrorMessage());
        assertEquals(CalendarConnectionStatus.DISCONNECTED, real.get("seat@example.com", KEY_INVOKER));
    }

    @Test
    void delegatedUnreachable_isPublishedOnce_notOnEverySweepThatFailsAgain() {
        CapturingPublisher publisher = new CapturingPublisher();
        CalendarConnectionStatusStore real = alwaysOnStore(true, publisher);
        real.recordDelegatedConnected("seat@example.com", KEY_INVOKER, "ws_real", null);

        real.recordDelegatedUnreachable("seat@example.com", KEY_INVOKER, "ws_real", null, "forbidden", "x");
        assertFalse(real.recordDelegatedUnreachable("seat@example.com", KEY_INVOKER, "ws_real", null,
                "forbidden", "x"));

        assertEquals(2, publisher.events.size(), "one CONNECTED, one DISCONNECTED");
    }

    @Test
    void delegatedUnreachable_forASeatNeverReached_publishesNothing_butRemembersTheFailure() {
        CapturingPublisher publisher = new CapturingPublisher();
        CalendarConnectionStatusStore real = alwaysOnStore(true, publisher);

        assertFalse(real.recordDelegatedUnreachable("never@example.com", KEY_INVOKER, "ws_real", null,
                "NOT_IN_DIRECTORY", "absent"));

        assertTrue(publisher.events.isEmpty(), "a seat never reached gets no status at all");
        assertEquals(CalendarConnectionStatus.DISCONNECTED, real.get("never@example.com", KEY_INVOKER),
                "kept locally so the snapshot can say FAILED and the first success is a transition");
    }

    @Test
    void delegatedUnreachable_neverPublishesAPersonalStatus_overAnOptInResidue() {
        CapturingPublisher publisher = new CapturingPublisher();
        CalendarConnectionStatusStore real = alwaysOnStore(false, publisher);
        real.put("user@example.com", KEY_INVOKER, CalendarConnectionStatus.REAUTH_REQUIRED, "ws_real",
                "invalid_grant", "revoked");
        publisher.events.clear();

        assertFalse(real.recordDelegatedUnreachable("user@example.com", KEY_INVOKER, "ws_real", null,
                "OUT_OF_SCOPE", "left"));
        assertTrue(publisher.events.isEmpty(),
                "only a mailbox previously CONNECTED is told it stopped being readable");
    }

    @Test
    void resolveEmail_readsTheWorkspaceAuthKeyForm() {
        assertEquals("ana@contoso.com",
                CalendarConnectionStatusStore.resolveEmail("workspace_auth:ws_x:Ana@Contoso.com"));
    }
}
