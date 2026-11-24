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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.config;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.EsbEndpointBinding.Endpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EsbEndpointBinding} — the helper that binds (and rebinds) this JVM's
 * krista-infra ESB properties to whatever endpoint the CA handshake reports.
 *
 * <p>Two behaviours carry real weight and are covered exhaustively:
 * <ul>
 *   <li>The <b>changed/unchanged</b> answer, because the caller uses it to decide whether to
 *       recycle live consumers. A false positive churns JMS connections on every refresh tick;
 *       a false negative leaves consumers dialling a broker that has moved.</li>
 *   <li>The <b>set-only namespace</b> rule (KIM-451): a blank {@code esbNamespace} means "leave
 *       destination naming alone", never "clear it".</li>
 * </ul>
 *
 * <p>The properties are process-global, so every test clears them in setup and teardown to keep
 * the suite hermetic regardless of ordering.
 */
@DisplayName("EsbEndpointBinding")
class EsbEndpointBindingTest {

    private static final String NAMESPACE_PROP = "broker.dest.namespace";

    private static final String[] MANAGED_PROPERTIES = {
            "broker.primary.host", "broker.primary.port", "broker.user.name", "broker.user.pwd",
            "krista.esb.host", "krista.esb.port", "krista.esb.username", "krista.esb.password",
            NAMESPACE_PROP
    };

    @BeforeEach
    @AfterEach
    void clearManagedProperties() {
        for (String property : MANAGED_PROPERTIES) {
            System.clearProperty(property);
        }
    }

    private static Endpoint endpoint(String host, int port) {
        return new Endpoint(host, port, null, "artemis", "secret");
    }

    @Nested
    @DisplayName("property binding")
    class PropertyBinding {

        @Test
        @DisplayName("Both the broker.* and krista.esb.* families are written together")
        void testWritesBothPropertyFamilies() {
            EsbEndpointBinding.apply(endpoint("artemis.acme.svc", 61617));

            assertEquals("artemis.acme.svc", System.getProperty("broker.primary.host"));
            assertEquals("61617", System.getProperty("broker.primary.port"));
            assertEquals("artemis", System.getProperty("broker.user.name"));
            assertEquals("secret", System.getProperty("broker.user.pwd"));

            // The mirrors must never describe a different broker than the broker.* pair.
            assertEquals("artemis.acme.svc", System.getProperty("krista.esb.host"));
            assertEquals("61617", System.getProperty("krista.esb.port"));
            assertEquals("artemis", System.getProperty("krista.esb.username"));
            assertEquals("secret", System.getProperty("krista.esb.password"));
        }

        @Test
        @DisplayName("A blank host is rejected — it would bind consumers to nothing")
        void testRejectsBlankHost() {
            assertThrows(IllegalArgumentException.class,
                    () -> EsbEndpointBinding.apply(endpoint("   ", 61616)));
            assertThrows(IllegalArgumentException.class,
                    () -> EsbEndpointBinding.apply(endpoint(null, 61616)));
        }

        @Test
        @DisplayName("current() reports an unset port as -1 so the first bind always registers")
        void testCurrentReportsUnsetPort() {
            assertNull(EsbEndpointBinding.current().host());
            assertEquals(-1, EsbEndpointBinding.current().port());
        }

        @Test
        @DisplayName("current() reports an unparseable port as -1 rather than throwing")
        void testCurrentToleratesGarbagePort() {
            System.setProperty("broker.primary.port", "not-a-number");

            assertEquals(-1, EsbEndpointBinding.current().port());
        }
    }

    @Nested
    @DisplayName("change detection")
    class ChangeDetection {

        @Test
        @DisplayName("The first bind reports a change")
        void testFirstBindIsAChange() {
            assertTrue(EsbEndpointBinding.apply(endpoint("artemis", 61616)));
        }

        @Test
        @DisplayName("Re-applying the identical endpoint reports no change")
        void testIdenticalRebindIsNoChange() {
            EsbEndpointBinding.apply(endpoint("artemis", 61616));

            // This is what stops the background refresh from recycling healthy consumers every tick.
            assertFalse(EsbEndpointBinding.apply(endpoint("artemis", 61616)));
        }

