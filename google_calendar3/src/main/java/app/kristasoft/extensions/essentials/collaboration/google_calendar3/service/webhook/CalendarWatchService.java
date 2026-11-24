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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook;

import app.krista.extension.authorization.MustAuthorizeException;
import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatus;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.AuthFailures;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.DelegatedMailboxStatus;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarAuthenticationService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleThrottleBackoff;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.ScannedMailboxReporter;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Channel;
import com.google.api.services.calendar.model.Events;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kristasoft.common.holders.ThreadLocalProxy;

import javax.inject.Inject;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Service for managing Google Calendar push notification channels.
 * Handles creation, renewal, and deletion of watch channels for calendar events.
 *
 * <p>Google Calendar push notifications work via the {@code watch} API:
 * <ul>
 *   <li>A watch channel is created on the primary calendar's events list</li>
 *   <li>Google sends POST notifications to the registered URL when events change</li>
 *   <li>Notifications contain only headers (no body) — actual changes must be fetched using sync tokens</li>
 *   <li>Channels expire and must be renewed (max ~7 days for calendar events)</li>
 * </ul>
 * </p>
 */
@Service
public class CalendarWatchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarWatchService.class);
    private static final String PRIMARY_CALENDAR = "primary";
    private static final long CHANNEL_TTL_MILLIS = 5 * 24 * 60 * 60 * 1000L; // 5 days (max is ~7 days)
    private static final long RENEWAL_INITIAL_DELAY_HOURS = 1;
    private static final long RENEWAL_INTERVAL_HOURS = 6;

    /**
     * KIM-474 / EF-23: how often the registered-seat scan set is reconciled.
     *
     * <p><b>Fifteen minutes, matching teams-transcribe's {@code reconcileNewTenantMailboxes}.</b> The
     * two extensions are two halves of one Meeting Agent feature, and an operator who watches a seat
     * appear on a Teams workspace in minutes and on a Google workspace six hours later has no way to
     * tell a cadence difference from a fault. Before this, the roster was refreshed only when a sweep
     * happened to call discovery — and the only scheduled sweep is the {@link #RENEWAL_INTERVAL_HOURS}
     * -hourly renewal, so a Google seat could wait six hours.
     *
     * <p>Deliberately NOT folded into the renewal cadence in either direction. Renewal is expensive —
     * three Google round trips per mailbox — and running it every fifteen minutes would multiply that
     * across the whole workspace for no benefit, since channels live five days. The reconcile is cheap
     * on an unchanged workspace: one Meeting Agent round trip plus a key-value read per user, with
     * Google calls only for a mailbox that is genuinely new or departed. And it is free on an opt-in
     * workspace, which returns before doing anything at all.
     *
     * <p>Not configurable, for the same reason {@code MAX_CONCURRENT_USERS} is not: a tunable nobody
     * sets is a tunable that drifts, and this one must stay in step with the other extension.
     */
    private static final long SEAT_RECONCILE_INTERVAL_MINUTES = 15;

    /**
     * First seat reconcile after start. One minute, matching teams-transcribe: long enough for the
     * invoker-load sequence to have primed the roster and registered channels, short enough that a
     * seat added while the extension was down is picked up promptly rather than a quarter-hour later.
     */
    private static final long SEAT_RECONCILE_INITIAL_DELAY_MINUTES = 1;

    /**
     * KIM-474 (scale): how many mailboxes have their watch channel registered, renewed or torn
     * down at once.
     *
     * <p>Deliberately the SAME number as {@code SendEventsToMAService.MAX_CONCURRENT_USERS}: both
     * fan-outs push on the same Google Calendar API for the same Workspace account, and two
     * different ceilings would mean the effective limit depends on which sweep happens to be
     * running. Not configurable — a tunable nobody sets is a tunable that drifts.
     *
     * <p>Applies ONLY on the delegated (Always On) path. With Always On off the population is the
     * handful of users who authorised their own calendar, the sequential loops below cost seconds,
     * and changing their shape would change how every existing tenant behaves at load for no
     * benefit.
     */
    private static final int MAX_CONCURRENT_USERS = 5;

    private final GoogleProviderFactory googleProviderFactory;
    private final GoogleCalendarAuthenticationService authenticationService;
    private final CalendarWatchStore watchStore;
    private final Invoker invoker;
    private final UserKeyIndexStore userKeyIndexStore;
    private final CalendarDiscoveryService discoveryService;

    /** Reports each sweep's mailboxes to Meeting Agent; null only for source-compatible callers. */
    private final ScannedMailboxReporter scannedMailboxReporter;

    /**
     * Optional status store. Under Always On nobody completes an OAuth callback, so a delegated
     * watch is where a mailbox proves readable (CONNECTED) or stops being readable (DISCONNECTED) —
     * the Google equivalent of the Teams subscription success point. Wired by
     * {@code GoogleCalendarExtension} after construction to keep existing tests and lightweight
     * consumers source-compatible.
     */
    private CalendarConnectionStatusStore connectionStatusStore;

    private ScheduledExecutorService renewalScheduler;

    @Inject
    public CalendarWatchService(GoogleProviderFactory googleProviderFactory,
                                GoogleCalendarAuthenticationService authenticationService,
                                CalendarWatchStore watchStore,
                                Invoker invoker,
                                UserKeyIndexStore userKeyIndexStore,
                                CalendarDiscoveryService discoveryService,
                                ScannedMailboxReporter scannedMailboxReporter) {
        this.googleProviderFactory = googleProviderFactory;
        this.authenticationService = authenticationService;
        this.watchStore = watchStore;
        this.invoker = invoker;
        this.userKeyIndexStore = userKeyIndexStore;
        this.discoveryService = discoveryService;
        this.scannedMailboxReporter = scannedMailboxReporter;
    }

    /** Keeps lightweight callers source-compatible. HK2 uses the injected constructor. */
    public CalendarWatchService(GoogleProviderFactory googleProviderFactory,
                                GoogleCalendarAuthenticationService authenticationService,
                                CalendarWatchStore watchStore,
                                Invoker invoker,
                                UserKeyIndexStore userKeyIndexStore,
                                CalendarDiscoveryService discoveryService) {
        this(googleProviderFactory, authenticationService, watchStore, invoker, userKeyIndexStore,
                discoveryService, null);
    }

    /**
     * Wires the connection-status store Always On sweeps publish through.
     */
    public void setConnectionStatusStore(CalendarConnectionStatusStore connectionStatusStore) {
        this.connectionStatusStore = connectionStatusStore;
    }

    /**
     * Creates watch channels for every user in the active scan set.
     * Skips users that already have an active watch channel.
     *
     * <p>KIM-474 / WP-G1: the set comes from {@link CalendarDiscoveryService} — the consented users
     * while Always On is off, this workspace's registered Meeting Agent users when it is on and
     * domain-wide delegation is configured. Without this, an Always On sweep would find meetings on
     * its first pass and then never hear about a single change to them.
     *
     * <p>Every user in the set is visited, which is why this sweep's report is the most complete
     * one on an Always On workspace. Each visit publishes the mailbox's status as it goes
     * ({@link #createWatchChannel(String, CalendarDiscoveryService.DiscoverySource)}); the sweep then
     * publishes DISCONNECTED for previously-connected mailboxes that left the scope
     * ({@link #publishScopeDepartures}) before revoking their channels.
     */
    public void subscribeAllUsers() {
        CalendarDiscoveryService.UserSet scanSet = discoveryService.discover("watch_subscribe");
        List<String> users = scanSet.userIds();
        ScannedMailboxReporter.SweepOutcomes outcomes = new ScannedMailboxReporter.SweepOutcomes();
        if (users.isEmpty()) {
            LOGGER.info("No users in scope. Skipping watch channel creation.");
        }
        else {
            LOGGER.info("Creating watch channels for {} user(s) (source={})...", users.size(), scanSet.source());
            forEachUser(scanSet, "watch-channel creation",
                    userId -> recordingOutcome(outcomes, userId,
                            () -> createWatchChannel(userId, scanSet.sourceOf(userId))));
        }
        // Not a return on an empty set: every seat being absent from the Workspace directory is an
        // empty scan set too, and those are exactly the rows (and statuses) an admin needs to see.
        publishScopeDepartures(scanSet, "watch_subscribe");
        revokeSubscriptionsOutOfScope(scanSet, "watch_subscribe");
        reportScannedMailboxes(scanSet, outcomes, "watch_subscribe");
    }

    /**
     * Runs one mailbox's attempt and records how it went. The failure is rethrown unchanged, so the
     * sweep's own per-user logging and reauthorization count see exactly what they always did.
     */
    private static void recordingOutcome(ScannedMailboxReporter.SweepOutcomes outcomes, String userId,
                                         Runnable attempt) {
        try {
            attempt.run();
            outcomes.proved(userId);
        } catch (RuntimeException failure) {
            outcomes.failed(userId, failure);
            throw failure;
        }
    }

    private void reportScannedMailboxes(CalendarDiscoveryService.UserSet scanSet,
                                        ScannedMailboxReporter.SweepOutcomes outcomes, String sweepName) {
        if (scannedMailboxReporter != null) {
            scannedMailboxReporter.report(scanSet, outcomes, sweepName);
        }
    }

    /**
     * Always On: publishes DISCONNECTED for every mailbox this sweep shows is no longer readable
     * without having attempted it — a registered seat a complete directory read no longer lists, and
     * a mailbox that holds a watch channel but has left the scanned set.
     *
     * <p>Runs BEFORE {@link #revokeSubscriptionsOutOfScope}, which deletes the watch rows this reads.
     * Deliberately independent of that method's empty-set guard: an empty roster keeps its channels
     * (stopping them all on one answer is the outage that guard prevents) but no calendar in it is
     * read any more, and Connected must say so. The store publishes only for a mailbox that was
     * CONNECTED, so repeating this every sweep publishes once.
     *
     * <p>A degraded sweep resolved no scope, so it proves nothing about who left: it publishes
     * nothing. An Opt-In sweep publishes nothing either — its statuses come from the personal-token
     * paths, unchanged.
     *
     * @param scanSet   the freshly resolved scan set
     * @param sweepName short label for the log lines
     */
    void publishScopeDepartures(CalendarDiscoveryService.UserSet scanSet, String sweepName) {
        if (connectionStatusStore == null || scanSet == null
                || !scanSet.source().delegated() || scanSet.degraded()) {
            return;
        }
        try {
            DelegatedMailboxStatus status = mailboxStatus();
            status.notInDirectory(scanSet.seatsNotInDirectory());

            Set<String> inScope = new java.util.HashSet<>();
            for (String userId : scanSet.userIds()) {
                if (userId != null) {
                    inScope.add(userId.toLowerCase(Locale.ROOT));
                }
            }
            List<String> departed = watchStore.subscribedUserIds().stream()
                    .filter(userId -> userId != null && !inScope.contains(userId.toLowerCase(Locale.ROOT)))
                    .toList();
            status.leftScanSet(departed);
        } catch (Exception statusFailure) {
            LOGGER.warn("[{}] could not publish the status of mailboxes that left the Always On scope: {}",
                    sweepName, statusFailure.getMessage());
        }
    }

    private DelegatedMailboxStatus mailboxStatus() {
        return new DelegatedMailboxStatus(connectionStatusStore, invoker);
    }

    /**
     * KIM-474 / EF-07: stops the watch channel of every user we hold one for that is no longer in
     * the active scan set.
     *
     * <p><b>Why this has to exist.</b> Every other sweep in this class — subscribe, renew, tear down
     * — iterates the users currently IN scope. A mailbox that LEAVES the scope is therefore never
     * visited again by any of them: nothing renews its channel and nothing stops it either. It kept
     * notifying us about a calendar we had already decided not to scan until Google expired the
     * channel on its own ({@link #CHANNEL_TTL_MILLIS}, ~5 days later), and the local store kept a
     * key nothing would ever revisit. Ported from teams-transcribe's
     * {@code CalendarSubscriptionService.revokeSubscriptionsOutOfScope} — same name on purpose, so
     * the two extensions' halves of one feature stay greppable as one thing. GLE's "subscription" is
     * a Google Calendar <em>watch channel</em>.
     *
     * <p>The store is the only record of what we are subscribed to, so the reconcile is "everything
     * in the store, minus everything in scope". That covers every way a user can leave — they left
     * the company, their Meeting Agent seat was revoked, their address dropped out of the Google
     * Workspace directory, they revoked their own consent, Always On was switched off — without
     * needing a signal specific to each.
     *
     * <p>Deliberately NOT driven off {@link CalendarDiscoveryService.UserSet#droppedConsentedUsers()}:
     * that names only the individually-authorised users an Always On sweep replaces, so it would
     * miss every other way out. It is a diagnostic, not a work list.
     *
     * <p>Comparison is case-insensitive. Seats arrive from Meeting Agent lower-cased while a
     * consented-era watch row carries whatever case the user authorised with, and a case-only
     * mismatch here would read as "this mailbox has left" and tear down a live channel.
     *
     * <p>Failure is per-user and non-fatal: one Google error must not abandon the rest of the
     * reconcile, and {@link #stopWatchChannel} removes the local row in its {@code finally} either
     * way — a channel we can no longer stop is one we have already stopped renewing, so keeping the
     * key would only strand it further.
     *
     * @param scanSet   the freshly resolved scan set
     * @param sweepName short label for the log lines
     * @return how many channels were revoked
     */
    public int revokeSubscriptionsOutOfScope(CalendarDiscoveryService.UserSet scanSet, String sweepName) {
        // An EMPTY scan set revokes NOTHING. This is the one input that could turn a reconcile into
        // an outage: a sweep that resolved no users produces an empty set, and treating "nothing in
        // scope" literally would stop every channel in the workspace — recoverable only by a full
        // re-subscribe, and indistinguishable from a legitimate empty result. And under the owner's
        // ruling (2026-09-12) an empty seat roster is a LEGITIMATE empty result: Always On scans
        // registered seats and only those, so a workspace between licences genuinely scans nobody.
        // It must stop renewing those channels, which it does by not visiting them — never by
        // actively tearing down every channel it holds.
        if (scanSet == null || scanSet.isEmpty()) {
            LOGGER.debug("[{}] the scan set is empty — skipping the out-of-scope revoke rather than "
                    + "stopping every watch channel we hold", sweepName);
            return 0;
        }
        // A DEGRADED scan set revokes NOTHING either. Degradation means Always On asked for its
        // registered-seat scan and could not have it — no service-account key, a refused domain-wide
        // delegation, an unreadable seat roster on a cold process — so discovery scanned nothing
        // this sweep. That is a missing answer, not an answer of "nobody": one transient fault must
        // not tear down the workspace's entire push pipeline. Only a set that actually resolved is
        // allowed to shrink what we hold. (Degraded sets are empty and so already stop at the guard
        // above; this branch states the rule rather than relying on that.) WARN, not debug: a
        // degraded sweep is an operator-visible condition.
        if (scanSet.degraded()) {
            LOGGER.warn("[{}] the scan set is DEGRADED ({}) — skipping the out-of-scope revoke. "
                    + "Held channels keep being renewed until a scan set resolves cleanly.",
                    sweepName, scanSet.degradedReason());
            return 0;
        }

        Set<String> held = watchStore.subscribedUserIds();
        if (held.isEmpty()) {
            return 0;
        }

        Set<String> inScope = new java.util.HashSet<>();
        for (String userId : scanSet.userIds()) {
            if (userId != null) {
                inScope.add(userId.toLowerCase(Locale.ROOT));
            }
        }
        List<String> stale = held.stream()
                .filter(userId -> !inScope.contains(userId.toLowerCase(Locale.ROOT)))
                .toList();
        if (stale.isEmpty()) {
            return 0;
        }

        LOGGER.info("[{}] {} user(s) hold a watch channel but are no longer in the scan set — "
                + "revoking: {}", sweepName, stale.size(), stale);

        int revoked = 0;
        for (String userId : stale) {
            try {
                // sourceFor, not scanSet.sourceOf: an out-of-scope user is by definition absent from
                // the set's members, so sourceOf would answer CONSENTED_USERS for a delegated
                // channel. sourceFor re-decides from the live roster, which is the closest thing to
                // the credential that created the channel. Either way the Google stop is
                // best-effort and the local row goes.
                stopWatchChannel(userId, discoveryService.sourceFor(userId));
            } catch (Exception e) {
                // Still counted. stopWatchChannel removes the local row in its finally block, so
                // even a failed Google stop leaves us no longer tracking and no longer renewing this
                // user — which is what makes the channel lapse. The residue is a channel that
                // expires on its own; logging it is the honest report, and skipping the count would
                // understate what actually changed.
                LOGGER.warn("[{}] stopped tracking out-of-scope user {} but could not stop its "
                        + "Google watch channel ({}); it expires on its own within {} day(s)",
                        sweepName, userId, e.getMessage(), CHANNEL_TTL_MILLIS / (24 * 60 * 60 * 1000L));
            }
            revoked++;
        }
        LOGGER.info("[{}] out-of-scope revoke complete: {} of {} watch channel(s) revoked",
                sweepName, revoked, stale.size());
        return revoked;
    }

    /**
     * KIM-474 (scale): runs one per-user action across the active scan set.
     *
     * <p><b>Consented set (Always On off): strictly sequential on the calling thread</b>, exactly as
     * every sweep in this class has always been. That population is a handful of users, the loop
     * costs seconds, and every tenant that has not opted in runs it.
     *
     * <p><b>Delegated: bounded virtual-thread fan-out at {@link #MAX_CONCURRENT_USERS}.</b> Watch
     * channels are three Google round-trips each ({@code calendars.get}, {@code events.list} for the
     * baseline sync token, {@code events.watch}); across a workspace of thousands of registered
     * users, doing that one at a time is a startup measured in hours — and channels expire after
     * five days, so a renewal pass that cannot get round them in time silently stops push
     * notifications for whoever was at the back of the queue.
     *
     * <p>The HK2 context is captured on the calling thread and restored on each virtual thread:
     * every store here is invoker-scoped and resolves through {@code ThreadLocalProxy}, so a task
     * without it would fail on its first store read. It is cleared afterwards so a carrier thread
     * cannot leak one invoker's context into another's work.
     *
     * @param scanSet  the sweep's resolved set
     * @param operation short label for the log lines
     * @param perUser  the action to run per user; failures are logged and skipped so one mailbox
     *                 cannot abort the sweep
     */
    private void forEachUser(CalendarDiscoveryService.UserSet scanSet, String operation,
                             java.util.function.Consumer<String> perUser) {
        List<String> users = scanSet.userIds();
        if (users.isEmpty()) {
            return;
        }

        // Counted on every path below and summarised once in the finally block. A user whose Google
        // authorization has lapsed fails on every tick until they reconnect, so the number worth
        // logging is "how many of the scan set are waiting on their user", not N separate failures.
        AtomicInteger reauthNeeded = new AtomicInteger();
        try {
            if (!scanSet.source().delegated()) {
                runSequentially(users, operation, perUser, reauthNeeded);
                return;
            }

            final Map<Class<?>, Object> hk2Context;
            Map<Class<?>, Object> captured;
            try {
                captured = ThreadLocalProxy.getAll();
            } catch (Exception e) {
                LOGGER.warn("Could not capture the HK2 context for the parallel {} sweep: {}. "
                        + "Falling back to sequential processing.", operation, e.getMessage());
                captured = null;
            }
            if (captured == null) {
                runSequentially(users, operation, perUser, reauthNeeded);
                return;
            }
            hk2Context = captured;

            Semaphore semaphore = new Semaphore(MAX_CONCURRENT_USERS);
            LOGGER.info("{}: processing {} tenant mailbox(es), {} at a time",
                    operation, users.size(), MAX_CONCURRENT_USERS);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<Void>> futures = users.stream()
                        .map(userId -> CompletableFuture.runAsync(() -> {
                            try {
                                semaphore.acquire();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                LOGGER.warn("{} interrupted while queued for mailbox {}", operation, userId);
                                return;
                            }
                            try {
                                try {
                                    ThreadLocalProxy.setAll(hk2Context);
                                } catch (Exception contextFailure) {
                                    // Log and still attempt the work, rather than skipping a mailbox
                                    // before anything was even tried.
                                    LOGGER.warn("Could not install the HK2 context for mailbox {} during {}: {}",
                                            userId, operation, contextFailure.getMessage());
                                }
                                perUser.accept(userId);
                            } catch (Exception e) {
                                logPerUserFailure(operation, userId, e, reauthNeeded);
                            } finally {
                                try {
                                    ThreadLocalProxy.setAll(new HashMap<>());
                                } catch (Exception cleanupFailure) {
                                    LOGGER.debug("Could not clear the HK2 context after {}: {}",
                                            operation, cleanupFailure.getMessage());
                                }
                                semaphore.release();
                            }
                        }, executor))
                        .toList();

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
        } finally {
            summariseReauthorizations(operation, users.size(), reauthNeeded.get());
        }
    }

    /**
     * Runs the sweep one user at a time on the calling thread — the consented-set path, and the
     * fallback when the HK2 context could not be captured for a parallel one.
     */
    private void runSequentially(List<String> users, String operation,
                                 java.util.function.Consumer<String> perUser,
                                 AtomicInteger reauthNeeded) {
        for (String userId : users) {
            try {
                perUser.accept(userId);
            } catch (Exception e) {
                logPerUserFailure(operation, userId, e, reauthNeeded);
            }
        }
    }

    /**
     * Logs one user's sweep failure, at the level the failure actually warrants.
     *
     * <p>A lapsed Google authorization is not a defect — it is the platform's reauthorization
     * signal, and it recurs on every tick until the user reconnects. It gets one WARN line carrying
     * the user id and the reason, and the stack trace moves to DEBUG: the trace is the same fixed
     * frame list every time and bottoms out in our own exception factory, so it never said anything
     * the class and message had not. {@link AuthFailures} has the full reasoning. Enable DEBUG for
     * this class to get the traces back on a pod without a rebuild.
     *
     * <p>Every other failure keeps its ERROR and its stack trace, unchanged — those are the ones
     * where the trace is the diagnostic.
     */
    private void logPerUserFailure(String operation, String userId, Exception failure,
                                   AtomicInteger reauthNeeded) {
        if (AuthFailures.needsReauthorization(failure)) {
            reauthNeeded.incrementAndGet();
            LOGGER.warn("Skipped {} for user {}: Google authorization has lapsed, the user must "
                    + "reauthorize ({})", operation, userId, AuthFailures.describe(failure));
            LOGGER.debug("Authorization-failure detail for user {} during {}", userId, operation, failure);
            return;
        }
        LOGGER.error("Failed {} for user {}: {}", operation, userId, failure.getMessage(), failure);
    }

    /**
     * Emits the sweep's one-line reauthorization summary, so the shape of the problem is legible
     * without counting WARN lines. Silent when nothing needed reauthorizing.
     */
    private void summariseReauthorizations(String operation, int totalUsers, int reauthNeeded) {
        if (reauthNeeded <= 0) {
            return;
        }
        LOGGER.info("{}: {} of {} user(s) must reauthorize Google Calendar and were skipped. Their "
                        + "connection status is published as {}, which raises their reconnect prompt.",
                operation, reauthNeeded, totalUsers, CalendarConnectionStatus.REAUTH_REQUIRED);
    }

    /**
     * Creates a Google Calendar push notification channel for a user, resolving the credential
     * source for that user on its own.
     *
     * @param userId the user ID (email) to create the watch for
     * @return the created Channel, or null if already subscribed
     */
    public Channel createWatchChannel(String userId) {
        return createWatchChannel(userId, discoveryService.sourceFor(userId));
    }

    /**
     * Creates a Google Calendar push notification channel for a user on an explicit credential
     * source — used by a sweep, which resolves each user's credential once from its immutable scan
     * set, so the credential cannot change under the sweep while it runs.
     *
     * @param userId the user ID (email) to create the watch for
     * @param source which credential registers the channel
     * @return the created Channel, or null if already subscribed
     */
    public Channel createWatchChannel(String userId, CalendarDiscoveryService.DiscoverySource source) {
        try {
            // Check if already subscribed
            if (watchStore.hasWatch(userId)) {
                long expiration = watchStore.getExpiration(userId);
                if (expiration > System.currentTimeMillis()) {
                    LOGGER.info("User {} already has an active watch channel (expires {}). Skipping.", userId, expiration);
                    // A saved channel can outlive its permission, and a saved CONNECTED status
                    // does not prove CA received the event. Verify access before reasserting it.
                    if (source.delegated() && connectionStatusStore != null) {
                        Calendar client = getCalendarClient(userId, source);
                        throttleAware(source, "verify delegated access for " + userId,
                                () -> client.events().list(PRIMARY_CALENDAR).setMaxResults(1).execute());
                    }
                    publishDelegatedConnected(userId, source);
                    return null;
                }
                // Channel expired, clean up and re-create
                LOGGER.info("Watch channel for user {} has expired. Re-creating...", userId);
                stopWatchChannel(userId, source);
            }

            LOGGER.info("Creating watch channel for user: {}", userId);

            Calendar calendarService = getCalendarClient(userId, source);

            // DIAGNOSTIC: reveal which Google account/calendar this watch is actually registered on.
            // If it is not the authenticated end-user's calendar, their events will never notify.
            try {
                String watchedPrimaryCalendarId = calendarService.calendars().get("primary").execute().getId();
                LOGGER.info("[GCAL-IDENTITY] watch: requestedUserId={} watchingPrimaryCalendarId={}",
                        userId, watchedPrimaryCalendarId);
                // Record email<->key while the token is healthy (watch creation runs at load for
                // every authenticated user) so a later auto-detected REAUTH_REQUIRED can recover
                // the email for this wsContact_<UUID> key and reach CA.
                userKeyIndexStore.put(invoker.getInvokerId(), watchedPrimaryCalendarId, userId);
            } catch (Exception identityEx) {
                LOGGER.warn("[GCAL-IDENTITY] watch: could not resolve primary calendar id for {}: {}",
                        userId, identityEx.getMessage());
            }

            // First, perform an initial sync to get the sync token
            String syncToken = initializeSyncToken(calendarService, userId, source);

            // Conversation Agent owns the provider callback ingress. There is no adapter-local or
            // Recall fallback: a missing CA URL must fail watch creation instead of registering a
            // channel against a legacy callback path.
            String notificationUrl = meetingAgentCalendarCallbackUrl();

            LOGGER.info("Notification URL: {}", notificationUrl);

            // Create a unique channel ID
            String channelId = "gcal-" + userId.hashCode() + "-" + UUID.randomUUID();

            Channel channel = new Channel();
            channel.setId(channelId);
            channel.setType("web_hook");
            channel.setAddress(notificationUrl);
            // Carry the userId in the channel token: Google echoes it back as the
            // X-Goog-Channel-Token header on every notification, so the receiver can
            // resolve the owning user directly (O(1), race-free) instead of scanning.
            channel.setToken(userId);
            channel.setExpiration(System.currentTimeMillis() + CHANNEL_TTL_MILLIS);

            LOGGER.info("Sending watch request to Google Calendar API...");
            // KIM-474 (scale): a delegated registration sweep issues one of these per mailbox and
            // is the easiest call here to get rate-limited. The consented path is unwrapped.
            Channel createdChannel = throttleAware(source, "watch channel for " + userId,
                    () -> calendarService.events()
                            .watch(PRIMARY_CALENDAR, channel)
                            .execute());

            LOGGER.info("Watch channel created: channelId={}, resourceId={}, expiration={}",
                    createdChannel.getId(), createdChannel.getResourceId(), createdChannel.getExpiration());

            // Store the channel data + a reverse channelId -> userId index for routing.
            watchStore.put(userId, createdChannel.getId(), createdChannel.getResourceId(),
                    createdChannel.getExpiration());
            watchStore.putChannelUser(createdChannel.getId(), userId);

            // Store the sync token for incremental sync
            if (syncToken != null) {
                watchStore.putSyncToken(userId, syncToken);
            }

            // Always On has no per-user OAuth callback. A watch channel is created only after the
            // delegated credential successfully read this mailbox and Google accepted the channel,
            // so this is the Google equivalent of Teams' successful subscription status update.
            publishDelegatedConnected(userId, source);

            return createdChannel;

        } catch (Exception e) {
            LOGGER.error("Failed to create watch channel for user {}: {}", userId, e.getMessage(), e);
            publishDelegatedUnreachable(userId, source, e);
            throw new RuntimeException("Failed to create watch channel", e);
        }
    }

    /**
     * Thrown when Meeting Agent's callback URL is not configured. A configuration fault of this
     * extension, discovered after the mailbox was already read, so it says nothing about whether
     * the calendar can be read and must not flip that mailbox to DISCONNECTED.
     */
    static final class CallbackUrlMissingException extends IllegalStateException {

        CallbackUrlMissingException() {
            super("Conversation Agent service URL is required for Google calendar watches");
        }
    }

    private String meetingAgentCalendarCallbackUrl() {
        Object configured = invoker.getAttributes() == null ? null
                : invoker.getAttributes().get(Constants.SERVICE_URL);
        String base = configured == null ? "" : String.valueOf(configured).trim();
        if (base.isEmpty()) {
            throw new CallbackUrlMissingException();
        }
        return base.replaceAll("/+$", "") + "/api/v1/calendar/provider/google/notification"
                + "?workspaceId=" + encode(invoker.getWorkspaceId())
                + "&invokerId=" + encode(invoker.getInvokerId());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * Renews an existing watch channel for a user by stopping the old one and creating a new one.
     * Google Calendar API doesn't support renewing channels — you must stop and re-create.
     *
     * @param userId the user ID
     */
    public void renewWatchChannel(String userId) {
        renewWatchChannel(userId, discoveryService.sourceFor(userId));
    }

    /**
     * Renews a watch channel on an explicit credential source — a renewal sweep resolves each
     * channel's credential from its immutable scan set rather than re-deciding per call.
     *
     * @param userId the user ID
     * @param source which credential re-registers the channel
     */
    public void renewWatchChannel(String userId, CalendarDiscoveryService.DiscoverySource source) {
        LOGGER.info("Renewing watch channel for user: {}", userId);
        try {
            stopWatchChannel(userId, source);
        } catch (Exception e) {
            LOGGER.warn("Failed to stop existing channel during renewal for user {}: {}", userId, e.getMessage());
        }
        createWatchChannel(userId, source);
    }

    /**
     * Stops a Google Calendar watch channel for a user, resolving the credential source on its own.
     *
     * @param userId the user ID
     */
    public void stopWatchChannel(String userId) {
        stopWatchChannel(userId, discoveryService.sourceFor(userId));
    }

    /**
     * Stops a Google Calendar watch channel for a user on an explicit credential source.
     *
     * @param userId the user ID
     * @param source which credential owns the channel
     */
    public void stopWatchChannel(String userId, CalendarDiscoveryService.DiscoverySource source) {
        String channelId = watchStore.getChannelId(userId);
        String resourceId = watchStore.getResourceId(userId);

        if (channelId == null) {
            LOGGER.info("No watch channel found for user {}. Nothing to stop.", userId);
            watchStore.remove(userId);
            return;
        }

        try {
            LOGGER.info("Stopping watch channel: channelId={}, resourceId={}", channelId, resourceId);

            Calendar calendarService = getCalendarClient(userId, source);

            Channel channel = new Channel();
            channel.setId(channelId);
            channel.setResourceId(resourceId);

            // KIM-474 (scale): a delegated teardown issues one of these per mailbox.
            throttleAware(source, "stop watch channel " + channelId, () ->
                    calendarService.channels().stop(channel).execute());

            LOGGER.info("Watch channel stopped: {}", channelId);
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404) {
                LOGGER.info("Watch channel {} not found (already expired or stopped). Continuing.", channelId);
            } else {
                LOGGER.error("Failed to stop watch channel {}: {}", channelId, e.getMessage(), e);
            }
        } catch (Exception e) {
            if (AuthFailures.needsReauthorization(e)) {
                // The channel is being torn down anyway, and the store entry is removed in the
                // finally block regardless — a lapsed authorization here changes no outcome.
                LOGGER.info("Could not stop watch channel {} for user {}: their Google authorization "
                        + "has lapsed. Dropping the local entry; Google expires the channel itself.",
                        channelId, userId);
                LOGGER.debug("Authorization-failure detail stopping watch channel {}", channelId, e);
            } else {
                LOGGER.error("Failed to stop watch channel {}: {}", channelId, e.getMessage(), e);
            }
        } finally {
            watchStore.remove(userId);
            if (channelId != null) {
                watchStore.removeChannelUser(channelId);
            }
        }
    }

    /**
     * Stops all watch channels for all authenticated users.
     * Called during invokerUnloaded lifecycle.
     */
    public void unsubscribeAllUsers() {
        CalendarDiscoveryService.UserSet scanSet = discoveryService.discover("watch_unsubscribe");
        List<String> users = scanSet.userIds();
        LOGGER.info("Stopping watch channels for {} user(s) (source={})...", users.size(), scanSet.source());
        forEachUser(scanSet, "watch-channel teardown",
                userId -> stopWatchChannel(userId, scanSet.sourceOf(userId)));
    }

    /**
     * Renews watch channels that are about to expire (within 1 day).
     */
    /**
     * Starts a background scheduler that periodically renews watch channels nearing expiry.
     *
     * <p>Google Calendar channels expire after {@link #CHANNEL_TTL_MILLIS} (~5 days) and are NOT
     * auto-renewed by Google, so without this push notifications silently stop after expiry.
     * Mirrors teams-transcribe's renewal scheduler.
     *
     * <p>The current HK2 context is captured here (this runs on the invoker-loaded thread, which
     * has it) and restored on the scheduler thread before each run, so {@link #renewExpiringChannels()}
     * can use the invoker-scoped stores and {@link GoogleProviderFactory}.
     */
    public synchronized void startRenewalScheduler() {
        if (renewalScheduler != null && !renewalScheduler.isShutdown()) {
            LOGGER.info("Watch-channel renewal scheduler already running.");
            return;
        }
        final Map<Class<?>, Object> hk2Context = ThreadLocalProxy.getAll();
        renewalScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gcal-watch-renewal");
            t.setDaemon(true);
            return t;
        });
        renewalScheduler.scheduleAtFixedRate(() -> {
            try {
                ThreadLocalProxy.setAll(hk2Context);
                LOGGER.info("Running scheduled watch-channel renewal check...");
                renewExpiringChannels();
            } catch (Exception e) {
                LOGGER.error("Scheduled watch-channel renewal failed: {}", e.getMessage(), e);
            }
        }, RENEWAL_INITIAL_DELAY_HOURS, RENEWAL_INTERVAL_HOURS, TimeUnit.HOURS);

        // EF-23: the seat reconcile runs on its own cadence, on the SAME single thread. Two tasks on
        // one thread cannot overlap, which is what keeps a reconcile out of the middle of a renewal
        // sweep — both walk the scan set and issue Google calls, and interleaving them would double
        // the concurrent pressure the MAX_CONCURRENT_USERS semaphore exists to bound.
        renewalScheduler.scheduleAtFixedRate(() -> {
            try {
                ThreadLocalProxy.setAll(hk2Context);
                reconcileRegisteredSeatsNow();
            } catch (Exception e) {
                LOGGER.error("Scheduled registered-seat reconcile failed: {}", e.getMessage(), e);
            }
        }, SEAT_RECONCILE_INITIAL_DELAY_MINUTES, SEAT_RECONCILE_INTERVAL_MINUTES, TimeUnit.MINUTES);

        LOGGER.info("Watch-channel renewal scheduler started (renewal: initial {}h, every {}h; "
                        + "seat reconcile: initial {}m, every {}m).",
                RENEWAL_INITIAL_DELAY_HOURS, RENEWAL_INTERVAL_HOURS,
                SEAT_RECONCILE_INITIAL_DELAY_MINUTES, SEAT_RECONCILE_INTERVAL_MINUTES);
    }

    /**
     * Stops the renewal scheduler. Called on {@code invokerUnloaded}.
     */
    public synchronized void stopRenewalScheduler() {
        if (renewalScheduler != null) {
            renewalScheduler.shutdownNow();
            renewalScheduler = null;
            LOGGER.info("Watch-channel renewal scheduler stopped.");
        }
    }

    /**
     * Renews every channel in the active scan set that expires within a day.
     *
     * <p>KIM-474: driven by {@link CalendarDiscoveryService}, so renewal covers whichever set is
     * active. An Always On deployment whose renewal still walked only the consented users would
     * lose push notifications for every registered user roughly five days after enabling it — a
     * failure that looks like "capture just stopped working" days after the change that caused it.
     *
     * <p>KIM-474 / EF-07: the renewal pass is also where held channels are reconciled against the
     * live scan set ({@link #revokeSubscriptionsOutOfScope}). This is the only sweep guaranteed to
     * run on a schedule, so it is the only place a departure can be noticed at all.
     *
     * <p>An Always On renewal reports its mailboxes like every other sweep that resolves a scope.
     * It touches only the channels near expiry, so the rest are reported from their last delegated
     * outcome (see {@link ScannedMailboxReporter}) rather than flipped to PENDING. It also publishes
     * the departures it observes, exactly as the subscribe sweep does. An Opt-In renewal reports
     * nothing and publishes nothing, as before Always On existed.
     */
    public void renewExpiringChannels() {
        CalendarDiscoveryService.UserSet scanSet = discoveryService.discover("watch_renewal");
        long renewThreshold = System.currentTimeMillis() + (24 * 60 * 60 * 1000L); // 1 day from now
        ScannedMailboxReporter.SweepOutcomes outcomes = new ScannedMailboxReporter.SweepOutcomes();

        forEachUser(scanSet, "watch-channel renewal", userId -> {
            long expiration = watchStore.getExpiration(userId);
            if (expiration > 0 && expiration < renewThreshold) {
                LOGGER.info("Watch channel for user {} expires soon ({}). Renewing...", userId, expiration);
                recordingOutcome(outcomes, userId,
                        () -> renewWatchChannel(userId, scanSet.sourceOf(userId)));
            }
        });

        publishScopeDepartures(scanSet, "watch_renewal");
        revokeSubscriptionsOutOfScope(scanSet, "watch_renewal");
        reportScannedMailboxes(scanSet, outcomes, "watch_renewal");
    }

    /**
     * KIM-474 / EF-23: re-reads the registered-seat roster and brings the watch scope in line with it,
     * immediately.
     *
     * <p>Two callers, and the split matters. {@code GoogleCalendarSeatRegisteredHandler} calls it when
     * Meeting Agent pushes a licensed-seat notification — the fast path, which turns "a new Google
     * seat waits for the next sweep" into "a new Google seat is watched in seconds". The
     * {@link #SEAT_RECONCILE_INTERVAL_MINUTES}-minute task below calls it on a timer, and that is the
     * reconciliation path: a missed or undelivered notification costs nothing beyond the gap to the
     * next tick, which is what lets the handler acknowledge failures instead of retrying.
     *
     * <p><b>Gated on Always On.</b> An opt-in workspace scans the users who authorised their own
     * calendars, a seat registration changes nothing about that set, and a Meeting Agent round trip
     * per tick for every opt-in tenant would be pure cost. The flag is read through the discovery
     * seam, which is the one place in this extension that acts on it.
     *
     * <p>{@code refresh()} through {@link CalendarDiscoveryService#primeRegisteredSeats()} rather than
     * the staleness-gated {@code refreshIfStale()}: a caller reaching this method either has a
     * specific reason to believe the roster changed, or is the timer whose whole job is to notice that
     * it did. A TTL check here would answer "not yet" to both.
     *
     * <p>{@link #subscribeAllUsers()} is what applies the new set, and it is idempotent by
     * construction — a user with an unexpired channel is skipped on a store read with no Google call,
     * so a tick on an unchanged workspace costs one Meeting Agent round trip and N key-value reads.
     * It also carries the out-of-scope revoke, so a seat that was REVOKED stops being watched on the
     * same tick; Meeting Agent emits no revocation signal, so the timer is the only thing that can
     * notice that at all.
     *
     * @return true when a reconcile ran, false when Always On is off and there was nothing to do
     */
    public boolean reconcileRegisteredSeatsNow() {
        if (!discoveryService.isAlwaysOn()) {
            LOGGER.debug("Seat reconcile skipped: Always On is off, so the scan set is the users who "
                    + "authorised their own calendars and a seat change does not affect it.");
            return false;
        }

        LOGGER.info("Reconciling the registered-seat scan set now...");
        discoveryService.primeRegisteredSeats();
        subscribeAllUsers();
        return true;
    }

    /**
     * Performs an initial full sync to obtain the sync token for incremental sync.
     * This is required before creating a watch channel — we need a baseline sync token
     * so that when notifications arrive, we can fetch only the changes.
     */
    private String initializeSyncToken(Calendar calendarService, String userId,
                                       CalendarDiscoveryService.DiscoverySource source) {
        try {
            String existingSyncToken = watchStore.getSyncToken(userId);
            if (existingSyncToken != null) {
                LOGGER.info("User {} already has a sync token. Reusing.", userId);
                return existingSyncToken;
            }

            LOGGER.info("Performing initial full sync to obtain sync token for user {}...", userId);

            String pageToken = null;
            String syncToken = null;

            do {
                final String currentPageToken = pageToken;
                Events events = throttleAware(source, "baseline sync token for " + userId,
                        () -> calendarService.events().list(PRIMARY_CALENDAR)
                                .setPageToken(currentPageToken)
                                .execute());

                pageToken = events.getNextPageToken();
                syncToken = events.getNextSyncToken();
            } while (pageToken != null);

            if (syncToken != null) {
                LOGGER.info("Obtained initial sync token for user {}", userId);
            } else {
                LOGGER.warn("Failed to obtain sync token for user {} after full sync", userId);
            }

            return syncToken;
        } catch (IOException e) {
            LOGGER.error("Failed to initialize sync token for user {}: {}", userId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Finds the user ID associated with a given channel ID.
     * Used when processing incoming notifications to identify the user.
     *
     * @param channelId the channel ID from the notification headers
     * @return the user ID, or null if not found
     */
    public String findUserByChannelId(String channelId) {
        if (channelId == null) return null;
        // Deliberately NOT routed through CalendarDiscoveryService: this is the last-resort scan for
        // channels created before the reverse index existed, so by construction it can only ever
        // match a consented-era channel. Reading the registered roster here would put a Meeting Agent
        // round-trip on the path of every unresolvable notification, for a set that cannot contain
        // the answer. Delegated channels are always resolved by the token or the index above.
        //
        // Under Always On the consented list is not consulted at all (owner ruling, 2026-11-10):
        // every channel this extension creates there is indexed, so a miss here is a channel we do
        // not own, and reading the personal-consent list to answer it is the very thing either/or
        // forbids.
        if (discoveryService.isAlwaysOn()) {
            LOGGER.warn("No user found for channelId: {} (Always On: the consented list is not consulted)",
                    channelId);
            return null;
        }
        List<String> users = authenticationService.getAuthenticatedUsers();
        for (String userId : users) {
            String storedChannelId = watchStore.getChannelId(userId);
            if (channelId.equals(storedChannelId)) {
                return userId;
            }
        }
        LOGGER.warn("No user found for channelId: {}", channelId);
        return null;
    }

    /**
     * Resolves the user that owns an incoming push notification, in priority order:
     * <ol>
     *   <li>the channel token ({@code X-Goog-Channel-Token}, set to the userId at creation),
     *       <b>correlated against the watch row for {@code channelId}</b> — O(1) and race-free,
     *       since the value travels inside the notification itself;</li>
     *   <li>the {@code channelId -> userId} reverse index;</li>
     *   <li>a legacy scan of authenticated users, for channels created before indexing.</li>
     * </ol>
     *
     * <p>Every limb keys on something this extension wrote itself. Neither header is evidence on its
     * own — the webhook URL is a fixed path on the appliance, so both are attacker-supplied in the
     * general case (S-23).
     *
     * @param channelId    the {@code X-Goog-Channel-ID} header
     * @param channelToken the {@code X-Goog-Channel-Token} header (may be null/blank)
     * @return the owning userId, or {@code null} if it cannot be resolved
     */
    public String resolveUser(String channelId, String channelToken) {
        if (channelToken != null && !channelToken.isBlank()) {
            // S-23: ONE rule for both populations — the token is trusted when, and only when, a watch
            // row we wrote ourselves correlates it to THIS channel id.
            //
            // The consented limb used to be `isUserAuthenticated(channelToken)` alone, with the
            // channel-id correlation applied only on the Always On path. Both headers arrive from
            // OUTSIDE: Google echoes them, but so does anybody who can reach this webhook URL, and
            // the URL is a fixed, guessable path on the appliance. So a request carrying
            // X-Goog-Channel-Token: <any consented user's address> was attributed to that user and
            // triggered an incremental sync of their calendar on demand — the token was checked for
            // being a KNOWN NAME, which is not the same as being evidence.
            //
            // Correlating the channel id fixes that: we write a watch row only for a channel we
            // ourselves registered, so matching it means the caller reproduced a value they could
            // only have got from Google's own notification.
            //
            // This is NOT a behaviour change for the consented population, which is why the
            // Always On gate could go. createWatchChannel writes the reverse index
            // (putChannelUser) for EVERY channel it creates, consented or delegated, so a genuine
            // notification the token limb now declines is resolved to the same user one limb down.
            // What stops resolving is a token with no matching channel — i.e. exactly the forged
            // case, plus a stale channel we no longer track and would not renew anyway.
            if (channelId != null && channelId.equals(watchStore.getChannelId(channelToken))) {
                return channelToken;
            }
        }
        String byIndex = watchStore.getUserByChannelId(channelId);
        if (byIndex != null) {
            return byIndex;
        }
        return findUserByChannelId(channelId);
    }

    /**
     * Builds the Calendar client that registers, renews, or stops a watch channel.
     *
     * @param userId the user whose calendar the channel watches
     * @param source consented — the user's own refresh token, exactly as before; tenant-directory —
     *               a delegated credential impersonating that mailbox
     */
    /**
     * KIM-474 (scale): issues a Google call through the rate-limit backoff when — and only when — it
     * is aimed at a delegated mailbox.
     *
     * <p>The asymmetry is deliberate. Always On multiplies every call in this class by the number of
     * registered users in the workspace, which is what earns a quota refusal and a header saying how
     * long to wait. A tenant that has not enabled Always On issues a handful of these in total, is
     * not rate-limited, and must keep behaving under a fault exactly as it does today — so it is
     * handed straight through, with no retry and no wrapper between it and Google.
     *
     * @param source    the credential source; a consented read means "no backoff"
     * @param operation short label for the throttle log line
     * @param call      the Google call to issue
     * @param <T>       the call's result type
     * @return the call's result
     * @throws IOException from the underlying call, unwrapped
     */
    private static <T> T throttleAware(CalendarDiscoveryService.DiscoverySource source, String operation,
                                       GoogleThrottleBackoff.GoogleCall<T> call) throws IOException {
        if (!source.delegated()) {
            return call.execute();
        }
        return GoogleThrottleBackoff.execute(operation, call);
    }

    private Calendar getCalendarClient(String userId, CalendarDiscoveryService.DiscoverySource source) {
        try {
            if (source == CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS) {
                // Either/or: no personal token is used while Always On is on, whatever a stale
                // caller passed in.
                if (discoveryService.isAlwaysOn()) {
                    throw CalendarDiscoveryService.personalTokenRefused(userId);
                }
                GoogleProvider provider = googleProviderFactory.create();
                // Per-user token so the watch channel targets THIS user's calendar,
                // not the invoker-configured account.
                return provider.getGoogleClientForUser(userId);
            }
            return discoveryService.calendarClientFor(userId, source);
        } catch (MustAuthorizeException mustAuthorize) {
            // Propagate the platform's reauthorization signal unchanged, and unlogged. Wrapping it
            // hid the type from callers that act on it, and logging it here produced a second full
            // stack trace for a failure the calling sweep already reports once.
            throw mustAuthorize;
        } catch (CalendarDiscoveryService.PersonalTokenRefusedException refused) {
            throw refused;
        } catch (Exception e) {
            // No log here either: every caller of this method logs the failure it gets, with the
            // user id and the stack trace. Logging both ends doubled every genuine error too.
            throw new RuntimeException("Failed to create Google Calendar client for user " + userId, e);
        }
    }

    /**
     * Always On: this mailbox was just read through domain-wide delegation, so it is CONNECTED.
     * Re-asserted on every successful visit; the store writes before it publishes, so a lost event
     * would otherwise stand for ever.
     */
    private void publishDelegatedConnected(String userId, CalendarDiscoveryService.DiscoverySource source) {
        if (!source.delegated() || connectionStatusStore == null) {
            return;
        }
        mailboxStatus().reachable(userId);
    }

    /**
     * Always On: an attempt on this mailbox failed. DISCONNECTED is published when it was CONNECTED
     * before; a seat never reached gets no status. Our own missing callback URL is not a fact about
     * the mailbox and publishes nothing.
     */
    private void publishDelegatedUnreachable(String userId, CalendarDiscoveryService.DiscoverySource source,
                                             Exception failure) {
        if (!source.delegated() || connectionStatusStore == null
                || failure instanceof CallbackUrlMissingException) {
            return;
        }
        mailboxStatus().unreachable(userId, failure);
    }
}
