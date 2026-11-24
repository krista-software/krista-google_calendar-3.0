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
 * Handles the "Disconnect My Calendar" catalog request — the deliberate, user-initiated
 * disconnect (the mirror of {@link AuthenticateHandler}).
 *
 * <p>Tears the connection down end to end:
 * <ol>
 *   <li>Revokes and forgets the stored OAuth grant ({@link GoogleProvider#revokeStoredToken}) so
 *       the extension can no longer act on the user's calendar.</li>
 *   <li>Stops the Google push-notification watch channel (idempotent).</li>
 *   <li>Unregisters the user from calendar sync and publishes
 *       {@code CalendarConnectionStatusChanged{status=DISCONNECTED, reason=USER_INITIATED}} to
 *       Conversation Agent, which <em>deletes</em> the user's calendar row (no reminder).</li>
 * </ol>
 *
 * <p>Steps 1 and 2 are best-effort (a broken/expired grant must still be disconnectable); the
 * authoritative step is the {@code unregisterUser} publish, which CA keys on the real
 * {@code workspaceId} ({@code ws_…}) from {@link RequestContext} — not the invoker id.
 */
@Service
public class DisconnectHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DisconnectHandler.class);

    private final GoogleProviderFactory googleProviderFactory;
    private final RequestContext requestContext;
    private final GoogleCalendarAuthenticationService authenticationService;
    private final CalendarWatchService calendarWatchService;

    @Inject
    public DisconnectHandler(GoogleProviderFactory googleProviderFactory,
                             RequestContext requestContext,
                             GoogleCalendarAuthenticationService authenticationService,
                             CalendarWatchService calendarWatchService) {
        this.googleProviderFactory = googleProviderFactory;
        this.requestContext = requestContext;
        this.authenticationService = authenticationService;
        this.calendarWatchService = calendarWatchService;
    }

    /**
     * Executes the disconnect. Resolves the user/invoker/workspace from the request context,
     * revokes + forgets the stored grant and stops the watch channel (both best-effort), then
     * unregisters the user so CA deletes the calendar row.
     *
     * @return {@link ExtensionResponse} with a status message
     */
    public ExtensionResponse execute() {
        LOGGER.info("Executing DisconnectHandler");

        try {
            boolean invokeAsUser = requestContext.invokeAsUser();
            String invokerId = requestContext.getInvokerId();
            String workspaceId = requestContext.getWorkspaceId();

            // create() loads the provider without validating the token, so a user whose grant is
            // already expired/revoked (REAUTH_REQUIRED) can still disconnect. getUserId() reads the
            // account/attributes, not the token — same resolution AuthenticateHandler uses.
            GoogleProvider googleProvider = googleProviderFactory.create();
            String userId = googleProvider.getUserId(!invokeAsUser);

            // 1. Stop push notifications first — while the token is still valid, since stopping the
            //    channel calls Google with the user's client (idempotent; best-effort).
            try {
                calendarWatchService.stopWatchChannel(userId);
            } catch (Exception watchEx) {
                LOGGER.warn("Disconnect: failed to stop watch channel for userId {}: {}",
                        userId, watchEx.getMessage(), watchEx);
            }

            // 2. Revoke + forget the stored OAuth grant (best-effort; no-op if already gone).
            try {
                googleProvider.revokeStoredToken(userId);
            } catch (Exception revokeEx) {
                LOGGER.warn("Disconnect: failed to revoke stored token for userId {}: {}",
                        userId, revokeEx.getMessage(), revokeEx);
            }

            // 3. Unregister from sync + publish DISCONNECTED{reason=USER_INITIATED} so CA deletes
            //    the row. Carries the real workspaceId (ws_…); invokerId only scopes the local key.
            authenticationService.unregisterUser(userId, invokerId, workspaceId);
            LOGGER.info("Disconnected Google Calendar for userId {} (workspaceId {})", userId, workspaceId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("Status", "Successfully disconnected your Google Calendar.");
            return new ExtensionResponseBuilder()
                    .success(result)
                    .build();

        } catch (Exception e) {
            LOGGER.error("Failed to disconnect Google Calendar: {}", e.getMessage(), e);
            return new ExtensionResponseBuilder()
                    .failure(new ErrorBuilder()
                            .errorMessage("Failed to disconnect Google Calendar: " + e.getMessage())
                            .build())
                    .build();
        }
    }
}
