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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.LiveGoogleCalendarConfiguration;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationException;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import com.google.api.services.calendar.Calendar;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.Map;

/**
 * KIM-474 — the single answer to "whose calendars do I scan, and with what credential?".
 *
 * <p>Every scan path in this extension — the batch sync, the single-user sync, watch-channel
 * creation, renewal and teardown, and push-notification handling — goes through this class. That is
 * the whole point of it: an {@code if (alwaysOn)} scattered across five services is how one of them
 * ends up on the wrong credential, scanning the wrong set, months later. There is one branch, here.
 *
 * <h3>Either/or (owner ruling, 2026-11-10)</h3>
 * Always On selects ONE credential for every calendar path, and the other one is never consulted.
 * <dl>
 *   <dt>Always On off — {@link DiscoverySource#CONSENTED_USERS}</dt>
 *   <dd>The list of users who individually authorised this extension, held in the KV store by
 *       {@link GoogleCalendarAuthenticationService}. Each is read with their own stored refresh
 *       token via {@link GoogleProvider#getGoogleClientForUser(String)}. This is what the extension
 *       did before Always On existed, and it is unchanged.</dd>
 *   <dt>Always On on — {@link DiscoverySource#ENROLLED_USERS}</dt>
 *   <dd>The workspace's <b>registered Meeting Agent seats</b> ({@link RegisteredSeatStore}) that the
 *       customer's Google Workspace directory also vouches for, read on a domain-wide-delegation
 *       credential and on nothing else. A personal token is not used for discovery, for scope, for
 *       a status, for the single-user paths, or as a fallback when the delegated sweep cannot run —
 *       {@link #calendarClientFor} refuses to build one. The tokens stay in the store untouched, so
 *       switching Always On off goes straight back to Opt-In.</dd>
 * </dl>
 *
 * <h3>Always On means registered users, not the whole tenant (WP-G1, 2026-09-10)</h3>
 * Always On originally scanned every active mailbox in the Workspace account. The product owner
 * withdrew that on 2026-09-10 — <i>"we won't scan an entire tenant blindly"</i> — and there is no
 * setting anywhere that turns it back on. Always On scans the registered, licensed Meeting Agent
 * users of this workspace, and the Admin SDK listing is used only to confirm that each of them is a
 * member of the Workspace account (nothing else can be impersonated through domain-wide delegation).
 * A seat the directory does not know is skipped with a WARN rather than failing the sweep, because
 * an address MA holds and Google does not is a data problem for one person, not for the tenant.
 * {@link DiscoverySource#TENANT_DIRECTORY} and {@link TenantUserDirectory} survive, compiled and
 * tested, for the possible future mode; nothing produces that source today.
 *
 * <h3>A degraded sweep scans nothing</h3>
 * Creating a service account and having a Workspace super-admin authorise its client id are
 * customer actions that frequently have not happened when the flag is first switched on, and the
 * seat roster or the directory can be briefly unreadable. Every such failure returns an EMPTY,
 * delegated {@link UserSet} carrying {@link UserSet#degradedReason()} and one actionable ERROR — never
 * the consented users, never a throw. The callers treat an empty degraded set as "scan nothing,
 * revoke nothing, publish no statuses, keep renewing what is held" and report the reason to
 * Meeting Agent on an empty snapshot. Falling back to personal tokens was the old behaviour; it made
 * Connected mean two different things in one workspace, and it is gone.
 *
 * <h3>Thread safety</h3>
 * Stateless. {@link #discover(String)} is called once per sweep and its result is passed down, so a
 * sweep never sees the set change under it even if an admin toggles the flag mid-run.
 */
