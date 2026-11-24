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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packaged ESB defaults, asserted as a shipped artifact.
 *
 * <h2>Why a test over a properties file</h2>
 *
 * <p>Every key here is load-bearing for a defect that has already happened, and a properties file is
 * the easiest thing in a repository to lose in a merge: nothing references it by symbol, no compiler
 * notices, and the failure it reintroduces is a slow one nobody connects to a config diff weeks
 * later. These assertions are the reason each key exists, written where a deletion trips over them.
 */
@DisplayName("Packaged ESB defaults")
class EsbDefaultPropertiesTest {

    private static Properties packaged;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = EsbDefaultPropertiesTest.class.getClassLoader()
                .getResourceAsStream("esb/esb_default.properties")) {
            assertNotNull(in, "esb/esb_default.properties is not on the classpath");
            packaged = new Properties();
            packaged.load(in);
        }
    }

    /**
     * P-01. krista-infra defaults producer session pooling to {@code false}, and with it off every
     * request/reply call creates its own temporary Artemis reply queue with a ten-minute
     * auto-delete delay — so live reply queues sit at roughly call-rate × 600 s. WP-E16 moved this
     * extension's whole Meeting Agent surface onto that path. A broker carrying a large population
     * of auto-delete queues has wedged in production before (2026-08-31), and the symptom was
     * Meeting Agent answering 401 because the broker had stopped answering at all.
     */
    @Test
    @DisplayName("P-01: producer session pooling is ON, so a reply queue is not created per call")
    void producerSessionPoolingIsEnabled() {
        assertEquals("true", packaged.getProperty("broker.producer.session.pooling.enabled"),
                "krista-infra defaults this to false. Without it every extension->MA request/reply "
                        + "call creates an auto-delete Artemis reply queue that outlives the call by "
                        + "ten minutes.");
    }

    /**
     * EF-02. Both budgets must be present and inside the clamp — a key that is absent still works
     * (the code falls back to its default) but the packaged file is where an operator looks to find
     * out what the extension does, and a missing key reads as "unbounded".
     */
    @Test
    @DisplayName("EF-02: both Meeting Agent call budgets are packaged, and inside the clamp")
    void callBudgetsArePackaged() {
        long read = Long.parseLong(packaged.getProperty("ca.esb.request.timeoutMs"));
        long bulk = Long.parseLong(packaged.getProperty("ca.esb.bulkRequest.timeoutMs"));

        assertEquals(30_000L, read);
        assertEquals(60_000L, bulk);
        assertTrue(read <= 120_000L && bulk <= 120_000L,
                "a packaged budget above the clamp ceiling would be silently reduced at runtime, so "
                        + "the file would document a behaviour the extension does not have");
        assertTrue(read >= 1_000L && bulk >= 1_000L);
    }

    /** EF-29: MA's batch cap is configuration, and the packaged value is the cap MA enforces today. */
    @Test
    @DisplayName("EF-29: the bulk cap is packaged at MA's documented 50")
    void bulkCapIsPackaged() {
        // The gateway's own resolution of this value (and its clamp) is asserted by
        // CaGatewayTimeoutBudgetTest, which lives in the same package as CaCallBudget.
        assertEquals("50", packaged.getProperty("ca.esb.bulkRequest.maxMeetings"));
    }

}
