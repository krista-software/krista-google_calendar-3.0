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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The invoker-discovery DTO — previously untested, and it carries the value the whole startup
 * contract handshake rests on.
 *
 * <h2>What actually needs pinning here</h2>
 *
 * <p>{@link InvokerDetails} has no logic worth testing; what it has is a <b>parsing contract</b> with
 * Meeting Agent, and every property of that contract fails silently when it breaks:
 *
 * <ul>
 *   <li>Gson maps on FIELD NAME. Rename {@code contractsFingerprint} and the field is simply never
 *       populated — no error, no warning — and {@code ContractHandshake} degrades to UNVERIFIED on
 *       every boot, which ADMITS. The consumers start against a contract nobody checked.</li>
 *   <li>An <b>absent</b> fingerprint must stay {@code null}, and null must mean "this Meeting Agent
 *       is older than WP-E2" rather than "mismatch". Defaulting it to a sentinel would refuse to
 *       start against every Meeting Agent that predates the handshake.</li>
 *   <li>An unknown key must be tolerated. Meeting Agent adds fields to this payload; a parser that
 *       rejected them would make every additive Meeting Agent change a broken bootstrap here.</li>
 * </ul>
 *
 * <p>Parsed with a plain {@link Gson}, the way {@code GoogleExtensionConfig} does it, so the
 * assertions are about the real mapping rather than about hand-set fields.
 */
@DisplayName("Invoker-discovery DTO parsing")
class InvokerDetailsTest {

    private static final String FINGERPRINT =
            "f4fd9e1756334f3c32d2a68c10406364b77878b673fb755b13eb2cd6f583bdb3";

    private static final Gson GSON = new Gson();

    @Test
    @DisplayName("a full response maps the id, the ESB block and the fingerprint")
    void mapsAFullResponse() {
        String json = """
                {
                  "id": "INV_google_calendar",
                  "extensionVersion": "3.1.0",
                  "contractsFingerprint": "%s",
                  "connectionDetails": {
                    "esb": {
                      "host": "artemis.krista.svc.cluster.local",
                      "port": 61616,
                      "userName": "ext",
                      "password": "secret",
                      "esbNamespace": "tenant-a"
                    }
                  }
                }
                """.formatted(FINGERPRINT);

        InvokerDetails details = GSON.fromJson(json, InvokerDetails.class);

        assertEquals("INV_google_calendar", details.getId());
        assertEquals(FINGERPRINT, details.getContractsFingerprint());
        assertNotNull(details.getEsbDetails(),
                "without the ESB block the extension cannot reach the broker at all");
        assertEquals("artemis.krista.svc.cluster.local", details.getEsbDetails().getHostOrDefault());
        assertEquals(61616, details.getEsbDetails().getPortOrDefault());
        assertEquals("tenant-a", details.getEsbDetails().getEsbNamespaceOrNull());
    }

    /**
     * The version-skew case that must NOT be read as a mismatch. Any Meeting Agent older than WP-E2
     * omits the key entirely, and {@code ContractHandshake} refuses to start consumers only when both
     * sides report a fingerprint and the two DIFFER — so a sentinel or an empty-string default here
     * would refuse to start against every such Meeting Agent.
     */
    @Test
    @DisplayName("an absent fingerprint is null, not a sentinel and not a mismatch")
    void absentFingerprintIsNull() {
        String json = """
                {
                  "id": "INV_google_calendar",
                  "connectionDetails": { "esb": { "host": "broker", "port": 61616 } }
                }
                """;

        InvokerDetails details = GSON.fromJson(json, InvokerDetails.class);

        assertNull(details.getContractsFingerprint(),
                "null means 'this Meeting Agent never reported one', which the handshake treats as "
                        + "UNVERIFIED. Any non-null default would be compared against the staged "
                        + "fingerprint and read as drift.");
        assertEquals("INV_google_calendar", details.getId());
    }

    /**
     * The field name IS the contract — Gson has nothing else to match on. A rename on either side
     * leaves the field null, which the handshake reads as "Meeting Agent reported nothing" and admits:
     * the failure is not a parse error, it is a check that silently stops happening.
     */
    @Test
    @DisplayName("the fingerprint is read from the key Meeting Agent actually sends")
    void fingerprintKeyNameIsTheContract() {
        InvokerDetails wrongKey = GSON.fromJson(
                "{\"id\":\"i\",\"contractFingerprint\":\"" + FINGERPRINT + "\"}",
                InvokerDetails.class);

        assertNull(wrongKey.getContractsFingerprint(),
                "a near-miss key (singular 'contract') maps to nothing. This is what a rename looks "
                        + "like from here: no error, and the startup handshake quietly stops checking.");

        InvokerDetails rightKey = GSON.fromJson(
                "{\"id\":\"i\",\"contractsFingerprint\":\"" + FINGERPRINT + "\"}",
                InvokerDetails.class);
        assertEquals(FINGERPRINT, rightKey.getContractsFingerprint());
    }

    @Test
    @DisplayName("unknown keys are tolerated, so an additive Meeting Agent change is not a breakage")
    void unknownKeysAreTolerated() {
        String json = """
                {
                  "id": "INV_google_calendar",
                  "somethingMeetingAgentAddedLater": { "nested": [1, 2, 3] },
                  "contractsFingerprint": "%s"
                }
                """.formatted(FINGERPRINT);

        InvokerDetails details = GSON.fromJson(json, InvokerDetails.class);

        assertEquals(FINGERPRINT, details.getContractsFingerprint());
        assertEquals("INV_google_calendar", details.getId());
    }

    @Test
    @DisplayName("a missing connection block yields null rather than throwing")
    void missingEsbBlockIsNull() {
        // The bootstrap has to be able to report "this invoker carries no ESB details" as a
        // condition, not as a stack trace on the invoker-load path.
        assertNull(GSON.fromJson("{\"id\":\"i\"}", InvokerDetails.class).getEsbDetails());
        assertNull(GSON.fromJson("{\"id\":\"i\",\"connectionDetails\":{}}", InvokerDetails.class)
                .getEsbDetails());
    }

    /**
     * The ESB block carries broker credentials. {@code toString} is reachable from any log line that
     * interpolates the DTO, and an invoker-discovery response is exactly the kind of object somebody
     * logs while debugging a bootstrap.
     */
    @Test
    @DisplayName("toString does not print the broker password")
    void toStringDoesNotLeakTheBrokerPassword() {
        String json = """
                {
                  "id": "INV_google_calendar",
                  "connectionDetails": {
                    "esb": { "host": "broker", "port": 61616,
                             "userName": "ext", "password": "s3cr3t-broker-pwd" }
                  }
                }
                """;

        String rendered = GSON.fromJson(json, InvokerDetails.class).toString();

        assertFalse(rendered.contains("s3cr3t-broker-pwd"),
                "the broker password must not reach a log line through toString: " + rendered);
    }
}
