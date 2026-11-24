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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatus;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.SendEventsToMAService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Admin REST resource for forcing / reading a user's Google Calendar connection
 * status, used to exercise the various authentication flows (and the resulting
 * Conversation-Agent state) on demand without driving real OAuth failures.
 *
 * <p>Mirrors the KIP (Microsoft Teams Transcribe) {@code AdminResource} contract:
 * the request must carry the admin key in the {@code x-admin-key} header. The
 * full path (the extension {@code @ApplicationPath} is {@code googlecalendar}) is:
 * <pre>
 *   POST /rest/googlecalendar/api/v1/admin/users/{userKey}/calendar/status?status=CONNECTED&amp;email=...
 *   GET  /rest/googlecalendar/api/v1/admin/users/{userKey}/calendar/status
 * </pre>
 *
 * <p>Forcing a status persists it and publishes <strong>unconditionally</strong>
 * to CA via {@link CalendarConnectionStatusStore#forceStatus}. For client
 * {@code wsContact_<id>} keys (which carry no email) the {@code email} query
 * param is required for the publish to reach CA — otherwise the status is still
 * persisted locally but {@code published} is {@code false}.
 *
 * <p>{@code DISCONNECTED} is a flag-only operation (mirrors KIP): the refresh
 * token is not removed, so the user can be restored by forcing {@code CONNECTED}.
 */
@Path("api/v1/admin")
public final class AdminCalendarStatusResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminCalendarStatusResource.class);

    /** Admin key required in the {@code x-admin-key} header (matches KIP's {@code ADMIN_API_KEY}). */
    private static final String ADMIN_API_KEY = "Theja";
    private static final String ADMIN_KEY_HEADER = "x-admin-key";

    /** Error context carried in the forced status event (mirrors KIP). */
    private static final String FORCE_ERROR_CODE = "ADMIN_FORCE_SET";
    private static final String FORCE_ERROR_MESSAGE = "Forced from Google Calendar admin API";

    /** Sentinel written over a user's refresh token by {@link #simulateTokenExpiry} so the next
     *  credential refresh fails with a genuine {@code invalid_grant}. */
    private static final String SIMULATED_INVALID_TOKEN = "SIMULATED_INVALID_GRANT";

    private static final List<String> ALLOWED_STATUSES =
            Arrays.stream(CalendarConnectionStatus.values()).map(Enum::name).toList();

    private final CalendarConnectionStatusStore connectionStatusStore;
    private final RefreshTokenStore refreshTokenStore;
    private final SendEventsToMAService sendEventsToMAService;
    private final String invokerId;
    /** Real workspace id ({@code ws_…}) published in the CA event — distinct from the invoker id. */
    private final String workspaceId;

    @Inject
    public AdminCalendarStatusResource(CalendarConnectionStatusStore connectionStatusStore,
                                       RefreshTokenStore refreshTokenStore,
                                       SendEventsToMAService sendEventsToMAService,
                                       Invoker invoker) {
        this.connectionStatusStore = connectionStatusStore;
        this.refreshTokenStore = refreshTokenStore;
        this.sendEventsToMAService = sendEventsToMAService;
        this.invokerId = invoker.getInvokerId();
        this.workspaceId = invoker.getWorkspaceId();
    }

    /**
     * Force-sets the connection status for {@code userKey} and publishes it to CA.
     *
     * @param apiKey  the {@code x-admin-key} header
     * @param userKey the user key (RefreshTokenStore key — email, {@code email#suffix}, or {@code wsContact_<id>})
     * @param status  one of {@code CONNECTED}, {@code REAUTH_REQUIRED}, {@code RENEWAL_FAILED}, {@code DISCONNECTED} (case-insensitive)
     * @param email   the user's email — required for {@code wsContact_<id>} keys; may be {@code null} otherwise
     */
    @POST
    @Path("users/{userKey}/calendar/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response forceCalendarStatus(@HeaderParam(ADMIN_KEY_HEADER) String apiKey,
                                        @PathParam("userKey") String userKey,
                                        @QueryParam("status") String status,
                                        @QueryParam("email") String email) {
        Response authError = validateApiKey(apiKey);
        if (authError != null) {
            return authError;
        }

        CalendarConnectionStatus parsed = parseStatus(status);
        if (parsed == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid or missing status", "allowed", ALLOWED_STATUSES))
                    .build();
        }

        boolean published = connectionStatusStore.forceStatus(userKey, invokerId, parsed,
                workspaceId, email, FORCE_ERROR_CODE, FORCE_ERROR_MESSAGE);
        LOGGER.info("Admin force-set calendar status for userKey={} to {} (published={})",
                userKey, parsed, published);

        return Response.ok(Map.of(
                "userKey", userKey,
                "status", parsed.name(),
                "email", email == null ? "" : email,
                "published", published)).build();
    }

    /**
     * Reads the currently stored connection status for {@code userKey}, or
     * {@code UNKNOWN} if none has been recorded.
     */
    @GET
    @Path("users/{userKey}/calendar/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCalendarStatus(@HeaderParam(ADMIN_KEY_HEADER) String apiKey,
                                      @PathParam("userKey") String userKey) {
        Response authError = validateApiKey(apiKey);
        if (authError != null) {
            return authError;
        }

        CalendarConnectionStatus current = connectionStatusStore.get(userKey, invokerId);
        return Response.ok(Map.of(
                "userKey", userKey,
                "status", current == null ? "UNKNOWN" : current.name())).build();
    }

    /**
     * Simulates token expiry for {@code userKey} by overwriting its stored refresh token with an
     * invalid sentinel, so the <strong>next</strong> sync/watch/webhook produces a genuine Google
     * {@code invalid_grant} — exercising the real auto-detection → REAUTH_REQUIRED → CA → reminder
     * chain (unlike {@link #forceCalendarStatus}, which shortcuts detection by publishing directly).
     *
     * <p>Reversible: completing the normal OAuth reconnect overwrites the sentinel with a fresh token.
     *
     * @param apiKey  the {@code x-admin-key} header
     * @param userKey the RefreshTokenStore key (e.g. {@code wsContact_<id>})
     */
    @POST
    @Path("users/{userKey}/calendar/simulate-expiry")
    @Produces(MediaType.APPLICATION_JSON)
    public Response simulateTokenExpiry(@HeaderParam(ADMIN_KEY_HEADER) String apiKey,
                                        @PathParam("userKey") String userKey,
                                        @QueryParam("days") @DefaultValue("30") long days,
                                        @QueryParam("triggerSync") @DefaultValue("true") boolean triggerSync) {
        Response authError = validateApiKey(apiKey);
        if (authError != null) {
            return authError;
        }

        String existing = refreshTokenStore.get(userKey);
        if (existing == null || existing.isBlank()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No refresh token stored for userKey", "userKey", userKey))
                    .build();
        }

        refreshTokenStore.put(userKey, SIMULATED_INVALID_TOKEN);
        LOGGER.info("Admin simulate-expiry: invalidated stored refresh token for userKey={} (triggerSync={})",
                userKey, triggerSync);

        // Trigger the sync now so the genuine invalid_grant fires in this request → REAUTH_REQUIRED
        // is published and the banner appears, without waiting for the next sync/watch/webhook. The
        // sync path records REAUTH_REQUIRED inside getGoogle's catch and then swallows the failure,
        // so this returns normally.
        boolean syncTriggered = false;
        if (triggerSync) {
            try {
                sendEventsToMAService.sendEventsToMAByUser(userKey, days);
                syncTriggered = true;
            } catch (Exception e) {
                LOGGER.warn("Admin simulate-expiry: sync trigger for userKey={} failed: {}", userKey, e.getMessage());
            }
        }

        return Response.ok(Map.of("userKey", userKey, "simulatedExpiry", true, "syncTriggered", syncTriggered)).build();
    }

    /** Returns a 401 response when the admin key is missing/incorrect, else {@code null}. */
    private Response validateApiKey(String apiKey) {
        if (!ADMIN_API_KEY.equals(apiKey)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid or missing API key"))
                    .build();
        }
        return null;
    }

    private static CalendarConnectionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return CalendarConnectionStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
