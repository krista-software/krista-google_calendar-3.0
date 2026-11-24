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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync;

import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.EsbCaGateway;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatus;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarWatchStore;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells Meeting Agent which mailboxes an Always On sweep is scanning and whether each is actually
 * reachable, so its console can show seats it would otherwise have no way to see.
 *
 * <p>Domain-wide delegation needs no per-user step, so without this a seat Google does not know
 * about, or one whose calendar cannot be read, is only ever a log line here.
 *
 * <h2>When a snapshot is sent</h2>
 * <ul>
 *   <li><b>Every Always On sweep that resolves a scope</b> — watch subscribe, the seat reconcile,
 *       renewal and the full sync all call {@link #report}.</li>
 *   <li><b>A degraded sweep sends an EMPTY snapshot carrying its reason.</b> It scanned nothing, so
 *       it has no rows; Meeting Agent stores the reason and prunes nothing on an empty snapshot.</li>
 *   <li><b>A healthy sweep with nothing in scope sends nothing.</b> An empty snapshot without a
 *       reason would tell Meeting Agent nothing it acts on.</li>
 *   <li><b>Opt-In sends nothing.</b> Meeting Agent keeps no Always On inventory for a calendar system
 *       in Opt-In mode, and the consented users already report themselves through their statuses.</li>
 * </ul>
 *
 * <h2>The four states</h2>
 * <ul>
 *   <li>{@value #SUBSCRIBED} — this sweep proved access (watch created, an existing watch's
 *       {@code events.list} verification succeeded, or the full sync read the calendar). For a
 *       mailbox this sweep did not touch, the last delegated read succeeded and a watch is held.</li>
 *   <li>{@value #PENDING} — in scope, not attempted in this sweep and nothing known about it yet.</li>
 *   <li>{@value #FAILED} — an attempt on this mailbox failed in this sweep, or, for a mailbox this
 *       sweep did not touch, the last delegated attempt failed.</li>
 *   <li>{@value #NOT_IN_DIRECTORY} — a registered seat that a complete read of the Workspace
 *       directory does not contain. Reported even though it is not scanned.</li>
 * </ul>
 *
 * <p>"Last known" for an untouched mailbox is read from {@link CalendarConnectionStatusStore}, which
 * under Always On is written only by delegated reads. That is what lets renewal — which touches only
 * the channels near expiry — report without flipping every other seat to {@value #PENDING}.
 *
 * <p>Best-effort: {@link #report} never throws, because this is an observability surface and a
 * capture sweep must not stop for it.
 */
@Service
public class ScannedMailboxReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScannedMailboxReporter.class);

    public static final String SUBSCRIBED = "SUBSCRIBED";
    public static final String PENDING = "PENDING";
    public static final String FAILED = "FAILED";
    public static final String NOT_IN_DIRECTORY = "NOT_IN_DIRECTORY";

    private static final String SCOPE_ENROLLED = "ENROLLED_USER";
    private static final String CALENDAR_SYSTEM = "GOOGLE";

    /**
     * What happened to each mailbox during one sweep. A mailbox with no entry was not attempted.
     * Thread-safe: a delegated sweep records from its virtual threads.
     */
    public static final class SweepOutcomes {

        /**
         * One mailbox's result in this sweep.
         *
         * @param failure null when the sweep proved access; otherwise what failed
         */
        public record Outcome(Throwable failure) {

            /** @return true when the sweep proved access to the mailbox */
            public boolean proved() {
                return failure == null;
            }
        }

        private final Map<String, Outcome> byUser = new ConcurrentHashMap<>();

        /** Records that this sweep proved access to the mailbox. */
        public void proved(String userId) {
            byUser.put(userId, new Outcome(null));
        }

        /** Records that this sweep's attempt on the mailbox failed, for no recorded reason. */
        public void failed(String userId) {
            failed(userId, null);
        }

        /**
         * Records that this sweep's attempt on the mailbox failed.
         *
         * @param userId the mailbox
         * @param cause  what failed, carried to the DISCONNECTED status as the provider error; may
         *               be null
         */
        public void failed(String userId, Throwable cause) {
            byUser.put(userId, new Outcome(cause != null ? cause
                    : new IllegalStateException("the attempt on this mailbox failed")));
        }

        /** @return true when this sweep recorded an outcome for the mailbox */
        public boolean recorded(String userId) {
            return byUser.containsKey(userId);
        }

        /** @return an immutable copy of every recorded outcome, keyed by mailbox */
        public Map<String, Outcome> all() {
            return Map.copyOf(byUser);
        }

        private Outcome of(String userId) {
            return byUser.get(userId);
        }
    }

    private final Invoker invoker;
    private final CalendarWatchStore watchStore;
    private final UserKeyIndexStore userKeyIndexStore;
    private final EsbCaGateway caGateway;
    private final CalendarConnectionStatusStore connectionStatusStore;

    @Inject
    public ScannedMailboxReporter(Invoker invoker,
                                  CalendarWatchStore watchStore,
                                  UserKeyIndexStore userKeyIndexStore,
                                  EsbCaGateway caGateway,
                                  CalendarConnectionStatusStore connectionStatusStore) {
        this.invoker = invoker;
        this.watchStore = watchStore;
        this.userKeyIndexStore = userKeyIndexStore;
        this.caGateway = caGateway;
        this.connectionStatusStore = connectionStatusStore;
    }

    /**
     * Reports one sweep to Meeting Agent. Never throws.
     *
     * @param scanSet   the sweep's resolved set
     * @param outcomes  what the sweep did to each mailbox
     * @param sweepName short label for the log lines
     */
    public void report(CalendarDiscoveryService.UserSet scanSet, SweepOutcomes outcomes, String sweepName) {
        try {
            if (scanSet == null || !scanSet.source().delegated()) {
                LOGGER.debug("[GCAL-DISCOVERY] sweep={} Opt-In mode: no mailbox snapshot is reported",
                        sweepName);
                return;
            }
            ScannedMailboxSnapshot snapshot = snapshot(scanSet, outcomes, Instant.now());
            if (snapshot.isEmpty() && snapshot.degradedReason() == null) {
                LOGGER.debug("[GCAL-DISCOVERY] sweep={} nothing to report to Meeting Agent", sweepName);
                return;
            }
            if (!caGateway.reportScannedMailboxes(snapshot)) {
                LOGGER.warn("[GCAL-DISCOVERY] sweep={} Meeting Agent did not accept the mailbox snapshot",
                        sweepName);
            }
        } catch (Exception failure) {
            LOGGER.warn("[GCAL-DISCOVERY] sweep={} could not report the mailbox snapshot: {}",
                    sweepName, failure.getMessage());
        }
    }

    /**
     * Builds the snapshot for one sweep. A degraded set is empty, so its snapshot is too: the
     * reason is what it reports.
     *
     * @param scanSet  the sweep's resolved set
     * @param outcomes what the sweep did to each mailbox
     * @param sweptAt  when the sweep ran
     * @return the snapshot, with one row per mailbox
     */
    ScannedMailboxSnapshot snapshot(CalendarDiscoveryService.UserSet scanSet, SweepOutcomes outcomes,
                                    Instant sweptAt) {
        long now = sweptAt.toEpochMilli();
        List<ScannedMailboxSnapshot.Mailbox> rows = new ArrayList<>();

        for (String userId : scanSet.userIds()) {
            String state = stateOf(userId, outcomes, now);
            String address = addressOf(userId);
            rows.add(new ScannedMailboxSnapshot.Mailbox(address, CALENDAR_SYSTEM, SCOPE_ENROLLED, state,
                    SUBSCRIBED.equals(state) ? channelIdOf(userId) : null, address));
        }
        for (String seat : scanSet.seatsNotInDirectory()) {
            rows.add(seatRow(seat, NOT_IN_DIRECTORY));
        }
        for (String seat : scanSet.seatsUnconfirmed()) {
            rows.add(seatRow(seat, PENDING));
        }
        return new ScannedMailboxSnapshot(scanSet.source().name(), scanSet.degradedReason(),
                sweptAt.toString(), rows);
    }

    private String stateOf(String userId, SweepOutcomes outcomes, long now) {
        SweepOutcomes.Outcome outcome = outcomes == null ? null : outcomes.of(userId);
        if (outcome != null) {
            return outcome.proved() ? SUBSCRIBED : FAILED;
        }
        CalendarConnectionStatus lastKnown = lastKnownStatus(userId);
        if (lastKnown == CalendarConnectionStatus.DISCONNECTED) {
            return FAILED;
        }
        return lastKnown == CalendarConnectionStatus.CONNECTED && hasActiveWatch(userId, now)
                ? SUBSCRIBED : PENDING;
    }

    private CalendarConnectionStatus lastKnownStatus(String userId) {
        if (connectionStatusStore == null) {
            return null;
        }
        try {
            return connectionStatusStore.get(userId, invoker.getInvokerId());
        } catch (Exception storeFailure) {
            LOGGER.debug("Could not read the last connection status for {}: {}", userId,
                    storeFailure.getMessage());
            return null;
        }
    }

    private static ScannedMailboxSnapshot.Mailbox seatRow(String seat, String state) {
        return new ScannedMailboxSnapshot.Mailbox(seat, CALENDAR_SYSTEM, SCOPE_ENROLLED, state, null, seat);
    }

    private boolean hasActiveWatch(String userId, long now) {
        try {
            return watchStore.getExpiration(userId) > now;
        } catch (Exception storeFailure) {
            LOGGER.debug("Could not read the watch expiry for {}: {}", userId, storeFailure.getMessage());
            return false;
        }
    }

    private String channelIdOf(String userId) {
        try {
            return watchStore.getChannelId(userId);
        } catch (Exception storeFailure) {
            LOGGER.debug("Could not read the watch channel for {}: {}", userId, storeFailure.getMessage());
            return null;
        }
    }

    /**
     * The address Meeting Agent joins this row on. A key with no recorded address falls back to the
     * key, as the Teams extension does: an unaddressable mailbox in scope is still something an
     * operator should see.
     */
    private String addressOf(String userId) {
        String email = CalendarConnectionStatusStore.resolveEmail(userId);
        if (email == null) {
            email = userKeyIndexStore.getEmailByKey(invoker.getInvokerId(), userId);
        }
        return email != null ? email : userId;
    }
}
