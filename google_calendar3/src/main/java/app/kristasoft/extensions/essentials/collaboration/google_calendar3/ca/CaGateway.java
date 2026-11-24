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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model.CreateMeetingRequestDto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.ScannedMailboxSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Every call this extension makes to Meeting Agent, in this extension's own vocabulary
 * (KIM-492 WP-E16).
 *
 * <p><b>There is exactly ONE implementation, {@link EsbCaGateway}, and this extension is ESB-only
 * for every Meeting Agent call.</b> The {@code caTransport} invoker attribute that used to choose
 * between this and an HTTP implementation was never commissioned; the product owner struck it on
 * 2026-09-11, and the HTTP gateway, the selector and the transport enum were deleted with it. A
 * rollback is a redeploy of the previous jar, not an attribute flip.
 *
 * <p>The interface survives the deletion on purpose. It is not an abstraction over a choice — it is
 * the vocabulary: {@link UpsertOutcome}, {@link UpsertStatus}, {@link MeetingLookupCriteria} and
 * {@link MeetingKeyMatch} are what five call sites read, and they are what keeps MA's wire shapes
 * out of the calendar code. Do not reintroduce a second implementation to "restore" a transport
 * choice; nothing asked for one.
 *
 * <h2>What "failure" means here</h2>
 *
 * <p>Nothing on this interface throws for a remote failure. Meeting Agent being briefly
 * unreachable must never abort a calendar sweep or drop a webhook — every existing call site
 * already degrades rather than propagates, and the seam preserves that contract unchanged. A
 * failure is an {@code Optional.empty()}, a {@code false}, an empty collection or an
 * {@link UpsertStatus#FAILED}, always with a WARN naming the cause.
 *
 * <h2>Reading an ESB reply</h2>
 *
 * <p>The ESB implementation branches on the reply's {@code errorCode} and on nothing else.
 * {@code ReplyBase.ok} and {@code errorMessage} carry no {@code @ProtoField} and never cross the
 * wire, so a decoded reply always reads {@code isOk() == true} regardless of what MA decided — and
 * a client-side transport failure produces a default-constructed reply whose {@code errorCode} is
 * {@code null}. Hence: blank {@code errorCode} is success, a populated one is that
 * {@code ExtErrorCodes} constant, and {@code null} (or a null reply) is a transport failure. Never
 * string-match {@code errorMessage}.
 */
public interface CaGateway {

    // ── A1 / A2 — upsert ──────────────────────────────────────────────────

    /**
     * How Meeting Agent answered one upsert.
     *
     * <p>The three non-failure values mirror what the HTTP path already distinguished, so that
     * moving transport changes no caller's decision:
     * <ul>
     *   <li>{@link #UPSERTED} — persisted (HTTP 200/201, ESB blank {@code errorCode}).</li>
     *   <li>{@link #SKIPPED_CANCELLED} — a cancellation for a meeting MA never captured, which MA
     *       deliberately drops. Counts as accepted: there is nothing to retry and nothing to keep
     *       tracking.</li>
     *   <li>{@link #NO_REGISTERED_USER} — the D-05 drop. Deliberately NOT accepted, because that is
     *       the pre-existing behaviour: local tracking is kept so the meeting is re-offered once
     *       somebody on it registers. Making it "accepted" would quietly discard the tracking entry
     *       the retry depends on.</li>
     * </ul>
     */
    enum UpsertStatus {
        UPSERTED,
        SKIPPED_CANCELLED,
        NO_REGISTERED_USER,
        FAILED;

        /** @return true when MA took a deliberate decision that needs no retry */
        public boolean acceptedByCa() {
            return this == UPSERTED || this == SKIPPED_CANCELLED;
        }
    }

    /**
     * One meeting's upsert result.
     *
     * @param status       what MA decided
     * @param meetingKey   MA's key, null unless {@link UpsertStatus#UPSERTED}
     * @param iCalUId      echoed back so the caller can key its tracking store without guessing
     * @param created      true when this upsert created the row rather than updating it
     * @param errorCode    the {@code ExtErrorCodes} constant, or null
     * @param errorMessage a human-readable cause for logs only — never branched on
     */
    record UpsertOutcome(UpsertStatus status, String meetingKey, String iCalUId, boolean created,
                         String errorCode, String errorMessage) {

        public static UpsertOutcome failed(String errorCode, String errorMessage) {
            return new UpsertOutcome(UpsertStatus.FAILED, null, null, false, errorCode, errorMessage);
        }

        public boolean acceptedByCa() {
            return status.acceptedByCa();
        }

        /**
         * EF-28: whether Meeting Agent never answered at all, as opposed to answering with a
         * refusal.
         *
         * <p>The distinction matters to a caller's telemetry and to whoever reads the logs after a
         * partial sweep. A refusal is Meeting Agent's decision about one meeting — a cancellation it
         * never captured, a meeting nobody registered for — and the sweep did its job. A transport
         * failure is our side being unable to ask, which is an outage, and nothing about the affected
         * meetings has been decided. Reporting the second as the first is how a broker problem gets
         * filed as a customer registration problem.
         *
         * <p>Reachable only through FAILED with no code: every refusal carries an
         * {@code ExtErrorCodes} constant, and a successful upsert is not FAILED.
         *
         * @return true when the call did not reach Meeting Agent
         */
        public boolean caUnreached() {
            return status == UpsertStatus.FAILED && errorCode == null;
        }
    }

    /**
     * A1 — upserts one meeting.
     *
     * <p><b>Never a cancellation</b> (EF-34 corrected this javadoc, which claimed an
     * {@code isCancelled} flag on the payload decides between the two). There is no such field on
     * {@link CreateMeetingRequestDto} and there never was: unlike the Teams extension, this one
     * routes a cancelled Google event down {@link #deleteMeeting} instead, and
     * {@code EsbCaGateway.toNativeMeeting} sets the wire type's {@code cancelled} to {@code false}
     * unconditionally because there is nothing to read it from. Reading this javadoc as a promise
     * would send somebody looking for a cancellation path through the upsert that does not exist.
     *
     * @param meeting the calendar-native payload for a create or an update
     * @return the outcome, never null
     */
    UpsertOutcome upsertMeeting(CreateMeetingRequestDto meeting);

    /**
     * A2 — upserts a batch of meetings and reports each one's outcome.
     *
     * <p>Per-meeting outcomes, not aggregate counts: the caller keys its tracking store by
     * {@code (meetingKey, iCalUId)} and cannot do that from a total. The HTTP batch route returned
     * an array the caller had to parse for exactly those two fields; the ESB reply carries them as
     * {@code UpsertResultProto} rows, with each row's own {@code errorCode}.
     *
     * <p><b>One outcome per non-null input meeting, always (EF-28.)</b> A chunk that Meeting Agent
     * refused, or that never reached it, contributes a FAILED outcome for each of ITS meetings rather
     * than contributing nothing. Before that, a batch large enough to be chunked could lose a whole
     * chunk silently: the surviving chunks made the result non-empty, and the missing rows read as
     * Meeting Agent's per-meeting registration gate. {@link UpsertOutcome#caUnreached()} separates
     * "we could not ask" from "Meeting Agent said no".
     *
     * @param meetings the batch; MA admits at most
     *                 {@code ca.esb.bulkRequest.maxMeetings} (default 50) per request and the
     *                 implementation chunks to that rather than making the caller do it
     * @return one outcome per non-null input meeting; empty only when the input was empty or the
     *         invoker reports no workspace
     */
    List<UpsertOutcome> bulkUpsertMeetings(List<CreateMeetingRequestDto> meetings);

    // ── A3 — cancel/delete ────────────────────────────────────────────────

    /**
     * A3 — deletes a meeting MA holds.
     *
     * <p>"Not found" is success, not an error, on both transports: HTTP 404 was already treated
     * that way, and the ESB reply says so explicitly with a blank {@code errorCode} and
     * {@code deleted=false}.
     *
     * @param meetingKey the MA meeting key
     * @param iCalUId    for logs and for MA's own verification; may be null
     * @param reason     why, for MA's audit trail
     * @return true when MA accepted the delete (including "there was nothing to delete")
     */
    boolean deleteMeeting(String meetingKey, String iCalUId, String reason);

    // ── A5 — find ─────────────────────────────────────────────────────────

    /**
     * The inputs of the cancellation lookup, all optional.
     *
     * <p>On the ESB every field is supplied in ONE request and MA applies them in the documented
     * precedence ({@code iCalUId} → {@code (meetingUrl, startTime)} → {@code (meetingUrl,
     * search)}), returning the first non-empty match set. The HTTP path this replaces could only
     * ever ask by {@code (meetingUrl, startTime)} — and has been answering 404 since it was
     * written, because the route it calls has never existed on MA (spec §6 R14).
     *
     * @param iCalUId    the calendar event's stable identifier — the strongest key, and the one
     *                   the HTTP path could not use at all
     * @param meetingUrl the join URL
     * @param startTime  the occurrence's UTC start
     * @param search     the normalised subject, supplied ONLY for a series cancellation — a title
     *                   search is far too broad to run on a single occurrence
     * @param userEmail  scopes the search to one participant
     */
    record MeetingLookupCriteria(String iCalUId, String meetingUrl, String startTime, String search,
                                 String userEmail) {
    }

    /**
     * The result of one cancellation lookup.
     *
     * <p><b>{@code meetingKeys} is ONE page of results and is not promised to be exhaustive</b> —
     * see {@link #findMeetingKeys(MeetingLookupCriteria)} for why that is the contract rather than
     * an omission. Do not read a short set as "MA holds no more than this"; read it as "these are
     * the strongest matches MA returned first".
     *
     * @param meetingKeys the matched keys, in MA's order, capped at one page; never null
     * @param matchedBy   which rule fired — one of {@code ICAL_UID}, {@code URL_AND_START},
     *                    {@code URL_AND_SEARCH}, {@code FILTERS} — or null when nothing matched
     */
    record MeetingKeyMatch(Set<String> meetingKeys, String matchedBy) {

        public static MeetingKeyMatch none() {
            return new MeetingKeyMatch(Set.of(), null);
        }

        public boolean isEmpty() {
            return meetingKeys == null || meetingKeys.isEmpty();
        }

        /**
         * @return the single best key, or null when nothing matched — the shape the one caller
         *         needs, which deletes exactly one meeting per cancelled occurrence
         */
        public String firstKey() {
            return isEmpty() ? null : meetingKeys.iterator().next();
        }
    }

    /**
     * A5 — resolves a cancelled calendar event to the MA meetings it stands for.
     *
     * <h2>Single page, by contract</h2>
     *
     * <p>This reads the FIRST page of MA's answer and stops. The reply carries a
     * {@code nextOffset} and implementations deliberately do not follow it, so
     * {@link MeetingKeyMatch#meetingKeys()} holds at most one page.
     *
     * <p>That is sufficient because of what the answer is FOR: the one caller cancels a calendar
     * occurrence and takes {@link MeetingKeyMatch#firstKey()} — exactly one meeting per cancelled
     * occurrence. MA applies the criteria in precedence order ({@code iCalUId} →
     * {@code (meetingUrl, startTime)} → {@code (meetingUrl, search)}) and returns the first
     * non-empty match set, so the strongest match is on page one by construction; a page-two key
     * could only ever be a weaker match for an occurrence that already has a stronger one. Paging
     * would cost a round trip per page on the cancellation path to surface keys no caller reads.
     *
     * <p>If a future caller ever needs the complete set — a series-wide cancellation that must
     * delete every occurrence, say — that caller needs paging added HERE, following
     * {@code nextOffset} the way the seat list already does. Do not infer completeness from a
     * short result set in the meantime.
     *
     * @param criteria the lookup inputs
     * @return the match — one page of keys, never null
     */
    MeetingKeyMatch findMeetingKeys(MeetingLookupCriteria criteria);

    // ── C1 — identity ─────────────────────────────────────────────────────

    /**
     * C1 — the workspace's ACTIVE, licensed Meeting Agent seats.
     *
     * <p>The two conditions are re-applied client-side by the implementation, exactly as the HTTP
     * path already did: the filter is the whole meaning of the roster, and an MA that ignored the
     * request's {@code status} would otherwise widen this tenant's scan silently.
     *
     * @return every seat's lower-cased address, or empty when ANY page failed — a partial roster is
     *         indistinguishable from a shrunken one, and acting on it would drop real seats and
     *         silently stop capturing their meetings
     */
    Optional<Set<String>> listLicensedSeatEmails();

    // ── C4 — scan inventory ───────────────────────────────────────────────

    /**
     * C4 — replaces this workspace's scanned-mailbox inventory on Meeting Agent with one sweep's
     * snapshot.
     *
     * <p>Best-effort: this is an observability surface and the capture path never waits on it. An
     * empty snapshot is sent only when it carries a {@code degradedReason} — a degraded Always On
     * sweep scanned nothing and reports why; Meeting Agent stores the reason and prunes nothing.
     * An empty snapshot without a reason is refused here, because it would tell Meeting Agent
     * nothing.
     *
     * @param snapshot one sweep's rows
     * @return true when Meeting Agent accepted the snapshot
     */
    boolean reportScannedMailboxes(ScannedMailboxSnapshot snapshot);
}
