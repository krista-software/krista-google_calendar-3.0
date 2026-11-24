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
import app.krista.extensions.util.KeyValueStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.EsbCaGateway;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * WP-G1 — the workspace's <b>registered Meeting Agent seats</b>, fetched from Meeting Agent and
 * cached in the KV store.
 *
 * <p>This is the population Always On scans. The product owner withdrew tenant-wide scanning on
 * 2026-09-10 ("we won't scan an entire tenant blindly"), so "Always On" now means <em>every
 * registered, licensed Meeting Agent user of this workspace</em> — not every mailbox in the Google
 * Workspace account. Meeting Agent owns that roster; this store is the only place that reads it.
 *
 * <h3>The source of truth is Meeting Agent, and there is no second one</h3>
 * The roster comes from Meeting Agent's {@code listUsers} operation on {@code ca.identity-1-IN}
 * (KIM-492 WP-E16), over the one gateway this extension has. Only rows that are
 * <b>both</b> {@code status == ACTIVE} and
 * {@code licensedSeat == true} count: an Observer or a Delegate Admin has a row but no seat, their
 * calendar is deliberately not synced, and their presence on a meeting does not trigger capture.
 * Meeting Agent's default status filter is "every status", so {@code status=ACTIVE} is sent
 * explicitly <em>and</em> re-checked here — a filter that silently stops narrowing must not widen
 * what we scan.
 *
 * <h3>A failed fetch keeps the last good set</h3>
 * The roster changes when somebody is onboarded or offboarded, which is rare; MA being briefly
 * unreachable is not. Losing the set would demote a working Always On tenant to its consented users
 * for as long as the outage lasts — capture quietly narrowing because of an unrelated fault. So a
 * failed refresh logs and returns false, and the previous set stays in force. The set is also
 * persisted to the KV store, so a container restart during an MA outage still starts from the last
 * known roster rather than from nothing.
 *
 * <p>The distinction that matters downstream is <b>"never fetched"</b> versus <b>"fetched, and it is
 * empty"</b>. {@link #seats()} returns an empty {@link Optional} only for the former — a missing
 * signal, on which {@link CalendarDiscoveryService} degrades to the consented set. A successfully
 * fetched empty roster is an answer, not an absence: a workspace with no licensed seats captures
 * nothing, and pretending otherwise would scan calendars the tenant has not paid to capture.
 *
 * <h3>Freshness</h3>
 * {@link #refresh()} is forced (invoker load, and any caller that knows something changed);
 * {@link #refreshIfStale()} refreshes only past {@link #REFRESH_INTERVAL_MILLIS}. Discovery calls
 * the latter, so every sweep — batch sync, watch subscribe, renewal, teardown — works from a roster
 * at most six hours old without a timer thread of its own.
 *
 * <h3>Thread safety</h3>
 * The snapshot is a volatile immutable value; {@link #refresh()} is synchronized so two concurrent
 * sweeps cannot both issue the same paged fetch. Readers never block.
 */
@Service
@Singleton
public class RegisteredSeatStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegisteredSeatStore.class);

    /** How many skipped rows one log line names before it summarises the rest. */
    private static final int MAX_NAMED = 10;

    /**
     * How long a fetched roster is considered current. Six hours, matching the cadence agreed for
     * both extensions in the KIM-492 alignment plan and the interval the watch-channel renewal
     * scheduler already runs at — the one recurring sweep guaranteed to call discovery.
     */
    static final long REFRESH_INTERVAL_MILLIS = 6L * 60 * 60 * 1000;

    private final Invoker invoker;
    private final KeyValueStore keyValueStore;
    private final EsbCaGateway caGateway;

    /** The last known roster, or null when nothing has been loaded in this process yet. */
    private volatile Roster roster;

    /** Guards the one-time KV read, so a cold process reads the persisted roster exactly once. */
    private volatile boolean loadedFromStore;

    @Inject
    public RegisteredSeatStore(Invoker invoker, KeyValueStore keyValueStore,
                               EsbCaGateway caGateway) {
        this.invoker = invoker;
        this.keyValueStore = keyValueStore;
        this.caGateway = caGateway;
    }

    /**
     * One fetched roster: the seat addresses, lower-cased, and when they were read.
     *
     * @param emails    lower-cased seat addresses, in the order Meeting Agent returned them
     * @param fetchedAt epoch millis of the fetch that produced this set
     */
    record Roster(Set<String> emails, long fetchedAt) {

        Roster {
            emails = Set.copyOf(emails);
        }
    }

    // ── reading ───────────────────────────────────────────────────────────

    /**
     * The registered, licensed seats of this workspace.
     *
     * @return the roster, or {@link Optional#empty()} when no roster has ever been fetched — the
     *         absence of a signal, which callers must not read as "there are no seats"
     */
    public Optional<Set<String>> seats() {
        Roster current = currentRoster();
        return current == null ? Optional.empty() : Optional.of(current.emails());
    }

    /**
     * Whether one address is a registered, licensed seat of this workspace.
     *
     * <p>Purely in-memory — it is on the push-notification and single-user-sync paths, where an HTTP
     * round-trip per call would be indefensible. An unknown roster answers {@code false}: the
     * single-user paths then behave exactly as they do with Always On off, which is the same
     * fail-narrow posture discovery takes when the roster is missing.
     *
     * @param email the address to test; may be null
     * @return true only when a roster has been fetched and contains this address
     */
    public boolean isRegisteredSeat(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        Roster current = currentRoster();
        return current != null && current.emails().contains(normalise(email));
    }

    /** @return epoch millis of the roster in force, or 0 when there is none */
    public long fetchedAt() {
        Roster current = currentRoster();
        return current == null ? 0L : current.fetchedAt();
    }

    // ── refreshing ────────────────────────────────────────────────────────

    /**
     * Re-fetches the roster when the one in force is older than {@link #REFRESH_INTERVAL_MILLIS},
     * or when there is none at all.
     *
     * @return true when a fetch succeeded on this call, false when none was needed or one failed
     */
    public boolean refreshIfStale() {
        Roster current = currentRoster();
        if (current != null
                && System.currentTimeMillis() - current.fetchedAt() < REFRESH_INTERVAL_MILLIS) {
            return false;
        }
        return refresh();
    }

    /**
     * Fetches the roster from Meeting Agent, replacing the one in force only on success.
     *
     * <p>Never throws: the caller is a calendar sweep, and a roster it could not refresh is a reason
     * to carry on with the previous one, not to abort the sweep.
     *
     * @return true when the roster was refreshed, false when the fetch failed and the previous set
     *         (if any) still stands
     */
    public synchronized boolean refresh() {
        // KIM-492 WP-E16 (C1): the paging, the two-condition seat filter and the endpoint all live
        // behind the gateway now. What stays HERE is the part that is this store's own: a failed
        // fetch keeps the last good roster rather than demoting a working Always On tenant to its
        // consented users for the length of an unrelated outage.
        Optional<Set<String>> fetchedEmails = caGateway.listLicensedSeatEmails();

        if (fetchedEmails.isEmpty()) {
            LOGGER.error("[GCAL-SEATS] Could not read the registered Meeting Agent users. Keeping "
                    + "the {} seat(s) already held — Always On scans the last known roster rather "
                    + "than narrowing because of one failed call.", heldCount());
            return false;
        }

        Set<String> collected = new LinkedHashSet<>(fetchedEmails.get());
        Roster fetched = new Roster(collected, System.currentTimeMillis());
        this.roster = fetched;
        this.loadedFromStore = true;
        persist(fetched);

        LOGGER.info("[GCAL-SEATS] workspace={} seats={} — the registered Meeting Agent users Always "
                + "On will scan", workspaceId(), collected.size());
        if (collected.isEmpty()) {
            // A successfully fetched EMPTY roster is an answer, not an absence. Downstream,
            // Optional.empty() means "never fetched" and degrades to the consented set; an empty
            // set means this workspace has bought no seats, and scanning anyway would capture
            // calendars the tenant has not paid for.
            LOGGER.warn("[GCAL-SEATS] workspace={} has no ACTIVE, licensed Meeting Agent user. With "
                    + "Always On enabled this workspace scans no calendars at all. Register and "
                    + "license at least one user in Meeting Agent, or turn Always On off to keep "
                    + "capturing the users who connected their own calendars.", workspaceId());
        }
        return true;
    }

    // ── internals ─────────────────────────────────────────────────────────

    private Roster currentRoster() {
        Roster current = roster;
        if (current != null || loadedFromStore) {
            return current;
        }
        return loadFromStore();
    }

    /**
     * Reads the roster persisted by a previous process, exactly once.
     *
     * <p>A restart during a Meeting Agent outage is precisely when the in-memory set is empty and
     * the fetch is failing, which is the moment a demotion to the consented users would be least
     * welcome and hardest to explain.
     */
    private synchronized Roster loadFromStore() {
        if (loadedFromStore) {
            return roster;
        }
        loadedFromStore = true;
        try {
            Object raw = keyValueStore.get(storeKey());
            if (raw == null) {
                return null;
            }
            JsonObject stored = JsonParser.parseString(raw.toString()).getAsJsonObject();
            Set<String> emails = new LinkedHashSet<>();
            if (stored.has("emails") && stored.get("emails").isJsonArray()) {
                for (JsonElement element : stored.getAsJsonArray("emails")) {
                    if (!element.isJsonNull()) {
                        emails.add(normalise(element.getAsString()));
                    }
                }
            }
            Roster restored = new Roster(emails, stored.has("fetchedAt")
                    ? stored.get("fetchedAt").getAsLong() : 0L);
            this.roster = restored;
            LOGGER.info("[GCAL-SEATS] Restored {} registered Meeting Agent user(s) recorded at {} "
                    + "from the key-value store.", emails.size(), restored.fetchedAt());
            return restored;
        }
        catch (Exception cause) {
            LOGGER.warn("[GCAL-SEATS] Could not restore the registered Meeting Agent users from the "
                    + "key-value store; the next sweep refreshes them: {}", cause.getMessage());
            return null;
        }
    }

    private void persist(Roster fetched) {
        try {
            JsonArray emails = new JsonArray();
            fetched.emails().forEach(emails::add);
            JsonObject payload = new JsonObject();
            payload.add("emails", emails);
            payload.addProperty("fetchedAt", fetched.fetchedAt());
            keyValueStore.put(storeKey(), payload.toString());
        }
        catch (Exception cause) {
            // The in-memory roster is already in force; losing the persisted copy costs only the
            // head start after a restart.
            LOGGER.warn("[GCAL-SEATS] Could not persist the registered Meeting Agent users: {}",
                    cause.getMessage());
        }
    }

    private String storeKey() {
        String invokerId;
        try {
            invokerId = invoker.getInvokerId();
        }
        catch (Exception cause) {
            invokerId = "unknown";
        }
        return "gcal_registered_seats:" + invokerId;
    }

    private String workspaceId() {
        try {
            String workspaceId = invoker.getWorkspaceId();
            return workspaceId == null || workspaceId.isBlank() ? null : workspaceId.trim();
        }
        catch (Exception cause) {
            LOGGER.warn("[GCAL-SEATS] Could not read this invoker's workspace id: {}",
                    cause.getMessage());
            return null;
        }
    }

    private int heldCount() {
        Roster current = currentRoster();
        return current == null ? 0 : current.emails().size();
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Names a bounded sample of a skipped population for a log line. */
    static String sample(Set<String> values) {
        if (values.isEmpty()) {
            return "[]";
        }
        StringBuilder text = new StringBuilder("[");
        int shown = 0;
        for (String value : values) {
            if (shown == MAX_NAMED) {
                text.append(", +").append(values.size() - shown).append(" more");
                break;
            }
            if (shown > 0) {
                text.append(", ");
            }
            text.append(value);
            shown++;
        }
        return text.append(']').toString();
    }
}
