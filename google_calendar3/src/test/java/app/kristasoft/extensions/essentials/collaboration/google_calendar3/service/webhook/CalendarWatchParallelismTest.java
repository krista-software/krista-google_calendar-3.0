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
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.calendar.Calendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS;
import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.TENANT_DIRECTORY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KIM-474 (scale): tenant-wide watch-channel work fans out; consented-user work does not.
 *
 * <p>Registering a watch channel is three Google round-trips — {@code calendars.get},
 * {@code events.list} for the baseline sync token, {@code events.watch}. One at a time that is fine
 * for the handful of users who authorised their own calendar and unusable for a Workspace account of
 * thousands: startup becomes hours, and because channels expire after five days, a renewal pass that
 * cannot get round the account in time silently stops push notifications for whoever was at the back
 * of the queue. So the tenant path fans out — <em>bounded</em>, because an unbounded burst against
 * the Calendar API earns the quota refusal this change exists to avoid, not to cause.
 *
 * <p>Concurrency is observed at the transport, by counting how many Google requests are genuinely in
 * flight at once — not by counting threads or trusting a constant.
 */
@DisplayName("Watch-channel sweeps fan out on the tenant path only (KIM-474)")
class CalendarWatchParallelismTest {

    private static final String NOTIFICATION_BASE_URL = "https://extensions.example";

    /** Matches {@code CalendarWatchService.MAX_CONCURRENT_USERS}. */
    private static final int EXPECTED_CAP = 5;

    /** Minimal in-memory KeyValueStore — the watch store's real logic is worth exercising. */
    private static final class InMemoryKeyValueStore implements KeyValueStore {

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
            return values.keySet();
        }

