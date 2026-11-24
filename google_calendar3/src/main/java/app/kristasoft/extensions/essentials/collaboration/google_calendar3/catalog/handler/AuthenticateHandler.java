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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.handler;

import app.krista.extension.authorization.MustAuthorizeException;
import app.krista.extension.executor.ErrorBuilder;
import app.krista.extension.executor.ExtensionResponse;
import app.krista.extension.executor.ExtensionResponseBuilder;
import app.krista.ksdk.context.RequestContext;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarAuthenticationService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarWatchService;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles the "Authenticate" catalog request for Google Calendar.
 *
 * <p>Validates the user's Google Calendar connection by attempting to obtain
 * a Google Calendar client. If the user is already authenticated (has a valid
 * refresh token), it confirms the connection and registers the user for
 * calendar sync. If not authenticated, it re-throws {@link MustAuthorizeException}
 * to trigger the OAuth authorization flow.</p>
 */
@Service
public class AuthenticateHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticateHandler.class);

    private final GoogleProviderFactory googleProviderFactory;
    private final RequestContext requestContext;
    private final GoogleCalendarAuthenticationService authenticationService;
    private final CalendarWatchService calendarWatchService;

    @Inject
    public AuthenticateHandler(GoogleProviderFactory googleProviderFactory,
                               RequestContext requestContext,
                               GoogleCalendarAuthenticationService authenticationService,
                               CalendarWatchService calendarWatchService) {
        this.googleProviderFactory = googleProviderFactory;
        this.requestContext = requestContext;
        this.authenticationService = authenticationService;
        this.calendarWatchService = calendarWatchService;
    }

    /**
     * Executes the authentication check.
     *
     * <p>Attempts to obtain a Google Calendar client to verify the user's
     * credentials are valid. On success, registers the user for calendar sync
     * and returns a success response with the user ID. On {@link MustAuthorizeException},
     * clears stale tokens and re-throws to trigger the OAuth flow.</p>
     *
     * @return {@link ExtensionResponse} with authentication status and user ID
     */
    public ExtensionResponse execute() {
        LOGGER.info("Executing AuthenticateHandler");

        try {
            boolean invokeAsUser = requestContext.invokeAsUser();
            LOGGER.info("Authentication mode - invokeAsUser: {}", invokeAsUser);

            // Create provider via factory — this will throw
            // MustAuthorizeException if no valid refresh token exists
            GoogleProvider googleProvider = googleProviderFactory.create();
            googleProvider.getGoogleClient();

            String userId = googleProvider.getUserId(!invokeAsUser);
            // Authenticated user's Google email — captured at OAuth time. Unlike "User Id"
            // (which is the Krista account id in invoke-as-user mode), this is always the email.
            String email = googleProvider.getGoogleAttributes().getMailId();
            LOGGER.info("User authentication completed successfully for userId: {}", userId);

            // Register user for calendar sync tracking. Resolve the END-USER email from their own
            // primary calendar — the wsContact_<id> key carries no email, and getGoogleAttributes()
            // .getMailId() is the invoker-configured account, not the user who authenticated — so CA
            // registers the correct user's calendar system. The published event must carry the real
            // workspaceId (ws_…), not the invokerId (INV_…) — CA keys the user on workspaceId, so an
            // invoker id makes it drop the event. invokerId still scopes the local status-store key.
            String connectionEmail = googleProvider.getPrimaryCalendarEmail(userId);
            authenticationService.registerUser(userId, requestContext.getInvokerId(),
                    requestContext.getWorkspaceId(), connectionEmail);

            // Subscribe the just-authenticated user to Google push notifications so calendar
            // changes are delivered immediately. Without this, a watch channel is only created
            // at extension load (invokerLoaded), so a user who authenticates later receives no
            // notifications until the next reload. Idempotent (skips if already active) and
            // guarded so a subscription failure (e.g. the push domain not being verified) does
            // not fail authentication.
            try {
                calendarWatchService.createWatchChannel(userId);
            } catch (Exception watchEx) {
                LOGGER.warn("Authenticated user {} but failed to create watch channel: {}",
                        userId, watchEx.getMessage(), watchEx);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("Status", "Successfully authenticated.");
            result.put("User Id", userId);
            if (email != null && !email.isBlank()) {
                result.put("Email", email);
            }

            return new ExtensionResponseBuilder()
                    .success(result)
                    .build();

        } catch (MustAuthorizeException e) {
            // Clear any stale tokens before re-triggering OAuth
            removeStaleTokens();
            throw e;

        } catch (Exception e) {
            LOGGER.error("Failed to validate connection: {}", e.getMessage(), e);
            return new ExtensionResponseBuilder()
                    .failure(new ErrorBuilder()
                            .errorMessage("Failed to validate connection: " + e.getMessage())
                            .build())
                    .build();
        }
    }

    /**
     * Removes stale refresh tokens when re-authentication is required.
     */
    private void removeStaleTokens() {
        try {
            GoogleProvider googleProvider = googleProviderFactory.create();
            String userId = googleProvider.getUserId(!requestContext.invokeAsUser());
            // forgetStoredToken resolves the correct refresh-token-store key (the raw userId is the
            // wrong key for studio users, where it is userId_clientId_authType) and removes only —
            // no Google revoke, since this token is already stale/expired.
            if (googleProvider.forgetStoredToken(userId)) {
                LOGGER.info("Removed stale refresh token for userId: {}", userId);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not remove stale tokens: {}", e.getMessage());
        }
    }
}
