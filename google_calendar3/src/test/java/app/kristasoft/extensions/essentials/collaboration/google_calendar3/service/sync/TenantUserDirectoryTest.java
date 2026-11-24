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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KIM-474 — tenant enumeration through the Admin SDK Directory API.
 *
 * <p>Driven against a real {@link Directory} client over a canned HTTP transport rather than a
 * mocked client. Paging is the whole substance of this class, and it lives inside the generated
 * Google client — {@code nextPageToken} handling, query-parameter placement, JSON binding. A mocked
 * {@code Directory.Users.List} would assert that the code calls the methods it calls, which proves
 * nothing; this asserts that the request Google would actually receive is right and that the pages
 * Google would actually return are stitched correctly.
 */
@DisplayName("Tenant user enumeration (KIM-474)")
class TenantUserDirectoryTest {

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
                public LowLevelHttpResponse execute() throws IOException {
                    if (response.getStatusCode() >= 400 && response.getContent() == null) {
                        throw new IOException("simulated transport failure");
                    }
                    return response;
                }
            };
        }
    }

    private static MockLowLevelHttpResponse page(String json) {
        return new MockLowLevelHttpResponse()
                .setStatusCode(200)
                .setContentType("application/json")
                .setContent(json);
    }

    private static MockLowLevelHttpResponse forbidden() {
        return new MockLowLevelHttpResponse()
                .setStatusCode(403)
                .setContentType("application/json")
                .setContent("{\"error\":{\"code\":403,\"message\":\"Not Authorized to access this "
                        + "resource/api\",\"errors\":[{\"reason\":\"forbidden\"}]}}");
    }

    private static String usersPage(String nextPageToken, String... entries) {
        StringBuilder json = new StringBuilder("{\"kind\":\"admin#directory#users\",\"users\":[");
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

    private static String user(String email) {
        return "{\"primaryEmail\":\"" + email + "\",\"suspended\":false,\"archived\":false}";
    }

    private static String user(String email, boolean suspended, boolean archived) {
        return "{\"primaryEmail\":\"" + email + "\",\"suspended\":" + suspended
                + ",\"archived\":" + archived + "}";
    }

    private static TenantUserDirectory directoryOver(RecordingTransport transport, String tenantDomain)
            throws Exception {
        Directory client = new Directory.Builder(transport, GsonFactory.getDefaultInstance(), null)
                .setApplicationName("test")
                .build();
        DomainWideDelegationProvider delegation = mock(DomainWideDelegationProvider.class);
        when(delegation.directoryClientForAdmin()).thenReturn(client);
        when(delegation.getTenantDomain()).thenReturn(tenantDomain);
        return new TenantUserDirectory(delegation);
    }

    // ── paging ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Follows nextPageToken until the tenant is exhausted")
    void followsPaging() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(
                page(usersPage("token-2", user("alice@acme.example"), user("bob@acme.example"))),
                page(usersPage("token-3", user("carol@acme.example"))),
                page(usersPage(null, user("dave@acme.example")))));

        List<String> emails = directoryOver(transport, null).listActiveUserEmails();

        assertEquals(List.of("alice@acme.example", "bob@acme.example",
                "carol@acme.example", "dave@acme.example"), emails);
        assertEquals(3, transport.requestedUrls.size(),
                "One request per page — stopping after the first would silently scan a fraction "
                        + "of a large tenant");
        assertTrue(transport.requestedUrls.get(1).contains("pageToken=token-2"),
                transport.requestedUrls.get(1));
        assertTrue(transport.requestedUrls.get(2).contains("pageToken=token-3"),
                transport.requestedUrls.get(2));
    }

    @Test
    @DisplayName("A single page with no token is one request, not two")
    void singlePage() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(
                page(usersPage(null, user("alice@acme.example")))));

        assertEquals(List.of("alice@acme.example"), directoryOver(transport, null).listActiveUserEmails());
        assertEquals(1, transport.requestedUrls.size());
    }

    @Test
    @DisplayName("A blank nextPageToken terminates rather than looping forever")
    void blankTokenTerminates() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(
                page(usersPage("", user("alice@acme.example")))));

        assertEquals(1, directoryOver(transport, null).listActiveUserEmails().size());
        assertEquals(1, transport.requestedUrls.size());
    }

    @Test
    @DisplayName("An empty account returns an empty list without failing")
    void emptyAccount() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(page("{\"kind\":\"admin#directory#users\"}")));

        assertTrue(directoryOver(transport, null).listActiveUserEmails().isEmpty());
    }

    // ── request shape ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Lists the whole account by default, and filters suspended users server-side")
    void defaultsToWholeAccount() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(page(usersPage(null))));

        directoryOver(transport, null).listActiveUserEmails();

        String url = transport.requestedUrls.get(0);
        assertTrue(url.contains("customer=my_customer"), url);
        assertFalse(url.contains("domain="), "customer and domain are mutually exclusive: " + url);
        assertTrue(url.contains("isSuspended%3Dfalse") || url.contains("isSuspended=false"), url);
        assertTrue(url.contains("maxResults=500"), url);
    }

    /**
     * A multi-domain Workspace account may only license part of itself for Meeting Agent. Restricting
     * enumeration is how that tenant avoids scanning calendars it never intended to include.
     */
    @Test
    @DisplayName("A configured tenant domain restricts the scan to that domain only")
    void restrictsToConfiguredDomain() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(page(usersPage(null))));

        directoryOver(transport, "acme.example").listActiveUserEmails();

        String url = transport.requestedUrls.get(0);
        assertTrue(url.contains("domain=acme.example"), url);
        assertFalse(url.contains("customer="), "customer and domain are mutually exclusive: " + url);
    }

    // ── per-entry filtering ───────────────────────────────────────────────

    @Test
    @DisplayName("Suspended and archived mailboxes are skipped, not scanned")
    void skipsInactiveMailboxes() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(page(usersPage(null,
                user("active@acme.example"),
                user("suspended@acme.example", true, false),
                user("archived@acme.example", false, true),
                "{\"suspended\":false}"))));

        assertEquals(List.of("active@acme.example"), directoryOver(transport, null).listActiveUserEmails());
    }

    @Test
    @DisplayName("Addresses are normalised and de-duplicated across pages")
    void deduplicatesAndNormalises() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(
                page(usersPage("token-2", user("Alice@Acme.Example"), user(" bob@acme.example "))),
                page(usersPage(null, user("alice@acme.example")))));

        assertEquals(List.of("alice@acme.example", "bob@acme.example"),
                directoryOver(transport, null).listActiveUserEmails());
    }

    // ── failure handling ──────────────────────────────────────────────────

    /**
     * The first page failing means delegation is not working at all — the caller must hear about it
     * so it can degrade to the consented set and log the admin remediation.
     */
    @Test
    @DisplayName("A refusal on the first page is thrown, so the caller can degrade")
    void firstPageFailureThrows() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(forbidden()));

        TenantUserDirectory directory = directoryOver(transport, null);

        DomainWideDelegationException failure =
                assertThrows(DomainWideDelegationException.class, directory::listActiveUserEmails);
        assertTrue(DomainWideDelegationProvider.isDelegationDenied(failure),
                "A 403 from users.list is an unauthorised delegation, and must be reported as one");
    }

    /**
     * A later page failing is different: we already hold a usable slice of the tenant. Scanning it
     * covers more calendars than falling back to the consented set would, and the next sweep starts
     * over — so the partial result is returned, loudly.
     */
    @Test
    @DisplayName("A failure part-way through returns the partial set rather than nothing")
    void laterPageFailureReturnsPartial() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(
                page(usersPage("token-2", user("alice@acme.example"), user("bob@acme.example"))),
                forbidden()));

        List<String> emails = directoryOver(transport, null).listActiveUserEmails();

        assertEquals(List.of("alice@acme.example", "bob@acme.example"), emails);
    }

    /**
     * The flag is what stops a seat beyond the broken page being reported to an admin as missing
     * from Google when all that happened is the listing stopped.
     */
    @Test
    @DisplayName("A listing that stopped early says so; one that reached the end says that")
    void listingReportsCompleteness() throws Exception {
        assertFalse(directoryOver(new RecordingTransport(List.of(
                page(usersPage("token-2", user("alice@acme.example"))),
                forbidden())), null).listActiveUsers().complete(),
                "a later-page failure leaves mailboxes unread");

        TenantUserDirectory.Listing whole = directoryOver(new RecordingTransport(List.of(
                page(usersPage("token-2", user("alice@acme.example"))),
                page(usersPage(null, user("bob@acme.example"))))), null).listActiveUsers();

        assertTrue(whole.complete());
        assertEquals(List.of("alice@acme.example", "bob@acme.example"), whole.emails());
    }

    @Test
    @DisplayName("A credential that cannot be built propagates as an admin-actionable failure")
    void credentialFailurePropagates() throws Exception {
        DomainWideDelegationProvider delegation = mock(DomainWideDelegationProvider.class);
        when(delegation.directoryClientForAdmin())
                .thenThrow(new DomainWideDelegationException("no key configured", null));

        TenantUserDirectory directory = new TenantUserDirectory(delegation);

        assertEquals("no key configured",
                assertThrows(DomainWideDelegationException.class, directory::listActiveUserEmails).getMessage());
    }

    /**
     * The service-account key travels through the same client that produces these failures, so the
     * enumeration path has to sanitise too — not just the credential builder.
     */
    @Test
    @DisplayName("An enumeration failure carries no key material into the log")
    void failureLeaksNoSecrets() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(
                new MockLowLevelHttpResponse().setStatusCode(403).setContentType("application/json")
                        .setContent("{\"error\":{\"code\":403,\"message\":\"token "
                                + "ya29." + "B".repeat(120) + " rejected\"}}")));

        TenantUserDirectory directory = directoryOver(transport, null);
        DomainWideDelegationException failure =
                assertThrows(DomainWideDelegationException.class, directory::listActiveUserEmails);

        java.io.StringWriter writer = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(writer));
        assertFalse(writer.toString().contains("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"), writer.toString());
    }

    @Test
    @DisplayName("The enumerated set is immutable — callers hand it straight to a sweep")
    void resultIsImmutable() throws Exception {
        RecordingTransport transport = new RecordingTransport(List.of(
                page(usersPage(null, user("alice@acme.example")))));

        List<String> emails = directoryOver(transport, null).listActiveUserEmails();

        assertThrows(UnsupportedOperationException.class, () -> emails.add("mallory@acme.example"));
    }
}
