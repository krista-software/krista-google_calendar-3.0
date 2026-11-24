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

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.Hk2ContextHolder;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarNotificationHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarWatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * REST resource for handling Google Calendar push notification webhooks.
 *
 * <p>Google Calendar push notifications are delivered as POST requests with:
 * <ul>
 *   <li>{@code X-Goog-Channel-ID} — the channel ID from the watch request</li>
 *   <li>{@code X-Goog-Resource-ID} — the resource ID being watched</li>
 *   <li>{@code X-Goog-Resource-State} — "sync" (initial), "exists" (change), or "not_exists" (deleted)</li>
 *   <li>{@code X-Goog-Message-Number} — sequential message number</li>
 * </ul>
 * The notification body is empty — actual changes must be fetched via incremental sync.</p>
 */
@Path("/calendar")
public class CalendarWebhookResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarWebhookResource.class);

    /**
     * Shared executor for async notification processing. A single long-lived
     * virtual-thread executor — replaces the previous bug of creating (and never
     * shutting down) a new executor on every notification.
     */
    private static final ExecutorService NOTIFICATION_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final CalendarWatchService watchService;
    private final CalendarNotificationHandler notificationHandler;
    private final Hk2ContextHolder hk2ContextHolder;

    @Inject
    public CalendarWebhookResource(CalendarWatchService watchService,
                                   CalendarNotificationHandler notificationHandler,
                                   Hk2ContextHolder hk2ContextHolder) {
        this.watchService = watchService;
        this.notificationHandler = notificationHandler;
        this.hk2ContextHolder = hk2ContextHolder;
    }

    /**
     * Handles Google Calendar push notification webhooks.
     * Google sends POST requests with notification metadata in headers (no body).
     *
     * @param channelId     the channel ID from the watch request
     * @param resourceId    the watched resource ID
     * @param resourceState the state: "sync", "exists", or "not_exists"
     * @param messageNumber the sequential message number
     * @return 200 OK (Google requires 200 within seconds or will retry/stop)
     */
    @POST
    @Path("/notification")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleNotification(
            @HeaderParam("X-Goog-Channel-ID") String channelId,
            @HeaderParam("X-Goog-Channel-Token") String channelToken,
            @HeaderParam("X-Goog-Resource-ID") String resourceId,
            @HeaderParam("X-Goog-Resource-State") String resourceState,
            @HeaderParam("X-Goog-Message-Number") String messageNumber) {

        LOGGER.info("Received Google Calendar notification: channelId={}, resourceId={}, state={}, messageNumber={}",
                channelId, resourceId, resourceState, messageNumber);

        if (channelId == null || channelId.isBlank()) {
            LOGGER.warn("Received notification without channel ID. Ignoring.");
            return Response.ok().build();
        }

        // Process asynchronously to return 200 quickly (Google requires fast response)
        NOTIFICATION_EXECUTOR.submit(() -> {
            try {
                // Restore the HK2/invoker context (this worker thread has none), so the
                // invoker-scoped stores and GoogleProviderFactory.create() work.
                hk2ContextHolder.restoreToCurrentThread();
                // Resolve the owning user: channel token (carried in the notification) first,
                // then the channelId reverse index, then a legacy scan.
                String userId = watchService.resolveUser(channelId, channelToken);
                if (userId == null) {
                    LOGGER.warn("No user found for channelId={}. Notification may be from expired channel.", channelId);
                    return;
                }

                for (int attempt = 0; attempt < 3; attempt++) {
                    if (notificationHandler.handleNotification(userId, channelId, resourceState)) return;
                    if (attempt < 2) java.util.concurrent.TimeUnit.SECONDS.sleep(1L << attempt);
                }
                LOGGER.warn("Calendar notification retries exhausted for {}; sync cursor retained for replay", userId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.error("Error processing calendar notification: {}", e.getMessage(), e);
            } finally {
                com.kristasoft.common.holders.ThreadLocalProxy.setAll(new java.util.HashMap<>());
            }
        });

        // Always return 200 OK immediately
        return Response.ok().build();
    }
}
