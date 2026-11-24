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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Locale;

/**
 * Reconciles durable Google authentication with the CA calendar-connection state — the Opt-In
 * personal-token health check.
 *
 * <p>An OAuth callback normally publishes {@code CONNECTED}, but that event can be lost while the
 * ESB endpoint is being configured or restarted.  Reasserting the state after both consumers are
 * connected makes the status self-healing without treating a user as connected unless Google can
 * still resolve the user's primary calendar.</p>
 *
 * <p><b>Does not run while Always On is on</b> (owner ruling, 2026-11-10). It refreshes every
 * personal token, which is a calendar operation on a credential Always On does not use, and its
 * refresh failures would publish {@code REAUTH_REQUIRED} for people who have nothing to
 * re-authenticate. Switching Always On off brings it straight back: the next reconcile re-asserts
 * every consented user whose token still works, which is what restores their Opt-In statuses.</p>
 */
@Service
@Singleton
public class CalendarConnectionReconciliationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarConnectionReconciliationService.class);

    private final GoogleCalendarAuthenticationService authenticationService;
    private final GoogleProviderFactory googleProviderFactory;
    private final UserKeyIndexStore userKeyIndexStore;
    private final Invoker invoker;
    private final CalendarDiscoveryService discoveryService;

    @Inject
    public CalendarConnectionReconciliationService(
            GoogleCalendarAuthenticationService authenticationService,
            GoogleProviderFactory googleProviderFactory,
            UserKeyIndexStore userKeyIndexStore,
            Invoker invoker,
            CalendarDiscoveryService discoveryService) {
        this.authenticationService = authenticationService;
        this.googleProviderFactory = googleProviderFactory;
        this.userKeyIndexStore = userKeyIndexStore;
        this.invoker = invoker;
        this.discoveryService = discoveryService;
    }

    /**
     * Reasserts CONNECTED for authenticated users whose Google primary calendar is still readable.
     *
     * @return number of users successfully reasserted
     */
    public int reassertConnectedUsers() {
        if (discoveryService.isAlwaysOn()) {
            LOGGER.info("Skipping Google calendar connection reconciliation: Always On is on, so personal "
                    + "sign-ins are not checked and their statuses are not published");
            return 0;
        }
        List<String> users = authenticationService.getAuthenticatedUsers();
        if (users == null || users.isEmpty()) {
            return 0;
        }

        final String invokerId = invoker.getInvokerId();
        final String workspaceId = invoker.getWorkspaceId();
        if (isBlank(invokerId) || isBlank(workspaceId)) {
            LOGGER.warn("Skipping Google calendar connection reconciliation: invoker or workspace id is missing");
            return 0;
        }

        final GoogleProvider provider;
        try {
            // This method may run outside a request thread; use the extension-scoped id rather than
            // RequestContext, which is only populated for an active catalog request.
            provider = googleProviderFactory.createForInvoker(invokerId);
        } catch (Exception cause) {
            LOGGER.warn("Could not create Google provider for connection reconciliation: {}", cause.getMessage());
            return 0;
        }

        int reasserted = 0;
        for (String userId : users) {
            if (isBlank(userId)) {
                continue;
            }
            try {
                String email = normalizeEmail(provider.getPrimaryCalendarEmail(userId));
                if (email == null) {
                    LOGGER.warn("Skipping CONNECTED reassertion for {}: primary calendar could not be resolved", userId);
                    continue;
                }

                // Keep the reverse lookup healthy for wsContact_<id> users before any future
                // token-refresh failure needs to publish a status for that opaque key.
                userKeyIndexStore.put(invokerId, email, userId);
                authenticationService.registerUser(userId, invokerId, workspaceId, email);
                reasserted++;
            } catch (Exception cause) {
                // One broken token must not prevent the rest of the tenant from being reconciled.
                LOGGER.warn("Could not reconcile Google calendar connection for {}: {}",
                        userId, cause.getMessage());
            }
        }
        LOGGER.info("Google calendar connection reconciliation complete: {}/{} user(s) reasserted",
                reasserted, users.size());
        return reasserted;
    }

    private static String normalizeEmail(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