        @Override
        public Iterator<Map.Entry<String, Object>> iterator() {
            return values.entrySet().iterator();
        }
    }

    /**
     * Records how many Google requests overlap, and on how many distinct threads. Each request holds
     * its slot briefly so overlap is observable rather than theoretical.
     */
    private static final class ConcurrencyProbe {

        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final AtomicInteger watchCalls = new AtomicInteger();
        // Thread IDs, not names: an unnamed virtual thread reports "" for its name, so a set of
        // names would collapse the whole fan-out into one entry and quietly assert nothing.
        private final Set<Long> threads = ConcurrentHashMap.newKeySet();

        private void enter(String url) {
            threads.add(Thread.currentThread().threadId());
            if (url.contains("/watch")) {
                watchCalls.incrementAndGet();
            }
            int current = inFlight.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
        }

        private void exit() {
            inFlight.decrementAndGet();
        }
    }

    /** Answers the three calls a watch registration makes, while feeding the probe. */
    private static final class ProbedTransport extends MockHttpTransport {

        private final ConcurrencyProbe probe;
        private final String primaryCalendarId;

        private ProbedTransport(ConcurrencyProbe probe, String primaryCalendarId) {
            this.probe = probe;
            this.primaryCalendarId = primaryCalendarId;
        }

        @Override
        public LowLevelHttpRequest buildRequest(String method, String url) {
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
                    probe.enter(url);
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        probe.exit();
                    }
                    return new MockLowLevelHttpResponse()
                            .setStatusCode(200)
                            .setContentType("application/json")
                            .setContent(body);
                }
            };
        }
    }

    private ConcurrencyProbe probe;
    private GoogleProviderFactory googleProviderFactory;
    private GoogleProvider googleProvider;
    private CalendarDiscoveryService discoveryService;
    private CalendarWatchStore watchStore;
    private CalendarWatchService watchService;

    @BeforeEach
    void setUp() {
        probe = new ConcurrencyProbe();
        googleProviderFactory = mock(GoogleProviderFactory.class);
        googleProvider = mock(GoogleProvider.class);
        discoveryService = mock(CalendarDiscoveryService.class);
        watchStore = new CalendarWatchStore(new InMemoryKeyValueStore());

        Invoker invoker = mock(Invoker.class);
        RoutingInfo routingInfo = mock(RoutingInfo.class);
        lenient().when(routingInfo.getRoutingURL(anyString(), any())).thenReturn(NOTIFICATION_BASE_URL);
        lenient().when(invoker.getRoutingInfo()).thenReturn(routingInfo);
        lenient().when(invoker.getAttributes()).thenReturn(Map.of(
                app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants.SERVICE_URL,
                "https://conversation-agent.example"));
        lenient().when(invoker.getInvokerId()).thenReturn("INV_google_calendar");
        lenient().when(googleProviderFactory.create()).thenReturn(googleProvider);

        watchService = new CalendarWatchService(googleProviderFactory,
                mock(GoogleCalendarAuthenticationService.class), watchStore, invoker,
                mock(UserKeyIndexStore.class), discoveryService);
    }

    private Calendar calendarFor(String userId) {
        return new Calendar.Builder(new ProbedTransport(probe, userId), GsonFactory.getDefaultInstance(), null)
                .setApplicationName("test")
                .build();
    }

    /** Wires a scan set of the requested size and a probed Calendar client per user. */
    private List<String> scanSetOf(CalendarDiscoveryService.DiscoverySource source, int population) {
        List<String> users = IntStream.range(0, population)
                .mapToObj(i -> "user" + i + "@acme.example")
                .toList();
        Set<String> tenantMembers = source == TENANT_DIRECTORY ? Set.copyOf(users) : Set.of();
        lenient().when(discoveryService.discover(anyString())).thenReturn(
                new CalendarDiscoveryService.UserSet(source, users, tenantMembers, null));

        Map<String, Calendar> clients = new HashMap<>();
        for (String user : users) {
            clients.put(user, calendarFor(user));
        }
        if (source == TENANT_DIRECTORY) {
            for (String user : users) {
                lenient().when(discoveryService.calendarClientFor(user, TENANT_DIRECTORY))
                        .thenReturn(clients.get(user));
            }
        } else {
            for (String user : users) {
                lenient().when(googleProvider.getGoogleClientForUser(user)).thenReturn(clients.get(user));
            }
        }
        return users;
    }

    // ── tenant-wide ───────────────────────────────────────────────────────

    @Test
    @Timeout(60)
    @DisplayName("Tenant-wide registration overlaps, and never above the cap")
    void tenantRegistrationIsBoundedAndParallel() {
        List<String> users = scanSetOf(TENANT_DIRECTORY, 20);

        watchService.subscribeAllUsers();

        for (String user : users) {
            assertNotNull(watchStore.getChannelId(user), "every mailbox must still get a channel: " + user);
        }
        assertEquals(users.size(), probe.watchCalls.get(), "one events.watch per mailbox, no more");
        assertTrue(probe.peak.get() > 1,
                "registration must actually overlap — sequentially this sweep does not finish in time "
                        + "on a real Workspace account. Observed peak: " + probe.peak.get());
        assertTrue(probe.peak.get() <= EXPECTED_CAP,
                "the fan-out must stay inside the existing concurrency cap of " + EXPECTED_CAP
                        + " — an unbounded burst is what earns a quota refusal. Observed peak: "
                        + probe.peak.get());
        assertTrue(probe.threads.size() > 1, "the sweep must not all run on the calling thread");
    }

    @Test
    @Timeout(60)
    @DisplayName("Tenant-wide renewal overlaps too, on the same cap")
    void tenantRenewalIsBoundedAndParallel() {
        List<String> users = scanSetOf(TENANT_DIRECTORY, 15);
        // Every channel already stored and expiring within the day, so all of them renew.
        for (String user : users) {
            watchStore.put(user, "old-" + user, "resource", System.currentTimeMillis() + 3_600_000L);
        }

        watchService.renewExpiringChannels();

        for (String user : users) {
            assertEquals("channel-" + user, watchStore.getChannelId(user),
                    "without renewal the tenant's push notifications stop ~5 days after Always On "
                            + "is enabled, with nothing to connect the outage to the change");
        }
        assertTrue(probe.peak.get() > 1, "renewal must overlap — observed peak " + probe.peak.get());
        assertTrue(probe.peak.get() <= EXPECTED_CAP,
                "renewal must stay inside the cap — observed peak " + probe.peak.get());
    }

    @Test
    @Timeout(60)
    @DisplayName("One mailbox refusing still does not abort a parallel sweep")
    void oneFailureDoesNotAbortTheParallelSweep() {
        List<String> users = scanSetOf(TENANT_DIRECTORY, 8);
        String doomed = users.get(3);
        lenient().when(discoveryService.calendarClientFor(doomed, TENANT_DIRECTORY))
                .thenThrow(new IllegalStateException("403 Not Authorized"));

        watchService.subscribeAllUsers();

        for (String user : users) {
            if (user.equals(doomed)) {
                continue;
            }
            assertNotNull(watchStore.getChannelId(user),
                    "a single unauthorised mailbox must cost that mailbox, not the whole account");
        }
    }

    // ── Always On OFF ─────────────────────────────────────────────────────

    /**
     * The constraint that matters more than the speed-up. Every tenant that has not enabled Always
     * On runs this path at load; it must behave exactly as it did before — one user at a time, on
     * the calling thread.
     */
    @Test
    @Timeout(60)
    @DisplayName("With Always On off, registration stays strictly sequential on the calling thread")
    void consentedPathStaysSequential() {
        List<String> users = scanSetOf(CONSENTED_USERS, 4);
        long callingThread = Thread.currentThread().threadId();

        watchService.subscribeAllUsers();

        for (String user : users) {
            assertNotNull(watchStore.getChannelId(user));
        }
        assertEquals(1, probe.peak.get(),
                "the consented path must issue one Google call at a time, exactly as it always has");
        assertEquals(Set.of(callingThread), probe.threads,
                "the consented path must not spawn threads — its shape is unchanged by this work");
    }
}
