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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup comparison of the wire contract THIS build was compiled against with the one the Meeting
 * Agent it just bootstrapped from was built from — layer 3 of the KIM-492 drift check (spec §2.3).
 *
 * <p><b>Why a runtime check when two build-time ones already exist.</b> MA's own fingerprint test
 * and {@code CaContractFingerprintTest} both compare artifacts that were present at compile time.
 * Neither can see the only failure that matters in production: a Google-extension pod and an MA
 * pod that each built cleanly, against DIFFERENT contract versions, and are now talking to each
 * other.
 *
 * <p><b>Why a mismatch refuses to start the consumers rather than warning.</b> A fingerprint
 * mismatch means the two sides disagree about proto field numbers, and proto3 has no field
 * presence: a renumbered field decodes as {@code ""}, {@code 0} or {@code false} on the far side
 * with no exception anywhere (spec §6 R2). Continuing produces silently wrong calendar data — an
 * empty organiser, a zero start time, a {@code false} where {@code true} was sent — which nobody
 * notices until a customer reports a missing transcript weeks later. Refusing to consume turns
 * that into an immediate, attributable startup error.
 *
 * <p><b>Why an ABSENT fingerprint is not a mismatch.</b> An MA older than WP-E2 reports none, and
 * treating the absence of a signal as a signal would take a working extension offline on every MA
 * blip — the same asymmetry {@code ExtensionService.hasBoundMailExtension} applies to a failed
 * read. Only two reported fingerprints that DIFFER refuse.
 *
 * <p><b>What keeps working on a refusal.</b> Only the ESB consumers are held back. The MA
 * bootstrap call and the whole registration family are untouched, so the extension still reports
 * itself and an operator sees it in the console with the reason in its logs.
 *
 * <p><b>Recovery.</b> A mismatch is resolved by deploying an MA and an extension built from the
 * same contract. {@code GoogleServiceBusManager} re-evaluates the verdict on every start AND on
 * every rebind, because a rebind re-fetches the invoker record: an MA redeployed onto a matching
 * contract therefore clears the refusal at the next Save Configuration, with no extension restart.
 * Re-running it there is also what stops a rebind from starting the consumers the refusal held
 * back.
 *
 * @since 3.1.0
 */
public final class ContractHandshake {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContractHandshake.class);

    /** Outcome of comparing MA's reported fingerprint with the staged one. */
    public enum Verdict {

        /** Both sides reported a fingerprint and they are identical. Consumers may start. */
        MATCH,

        /** Both sides reported a fingerprint and they DIFFER. Consumers must not start. */
        MISMATCH,

        /**
         * No comparison was possible — MA reported no fingerprint (it predates WP-E2), or the
         * bootstrap did not complete, or this jar carries no staged fingerprint. Consumers start.
         */
        UNVERIFIED
    }

    private ContractHandshake() {
    }

    /**
     * The fingerprint this build was compiled against, read from the staged contract jar's own
     * {@code wire/ca-ext-contract.fingerprint} entry.
     *
     * <p>Deliberately read from the JAR rather than from the {@code libs/*.fingerprint} sidecar:
     * the sidecar is a build-time file that is not packaged into the extension, and the value that
     * matters at runtime is the one belonging to the classes actually on the classpath.
     * {@code CaContractFingerprintTest} is what proves the two agree.
     *
     * @return the staged fingerprint, or {@code null} if it cannot be read
     */
    public static String stagedFingerprint() {
        try {
            String staged = ContractFingerprint.published();
            return staged == null || staged.isBlank() ? null : staged.trim();
        }
        catch (RuntimeException e) {
            LOGGER.warn("Could not read the staged contract fingerprint from the contract jar: {}."
                    + " The startup contract handshake will be skipped.", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Compares MA's reported fingerprint with the staged one.
     *
     * <p>Blank is treated as absent on both sides — an empty JSON value carries no more
     * information than a missing key, and inventing a mismatch out of one would take a working
     * extension offline over a serialization detail.
     *
     * @param caReported the {@code contractsFingerprint} MA returned on the invoker element
     * @param staged     this build's own fingerprint, from {@link #stagedFingerprint()}
     * @return the verdict; never {@code null}
     */
    public static Verdict compare(String caReported, String staged) {
        if (caReported == null || caReported.isBlank() || staged == null || staged.isBlank()) {
            return Verdict.UNVERIFIED;
        }
        return caReported.trim().equalsIgnoreCase(staged.trim()) ? Verdict.MATCH : Verdict.MISMATCH;
    }

    /**
     * Builds the operator-facing explanation for a mismatch. Kept beside the comparison so the
     * message and the rule cannot drift apart, and so a test can assert both fingerprints are
     * actually named — a refusal that does not say WHICH two builds disagree is not actionable.
     *
     * @param caReported MA's reported fingerprint
     * @param staged     this build's staged fingerprint
     * @return the log message
     */
    public static String mismatchMessage(String caReported, String staged) {
        return "Meeting Agent and this extension were built from DIFFERENT ext<->MA wire contracts."
                + " MA reported contractsFingerprint=" + caReported
                + ", this build has " + staged + "."
                + " ESB consumers will NOT be started: proto3 has no field presence, so consuming"
                + " messages across a contract mismatch produces silently wrong data rather than"
                + " an error. Bootstrap and registration are unaffected."
                + " Fix by deploying a Meeting Agent and an extension built from the same contract"
                + " (refresh libs/conversation-agent-contracts-<version>.jar and its .fingerprint"
                + " from that MA build), then restart this extension.";
    }
}
