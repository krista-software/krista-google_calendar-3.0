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

import app.krista.conversationagent.module.calendar.esb.CalendarDailySyncCommandProto;
import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.Hk2ContextHolder;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarSyncTelemetryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.SendEventsToMAService;
import app.krista.infra.esb.service.ServiceEndpoint;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the full-tenant calendar sweep when Meeting Agent asks for it, once a night (KIM-492).
 *
 * <p>Deliberately shaped as a near-copy of teams-transcribe's {@code KipCalendarDailySyncHandler},
 * so a change to one is obviously a change the other needs. The reasoning below is the same on both
 * sides; only the sweep call and the telemetry differ.
 *
 * <h2>What this restores</h2>
 *
 * <p>The <b>Adaptive Calendar Sync</b> catalog request has always been the only thing that sweeps
 * every calendar in the scan set. Nothing here runs it on a timer: the 15-minute seat reconcile and
 * the 6-hourly watch renewal create MISSING channels and never sync. A Krista conversation invoked
 * it daily, Always On removed the conversations, and the daily sweep went with them.
 *
 * <h2>Why Meeting Agent drives it rather than a timer here</h2>
 *
 * <p>This extension has no cross-replica protection of any kind — the renewal executor is started
 * unconditionally on {@code INVOKER_LOADED}, with no lock and no leader election. A midnight sweep
 * on a local timer would run once per replica, multiplying Google load at the worst moment. Meeting
 * Agent has a distributed scheduler lock, and the enable/disable and timing configuration lives
 * there.
 *
 * <h2>Foreign events are IGNORED, not merely logged</h2>
 *
 * <p>The destination is shared per calendar system and isolation is the consumer's tenant selector.
 * Acting on a stranger's signal here would read another organisation's entire directory, so the
 * event's {@code workspaceId} is compared with this invoker's own and a mismatch is dropped.
 *
 * <h2>Debounced</h2>
 *
 * <p>Unlike the per-user back-fill on the seat-registered queue, this IS a whole-roster sweep: a
 * second command minutes after the first would re-read every calendar to learn nothing. A
 * redelivery, a Meeting Agent restart, or a manual run are all realistic ways to get two in quick
 * succession.
 *
 * <h2>A failed sweep is ACKNOWLEDGED, never rethrown</h2>
 *
 * <p>The queue is durable with {@code setProcessorAutoAcknowledge(false)}, so an escaping throwable
 * redelivers — and the conditions that fail a sweep (Google refusing a delegated credential, quota
 * exhaustion, Meeting Agent unreachable) are exactly the conditions that fail every redelivery. One
 * nightly command would become an unbounded retry loop against a provider already refusing us.
 * <b>Tomorrow night's command is the retry</b>, and a missed sweep costs a day of reconciliation lag
 * on top of a watch-channel path that still works.
 *
 * @since 3.1.0
 */
