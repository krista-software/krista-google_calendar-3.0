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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationException;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationProvider;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.directory.Directory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WP-DL — expanding a Google Group attendee into the people it invited.
 *
 * <p>Driven against a real {@link Directory} client over a canned HTTP transport, for the same
 * reason {@code TenantUserDirectoryTest} is: the substance here is paging, query parameters and JSON
 * binding, all of which live inside the generated Google client. A mocked {@code Directory.Members}
 * would assert that the code calls the methods it calls.
 *
 * <p>The behaviours worth protecting are the ones with a customer-visible failure mode:
 * <ul>
 *   <li><strong>Nobody outside the workspace is probed.</strong> The expansion asks the customer's
 *       own directory about their own addresses; walking an external attendee's address into an
 *       Admin SDK lookup is neither necessary nor defensible.</li>
 *   <li><strong>A missing scope costs the expansion, nothing else.</strong> Every tenant that
 *       configured domain-wide delegation before WP-DL granted two scopes and this needs a third, so
 *       the un-granted state is not an edge case — it is the state of the entire installed base on
 *       upgrade day, and it must degrade to exactly the previous release's behaviour.</li>
 *   <li><strong>A group nobody can read never fails a meeting.</strong> The upsert is the product;
 *       the tag is an enhancement to it.</li>
 * </ul>
 */
@DisplayName("Google Group expansion (WP-DL)")
class GroupExpansionServiceTest {

    private static final String DOMAIN = "acme.example";
    private static final String GROUP = "engineering@acme.example";

    /** Records every request URL the client issued, so paging and filters can be asserted. */
    private static final class RecordingTransport extends MockHttpTransport {

        private final List<String> requestedUrls = new ArrayList<>();
        private final List<MockLowLevelHttpResponse> responses;
        private int served;

        private RecordingTransport(List<MockLowLevelHttpResponse> responses) {
            this.responses = responses;
        }

        @Override
        public LowLevelHttpRequest buildRequest(String method, String url) {
            requestedUrls.add(url);
            MockLowLevelHttpResponse response = responses.get(Math.min(served++, responses.size() - 1));
            return new MockLowLevelHttpRequest() {
                @Override
                public LowLevelHttpResponse execute() {
                    return response;
                }
            };
        }
    }

    /** An in-memory {@link KeyValueStore}, so the cache is exercised for real. */
    private static final class FakeKeyValueStore implements KeyValueStore {

        private final Map<String, Object> entries = new LinkedHashMap<>();

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

    private static MockLowLevelHttpResponse ok(String json) {
        return new MockLowLevelHttpResponse()
                .setStatusCode(200)
                .setContentType("application/json")
                .setContent(json);
    }

    private static MockLowLevelHttpResponse notFound() {
        return new MockLowLevelHttpResponse()
                .setStatusCode(404)
                .setContentType("application/json")
                .setContent("{\"error\":{\"code\":404,\"message\":\"Resource Not Found: groupKey\","
                        + "\"errors\":[{\"reason\":\"notFound\"}]}}");
    }

    private static MockLowLevelHttpResponse forbidden() {
        return new MockLowLevelHttpResponse()
                .setStatusCode(403)
                .setContentType("application/json")
                .setContent("{\"error\":{\"code\":403,\"message\":\"Request had insufficient "
                        + "authentication scopes.\",\"errors\":[{\"reason\":\"forbidden\"}]}}");
    }

    private static String groupBody(String email) {
        return "{\"kind\":\"admin#directory#group\",\"email\":\"" + email + "\"}";
    }

    private static String membersPage(String nextPageToken, String... entries) {
        StringBuilder json = new StringBuilder("{\"kind\":\"admin#directory#members\",\"members\":[");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(entries[i]);
        }
        json.append(']');
        if (nextPageToken != null) {
            json.append(",\"nextPageToken\":\"").append(nextPageToken).append('"');
        }
        return json.append('}').toString();
    }

    private static String member(String email) {
        return member(email, "USER", "MEMBER", "ACTIVE");
    }

    private static String member(String email, String type, String role, String status) {
        return "{\"email\":\"" + email + "\",\"type\":\"" + type + "\",\"role\":\"" + role
                + "\",\"status\":\"" + status + "\"}";
    }

