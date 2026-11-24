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

import app.krista.extensions.util.KeyValueStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatus;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarDisconnectReason;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@Singleton
public class GoogleCalendarAuthenticationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleCalendarAuthenticationService.class);
    private static final String AUTH_USERS_KEY = "gcal_sync_authenticated_users";

    /**
     * The composite key form written by the Meeting-Agent-driven reconnect flow. Mirrors
     * {@code UsersResource.WORKSPACE_AUTH_KEY_PREFIX} — the same forms that resource already parses
     * when reporting users to Meeting Agent, which the credential decision here must agree with.
     */
    private static final String WORKSPACE_AUTH_KEY_PREFIX = "workspace_auth:";

    /** Legacy {@code email#suffix} key form. */
    private static final String LEGACY_KEY_DELIMITER = "#";

    private final KeyValueStore keyValueStore;
    private final Gson gson = new Gson();
    private final Type listType = new TypeToken<List<String>>() {}.getType();

    /**
     * Optional status store. Null-safe: if absent (unit tests, etc.) the status
     * transitions simply are not published.
     */
    private CalendarConnectionStatusStore connectionStatusStore;

    @Inject
    public GoogleCalendarAuthenticationService(KeyValueStore keyValueStore) {
        this.keyValueStore = keyValueStore;
    }

    /**
     * Inject the connection status store after construction.
     * Called from {@code GoogleCalendarExtension} once both beans are available.
     */
    public void setConnectionStatusStore(CalendarConnectionStatusStore connectionStatusStore) {
        this.connectionStatusStore = connectionStatusStore;
    }

    /**
     * Returns the list of authenticated user IDs (email addresses) available for sync.
     */
    public List<String> getAuthenticatedUsers() {
        try {
            Object raw = keyValueStore.get(AUTH_USERS_KEY);
            if (raw == null) return Collections.emptyList();

            if (raw instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) raw;
                return new ArrayList<>(list);
            }

            String json = raw.toString();
            if (json.isBlank()) return Collections.emptyList();
            List<String> list = gson.fromJson(json, listType);
            return list == null ? Collections.emptyList() : new ArrayList<>(list);
        } catch (Exception e) {
            LOGGER.warn("Failed to get authenticated users: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Registers a user as authenticated and available for calendar sync.
     * Also records {@link CalendarConnectionStatus#CONNECTED} via
     * {@code CalendarConnectionStatusStore.recordConnected}, which publishes a
     * status-changed event to CA Service on every registration (re-asserting the
     * calendar system, not gated on a status transition).
     *
     * @param userId      the user key (typically the user's email address)
     * @param invokerId   the invoker identifier — scopes the local status-store key only
     * @param workspaceId the real workspace id ({@code ws_…}) published in the CA event
     */
    public void registerUser(String userId, String invokerId, String workspaceId) {
        registerUser(userId, invokerId, workspaceId, null);
    }

    /**
     * Canonical overload — takes the authenticated user's email explicitly (resolved by the caller
     * from the user's primary calendar, {@code GoogleProvider.getPrimaryCalendarEmail}). Required
     * for client ({@code wsContact_<id>}) users whose key carries no email; passed through to
     * {@link CalendarConnectionStatusStore#recordConnected(String, String, String, String)} so CA
     * can register the calendar system. A null/blank email falls back to key-based resolution.
     *
     * <p>{@code invokerId} scopes the local KV-store key; {@code workspaceId} ({@code ws_…}) is what
     * the CA event carries and CA keys the user on. They are <em>not</em> interchangeable — sending
     * the invoker id ({@code INV_…}) as the workspaceId makes CA drop the event.
     *
     * @param userId      the user key
     * @param invokerId   the invoker identifier (KV-store scope only)
     * @param workspaceId the real workspace id published in the event
     * @param email       the authenticated user's email, or {@code null} to resolve from the key
     */
    public void registerUser(String userId, String invokerId, String workspaceId, String email) {
        if (userId == null || userId.isBlank()) return;
        try {
            List<String> users = new ArrayList<>(getAuthenticatedUsers());
            // Keyed on the PERSON, not the key string, so re-authenticating through a different
            // flow — or under a different case — does not add a SECOND entry for the same person.
            // Two entries would survive a disconnect one at a time and leave the list disagreeing
            // with itself about whether that person is connected.
            String candidate = identityOf(userId);
            boolean alreadyRegistered = users.stream()
                    .anyMatch(stored -> stored != null && identityOf(stored).equals(candidate));
            if (!alreadyRegistered) {
                users.add(userId);
                keyValueStore.put(AUTH_USERS_KEY, gson.toJson(users));
                LOGGER.info("Registered user {} for calendar sync ({} total users)", userId, users.size());
            }
            if (connectionStatusStore != null) {
                // Re-assert CONNECTED to CA on every registration (ungated), matching the
                // OAuth-callback path, so CA registers the calendar system reliably.
                connectionStatusStore.recordConnected(userId, invokerId, workspaceId, email);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to register user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Backward-compatible overload — no workspace id available, so the CA publish is skipped
     * (the local registration still happens). Retained only for legacy callers.
     *
     * @deprecated Prefer {@link #registerUser(String, String, String, String)} so the status store
     *             publishes with the real workspaceId.
     */
    @Deprecated
    public void registerUser(String userId) {
        LOGGER.warn("registerUser(String) called without invokerId/workspaceId for userId='{}' — "
                + "the CA publish will be skipped (no workspaceId). Migrate the caller to "
                + "registerUser(userId, invokerId, workspaceId, email).", userId);
        registerUser(userId, "", null, null);
    }

    /**
     * Removes a user from the authenticated user list.
     * Also records {@link CalendarConnectionStatus#DISCONNECTED} in the status store.
     *
     * <p>This represents a <em>deliberate</em> disconnect (the user removed their calendar), so the
     * DISCONNECTED event carries {@code reason=}{@link CalendarDisconnectReason#USER_INITIATED} —
     * CA deletes the {@code user_calendar_connection} row and sends no reminder. An involuntary
     * removal should not route through here (it would emit REAUTH_REQUIRED/RENEWAL_FAILED instead).
     *
     * <p>{@code invokerId} scopes the local status-store key; {@code workspaceId} ({@code ws_…}) is
     * what the DISCONNECTED event carries and CA keys the user on — they are not interchangeable.
     *
     * @param userId      the user key
     * @param invokerId   the invoker identifier — scopes the local status-store key only
     * @param workspaceId the real workspace id ({@code ws_…}) published in the CA event
     */
    public void unregisterUser(String userId, String invokerId, String workspaceId) {
        if (userId == null || userId.isBlank()) return;
        try {
            List<String> users = new ArrayList<>(getAuthenticatedUsers());
            // Removes EVERY entry naming this person, not just one byte-identical to the key we
            // were handed. The list is written verbatim at registration, so the same person can sit
            // in it as Ana@Contoso.com while the disconnect arrives as ana@contoso.com — and an
            // exact remove() then leaves them registered. They keep being swept, their revoked
            // token fails with invalid_grant, and CA nags them to reconnect the calendar they just
            // chose to disconnect. A disconnect that silently fails to take effect is worse than
            // one that fails loudly, because the user believes they have opted out.
            String needle = identityOf(userId);
            if (users.removeIf(stored -> stored != null && identityOf(stored).equals(needle))) {
                keyValueStore.put(AUTH_USERS_KEY, gson.toJson(users));
                LOGGER.info("Unregistered user {} from calendar sync ({} remaining users)", userId, users.size());
            }
            if (connectionStatusStore != null) {
                connectionStatusStore.put(userId, invokerId, CalendarConnectionStatus.DISCONNECTED, workspaceId,
                        CalendarDisconnectReason.USER_INITIATED);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to unregister user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Backward-compatible overload — no workspace id available, so the DISCONNECTED publish is
     * skipped (the local removal still happens). Retained only for legacy callers.
     *
     * @deprecated Prefer {@link #unregisterUser(String, String, String)} so the status store
     *             publishes with the real workspaceId.
     */
    @Deprecated
    public void unregisterUser(String userId) {
        LOGGER.warn("unregisterUser(String) called without invokerId/workspaceId for userId='{}' — "
                + "the DISCONNECTED publish will be skipped (no workspaceId). Migrate the "
                + "caller to unregisterUser(userId, invokerId, workspaceId).", userId);
        unregisterUser(userId, "", null);
    }

    /**
     * Checks if a specific user is authenticated for sync.
     *
     * <h2>Why this is case-insensitive</h2>
     *
     * <p>It decides which CREDENTIAL a calendar is read with. {@code CalendarDiscoveryService}'s
     * {@code sourceFor}/{@code isSyncable} ask this first: yes means the user's own OAuth token,
     * no means the Always On path — a registered seat read through the domain-wide-delegation
     * service account.
     *
     * <p>The stored list is written verbatim by {@link #registerUser}, in whatever case the OAuth
     * flow supplied, while the seat and directory sides of the same decision lower-case before
     * comparing ({@code CalendarDiscoveryService} lines around the tenant-member and scan-member
     * checks). An exact {@code contains} across that asymmetry answers "no" for a user who is
     * plainly authenticated — for example one who registered as {@code Ana@Contoso.com} and is then
     * addressed as {@code ana@contoso.com}, which is how Meeting Agent's seat roster stores them,
     * because Meeting Agent lower-cases every email it holds.
     *
     * <p>The consequence is a silent credential swap: their own valid token sits unread while we
     * impersonate them through the service account. Domain-wide delegation usually covers the same
     * mailbox, which is what would keep this hidden rather than obvious — until delegation is
     * scoped to an OU, or the person is outside the delegated domain, and then it fails for some
     * users in one tenant and not others.
     *
     * <p>Comparison is normalised rather than the stored values being rewritten: the list is
     * durable state shared with the sweeps, and a migration is not needed to answer a question
     * correctly. Email local parts are treated as case-insensitive, which is how Google Workspace
     * treats them.
     */
    public boolean isUserAuthenticated(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        String needle = identityOf(userId);
        for (String stored : getAuthenticatedUsers()) {
            if (stored != null && identityOf(stored).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The person a stored key addresses, normalised — <b>not</b> the key itself.
     *
     * <p>The same person is stored under more than one key form, exactly as in the Teams extension:
     * a bare email, a {@code workspace_auth:{workspaceId}:{email}} key written by the
     * Meeting-Agent-driven reconnect flow, and a legacy {@code email#suffix} form. {@code UsersResource}
     * already parses all three when reporting users to Meeting Agent; the credential decision did not,
     * and compared raw strings instead.
     *
     * <p>So a user who reconnected through Meeting Agent — stored as
     * {@code workspace_auth:ws_x:ana@contoso.com} — read as unauthenticated when addressed as
     * {@code ana@contoso.com}, which is how the seat roster and the webhooks name people. Under
     * Always On that routes them to the domain-wide-delegation service account while their own valid
     * token sits unread. No casing change could have fixed that one: the strings differ by a prefix.
     *
     * <p>{@code wsContact_<id>} keys are returned as-is and deliberately not resolved here. They are
     * platform accounts rather than end users — {@code UsersResource.extractEmail} excludes them from
     * the user listing for the same reason — so there is no email to match them by, and inventing one
     * would risk matching a platform account to a person. Mapping those would need
     * {@code UserKeyIndexStore}, which this class does not have and which is not needed for the
     * defect above.
     */
    private static String identityOf(String key) {
        String trimmed = key.trim();
        if (trimmed.startsWith(WORKSPACE_AUTH_KEY_PREFIX)) {
            String raw = trimmed.substring(WORKSPACE_AUTH_KEY_PREFIX.length());
            int separator = raw.indexOf(':');
            if (separator > 0 && separator < raw.length() - 1) {
                return raw.substring(separator + 1).trim().toLowerCase(java.util.Locale.ROOT);
            }
            return trimmed.toLowerCase(java.util.Locale.ROOT);
        }
        int hashIndex = trimmed.indexOf(LEGACY_KEY_DELIMITER);
        if (hashIndex > 0) {
            return trimmed.substring(0, hashIndex).trim().toLowerCase(java.util.Locale.ROOT);
        }
        return trimmed.toLowerCase(java.util.Locale.ROOT);
    }
}
