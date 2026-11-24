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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar;

import app.krista.conversationagent.module.calendar.esb.CalendarSeatRegisteredCommandProto;
import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.Hk2ContextHolder;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarSyncTelemetryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.SendEventsToMAService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarWatchService;
import app.krista.infra.esb.service.ServiceEndpoint;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Locale;

/**
 * Reconciles the Always On scan set the moment Meeting Agent registers a licensed seat
 * (KIM-474 / EF-14), and — when the event asks for one — back-fills that seat's upcoming calendar.
 *
 * <p>The two halves answer different questions. The reconcile is roster-wide and idempotent, with a
 * scheduled pass behind it; the back-fill is per-mailbox and nothing retries it. See
 * {@link #backFillIfRequested}, which is where that distinction is argued.
 *
 * <p>Without this, a newly-licensed Google user waited for the next scheduled roster refresh before
 * their calendar was scanned or watched at all — and the durable
 * {@code ext.calendar.seat.registered.google} queue, which Meeting Agent has been producing to,
 * accumulated events with nothing consuming them.
 *
 * <h2>Foreign events are IGNORED, not merely logged</h2>
 *
 * <p>The destination is shared per calendar system and isolation is by the consumer's tenant
 * selector, so an event for another workspace should never arrive here. Should is not a guarantee:
 * this extension runs multi-tenant, and if that selector is ever wrong the reconcile would refresh
 * THIS workspace's roster in response to a stranger's seat — harmless in itself, but it would also
 * make a misconfigured selector invisible, because the reconcile succeeds either way. Comparing the
 * event's {@code workspaceId} against the invoker's own turns that into one WARN naming both ids.
 * teams-transcribe does not make this comparison (S-20); it should.
 *
 * <p>An event whose {@code workspaceId} is blank is treated as OURS. Meeting Agent populates it
 * today, and refusing an unattributed hint would mean a Meeting Agent that stops populating the field
 * silently disables the fast path — a worse failure than one redundant roster refresh.
 *
 * <h2>A failed reconcile is ACKNOWLEDGED, never rethrown</h2>
 *
 * <p>The queue is durable with {@code setProcessorAutoAcknowledge(false)}, so an escaping throwable
 * makes the broker redeliver the same event — and the conditions that fail this call (Meeting Agent
 * unreachable, Google refusing a delegated credential, a quota exhaustion) are exactly the conditions
 * that fail every redelivery. One seat registration would become an unbounded retry loop on top of a
 * scheduled sweep that already retries. <b>The scheduled reconcile IS the retry</b>: it is
 * idempotent, it only creates channels absent from the local store, and it will pick this seat up on
 * its next run. So the failure is logged at WARN, recorded on a metric, and the message acknowledged.
 *
 * <p>{@link Throwable} rather than {@link RuntimeException}: on a durable manual-ack queue an
 * {@code Error} — a {@code NoClassDefFoundError} from a mis-staged contract jar is the realistic one
 * — redelivers exactly like an exception, and a poison message that cannot be decoded would be
 * retried forever. teams-transcribe catches {@code RuntimeException} here (EF-27).
 *
 * @since 3.1.0
 */