    private GroupExpansionService serviceOver(RecordingTransport transport, String tenantDomain,
                                              KeyValueStore keyValueStore) throws Exception {
        Directory client = new Directory.Builder(transport, GsonFactory.getDefaultInstance(), null)
                .setApplicationName("test")
                .build();
        DomainWideDelegationProvider delegation = mock(DomainWideDelegationProvider.class);
        lenient().when(delegation.configurationGap()).thenReturn(null);
        lenient().when(delegation.getTenantDomain()).thenReturn(tenantDomain);
        lenient().when(delegation.getAdminEmail()).thenReturn("admin@" + DOMAIN);
        lenient().when(delegation.directoryClientForGroups()).thenReturn(client);
        return new GroupExpansionService(invoker(), keyValueStore, delegation);
    }

    private GroupExpansionService serviceOver(RecordingTransport transport) throws Exception {
        return serviceOver(transport, DOMAIN, new FakeKeyValueStore());
    }

    private static Invoker invoker() {
        Invoker invoker = mock(Invoker.class);
        lenient().when(invoker.getInvokerId()).thenReturn("INV_gcal");
        return invoker;
    }

    // ── the happy path ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Expanding a group")
    class HappyPath {

        @Test
        @DisplayName("Returns the group's active user members, lower-cased")
        void expandsMembers() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(
                    ok(groupBody(GROUP)),
                    ok(membersPage(null, member("Alice@Acme.Example"), member("bob@acme.example")))));

            Map<String, List<String>> expansions =
                    serviceOver(transport).expand(List.of(GROUP, "carol@acme.example"));

