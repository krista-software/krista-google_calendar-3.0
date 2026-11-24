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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.EsbCaGateway;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarAuthenticationService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.ScannedMailboxReporter;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.ScannedMailboxSnapshot;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.calendar.Calendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS;
import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.ENROLLED_USERS;
import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.TENANT_DIRECTORY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * KIM-474 — watch-channel registration must follow whichever scan set is active, on the matching
 * credential.
 *
 * <p>Getting this wrong is a slow failure rather than a loud one, which is why it is tested at this
 * depth. If tenant-wide discovery finds every mailbox but channels are still registered only for the
 * handful of users who consented, the first sweep captures the tenant's meetings and then nothing
 * ever hears about a change to them again. And if renewal walks the wrong set, push notifications
 * for the whole tenant stop roughly five days after Always On was enabled — long enough that nobody
 * connects the two.
 *
 * <p>The channel is created through the real Google client over a canned transport, so the assertion
 * is on the request that would actually reach Google and the state that would actually be stored.
 */
@DisplayName("Watch channels follow the active scan set (KIM-474)")
class CalendarWatchServiceDiscoveryTest {

    private static final String NOTIFICATION_BASE_URL = "https://extensions.example";

    /** Minimal in-memory KeyValueStore — the watch store's real logic is worth exercising. */
    private static final class InMemoryKeyValueStore implements KeyValueStore {

        // subscribeAllUsers uses bounded virtual-thread fan-out; the fake store must model the
        // thread-safe KV store used in production rather than losing concurrent channel writes.
        private final Map<String, Object> values = new ConcurrentHashMap<>();

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
            return Set.copyOf(values.keySet());
        }

