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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors;

import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.LiveGoogleCalendarConfiguration;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.directory.Directory;
import com.google.api.services.directory.DirectoryScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * KIM-474 — builds Google clients that act <em>as</em> an arbitrary user of the customer's Google
 * Workspace account, using a service account with domain-wide delegation (DWD).
 *
 * <h3>Why this class exists alongside {@link GoogleProvider}, not instead of it</h3>
 * Microsoft and Google are not symmetric here, and the difference is structural rather than
 * cosmetic. A Microsoft application registration can be granted <em>application permissions</em> by
 * a tenant admin and then act tenant-wide with no user in the loop. A Google OAuth client id and
 * secret cannot: they only ever mint tokens for a user who personally consented, which is exactly
 * what {@link GoogleProvider#getGoogleClientForUser(String)} does with a stored refresh token.
 * Reading every mailbox in a Workspace account therefore needs a <em>different credential
 * artefact</em> — a service account whose numeric client id a Workspace super-admin has authorised
 * against named scopes.
 *
 * <p>{@link GoogleProvider} is untouched and remains the ONLY path used while Always On is off.
 * This class is reached solely through {@code CalendarDiscoveryService}, and only when the tenant
 * has both switched Always On on and finished the DWD configuration.
 *
 * <h3>Least privilege</h3>
 * The delegated path only ever reads calendars, looks users up, expands groups and registers push
 * channels, so every scope it requests is read-only ({@link #CALENDAR_SCOPE},
 * {@link #DIRECTORY_SCOPE}, {@link #GROUP_SCOPE}). Catalog operations that write to a calendar
 * (schedule, cancel, accept…) continue to run on the individual user's own consented credential and
 * are unaffected. Narrower scopes are also a smaller thing to ask a customer's super-admin to
 * authorise.
 *
 * <p>Each client requests only the scopes its own calls need, rather than one union: a delegated
 * token is issued for the whole requested set or not at all, so a shared scope list would make every
 * feature fail whenever a customer had not granted the newest one.
 *
 * <h3>Secret handling</h3>
 * The service-account JSON key is a private key. It is read from the invoker attributes on demand,
 * held only for the duration of a credential build, and is never logged, echoed, or placed in an
 * exception message — see {@link #redact(String)} and {@link #sanitized(Throwable)}, which are
 * applied at the throw site rather than at each log site so the guarantee survives new call sites.
 *
 * <h3>Thread safety</h3>
 * Stateless and immutable apart from the injected {@link Invoker}; every method builds its own
 * transport and credential. Safe to call from the virtual-thread fan-out in
 * {@code SendEventsToMAService}.
 */
@Service
public class DomainWideDelegationProvider {

    /**
     * The Calendar scope the super-admin must authorise. Read-only is sufficient for everything the
     * tenant-wide scan does: {@code events.list}, {@code calendars.get("primary")},
     * {@code events.watch} and {@code channels.stop} all accept it.
     */
    public static final String CALENDAR_SCOPE = CalendarScopes.CALENDAR_READONLY;

    /** The Admin SDK scope required to enumerate the account's users. */
    public static final String DIRECTORY_SCOPE = DirectoryScopes.ADMIN_DIRECTORY_USER_READONLY;

    /**
     * WP-DL: the Admin SDK scope required to expand a Google Group into its members —
     * {@code admin.directory.group.readonly}.
     *
     * <p><b>One scope, not two.</b> Google accepts this scope for both calls the expansion makes:
     * {@code groups.get} (is this address a group?) and {@code members.list} (who is in it?). The
     * narrower {@code admin.directory.group.member.readonly} covers only the second, so asking for
     * this one is a single line for a super-admin to add rather than two, and it is still read-only.
     *
     * <p><b>Deliberately NOT added to {@link #directoryClientForAdmin()}.</b> A delegated token is
     * all-or-nothing: requesting a scope the super-admin has not authorised fails the whole token
     * request, not just the call that needed it. Folding this into the user-enumeration client would
     * therefore break Always On discovery outright for every tenant that granted the original two
     * scopes — turning an additive feature into a fleet-wide outage on upgrade. It lives on its own
     * client ({@link #directoryClientForGroups()}) so that a tenant which has not re-granted loses
     * exactly the group expansion and nothing else.
     */
    public static final String GROUP_SCOPE = DirectoryScopes.ADMIN_DIRECTORY_GROUP_READONLY;

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainWideDelegationProvider.class);
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    /** Matches a whole PEM block, including the private key body between the markers. */
    private static final Pattern PEM_BLOCK =
            Pattern.compile("-----BEGIN[^-]*-----.*?-----END[^-]*-----", Pattern.DOTALL);

    /**
     * Matches any long unbroken base64/base64url run — key material, access tokens, and signed
     * assertions all look like this, and none of them belong in a log line. Ordinary prose, URLs and
     * email addresses break on characters outside the class well before 40 chars.
     */
    private static final Pattern LONG_SECRET_RUN = Pattern.compile("[A-Za-z0-9+/=_-]{40,}");

    private static final int MAX_DIAGNOSTIC_LENGTH = 300;
    private static final int MAX_CAUSE_DEPTH = 6;

    private final LiveGoogleCalendarConfiguration liveConfiguration;

    @Inject
    public DomainWideDelegationProvider(LiveGoogleCalendarConfiguration liveConfiguration) {
        this.liveConfiguration = liveConfiguration;
    }

    public DomainWideDelegationProvider(Invoker invoker) {
        this(new LiveGoogleCalendarConfiguration(invoker));
    }

    // ── configuration ─────────────────────────────────────────────────────

    /**
     * @return true when both the service-account key and the admin email to impersonate are present
     */
    public boolean isConfigured() {
        return configurationGap() == null;
    }

    /**
     * Describes what is missing, for the single actionable ERROR the discovery seam logs.
     *
     * @return null when the configuration is complete, otherwise a short phrase naming the gap
     */
    public String configurationGap() {
        boolean hasKey = attribute(Constants.SERVICE_ACCOUNT_KEY) != null;
        boolean hasAdmin = attribute(Constants.TENANT_ADMIN_EMAIL) != null;
        if (!hasKey && !hasAdmin) {
            return "neither the '" + Constants.SERVICE_ACCOUNT_KEY + "' service-account key nor the '"
                    + Constants.TENANT_ADMIN_EMAIL + "' admin email is configured";
        }
        if (!hasKey) {
            return "the '" + Constants.SERVICE_ACCOUNT_KEY + "' service-account key is not configured";
        }
        if (!hasAdmin) {
            return "the '" + Constants.TENANT_ADMIN_EMAIL
                    + "' Google Workspace admin email to impersonate is not configured";
        }
        return null;
    }

    /** @return the Workspace admin email to impersonate for Admin SDK calls, or null when unset */
    public String getAdminEmail() {
        return attribute(Constants.TENANT_ADMIN_EMAIL);
    }

    /** @return the single domain to restrict enumeration to, or null to list the whole account */
    public String getTenantDomain() {
        return attribute(Constants.TENANT_DOMAIN);
    }

    // ── clients ───────────────────────────────────────────────────────────

    /**
     * Builds a Calendar client that acts as {@code userEmail}.
     *
     * <p>Under delegation the access token is minted <em>for that user</em>, so every call behaves
     * exactly as if they had made it — {@code "primary"} resolves to their own primary calendar, and
     * the existing call shapes in {@code GoogleCalendarEventFetcher} and {@code CalendarWatchService}
     * need no change.
     *
     * <p>Deliberately does <strong>not</strong> refresh eagerly (unlike the per-user path, which
     * must fail fast so it can raise a re-authorise prompt). A tenant sweep touches hundreds of
     * mailboxes; the token is fetched lazily on first use, and a mailbox that refuses is skipped by
     * the caller rather than aborting the sweep.
     *
     * @param userEmail the mailbox to impersonate
     * @return a Calendar client authenticated as that user
     * @throws DomainWideDelegationException if the key is missing or unreadable
     */
    public Calendar calendarClientFor(String userEmail) throws DomainWideDelegationException {
        GoogleCredentials credentials = delegatedCredentials(userEmail, List.of(CALENDAR_SCOPE));
        return new Calendar.Builder(transport(), JSON_FACTORY, new HttpCredentialsAdapter(credentials))
                .setApplicationName(Constants.GOOGLE_CALENDAR)
                .build();
    }

    /**
     * Builds an Admin SDK Directory client that acts as the configured Workspace admin.
     *
     * @return a Directory client authenticated as the admin
     * @throws DomainWideDelegationException if the key or the admin email is missing or unreadable
     */
    public Directory directoryClientForAdmin() throws DomainWideDelegationException {
        String adminEmail = getAdminEmail();
        if (adminEmail == null) {
            throw new DomainWideDelegationException(
                    "Tenant enumeration needs a Google Workspace admin to impersonate, but "
                            + configurationGap() + ".", null);
        }
        GoogleCredentials credentials = delegatedCredentials(adminEmail, List.of(DIRECTORY_SCOPE));
        return new Directory.Builder(transport(), JSON_FACTORY, new HttpCredentialsAdapter(credentials))
                .setApplicationName(Constants.GOOGLE_CALENDAR)
                .build();
    }

    /**
     * WP-DL: builds an Admin SDK Directory client scoped to read GROUPS, as the configured admin.
     *
     * <p>Separate from {@link #directoryClientForAdmin()} on purpose — see {@link #GROUP_SCOPE}. A
     * delegated token is issued for the whole requested scope set or not at all, so a tenant that
     * granted only the original two scopes must keep getting a working user enumeration; only the
     * group expansion fails, and {@code GroupExpansionService} reports it once with the scope to add.
     *
     * @return a Directory client authenticated as the admin, scoped to read groups
     * @throws DomainWideDelegationException if the key or the admin email is missing or unreadable
     */
    public Directory directoryClientForGroups() throws DomainWideDelegationException {
        String adminEmail = getAdminEmail();
        if (adminEmail == null) {
            throw new DomainWideDelegationException(
                    "Expanding a Google Group needs a Google Workspace admin to impersonate, but "
                            + configurationGap() + ".", null);
        }
        GoogleCredentials credentials = delegatedCredentials(adminEmail, List.of(GROUP_SCOPE));
        return new Directory.Builder(transport(), JSON_FACTORY, new HttpCredentialsAdapter(credentials))
                .setApplicationName(Constants.GOOGLE_CALENDAR)
                .build();
    }

    /**
     * Performs the small, read-only probes used by Studio's Validate Attributes action.  Google has
     * no OAuth admin-consent redirect for Workspace DWD: the super-admin must add the service
     * account's numeric client ID in the Admin Console.  Probing both APIs here lets the extension
     * surface that fact immediately instead of waiting for the first background sweep.
     *
     * @param candidateAttributes attributes currently being validated; may be null to use live ones
     * @throws DomainWideDelegationException when configuration is incomplete or DWD is refused
     */
    public void validateDelegatedAccess(Map<String, Object> candidateAttributes)
            throws DomainWideDelegationException {
        String serviceAccountJson = attribute(candidateAttributes, Constants.SERVICE_ACCOUNT_KEY);
        String adminEmail = attribute(candidateAttributes, Constants.TENANT_ADMIN_EMAIL);
        if (serviceAccountJson == null || adminEmail == null) {
            String gap = serviceAccountJson == null && adminEmail == null
                    ? "neither the '" + Constants.SERVICE_ACCOUNT_KEY + "' service-account key nor the '"
                    + Constants.TENANT_ADMIN_EMAIL + "' admin email is configured"
                    : serviceAccountJson == null
                    ? "the '" + Constants.SERVICE_ACCOUNT_KEY + "' service-account key is not configured"
                    : "the '" + Constants.TENANT_ADMIN_EMAIL + "' Google Workspace admin email is not configured";
            throw new DomainWideDelegationException(
                    "Always On requires Google Workspace domain-wide delegation, but " + gap + ".", null);
        }

        try {
            HttpTransport transport = transport();
            GoogleCredentials directoryCredentials = delegatedCredentials(
                    adminEmail, List.of(DIRECTORY_SCOPE), serviceAccountJson);
            new Directory.Builder(transport, JSON_FACTORY,
                    new HttpCredentialsAdapter(directoryCredentials))
                    .setApplicationName(Constants.GOOGLE_CALENDAR)
                    .build()
                    .users().list().setCustomer("my_customer").setMaxResults(1).setProjection("basic")
                    .execute();

            GoogleCredentials calendarCredentials = delegatedCredentials(
                    adminEmail, List.of(CALENDAR_SCOPE), serviceAccountJson);
            new Calendar.Builder(transport, JSON_FACTORY,
                    new HttpCredentialsAdapter(calendarCredentials))
                    .setApplicationName(Constants.GOOGLE_CALENDAR)
                    .build()
                    .calendars().get(Constants.PRIMARY).execute();
        } catch (DomainWideDelegationException cause) {
            throw cause;
        } catch (IOException | RuntimeException cause) {
            String reason = isDelegationDenied(cause)
                    ? "The Google Workspace super-admin has not authorised this service account Client ID "
                      + "for the required Calendar and Admin SDK read-only scopes."
                    : "Google Workspace DWD validation could not complete: " + redact(cause.getMessage());
            throw new DomainWideDelegationException(reason, sanitized(cause));
        }
    }

    /**
     * Builds the delegated credential itself — the one call that turns a service-account key into
     * authority over a named user's data.
     *
     * <p>Package-visible rather than private so it can be unit-tested directly against a synthetic
     * key: the construction is offline (no token is fetched here), which is what makes the
     * delegation wiring testable at all.
     *
     * @param impersonatedUser the user the credential will act as
     * @param scopes           the scopes the super-admin must have authorised for this client id
     * @return a scoped, delegated credential
     * @throws DomainWideDelegationException if the key is absent or cannot be parsed
     */
    GoogleCredentials delegatedCredentials(String impersonatedUser, Collection<String> scopes)
            throws DomainWideDelegationException {
        return delegatedCredentials(impersonatedUser, scopes, attribute(Constants.SERVICE_ACCOUNT_KEY));
    }

    private GoogleCredentials delegatedCredentials(String impersonatedUser, Collection<String> scopes,
                                                   String serviceAccountJson)
            throws DomainWideDelegationException {
        if (impersonatedUser == null || impersonatedUser.isBlank()) {
            throw new DomainWideDelegationException(
                    "A user email is required to build a delegated Google credential.", null);
        }
        if (serviceAccountJson == null) {
            throw new DomainWideDelegationException(
                    "Domain-wide delegation is not available because " + configurationGap() + ".", null);
        }
        try (InputStream keyStream =
                     new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8))) {
            return ServiceAccountCredentials.fromStream(keyStream)
                    .createScoped(scopes)
                    .createDelegated(impersonatedUser);
        } catch (IOException | RuntimeException cause) {
            // The key itself is the input to the failing parse, so the cause is sanitised before it
            // is ever attached to something that will be logged with a stack trace.
            throw new DomainWideDelegationException(
                    "The configured Google service-account key could not be read. Paste the whole "
                            + "JSON key file downloaded from Google Cloud into the '"
                            + Constants.SERVICE_ACCOUNT_KEY + "' attribute, unmodified.",
                    sanitized(cause));
        }
    }

    private HttpTransport transport() throws DomainWideDelegationException {
        try {
            return GoogleNetHttpTransport.newTrustedTransport();
        } catch (GeneralSecurityException | IOException cause) {
            throw new DomainWideDelegationException(
                    "Could not create a trusted HTTP transport for the Google APIs.", sanitized(cause));
        }
    }

    // ── failure classification ────────────────────────────────────────────

    /**
     * Recognises the family of failures that mean "the super-admin has not authorised this service
     * account for these scopes" — as opposed to a transient network or quota fault.
     *
     * <p>Google reports an unauthorised delegation in several shapes depending on where it is
     * caught: {@code unauthorized_client} from the token endpoint (the client id is not authorised
     * at all), {@code access_denied} (authorised for a different scope set), {@code invalid_grant}
     * with an unknown impersonated user, or a plain 401/403 from the API. All of them have the same
     * remediation, so all of them are one bucket.
     *
     * @param cause the failure to classify; may be null
     * @return true when the remediation is an admin action in the Google Workspace Admin console
     */
    public static boolean isDelegationDenied(Throwable cause) {
        int depth = 0;
        for (Throwable current = cause; current != null && depth < MAX_CAUSE_DEPTH; current = current.getCause()) {
            depth++;
            if (current instanceof GoogleJsonResponseException response
                    && (response.getStatusCode() == 401 || response.getStatusCode() == 403)) {
                return true;
            }
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("unauthorized_client")
                    || lower.contains("access_denied")
                    || lower.contains("invalid_grant")
                    || lower.contains("not authorized to access this resource")
                    || lower.contains("caller does not have permission")) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /**
     * The remediation sentence shown to an administrator whenever tenant-wide discovery is
     * unavailable. Deliberately names the console location and the exact scope strings — a message
     * that only says "delegation failed" costs a support round-trip every time.
     *
     * @return a single-sentence, copy-pasteable instruction
     */
    public static String remediation() {
        return "To enable Always On capture: (1) create a service account in Google Cloud and download "
                + "its JSON key; (2) in the Google Workspace Admin console open Security > Access and data "
                + "control > API controls > Domain-wide delegation and authorise that service account's "
                + "numeric Client ID for these scopes: " + CALENDAR_SCOPE + ", " + DIRECTORY_SCOPE
                + " and " + GROUP_SCOPE + " (the last one is only needed to expand distribution lists "
                + "into their members; without it everything else still works); (3) paste the JSON key "
                + "into this extension's '" + Constants.SERVICE_ACCOUNT_KEY
                + "' attribute and a Workspace super-admin's address into '" + Constants.TENANT_ADMIN_EMAIL
                + "', then save the invoker.";
    }

    // ── secret hygiene ────────────────────────────────────────────────────

    /**
     * Strips anything that could be key material out of a diagnostic string.
     *
     * <p>Applied to every message that can reach a log or an exception on this path. The
     * service-account JSON is the input to the parsers that produce these messages, and a
     * third-party library is free to quote its input — so the guarantee cannot rest on trusting each
     * library, only on redacting unconditionally.
     *
     * @param message the raw diagnostic text; may be null
     * @return a bounded, key-free version, never null
     */
    static String redact(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        String redacted = PEM_BLOCK.matcher(message).replaceAll("[REDACTED KEY BLOCK]");
        redacted = LONG_SECRET_RUN.matcher(redacted).replaceAll("[REDACTED]");
        if (redacted.length() > MAX_DIAGNOSTIC_LENGTH) {
            redacted = redacted.substring(0, MAX_DIAGNOSTIC_LENGTH) + "…";
        }
        return redacted;
    }

    /**
     * Copies a failure into an equivalent throwable whose message — and every message in its cause
     * chain — has been through {@link #redact(String)}, preserving the original stack traces.
     *
     * <p>This is what lets the scan paths log {@code LOGGER.error(msg, exception)} normally: the
     * where-it-failed detail survives, the key does not.
     *
     * <p>Public because every collaborator on the tenant-wide path — the Directory enumerator, the
     * discovery seam — must sanitise at its own throw site too. There is no safe place to skip it:
     * the same service-account key feeds every one of these calls.
     *
     * @param cause the original failure; may be null
     * @return a sanitised copy, or null when {@code cause} was null
     */
    public static Throwable sanitized(Throwable cause) {
        return sanitized(cause, 0);
    }

    private static Throwable sanitized(Throwable cause, int depth) {
        if (cause == null || depth >= MAX_CAUSE_DEPTH) {
            return null;
        }
        Throwable safe = new Throwable(cause.getClass().getName() + ": " + redact(cause.getMessage()));
        safe.setStackTrace(cause.getStackTrace());
        Throwable inner = cause.getCause();
        if (inner != null && inner != cause) {
            Throwable safeInner = sanitized(inner, depth + 1);
            if (safeInner != null) {
                safe.initCause(safeInner);
            }
        }
        return safe;
    }

    // ── attribute access ──────────────────────────────────────────────────

    /**
     * Reads one invoker attribute without ever logging its value.
     *
     * <p>An invoker removed mid-sweep throws on every call, which must read as "not configured"
     * rather than propagate: the caller's job is to degrade, not to fail.
     */
    private String attribute(String key) {
        try {
            Map<String, Object> attributes = liveConfiguration.attributes();
            return Constants.readTextAttribute(attributes, key);
        } catch (Exception cause) {
            LOGGER.warn("Could not read the '{}' invoker attribute; treating it as unset: {}",
                    key, redact(cause.getMessage()));
            return null;
        }
    }

    private String attribute(Map<String, Object> candidateAttributes, String key) {
        if (candidateAttributes != null) {
            return Constants.readTextAttribute(candidateAttributes, key);
        }
        return attribute(key);
    }
}
