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

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.GoogleAttributeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants.AUTH_CONTEXT_ID;
import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants.AUTHENTICATED_ACCOUNT_ID_STATE_MARKER;
import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants.INVOKER_ID_PREFIX;

/**
 * Utility class for authentication-related helper methods.
 *
 * <p>This class provides static utility methods for authentication operations including
 * state parameter parsing, auth context ID extraction, and cleanup operations for
 * authentication resources.</p>
 * @author Krista Software
 * @version 3.0.0
 * @since 3.0.0
 */
public final class AuthHelper {

    /** Logger instance for authentication helper operations. */
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthHelper.class);

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private AuthHelper() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Extracts the auth context ID from a state parameter string.
     *
     * <p>The state parameter typically contains multiple components separated by "#" characters.
     * This method locates and extracts the auth context ID component from the state string.</p>
     *
     * <h3>State Parameter Format:</h3>
     * <pre>
     * userId#authContextId#forwardPath
     * or
     * userId#authContextId
     * or
     * userId
     * </pre>
     *
     * <h3>Extraction Logic:</h3>
     * <ol>
     *   <li>Search for AUTH_CONTEXT_ID constant in the state string</li>
     *   <li>If not found, return null</li>
     *   <li>Extract substring from AUTH_CONTEXT_ID to end of string</li>
     *   <li>If "#" exists after AUTH_CONTEXT_ID, extract up to that "#"</li>
     *   <li>Otherwise, use the entire remaining substring</li>
     * </ol>
     *
     * @param state the state parameter string containing auth context ID, can be null
     * @return the extracted auth context ID, or null if not found or state is null
     *
     * @see app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants#AUTH_CONTEXT_ID
     */
    public static String getAuthContextId(String state) {
        if (state == null || state.isEmpty()) {
            LOGGER.debug("State parameter is null or empty");
            return null;
        }

        int authContextIdIndex = state.indexOf(AUTH_CONTEXT_ID);
        if (authContextIdIndex == -1) {
            LOGGER.debug("AuthContextId not found in state parameter");
            return null;
        }

        String authContextIdToEnd = state.substring(authContextIdIndex);
        int hashIndex = authContextIdToEnd.indexOf("#");
        String authContextId;
        if (hashIndex != -1) {
            // If there is a "#", end the extraction at this index
            authContextId = authContextIdToEnd.substring(0, hashIndex);
        } else {
            // If there is no "#", use the entire substring
            authContextId = authContextIdToEnd;
        }
        authContextId = stripStateMetadata(authContextId);

        LOGGER.debug("Extracted authContextId: {}", authContextId);
        return authContextId;
    }

    private static String stripStateMetadata(String value) {
        int markerIndex = value.indexOf(AUTHENTICATED_ACCOUNT_ID_STATE_MARKER);
        return markerIndex == -1 ? value : value.substring(0, markerIndex);
    }

    /**
     * Extracts the invokerId carried in the reconnect OAuth {@code state}.
     *
     * <p>The CA-initiated reconnect stamps the invokerId (resolved on the reliable ESB thread) into
     * the state — shape {@code key#invokerId_<id>#forwardPath} — so the forwarded OAuth callback can
     * load the invoker's OAuth client without depending on the forwarded-thread {@code Invoker}
     * (whose identity is unreliable across stages). Mirrors {@link #getAuthContextId(String)}.
     *
     * @param state the OAuth state, may be null
     * @return the bare invokerId (prefix stripped), or {@code null} if not present / state is null
     */
    public static String getInvokerId(String state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        int markerIndex = state.indexOf(INVOKER_ID_PREFIX);
        if (markerIndex == -1) {
            return null;
        }
        String fromMarker = state.substring(markerIndex + INVOKER_ID_PREFIX.length());
        int hashIndex = fromMarker.indexOf('#');
        String invokerId = hashIndex != -1 ? fromMarker.substring(0, hashIndex) : fromMarker;
        return invokerId.isBlank() ? null : invokerId;
    }

    /**
     * Cleans up authentication context by removing it from the attribute store.
     *
     * <p>This method is typically called in finally blocks to ensure authentication
     * resources are properly cleaned up after authentication operations complete,
     * whether successful or not.</p>
     *
     * <h3>Cleanup Behavior:</h3>
     * <ul>
     *   <li>If authContextId is null → No action taken (safe no-op)</li>
     *   <li>If authContextId is not null → Removes from googleAttributeStore</li>
     *   <li>Logs cleanup operation for debugging</li>
     *   <li>Handles exceptions gracefully without throwing</li>
     * </ul>
     *
     * <h3>Usage Example:</h3>
     * <pre>
     * String authContextId = null;
     * try {
     *     authContextId = providerFactory.createAttributes(attributes);
     *     // Perform authentication operations
     * } finally {
     *     AuthHelper.cleanupAuthContext(googleAttributeStore, authContextId);
     * }
     * </pre>
     *
     * @param googleAttributeStore the store containing authentication attributes, must not be null
     * @param authContextId the auth context ID to clean up, can be null (no-op if null)
     * @throws NullPointerException if googleAttributeStore is null
     *
     * @see GoogleAttributeStore#remove(String)
     */
    public static void cleanupAuthContext(GoogleAttributeStore googleAttributeStore, String authContextId) {
        if (googleAttributeStore == null) {
            throw new NullPointerException("GoogleAttributeStore cannot be null");
        }

        if (authContextId == null) {
            LOGGER.debug("AuthContextId is null, skipping cleanup");
            return;
        }

        try {
            LOGGER.debug("Cleaning up auth context: {}", authContextId);
            googleAttributeStore.remove(authContextId);
            LOGGER.debug("Successfully cleaned up auth context: {}", authContextId);
        } catch (Exception e) {
            LOGGER.warn("Failed to clean up auth context: {}, error: {}", authContextId, e.getMessage(), e);
            // Don't throw exception - cleanup should not fail the operation
        }
    }

    /**
     * Cleans up authentication context if a condition is met.
     *
     * <p>This is a conditional cleanup method that only performs cleanup if the
     * specified condition is true. Useful for cases where cleanup should only
     * happen in certain scenarios (e.g., when authUrl is null).</p>
     *
     * <h3>Usage Example:</h3>
     * <pre>
     * String authUrl = null;
     * String authContextId = null;
     * try {
     *     authContextId = providerFactory.createAttributes(attributes);
     *     // Perform operations that may set authUrl
     * } finally {
     *     AuthHelper.cleanupAuthContextIf(googleAttributeStore, authContextId, authUrl == null);
     * }
     * </pre>
     *
     * @param googleAttributeStore the store containing authentication attributes, must not be null
     * @param authContextId the auth context ID to clean up, can be null
     * @param condition if true, cleanup is performed; if false, no action taken
     * @throws NullPointerException if googleAttributeStore is null
     *
     * @see #cleanupAuthContext(GoogleAttributeStore, String)
     */
    public static void cleanupAuthContextIf(GoogleAttributeStore googleAttributeStore, String authContextId, boolean condition) {
        if (!condition) {
            LOGGER.debug("Cleanup condition not met, skipping cleanup for authContextId: {}", authContextId);
            return;
        }

        cleanupAuthContext(googleAttributeStore, authContextId);
    }
}
