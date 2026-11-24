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

import app.krista.conversationagent.module.calendar.esb.ExtensionCalendarServiceClient;
import app.krista.conversationagent.module.calendar.esb.dto.BulkUpsertMeetingsReply;
import app.krista.conversationagent.module.calendar.esb.dto.BulkUpsertMeetingsRequest;
import app.krista.conversationagent.module.calendar.esb.dto.CancelMeetingReply;
import app.krista.conversationagent.module.calendar.esb.dto.CancelMeetingRequest;
import app.krista.conversationagent.module.calendar.esb.dto.FindMeetingsReply;
import app.krista.conversationagent.module.calendar.esb.dto.FindMeetingsRequest;
import app.krista.conversationagent.module.calendar.esb.dto.UpsertMeetingReply;
import app.krista.conversationagent.module.calendar.esb.dto.UpsertMeetingRequest;
import app.krista.conversationagent.module.shared.esb.ExtErrorCodes;
import app.krista.conversationagent.module.shared.esb.ext.ExtensionIdentityServiceClient;
import app.krista.conversationagent.module.shared.esb.ext.dto.ListUsersReply;
import app.krista.conversationagent.module.shared.esb.ext.dto.ListUsersRequest;
import app.krista.conversationagent.module.shared.esb.ext.dto.ScannedMailboxSnapshotReply;
import app.krista.conversationagent.module.shared.esb.ext.dto.ScannedMailboxSnapshotRequest;
import app.krista.conversationagent.module.shared.proto.MeetingViewProto;
import app.krista.conversationagent.module.shared.proto.NativeMeetingProto;
import app.krista.conversationagent.module.shared.proto.PersonProto;
import app.krista.conversationagent.module.shared.proto.ScannedMailboxProto;
import app.krista.conversationagent.module.shared.proto.UpsertResultProto;
import app.krista.conversationagent.module.shared.proto.UserViewProto;
import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model.CreateMeetingRequestDto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model.Person;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.ScannedMailboxSnapshot;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The {@link CaGateway} over the ESB — the ONLY implementation, and the only way this extension
 * talks to Meeting Agent (KIM-492 WP-E16; the {@code caTransport} attribute and the HTTP gateway
 * beside it were struck by the product owner on 2026-09-11 and deleted).
 *
 * <p>Every method builds a request from the staged contract jar, calls the generated client, and
 * maps the reply back into this extension's own model types. The generated clients are created per
 * call and closed: {@code ServiceClientBase} holds no connection of its own (the broker connection
 * is cached by {@code host:port} inside krista-infra), so construction is cheap and closing is what
 * releases any requester a failed call left behind.
 *
 * <h2>Reading a reply — the ONE rule</h2>
 *
 * <p>Branch on {@code errorCode}, on nothing else:
 * <ul>
 *   <li><b>blank</b> — success.</li>
 *   <li><b>populated</b> — that {@code ExtErrorCodes} constant is MA's answer.</li>
 *   <li><b>null, or a null reply</b> — the call never reached MA (timeout, no marshaller, broker
 *       down). The generated client catches its own exception, returns a
 *       <em>default-constructed</em> reply and fills only {@code ReplyBase.errorMessage}; and
 *       {@code execAPI} returns {@code null} outright when no response object came back.</li>
 * </ul>
 *
 * <p>{@code ReplyBase.ok} and {@code errorMessage} carry no {@code @ProtoField} and therefore never
 * cross the wire — a decoded reply always reads {@code isOk() == true} no matter what MA decided.
 * Reading {@code isOk()} would report every failure as a success, and string-matching
 * {@code errorMessage} would read a field that is empty on every remote answer. Both are banned.
 *
 * <h2>Failure posture</h2>
 *
 * <p>No method throws. Meeting Agent being unreachable degrades a sweep, never aborts it — the
 * behaviour every call site already had over HTTP.
 *
 * <p>Thread-safe: stateless apart from the injected {@link Invoker}.
 */