@Service
public class CalendarDiscoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarDiscoveryService.class);

    /**
     * WP-G1: {@link UserSet#degradedReason()} when the registered-seat roster has never been read.
     *
     * <p>A short, stable token rather than a sentence. Every other degradation names a piece of
     * Google configuration that is naturally greppable ({@code serviceAccountKey}, "Client ID is not
     * authorised"); this one is an outage between two of our own services, and callers that have to
     * tell it apart deserve something better than a substring of prose.
     */
    public static final String REGISTERED_SEATS_UNAVAILABLE = "registered-seats-unavailable";

    /** Where a sweep's user set came from, and therefore which credential reads each calendar. */
    public enum DiscoverySource {
        /** Users who individually authorised this extension; read with their own refresh token. */
        CONSENTED_USERS,
        /**
         * WP-G1: the workspace's registered, licensed Meeting Agent users that the Google Workspace
         * directory also vouches for; read via domain-wide delegation. This is what Always On means.
         */
        ENROLLED_USERS,
        /**
         * Every active mailbox in the Workspace account, read via domain-wide delegation.
         *
         * <p><b>Withdrawn by the product owner on 2026-09-10 and no longer produced.</b> Always On
         * scans {@link #ENROLLED_USERS}; there is no setting that selects this one. The constant and
         * {@link TenantUserDirectory#listActiveUserEmails()} are retained, compiled and unit-tested,
         * so a future tenant-wide mode is a deliberate change rather than a rediscovery — and so
         * that any historical value read back from a store still resolves.
         */
        TENANT_DIRECTORY;

        /**
         * Whether calendars in this set are read on a domain-wide-delegation credential rather than
         * an individual's stored refresh token.
         *
         * <p>Every scale behaviour keys on this, not on a named constant: parallel fan-out, the
         * rate-limit backoff, and the fallback that recovers a primary-calendar id from the
         * enumerated address. Comparing against a single constant is how one of those quietly stops
         * applying the day a second delegated source appears — which is exactly what
         * {@link #ENROLLED_USERS} is.
         *
         * @return true for every delegated source
         */
        public boolean delegated() {
            return this != CONSENTED_USERS;
        }
    }

    /**
     * One sweep's resolved scan set: who to scan, and which credential reads each of them. Resolved
     * once and immutable, so a sweep cannot see the set — or the credential for any user in it —
     * change under it while it runs.
     *
     * @param source          the sweep's mode, which is also the credential for every user in it
     * @param userIds         the user ids to scan — emails, or {@code wsContact_<id>} keys for
     *                        client users who authorised individually (Opt-In only)
     * @param tenantMembers   lower-cased addresses this sweep resolved as readable through
     *                        domain-wide delegation (WP-G1: registered Meeting Agent seats the
     *                        Workspace directory also vouches for); empty on a consented sweep
     * @param degradedReason  null on a clean resolution; otherwise why Always On could not resolve
     *                        its enrolled set this sweep. A degraded set is always empty.
     * @param seatsNotInDirectory registered seats a COMPLETE read of the Workspace directory does
     *                        not contain. Never scanned, and reported to Meeting Agent so an admin
     *                        can see them. Always empty when the directory could not be read.
     * @param seatsUnconfirmed registered seats absent from a directory listing that stopped early.
     *                        Their absence proves nothing, so they are not {@code seatsNotInDirectory}.
     */
    public record UserSet(DiscoverySource source, List<String> userIds,
                          Set<String> tenantMembers, String degradedReason,
                          List<String> seatsNotInDirectory, List<String> seatsUnconfirmed) {

        public UserSet {
            userIds = List.copyOf(userIds);
            tenantMembers = Set.copyOf(tenantMembers);
            seatsNotInDirectory = seatsNotInDirectory == null ? List.of() : List.copyOf(seatsNotInDirectory);
            seatsUnconfirmed = seatsUnconfirmed == null ? List.of() : List.copyOf(seatsUnconfirmed);
        }

        /** Convenience for a sweep that reports no seat outside the scan set. */
        public UserSet(DiscoverySource source, List<String> userIds,
                       Set<String> tenantMembers, String degradedReason) {
            this(source, userIds, tenantMembers, degradedReason, List.of(), List.of());
        }

        /** Convenience for a consented sweep, which has no tenant members by definition. */
        public UserSet(DiscoverySource source, List<String> userIds, String degradedReason) {
            this(source, userIds, Set.of(), degradedReason);
        }

        /**
         * The credential for one user of this set: the sweep's own source, for every user.
         *
         * <p>Either/or means there is no per-user exception left to make. A consented sweep reads
         * every user with their own token; a delegated sweep reads every user through domain-wide
         * delegation, and a user outside its set is not read at all — so answering
         * {@link DiscoverySource#CONSENTED_USERS} for them, as this once did, would hand a caller a
         * personal token under Always On.
         *
         * @param userId a user id from {@link #userIds()}
         * @return the credential source to read that user with
         */
        public DiscoverySource sourceOf(String userId) {
            return source;
        }

        /** @return true when Always On could not resolve its scan set this sweep */
        public boolean degraded() {
            return degradedReason != null;
        }

        public int size() {
            return userIds.size();
        }

        public boolean isEmpty() {
            return userIds.isEmpty();
        }
    }

    private final Invoker invoker;
    private final GoogleCalendarAuthenticationService authenticationService;
    private final GoogleProviderFactory googleProviderFactory;
    private final DomainWideDelegationProvider delegationProvider;
    private final TenantUserDirectory tenantUserDirectory;
    private final RegisteredSeatStore registeredSeatStore;
    private final LiveGoogleCalendarConfiguration liveConfiguration;

    @Inject
    public CalendarDiscoveryService(Invoker invoker,
                                    GoogleCalendarAuthenticationService authenticationService,
                                    GoogleProviderFactory googleProviderFactory,
                                    DomainWideDelegationProvider delegationProvider,
                                    TenantUserDirectory tenantUserDirectory,
                                    RegisteredSeatStore registeredSeatStore,
                                    LiveGoogleCalendarConfiguration liveConfiguration) {
        this.invoker = invoker;
        this.authenticationService = authenticationService;
        this.googleProviderFactory = googleProviderFactory;
        this.delegationProvider = delegationProvider;
        this.tenantUserDirectory = tenantUserDirectory;
        this.registeredSeatStore = registeredSeatStore;
        this.liveConfiguration = liveConfiguration;
    }

    public CalendarDiscoveryService(Invoker invoker,
                                    GoogleCalendarAuthenticationService authenticationService,
                                    GoogleProviderFactory googleProviderFactory,
                                    DomainWideDelegationProvider delegationProvider,
                                    TenantUserDirectory tenantUserDirectory,
                                    RegisteredSeatStore registeredSeatStore) {
        this(invoker, authenticationService, googleProviderFactory, delegationProvider,
                tenantUserDirectory, registeredSeatStore, new LiveGoogleCalendarConfiguration(invoker));
    }

    // ── the flag ──────────────────────────────────────────────────────────

    /**
     * Reads the Always On invoker attribute LIVE, so a Studio toggle takes effect on the next sweep
     * with no restart.
     *
     * <p>Degrades to {@code false} on any read failure: an unreadable attribute must never widen
     * capture across a whole tenant.
     *
     * @return true when the tenant has opted into Always On capture
     */
    public boolean isAlwaysOn() {
        try {
            return Constants.readAlwaysOn(liveConfiguration.attributes());
        } catch (Exception cause) {
            LOGGER.warn("Could not read the '{}' attribute; treating Always On as off: {}",
                    Constants.ALWAYS_ON, cause.getMessage());
            return false;
        }
    }

    /**
     * Validates the domain-wide-delegation configuration when Always On is enabled — and only when
     * that configuration is actually there.
     *
     * <p><strong>Exactly the map handed in is validated.</strong> Studio's Setup form carries every
     * attribute and omits a field the admin cleared, so filling absent keys back in from the live
     * invoker (as this used to) validated a key or admin email the admin had just removed — and when
     * that stale value failed, the admin could not save the cleared state at all. The Save
     * Configuration catalog request, the other caller, completes its partial form from the saved
     * configuration itself before calling here.
     *
     * <p><strong>Absent credentials mean "nothing to validate yet", not failure.</strong> Both
     * credentials are optional on the invoker: Always On without them is a legal, documented state
     * in which nothing is captured ({@link #discover} degrades to an empty scan with an ERROR naming
     * the gap on every sweep). So an admin may switch Always On on, save, and add
     * the service-account key and admin email later — the delegation check runs the first time both
     * are present, which is the same "no email, no authentication; email, normal flow" rule the Teams
     * extension applies to its service-account email. Refusing to save until the credentials were in
     * place forced them to be pasted in one sitting and, worse, blocked removing a bad key.
     */
    public void validateDelegatedAccessIfAlwaysOn(Map<String, Object> candidateAttributes)
            throws DomainWideDelegationException {
        Map<String, Object> attributes = candidateAttributes == null ? Map.of() : candidateAttributes;
        if (!Constants.readAlwaysOn(attributes)) {
            return;
        }
        String gap = Constants.missingDelegationFields(attributes);
        if (gap != null) {
            LOGGER.info("[GCAL-DWD] Always On is on but {} {} not supplied — saving without a delegation "
                    + "check; no calendar is scanned until both are configured",
                    gap, gap.contains(",") ? "are" : "is");
            return;
        }
        delegationProvider.validateDelegatedAccess(attributes);
    }

    // ── the seam ──────────────────────────────────────────────────────────

    /**
     * Resolves the set of users this sweep must scan, and logs the effective mode and set size once.
     *
     * @param sweepName a short label for the log line ({@code full_sync}, {@code watch_subscribe}, …)
     * @return the resolved set; never null, and never throws
     */
    public UserSet discover(String sweepName) {
        if (!isAlwaysOn()) {
            List<String> consented = consentedUsers();
            LOGGER.info("[GCAL-DISCOVERY] sweep={} mode=CONSENTED_USERS users={} alwaysOn=false",
                    sweepName, consented.size());
            return new UserSet(DiscoverySource.CONSENTED_USERS, consented, null);
        }

        String gap = delegationProvider.configurationGap();
        if (gap != null) {
            return degrade(sweepName, "Always On is enabled but " + gap, null);
        }

        // WP-G1: the roster Always On scans. Refreshed here rather than on a timer of its own —
        // discovery is the one thing every sweep already calls, so a six-hour TTL bounds staleness
        // on all of them at once, and the seat list is never fetched for an opt-in tenant at all.
        registeredSeatStore.refreshIfStale();
        Optional<Set<String>> registeredSeats = registeredSeatStore.seats();
        if (registeredSeats.isEmpty()) {
            return degrade(sweepName, REGISTERED_SEATS_UNAVAILABLE,
                    "Always On is enabled and domain-wide delegation is configured, but the list of "
                            + "registered Meeting Agent users for this workspace has never been read "
                            + "successfully, so there is nothing to scan",
                    "Check the 'serviceUrl' and 'serviceApiKey' attributes and that Meeting Agent is "
                            + "reachable from this extension; the next sweep retries.", null);
        }

        List<String> tenantUsers;
        boolean directoryComplete;
        try {
            TenantUserDirectory.Listing listing = tenantUserDirectory.listActiveUsers();
            tenantUsers = listing.emails();
            directoryComplete = listing.complete();
        } catch (DomainWideDelegationException cause) {
            String reason = DomainWideDelegationProvider.isDelegationDenied(cause)
                    ? "Always On is enabled but Google refused the domain-wide delegation — the service "
                      + "account's Client ID is not authorised for the required scopes"
                    : "Always On is enabled but the tenant's users could not be listed";
            return degrade(sweepName, reason, cause);
        }

        if (tenantUsers.isEmpty()) {
            return degrade(sweepName,
                    "Always On is enabled and domain-wide delegation works, but the Google Workspace "
                            + "account reported no active users", null);
        }

        Set<String> directory = new HashSet<>();
        for (String email : tenantUsers) {
            directory.add(email.toLowerCase(Locale.ROOT));
        }

        // The scan set is the registered seats, confined to the ones the Workspace directory
        // vouches for: domain-wide delegation can only ever mint a token for a member of the
        // delegating account, so a seat outside it is unreadable however much we would like to
        // read it. Seat order is preserved so the sweep's order is Meeting Agent's, not a hash's.
        List<String> scanSet = new ArrayList<>();
        List<String> notInDirectory = new ArrayList<>();
        for (String seat : registeredSeats.get()) {
            if (directory.contains(seat)) {
                scanSet.add(seat);
            }
            else {
                notInDirectory.add(seat);
            }
        }
        reportSeatsMissingFromDirectory(sweepName, notInDirectory);
        // A listing that stopped early cannot say a mailbox does not exist, only that it was not
        // reached, so those seats must not be reported to an admin as missing from Google.
        List<String> confirmedAbsent = directoryComplete ? notInDirectory : List.of();
        List<String> unconfirmed = directoryComplete ? List.of() : notInDirectory;

        Set<String> members = new HashSet<>(scanSet);

        LOGGER.info("[GCAL-DISCOVERY] sweep={} mode=ENROLLED_USERS users={} seats={} "
                        + "seatsNotInDirectory={} alwaysOn=true",
                sweepName, scanSet.size(), registeredSeats.get().size(), notInDirectory.size());
        if (scanSet.isEmpty()) {
            // Owner ruling, 2026-09-12: capture scope is SEAT and an empty seat roster scans NOBODY.
            // That is the product rule, not a fault — so it degrades nothing and throws nothing. But
            // "the extension captured no meetings" is indistinguishable from a broken integration
            // from the outside, so it must be greppable and actionable HERE, on the sweep, and not
            // only on the seat store's own refresh (which logs it once per six-hour roster fetch and
            // is silent on every sweep in between).
            //
            // The two causes are reported separately on purpose. "No licensed seats" is fixed in
            // Meeting Agent; "seats exist but Google does not list them" is fixed in the Google
            // Workspace admin console. One message carrying "0 registered user(s), 0 of them absent
            // from the directory" tells an operator neither.
            if (registeredSeats.get().isEmpty()) {
                LOGGER.warn("[GCAL-DISCOVERY] sweep={} Always On is ON but this workspace has NO "
                        + "ACTIVE, licensed Meeting Agent user, so NO calendars are scanned and "
                        + "nothing will be captured. Capture scope is registered Meeting Agent "
                        + "seats only. Register and license at least one user in Meeting Agent, or "
                        + "turn Always On off to capture the users who connected their own "
                        + "calendars instead.", sweepName);
            }
            else {
                LOGGER.warn("[GCAL-DISCOVERY] sweep={} Always On is ON but this sweep resolved NO "
                        + "calendars to scan: {} registered Meeting Agent user(s), {} of them absent "
                        + "from the Google Workspace directory. Nothing will be captured until a "
                        + "registered, licensed user exists whose address is also an active mailbox in "
                        + "the Workspace account.", sweepName, registeredSeats.get().size(),
                        notInDirectory.size());
            }
        }
        return new UserSet(DiscoverySource.ENROLLED_USERS, scanSet, members, null,
                confirmedAbsent, unconfirmed);
    }

    /**
     * One WARN per sweep naming the registered Meeting Agent users the Google Workspace directory
     * does not contain.
     *
     * <p>Skipping them is the only possible behaviour — a delegated credential impersonates a member
     * of the delegating Workspace account and nothing else — but it is also invisible from Meeting
     * Agent's side, where the user looks perfectly registered. The usual causes are an address that
     * is an alias rather than the primary email, a suspended or archived mailbox, and a seat on a
     * domain excluded by the {@code tenantDomain} attribute.
     */
    private void reportSeatsMissingFromDirectory(String sweepName, List<String> notInDirectory) {
        if (notInDirectory.isEmpty()) {
            return;
        }
        LOGGER.warn("[GCAL-DISCOVERY] sweep={} {} registered Meeting Agent user(s) are not active "
                + "mailboxes in the Google Workspace directory and are therefore NOT scanned: {}. "
                + "Usual causes: the address is an alias rather than the account's primary email, "
                + "the mailbox is suspended or archived, or the 'tenantDomain' attribute excludes "
                + "its domain.", sweepName, notInDirectory.size(),
                RegisteredSeatStore.sample(new LinkedHashSet<>(notInDirectory)));
    }

    /**
     * WP-G1: forces a refresh of the registered-seat roster, for the invoker-load hook.
     *
     * <p>Startup is the one moment the roster is worth re-reading whatever its age: the process has
     * just come up, {@code serviceUrl} / {@code serviceApiKey} may have changed while it was down,
     * and the very next thing the extension does is register watch channels for the scan set. It
     * lives here rather than on the extension class so the Always On flag keeps being read in
     * exactly one place — an opt-in tenant never issues the call at all.
     */
    public void primeRegisteredSeats() {
        if (!isAlwaysOn()) {
            return;
        }
        registeredSeatStore.refresh();
    }

    /**
     * Which credential a single named user must be read with, outside a sweep — the single-user
     * sync, watch-channel creation on authorisation, channel teardown, and the push-notification
     * path, all of which are handed a user id rather than discovering one.
     *
     * <p>Either/or: Always On off answers {@link DiscoverySource#CONSENTED_USERS} (the user's own
     * token, exactly as before Always On existed); Always On on answers
     * {@link DiscoverySource#ENROLLED_USERS} for everybody. There is no per-user exception — a
     * stored refresh token is not evidence of anything under Always On, because it is not used.
     * Whether the user may be read at all is {@link #isSyncable}'s question, not this one's: a
     * user who is not a registered seat is not syncable, and a teardown of their old channel on
     * the delegated credential fails harmlessly and lets the channel expire.
     *
     * @param userId the user id to read
     * @return the credential source to use
     */
    public DiscoverySource sourceFor(String userId) {
        return isAlwaysOn() ? DiscoverySource.ENROLLED_USERS : DiscoverySource.CONSENTED_USERS;
    }

    /**
     * Whether a single named user may be synced at all.
     *
     * <p>With Always On off this is exactly the pre-existing gate: only users who authorised are
     * syncable. With Always On on, the boundary is the registered Meeting Agent roster read through
     * a configured delegation (WP-G1) — the same population {@link #discover(String)} resolves, so a
     * mailbox cannot be reachable through a single-user call that a sweep would refuse to scan.
     * Under Always On with delegation unconfigured or no roster yet, nobody is syncable: the
     * consented list is not a fallback.
     *
     * @param userId the user id to test
     * @return true when a sync may proceed for this user
     */
    public boolean isSyncable(String userId) {
        if (isAlwaysOn()) {
            return looksLikeEmail(userId)
                    && delegationProvider.isConfigured()
                    && registeredSeatStore.seats().isPresent()
                    && registeredSeatStore.isRegisteredSeat(userId);
        }
        return authenticationService.isUserAuthenticated(userId);
    }

    /**
     * Raised by every personal-token client build attempted while Always On is on. A type of its
     * own so callers that wrap client failures can let it through unwrapped, and an operator sees
     * the same sentence whichever path tried.
     */
    public static final class PersonalTokenRefusedException extends IllegalStateException {

        PersonalTokenRefusedException(String userId) {
            super("Always On is on, so " + userId + " is read through domain-wide delegation only; "
                    + "personal Google sign-ins are not used for any calendar operation");
        }
    }

    /**
     * The failure every personal-token client build raises while Always On is on.
     *
     * @param userId the user the build was for
     * @return the exception to throw
     */
    public static PersonalTokenRefusedException personalTokenRefused(String userId) {
        return new PersonalTokenRefusedException(userId);
    }

    // ── credential selection ──────────────────────────────────────────────

    /**
     * Builds the Calendar client for one user under the given source.
     *
     * @param userId the user whose calendar to read
     * @param source the credential source, from {@link #discover(String)} or {@link #sourceFor}
     * @return an authenticated Calendar client
     * @throws IllegalStateException when the client cannot be built — including a personal-token
     *                               client asked for while Always On is on; callers skip that mailbox
     */
    public Calendar calendarClientFor(String userId, DiscoverySource source) {
        if (source == DiscoverySource.CONSENTED_USERS) {
            requireOptIn(userId);
            return googleProviderFactory.create().getGoogleClientForUser(userId);
        }
        return delegatedClient(userId);
    }

    /**
     * Overload for callers that already hold a {@link GoogleProvider} — the batch sync builds one
     * per user for its attribute reads. Reusing it keeps the Always-On-off path byte-identical to
     * what it was before this seam existed: the exact same
     * {@code provider.getGoogleClientForUser(userId)} call on the exact same provider instance.
     *
     * @param consentedProvider the provider already built for this user's attribute reads
     * @param userId            the user whose calendar to read
     * @param source            the credential source
     * @return an authenticated Calendar client
     */
    public Calendar calendarClientFor(GoogleProvider consentedProvider, String userId, DiscoverySource source) {
        if (source == DiscoverySource.CONSENTED_USERS) {
            requireOptIn(userId);
            return consentedProvider.getGoogleClientForUser(userId);
        }
        return delegatedClient(userId);
    }

    /**
     * The last line of the either/or rule: no personal-token client is built while Always On is
     * on, whichever path asked. Read live, so a sweep resolved just before the flag flipped stops
     * using tokens on its next user rather than at its end.
     */
    private void requireOptIn(String userId) {
        if (isAlwaysOn()) {
            throw personalTokenRefused(userId);
        }
    }

    private Calendar delegatedClient(String userId) {
        try {
            return delegationProvider.calendarClientFor(userId);
        } catch (DomainWideDelegationException cause) {
            throw new IllegalStateException(
                    "Could not build a delegated Google Calendar client for " + userId + ": "
                            + cause.getMessage(), cause);
        }
    }

    // ── internals ─────────────────────────────────────────────────────────

    private List<String> consentedUsers() {
        return authenticationService.getAuthenticatedUsers();
    }

    /**
     * Returns an EMPTY, delegated scan set carrying the reason, with one actionable ERROR naming
     * the missing configuration and the exact admin action.
     *
     * <p>ERROR rather than WARN on purpose: the tenant has switched on a feature that is not doing
     * what they asked, and the only person who can fix it is an administrator.
     */
    private UserSet degrade(String sweepName, String reason, Throwable cause) {
        return degrade(sweepName, reason, reason, DomainWideDelegationProvider.remediation(), cause);
    }

    /**
     * As above, but with the machine-readable reason separated from the human explanation.
     *
     * <p>{@link UserSet#degradedReason()} is read by tests, by the scanned-mailbox report and by
     * anything that has to recognise <em>which</em> degradation happened, so for a cause with no
     * single obvious phrase to grep for — {@value #REGISTERED_SEATS_UNAVAILABLE} — the short token
     * is carried on the value and the sentence goes to the log.
     *
     * <p>The set is empty and its source is delegated, never the consented users: under Always On
     * a personal token is not a fallback (owner ruling, 2026-11-10). Callers scan nothing, revoke
     * nothing and publish no statuses for it, and keep renewing the channels they hold.
     *
     * @param reason      the value carried on the returned {@link UserSet}
     * @param explanation the sentence the ERROR line leads with
     * @param remediation what an administrator should do about it
     */
    private UserSet degrade(String sweepName, String reason, String explanation,
                            String remediation, Throwable cause) {
        String message = explanation + ". Scanning NO calendars this sweep — meetings will not be "
                + "captured until this is fixed; channels already held keep being renewed. "
                + remediation;
        if (cause != null) {
            LOGGER.error("[GCAL-DISCOVERY] sweep={} mode=ENROLLED_USERS users=0 alwaysOn=true degraded=true — {}",
                    sweepName, message, cause);
        } else {
            LOGGER.error("[GCAL-DISCOVERY] sweep={} mode=ENROLLED_USERS users=0 alwaysOn=true degraded=true — {}",
                    sweepName, message);
        }
        return new UserSet(DiscoverySource.ENROLLED_USERS, List.of(), Set.of(), reason);
    }

    /**
     * A delegated credential is minted for an email address. The consented store also holds opaque
     * {@code wsContact_<uuid>} client keys, which are not addresses and cannot be impersonated.
     */
    private static boolean looksLikeEmail(String userId) {
        return userId != null && userId.indexOf('@') > 0;
    }
}
