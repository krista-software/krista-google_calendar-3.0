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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.auth;

import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.GoogleAttributes;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.GoogleAttributeStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import com.github.scribejava.core.oauth.OAuth20Service;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.NoSuchElementException;

/**
 * Builds Google OAuth authorization URLs from a non-request-scoped context.
 *
 * <p>Extracted out of {@code AuthenticationResource} so the calendar-reconnect
 * ESB listener can produce the same URL the HTTP flow produces, without
 * needing the JAX-RS request context.
 *
 * <p>Construction-time fields (invokerId, base routing URL) are captured from
 * the {@link Invoker} bean, which is HK2-singleton-scoped at the extension
 * level. {@link GoogleAttributes} are loaded from the persistent store on each
 * call — they may be updated by the admin while the extension is running.
 *
 * @since 3.0.3
 */
@Service
public class GoogleOAuthUrlService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleOAuthUrlService.class);

    /**
     * Validity window for a generated OAuth URL. Google's consent screen URL
     * is not time-limited, but the spec exposes a hint so CA's 180s pending
     * sweeper has a known ceiling. Match KIP's value.
     */
    public static final int AUTH_URL_EXPIRES_IN_SECONDS = 180;

    private final GoogleAttributeStore attributeStore;
    private final RefreshTokenStore refreshTokenStore;
    private final UserKeyIndexStore userKeyIndexStore;
    private final String invokerId;

    @Inject
    public GoogleOAuthUrlService(GoogleAttributeStore attributeStore,
                                 RefreshTokenStore refreshTokenStore,
                                 UserKeyIndexStore userKeyIndexStore,
                                 Invoker invoker) {
        this.attributeStore = attributeStore;
        this.refreshTokenStore = refreshTokenStore;
        this.userKeyIndexStore = userKeyIndexStore;
        this.invokerId = invoker.getInvokerId();
    }

    /**
     * Builds an OAuth authorization URL for the given user.
     *
     * @param workspaceId the workspace; informational (the actual auth context
     *                    comes from the loaded attributes for {@link #invokerId})
     * @param userEmail   the user's email; used to locate the user's existing
     *                    refresh-token-store key, which becomes the OAuth
     *                    {@code state} the callback writes the new token under
     * @return {@link AuthUrlResult} containing the URL and its validity window
     * @throws NoSuchElementException   if no Google OAuth configuration exists
     *                                  for this workspace (maps to spec §B.3
     *                                  {@code NOT_REGISTERED})
     * @throws IllegalArgumentException if {@code userEmail} is null or blank
     */
    public AuthUrlResult buildAuthUrl(String workspaceId, String userEmail) {
        if (userEmail == null || userEmail.isBlank()) {
            throw new IllegalArgumentException("userEmail is required");
        }

        GoogleAttributes attributes = attributeStore.load(invokerId);
        if (attributes == null
                || attributes.getClientId() == null || attributes.getClientId().isBlank()
                || attributes.getClientSecret() == null || attributes.getClientSecret().isBlank()) {
            throw new NoSuchElementException(
                    "No Google OAuth configuration for workspace " + workspaceId
                            + " (invokerId=" + invokerId + ")");
        }

        OAuth20Service oauth = attributes.getOAuth20Service();
        // The OAuth callback (AuthenticationResource) splits state on '#' and
        // uses parts[0] as the refresh-token-store key. That key MUST equal the
        // key the sync/refresh path reads (GoogleProvider.getRefTokenStoreKey),
        // which for an MA end-user is the wsContact_{invokerId}_{email} key, NOT
        // the bare email. Using the bare email parks the refreshed token under a
        // key nothing reads (banner clears but sync stays broken) and trips the
        // non-wsContact hasUserAccess gate. Resolve the existing key instead.
        String emailLower = userEmail.trim().toLowerCase(java.util.Locale.ROOT);
        String stateKey = resolveStateKey(emailLower);
        // The redirect_uri registered with Google is environment-level
        // (GoogleAttributes#getCallbackEndPoint), so the platform routes the callback
        // back to this invoker using the per-instance forwardPath carried in the OAuth
        // state — matching the setup flow. Without it the callback is unroutable (502).
        //
        // We also carry this invokerId in the state: the AuthInitiate is consumed by whichever
        // stage (draft/test/published) wins it, and the callback is forwarded back to that stage,
        // where the request-thread Invoker is unreliable (see Hk2ContextHolder). buildAuthUrl runs
        // on the ESB thread where invokerId is reliable AND just loaded the OAuth client under it,
        // so the callback uses this state-supplied id (AuthHelper.getInvokerId ->
        // createForInvoker) instead of the forwarded-thread Invoker. No authContextId is added.
        String stateBase = (invokerId != null && !invokerId.isBlank())
                ? stateKey + Constants.HASH + Constants.INVOKER_ID_PREFIX + invokerId
                : stateKey;
        // Route the callback to the LIVE (published) stage by stripping any draft/test stage segment
        // from the forwardPath. Whichever stage consumes the AuthInitiate stamps its own stage into
        // the forwardPath, but only the live stage can load the OAuth client on the forwarded callback
        // (draft/test return null attributes there). Canonicalizing here keeps reconnect deterministic
        // regardless of which stage built the URL. (Reconnect-only — the shared appendForwardPath used
        // by the setup flow is left untouched, since setup must return to its originating stage.)
        String forwardPath = canonicalizeForwardPath(attributes.getForwardPath());
        String state = (forwardPath != null && !forwardPath.isEmpty())
                ? stateBase + Constants.HASH + forwardPath
                : stateBase;
        String authUrl = oauth.getAuthorizationUrl(state) + Constants.AUTH_URL_QUERY_PARAMS;

        LOGGER.info("Built Google OAuth URL for ws={} email={} (stateKey={})",
                workspaceId, userEmail, stateKey);

        return new AuthUrlResult(authUrl, AUTH_URL_EXPIRES_IN_SECONDS);
    }

    /**
     * Strips a {@code /draft} or {@code /test} stage segment from a forwardPath so the reconnect
     * callback routes to the live (published) stage — the only stage that can load the OAuth client
     * on the forwarded callback. The segment sits between the route token and the extension forward
     * path: {@code …/<token>/{draft|test}/rest/googlecalendar/…}. No-op for the live path (no segment).
     *
     * <p>Package-private for unit testing.
     */
    static String canonicalizeForwardPath(String forwardPath) {
        if (forwardPath == null || forwardPath.isEmpty()) {
            return forwardPath;
        }
        return forwardPath
                .replaceFirst("/draft(?=/rest/)", "")
                .replaceFirst("/test(?=/rest/)", "");
    }

    /**
     * Resolves the OAuth {@code state} / refresh-token-store key for a reconnect.
     *
     * <p>The value must equal the key the sync/refresh path reads
     * ({@code GoogleProvider.getRefTokenStoreKey}). For an MA end-user that is the
     * existing {@code wsContact_{invokerId}_{email}} key: the broken token that
     * prompted reauth is still in the store, so we locate it by matching the
     * trailing email. This is resolved purely from {@link RefreshTokenStore}, with
     * no dependency on an {@code @Authenticated} account lookup (which is not
     * available on the ESB / non-request thread that drives reconnect).
     *
     * <p>Falls back to the lower-cased email when no matching key exists,
     * preserving prior behaviour rather than failing the URL build.
     *
     * <p>Package-private for unit testing.
     */
    String resolveStateKey(String emailLower) {
        // Prefer the persisted email->key index. It is populated while the token is
        // healthy (sync + auth callback) and works for the real client key shape
        // wsContact_<contactUUID>, which carries NO email suffix — so the suffix-scan
        // below can never match it. The index also needs no live Google call (the
        // reconnect token is the broken one).
        String indexed = userKeyIndexStore.get(invokerId, emailLower);
        if (indexed != null && !indexed.isBlank()) {
            return indexed;
        }
        String suffix = "_" + emailLower;
        try {
            for (String key : refreshTokenStore.getKey()) {
                if (key != null
                        && key.startsWith(Constants.WS_CONTACT)
                        && key.toLowerCase(java.util.Locale.ROOT).endsWith(suffix)) {
                    return key;
                }
            }
            LOGGER.warn("Reconnect: no wsContact token-store key found for email={} - using email as the state "
                    + "key; the refreshed token may land under a key the sync path does not read", emailLower);
        } catch (RuntimeException cause) {
            LOGGER.warn("Reconnect: token-key lookup failed for email={}: {} - using email as state key",
                    emailLower, cause.getMessage());
        }
        return emailLower;
    }

    /** Immutable result. */
    public static final class AuthUrlResult {
        private final String authUrl;
        private final int expiresInSeconds;

        public AuthUrlResult(String authUrl, int expiresInSeconds) {
            this.authUrl = authUrl;
            this.expiresInSeconds = expiresInSeconds;
        }

        public String getAuthUrl() {
            return authUrl;
        }

        public int getExpiresInSeconds() {
            return expiresInSeconds;
        }
    }
}