@Service
public class GoogleCalendarDailySyncHandler
        implements GoogleCalendarDailySyncService, ServiceEndpoint {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GoogleCalendarDailySyncHandler.class);

    private static final String TAG = "[GCAL-DAILY-SYNC]";

    /**
     * How long after a sweep another command is acknowledged without sweeping again.
     *
     * <p>Ten minutes, matching teams-transcribe. A full sweep of a large tenant takes minutes, and
     * every realistic cause of a second command inside that window wants the same answer: the sweep
     * that just ran already covers it.
     */
    static final long DEBOUNCE_WINDOW_MS = Duration.ofMinutes(10).toMillis();

    /** Window used when Meeting Agent does not specify one. Matches the catalog request's default. */
    static final long DEFAULT_SYNC_DAYS = 30L;

    /** Ceiling on a requested window, mirroring the seat back-fill's. */
    static final long MAX_SYNC_DAYS = 365L;

    private final SendEventsToMAService sendEventsToMAService;
    private final CalendarSyncTelemetryService telemetryService;
    private final Invoker invoker;
    private final Hk2ContextHolder contextHolder;

    private final AtomicLong lastSweptAt = new AtomicLong(0L);

    @Inject
    public GoogleCalendarDailySyncHandler(SendEventsToMAService sendEventsToMAService,
                                          CalendarSyncTelemetryService telemetryService,
                                          Invoker invoker,
                                          Hk2ContextHolder contextHolder) {
        this.sendEventsToMAService = sendEventsToMAService;
        this.telemetryService = telemetryService;
        this.invoker = invoker;
        this.contextHolder = contextHolder;
    }

    /** Compatibility for standalone invoker-context callers; HK2 injects the scoped constructor. */
    public GoogleCalendarDailySyncHandler(SendEventsToMAService sendEventsToMAService,
                                          CalendarSyncTelemetryService telemetryService,
                                          Invoker invoker) {
        this(sendEventsToMAService, telemetryService, invoker, null);
    }

    @Override
    public void googleCalendarDailySync(CalendarDailySyncCommandProto event) {
        if (contextHolder == null) {
            processDailySync(event);
        } else if (!contextHolder.runWithCapturedContext(() -> processDailySync(event))) {
            LOGGER.warn("{} Cannot process sync without this invoker's lifecycle context. "
                    + "No sweep was attempted.", TAG);
        }
    }

    private void processDailySync(CalendarDailySyncCommandProto event) {
        if (event == null) {
            LOGGER.warn("{} Received a null sweep request — acknowledging.", TAG);
            return;
        }

        LOGGER.info("{} workspace={} days={} corr={}",
                TAG, event.getWorkspaceId(), event.getSyncDays(), event.getCorrelationId());

        if (!isForThisWorkspace(event.getWorkspaceId())) {
            LOGGER.warn("{} Ignoring a sweep request for workspace {} — this invoker serves {}. "
                            + "Acting on it would read another organisation's entire directory on "
                            + "that organisation's signal, so it is reported rather than silently "
                            + "acted on.",
                    TAG, event.getWorkspaceId(), ownWorkspaceId());
            record("ignored_foreign_workspace", true);
            return;
        }

        long started = System.currentTimeMillis();
        if (!claimSweepSlot(started)) {
            LOGGER.info("{} A sweep ran within the last {}ms — acknowledging without sweeping again.",
                    TAG, DEBOUNCE_WINDOW_MS);
            record("debounced", true);
            return;
        }

        long days = resolveSyncDays(event.getSyncDays());
        try {
            sendEventsToMAService.sendEventsToMA(days);
            record("swept", true);
            LOGGER.info("{} Sweep complete over the next {} day(s) in {}ms.",
                    TAG, days, System.currentTimeMillis() - started);
        }
        catch (Throwable failure) {
            record("sweep_failed", false);
            // Acknowledged deliberately — see the class javadoc. Rethrowing on a durable manual-ack
            // queue would redeliver into the same failure forever.
            LOGGER.warn("{} Sweep failed; acknowledging the event. Calendars still update on watch "
                            + "notifications, and the next nightly command is the retry: {}",
                    TAG, failure.getMessage(), failure);
        }
    }

    /**
     * Whether this request is about the workspace this invoker serves.
     *
     * <p>Production ESB processing must resolve its own workspace from the captured context.
     * Blank event workspace hints remain accepted within that known workspace. Legacy standalone
     * callers keep their existing unknown-workspace behaviour.
     */
    private boolean isForThisWorkspace(String eventWorkspaceId) {
        String own = ownWorkspaceId();
        if (own == null || own.isBlank()) {
            return contextHolder == null;
        }
        if (eventWorkspaceId == null || eventWorkspaceId.isBlank()) {
            return true;
        }
        return own.trim().equalsIgnoreCase(eventWorkspaceId.trim());
    }

    private String ownWorkspaceId() {
        try {
            return invoker == null ? null : invoker.getWorkspaceId();
        }
        catch (Exception unavailable) {
            LOGGER.debug("{} Could not read this invoker's workspace id: {}",
                    TAG, unavailable.getMessage());
            return null;
        }
    }

    /**
     * Claims the right to sweep now, or reports that a recent sweep already covers it.
     *
     * <p>Compare-and-set rather than a lock: two commands arriving concurrently must produce ONE
     * sweep, and the loser acknowledges rather than queueing behind the winner.
     */
    private boolean claimSweepSlot(long now) {
        while (true) {
            long previous = lastSweptAt.get();
            if (previous != 0L && now - previous < DEBOUNCE_WINDOW_MS) {
                return false;
            }
            if (lastSweptAt.compareAndSet(previous, now)) {
                return true;
            }
        }
    }

    /**
     * Clamps the requested window into something this extension is willing to ask Google for.
     *
     * <p><b>This is not only a fetch window.</b> The sweep keeps its DELETION-detection window
     * identical to its fetch window, so the value decides which meetings get cleaned up, not merely
     * how many are looked at. Clamped rather than refused because the producer is a separately
     * deployed service across a wire contract, and a value this extension cannot serve is better
     * turned into a sane window than into an exception that costs the night's reconciliation.
     */
    private long resolveSyncDays(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_SYNC_DAYS;
        }
        if (requested > MAX_SYNC_DAYS) {
            LOGGER.warn("{} A sweep was requested for {} days, beyond the {}-day ceiling this "
                    + "extension will read; clamping.", TAG, requested, MAX_SYNC_DAYS);
            return MAX_SYNC_DAYS;
        }
        return requested;
    }

    /**
     * Records the outcome. Never allowed to throw: this handler's contract is to acknowledge, and an
     * unavailable metrics backend must not turn that into a redelivery loop.
     */
    private void record(String outcome, boolean success) {
        try {
            telemetryService.recordApiCall("ma",
                    "daily_sync_" + outcome.toLowerCase(Locale.ROOT), success);
        }
        catch (Exception metricsUnavailable) {
            LOGGER.debug("{} Could not record the sweep metric: {}",
                    TAG, metricsUnavailable.getMessage());
        }
    }
}