@Service
@Singleton
public class EsbCaGateway implements CaGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(EsbCaGateway.class);

    /**
     * MA's cap on {@code bulkUpsertMeetings}; over it the whole batch is rejected with
     * {@code VALIDATION_ERROR}. Chunked here rather than at the call site so a caller that batches
     * differently cannot trip it.
     *
     * <p>EF-29: read from {@value CaCallBudget#MAX_BULK_KEY} rather than compiled in, so a Meeting
     * Agent that raises its cap does not need an extension release. The constant remains as the
     * default and as the value tests assert against.
     */
    static int maxMeetingsPerBulk() {
        return CaCallBudget.maxMeetingsPerBulk();
    }

    /** Page size for the A5 listing. Half MA's cap of 200, so a future cap change cannot break it. */
    static final int LIST_PAGE_SIZE = 100;

    /** MA's cap on C1 {@code limit} ({@code UserService.MAX_LIST_LIMIT}). */
    static final int SEAT_PAGE_SIZE = 1000;

    /**
     * Hard stop on every paging loop. Not a timeout and not tuning — a remote cursor that never
     * reports exhaustion would otherwise spin forever, and spec §3.7 forbids an unbounded loop on
     * either side. Truncation is logged loudly and treated as failure, because the one paged read
     * here is the seat roster and a partial roster is indistinguishable from a shrunken one.
     */
    static final int MAX_PAGES = 100;

    /** Projection constant, mirrored from the contract (spec §3.7). */
    static final String PROJECTION_KEYS = "KEYS";

    /**
     * Reply budget for the scan snapshot, deliberately shorter than a bulk call's. The report runs
     * at the tail of a sweep and must not stall one when the broker is away, and giving up on the
     * REPLY loses nothing: a request already on the queue still replaces the inventory.
     */
    static final long SNAPSHOT_REPLY_BUDGET_MILLIS = 10_000L;

    /** The only status the cancellation lookup ever asks for, unchanged from the HTTP route. */
    private static final String STATUS_SCHEDULED = "SCHEDULED";

    private final Invoker invoker;

    @Inject
    public EsbCaGateway(Invoker invoker) {
        this.invoker = invoker;
        CaContractProtoRegistration.registerAll();
    }

    // ── A1 — upsertMeeting ────────────────────────────────────────────────

    @Override
    public UpsertOutcome upsertMeeting(CreateMeetingRequestDto meeting) {
        if (meeting == null)
            return UpsertOutcome.failed(ExtErrorCodes.VALIDATION_ERROR, "no meeting payload");

        String workspaceId = workspaceId();
        if (workspaceId == null)
            return UpsertOutcome.failed(ExtErrorCodes.VALIDATION_ERROR,
                    "this invoker reports no workspace id");

        UpsertMeetingRequest request = new UpsertMeetingRequest();
        request.setWorkspaceId(workspaceId);
        request.setCorrelationId(newCorrelationId());
        request.setInvokerId(invokerId(meeting));
        request.setMeeting(toNativeMeeting(meeting));

        UpsertMeetingReply reply = onCalendar("upsertMeeting", CaCallBudget.readMillis(),
                client -> client.upsertMeeting(request));
        Verdict verdict = verdict(reply == null ? null : reply.getErrorCode());

        if (verdict.unreached()) {
            LOGGER.warn("The upsert of iCalUId={} never reached Meeting Agent.", meeting.getiCalUId());
            return UpsertOutcome.failed(null, "the upsert never reached Meeting Agent");
        }
        if (verdict.failed()) {
            return new UpsertOutcome(statusFor(verdict.errorCode()), null, meeting.getiCalUId(), false,
                    verdict.errorCode(), reply.getErrorMessage());
        }
        return new UpsertOutcome(UpsertStatus.UPSERTED, normalise(reply.getMeetingKey()),
                normalise(reply.getICalUId()), reply.isCreated(), null, null);
    }

    // ── A2 — bulkUpsertMeetings ───────────────────────────────────────────

    @Override
    public List<UpsertOutcome> bulkUpsertMeetings(List<CreateMeetingRequestDto> meetings) {
        if (meetings == null || meetings.isEmpty())
            return List.of();

        String workspaceId = workspaceId();
        if (workspaceId == null) {
            LOGGER.warn("Cannot upsert a batch of {} meeting(s): this invoker reports no workspace id.",
                    meetings.size());
            return List.of();
        }

        List<CreateMeetingRequestDto> present = meetings.stream()
                .filter(Objects::nonNull)
                .toList();
        if (present.isEmpty())
            return List.of();

        // Chunked to MA's cap here rather than at the call site: the sync path batches by its own
        // page size, and a batch over the cap is rejected WHOLE, so one oversized page would lose
        // every meeting on it rather than the surplus.
        int chunkSize = maxMeetingsPerBulk();
        List<UpsertOutcome> outcomes = new ArrayList<>(present.size());
        for (int from = 0; from < present.size(); from += chunkSize) {
            List<CreateMeetingRequestDto> chunk =
                    present.subList(from, Math.min(from + chunkSize, present.size()));
            outcomes.addAll(bulkChunk(workspaceId, chunk));
        }
        return outcomes;
    }

    /**
     * EF-28: one FAILED outcome per meeting of a chunk the batch never got past.
     *
     * <p>A chunk-level failure used to return an EMPTY list, which the batch then concatenated with
     * whatever the surviving chunks produced. The caller's only aggregate signal —
     * {@code !outcomes.isEmpty()} — therefore read a partial batch as a clean one, and its log line
     * attributed the missing rows to Meeting Agent's per-meeting {@code NO_REGISTERED_USER} gate.
     * Two successful chunks were enough to mask a third that never reached Meeting Agent at all.
     *
     * <p>Naming every meeting keeps the invariant the caller can actually use: {@code bulkUpsertMeetings}
     * returns exactly one outcome per non-null input, so "fewer outcomes than meetings" stops being
     * a state anyone has to interpret. The outcomes are FAILED, which is not
     * {@code acceptedByCa()}, so nothing gets tracked and the next sweep re-offers them — the
     * pre-existing behaviour, now reached deliberately rather than by an empty list.
     *
     * @param chunk     the meetings that were in the failed request
     * @param errorCode Meeting Agent's refusal code, or null when the call never reached it
     * @param message   for logs only
     */
    private static List<UpsertOutcome> chunkFailure(List<CreateMeetingRequestDto> chunk,
            String errorCode, String message) {
        List<UpsertOutcome> outcomes = new ArrayList<>(chunk.size());
        for (CreateMeetingRequestDto meeting : chunk) {
            outcomes.add(new UpsertOutcome(UpsertStatus.FAILED, null, meeting.getiCalUId(), false,
                    errorCode, message));
        }
        return outcomes;
    }

    private List<UpsertOutcome> bulkChunk(String workspaceId, List<CreateMeetingRequestDto> chunk) {
        BulkUpsertMeetingsRequest request = new BulkUpsertMeetingsRequest();
        request.setWorkspaceId(workspaceId);
        request.setCorrelationId(newCorrelationId());
        // FR-002 attribution, threaded into every per-meeting upsert. The HTTP batch route carried
        // it per DTO; the ESB request carries it once for the batch.
        request.setInvokerId(invokerId(chunk.get(0)));
        request.setMeetings(chunk.stream().map(EsbCaGateway::toNativeMeeting).toList());

        BulkUpsertMeetingsReply reply = onCalendar("bulkUpsertMeetings", CaCallBudget.bulkMillis(),
                client -> client.bulkUpsertMeetings(request));
        Verdict verdict = verdict(reply == null ? null : reply.getErrorCode());

        if (verdict.unreached()) {
            LOGGER.warn("Batch of {} meeting(s) never reached Meeting Agent.", chunk.size());
            return chunkFailure(chunk, null, "the batch never reached Meeting Agent");
        }
        if (verdict.failed()) {
            // A whole-request rejection (over the cap, unregistered workspace, …). Every meeting in
            // the chunk is named (EF-28): returning an empty list here let two healthy chunks mask a
            // third, and reported the gap as Meeting Agent's per-meeting registration gate.
            LOGGER.warn("Meeting Agent refused the batch of {} meeting(s): {} {}",
                    chunk.size(), verdict.errorCode(), reply.getErrorMessage());
            return chunkFailure(chunk, verdict.errorCode(), reply.getErrorMessage());
        }

        List<UpsertResultProto> results = reply.getResults();
        if (results == null || results.isEmpty()) {
            // MA answered cleanly and reported on nothing. Same shape of surprise as a refusal from
            // the caller's point of view, so it is named the same way rather than vanishing.
            LOGGER.warn("Meeting Agent accepted the batch of {} meeting(s) but reported no "
                    + "per-meeting results.", chunk.size());
            return chunkFailure(chunk, null, "Meeting Agent reported no per-meeting results");
        }

        List<UpsertOutcome> outcomes = new ArrayList<>(results.size());
        for (UpsertResultProto result : results) {
            // A ROW's errorCode is always decoded — the batch reply itself already arrived — so
            // blank is the only success signal here and UNREACHED cannot occur.
            String rowError = normalise(result.getErrorCode());
            outcomes.add(rowError == null
                    ? new UpsertOutcome(UpsertStatus.UPSERTED, normalise(result.getMeetingKey()),
                            normalise(result.getICalUId()), result.isCreated(), null, null)
                    : new UpsertOutcome(statusFor(rowError), null, normalise(result.getICalUId()), false,
                            rowError, result.getErrorMessage()));
        }
        return outcomes;
    }

    // ── A3 — cancelMeeting ────────────────────────────────────────────────

    @Override
    public boolean deleteMeeting(String meetingKey, String iCalUId, String reason) {
        String workspaceId = workspaceId();
        if (workspaceId == null) {
            LOGGER.warn("Cannot delete meetingKey={}: this invoker reports no workspace id.", meetingKey);
            return false;
        }

        CancelMeetingRequest request = new CancelMeetingRequest();
        request.setWorkspaceId(workspaceId);
        request.setCorrelationId(newCorrelationId());
        request.setInvokerId(invokerId(null));
        request.setMeetingKey(meetingKey);
        request.setICalUId(iCalUId);
        request.setReason(reason);

        CancelMeetingReply reply = onCalendar("cancelMeeting", CaCallBudget.readMillis(),
                client -> client.cancelMeeting(request));
        Verdict verdict = verdict(reply == null ? null : reply.getErrorCode());

        if (verdict.unreached()) {
            LOGGER.warn("MA delete for meetingKey={} never reached Meeting Agent.", meetingKey);
            return false;
        }
        if (verdict.failed()) {
            LOGGER.warn("MA delete failed for meetingKey={}: {} {}",
                    meetingKey, verdict.errorCode(), reply.getErrorMessage());
            return false;
        }

        // deleted=false with a blank errorCode is MA saying "there was nothing here" — success, and
        // exactly what HTTP 404 already meant on this path. A meeting whose capture is armed comes
        // back deleted=false, botCancelInitiated=true: the teardown is under way, also success.
        LOGGER.info("MA delete accepted for meetingKey={} deleted={} botCancelInitiated={}",
                meetingKey, reply.isDeleted(), reply.isBotCancelInitiated());
        return true;
    }

    // ── A5 — findMeetings ─────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Reads ONE page — {@code limit=LIST_PAGE_SIZE}, {@code offset=0} — and never
     * follows the reply's {@code nextOffset}, per the single-page contract on
     * {@link CaGateway#findMeetingKeys(MeetingLookupCriteria)}. MA returns its strongest
     * non-empty match set first and the one caller takes {@link MeetingKeyMatch#firstKey()}, so a
     * second round trip on the cancellation path could only surface weaker keys nobody reads.
     * Contrast {@link #listLicensedSeatEmails()}, which DOES page: there the complete set is the
     * answer.
     */
    @Override
    public MeetingKeyMatch findMeetingKeys(MeetingLookupCriteria criteria) {
        if (criteria == null)
            return MeetingKeyMatch.none();

        String workspaceId = workspaceId();
        if (workspaceId == null) {
            LOGGER.warn("Cannot look up meeting keys: this invoker reports no workspace id.");
            return MeetingKeyMatch.none();
        }

        // ONE request carrying every criterion. MA walks the precedence iCalUId, then
        // (meetingUrl, startTime), then (meetingUrl, search), and returns the first non-empty match
        // set plus the rule that fired. The HTTP path could only ever ask by (meetingUrl,
        // startTime) — against a route MA has never served — so supplying iCalUId here is both the
        // strongest key and the reason cancellations start working at all (spec §6 R14).
        FindMeetingsRequest request = new FindMeetingsRequest();
        request.setWorkspaceId(workspaceId);
        request.setCorrelationId(newCorrelationId());
        request.setInvokerId(invokerId(null));
        request.setICalUId(criteria.iCalUId());
        request.setMeetingUrl(criteria.meetingUrl());
        request.setStartTime(criteria.startTime());
        request.setSearch(criteria.search());
        request.setUserEmail(criteria.userEmail());
        request.setStatus(STATUS_SCHEDULED);
        request.setProjection(PROJECTION_KEYS);
        // One page, deliberately: the reply's nextOffset is not followed here. See the javadoc
        // above and on CaGateway.findMeetingKeys for why completeness is not the answer this
        // caller needs.
        request.setLimit(LIST_PAGE_SIZE);
        request.setOffset(0);

        FindMeetingsReply reply = onCalendar("findMeetings", CaCallBudget.readMillis(),
                client -> client.findMeetings(request));
        Verdict verdict = verdict(reply == null ? null : reply.getErrorCode());

        if (verdict.unreached()) {
            LOGGER.warn("MA meeting lookup never reached Meeting Agent.");
            return new MeetingKeyMatch(Set.of(), "FAILED");
        }
        if (verdict.failed()) {
            LOGGER.warn("MA meeting lookup failed: {} {}", verdict.errorCode(), reply.getErrorMessage());
            return new MeetingKeyMatch(Set.of(), "FAILED");
        }

        Set<String> keys = new LinkedHashSet<>();
        for (MeetingViewProto meeting : safeList(reply.getMeetings())) {
            String key = normalise(meeting.getMeetingKey());
            if (key != null)
                keys.add(key);
        }
        LOGGER.info("MA meeting lookup matchedBy={} matched {} meetingKey(s)",
                reply.getMatchedBy(), keys.size());
        return new MeetingKeyMatch(keys, normalise(reply.getMatchedBy()));
    }

    // ── C1 — listUsers ────────────────────────────────────────────────────

    @Override
    public Optional<Set<String>> listLicensedSeatEmails() {
        String workspaceId = workspaceId();
        if (workspaceId == null) {
            LOGGER.warn("Cannot fetch the Meeting Agent seat list: this invoker reports no workspace id.");
            return Optional.empty();
        }

        Set<String> emails = new LinkedHashSet<>();
        int offset = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            ListUsersRequest request = new ListUsersRequest();
            request.setWorkspaceId(workspaceId);
            request.setCorrelationId(newCorrelationId());
            request.setInvokerId(invokerId(null));
            // ACTIVE is requested explicitly because MA's default is EVERY status; the same two
            // conditions are then re-applied per row below, so the roster's meaning does not depend
            // on which MA version answered.
            request.setStatus("ACTIVE");
            request.setLimit(SEAT_PAGE_SIZE);
            request.setOffset(offset);

            ListUsersReply reply = onIdentity("listUsers", CaCallBudget.bulkMillis(),
                    client -> client.listUsers(request));
            Verdict verdict = verdict(reply == null ? null : reply.getErrorCode());

            if (verdict.unreached()) {
                LOGGER.warn("The seat list for workspace {} never reached Meeting Agent (page {}).",
                        workspaceId, page);
                return Optional.empty();
            }
            if (verdict.failed()) {
                LOGGER.warn("Meeting Agent refused the seat list for workspace {} (page {}): {} {}",
                        workspaceId, page, verdict.errorCode(), reply.getErrorMessage());
                return Optional.empty();
            }

            for (UserViewProto user : safeList(reply.getUsers())) {
                String email = seatAddress(user);
                if (email != null)
                    emails.add(email);
            }

            int nextOffset = reply.getNextOffset();
            if (nextOffset < 0)
                return Optional.of(emails);
            if (nextOffset <= offset) {
                LOGGER.warn("Meeting Agent returned nextOffset={} at offset={} for the seat list of"
                                + " workspace {} — the cursor is not advancing. The roster is INCOMPLETE,"
                                + " so it is not stored.", nextOffset, offset, workspaceId);
                return Optional.empty();
            }
            offset = nextOffset;
        }

        LOGGER.warn("Stopped reading the Meeting Agent seat list for workspace {} after {} pages. The"
                        + " roster is INCOMPLETE, so it is not stored — the previous set stays in force.",
                workspaceId, MAX_PAGES);
        return Optional.empty();
    }

    // ── C4 — reportScannedMailboxes ───────────────────────────────────────

    @Override
    public boolean reportScannedMailboxes(ScannedMailboxSnapshot snapshot) {
        // An empty snapshot goes out only when it carries a degraded reason: that is how a sweep
        // that scanned nothing says why, and Meeting Agent prunes nothing on it. Empty without a
        // reason would tell Meeting Agent nothing it acts on.
        if (snapshot == null || (snapshot.isEmpty() && normalise(snapshot.degradedReason()) == null)) {
            LOGGER.debug("Skipping the mailbox snapshot: nothing to report.");
            return false;
        }
        String workspaceId = workspaceId();
        if (workspaceId == null) {
            LOGGER.debug("Skipping the mailbox snapshot: this invoker reports no workspace id.");
            return false;
        }

        ScannedMailboxSnapshotRequest request = new ScannedMailboxSnapshotRequest();
        request.setWorkspaceId(workspaceId);
        request.setCorrelationId(newCorrelationId());
        request.setInvokerId(invokerId(null));
        request.setMode(snapshot.mode());
        request.setDegradedReason(snapshot.degradedReason());
        request.setSweptAt(snapshot.sweptAt());
        request.setMailboxes(snapshot.mailboxes().stream()
                .map(EsbCaGateway::toScannedMailbox)
                .toList());

        ScannedMailboxSnapshotReply reply = onIdentity("reportScannedMailboxes",
                SNAPSHOT_REPLY_BUDGET_MILLIS, client -> client.reportScannedMailboxes(request));
        Verdict verdict = verdict(reply == null ? null : reply.getErrorCode());

        if (verdict.unreached()) {
            LOGGER.warn("The mailbox snapshot for workspace {} was not confirmed by Meeting Agent.",
                    workspaceId);
            return false;
        }
        if (verdict.failed()) {
            LOGGER.warn("Meeting Agent refused the mailbox snapshot for workspace {}: {} {}",
                    workspaceId, verdict.errorCode(), reply.getErrorMessage());
            return false;
        }
        LOGGER.info("Reported {} scanned mailbox(es) to Meeting Agent (accepted={}, pruned={}, truncated={})",
                snapshot.size(), reply.getAccepted(), reply.getPruned(), reply.isTruncated());
        return true;
    }

    private static ScannedMailboxProto toScannedMailbox(ScannedMailboxSnapshot.Mailbox mailbox) {
        ScannedMailboxProto proto = new ScannedMailboxProto();
        proto.setEmail(mailbox.email());
        proto.setCalendarSystem(mailbox.calendarSystem());
        proto.setScopeReason(mailbox.scopeReason());
        proto.setSubscriptionState(mailbox.subscriptionState());
        proto.setSubscriptionId(mailbox.subscriptionId());
        proto.setDisplayLabel(mailbox.displayLabel());
        return proto;
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private <R> R onCalendar(String apiName, long replyBudgetMillis,
            Function<ExtensionCalendarServiceClient, R> call) {
        return invoke(apiName, () -> calendarClient(replyBudgetMillis), call);
    }

    private <R> R onIdentity(String apiName, long replyBudgetMillis,
            Function<ExtensionIdentityServiceClient, R> call) {
        return invoke(apiName, () -> identityClient(replyBudgetMillis), call);
    }

    /**
     * Creates a client for {@code ca.calendar-1-IN}, bounded to this call's reply budget.
     *
     * <p>The DEFAULT config, deliberately, not {@code forTenant(...)}: the generated client's tenant
     * routing would compute {@code ca.calendar::<workspace>} and MA binds no such destination.
     * Tenant identity travels as the {@code workspaceId} field in the payload and is verified
     * MA-side against the registered invoker (spec §1.4) — the destination is shared.
     *
     * <p>Overridable so the mapping tests can drive a stub client without a broker, so the
     * round-trip test can point one at a throwaway destination, and so
     * {@code CaGatewayTimeoutBudgetTest} can capture the budget every operation asks for.
     *
     * @param replyBudgetMillis how long this call may wait for its reply
     * @return a fresh client; the caller closes it
     */
    protected ExtensionCalendarServiceClient calendarClient(long replyBudgetMillis) {
        return new ExtensionCalendarServiceClient(
                withReplyBudget(ExtensionCalendarServiceClient.config(), replyBudgetMillis));
    }

    /**
     * Creates a client for {@code ca.identity-1-IN}. See {@link #calendarClient(long)}.
     *
     * @param replyBudgetMillis how long this call may wait for its reply
     * @return a fresh client; the caller closes it
     */
    protected ExtensionIdentityServiceClient identityClient(long replyBudgetMillis) {
        return new ExtensionIdentityServiceClient(
                withReplyBudget(ExtensionIdentityServiceClient.config(), replyBudgetMillis));
    }

    /**
     * EF-02: a copy of the service's config whose reply wait is this call's budget.
     *
     * <p>A COPY, never the shared static the generated client hands out: {@code config()} returns the
     * one instance every client of that service is built from, so mutating it in place would make the
     * last budget set win for every subsequent call on every thread.
     *
     * <p>This is where the budget lands because this is where krista-infra reads it.
     * {@code QRequester.receive()} waits {@code replyOverride != null ? replyOverride :
     * config.getResponseTimeout()}, and {@code producerGateBudget} falls through to the same value.
     * {@code Input.setTimeout(...)} — the route the reply-override half exists for — is NOT usable
     * here: it is a {@code default} interface method with an empty body, and Meeting Agent's
     * generated request DTOs implement {@code Input} without overriding it, so the call would store
     * nothing and the shipped ten-minute {@code broker.response.timeout} would stay in force. See
     * {@link CaCallBudget} for the verification.
     *
     * @param base              the service's default config
     * @param replyBudgetMillis the budget, already clamped by {@link CaCallBudget}
     * @return a copy carrying the budget
     */
    static app.krista.infra.esb.ServiceBusConfig withReplyBudget(
            app.krista.infra.esb.ServiceBusConfig base, long replyBudgetMillis) {
        return base.copy().setResponseTimeout(java.time.Duration.ofMillis(replyBudgetMillis));
    }

    /**
     * Runs one client call, guaranteeing the client is closed and that nothing escapes.
     *
     * <p>The generated client already converts its own exceptions into an error reply, so a
     * {@code null} here means either {@code execAPI} returned no response object at all or the
     * client itself could not be constructed — both "the call did not happen", which is precisely
     * what the callers treat a null reply as.
     */
    private <C extends AutoCloseable, R> R invoke(String apiName, Supplier<C> factory,
            Function<C, R> call) {

        C client = null;
        try {
            client = factory.get();
            return call.apply(client);
        }
        catch (Exception failure) {
            LOGGER.warn("ESB call {} to Meeting Agent could not be made: {}", apiName,
                    failure.getMessage(), failure);
            return null;
        }
        finally {
            if (client != null) {
                try {
                    client.close();
                }
                catch (Exception closeFailure) {
                    LOGGER.debug("Could not close the {} client: {}", apiName, closeFailure.getMessage());
                }
            }
        }
    }

    /**
     * Classifies a populated {@code errorCode} into the outcome the caller acts on.
     *
     * <p>Only the two MA decisions the HTTP path already distinguished get their own status;
     * everything else — {@code VALIDATION_ERROR}, {@code WORKSPACE_NOT_REGISTERED},
     * {@code INTERNAL_ERROR}, an unknown future code — is a plain failure. An unknown code degrading
     * to FAILED is deliberate: the safe reading of "MA said something this build has never heard of"
     * is "this did not work", not "this was fine".
     */
    private static UpsertStatus statusFor(String errorCode) {
        if (ExtErrorCodes.MEETING_SKIPPED_CANCELLED.equals(errorCode))
            return UpsertStatus.SKIPPED_CANCELLED;
        if (ExtErrorCodes.NO_REGISTERED_USER.equals(errorCode))
            return UpsertStatus.NO_REGISTERED_USER;
        return UpsertStatus.FAILED;
    }

    /**
     * Applies the seat rule to one row of MA's answer.
     *
     * @param user one row
     * @return the seat's lower-cased address, or null when the row is not an active licensed seat
     */
    private static String seatAddress(UserViewProto user) {
        if (user == null || !user.isLicensedSeat())
            return null;
        String status = user.getStatus();
        if (status == null || !"ACTIVE".equalsIgnoreCase(status.trim()))
            return null;
        return normalise(lower(user.getEmail()));
    }

    // ── mapping ───────────────────────────────────────────────────────────

    /**
     * Maps this extension's upsert payload onto the wire type.
     *
     * <p>{@code CreateMeetingRequestDto.calendarSystem} carries the token the HTTP body has always
     * sent under that name, and {@code NativeMeetingProto.calendarSystem} is the same field, so it
     * is passed through verbatim rather than translated.
     *
     * <p>{@code viaGroup} rides on {@link PersonProto} field 3 (WP-DL), so a member expanded out of
     * a Google Group keeps its provenance on the ESB exactly as it had it over HTTP.
     *
     * @param dto the payload
     * @return the wire message
     */
    static NativeMeetingProto toNativeMeeting(CreateMeetingRequestDto dto) {
        NativeMeetingProto proto = new NativeMeetingProto();
        proto.setTitle(dto.getTitle());
        proto.setMeetingUrl(dto.getMeetingUrl());
        proto.setStartTime(dto.getStartTime());
        proto.setEndTime(dto.getEndTime());
        proto.setTimezone(dto.getTimezone());
        proto.setOrganizer(toPerson(dto.getOrganizer()));
        proto.setAttendees(dto.getAttendees() == null
                ? List.of()
                : dto.getAttendees().stream()
                        .filter(Objects::nonNull)
                        .map(EsbCaGateway::toPerson)
                        .toList());
        proto.setCalendarSystem(dto.getCalendarSystem());
        proto.setRecurring(dto.isRecurring());
        proto.setRecurringSeriesId(dto.getRecurringSeriesId());
        proto.setBody(dto.getBody());
        proto.setICalUId(dto.getiCalUId());
        // Always false, and that is not an omission. Unlike the Teams extension, this one never
        // upserts a cancellation: a cancelled Google event goes down the A3 delete path
        // (handleCancelledEvent / deleteEventFromMA), and CreateMeetingRequestDto carries no
        // cancelled flag to read. Setting it from anywhere would need a field that does not exist.
        proto.setCancelled(false);
        return proto;
    }

    private static PersonProto toPerson(Person person) {
        if (person == null)
            return null;
        return new PersonProto(person.getEmail(), person.getName(), person.getViaGroup());
    }

    // ── small helpers ─────────────────────────────────────────────────────

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** How a reply is to be read. See {@link #verdict(String)} for why three states and not two. */
    private enum ReplyKind {
        /** The call never reached Meeting Agent. */
        UNREACHED,
        /** Meeting Agent answered, and it worked. */
        OK,
        /** Meeting Agent answered with an {@code ExtErrorCodes} constant. */
        ERROR
    }

    /**
     * @param kind      what happened
     * @param errorCode the {@code ExtErrorCodes} constant when {@link ReplyKind#ERROR}, else null
     */
    private record Verdict(ReplyKind kind, String errorCode) {

        boolean unreached() {
            return kind == ReplyKind.UNREACHED;
        }

        boolean failed() {
            return kind == ReplyKind.ERROR;
        }
    }

    /**
     * Classifies a reply's {@code errorCode} — the ONE legal way to read an outcome here.
     *
     * <p><b>The null/blank distinction is load-bearing and is not a style choice.</b> The generated
     * ProtoStream marshaller fills every string field absent from the wire with {@code ""} before
     * returning, so a reply that genuinely came back from Meeting Agent ALWAYS has a non-null
     * {@code errorCode} — {@code ""} when it succeeded, the constant when it did not.
     *
     * <p>A {@code null} therefore cannot have come off the wire. It means the object was never
     * decoded at all: the generated client caught its own exception and returned a
     * <em>default-constructed</em> reply with only {@code ReplyBase.errorMessage} set — a timeout,
     * an unreachable broker, a missing marshaller. Collapsing null into blank would read every one
     * of those as a success, which on the upsert path means a calendar sweep reporting meetings
     * captured that Meeting Agent never received.
     *
     * <p>{@code ReplyBase.ok} cannot help: it carries no {@code @ProtoField}, never crosses the
     * wire, and so reads {@code true} on every decoded reply regardless of the verdict.
     *
     * @param rawErrorCode the reply's {@code errorCode} exactly as returned, or null when there was
     *                     no reply object at all
     * @return the verdict, never null
     */
    private static Verdict verdict(String rawErrorCode) {
        if (rawErrorCode == null) {
            return new Verdict(ReplyKind.UNREACHED, null);
        }
        String trimmed = rawErrorCode.trim();
        return trimmed.isEmpty()
                ? new Verdict(ReplyKind.OK, null)
                : new Verdict(ReplyKind.ERROR, trimmed);
    }

    /**
     * proto3 has no field presence, so an absent string decodes as {@code ""}. Blank-to-null
     * normalisation happens here, once, at the door — the pattern spec §3.4 mandates.
     *
     * <p>Deliberately NOT used on {@code errorCode}: see {@link #verdict(String)}.
     */
    private static String normalise(String value) {
        if (value == null)
            return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private String newCorrelationId() {
        return UUID.randomUUID().toString();
    }

    private String workspaceId() {
        try {
            return normalise(invoker.getWorkspaceId());
        }
        catch (Exception unavailable) {
            LOGGER.warn("Could not read the workspace id: {}", unavailable.getMessage());
            return null;
        }
    }

    /**
     * KIM-474 (FR-002) attribution.
     *
     * <p>Prefers the id the caller already stamped on the payload — the sync and webhook paths both
     * do that before handing the DTO over, and using it keeps the attribution identical on both
     * transports. Falls back to reading the invoker. Informational on MA's side and never a gate, so
     * an invoker that cannot report its own id degrades to a null field rather than losing the call.
     *
     * @param stamped the payload whose {@code invokerId} to prefer, or null
     * @return the invoker id, or null
     */
    private String invokerId(CreateMeetingRequestDto stamped) {
        if (stamped != null) {
            String fromPayload = normalise(stamped.getInvokerId());
            if (fromPayload != null)
                return fromPayload;
        }
        try {
            return normalise(invoker.getInvokerId());
        }
        catch (Exception unavailable) {
            LOGGER.debug("Could not read the invoker id: {}", unavailable.getMessage());
            return null;
        }
    }
}
