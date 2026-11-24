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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb;

import app.krista.conversationagent.module.calendar.esb.CalendarAuthInitiateCommandProto;
import app.krista.conversationagent.module.calendar.esb.CalendarAuthInitiateReplyProto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.auth.GoogleOAuthUrlService;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.NoSuchElementException;

/**
 * ESB listener for {@code krista.calendar.auth.initiate.google} queue events.
 *
 * <p>Receives a {@link CalendarAuthInitiateCommandProto} from CA Service, constructs
 * a Google OAuth authorization URL via {@link GoogleOAuthUrlService}, and returns a
 * {@link CalendarAuthInitiateReplyProto} via the JMS reply-to mechanism.
 *
 * <p>Both DTOs come from the staged CA contract jar since KIM-492 WP-E15; they were hand-authored
 * in this package until then. Field numbers, names and types are unchanged — see
 * {@link CalendarReconnectEsbProtoInitializer} for why keeping a local copy alongside the jar's
 * was not an option.
 *
 * @since 3.0.3
 */
@Service
public class CalendarAuthInitiateEsbListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarAuthInitiateEsbListener.class);

    /** Calendar system this listener is responsible for; matches the queue suffix. */
    static final String EXPECTED_SYSTEM = "GOOGLE";

    // Spec §B / §B.3 error codes (machine-readable, fixed taxonomy)
    static final String ERROR_SYSTEM_MISMATCH = "SYSTEM_MISMATCH";
    static final String ERROR_NOT_REGISTERED = "NOT_REGISTERED";
    static final String ERROR_INVALID_REQUEST = "INVALID_REQUEST";
    static final String ERROR_INTERNAL = "INTERNAL_ERROR";

    private final GoogleOAuthUrlService oauthUrlService;

    @Inject
    public CalendarAuthInitiateEsbListener(GoogleOAuthUrlService oauthUrlService) {
        this.oauthUrlService = oauthUrlService;
    }

    /**
     * Handle a calendar auth-initiate command.
     *
     * @param cmd the command from CA
     * @return reply with auth URL on success, error fields on failure
     */
    public CalendarAuthInitiateReplyProto handle(CalendarAuthInitiateCommandProto cmd) {
        LOGGER.info("Processing CalendarAuthInitiate (Google): ws={} email={} system={} correlationId={}",
                cmd.getWorkspaceId(), cmd.getUserEmail(), cmd.getCalendarSystem(), cmd.getCorrelationId());

        CalendarAuthInitiateReplyProto reply = new CalendarAuthInitiateReplyProto();

        // Spec §B.2: validate calendarSystem matches the queue's system.
        String sys = cmd.getCalendarSystem();
        if (sys == null || !sys.trim().equalsIgnoreCase(EXPECTED_SYSTEM)) {
            LOGGER.warn("CalendarAuthInitiate (Google) rejected — calendarSystem={} does not match queue={}",
                    sys, EXPECTED_SYSTEM);
            return errorReply(reply, ERROR_SYSTEM_MISMATCH,
                    "calendarSystem '" + sys + "' does not match this queue (" + EXPECTED_SYSTEM + ")");
        }

        try {
            GoogleOAuthUrlService.AuthUrlResult result = oauthUrlService.buildAuthUrl(
                    cmd.getWorkspaceId(), cmd.getUserEmail());

            long now = System.currentTimeMillis();
            reply.setOk(true);
            reply.setAuthUrl(result.getAuthUrl());
            reply.setAuthInitiatedAtEpochMillis(now);
            reply.setExpiresAtEpochMillis(now + (long) result.getExpiresInSeconds() * 1000L);

            LOGGER.info("CalendarAuthInitiate (Google) succeeded: ws={} email={}",
                    cmd.getWorkspaceId(), cmd.getUserEmail());
            return reply;

        } catch (NoSuchElementException e) {
            // No Google OAuth config for this workspace — spec §B.3.
            LOGGER.warn("CalendarAuthInitiate (Google) rejected — workspace not registered: ws={} email={}: {}",
                    cmd.getWorkspaceId(), cmd.getUserEmail(), e.getMessage());
            return errorReply(reply, ERROR_NOT_REGISTERED, e.getMessage());

        } catch (IllegalArgumentException e) {
            LOGGER.warn("CalendarAuthInitiate (Google) rejected — invalid request: ws={} email={}: {}",
                    cmd.getWorkspaceId(), cmd.getUserEmail(), e.getMessage());
            return errorReply(reply, ERROR_INVALID_REQUEST, e.getMessage());

        } catch (Exception e) {
            LOGGER.error("CalendarAuthInitiate (Google) failed: ws={} email={}: {}",
                    cmd.getWorkspaceId(), cmd.getUserEmail(), e.getMessage(), e);
            return errorReply(reply, ERROR_INTERNAL, e.getMessage());
        }
    }

    private static CalendarAuthInitiateReplyProto errorReply(CalendarAuthInitiateReplyProto reply,
                                                              String code, String message) {
        reply.setOk(false);
        reply.setErrorCode(code);
        reply.setErrorMessage(message);
        return reply;
    }
}