        @Override
        public Iterator<Map.Entry<String, Object>> iterator() {
            return new HashMap<>(values).entrySet().iterator();
        }
    }

    /**
     * Answers the three calls {@code createWatchChannel} makes — {@code calendars.get("primary")},
     * {@code events.list} for the baseline sync token, and {@code events.watch} — and records the
     * URLs so the test can prove which calendar was actually targeted.
     */
    private static final class CalendarTransport extends MockHttpTransport {

        private final String primaryCalendarId;
        private final List<String> requestedUrls = new ArrayList<>();
        private final List<String> watchBodies = new ArrayList<>();

        private CalendarTransport(String primaryCalendarId) {
            this.primaryCalendarId = primaryCalendarId;
        }

        @Override
        public LowLevelHttpRequest buildRequest(String method, String url) {
            requestedUrls.add(url);
            final String body;
            if (url.contains("/watch")) {
                body = "{\"kind\":\"api#channel\",\"id\":\"channel-" + primaryCalendarId
                        + "\",\"resourceId\":\"resource-1\",\"expiration\":\"4102444800000\"}";
            } else if (url.contains("/events")) {
                body = "{\"kind\":\"calendar#events\",\"items\":[],\"nextSyncToken\":\"sync-1\"}";
            } else {
                body = "{\"kind\":\"calendar#calendar\",\"id\":\"" + primaryCalendarId + "\"}";
            }
            return new MockLowLevelHttpRequest() {
                @Override
                public LowLevelHttpResponse execute() throws IOException {
                    if (url.contains("/watch")) {
                        watchBodies.add(getContentAsString());
                    }
                    return new MockLowLevelHttpResponse()
                            .setStatusCode(200)
                            .setContentType("application/json")
                            .setContent(body);
                }
            };
        }
    }

    private static Calendar calendarOver(CalendarTransport transport) {
        return new Calendar.Builder(transport, GsonFactory.getDefaultInstance(), null)
                .setApplicationName("test")
                .build();
    }

    private GoogleProviderFactory googleProviderFactory;
    private GoogleProvider googleProvider;
    private GoogleCalendarAuthenticationService authenticationService;
    private CalendarDiscoveryService discoveryService;
    private CalendarWatchStore watchStore;
    private Invoker invoker;
    private CalendarWatchService watchService;
    private CalendarConnectionStatusStore connectionStatusStore;

    @BeforeEach
    void setUp() {
        googleProviderFactory = mock(GoogleProviderFactory.class);
        googleProvider = mock(GoogleProvider.class);
        authenticationService = mock(GoogleCalendarAuthenticationService.class);
        discoveryService = mock(CalendarDiscoveryService.class);
        watchStore = new CalendarWatchStore(new InMemoryKeyValueStore());
        invoker = mock(Invoker.class);
        connectionStatusStore = mock(CalendarConnectionStatusStore.class);

        RoutingInfo routingInfo = mock(RoutingInfo.class);
        lenient().when(routingInfo.getRoutingURL(anyString(), any())).thenReturn(NOTIFICATION_BASE_URL);
        lenient().when(invoker.getRoutingInfo()).thenReturn(routingInfo);
        lenient().when(invoker.getAttributes()).thenReturn(Map.of(
                app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants.SERVICE_URL,
                "https://conversation-agent.example"));
        lenient().when(invoker.getInvokerId()).thenReturn("INV_google_calendar");
        lenient().when(invoker.getWorkspaceId()).thenReturn("ws-gcal-1");
        lenient().when(googleProviderFactory.create()).thenReturn(googleProvider);

        watchService = new CalendarWatchService(googleProviderFactory, authenticationService, watchStore,
                invoker, mock(UserKeyIndexStore.class), discoveryService);
        watchService.setConnectionStatusStore(connectionStatusStore);
    }

    private void scanSetIs(CalendarDiscoveryService.DiscoverySource source, List<String> users) {
        Set<String> tenantMembers = source == TENANT_DIRECTORY || source == ENROLLED_USERS
                ? Set.copyOf(users) : Set.of();
        lenient().when(discoveryService.discover(anyString()))
                .thenReturn(new CalendarDiscoveryService.UserSet(source, users, tenantMembers, null));
    }

    // ── subscribe ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Subscribing")
    class Subscribing {

        @Test
        @DisplayName("Always On off: channels for the consented users, on their own tokens")
        void consentedPathUnchanged() {
            scanSetIs(CONSENTED_USERS, List.of("consented@acme.example"));
            CalendarTransport transport = new CalendarTransport("consented@acme.example");
            when(googleProvider.getGoogleClientForUser("consented@acme.example"))
                    .thenReturn(calendarOver(transport));

            watchService.subscribeAllUsers();

            verify(googleProvider).getGoogleClientForUser("consented@acme.example");
            verify(discoveryService, never()).calendarClientFor(anyString(), any());
            verify(connectionStatusStore, never()).recordDelegatedConnected(any(), any(), any(), any());
            verify(connectionStatusStore, never()).recordDelegatedUnreachable(any(), any(), any(), any(), any(), any());
            assertEquals("channel-consented@acme.example", watchStore.getChannelId("consented@acme.example"));
            assertEquals("sync-1", watchStore.getSyncToken("consented@acme.example"));
        }

        @Test
        @DisplayName("Always On on: a channel per tenant mailbox, on delegated credentials")
        void tenantWideUsesDelegation() {
            scanSetIs(TENANT_DIRECTORY, List.of("alice@acme.example", "bob@acme.example"));
            when(discoveryService.calendarClientFor("alice@acme.example", TENANT_DIRECTORY))
                    .thenReturn(calendarOver(new CalendarTransport("alice@acme.example")));
            when(discoveryService.calendarClientFor("bob@acme.example", TENANT_DIRECTORY))
                    .thenReturn(calendarOver(new CalendarTransport("bob@acme.example")));

            watchService.subscribeAllUsers();

            assertEquals("channel-alice@acme.example", watchStore.getChannelId("alice@acme.example"));
            assertEquals("channel-bob@acme.example", watchStore.getChannelId("bob@acme.example"));
            verify(googleProviderFactory, never()).create();
        }

        @Test
        @DisplayName("Always On enrolled seats publish CONNECTED after delegated watch setup")
        void enrolledSeatsPublishConnected() {
            scanSetIs(ENROLLED_USERS, List.of("alice@acme.example"));
            when(discoveryService.calendarClientFor("alice@acme.example", ENROLLED_USERS))
                    .thenReturn(calendarOver(new CalendarTransport("alice@acme.example")));

            watchService.subscribeAllUsers();

            verify(connectionStatusStore).recordDelegatedConnected("alice@acme.example", "INV_google_calendar",
                    "ws-gcal-1", null);
            verify(connectionStatusStore, never()).recordConnected(any(), any(), any(), any());
        }

        /**
         * The reverse index is what routes an inbound notification to its owner in O(1). A
         * tenant-wide channel's owner is not in the consented list, so without the index every
         * notification for them would fall through to a scan that cannot find them.
         */
        @Test
        @DisplayName("Every tenant-wide channel is indexed back to its owner")
        void indexesChannelsToOwners() {
            scanSetIs(TENANT_DIRECTORY, List.of("alice@acme.example"));
            when(discoveryService.calendarClientFor("alice@acme.example", TENANT_DIRECTORY))
                    .thenReturn(calendarOver(new CalendarTransport("alice@acme.example")));

            watchService.subscribeAllUsers();

            assertEquals("alice@acme.example",
                    watchStore.getUserByChannelId("channel-alice@acme.example"));
        }

        @Test
        @DisplayName("One mailbox refusing does not abort the sweep")
        void oneFailureDoesNotAbortTheSweep() {
            scanSetIs(TENANT_DIRECTORY, List.of("denied@acme.example", "alice@acme.example"));
            when(discoveryService.calendarClientFor("denied@acme.example", TENANT_DIRECTORY))
                    .thenThrow(new IllegalStateException("403 Not Authorized"));
            when(discoveryService.calendarClientFor("alice@acme.example", TENANT_DIRECTORY))
                    .thenReturn(calendarOver(new CalendarTransport("alice@acme.example")));

            watchService.subscribeAllUsers();

            assertNull(watchStore.getChannelId("denied@acme.example"));
            assertNotNull(watchStore.getChannelId("alice@acme.example"),
                    "A single unauthorised mailbox must cost that mailbox, not the whole tenant");
        }

        @Test
        @DisplayName("An empty scan set registers nothing and touches no Google API")
        void emptySetIsANoOp() {
            scanSetIs(CONSENTED_USERS, List.of());

            watchService.subscribeAllUsers();

            verify(googleProviderFactory, never()).create();
            verify(discoveryService, never()).calendarClientFor(anyString(), any());
        }
    }

    // ── renewal and teardown ──────────────────────────────────────────────

    @Nested
    @DisplayName("Renewal and teardown")
    class RenewalAndTeardown {

        @Test
        @DisplayName("Renewal covers the tenant set, not just the consented users")
        void renewalFollowsTheActiveSet() {
            scanSetIs(TENANT_DIRECTORY, List.of("alice@acme.example"));
            // A channel already stored and expiring within the day.
            watchStore.put("alice@acme.example", "old-channel", "old-resource",
                    System.currentTimeMillis() + 3_600_000L);
            watchStore.putChannelUser("old-channel", "alice@acme.example");
            when(discoveryService.calendarClientFor("alice@acme.example", TENANT_DIRECTORY))
                    .thenReturn(calendarOver(new CalendarTransport("alice@acme.example")));

            watchService.renewExpiringChannels();

            assertEquals("channel-alice@acme.example", watchStore.getChannelId("alice@acme.example"),
                    "Without this the tenant's push notifications stop ~5 days after Always On is "
                            + "enabled, with nothing to connect the outage to the change");
            verify(discoveryService).discover("watch_renewal");
        }

        @Test
        @DisplayName("A channel nowhere near expiry is left alone")
        void doesNotRenewEarly() {
            scanSetIs(TENANT_DIRECTORY, List.of("alice@acme.example"));
            watchStore.put("alice@acme.example", "current-channel", "resource",
                    System.currentTimeMillis() + (5L * 24 * 60 * 60 * 1000));

            watchService.renewExpiringChannels();

            assertEquals("current-channel", watchStore.getChannelId("alice@acme.example"));
            verify(discoveryService, never()).calendarClientFor(anyString(), any());
        }

        @Test
        @DisplayName("Teardown stops the tenant's channels on the credential that created them")
        void teardownFollowsTheActiveSet() {
            scanSetIs(TENANT_DIRECTORY, List.of("alice@acme.example"));
            watchStore.put("alice@acme.example", "channel-1", "resource-1",
                    System.currentTimeMillis() + 3_600_000L);
            when(discoveryService.calendarClientFor("alice@acme.example", TENANT_DIRECTORY))
                    .thenReturn(calendarOver(new CalendarTransport("alice@acme.example")));

            watchService.unsubscribeAllUsers();

            assertNull(watchStore.getChannelId("alice@acme.example"));
            verify(discoveryService).calendarClientFor("alice@acme.example", TENANT_DIRECTORY);
            verify(googleProviderFactory, never()).create();
        }

        @Test
        @DisplayName("An already-active channel is checked but not re-created")
        void skipsActiveChannels() {
            scanSetIs(TENANT_DIRECTORY, List.of("alice@acme.example"));
            watchStore.put("alice@acme.example", "existing", "resource",
                    System.currentTimeMillis() + (4L * 24 * 60 * 60 * 1000));
            when(discoveryService.calendarClientFor("alice@acme.example", TENANT_DIRECTORY))
                    .thenReturn(calendarOver(new CalendarTransport("alice@acme.example")));

            watchService.subscribeAllUsers();

            assertEquals("existing", watchStore.getChannelId("alice@acme.example"));
            verify(discoveryService).calendarClientFor("alice@acme.example", TENANT_DIRECTORY);
        }
    }

    // ── notification routing ──────────────────────────────────────────────

    /**
     * Google echoes the channel token back on every notification. The token is the fastest and
     * race-free way to identify the owner — but it arrives from outside, so it is only trusted when
     * something local vouches for it.
     */
    @Nested
    @DisplayName("Resolving the owner of an inbound notification")
    class OwnerResolution {

        /**
         * S-23 — EDITED. This previously asserted that a token naming a consented user is trusted on
         * that basis alone ({@code isUserAuthenticated(channelToken)}), with the channel-id
         * correlation applied only on the Always On path.
         *
         * <p>That made the consented limb forgeable. Both headers arrive from OUTSIDE — Google echoes
         * them, but so does anybody who can reach the webhook, and its URL is a fixed, guessable path
         * on the appliance — so a request carrying {@code X-Goog-Channel-Token: <a consented user's
         * address>} was attributed to that user and triggered an incremental sync of their calendar on
         * demand. The token was checked for being a KNOWN NAME, which is not the same as being
         * evidence.
         *
         * <p><b>Correlating the channel id costs the consented population nothing</b>, which is what
         * let the Always On gate go: {@code createWatchChannel} writes the reverse index for EVERY
         * channel it creates, so a genuine notification the token limb now declines is resolved to the
         * same user one limb down — asserted by {@link #consentedNotificationStillResolves()} below.
         */
        @Test
        @DisplayName("S-23: a token alone is NOT trusted, however well-known the name it carries")
        void consentedTokenAloneIsNotTrusted() {
            when(authenticationService.isUserAuthenticated("consented@acme.example")).thenReturn(true);
            when(authenticationService.getAuthenticatedUsers())
                    .thenReturn(List.of("consented@acme.example"));

            assertNull(watchService.resolveUser("forged-channel", "consented@acme.example"),
                    "Being a consented user is not evidence that this notification is about them. "
                            + "The webhook URL is a fixed path on the appliance, so the token is "
                            + "attacker-supplied and naming a real user is trivial.");
        }

        /**
         * The other half of S-23: the fix must not cost a real notification. This is the production
         * shape — a channel created through {@code createWatchChannel}, which writes both the watch row
         * and the reverse index — so the user resolves whether or not the token limb accepts it.
         */
        @Test
        @DisplayName("S-23: a REAL consented notification still resolves, through the store")
        void consentedNotificationStillResolves() {
            // Exactly what createWatchChannel writes, for consented and delegated channels alike.
            watchStore.put("consented@acme.example", "channel-1", "resource-1", 0L);
            watchStore.putChannelUser("channel-1", "consented@acme.example");

            assertEquals("consented@acme.example",
                    watchService.resolveUser("channel-1", "consented@acme.example"));
            // And with the token missing entirely, which is the same path.
            assertEquals("consented@acme.example", watchService.resolveUser("channel-1", null));
        }

        @Test
        @DisplayName("a token backed by our own matching watch row is trusted, flag or no flag")
        void tokenTrustedWhenBackedByOurOwnRow() {
            // No isAlwaysOn stub: S-23 removed the gate, so the rule is now the same for both
            // populations. One rule is also one thing to get right.
            watchStore.put("alice@acme.example", "channel-1", "resource-1", 0L);

            assertEquals("alice@acme.example", watchService.resolveUser("channel-1", "alice@acme.example"));
        }

        @Test
        @DisplayName("a token whose channel does not match is NOT trusted")
        void mismatchedChannelNotTrusted() {
            watchStore.put("alice@acme.example", "channel-1", "resource-1", 0L);

            assertNull(watchService.resolveUser("channel-1", "mallory@acme.example"),
                    "Requiring the channelId to match makes the token unforgeable-by-guessing rather "
                            + "than merely well-formed");
        }

        /**
         * The attack this closes, stated as one case: an outsider who knows a colleague's address and
         * the webhook path must not be able to name them as the owner of a notification.
         */
        @Test
        @DisplayName("S-23: a forged token cannot select a user whose channel it does not know")
        void forgedTokenCannotSelectAUser() {
            when(authenticationService.isUserAuthenticated("victim@acme.example")).thenReturn(true);
            when(authenticationService.getAuthenticatedUsers())
                    .thenReturn(List.of("victim@acme.example"));
            watchStore.put("victim@acme.example", "real-channel", "resource-1", 0L);
            watchStore.putChannelUser("real-channel", "victim@acme.example");

            assertNull(watchService.resolveUser("guessed-channel", "victim@acme.example"),
                    "the caller reproduced neither the channel id Google issued nor anything else "
                            + "this extension wrote, so there is nothing vouching for the token");
        }

        @Test
        @DisplayName("The reverse index still resolves a channel whose token was lost")
        void indexResolvesWhenTokenMissing() {
            watchStore.putChannelUser("channel-1", "alice@acme.example");
            lenient().when(discoveryService.isAlwaysOn()).thenReturn(true);

            assertEquals("alice@acme.example", watchService.resolveUser("channel-1", null));
        }

        /**
         * The last-resort scan exists for channels created before the reverse index. It is
         * deliberately NOT routed through discovery: doing so would put an Admin SDK round-trip on
         * every unresolvable notification, searching a set that by construction cannot contain the
         * answer.
         */
        @Test
        @DisplayName("The legacy scan stays on the consented list and makes no Directory call")
        void legacyScanDoesNotEnumerateTheTenant() {
            when(authenticationService.getAuthenticatedUsers()).thenReturn(List.of("legacy@acme.example"));
            watchStore.put("legacy@acme.example", "channel-legacy", "resource", 0L);

            assertEquals("legacy@acme.example", watchService.findUserByChannelId("channel-legacy"));
            verify(discoveryService, never()).discover(anyString());
        }
    }

    // ── explicit-source overloads ─────────────────────────────────────────

    @Test
    @DisplayName("A single-user call resolves its own source through the seam")
    void singleUserCallResolvesItsOwnSource() {
        when(discoveryService.sourceFor("alice@acme.example")).thenReturn(TENANT_DIRECTORY);
        when(discoveryService.calendarClientFor("alice@acme.example", TENANT_DIRECTORY))
                .thenReturn(calendarOver(new CalendarTransport("alice@acme.example")));

        assertNotNull(watchService.createWatchChannel("alice@acme.example"));

        verify(discoveryService).sourceFor("alice@acme.example");
        verify(discoveryService).calendarClientFor(eq("alice@acme.example"), eq(TENANT_DIRECTORY));
    }

    @Test
    @DisplayName("The channel address is Conversation Agent's callback, scoped to this workspace and invoker")
    void channelAddressIsConversationAgentCallback() {
        scanSetIs(TENANT_DIRECTORY, List.of("alice@acme.example"));
        CalendarTransport transport = new CalendarTransport("alice@acme.example");
        when(discoveryService.calendarClientFor("alice@acme.example", TENANT_DIRECTORY))
                .thenReturn(calendarOver(transport));

        watchService.subscribeAllUsers();

        assertEquals(1, transport.watchBodies.size(), transport.requestedUrls.toString());
        // CA answers 400 without workspaceId/invokerId and 404 unless invokerId matches the
        // GOOGLE binding it holds for that workspace.
        assertTrue(transport.watchBodies.get(0).contains("\"address\":\"https://conversation-agent.example"
                        + "/api/v1/calendar/provider/google/notification?workspaceId=ws-gcal-1"
                        + "&invokerId=INV_google_calendar\""),
                transport.watchBodies.get(0));
    }

    @Test
    @DisplayName("No CA service URL refuses the watch instead of registering a legacy address")
    void missingServiceUrlRefusesTheWatch() {
        when(invoker.getAttributes()).thenReturn(Map.of());
        when(discoveryService.sourceFor("alice@acme.example")).thenReturn(TENANT_DIRECTORY);
        CalendarTransport transport = new CalendarTransport("alice@acme.example");
        when(discoveryService.calendarClientFor("alice@acme.example", TENANT_DIRECTORY))
                .thenReturn(calendarOver(transport));

        assertThrows(RuntimeException.class, () -> watchService.createWatchChannel("alice@acme.example"));
        assertTrue(transport.watchBodies.isEmpty(), transport.requestedUrls.toString());
    }

    // ── the scanned-mailbox report ────────────────────────────────────────

    @Nested
    @DisplayName("Reporting the sweep to Meeting Agent")
    class Reporting {

        private EsbCaGateway caGateway;
        private CalendarWatchService reporting;

        @BeforeEach
        void reportingService() {
            caGateway = mock(EsbCaGateway.class);
            lenient().when(caGateway.reportScannedMailboxes(any())).thenReturn(true);
            UserKeyIndexStore index = mock(UserKeyIndexStore.class);
            reporting = new CalendarWatchService(googleProviderFactory, authenticationService, watchStore,
                    invoker, index, discoveryService,
                    new ScannedMailboxReporter(invoker, watchStore, index, caGateway, connectionStatusStore));
            reporting.setConnectionStatusStore(connectionStatusStore);
        }

        private ScannedMailboxSnapshot reported() {
            ArgumentCaptor<ScannedMailboxSnapshot> sent = ArgumentCaptor.forClass(ScannedMailboxSnapshot.class);
            verify(caGateway).reportScannedMailboxes(sent.capture());
            return sent.getValue();
        }

        private Map<String, String> statesByEmail(ScannedMailboxSnapshot snapshot) {
            Map<String, String> states = new HashMap<>();
            snapshot.mailboxes().forEach(row -> states.put(row.email(), row.subscriptionState()));
            return states;
        }

        private void holdWatch(String userId, long expiration) {
            watchStore.put(userId, "channel-" + userId, "resource-1", expiration);
        }

        @Test
        @DisplayName("Always On: a created channel and a failed mailbox are told apart, in one sweep")
        void delegatedSweepReportsEachOutcome() {
            scanSetIs(ENROLLED_USERS, List.of("alice@acme.example", "denied@acme.example"));
            when(discoveryService.calendarClientFor("alice@acme.example", ENROLLED_USERS))
                    .thenReturn(calendarOver(new CalendarTransport("alice@acme.example")));
            when(discoveryService.calendarClientFor("denied@acme.example", ENROLLED_USERS))
                    .thenThrow(new IllegalStateException("403 Not Authorized"));

            reporting.subscribeAllUsers();

            ScannedMailboxSnapshot snapshot = reported();
            assertEquals("ENROLLED_USERS", snapshot.mode());
            assertEquals(Map.of("alice@acme.example", "SUBSCRIBED", "denied@acme.example", "FAILED"),
                    statesByEmail(snapshot));
            snapshot.mailboxes().forEach(row -> {
                assertEquals("GOOGLE", row.calendarSystem());
                assertEquals("ENROLLED_USER", row.scopeReason());
            });
            assertEquals("channel-alice@acme.example", snapshot.mailboxes().get(0).subscriptionId());
            assertNull(snapshot.mailboxes().get(1).subscriptionId());
        }

        @Test
        @DisplayName("Always On: an existing watch is SUBSCRIBED only once events.list has answered")
        void delegatedExistingWatchIsVerified() {
            scanSetIs(ENROLLED_USERS, List.of("alice@acme.example"));
            holdWatch("alice@acme.example", System.currentTimeMillis() + 3_600_000);
            CalendarTransport transport = new CalendarTransport("alice@acme.example");
            when(discoveryService.calendarClientFor("alice@acme.example", ENROLLED_USERS))
                    .thenReturn(calendarOver(transport));

            reporting.subscribeAllUsers();

            assertEquals("SUBSCRIBED", statesByEmail(reported()).get("alice@acme.example"));
            assertTrue(transport.requestedUrls.stream().anyMatch(url -> url.contains("/events")),
                    "the state must rest on the read having happened");
        }

        @Test
        @DisplayName("Always On: a failed verification is FAILED and goes down the delegated DISCONNECTED path")
        void delegatedVerificationFailureIsFailedOnTheDelegatedPath() {
            scanSetIs(ENROLLED_USERS, List.of("alice@acme.example"));
            holdWatch("alice@acme.example", System.currentTimeMillis() + 3_600_000);
            when(discoveryService.calendarClientFor("alice@acme.example", ENROLLED_USERS))
                    .thenThrow(new IllegalStateException("403 delegation revoked"));

            reporting.subscribeAllUsers();

            assertEquals("FAILED", statesByEmail(reported()).get("alice@acme.example"));
            // The store decides: DISCONNECTED is published only if alice was CONNECTED before.
            verify(connectionStatusStore).recordDelegatedUnreachable(eq("alice@acme.example"),
                    eq("INV_google_calendar"), eq("ws-gcal-1"), isNull(), eq("IllegalStateException"),
                    eq("403 delegation revoked"));
            verify(connectionStatusStore, never()).recordConnected(any(), any(), any(), any());
            verify(connectionStatusStore, never()).put(any(), any(), any(), any());
            verify(connectionStatusStore, never()).put(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Opt-In: no snapshot is sent — Meeting Agent keeps no Always On inventory for it")
        void consentedSweepSendsNoSnapshot() {
            scanSetIs(CONSENTED_USERS, List.of("consented@acme.example"));
            when(googleProvider.getGoogleClientForUser("consented@acme.example"))
                    .thenReturn(calendarOver(new CalendarTransport("consented@acme.example")));

            reporting.subscribeAllUsers();

            assertNotNull(watchStore.getChannelId("consented@acme.example"));
            verify(caGateway, never()).reportScannedMailboxes(any());
        }

        @Test
        @DisplayName("Seats outside the directory are reported even when nobody is left to scan")
        void emptyScanSetStillReportsAbsentSeats() {
            when(discoveryService.discover(anyString())).thenReturn(new CalendarDiscoveryService.UserSet(
                    ENROLLED_USERS, List.of(), Set.of(), null,
                    List.of("ghost@acme.example"), List.of("unread@acme.example")));

            reporting.subscribeAllUsers();

            ScannedMailboxSnapshot snapshot = reported();
            assertEquals(Map.of("ghost@acme.example", "NOT_IN_DIRECTORY", "unread@acme.example", "PENDING"),
                    statesByEmail(snapshot));
            ScannedMailboxSnapshot.Mailbox ghost = snapshot.mailboxes().stream()
                    .filter(row -> row.email().equals("ghost@acme.example")).findFirst().orElseThrow();
            assertNull(ghost.subscriptionId());
            assertEquals("ghost@acme.example", ghost.displayLabel());
            assertEquals("ENROLLED_USER", ghost.scopeReason());
        }

        @Test
        @DisplayName("An empty sweep sends nothing: it could equally be a sweep that failed")
        void emptySweepIsNotReported() {
            scanSetIs(ENROLLED_USERS, List.of());

            reporting.subscribeAllUsers();

            verify(caGateway, never()).reportScannedMailboxes(any());
        }

        @Test
        @DisplayName("A degraded sweep scans nothing, publishes nothing, and reports an EMPTY snapshot with its reason")
        void degradedSweepScansNothingAndReportsItsReason() {
            when(discoveryService.discover(anyString())).thenReturn(new CalendarDiscoveryService.UserSet(
                    ENROLLED_USERS, List.of(), Set.of(), "registered-seats-unavailable"));
            // A channel already held must survive: a missing answer is not an answer of "nobody".
            holdWatch("held@acme.example", System.currentTimeMillis() + 3L * 24 * 3_600_000);

            reporting.subscribeAllUsers();

            ScannedMailboxSnapshot snapshot = reported();
            assertEquals("ENROLLED_USERS", snapshot.mode());
            assertEquals("registered-seats-unavailable", snapshot.degradedReason());
            assertTrue(snapshot.mailboxes().isEmpty(), "a degraded sweep scanned nothing, so it has no rows");
            verifyNoInteractions(connectionStatusStore);
            verifyNoInteractions(googleProviderFactory);
            verify(discoveryService, never()).calendarClientFor(anyString(), any());
            assertNotNull(watchStore.getChannelId("held@acme.example"), "revoke nothing");
        }

        @Test
        @DisplayName("A report that throws costs the report, never the sweep")
        void reportFailureDoesNotFailTheSweep() {
            scanSetIs(ENROLLED_USERS, List.of("alice@acme.example"));
            when(discoveryService.calendarClientFor("alice@acme.example", ENROLLED_USERS))
                    .thenReturn(calendarOver(new CalendarTransport("alice@acme.example")));
            when(caGateway.reportScannedMailboxes(any())).thenThrow(new IllegalStateException("broker gone"));

            reporting.subscribeAllUsers();

            assertNotNull(watchStore.getChannelId("alice@acme.example"));
        }

        @Test
        @DisplayName("Always On renewal reports: untouched seats keep their last delegated outcome")
        void delegatedRenewalReportsLastKnownForUntouchedSeats() {
            scanSetIs(ENROLLED_USERS, List.of("watching@acme.example", "broken@acme.example",
                    "unknown@acme.example"));
            holdWatch("watching@acme.example", System.currentTimeMillis() + 3L * 24 * 3_600_000);
            holdWatch("broken@acme.example", System.currentTimeMillis() + 3L * 24 * 3_600_000);
            when(connectionStatusStore.get("watching@acme.example", "INV_google_calendar"))
                    .thenReturn(app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store
                            .CalendarConnectionStatus.CONNECTED);
            when(connectionStatusStore.get("broken@acme.example", "INV_google_calendar"))
                    .thenReturn(app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store
                            .CalendarConnectionStatus.DISCONNECTED);

            reporting.renewExpiringChannels();

            assertEquals(Map.of("watching@acme.example", "SUBSCRIBED", "broken@acme.example", "FAILED",
                    "unknown@acme.example", "PENDING"), statesByEmail(reported()));
            verify(discoveryService, never()).calendarClientFor(anyString(), any());
        }

        @Test
        @DisplayName("Always On renewal: a renewal that fails is FAILED and goes down the delegated path")
        void delegatedRenewalFailureIsFailed() {
            scanSetIs(ENROLLED_USERS, List.of("expiring@acme.example"));
            holdWatch("expiring@acme.example", System.currentTimeMillis() + 3_600_000);
            when(discoveryService.calendarClientFor("expiring@acme.example", ENROLLED_USERS))
                    .thenThrow(new IllegalStateException("403 delegation revoked"));

            reporting.renewExpiringChannels();

            assertEquals("FAILED", statesByEmail(reported()).get("expiring@acme.example"));
            verify(connectionStatusStore).recordDelegatedUnreachable(eq("expiring@acme.example"),
                    anyString(), anyString(), isNull(), anyString(), anyString());
        }

        @Test
        @DisplayName("Opt-In renewal reports nothing and publishes nothing, as before Always On existed")
        void consentedRenewalIsUnchanged() {
            scanSetIs(CONSENTED_USERS, List.of("consented@acme.example"));
            holdWatch("consented@acme.example", System.currentTimeMillis() + 3L * 24 * 3_600_000);
            lenient().when(authenticationService.getAuthenticatedUsers())
                    .thenReturn(List.of("consented@acme.example"));

            reporting.renewExpiringChannels();

            verify(caGateway, never()).reportScannedMailboxes(any());
            verifyNoInteractions(connectionStatusStore);
        }
    }

    // ── Always On statuses for mailboxes that stopped being readable ──────

    @Nested
    @DisplayName("Always On: Connected changes when the calendar stops being readable")
    class Departures {

        private void holdWatch(String userId) {
            watchStore.put(userId, "channel-" + userId, "resource-1",
                    System.currentTimeMillis() + 3L * 24 * 3_600_000);
            watchStore.putChannelUser("channel-" + userId, userId);
        }

        @Test
        @DisplayName("A mailbox that left the scanned set goes DISCONNECTED before its channel is revoked")
        void leftTheScanSet() {
            scanSetIs(ENROLLED_USERS, List.of("alice@acme.example"));
            holdWatch("alice@acme.example");
            holdWatch("gone@acme.example");
            lenient().when(discoveryService.sourceFor("gone@acme.example")).thenReturn(ENROLLED_USERS);
            when(discoveryService.calendarClientFor("alice@acme.example", ENROLLED_USERS))
                    .thenReturn(calendarOver(new CalendarTransport("alice@acme.example")));

            watchService.subscribeAllUsers();

            verify(connectionStatusStore).recordDelegatedUnreachable(eq("gone@acme.example"),
                    eq("INV_google_calendar"), eq("ws-gcal-1"), isNull(), eq("OUT_OF_SCOPE"), anyString());
            verify(connectionStatusStore, never()).recordDelegatedUnreachable(eq("alice@acme.example"),
                    any(), any(), any(), any(), any());
            assertNull(watchStore.getChannelId("gone@acme.example"), "and then the channel is revoked");
        }

        /**
         * An empty roster keeps its channels — stopping every one on a single answer is the outage
         * the revoke guard prevents — but none of those calendars is read any more, so Connected
         * must stop saying otherwise.
         */
        @Test
        @DisplayName("An empty roster disconnects every held mailbox but stops no channel")
        void emptyRosterDisconnectsWithoutRevoking() {
            scanSetIs(ENROLLED_USERS, List.of());
            holdWatch("formerly@acme.example");

            watchService.subscribeAllUsers();

            verify(connectionStatusStore).recordDelegatedUnreachable(eq("formerly@acme.example"),
                    any(), any(), isNull(), eq("OUT_OF_SCOPE"), anyString());
            assertNotNull(watchStore.getChannelId("formerly@acme.example"));
        }

        @Test
        @DisplayName("A seat the directory no longer lists goes DISCONNECTED with NOT_IN_DIRECTORY")
        void notInDirectory() {
            when(discoveryService.discover(anyString())).thenReturn(new CalendarDiscoveryService.UserSet(
                    ENROLLED_USERS, List.of(), Set.of(), null, List.of("ghost@acme.example"), List.of()));

            watchService.subscribeAllUsers();

            verify(connectionStatusStore).recordDelegatedUnreachable(eq("ghost@acme.example"),
                    any(), any(), isNull(), eq("NOT_IN_DIRECTORY"), anyString());
        }

        @Test
        @DisplayName("A seat never reached gets no status at all")
        void neverReachedSeatGetsNothing() {
            when(discoveryService.discover(anyString())).thenReturn(new CalendarDiscoveryService.UserSet(
                    ENROLLED_USERS, List.of(), Set.of(), null, List.of(), List.of("unread@acme.example")));

            watchService.subscribeAllUsers();

            verifyNoInteractions(connectionStatusStore);
        }

        @Test
        @DisplayName("A degraded sweep observes no departure: it resolved no scope")
        void degradedSweepPublishesNoDeparture() {
            when(discoveryService.discover(anyString())).thenReturn(new CalendarDiscoveryService.UserSet(
                    ENROLLED_USERS, List.of(), Set.of(), "directory read failed"));
            holdWatch("held@acme.example");

            watchService.subscribeAllUsers();

            verifyNoInteractions(connectionStatusStore);
            assertNotNull(watchStore.getChannelId("held@acme.example"));
        }

        @Test
        @DisplayName("Opt-In: a consented user whose consent was withdrawn is revoked with no status from here")
        void optInDeparturePublishesNothing() {
            scanSetIs(CONSENTED_USERS, List.of("consented@acme.example"));
            holdWatch("consented@acme.example");
            holdWatch("withdrawn@acme.example");
            lenient().when(discoveryService.sourceFor("withdrawn@acme.example")).thenReturn(CONSENTED_USERS);

            watchService.subscribeAllUsers();

            verifyNoInteractions(connectionStatusStore);
            assertNull(watchStore.getChannelId("withdrawn@acme.example"));
        }

        @Test
        @DisplayName("Our own missing callback URL is not a fact about the mailbox: no DISCONNECTED")
        void missingCallbackUrlPublishesNothing() {
            when(invoker.getAttributes()).thenReturn(Map.of());
            when(discoveryService.calendarClientFor("alice@acme.example", ENROLLED_USERS))
                    .thenReturn(calendarOver(new CalendarTransport("alice@acme.example")));

            assertThrows(RuntimeException.class,
                    () -> watchService.createWatchChannel("alice@acme.example", ENROLLED_USERS));

            verify(connectionStatusStore, never()).recordDelegatedUnreachable(any(), any(), any(), any(), any(), any());
        }
    }

    // ── either/or: no personal token under Always On ──────────────────────

    @Nested
    @DisplayName("Always On never builds a per-user client")
    class NoPersonalTokens {

        @Test
        @DisplayName("A stale CONSENTED source under Always On is refused before any token is touched")
        void staleConsentedSourceIsRefused() {
            when(discoveryService.isAlwaysOn()).thenReturn(true);

            assertThrows(RuntimeException.class,
                    () -> watchService.createWatchChannel("consented@acme.example", CONSENTED_USERS));

            verifyNoInteractions(googleProviderFactory);
            verify(googleProvider, never()).getGoogleClientForUser(anyString());
        }

        @Test
        @DisplayName("A delegated sweep reads every mailbox on the delegated credential")
        void delegatedSweepNeverTouchesTheProvider() {
            scanSetIs(ENROLLED_USERS, List.of("alice@acme.example", "bob@acme.example"));
            when(discoveryService.calendarClientFor(anyString(), eq(ENROLLED_USERS)))
                    .thenAnswer(call -> calendarOver(new CalendarTransport(call.getArgument(0))));

            watchService.subscribeAllUsers();
            watchService.renewExpiringChannels();

            verifyNoInteractions(googleProviderFactory);
        }

        @Test
        @DisplayName("The legacy channel scan does not read the consented list under Always On")
        void legacyScanSkipsTheConsentedList() {
            when(discoveryService.isAlwaysOn()).thenReturn(true);

            assertNull(watchService.findUserByChannelId("channel-legacy"));

            verifyNoInteractions(authenticationService);
        }
    }
}
