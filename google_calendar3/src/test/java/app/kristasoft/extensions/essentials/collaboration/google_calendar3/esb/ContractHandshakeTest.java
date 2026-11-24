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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 3 of the KIM-492 drift check: the startup handshake's decision table.
 *
 * <p>The asymmetry between MISMATCH and UNVERIFIED is the whole point of these tests. Getting it
 * backwards in either direction is a production outage: refusing on absence takes every extension
 * offline the moment MA has a blip, and admitting on a real mismatch produces silently wrong
 * calendar data that surfaces weeks later as a missing transcript.
 */
@DisplayName("Startup contract handshake")
class ContractHandshakeTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);

    @Nested
    @DisplayName("refuses only on a genuine disagreement")
    class Mismatch {

        @Test
        @DisplayName("two fingerprints that differ is a MISMATCH")
        void differingFingerprintsMismatch() {
            assertEquals(ContractHandshake.Verdict.MISMATCH, ContractHandshake.compare(A, B));
        }

        @Test
        @DisplayName("the refusal message names BOTH fingerprints, so an operator knows what to refresh")
        void mismatchMessageNamesBothSides() {
            String message = ContractHandshake.mismatchMessage(A, B);

            assertNotNull(message);
            assertTrue(message.contains(A), "MA's reported fingerprint must appear in the message");
            assertTrue(message.contains(B), "this build's fingerprint must appear in the message");
            assertTrue(message.contains("will NOT be started"),
                    "the message must say what was refused, not only that something differs");
        }
    }

    @Nested
    @DisplayName("admits on a match")
    class Match {

        @Test
        @DisplayName("identical fingerprints are a MATCH")
        void identicalFingerprintsMatch() {
            assertEquals(ContractHandshake.Verdict.MATCH, ContractHandshake.compare(A, A));
        }

        @Test
        @DisplayName("case and surrounding whitespace do not manufacture a mismatch")
        void caseAndWhitespaceInsensitive() {
            assertEquals(ContractHandshake.Verdict.MATCH,
                    ContractHandshake.compare("  " + A.toUpperCase() + "\n", A));
        }
    }

    @Nested
    @DisplayName("treats an absent signal as UNVERIFIED, never as drift")
    class Unverified {

        @ParameterizedTest(name = "MA reported [{0}] -> UNVERIFIED")
        @NullSource
        @ValueSource(strings = {"", "   "})
        @DisplayName("an MA that predates WP-E2 reports no fingerprint and must not be refused")
        void absentCaFingerprintIsUnverified(String reported) {
            assertEquals(ContractHandshake.Verdict.UNVERIFIED, ContractHandshake.compare(reported, A));
        }

        @ParameterizedTest(name = "staged [{0}] -> UNVERIFIED")
        @NullSource
        @ValueSource(strings = {"", "   "})
        @DisplayName("a build with no staged fingerprint cannot assert drift about anyone")
        void absentStagedFingerprintIsUnverified(String staged) {
            assertEquals(ContractHandshake.Verdict.UNVERIFIED, ContractHandshake.compare(A, staged));
        }

        @Test
        @DisplayName("both absent is still UNVERIFIED, not a vacuous MATCH")
        void bothAbsentIsUnverified() {
            assertEquals(ContractHandshake.Verdict.UNVERIFIED, ContractHandshake.compare(null, null));
        }
    }

    @Test
    @DisplayName("the staged fingerprint is readable from the contract jar on the classpath")
    void stagedFingerprintIsReadable() {
        String staged = ContractHandshake.stagedFingerprint();

        assertNotNull(staged, "the staged contract jar must carry wire/ca-ext-contract.fingerprint");
        assertEquals(64, staged.length(), "a SHA-256 hex digest is 64 characters");
    }
}