@Service
public class GoogleCalendarSeatRegisteredHandler
        implements GoogleCalendarSeatRegisteredService, ServiceEndpoint {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GoogleCalendarSeatRegisteredHandler.class);

    /** Window used when a back-fill is requested without a usable day count. Matches Meeting Agent's. */
    static final long DEFAULT_BACK_FILL_DAYS = 30L;

    /** Ceiling on a requested back-fill window. Matches Meeting Agent's {@code MAX_DAYS}. */
    static final long MAX_BACK_FILL_DAYS = 365L;

    private final CalendarWatchService watchService;
    private final SendEventsToMAService sendEventsToMAService;
    private final CalendarDiscoveryService discoveryService;
    private final CalendarSyncTelemetryService telemetryService;
    private final Invoker invoker;
    private final Hk2ContextHolder contextHolder;

    @Inject
    public GoogleCalendarSeatRegisteredHandler(CalendarWatchService watchService,
                                               SendEventsToMAService sendEventsToMAService,
                                               CalendarDiscoveryService discoveryService,
                                               CalendarSyncTelemetryService telemetryService,
                                               Invoker invoker,
                                               Hk2ContextHolder contextHolder) {
        this.watchService = watchService;
        this.sendEventsToMAService = sendEventsToMAService;
        this.discoveryService = discoveryService;
        this.telemetryService = telemetryService;
        this.invoker = invoker;
        this.contextHolder = contextHolder;
    }

    /** Compatibility for standalone callers already running in an invoker context. HK2 uses the
     * injected constructor above, which always supplies the shared lifecycle context holder. */
    public GoogleCalendarSeatRegisteredHandler(CalendarWatchService watchService,
                                               SendEventsToMAService sendEventsToMAService,
                                               CalendarDiscoveryService discoveryService,
                                               CalendarSyncTelemetryService telemetryService,
                                               Invoker invoker) {
        this(watchService, sendEventsToMAService, discoveryService, telemetryService, invoker, null);
    }

    @Override
    public void googleCalendarSeatRegistered(CalendarSeatRegisteredCommandProto event) {
        if (contextHolder == null) {
            processSeatRegistered(event);
        } else if (!contextHolder.runWithCapturedContext(() -> processSeatRegistered(event))) {
            LOGGER.warn("[GCAL-SEAT-EVENT] Cannot process registration/sync without this invoker's "
                    + "lifecycle context. No watch or back-fill was attempted.");
        }
    }

    private void processSeatRegistered(CalendarSeatRegisteredCommandProto event) {
        if (event == null) {
            LOGGER.warn("[GCAL-SEAT-EVENT] Received a null seat notification — acknowledging.");
            return;
        }

        LOGGER.info("[GCAL-SEAT-EVENT] workspace={} email={} reason={} corr={}",
                event.getWorkspaceId(), event.getUserEmail(), event.getReason(),
                event.getCorrelationId());

        if (!isForThisWorkspace(event.getWorkspaceId())) {
            // Acknowledged, not requeued: redelivering an event that is not ours would loop, and
            // the workspace it IS for has its own consumer.
            LOGGER.warn("[GCAL-SEAT-EVENT] Ignoring a seat notification for workspace {} — this"
                            + " invoker serves {}. Consumer tenant isolation is by the consumer's"
                            + " tenant selector; a mismatch here means that selector is not doing"
                            + " its job, so it is reported rather than silently acted on.",
                    event.getWorkspaceId(), ownWorkspaceId());
            record("ignored_foreign_workspace", true);
            return;
        }

        try {
            // Gated on Always On inside the service: an opt-in workspace scans the users who
            // authorised their own calendars and a seat registration changes nothing for it.
            boolean reconciled = watchService.reconcileRegisteredSeatsNow();
            record(reconciled ? "reconciled" : "skipped_not_always_on", true);
        }
        catch (Throwable failure) {
            record("reconcile_failed", false);
            // Acknowledged deliberately — see the class javadoc. Rethrowing redelivers.
            LOGGER.warn("[GCAL-SEAT-EVENT] Immediate reconciliation failed; acknowledging the event."
                            + " The scheduled reconcile is the retry and will pick this seat up: {}",
                    failure.getMessage(), failure);
        }

        backFillIfRequested(event);
    }

    /**
     * Back-fills this seat's upcoming calendar when Meeting Agent asked for one.
     *
     * <h2>Why a watch channel is not enough</h2>
     *
     * <p>Everything above this line establishes a Google <em>watch channel</em>, and a watch channel
     * only delivers on CHANGE. A meeting already sitting on a newly seated user's calendar that
     * nobody subsequently edits produces no notification at all — so the user is registered, watched,
     * and invisible to Meeting Agent until one of their meetings happens to move. That was masked for
     * years by the "Connect My Calendar" conversation, which synced as a side effect; Always On
     * removes that conversation deliberately, and removed the only thing that populated a new user's
     * diary with it.
     *
     * <p>Note that {@code createWatchChannel} already walks the user's calendar to establish a
     * baseline sync token — and then keeps only the token. The events it saw are discarded. That is
     * the precise gap this closes, and it is why the fix is a sync call rather than a change to the
     * watch path.
     *
     * <h2>Per-mailbox, keyed on THIS event</h2>
     *
     * <p>The reconcile above is roster-wide and idempotent; this is per-mailbox work keyed on the
     * event's {@code userEmail}, and no later pass performs it — neither the scheduled seat reconcile
     * nor the renewal scheduler ever syncs, they only create missing channels. So a back-fill that is
     * skipped here is not picked up later, which is why it runs even when the reconcile above failed.
     *
     * <h2>Absent means "not asked for"</h2>
     *
     * <p>A back-fill is a Google read per seat, so it happens only on an explicit request. An event
     * from a Meeting Agent that predates the field carries neither {@code syncCalendar} nor
     * {@code syncDays} and lands here as {@code false} — today's watch-only behaviour, unchanged.
     *
     * <h2>A failure is acknowledged, and has no retry behind it</h2>
     *
     * <p>Same durable-queue reasoning as the reconcile. The difference worth stating plainly is that
     * the reconcile has an idempotent scheduled pass behind it and <b>this does not</b> — a lost
     * back-fill stays lost until something edits one of the user's meetings or an operator runs the
     * catalog sync by hand. Hence WARN naming the user, not DEBUG.
     *
     * <p>One Google-specific trap: {@code sendEventsToMAByUser} <b>returns quietly</b> when the user
     * is not syncable, where the Microsoft equivalent throws. A silent return would look identical to
     * a successful back-fill of an empty calendar, so syncability is checked here and reported
     * explicitly rather than inferred from the absence of an exception.
     */
    private void backFillIfRequested(CalendarSeatRegisteredCommandProto event) {
        if (!Boolean.TRUE.equals(event.getSyncCalendar())) {
            return;
        }

        String userEmail = event.getUserEmail();
        if (userEmail == null || userEmail.isBlank()) {
            LOGGER.warn("[GCAL-SEAT-EVENT] A back-fill was requested but the event carries no"
                    + " userEmail; there is no calendar to read. Acknowledging.");
            record("backfill_no_user", false);
            return;
        }
        userEmail = userEmail.trim();

        long days = resolveBackFillDays(event.getSyncDays());
        try {
            // sendEventsToMAByUser returns quietly for a non-syncable user, which is indistinguishable
            // from a successful back-fill of an empty calendar. Ask first so the log says which it was.
            if (!discoveryService.isSyncable(userEmail)) {
                record("backfill_not_syncable", false);
                LOGGER.warn("[GCAL-SEAT-EVENT] A back-fill was requested for {} but this extension"
                                + " cannot reach that calendar — under Always On the tenant could not"
                                + " be resolved, and under opt-in the user has not authorised their"
                                + " calendar. Their existing meetings stay invisible to Meeting Agent"
                                + " until one of them changes.",
                        userEmail);
                return;
            }
            sendEventsToMAService.sendEventsToMAByUser(userEmail, days);
            record("backfilled", true);
            LOGGER.info("[GCAL-SEAT-EVENT] Back-filled {} over the next {} day(s).", userEmail, days);
        }
        catch (Throwable failure) {
            record("backfill_failed", false);
            LOGGER.warn("[GCAL-SEAT-EVENT] Back-fill failed for {} over {} day(s); acknowledging the"
                            + " event. The watch channel is unaffected, but nothing retries a"
                            + " back-fill: this user's existing meetings stay invisible to Meeting"
                            + " Agent until one of them changes or an operator runs Calendar Sync By"
                            + " User. Cause: {}",
                    userEmail, days, failure.getMessage(), failure);
        }
    }

    /**
     * Clamps the requested window into something this extension is willing to ask Google for.
     *
     * <p>Meeting Agent already validates the field and rejects an out-of-range request with a 422, so
     * in practice nothing arrives here needing correction. It is clamped anyway because the producer
     * is a separately deployed service across a wire contract: a value this extension cannot serve is
     * better turned into a sane window with a WARN than into an exception that costs the seat its
     * back-fill entirely.
     */
    private long resolveBackFillDays(Integer requested) {
        if (requested == null || requested <= 0) {
            LOGGER.warn("[GCAL-SEAT-EVENT] A back-fill was requested with days={} — using the {}-day"
                    + " default instead.", requested, DEFAULT_BACK_FILL_DAYS);
            return DEFAULT_BACK_FILL_DAYS;
        }
        if (requested > MAX_BACK_FILL_DAYS) {
            LOGGER.warn("[GCAL-SEAT-EVENT] A back-fill was requested for {} days, beyond the {}-day"
                    + " ceiling this extension will read; clamping.", requested, MAX_BACK_FILL_DAYS);
            return MAX_BACK_FILL_DAYS;
        }
        return requested;
    }

    /**
     * Whether this notification is about the workspace this invoker serves.
     *
     * <p>Production ESB processing must resolve its own workspace from the captured context.
     * Blank event workspace hints remain accepted within that known workspace. Legacy standalone
     * callers keep their existing unknown-workspace behaviour.
     *
     * @param eventWorkspaceId the workspace on the event
     * @return true when the event may be acted on
     */
    private boolean isForThisWorkspace(String eventWorkspaceId) {
        String own = ownWorkspaceId();
        if (own == null || own.isBlank()) {
            // Legacy standalone hint handling is unchanged. A production ESB handler must be able
            // to resolve its own workspace from the restored context before reading calendars.
            return contextHolder == null;
        }
        if (eventWorkspaceId == null || eventWorkspaceId.isBlank()) {
            return true;
        }
        return own.trim().equalsIgnoreCase(eventWorkspaceId.trim());
    }

    private String ownWorkspaceId() {
        try {
            return invoker.getWorkspaceId();
        }
        catch (Exception unavailable) {
            LOGGER.debug("[GCAL-SEAT-EVENT] Could not read this invoker's workspace id: {}",
                    unavailable.getMessage());
            return null;
        }
    }

    /**
     * Records the outcome. Never allowed to throw: this handler's contract is to acknowledge, and an
     * unavailable metrics backend must not turn that into a redelivery loop.
     */
    private void record(String outcome, boolean success) {
        try {
            telemetryService.recordApiCall("ma",
                    "seat_registered_" + outcome.toLowerCase(Locale.ROOT), success);
        }
        catch (Exception metricsUnavailable) {
            LOGGER.debug("[GCAL-SEAT-EVENT] Could not record the seat-event metric: {}",
                    metricsUnavailable.getMessage());
        }
    }
}
