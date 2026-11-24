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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors;

import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KIM-474 — the domain-wide-delegation credential path.
 *
 * <p>This is the credential that makes tenant-wide capture possible on Google at all: an OAuth
 * client id and secret can only ever act for a user who personally consented, so reading every
 * mailbox needs a service account whose client id a Workspace super-admin has authorised. These
 * tests pin three things that are otherwise only observable in a customer's tenant:
 * <ol>
 *   <li>the credential is actually <em>delegated</em> — scoped and impersonating a named user;</li>
 *   <li>an incomplete or unauthorised configuration produces an admin-actionable failure rather
 *       than a stack trace nobody can act on;</li>
 *   <li>the service-account private key never appears in a message, at any level, on any path.</li>
 * </ol>
 *
 * <p>The credential is built entirely offline — no token is fetched at construction — which is what
 * makes point 1 testable without a live Google tenant.
 */
@DisplayName("Domain-wide delegation (KIM-474)")
class DomainWideDelegationProviderTest {

    private static final String ADMIN = "admin@acme.example";
    private static final String USER = "someone@acme.example";

    /** A syntactically real service-account key: the private key is a genuine, throwaway RSA key. */
    private static String serviceAccountJson;
    private static String privateKeyPem;

    @BeforeAll
    static void generateThrowawayKey() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(keyPair.getPrivate().getEncoded());
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
        serviceAccountJson = "{"
                + "\"type\":\"service_account\","
                + "\"project_id\":\"meeting-agent-test\","
                + "\"private_key_id\":\"0123456789abcdef0123456789abcdef01234567\","
                + "\"private_key\":\"" + privateKeyPem.replace("\n", "\\n") + "\","
                + "\"client_email\":\"meeting-agent@meeting-agent-test.iam.gserviceaccount.com\","
                + "\"client_id\":\"109876543210987654321\","
                + "\"token_uri\":\"https://oauth2.googleapis.com/token\""
                + "}";
    }

    private static DomainWideDelegationProvider providerWith(Map<String, Object> attributes) {
        Invoker invoker = mock(Invoker.class);
        when(invoker.getAttributes()).thenReturn(attributes);
        return new DomainWideDelegationProvider(invoker);
    }

    private static Map<String, Object> fullyConfigured() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(Constants.SERVICE_ACCOUNT_KEY, serviceAccountJson);
        attributes.put(Constants.TENANT_ADMIN_EMAIL, ADMIN);
        return attributes;
    }

    /** Renders an exception exactly as a logger with a stack trace would. */
    private static String fullyRendered(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    // ── configuration gating ──────────────────────────────────────────────

    @Nested
    @DisplayName("Configuration gating")
    class ConfigurationGating {

        @Test
        @DisplayName("A complete configuration reports no gap")
        void completeConfiguration() {
            DomainWideDelegationProvider provider = providerWith(fullyConfigured());

            assertTrue(provider.isConfigured());
            assertNull(provider.configurationGap());
            assertEquals(ADMIN, provider.getAdminEmail());
        }

        @Test
        @DisplayName("Each missing piece is named individually, so the admin knows what to add")
        void namesTheMissingPiece() {
            Map<String, Object> keyOnly = new HashMap<>();
            keyOnly.put(Constants.SERVICE_ACCOUNT_KEY, serviceAccountJson);
            assertTrue(providerWith(keyOnly).configurationGap().contains(Constants.TENANT_ADMIN_EMAIL));

            Map<String, Object> adminOnly = new HashMap<>();
            adminOnly.put(Constants.TENANT_ADMIN_EMAIL, ADMIN);
            assertTrue(providerWith(adminOnly).configurationGap().contains(Constants.SERVICE_ACCOUNT_KEY));

            String bothMissing = providerWith(new HashMap<>()).configurationGap();
            assertTrue(bothMissing.contains(Constants.SERVICE_ACCOUNT_KEY), bothMissing);
            assertTrue(bothMissing.contains(Constants.TENANT_ADMIN_EMAIL), bothMissing);
        }

        /**
         * An unset Studio text field reaches the attribute map in several shapes depending on how it
         * was saved and round-tripped. Every one of them has to mean "not configured", because the
         * alternative is attempting delegation with an empty key on every sweep.
         */
        @Test
        @DisplayName("Every spelling of 'unset' reads as unconfigured")
        void blankSpellings() {
            for (Object blank : List.of("", "   ", "null", "NULL")) {
                Map<String, Object> attributes = fullyConfigured();
                attributes.put(Constants.SERVICE_ACCOUNT_KEY, blank);
                assertFalse(providerWith(attributes).isConfigured(),
                        "Expected '" + blank + "' to read as unconfigured");
            }
            Map<String, Object> nullValue = fullyConfigured();
            nullValue.put(Constants.SERVICE_ACCOUNT_KEY, null);
            assertFalse(providerWith(nullValue).isConfigured());
        }

        @Test
        @DisplayName("An invoker removed mid-sweep reads as unconfigured rather than throwing")
        void removedInvokerDegrades() {
            Invoker removed = mock(Invoker.class);
            when(removed.getAttributes()).thenThrow(new IllegalStateException("invoker was removed"));

            assertFalse(new DomainWideDelegationProvider(removed).isConfigured());
        }

        @Test
        @DisplayName("The tenant domain is optional and defaults to the whole account")
        void tenantDomainIsOptional() {
            assertNull(providerWith(fullyConfigured()).getTenantDomain());

            Map<String, Object> withDomain = fullyConfigured();
            withDomain.put(Constants.TENANT_DOMAIN, "acme.example");
            assertEquals("acme.example", providerWith(withDomain).getTenantDomain());
        }
    }

    // ── the delegated credential ──────────────────────────────────────────

    @Nested
    @DisplayName("Delegated credential construction")
    class DelegatedCredential {

        @Test
        @DisplayName("Builds a credential that impersonates the named user with the named scopes")
        void impersonatesTheUser() throws Exception {
            GoogleCredentials credentials = providerWith(fullyConfigured())
                    .delegatedCredentials(USER, List.of(DomainWideDelegationProvider.CALENDAR_SCOPE));

            assertTrue(credentials instanceof ServiceAccountCredentials,
                    "Delegation is only possible on a service-account credential");
            ServiceAccountCredentials serviceAccount = (ServiceAccountCredentials) credentials;

            assertEquals(USER, serviceAccount.getServiceAccountUser(),
                    "Without the impersonated subject the token is minted for the service account "
                            + "itself, which owns no calendar and sees nothing");
            assertTrue(serviceAccount.getScopes().contains(DomainWideDelegationProvider.CALENDAR_SCOPE),
                    "Scopes must survive createDelegated — they are what the super-admin authorised");
        }

        @Test
        @DisplayName("The Directory credential impersonates the admin, not the scanned user")
        void directoryImpersonatesTheAdmin() throws Exception {
            ServiceAccountCredentials credentials = (ServiceAccountCredentials) providerWith(fullyConfigured())
                    .delegatedCredentials(ADMIN, List.of(DomainWideDelegationProvider.DIRECTORY_SCOPE));

            assertEquals(ADMIN, credentials.getServiceAccountUser());
            assertTrue(credentials.getScopes().contains(DomainWideDelegationProvider.DIRECTORY_SCOPE));
        }

        @Test
        @DisplayName("Requesting the Calendar scope alone does not smuggle in Directory access")
        void scopesAreLeastPrivilege() throws Exception {
            ServiceAccountCredentials credentials = (ServiceAccountCredentials) providerWith(fullyConfigured())
                    .delegatedCredentials(USER, List.of(DomainWideDelegationProvider.CALENDAR_SCOPE));

            assertFalse(credentials.getScopes().contains(DomainWideDelegationProvider.DIRECTORY_SCOPE));
            assertTrue(DomainWideDelegationProvider.CALENDAR_SCOPE.endsWith("calendar.readonly"),
                    "The scan path only reads; asking a customer's super-admin for write access to "
                            + "every calendar in the company would be indefensible");
        }

        @Test
        @DisplayName("A missing key fails with the configuration gap, not a NullPointerException")
        void missingKeyIsActionable() {
            DomainWideDelegationException failure = assertThrows(DomainWideDelegationException.class,
                    () -> providerWith(new HashMap<>())
                            .delegatedCredentials(USER, List.of(DomainWideDelegationProvider.CALENDAR_SCOPE)));

            assertTrue(failure.getMessage().contains(Constants.SERVICE_ACCOUNT_KEY), failure.getMessage());
        }

        @Test
        @DisplayName("A blank impersonated user is refused before the key is even read")
        void blankUserIsRefused() {
            assertThrows(DomainWideDelegationException.class,
                    () -> providerWith(fullyConfigured())
                            .delegatedCredentials("  ", List.of(DomainWideDelegationProvider.CALENDAR_SCOPE)));
        }

        @Test
        @DisplayName("A malformed key tells the admin what to paste")
        void malformedKeyIsActionable() {
            Map<String, Object> attributes = fullyConfigured();
            attributes.put(Constants.SERVICE_ACCOUNT_KEY, "{\"type\":\"not_a_service_account\"}");

            DomainWideDelegationException failure = assertThrows(DomainWideDelegationException.class,
                    () -> providerWith(attributes)
                            .delegatedCredentials(USER, List.of(DomainWideDelegationProvider.CALENDAR_SCOPE)));

            assertTrue(failure.getMessage().contains("JSON key file"), failure.getMessage());
        }

        @Test
        @DisplayName("A Calendar client can be built for an arbitrary tenant mailbox")
        void buildsACalendarClient() throws Exception {
            assertNotNull(providerWith(fullyConfigured()).calendarClientFor(USER));
        }

        @Test
        @DisplayName("A Directory client cannot be built without an admin to impersonate")
        void directoryNeedsAnAdmin() {
            Map<String, Object> keyOnly = new HashMap<>();
            keyOnly.put(Constants.SERVICE_ACCOUNT_KEY, serviceAccountJson);

            DomainWideDelegationException failure = assertThrows(DomainWideDelegationException.class,
                    () -> providerWith(keyOnly).directoryClientForAdmin());

            assertTrue(failure.getMessage().contains(Constants.TENANT_ADMIN_EMAIL), failure.getMessage());
        }
    }

    // ── secret hygiene ────────────────────────────────────────────────────

    /**
     * The private key is the input to every parser on this path, and libraries are free to quote
     * their input. These tests are the reason redaction happens at the throw site rather than being
     * left to whoever writes the next log line.
     */
    @Nested
    @DisplayName("The service-account private key never reaches a log or a message")
    class SecretHygiene {

        @Test
        @DisplayName("A PEM block is removed wholesale")
        void redactsPemBlocks() {
            String redacted = DomainWideDelegationProvider.redact("failed on " + privateKeyPem + " sorry");

            assertFalse(redacted.contains("BEGIN PRIVATE KEY"), redacted);
            assertFalse(redacted.contains(privateKeyPem.substring(40, 80)), redacted);
        }

        @Test
        @DisplayName("A bare base64 run — a token or a key body without markers — is removed")
        void redactsLongSecretRuns() {
            String token = "ya29." + "A".repeat(120);

            String redacted = DomainWideDelegationProvider.redact("token=" + token);

            assertFalse(redacted.contains("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), redacted);
            assertTrue(redacted.contains("[REDACTED]"), redacted);
        }

        @Test
        @DisplayName("Ordinary diagnostics survive redaction, so the message stays useful")
        void keepsOrdinaryText() {
            String message = "Error reading credentials from stream, 'type' field not specified.";

            assertEquals(message, DomainWideDelegationProvider.redact(message));
        }

        @Test
        @DisplayName("Redaction is bounded, so one huge message cannot flood the log")
        void boundsLength() {
            assertTrue(DomainWideDelegationProvider.redact("x".repeat(5_000)).length() <= 301);
        }

        @Test
        @DisplayName("Null and empty inputs are handled without a NullPointerException")
        void handlesNulls() {
            assertEquals("", DomainWideDelegationProvider.redact(null));
            assertEquals("", DomainWideDelegationProvider.redact(""));
        }

        @Test
        @DisplayName("A sanitised cause keeps its stack trace but loses its secrets")
        void sanitizedKeepsStackTraceLosesSecrets() {
            IOException inner = new IOException("inner key " + privateKeyPem);
            RuntimeException outer = new RuntimeException("outer key " + privateKeyPem, inner);

            Throwable sanitized = DomainWideDelegationProvider.sanitized(outer);

            assertNotNull(sanitized);
            assertEquals(outer.getStackTrace().length, sanitized.getStackTrace().length,
                    "The stack trace is the diagnostic value; only the message is a liability");
            String rendered = fullyRendered(sanitized);
            assertFalse(rendered.contains("BEGIN PRIVATE KEY"), rendered);
            assertTrue(rendered.contains("java.lang.RuntimeException"), rendered);
            assertTrue(rendered.contains("java.io.IOException"),
                    "The cause chain must survive so the real failure is still identifiable");
        }

        @Test
        @DisplayName("A self-referencing cause chain terminates instead of overflowing the stack")
        void handlesCycles() {
            RuntimeException looping = new RuntimeException("boom") {
                @Override
                public synchronized Throwable getCause() {
                    return this;
                }
            };

            assertNotNull(DomainWideDelegationProvider.sanitized(looping));
        }

        /**
         * The end-to-end guarantee: take the real failure path an administrator triggers by pasting
         * a broken key, render it exactly as a logger would, and prove the key is not in it.
         */
        @Test
        @DisplayName("A parse failure logged with its stack trace contains no key material")
        void parseFailureLogsNoKey() {
            Map<String, Object> attributes = fullyConfigured();
            attributes.put(Constants.SERVICE_ACCOUNT_KEY,
                    serviceAccountJson.replace("\"type\":\"service_account\",", ""));

            DomainWideDelegationException failure = assertThrows(DomainWideDelegationException.class,
                    () -> providerWith(attributes)
                            .delegatedCredentials(USER, List.of(DomainWideDelegationProvider.CALENDAR_SCOPE)));

            String rendered = fullyRendered(failure);
            assertFalse(rendered.contains("BEGIN PRIVATE KEY"), rendered);
            assertFalse(rendered.contains(privateKeyPem.substring(40, 90)), rendered);
        }
    }

    // ── failure classification ────────────────────────────────────────────

    @Nested
    @DisplayName("Recognising an unauthorised delegation")
    class DelegationDenied {

        @Test
        @DisplayName("Every shape Google reports an unauthorised delegation in is one bucket")
        void recognisesTheFamily() {
            assertTrue(DomainWideDelegationProvider.isDelegationDenied(
                    new IOException("Error getting access token for service account: unauthorized_client")));
            assertTrue(DomainWideDelegationProvider.isDelegationDenied(
                    new IOException("400 Bad Request: {\"error\":\"access_denied\"}")));
            assertTrue(DomainWideDelegationProvider.isDelegationDenied(
                    new IOException("invalid_grant: Invalid email or User ID")));
            assertTrue(DomainWideDelegationProvider.isDelegationDenied(
                    new RuntimeException("wrapper", new IOException("unauthorized_client"))),
                    "The signal is usually one or two frames down, not on the outermost throwable");
        }

        @Test
        @DisplayName("A transient fault is NOT reported as an authorisation problem")
        void doesNotOverClaim() {
            assertFalse(DomainWideDelegationProvider.isDelegationDenied(
                    new IOException("Connection reset by peer")));
            assertFalse(DomainWideDelegationProvider.isDelegationDenied(
                    new IOException("503 Service Unavailable")));
            assertFalse(DomainWideDelegationProvider.isDelegationDenied(null));
        }

        @Test
        @DisplayName("The remediation names the console location and both exact scopes")
        void remediationIsCopyPasteable() {
            String remediation = DomainWideDelegationProvider.remediation();

            assertTrue(remediation.contains("Domain-wide delegation"), remediation);
            assertTrue(remediation.contains(DomainWideDelegationProvider.CALENDAR_SCOPE), remediation);
            assertTrue(remediation.contains(DomainWideDelegationProvider.DIRECTORY_SCOPE), remediation);
            assertTrue(remediation.contains(Constants.SERVICE_ACCOUNT_KEY), remediation);
            assertTrue(remediation.contains(Constants.TENANT_ADMIN_EMAIL), remediation);
        }
    }
}