            assertEquals(Map.of(GROUP, List.of("alice@acme.example", "bob@acme.example")), expansions);
        }

        @Test
        @DisplayName("Asks for derived membership, so nested groups arrive already flattened")
        void requestsDerivedMembership() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(
                    ok(groupBody(GROUP)),
                    ok(membersPage(null, member("alice@acme.example")))));

            serviceOver(transport).expand(List.of(GROUP));

            String membersUrl = transport.requestedUrls.get(1);
            assertTrue(membersUrl.contains("includeDerivedMembership=true"), membersUrl);
        }

        /**
         * With derived membership on, a nested group's people are already listed individually, so the
         * {@code GROUP} entry alongside them is a duplicate. Including it would put a distribution
         * list into the attendee list dressed as a person — the exact confusion this feature removes.
         */
        @Test
        @DisplayName("A nested-group entry is skipped; its flattened members are kept")
        void skipsNestedGroupEntries() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(
                    ok(groupBody(GROUP)),
                    ok(membersPage(null,
                            member("alice@acme.example"),
                            member("platform@acme.example", "GROUP", "MEMBER", "ACTIVE"),
                            member("dave@acme.example"),
                            member("everyone@acme.example", "CUSTOMER", "MEMBER", "ACTIVE")))));

            Map<String, List<String>> expansions = serviceOver(transport).expand(List.of(GROUP));

            assertEquals(List.of("alice@acme.example", "dave@acme.example"), expansions.get(GROUP));
        }

        @Test
        @DisplayName("Suspended members are not invitees; managers and owners are")
        void filtersByStatusAndRole() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(
                    ok(groupBody(GROUP)),
                    ok(membersPage(null,
                            member("boss@acme.example", "USER", "OWNER", "ACTIVE"),
                            member("lead@acme.example", "USER", "MANAGER", "ACTIVE"),
                            member("gone@acme.example", "USER", "MEMBER", "SUSPENDED"),
                            member("alice@acme.example")))));

            assertEquals(List.of("boss@acme.example", "lead@acme.example", "alice@acme.example"),
                    serviceOver(transport).expand(List.of(GROUP)).get(GROUP));
        }

        /**
         * Google does not populate {@code status} for every membership shape. Reading "the API did
         * not say" as "suspended" would silently empty an expansion — a failure indistinguishable
         * from the feature not working at all.
         */
        @Test
        @DisplayName("A member with no status is treated as active, not as suspended")
        void absentStatusIsActive() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(
                    ok(groupBody(GROUP)),
                    ok(membersPage(null, "{\"email\":\"alice@acme.example\",\"type\":\"USER\"}"))));

            assertEquals(List.of("alice@acme.example"), serviceOver(transport).expand(List.of(GROUP)).get(GROUP));
        }

        @Test
        @DisplayName("Follows nextPageToken across member pages")
        void followsPaging() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(
                    ok(groupBody(GROUP)),
                    ok(membersPage("token-2", member("alice@acme.example"))),
                    ok(membersPage(null, member("bob@acme.example")))));

            assertEquals(List.of("alice@acme.example", "bob@acme.example"),
                    serviceOver(transport).expand(List.of(GROUP)).get(GROUP));
            assertTrue(transport.requestedUrls.get(2).contains("pageToken=token-2"),
                    transport.requestedUrls.get(2));
        }

        /**
         * A company-wide list has no business becoming an attendee list: the meeting would become
         * "visible to everyone", which is not a permission anybody granted, and the upsert payload
         * would dwarf the meeting.
         */
        @Test
        @DisplayName("An oversized group is truncated at the cap, and stops paging there")
        void capsTheExpansion() throws Exception {
            String[] page = new String[GroupExpansionService.MAX_MEMBERS + 50];
            for (int i = 0; i < page.length; i++) {
                page[i] = member("user" + i + "@acme.example");
            }
            RecordingTransport transport = new RecordingTransport(List.of(
                    ok(groupBody(GROUP)),
                    ok(membersPage("token-2", page))));

            List<String> members = serviceOver(transport).expand(List.of(GROUP)).get(GROUP);

            assertEquals(GroupExpansionService.MAX_MEMBERS, members.size());
            assertEquals(2, transport.requestedUrls.size(),
                    "the cap must stop the walk, not merely trim the result — otherwise a "
                            + "company-wide list still costs every page");
        }

        @Test
        @DisplayName("A group with no usable members expands to nothing rather than an empty tag")
        void emptyGroupYieldsNoEntry() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(
                    ok(groupBody(GROUP)),
                    ok(membersPage(null))));

            assertTrue(serviceOver(transport).expand(List.of(GROUP)).isEmpty());
        }
    }

    // ── who gets probed ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Which addresses are probed at all")
    class Scoping {

        /**
         * Two independent reasons, and the second is the one that matters: an Admin SDK round-trip
         * per external attendee per meeting is wasteful, but asking the customer's directory about
         * an outsider's address is not something this feature needs to do in order to work.
         */
        @Test
        @DisplayName("An address outside tenantDomain is never looked up")
        void outsideTheDomainIsNotProbed() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(ok(groupBody(GROUP))));

            Map<String, List<String>> expansions = serviceOver(transport)
                    .expand(List.of("partners@othercorp.example", "someone@gmail.example"));

            assertTrue(expansions.isEmpty());
            assertTrue(transport.requestedUrls.isEmpty(),
                    "not one request may leave for an address outside the workspace's own domain");
        }

        @Test
        @DisplayName("tenantDomain wins when it is set")
        void tenantDomainIsHonoured() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(ok(groupBody(GROUP))));
            GroupExpansionService service =
                    serviceOver(transport, "subsidiary.example", new FakeKeyValueStore());

            service.expand(List.of(GROUP));

            assertTrue(transport.requestedUrls.isEmpty(),
                    "engineering@acme.example is outside the configured tenantDomain");
            assertEquals("subsidiary.example", service.expansionDomain());
        }

        /**
         * {@code tenantDomain} is optional. The admin address is not — domain-wide delegation cannot
         * be configured without it — and it names exactly the account the delegation was granted
         * over, so it is the right default rather than a guess.
         */
        @Test
        @DisplayName("With tenantDomain blank, the admin's own domain stands in")
        void adminDomainIsTheDefault() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(
                    ok(groupBody(GROUP)),
                    ok(membersPage(null, member("alice@acme.example")))));
            GroupExpansionService service = serviceOver(transport, "", new FakeKeyValueStore());

            assertEquals(DOMAIN, service.expansionDomain());
            assertEquals(List.of("alice@acme.example"), service.expand(List.of(GROUP)).get(GROUP));
        }

        @Test
        @DisplayName("An empty attendee list asks Google nothing")
        void noAttendeesNoCalls() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(ok(groupBody(GROUP))));

            assertTrue(serviceOver(transport).expand(List.of()).isEmpty());
            assertTrue(serviceOver(transport).expand(null).isEmpty());
            assertTrue(transport.requestedUrls.isEmpty());
        }
    }

    // ── failure ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("When the group cannot be read")
    class Failure {

        /**
         * The overwhelmingly common case: an attendee is a person. It must be cheap, cached, and not
         * WARNed — a log line per human attendee per meeting would bury everything else.
         */
        @Test
        @DisplayName("An address that is not a group is skipped, and not asked about again")
        void notAGroupIsSkippedAndCached() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(notFound()));
            GroupExpansionService service = serviceOver(transport);

            assertTrue(service.expand(List.of("alice@acme.example")).isEmpty());
            assertTrue(service.expand(List.of("alice@acme.example")).isEmpty());

            assertEquals(1, transport.requestedUrls.size(),
                    "a person is not a group on every sweep — the negative answer is cached too");
        }

        /**
         * Upgrade day for the entire installed base: two scopes granted, three needed. Everything
         * else must keep working, and the meeting must still be sent.
         */
        @Test
        @DisplayName("A refused scope skips expansion and stops asking")
        void missingScopeSkipsExpansion() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(forbidden()));
            GroupExpansionService service = serviceOver(transport);

            assertTrue(service.expand(List.of(GROUP)).isEmpty());
            assertTrue(service.expand(List.of(GROUP)).isEmpty());
            assertTrue(service.expand(List.of("another@acme.example")).isEmpty());

            assertEquals(1, transport.requestedUrls.size(),
                    "once Google has refused the credential, every further lookup this window would "
                            + "be refused identically — and would log identically");
        }

        /**
         * The refusal is about the credential, not about the address. Recording it as "this is not a
         * group" would outlive the re-grant: the super-admin would add the scope, and the extension
         * would keep skipping the very lists it had asked for the scope in order to expand.
         */
        @Test
        @DisplayName("A refusal is not cached as 'not a group', so a re-grant takes effect")
        void scopeRefusalIsNotANegativeAnswer() throws Exception {
            FakeKeyValueStore store = new FakeKeyValueStore();
            serviceOver(new RecordingTransport(List.of(forbidden())), DOMAIN, store)
                    .expand(List.of(GROUP));

            assertTrue(store.getKeys().isEmpty(),
                    "a credential refusal must leave no cached answer about the address behind");

            RecordingTransport afterRegrant = new RecordingTransport(List.of(
                    ok(groupBody(GROUP)),
                    ok(membersPage(null, member("alice@acme.example")))));

            assertEquals(List.of("alice@acme.example"),
                    serviceOver(afterRegrant, DOMAIN, store).expand(List.of(GROUP)).get(GROUP));
        }

        @Test
        @DisplayName("An unbuildable credential skips expansion rather than failing the meeting")
        void unbuildableCredentialIsSkipped() throws Exception {
            DomainWideDelegationProvider delegation = mock(DomainWideDelegationProvider.class);
            when(delegation.configurationGap()).thenReturn(null);
            when(delegation.getTenantDomain()).thenReturn(DOMAIN);
            when(delegation.directoryClientForGroups())
                    .thenThrow(new DomainWideDelegationException("key unreadable", null));

            GroupExpansionService service =
                    new GroupExpansionService(invoker(), new FakeKeyValueStore(), delegation);

            assertTrue(service.expand(List.of(GROUP)).isEmpty());
        }

        /**
         * An Opt-In tenant has no admin credential and no way to get one — there is no per-user OAuth
         * grant that can answer a group-membership question. Not a fault, and not a reason to touch
         * anything.
         */
        @Test
        @DisplayName("Without domain-wide delegation there is no expansion and no call")
        void noDelegationNoExpansion() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(ok(groupBody(GROUP))));
            DomainWideDelegationProvider delegation = mock(DomainWideDelegationProvider.class);
            when(delegation.configurationGap())
                    .thenReturn("the 'serviceAccountKey' service-account key is not configured");

            GroupExpansionService service =
                    new GroupExpansionService(invoker(), new FakeKeyValueStore(), delegation);

            assertTrue(service.expand(List.of(GROUP)).isEmpty());
            assertTrue(transport.requestedUrls.isEmpty());
        }
    }

    // ── caching ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Caching")
    class Caching {

        @Test
        @DisplayName("A second event with the same group costs no API call")
        void cacheHitAvoidsTheApi() throws Exception {
            RecordingTransport transport = new RecordingTransport(List.of(
                    ok(groupBody(GROUP)),
                    ok(membersPage(null, member("alice@acme.example")))));
            GroupExpansionService service = serviceOver(transport);

            Map<String, List<String>> first = service.expand(List.of(GROUP));
            Map<String, List<String>> second = service.expand(List.of(GROUP));

            assertEquals(first, second);
            assertEquals(2, transport.requestedUrls.size(),
                    "one groups.get and one members.list in total — a sweep of 200 meetings on the "
                            + "same list must not be 400 Admin SDK calls");
        }

        /**
         * A restarted container in the middle of a sweep would otherwise re-resolve every group it
         * had already resolved, which is the moment the Admin SDK quota is least welcome.
         */
        @Test
        @DisplayName("A cold process reuses the expansion a previous one cached")
        void coldProcessReusesTheStore() throws Exception {
            FakeKeyValueStore store = new FakeKeyValueStore();
            RecordingTransport first = new RecordingTransport(List.of(
                    ok(groupBody(GROUP)),
                    ok(membersPage(null, member("alice@acme.example")))));
            serviceOver(first, DOMAIN, store).expand(List.of(GROUP));

            RecordingTransport afterRestart = new RecordingTransport(List.of(ok(groupBody(GROUP))));

            assertEquals(List.of("alice@acme.example"),
                    serviceOver(afterRestart, DOMAIN, store).expand(List.of(GROUP)).get(GROUP));
            assertTrue(afterRestart.requestedUrls.isEmpty(), "a warm cache issues no request");
        }

        @Test
        @DisplayName("A corrupt cache entry is re-resolved rather than trusted")
        void corruptEntryIsRefetched() throws Exception {
            FakeKeyValueStore store = new FakeKeyValueStore();
            store.put("gcal_group_members:INV_gcal:" + GROUP, "not json");
            RecordingTransport transport = new RecordingTransport(List.of(
                    ok(groupBody(GROUP)),
                    ok(membersPage(null, member("alice@acme.example")))));

            assertEquals(List.of("alice@acme.example"),
                    serviceOver(transport, DOMAIN, store).expand(List.of(GROUP)).get(GROUP));
        }

        @Test
        @DisplayName("A stale cache entry is re-resolved")
        void staleEntryIsRefetched() throws Exception {
            FakeKeyValueStore store = new FakeKeyValueStore();
            long stale = System.currentTimeMillis() - GroupExpansionService.CACHE_TTL_MILLIS - 1_000;
            store.put("gcal_group_members:INV_gcal:" + GROUP,
                    "{\"group\":true,\"members\":[\"old@acme.example\"],\"resolvedAt\":" + stale + "}");
            RecordingTransport transport = new RecordingTransport(List.of(
                    ok(groupBody(GROUP)),
                    ok(membersPage(null, member("alice@acme.example")))));

            assertEquals(List.of("alice@acme.example"),
                    serviceOver(transport, DOMAIN, store).expand(List.of(GROUP)).get(GROUP));
        }

        @Test
        @DisplayName("The cache window matches the seat roster's, so 'how stale' has one answer")
        void ttlMatchesTheSeatRoster() {
            assertEquals(RegisteredSeatStore.REFRESH_INTERVAL_MILLIS,
                    GroupExpansionService.CACHE_TTL_MILLIS);
        }
    }

    // ── HK2 wiring ────────────────────────────────────────────────────────

    @Test
    @DisplayName("The service is an @Service with a single @Inject constructor HK2 can satisfy")
    void isInjectable() {
        assertTrue(GroupExpansionService.class.isAnnotationPresent(org.jvnet.hk2.annotations.Service.class));
        java.lang.reflect.Constructor<?> injectConstructor = null;
        for (java.lang.reflect.Constructor<?> constructor
                : GroupExpansionService.class.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(javax.inject.Inject.class)) {
                injectConstructor = constructor;
            }
        }
        assertFalse(injectConstructor == null, "GroupExpansionService must declare an @Inject constructor");
        for (java.lang.reflect.Parameter parameter : injectConstructor.getParameters()) {
            Class<?> type = parameter.getType();
            assertTrue(type.isInterface()
                            || type.isAnnotationPresent(org.jvnet.hk2.annotations.Service.class),
                    type.getSimpleName() + " is neither a platform-provided interface nor an "
                            + "@Service, so HK2 cannot supply it");
        }
    }
}
