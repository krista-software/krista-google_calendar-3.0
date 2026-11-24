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

import app.krista.conversationagent.module.shared.esb.ContractFingerprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 2 of the KIM-492 drift check (spec §2.3): the staged Meeting Agent contract jar and the
 * {@code .fingerprint} sidecar committed beside it must come from ONE MA build.
 *
 * <p><b>What this catches that nothing else can.</b> A jar refreshed without its sidecar, or a
 * sidecar refreshed without its jar — the two ways a staged artifact silently stops describing
 * what it contains. Neither shows up as a compile error, and at runtime proto3's lack of field
 * presence turns the disagreement into wrong VALUES rather than an exception (spec §6 R2). The
 * check has to be build-failing because a warning about this would be read after the data loss.
 *
 * <p><b>The normalisation is not reimplemented here.</b> {@code ContractFingerprint} ships inside
 * the jar and is the same code MA's own fingerprint task runs. Re-deriving the canonical form on
 * this side would create a second definition of "the contract" — which is precisely the class of
 * duplication WP-E15 exists to delete.
 */
@DisplayName("Staged MA contract jar ↔ fingerprint")
class CaContractFingerprintTest {

    /**
     * Resolved from the project directory rather than the classpath: the point is to check the
     * files as COMMITTED under libs/, not whatever ended up on the test runtime classpath.
     */
    private static Path stagedJar() throws IOException {
        Path libs = Paths.get("libs");
        assertTrue(Files.isDirectory(libs),
                "libs/ is missing — the staged MA contract jar is a committed build input");

        try (Stream<Path> entries = Files.list(libs)) {
            return entries
                    .filter(p -> p.getFileName().toString().startsWith("conversation-agent-contracts-"))
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "No conversation-agent-contracts-*.jar staged under libs/"));
        }
    }

    private static Path sidecarFor(Path jar) {
        String name = jar.getFileName().toString();
        return jar.resolveSibling(name.substring(0, name.length() - ".jar".length()) + ".fingerprint");
    }

    @Test
    @DisplayName("the staged jar's wire contract matches the committed .fingerprint sidecar")
    void stagedJarMatchesItsSidecar() throws IOException {
        Path jar = stagedJar();
        Path sidecar = sidecarFor(jar);

        assertTrue(Files.isRegularFile(sidecar),
                "Staged jar " + jar.getFileName() + " has no .fingerprint beside it. The two are"
                        + " staged together and must be refreshed together.");

        String expected = Files.readString(sidecar, StandardCharsets.UTF_8).trim();
        ContractFingerprint.VerificationResult result =
                ContractFingerprint.verifyStagedJar(jar, expected);

        assertEquals(expected, result.fingerprint());
        assertTrue(result.canonicalLines() > 0,
                "The jar's canonical contract is empty — it carries no schemas or service"
                        + " definitions, so the fingerprint attests to nothing");
    }

    @Test
    @DisplayName("a tampered sidecar fails the check instead of passing quietly")
    void aTamperedSidecarIsRejected() throws IOException {
        Path jar = stagedJar();

        ContractFingerprint.ContractDriftException drift =
                assertThrows(ContractFingerprint.ContractDriftException.class,
                        () -> ContractFingerprint.verifyStagedJar(jar, "0".repeat(64)));

        // The refusal has to name BOTH sides. "Contracts differ" without the two fingerprints is
        // not actionable: the operator cannot tell which artifact to refresh.
        assertNotNull(drift.getMessage());
        assertTrue(drift.getMessage().contains("0".repeat(64)),
                "The drift message must quote the sidecar fingerprint it rejected");
    }

    /**
     * EF-13 — the gap the fingerprint check CANNOT see, asserted against the committed jar itself.
     *
     * <p>The seat-registered schema — and, since KIM-492, the in-call messages schema — sits OUTSIDE
     * the fingerprinted set
     * ({@code ContractFingerprint}'s four-proto allow-list; {@code esb-transport-progress-report.md:147}
     * records that as intentional). So a staged jar can be missing it entirely and still verify against
     * a matching sidecar — which is exactly what happened: this extension's jar was FIVE entries short
     * of teams-transcribe's under a byte-identical {@code .fingerprint}, and nothing on either side
     * could tell. In a deployed jar the absence costs every seat notification, decoded as "no
     * ProtoStream marshaller registered for input" on a durable queue.
     *
     * <p>Asserted on the JAR rather than by class-loading: {@code CaContractProtoRegistrationTest}
     * covers the runtime registration, but it runs against the test classpath, and the artifact this
     * extension ships is the file under {@code libs/}. Those are the two halves of EF-13 and only this
     * one sees a truncated stage.
     */
    @Test
    @DisplayName("the staged jar carries the UNFINGERPRINTED contracts, which the sidecar cannot attest")
    void stagedJarCarriesTheUnfingerprintedContracts() throws IOException {
        Path jar = stagedJar();

        java.util.Set<String> entries = new java.util.HashSet<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            zip.stream().map(java.util.zip.ZipEntry::getName).forEach(entries::add);
        }

        for (String required : java.util.List.of(
                "app/krista/conversationagent/module/calendar/esb/CalendarSeatRegisteredCommandProto.class",
                "app/krista/conversationagent/module/calendar/esb/CalendarSeatRegisteredProtoInitializer.class",
                "app/krista/conversationagent/module/calendar/esb/CalendarSeatRegisteredProtoInitializerImpl.class",
                "proto/ext/calendar/calendar-seat-registered.proto",
                // KIM-492 — the in-call messages broadcast, outside the fingerprinted set for the
                // same reason and therefore exposed to the same truncated-stage failure.
                "app/krista/conversationagent/module/calendar/esb/CalendarWorkspaceMessagesProto.class",
                "app/krista/conversationagent/module/calendar/esb/CalendarWorkspaceMessagesProtoInitializer.class",
                "app/krista/conversationagent/module/calendar/esb/CalendarWorkspaceMessagesProtoInitializerImpl.class",
                "proto/ext/calendar/calendar-workspace-messages.proto")) {
            assertTrue(entries.contains(required),
                    "The staged contract jar " + jar.getFileName() + " does not contain " + required
                            + ". The .fingerprint sidecar CANNOT catch this — the seat-registered "
                            + "schema is outside the fingerprinted set — so a jar staged from a "
                            + "partial Meeting Agent build verifies cleanly and then fails every "
                            + "ext.calendar.seat.registered.google message at runtime. Re-stage all "
                            + "extensions' jars from ONE Meeting Agent build.");
        }

        // The generated marshaller's name carries a schema hash, so it is matched by prefix rather
        // than by literal: pinning the hash would make an innocuous contract rebuild a red build.
        assertTrue(entries.stream().anyMatch(name -> name.startsWith(
                        "app/krista/conversationagent/module/calendar/esb/"
                                + "CalendarSeatRegisteredCommandProto$___Marshaller")),
                "The seat-registered command's generated marshaller is absent from the staged jar. "
                        + "The initializer would register a schema it cannot marshal.");

        assertTrue(entries.stream().anyMatch(name -> name.startsWith(
                        "app/krista/conversationagent/module/calendar/esb/"
                                + "CalendarWorkspaceMessagesProto$___Marshaller")),
                "The in-call messages broadcast's generated marshaller is absent from the staged "
                        + "jar. The initializer would register a schema it cannot marshal, so the "
                        + "durable ext.calendar.workspace.messages.google queue would never drain.");
    }

    @Test
    @DisplayName("the fingerprint inside the jar is the one the runtime handshake compares")
    void theRuntimeHandshakeReadsTheSameFingerprint() throws IOException {
        String staged = ContractHandshake.stagedFingerprint();

        assertNotNull(staged,
                "ContractHandshake could not read wire/ca-ext-contract.fingerprint from the"
                        + " contract jar. Without it the startup handshake degrades to UNVERIFIED"
                        + " on every boot and stops catching a stale MA deploy.");
        assertEquals(Files.readString(sidecarFor(stagedJar()), StandardCharsets.UTF_8).trim(), staged,
                "The fingerprint the runtime reads from the jar differs from the committed"
                        + " sidecar this build was checked against");
    }
}
