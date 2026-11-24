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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.api;

import app.krista.extension.executor.Invoker;
import app.krista.ksdk.accounts.Account;
import app.krista.ksdk.accounts.AccountProvider;
import app.krista.ksdk.accounts.Person;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only listing endpoint for users with valid OAuth grants on this
 * extension instance. Consumed by the conversation-agent service's
 * environment seeding job and reconciliation tooling — see
 * <em>Extension Contract — User Management &amp; Multi-Tenancy</em>, §2.
 *
 * <p>The endpoint inherits the framework's existing inbound auth rules
 * (the same rules that gate every other REST surface on this extension —
 * the OAuth callback, lifecycle, and notification paths). No new auth
 * header is introduced.
 *
 * <p>Workspace-scoped via the invoker URL: each extension instance is
 * registered against exactly one workspace, so this resource derives the
 * workspace from {@link Invoker#getWorkspaceId()} and reports only that
 * workspace's users. The endpoint MUST NOT side-effect the auth state.
 */
@Path("users")
public final class UsersResource {

    static final String WORKSPACE_AUTH_KEY_PREFIX = "workspace_auth:";
    static final String WS_CONTACT_KEY_PREFIX = "wsContact";
    static final String LEGACY_KEY_DELIMITER = "#";

    private static final Logger LOGGER = LoggerFactory.getLogger(UsersResource.class);

    private final RefreshTokenStore refreshTokenStore;
    private final AccountProvider accountProvider;
    private final Invoker invoker;

    @Inject
    public UsersResource(RefreshTokenStore refreshTokenStore,
                         AccountProvider accountProvider,
                         Invoker invoker) {
        this.refreshTokenStore = refreshTokenStore;
        this.accountProvider = accountProvider;
        this.invoker = invoker;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response listUsers() {
        List<String> tokenKeys;
        try {
            tokenKeys = refreshTokenStore.getKey();
        } catch (RuntimeException cause) {
            LOGGER.error("GET /users: refresh token store unavailable", cause);
            return unavailable();
        }
        if (tokenKeys == null) {
            tokenKeys = List.of();
        }

        try {
            String workspaceId = invoker.getWorkspaceId();
            Set<String> seenEmails = new LinkedHashSet<>();
            List<Map<String, Object>> users = new ArrayList<>();

            for (String key : tokenKeys) {
                Map<String, Object> entry = buildEntry(key, workspaceId, seenEmails);
                if (entry != null) {
                    users.add(entry);
                }
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("extensionId", invoker.getInvokerId());
            body.put("users", users);

            LOGGER.info("GET /users: returning {} eligible user(s) (of {} candidate token rows)",
                    users.size(), tokenKeys.size());
            return Response.ok(body).build();
        } catch (RuntimeException cause) {
            String errorId = UUID.randomUUID().toString();
            LOGGER.error("GET /users: unexpected failure while building response (errorId={})",
                    errorId, cause);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of(
                            "error", "INTERNAL_ERROR",
                            "message", "Failed to build users response.",
                            "errorId", errorId))
                    .build();
        }
    }

    private Map<String, Object> buildEntry(String key, String workspaceId, Set<String> seenEmails) {
        String email = extractEmail(key, workspaceId);
        if (email == null) {
            return null;
        }

        String token = refreshTokenStore.get(key);
        if (token == null || token.isBlank()) {
            return null;
        }

        Account account;
        try {
            account = accountProvider.lookupAccount(email);
        } catch (RuntimeException cause) {
            LOGGER.debug("GET /users: lookupAccount failed for a candidate; excluding", cause);
            return null;
        }
        if (account == null) {
            return null;
        }

        if (!seenEmails.add(email)) {
            return null;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("email", email);
        String displayName = displayNameOf(account);
        if (displayName != null && !displayName.isBlank()) {
            entry.put("displayName", displayName);
        }
        entry.put("lastTokenRefresh", null);
        return entry;
    }

    /**
     * Extracts a user email from a refresh-token store key, or returns
     * {@code null} when the key does not represent a user attached to the
     * given workspace.
     *
     * <p>Three key formats are recognised:
     * <ol>
     *   <li><strong>Workspace-scoped (API v1, forward-compatible):</strong>
     *       {@code workspace_auth:{workspaceId}:{email}}</li>
     *   <li><strong>Studio user (Google legacy):</strong>
     *       {@code {email}#{authContextId}[#{forwardPath}]} — split on
     *       {@code #}, the first segment is the email.</li>
     *   <li><strong>Bare email (legacy):</strong> the key is the email
     *       itself.</li>
     * </ol>
     *
     * <p>Platform-account keys (e.g. {@code wsContact_xxx}) are
     * intentionally excluded — they are not user emails.
     */
    private static String extractEmail(String key, String workspaceId) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.startsWith(WS_CONTACT_KEY_PREFIX)) {
            return null;
        }
        if (key.startsWith(WORKSPACE_AUTH_KEY_PREFIX)) {
            String[] parts = key.split(":", 3);
            if (parts.length < 3) {
                return null;
            }
            if (workspaceId == null || !workspaceId.equals(parts[1])) {
                return null;
            }
            return normalizeEmail(parts[2]);
        }
        String candidate = key;
        int hashIndex = key.indexOf(LEGACY_KEY_DELIMITER);
        if (hashIndex >= 0) {
            candidate = key.substring(0, hashIndex);
        }
        return Validator.isValidEmail(candidate) ? normalizeEmail(candidate) : null;
    }

    private static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String displayNameOf(Account account) {
        Person person = account.getPerson();
        return person == null ? null : person.getPersonName();
    }

    private static Response unavailable() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of(
                        "error", "UNAVAILABLE",
                        "message", "Refresh token store is temporarily unavailable.",
                        "retryAfterSeconds", 30))
                .build();
    }
}