        @Test
        @DisplayName("A moved host reports a change")
        void testHostMoveIsAChange() {
            EsbEndpointBinding.apply(endpoint("artemis", 61616));

            assertTrue(EsbEndpointBinding.apply(endpoint("artemis-2", 61616)));
            assertEquals("artemis-2", System.getProperty("broker.primary.host"));
        }

        @Test
        @DisplayName("A moved port reports a change — it is part of the connection-cache key")
        void testPortMoveIsAChange() {
            EsbEndpointBinding.apply(endpoint("artemis", 61616));

            assertTrue(EsbEndpointBinding.apply(endpoint("artemis", 61617)));
            assertEquals("61617", System.getProperty("broker.primary.port"));
        }

        @Test
        @DisplayName("Rotated credentials report a change — an open consumer cannot pick them up")
        void testCredentialChangeIsAChange() {
            EsbEndpointBinding.apply(endpoint("artemis", 61616));

            assertTrue(EsbEndpointBinding.apply(
                    new Endpoint("artemis", 61616, null, "artemis", "rotated")));
            assertTrue(EsbEndpointBinding.apply(
                    new Endpoint("artemis", 61616, null, "someone-else", "rotated")));
        }
    }

    @Nested
    @DisplayName("destination namespace (KIM-451)")
    class DestinationNamespace {

        @Test
        @DisplayName("Non-blank namespace is set verbatim (trimmed)")
        void testSetsNamespace() {
            EsbEndpointBinding.apply(new Endpoint("artemis", 61616, "  acme-prod\n", "u", "p"));

            assertEquals("acme-prod", System.getProperty(NAMESPACE_PROP));
        }

        @Test
        @DisplayName("Value is passed through verbatim — the extension does NOT re-validate")
        void testDoesNotRevalidate() {
            // Infra owns [a-z0-9-]+ validation and fails fast; the extension must not second-guess
            // it. An uppercase value must still be forwarded untouched.
            EsbEndpointBinding.apply(new Endpoint("artemis", 61616, "Not_Valid.NS", "u", "p"));

            assertEquals("Not_Valid.NS", System.getProperty(NAMESPACE_PROP));
        }

        @Test
        @DisplayName("Null or blank namespace leaves the property absent")
        void testBlankLeavesAbsent() {
            EsbEndpointBinding.apply(new Endpoint("artemis", 61616, null, "u", "p"));
            assertNull(System.getProperty(NAMESPACE_PROP));

            EsbEndpointBinding.apply(new Endpoint("artemis", 61616, "   ", "u", "p"));
            assertNull(System.getProperty(NAMESPACE_PROP));
        }

        @Test
        @DisplayName("Blank namespace does NOT clear a value already present")
        void testBlankDoesNotClearExisting() {
            EsbEndpointBinding.apply(new Endpoint("artemis", 61616, "pre-existing", "u", "p"));

            EsbEndpointBinding.apply(new Endpoint("artemis", 61616, "  ", "u", "p"));

            assertEquals("pre-existing", System.getProperty(NAMESPACE_PROP));
        }

        @Test
        @DisplayName("Dropping the namespace from the handshake is NOT a change")
        void testBlankNamespaceIsNotAChange() {
            EsbEndpointBinding.apply(new Endpoint("artemis", 61616, "preview", "u", "p"));

            // A CA that stops reporting esbNamespace must not be read as "move every queue name",
            // so this must neither clear the property nor trigger a consumer recycle.
            assertFalse(EsbEndpointBinding.apply(new Endpoint("artemis", 61616, null, "u", "p")));
            assertEquals("preview", System.getProperty(NAMESPACE_PROP));
        }

        @Test
        @DisplayName("A different namespace reports a change")
        void testNamespaceMoveIsAChange() {
            EsbEndpointBinding.apply(new Endpoint("artemis", 61616, "preview", "u", "p"));

            assertTrue(EsbEndpointBinding.apply(new Endpoint("artemis", 61616, "prod", "u", "p")));
            assertEquals("prod", System.getProperty(NAMESPACE_PROP));
        }

        @Test
        @DisplayName("Re-applying the same namespace reports no change")
        void testSameNamespaceIsNoChange() {
            EsbEndpointBinding.apply(new Endpoint("artemis", 61616, "preview", "u", "p"));

            assertFalse(EsbEndpointBinding.apply(new Endpoint("artemis", 61616, "preview", "u", "p")));
        }
    }
}
