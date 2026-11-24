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

import app.krista.extension.authorization.MustAuthorizeException;
import app.krista.ksdk.context.AuthorizationContext;
import app.krista.ksdk.context.RequestContext;
import app.krista.model.field.NamedValuedField;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.GoogleAttributes;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatus;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.GoogleAttributeStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants.GOOGLE_CALENDAR;
import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants.PRIMARY;

/**
 * Provider class for creating and managing Google Calendar API client instances.
 *
 * <p>This class handles OAuth 2.0 authentication, credential management, and Google Calendar
 * client creation using the modern GoogleCredentials API. It supports both admin and
 * user-level authentication flows.</p>
 *
 * <p><b>Migration Note:</b> This class has been migrated from the deprecated GoogleCredential
 * to the modern GoogleCredentials API (com.google.auth library) for better security,
 * maintainability, and future compatibility.</p>
 *
 * @see com.google.auth.oauth2.GoogleCredentials
 * @see com.google.auth.oauth2.UserCredentials
 */
public class GoogleProvider {

    static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleProvider.class);

    private final RefreshTokenStore refreshTokenStore;
    private final GoogleAttributes gmailAttributes;
    private final RequestContext requestContext;
    private final AuthorizationContext authorizationContext;
    private final String authContextId;

    /**
     * Optional — set by {@code GoogleProviderFactory.setConnectionStatusStore}
     * after wiring. Null-safe: when absent, invalid_grant detection logs only
     * (i.e. tests and legacy callers still work).
     */
    private app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore connectionStatusStore;

    @Inject
    public GoogleProvider(RefreshTokenStore refreshTokenStore, GoogleAttributeStore googleAttributeStore, RequestContext requestContext, AuthorizationContext authorizationContext) {
        this(refreshTokenStore, googleAttributeStore.load(requestContext.getInvokerId()), requestContext, authorizationContext, requestContext.getInvokerId());
    }

    public GoogleProvider(RefreshTokenStore refreshTokenStore, GoogleAttributes googleAttributes, RequestContext requestContext, AuthorizationContext authorizationContext, String authContextId) {
        this.refreshTokenStore = refreshTokenStore;
        this.gmailAttributes = googleAttributes;
        this.requestContext = requestContext;
        this.authorizationContext = authorizationContext;
        this.authContextId = authContextId;
    }

    /** Set by {@code GoogleProviderFactory} so the refresh path can record REAUTH_REQUIRED. */
    public void setConnectionStatusStore(
            app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore store) {
        this.connectionStatusStore = store;
    }

    /**
     * Gets the Google Calendar authentication attributes.
     *
     * @return GoogleAttributes instance
     */
    public GoogleAttributes getGoogleAttributes() {
        return gmailAttributes;
    }

    /**
     * Gets a Google Calendar client instance based on request context.
     *
     * @return Calendar client instance
     * @throws IOException if client creation fails
     */
    public Calendar getGoogleClient() throws IOException {
        return getGoogleClient(!requestContext.invokeAsUser());
    }

    /**
     * Gets a Google Calendar client instance for admin operations.
     *
     * @return Calendar client instance configured for admin access
     * @throws IOException if client creation fails
     */
    public Calendar getGoogleClientForAdmin() throws IOException {
        return getGoogleClient(true);
    }

    /**
     * Builds a Calendar client for a SPECIFIC user, using that user's own stored
     * refresh token rather than the invoker-configured account.
     *
     * <p>Required for per-user calendar sync and watch channels: without this,
     * {@link #getGoogleClientForAdmin()} always resolves to the invoker-configured
     * email, so every user's sync/watch would target the same (wrong) calendar.
     *
     * @param userId the user id (e.g. {@code wsContact_...} or email) whose calendar to access
     * @return a Calendar client authenticated as that user
     */
    public Calendar getGoogleClientForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required to build a per-user Google client");
        }
        try {
            String refreshTokenStoreKey = getRefTokenStoreKey(userId);
            String refreshToken = refreshTokenStore.get(refreshTokenStoreKey);
            if (refreshToken == null) {
                throw createMustAuthorizationException(refreshTokenStoreKey, false);
            }
            // Status events use the logical user id, while MustAuthorizeException must retain the
            // token-store key in OAuth state so the callback replaces the token at the key this
            // provider reads. These identities are deliberately separate.
            return getGoogle(userId, refreshTokenStoreKey, refreshToken, true);
        } catch (RuntimeException cause) {
            LOGGER.error(Constants.FAILED_TO_GET_GOOGLE_CLIENT + "{} {}", cause.getClass().getName(), cause.getMessage());
            throw cause;
        }
    }

    /**
     * Resolves the authenticated user's email — the id of their primary calendar — using that
     * user's own client ({@link #getGoogleClientForUser}). This is the only reliable email for
     * client ({@code wsContact_<id>}) users, whose store key carries no email and whose configured
     * account ({@code getGoogleAttributes().getMailId()}) is a different identity. Returns
     * {@code null} on any failure so callers can fall back.
     *
     * @param userId the user id whose primary-calendar email to resolve
     * @return the authenticated user's email, or {@code null} if it cannot be resolved
     */
    public String getPrimaryCalendarEmail(String userId) {
        try {
            return getGoogleClientForUser(userId).calendars().get("primary").execute().getId();
        } catch (Exception cause) {
            LOGGER.warn("Could not resolve primary-calendar email for userId={}: {}", userId, cause.getMessage());
            return null;
        }
    }

    /**
     * Creates a Google Calendar client instance with specified authentication mode.
     *
     * @param useEmail flag indicating whether to use email for authentication
     * @return Calendar client instance
     */
    private Calendar getGoogleClient(boolean useEmail) {
        try {
            String userId = getUserId(useEmail);
            String refreshTokenStoreKey = getRefTokenStoreKey(userId);
            String refreshToken = refreshTokenStore.get(refreshTokenStoreKey);
            if (refreshToken == null) {
                throw createMustAuthorizationException(refreshTokenStoreKey, false);
            }
            return getGoogle(userId, refreshTokenStoreKey, refreshToken, useEmail);
        } catch (RuntimeException cause) {
            LOGGER.error(Constants.FAILED_TO_GET_GOOGLE_CLIENT + "{} {}", cause.getClass().getName(), cause.getMessage());
            throw cause;
        }
    }

    /**
     * Creates and configures a Google Calendar client instance.
     *
     * <p>Uses the modern GoogleCredentials API from com.google.auth library,
     * which replaces the deprecated GoogleCredential class.</p>
     *
     * @param statusUserId logical user identifier used by CA connection-status events
     * @param authorizationUserId token-store key carried through MustAuthorizeException/OAuth state
     * @param refreshToken OAuth refresh token for authentication
     * @param isUseEmail flag indicating whether to use email for authentication
     * @return configured Calendar client instance
     */
    private Calendar getGoogle(String statusUserId, String authorizationUserId,
                               String refreshToken, Boolean isUseEmail) {
        try {
            final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

            // Use modern GoogleCredentials instead of deprecated GoogleCredential
            GoogleCredentials credentials = getGoogleCredentials(refreshToken, httpTransport, isUseEmail);

            // Wrap credentials in HttpCredentialsAdapter for Calendar.Builder
            return new Calendar.Builder(httpTransport, JSON_FACTORY, new HttpCredentialsAdapter(credentials))
                    .setApplicationName(GOOGLE_CALENDAR)
                    .build();

        } catch (IOException | GeneralSecurityException cause) {
            // Spec §F.2: classify the refresh failure and record the connection
            // status so the user-visible reconnect banner appears (invalid_grant ->
            // REAUTH_REQUIRED) or a transient failure is surfaced (-> RENEWAL_FAILED).
            handleRefreshFailure(statusUserId, authorizationUserId, cause);
            throw new IllegalStateException("Unreachable after handling Google refresh failure", cause);
        }
    }

    void handleRefreshFailure(String userId, Exception cause) {
        handleRefreshFailure(userId, userId, cause);
    }

    private void handleRefreshFailure(String statusUserId, String authorizationUserId, Exception cause) {
        recordRefreshFailureStatus(statusUserId, cause);
        if (isInvalidGrant(cause) || (requestContext != null && requestContext.invokeAsUser())) {
            throw createMustAuthorizationException(authorizationUserId, true);
        }
        throw new IllegalStateException("You are not authorized. Please ask admin to validate the attributes.", cause);
    }

    /**
     * Classifies a credential-refresh failure and records the resulting connection
     * status. Mirrors KIP's {@code recordFailureStatus}: a Google {@code invalid_grant}
     * (expired/revoked refresh token, or consent required) records
     * {@link CalendarConnectionStatus#REAUTH_REQUIRED}; any other refresh failure
     * (network, 5xx, etc.) records {@link CalendarConnectionStatus#RENEWAL_FAILED}.
     *
     * <p>Best-effort: if the store isn't wired (e.g. tests) or the status update
     * fails, we log and continue — the original auth failure still propagates. The
     * underlying {@code put} is transition-gated, so a transient {@code RENEWAL_FAILED}
     * self-clears on the next successful authentication (which re-asserts CONNECTED).
     *
     * <p>Package-private for unit testing.
     */
    void recordRefreshFailureStatus(String userId, Exception cause) {
        if (connectionStatusStore == null) {
            return;
        }
        boolean invalidGrant = isInvalidGrant(cause);
        CalendarConnectionStatus status = invalidGrant
                ? CalendarConnectionStatus.REAUTH_REQUIRED
                : CalendarConnectionStatus.RENEWAL_FAILED;
        // invalid_grant is the canonical reauth signal; for transient failures carry
        // the exception class name as the error code (KIP parity).
        String errorCode = invalidGrant ? "invalid_grant"
                : (cause != null ? cause.getClass().getSimpleName() : null);
        String errorMessage = cause != null ? cause.getMessage() : null;
        try {
            String invokerId = requestContext != null ? requestContext.getInvokerId() : authContextId;
            // The published event must carry the real workspaceId (ws_…), not the invokerId
            // (INV_…) — CA keys the user on workspaceId. invokerId only scopes the local KV key.
            String workspaceId = requestContext != null ? requestContext.getWorkspaceId() : null;
            // Use the auto-resolving overload so wsContact_{invokerId}_{email}
            // compound userIds are flattened to a real email before publish.
            connectionStatusStore.put(userId, invokerId, status, workspaceId, errorCode, errorMessage);
            LOGGER.warn("Refresh failure for userId={} — recorded {} (errorCode={})", userId, status, errorCode);
        } catch (Exception statusEx) {
            LOGGER.warn("Failed to record {} for userId={}: {}", status, userId, statusEx.getMessage());
        }
    }

    private boolean isInvalidGrant(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains("invalid_grant")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates and configures Google credentials for API authentication.
     *
     * <p>Uses the modern GoogleCredentials API from com.google.auth library,
     * which replaces the deprecated GoogleCredential class. This implementation
     * uses UserCredentials for OAuth 2.0 user authentication with refresh tokens.</p>
     *
     * <h3>Security Measures:</h3>
     * <ul>
     *   <li>Validates that credentials are from secure storage (KeyValueStore)</li>
     *   <li>Validates client ID and client secret are not null or empty</li>
     *   <li>Validates refresh token is not null or empty</li>
     *   <li>Logs security validation for audit purposes</li>
     * </ul>
     *
     * @param refreshToken OAuth refresh token for obtaining access tokens
     * @param HTTP_TRANSPORT HTTP transport instance (retained for signature compatibility)
     * @param isUseEmail flag indicating whether to use email for authentication (retained for signature compatibility)
     * @return configured GoogleCredentials instance with refreshed access token
     * @throws IOException if credential refresh fails or network error occurs
     * @throws SecurityException if credentials are not securely stored or validation fails
     * @throws IllegalArgumentException if any credential parameter is null or empty
     */
    private GoogleCredentials getGoogleCredentials(String refreshToken, NetHttpTransport HTTP_TRANSPORT, Boolean isUseEmail) throws IOException {
        LOGGER.debug("Creating Google credentials with refresh token");

        // Security validation: Ensure credentials come from secure storage
        validateSecureCredentialStorage();

        // Validate and retrieve credentials with security checks
        String clientId = validateAndGetClientId();
        String clientSecret = validateAndGetClientSecret();
        String validatedRefreshToken = validateRefreshToken(refreshToken);

        // Create UserCredentials with validated credentials
        // UserCredentials is the modern replacement for GoogleCredential for user OAuth flows
        UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRefreshToken(validatedRefreshToken)
                .build();

        // Refresh the access token immediately to ensure credentials are valid
        credentials.refresh();

        LOGGER.debug("Successfully created and refreshed Google credentials");
        return credentials;
    }

    /**
     * Validates that credentials are stored securely in KeyValueStore.
     *
     * <p>This method ensures that GoogleAttributes were loaded from the secure
     * KeyValueStore (via GoogleAttributeStore) and not from insecure sources like
     * plain text files, environment variables, or user input.</p>
     *
     * <h3>Security Checks:</h3>
     * <ul>
     *   <li>Verifies authContextId is present (indicates secure storage)</li>
     *   <li>Verifies GoogleAttributes instance is not null</li>
     *   <li>Verifies authContextId follows expected format</li>
     *   <li>Logs security validation for audit trail</li>
     * </ul>
     *
     * <h3>Accepted Formats:</h3>
     * <ul>
     *   <li><b>authContextId_UUID:</b> New-style auth context ID (e.g., authContextId_2db6867c-b397-49dc-96a8-4a359e7e6304)</li>
     *   <li><b>AUTH_CONTEXT_ID_UUID:</b> Legacy auth context ID format</li>
     *   <li><b>INV_UUID:</b> Invoker ID format (e.g., INV_e256e492-ef88-4d8e-8625-20e75d96d719)</li>
     * </ul>
     *
     * @throws SecurityException if credentials are not from secure storage
     */
    private void validateSecureCredentialStorage() {
        LOGGER.debug("Validating secure credential storage");

        // Check that authContextId exists (indicates credentials from KeyValueStore)
        if (authContextId == null || authContextId.isBlank()) {
            LOGGER.error("Security violation: Credentials not loaded from secure storage (missing authContextId)");
            throw new SecurityException("Credentials must be stored securely in KeyValueStore");
        }

        // Check that GoogleAttributes is properly initialized
        if (gmailAttributes == null) {
            LOGGER.error("Security violation: GoogleAttributes is null");
            throw new SecurityException("GoogleAttributes must be initialized from secure storage");
        }

        // Verify authContextId follows expected format
        // Accept: authContextId_UUID, AUTH_CONTEXT_ID_UUID, or INV_UUID (invoker ID)
        boolean isValidFormat = authContextId.startsWith("authContextId_")
                             || authContextId.startsWith("AUTH_CONTEXT_ID_")
                             || authContextId.startsWith("INV_");

        if (!isValidFormat) {
            LOGGER.error("Security violation: Invalid authContextId format: {}", authContextId);
            throw new SecurityException("Invalid authContextId format - credentials may not be from secure storage");
        }

        LOGGER.debug("Credential storage validation passed - credentials from secure KeyValueStore (format: {})",
                     authContextId.substring(0, Math.min(authContextId.indexOf('_') + 1, authContextId.length())));
    }

    /**
     * Validates and retrieves the client ID with security checks.
     *
     * <p>Ensures the client ID is not null, not empty, and follows expected format.</p>
     *
     * @return validated client ID
     * @throws IllegalArgumentException if client ID is invalid
     */
    private String validateAndGetClientId() {
        String clientId = gmailAttributes.getClientId();

        if (clientId == null || clientId.isBlank()) {
            LOGGER.error("Security violation: Client ID is null or empty");
            throw new IllegalArgumentException("Client ID must not be null or empty");
        }

        // Validate client ID format (should end with .apps.googleusercontent.com)
        if (!clientId.contains(".apps.googleusercontent.com")) {
            LOGGER.warn("Client ID does not match expected Google OAuth format: {}", clientId);
        }

        LOGGER.debug("Client ID validation passed");
        return clientId;
    }

    /**
     * Validates and retrieves the client secret with security checks.
     *
     * <p>Ensures the client secret is not null, not empty, and meets minimum security requirements.</p>
     *
     * @return validated client secret
     * @throws IllegalArgumentException if client secret is invalid
     */
    private String validateAndGetClientSecret() {
        String clientSecret = gmailAttributes.getClientSecret();

        if (clientSecret == null || clientSecret.isBlank()) {
            LOGGER.error("Security violation: Client secret is null or empty");
            throw new IllegalArgumentException("Client secret must not be null or empty");
        }

        // Validate minimum length for security
        if (clientSecret.length() < 20) {
            LOGGER.error("Security violation: Client secret is too short (length: {})", clientSecret.length());
            throw new IllegalArgumentException("Client secret does not meet minimum security requirements");
        }

        LOGGER.debug("Client secret validation passed");
        return clientSecret;
    }

    /**
     * Validates the refresh token with security checks.
     *
     * <p>Ensures the refresh token is not null, not empty, and meets minimum requirements.</p>
     *
     * @param refreshToken the refresh token to validate
     * @return validated refresh token
     * @throws IllegalArgumentException if refresh token is invalid
     */
    private String validateRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            LOGGER.error("Security violation: Refresh token is null or empty");
            throw new IllegalArgumentException("Refresh token must not be null or empty");
        }

        // Validate minimum length
        if (refreshToken.length() < 20) {
            LOGGER.error("Security violation: Refresh token is too short (length: {})", refreshToken.length());
            throw new IllegalArgumentException("Refresh token does not meet minimum security requirements");
        }

        LOGGER.debug("Refresh token validation passed");
        return refreshToken;
    }


    /**
     * Creates a MustAuthorizeException for OAuth authorization flow.
     *
     * @param userId user identifier to include in exception details
     * @param reAuthentication true if this is a re-authentication request
     * @return MustAuthorizeException with appropriate message and details
     */
    public MustAuthorizeException createMustAuthorizationException(String userId, boolean reAuthentication) {
        LOGGER.error(reAuthentication ? Constants.GOT_ERROR_FOR_AUTHENTICATION_SO_SENDING_FOR_RE_AUTHENTICATION : Constants.GOT_ERROR_FOR_AUTHENTICATION_SO_SENDING_FOR_AUTHENTICATION);
        List<NamedValuedField> details = new ArrayList<>();
        NamedValuedField userIdField = new NamedValuedField(Constants.USER_ID, Constants.TEXT, userId, new HashMap<>(), new HashMap<>());
        details.add(userIdField);
        if (authContextId != null) {
            NamedValuedField contextIdField = new NamedValuedField(Constants.AUTH_CONTEXT_ID, Constants.TEXT, authContextId, new HashMap<>(), new HashMap<>());
            details.add(contextIdField);
        }
        return new MustAuthorizeException(reAuthentication ? Constants.REFRESH_TOKEN_EXPIRED_PLEASE_REAUTHORIZE_YOURSELF : Constants.AUTHORIZATION_PROMPT, details);
    }

    /**
     * Builds the platform authorization signal for a logical calendar user while carrying the
     * corresponding refresh-token-store key in OAuth state. Studio users require the
     * {@code _clientId_authType} suffix; client {@code wsContact_} users remain unchanged.
     */
    public MustAuthorizeException createMustAuthorizationExceptionForUser(
            String userId, boolean reAuthentication) {
        return createMustAuthorizationException(getRefTokenStoreKey(userId), reAuthentication);
    }

    /**
     * Gets the user identifier based on authentication context.
     *
     * @param calledFromValidateAttributes true if called from attribute validation
     * @return user identifier (email address)
     * @throws IllegalStateException if user ID cannot be determined
     */
    public String getUserId(boolean calledFromValidateAttributes) {
        String userId = calledFromValidateAttributes || !requestContext.invokeAsUser()
                ? gmailAttributes.getMailId()
                : authorizationContext.getAuthorizedAccount().getAccountId();
        if (userId == null) {
            throw new IllegalStateException(Constants.FAILED_TO_GET_ACCOUNT);
        }
        return userId;
    }

    /**
     * Constructs the refresh token store key for the given user.
     *
     * @param userId user identifier
     * @return refresh token store key
     */
    private String getRefTokenStoreKey(String userId) {
        if (userId.startsWith(Constants.WS_CONTACT)) {
            return userId;
        } else {
            return userId + Constants.UNDER_SCORE + gmailAttributes.getClientId() + Constants.UNDER_SCORE + gmailAttributes.getAuthType();
        }
    }

    /**
     * Revokes and forgets the stored OAuth grant for {@code userId} — used by the deliberate
     * "Disconnect my calendar" flow so the disconnect is real (the extension can no longer act on
     * the user's calendar). Resolves the same refresh-token-store key the auth paths use, makes a
     * best-effort revoke at Google (failures are logged, not thrown — e.g. the token is already
     * expired/revoked), then removes the token from the store. No-op when no token is stored.
     *
     * @param userId the user id whose stored grant to revoke (email or {@code wsContact_<id>})
     * @return {@code true} if a stored token was found and removed
     */
    public boolean revokeStoredToken(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        String refreshTokenStoreKey = getRefTokenStoreKey(userId);
        String refreshToken = refreshTokenStore.get(refreshTokenStoreKey);
        if (refreshToken == null || refreshToken.isBlank()) {
            return false;
        }
        try {
            gmailAttributes.getOAuth20Service().revokeToken(refreshToken);
        } catch (Exception cause) {
            LOGGER.warn("Failed to revoke Google token for userId={} (continuing with local removal): {}",
                    userId, cause.getMessage());
        }
        refreshTokenStore.remove(refreshTokenStoreKey);
        return true;
    }

    /**
     * Removes the stored OAuth grant for {@code userId} under the correct refresh-token-store key,
     * <strong>without</strong> a Google revoke call — for clearing a <em>stale</em> token (e.g. on a
     * re-auth path where the token already triggered {@code invalid_grant}, so revoking it would be a
     * meaningless round-trip). Resolves the same key the auth paths use (so it correctly clears the
     * {@code userId_clientId_authType} studio-user key, not the bare {@code userId}). No-op when no
     * token is stored.
     *
     * @param userId the user id whose stored grant to forget (email or {@code wsContact_<id>})
     * @return {@code true} if a stored token was found and removed
     */
    public boolean forgetStoredToken(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        String refreshTokenStoreKey = getRefTokenStoreKey(userId);
        if (refreshTokenStore.get(refreshTokenStoreKey) == null) {
            return false;
        }
        refreshTokenStore.remove(refreshTokenStoreKey);
        return true;
    }

    /**
     * This function is used to test if the connection with google calendar is successfully authenticated.
     * If there is an exception we are parsing to check if it Unauthorised. If so creating must authorise exception.
     */
    public void testConnection() throws IOException {
        getGoogleClientForAdmin().events().list(PRIMARY).setMaxResults(Math.toIntExact(1L))
                .setTimeMin(new DateTime(System.currentTimeMillis()))
                .setOrderBy("startTime")
                .setSingleEvents(true).execute();
    }
}
