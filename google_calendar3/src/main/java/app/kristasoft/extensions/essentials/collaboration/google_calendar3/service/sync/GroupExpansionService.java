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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationException;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationProvider;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.directory.Directory;
import com.google.api.services.directory.model.Member;
import com.google.api.services.directory.model.Members;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WP-DL — expands a Google Group invited to a meeting into the people actually invited, so that a
 * registered user who was reached through a distribution list is a first-class attendee rather than
 * an address nobody can resolve.
 *
 * <h3>The problem this exists to solve</h3>
 * Meeting Agent's dashboard visibility, its {@code INTERNAL_ONLY} / {@code MEETING_AGENT_USERS}
 * recipient scopes and its enrolment nudge all key on explicit attendee rows. A person invited via
 * {@code engineering@acme.example} produces exactly one attendee row — the group address — so they
 * cannot see the meeting in MA and do not receive its report, even though the answer to "were you
 * invited?" is plainly yes. The extension is the only party holding a credential that can resolve
 * the group, so the expansion happens here, at ingest.
 *
 * <h3>Point-in-time, deliberately</h3>
 * The expansion is re-sent on every calendar update until the meeting ends, so the attendee set
 * records <em>who was invited when the meeting happened</em> — which is what a transcript's access
 * set should be, and it does not change retroactively as a distribution list churns. Current
 * membership is a different question, and AIQA keeps answering that one live at Q&amp;A time. The
 * group address itself stays in the attendee list exactly as before; members are added alongside it.
 *
 * <h3>Never at the cost of the meeting</h3>
 * Every failure here — an unauthorised scope, a group that is not a group, a quota refusal, a
 * malformed response — skips the expansion and returns what it has. An upsert that reached MA
 * before this feature existed must still reach MA now; the worst outcome of a failure is the
 * behaviour of the previous release.
 *
 * <h3>Logging is bounded by the cache, not by a counter</h3>
 * This runs once per event, so a naive WARN would produce one line per event per sweep. Both the
 * positive and the negative answers are cached for {@link #CACHE_TTL_MILLIS}, and the log is written
 * when the cache entry is <em>created</em> — so a group that does not exist, or a scope that has not
 * been granted, is reported once per TTL window rather than once per meeting. The window is also
 * what makes a re-grant take effect without a restart.
 *
 * <h3>Thread safety</h3>
 * The memo is a {@link ConcurrentHashMap} of immutable values; two sweeps racing on the same group
 * may both fetch it once, which is harmless. Safe on the virtual-thread fan-out in
 * {@code SendEventsToMAService}.
 */
@Service
@Singleton
public class GroupExpansionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GroupExpansionService.class);

    /**
     * How long an expansion — or a "this address is not a group" answer — is trusted.
     *
     * <p>Six hours, matching {@link RegisteredSeatStore#REFRESH_INTERVAL_MILLIS}: both are "how
     * stale may a roster of people be", and two different answers to that question is one more thing
     * to explain when somebody asks why a new starter is not on a meeting yet.
     */
    static final long CACHE_TTL_MILLIS = 6L * 60 * 60 * 1000;

    /**
     * The largest expansion sent to Meeting Agent. A company-wide list has no business becoming an
     * attendee list — the meeting would be "visible to everyone", which is not a permission anybody
     * granted, and the upsert payload would dwarf the meeting itself. Truncation is WARNed, never
     * silent.
     */
    static final int MAX_MEMBERS = 500;

    /** Admin SDK maximum page size for {@code members.list}. */
    private static final int PAGE_SIZE = 200;

    /** Hard stop on paging: {@link #MAX_MEMBERS} is reached long before this on any real group. */
    private static final int MAX_PAGES = 25;

    /** How often the same "expansion is switched off" INFO may be repeated. */
    private static final long INFO_WINDOW_MILLIS = 15L * 60 * 1000;

    /** Roles whose holders were genuinely invited. Anything else is not a recipient. */
    private static final Set<String> INVITED_ROLES = Set.of("MEMBER", "MANAGER", "OWNER");

    private final Invoker invoker;
    private final KeyValueStore keyValueStore;
    private final DomainWideDelegationProvider delegationProvider;

    /** groupAddress (lower-cased) -> the answer for it, positive or negative. */
    private final Map<String, Expansion> memo = new ConcurrentHashMap<>();

    /** When the delegated credential was last refused for the group scope; 0 = not refused. */
    private final AtomicLong scopeDeniedAt = new AtomicLong();

    /** Last time the "no admin credential, expansion is off" INFO was written. */
    private final AtomicLong lastDisabledInfoAt = new AtomicLong();

    @Inject
    public GroupExpansionService(Invoker invoker,
                                 KeyValueStore keyValueStore,
                                 DomainWideDelegationProvider delegationProvider) {
        this.invoker = invoker;
        this.keyValueStore = keyValueStore;
        this.delegationProvider = delegationProvider;
    }

    /**
     * One resolved answer about one address.
     *
     * @param group   true when the address is a Google Group in this Workspace account
     * @param members its member addresses, lower-cased; always empty when {@code group} is false
     * @param resolvedAt epoch millis, for the TTL
     */
    record Expansion(boolean group, List<String> members, long resolvedAt) {

        Expansion {
            members = List.copyOf(members);
        }

        boolean fresh(long now) {
            return now - resolvedAt < CACHE_TTL_MILLIS;
        }

        static Expansion notAGroup(long now) {
            return new Expansion(false, List.of(), now);
        }
    }

    // ── the entry point ───────────────────────────────────────────────────

    /**
     * Expands whichever of {@code attendeeEmails} are Google Groups on this workspace's own domain.
     *
     * <p>Never throws. An address that is not a group, a group that cannot be read, or a credential
     * that has not been authorised all produce "no expansion for that address", and the caller sends
     * the meeting exactly as it would have before.
     *
     * @param attendeeEmails the event's attendee addresses; may be null or empty
     * @return group address (lower-cased) → member addresses (lower-cased), in attendee order;
     *         empty when nothing expanded. Never null.
     */
    public Map<String, List<String>> expand(Collection<String> attendeeEmails) {
        if (attendeeEmails == null || attendeeEmails.isEmpty()) {
            return Map.of();
        }
        if (!expansionAvailable()) {
            return Map.of();
        }

        String domain = expansionDomain();
        if (domain == null) {
            return Map.of();
        }

        Map<String, List<String>> expansions = new LinkedHashMap<>();
        for (String candidate : candidatesOnDomain(attendeeEmails, domain)) {
            Expansion expansion = resolve(candidate);
            if (expansion != null && expansion.group() && !expansion.members().isEmpty()) {
                expansions.put(candidate, expansion.members());
            }
        }
        return expansions;
    }

    /**
     * Whether an admin credential exists to expand with.
     *
     * <p>Group membership is an Admin SDK question, and an Opt-In tenant that never configured
     * domain-wide delegation has no identity that may ask it — there is no per-user OAuth grant that
     * substitutes. That is not a fault, so it is INFO rather than WARN, and it is throttled because
     * this method runs once per event.
     */
    private boolean expansionAvailable() {
        String gap;
        try {
            gap = delegationProvider.configurationGap();
        }
        catch (Exception cause) {
            LOGGER.warn("[GCAL-GROUPS] Could not read the delegation configuration; skipping group "
                    + "expansion for this event: {}", cause.getMessage());
            return false;
        }
        if (gap != null) {
            if (throttled(lastDisabledInfoAt, INFO_WINDOW_MILLIS)) {
                LOGGER.info("[GCAL-GROUPS] Group expansion is off because {}. Meetings are sent with "
                        + "the group address as the only attendee, as before — members invited "
                        + "through a distribution list will not see the meeting in Meeting Agent. "
                        + "Configure domain-wide delegation to enable it.", gap);
            }
            return false;
        }
        long deniedAt = scopeDeniedAt.get();
        if (deniedAt != 0 && System.currentTimeMillis() - deniedAt < CACHE_TTL_MILLIS) {
            return false; // already reported; retried once the window lapses
        }
        return true;
    }

    /**
     * The domain whose addresses may be probed as groups.
     *
     * <p>Restricting this matters for two independent reasons. It keeps an Admin SDK round-trip off
     * every external attendee on every meeting, and — more importantly — asking the customer's
     * directory about an outsider's address is not something this feature needs to do in order to
     * work.
     *
     * <p>{@code tenantDomain} is the explicit answer when it is set. It is optional, so when it is
     * blank the domain of {@code tenantAdminEmail} stands in: domain-wide delegation is granted over
     * exactly that Workspace account, and the admin address is mandatory whenever delegation is
     * configured at all, so it is always available here and always names the right account.
     *
     * @return the lower-cased domain, or null when neither is readable
     */
    String expansionDomain() {
        String configured = delegationProvider.getTenantDomain();
        if (configured != null && !configured.isBlank()) {
            return configured.trim().toLowerCase(Locale.ROOT);
        }
        String adminEmail = delegationProvider.getAdminEmail();
        int at = adminEmail == null ? -1 : adminEmail.indexOf('@');
        if (at < 0 || at == adminEmail.length() - 1) {
            LOGGER.warn("[GCAL-GROUPS] Neither 'tenantDomain' nor a usable 'tenantAdminEmail' is "
                    + "configured, so there is no domain to expand groups on; skipping.");
            return null;
        }
        return adminEmail.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }

    /** The distinct attendee addresses on {@code domain}, lower-cased, in first-seen order. */
    private Set<String> candidatesOnDomain(Collection<String> attendeeEmails, String domain) {
        Set<String> candidates = new LinkedHashSet<>();
        String suffix = "@" + domain;
        for (String email : attendeeEmails) {
            if (email == null || email.isBlank()) {
                continue;
            }
            String normalised = email.trim().toLowerCase(Locale.ROOT);
            if (normalised.endsWith(suffix)) {
                candidates.add(normalised);
            }
        }
        return candidates;
    }

    // ── resolution ────────────────────────────────────────────────────────

    /**
     * The answer for one address: from the memo, then from the key-value store, then from Google.
     *
     * @return the resolved answer, or null when it could not be resolved this time (a transient
     *         fault — deliberately NOT cached, so the next sweep tries again)
     */
    private Expansion resolve(String address) {
        long now = System.currentTimeMillis();

        Expansion cached = memo.get(address);
        if (cached != null && cached.fresh(now)) {
            return cached;
        }
        if (cached == null) {
            Expansion stored = readFromStore(address);
            if (stored != null && stored.fresh(now)) {
                memo.put(address, stored);
                return stored;
            }
        }

        Expansion fetched = fetch(address, now);
        if (fetched != null) {
            memo.put(address, fetched);
            writeToStore(address, fetched);
        }
        return fetched;
    }

    /**
     * Asks Google whether {@code address} is a group and, if so, who is in it.
     *
     * @return the answer, or null on a transient fault
     */
    private Expansion fetch(String address, long now) {
        Directory directory;
        try {
            directory = delegationProvider.directoryClientForGroups();
        }
        catch (DomainWideDelegationException cause) {
            reportScopeDenied(address, cause);
            return null;
        }

        try {
            GoogleThrottleBackoff.execute("group lookup " + address,
                    () -> directory.groups().get(address).execute());
        }
        catch (IOException | RuntimeException cause) {
            if (notFound(cause)) {
                // The ordinary case by a wide margin: a person, not a distribution list. Cached so
                // the same address is not looked up again for every meeting they are on, and NOT
                // logged at WARN — this is not a problem.
                LOGGER.debug("[GCAL-GROUPS] {} is not a Google Group; no expansion.", address);
                return Expansion.notAGroup(now);
            }
            if (DomainWideDelegationProvider.isDelegationDenied(cause)) {
                reportScopeDenied(address, cause);
                return null;
            }
            // The service-account key feeds this call, so the cause is sanitised before it reaches
            // a log line with a stack trace attached.
            LOGGER.warn("[GCAL-GROUPS] Could not determine whether {} is a Google Group; sending the "
                    + "meeting without expanding it.", address,
                    DomainWideDelegationProvider.sanitized(cause));
            return null;
        }

        List<String> members = listMembers(directory, address);
        if (members == null) {
            return null;
        }
        return new Expansion(true, members, now);
    }

    /**
     * Pages {@code members.list} for one group.
     *
     * <p>{@code includeDerivedMembership=true} flattens nested groups for us, so a nested-group entry
     * in the response is a duplicate of members already listed individually — including it would put
     * a group address in the attendee list as though it were a person. Roles are filtered here rather
     * than through the {@code roles} query parameter, which does not combine with derived membership.
     *
     * @return the member addresses, lower-cased, or null on a fault the caller must not cache
     */
    private List<String> listMembers(Directory directory, String group) {
        Set<String> members = new LinkedHashSet<>();
        int skippedNested = 0;
        int skippedInactive = 0;
        int page = 0;
        String pageToken = null;
        boolean truncated = false;

        while (page < MAX_PAGES) {
            Members response;
            final String currentPageToken = pageToken;
            final int pageNumber = page + 1;
            try {
                response = GoogleThrottleBackoff.execute(
                        "group members page " + pageNumber + " of " + group,
                        () -> directory.members().list(group)
                                .setIncludeDerivedMembership(true)
                                .setMaxResults(PAGE_SIZE)
                                .setPageToken(currentPageToken)
                                .execute());
            }
            catch (IOException | RuntimeException cause) {
                if (DomainWideDelegationProvider.isDelegationDenied(cause)) {
                    reportScopeDenied(group, cause);
                    return null;
                }
                if (page == 0) {
                    LOGGER.warn("[GCAL-GROUPS] Could not list the members of {}; sending the meeting "
                            + "without expanding it.", group,
                            DomainWideDelegationProvider.sanitized(cause));
                    return null;
                }
                // A later page failed. A partial expansion is better than none — everybody collected
                // so far really is a member — but it must not be cached as though it were complete.
                LOGGER.warn("[GCAL-GROUPS] Listing {} failed on page {} after {} member(s); sending "
                        + "the partial expansion and retrying on the next sweep.",
                        group, pageNumber, members.size());
                return null;
            }

            List<Member> entries = response.getMembers();
            if (entries != null) {
                for (Member member : entries) {
                    if (member == null) {
                        continue;
                    }
                    String email = member.getEmail();
                    if (email == null || email.isBlank()) {
                        continue; // a member with no address cannot be invited to anything
                    }
                    if (isNestedGroup(member)) {
                        skippedNested++;
                        continue;
                    }
                    if (!invited(member)) {
                        skippedInactive++;
                        continue;
                    }
                    members.add(email.trim().toLowerCase(Locale.ROOT));
                    if (members.size() >= MAX_MEMBERS) {
                        truncated = true;
                        break;
                    }
                }
            }

            page++;
            pageToken = response.getNextPageToken();
            if (truncated || pageToken == null || pageToken.isBlank()) {
                break;
            }
        }

        if (truncated || page >= MAX_PAGES) {
            LOGGER.warn("[GCAL-GROUPS] {} has more than {} member(s); the expansion is truncated to "
                    + "the first {}. Meetings invited to a list this large are better modelled by "
                    + "inviting the people who actually attend.", group, MAX_MEMBERS, members.size());
        }
        LOGGER.info("[GCAL-GROUPS] group={} members={} skippedNested={} skippedInactive={} pages={}",
                group, members.size(), skippedNested, skippedInactive, page);
        return List.copyOf(members);
    }

    /**
     * Whether this entry is a nested group rather than a person.
     *
     * <p>Derived membership already flattens nested groups into their individual members, so a
     * {@code GROUP} entry here is a duplicate — and adding it would put a distribution list into the
     * attendee list dressed as an invitee, which is exactly the confusion this feature removes.
     * {@code CUSTOMER} entries ("the whole domain") are not addressable people either.
     */
    private static boolean isNestedGroup(Member member) {
        String type = member.getType();
        return type != null && !"USER".equalsIgnoreCase(type.trim());
    }

    /**
     * Whether this member was genuinely invited: an active account holding a membership role.
     *
     * <p>An absent {@code status} is treated as active. Google does not populate it for every
     * membership shape, and reading "the API did not say" as "suspended" would silently empty an
     * expansion — a failure that looks exactly like the feature not working.
     */
    private static boolean invited(Member member) {
        String status = member.getStatus();
        if (status != null && !status.isBlank() && !"ACTIVE".equalsIgnoreCase(status.trim())) {
            return false;
        }
        String role = member.getRole();
        return role == null || role.isBlank() || INVITED_ROLES.contains(role.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Records that the delegated credential was refused, with one WARN naming the exact scope.
     *
     * <p>This is the expected state for every tenant that configured domain-wide delegation before
     * this feature existed: they authorised two scopes, and this needs a third. It must read as an
     * instruction, not as a stack trace, and it must not repeat once per meeting — so it is recorded
     * for a TTL window, after which the credential is tried again and a re-grant takes effect with no
     * restart.
     */
    private void reportScopeDenied(String address, Throwable cause) {
        long now = System.currentTimeMillis();
        long previous = scopeDeniedAt.get();
        if (previous != 0 && now - previous < CACHE_TTL_MILLIS) {
            return;
        }
        scopeDeniedAt.set(now);
        LOGGER.warn("[GCAL-GROUPS] Google refused the delegated credential for reading groups (first "
                + "seen on {}), so meetings invited through a distribution list are sent with the "
                + "group address as their only attendee. A Google Workspace super-admin must ADD "
                + "this scope to the service account's existing Domain-wide delegation entry, "
                + "alongside the two already authorised: {}. Until then everything else keeps "
                + "working unchanged. Retried automatically within {} hours of this message.",
                address, DomainWideDelegationProvider.GROUP_SCOPE, CACHE_TTL_MILLIS / 3_600_000,
                DomainWideDelegationProvider.sanitized(cause));
    }

    private static boolean notFound(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof GoogleJsonResponseException response
                    && response.getStatusCode() == 404) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static boolean throttled(AtomicLong lastAt, long windowMillis) {
        long now = System.currentTimeMillis();
        long previous = lastAt.get();
        if (previous != 0 && now - previous < windowMillis) {
            return false;
        }
        return lastAt.compareAndSet(previous, now);
    }

    // ── persistence ───────────────────────────────────────────────────────

    private Expansion readFromStore(String address) {
        try {
            Object raw = keyValueStore.get(storeKey(address));
            if (raw == null) {
                return null;
            }
            JsonObject stored = JsonParser.parseString(raw.toString()).getAsJsonObject();
            List<String> members = new ArrayList<>();
            if (stored.has("members") && stored.get("members").isJsonArray()) {
                for (JsonElement element : stored.getAsJsonArray("members")) {
                    if (!element.isJsonNull()) {
                        members.add(element.getAsString());
                    }
                }
            }
            boolean group = stored.has("group") && stored.get("group").getAsBoolean();
            long resolvedAt = stored.has("resolvedAt") ? stored.get("resolvedAt").getAsLong() : 0L;
            return new Expansion(group, members, resolvedAt);
        }
        catch (Exception cause) {
            LOGGER.debug("[GCAL-GROUPS] Could not read the cached expansion for {}: {}",
                    address, cause.getMessage());
            return null;
        }
    }

    private void writeToStore(String address, Expansion expansion) {
        try {
            JsonArray members = new JsonArray();
            expansion.members().forEach(members::add);
            JsonObject payload = new JsonObject();
            payload.addProperty("group", expansion.group());
            payload.add("members", members);
            payload.addProperty("resolvedAt", expansion.resolvedAt());
            keyValueStore.put(storeKey(address), payload.toString());
        }
        catch (Exception cause) {
            // The in-process memo already holds it; losing the persisted copy costs one lookup after
            // a restart.
            LOGGER.debug("[GCAL-GROUPS] Could not cache the expansion for {}: {}",
                    address, cause.getMessage());
        }
    }

    private String storeKey(String address) {
        String invokerId;
        try {
            invokerId = invoker.getInvokerId();
        }
        catch (Exception cause) {
            invokerId = "unknown";
        }
        return "gcal_group_members:" + invokerId + ":" + address;
    }
}
