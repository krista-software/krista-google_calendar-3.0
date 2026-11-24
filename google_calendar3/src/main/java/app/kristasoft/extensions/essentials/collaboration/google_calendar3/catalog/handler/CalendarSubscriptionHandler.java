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

import app.krista.extension.executor.ExtensionResponse;
import app.krista.extension.executor.ExtensionResponseBuilder;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarWatchService;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Map;

/**
 * Catalog handler for managing Google Calendar push notification subscriptions.
 * Supports subscribe, unsubscribe, and renew operations.
 */
@Service
public class CalendarSubscriptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarSubscriptionHandler.class);

    private final CalendarWatchService calendarWatchService;

    @Inject
    public CalendarSubscriptionHandler(CalendarWatchService calendarWatchService) {
        this.calendarWatchService = calendarWatchService;
    }

    /**
     * Subscribes all authenticated users to calendar push notifications.
     */
    public ExtensionResponse subscribe() {
        LOGGER.info("Calendar Subscription: subscribing all users");
        try {
            calendarWatchService.subscribeAllUsers();
            return new ExtensionResponseBuilder()
                    .success(Map.of("Status", "Successfully subscribed all users to calendar push notifications."))
                    .build();
        } catch (Exception e) {
            LOGGER.error("Failed to subscribe users: {}", e.getMessage(), e);
            return new ExtensionResponseBuilder()
                    .success(Map.of("Status", "Failed to subscribe: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Unsubscribes all authenticated users from calendar push notifications.
     */
    public ExtensionResponse unsubscribe() {
        LOGGER.info("Calendar Subscription: unsubscribing all users");
        try {
            calendarWatchService.unsubscribeAllUsers();
            return new ExtensionResponseBuilder()
                    .success(Map.of("Status", "Successfully unsubscribed all users from calendar push notifications."))
                    .build();
        } catch (Exception e) {
            LOGGER.error("Failed to unsubscribe users: {}", e.getMessage(), e);
            return new ExtensionResponseBuilder()
                    .success(Map.of("Status", "Failed to unsubscribe: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Renews watch channels that are about to expire.
     */
    public ExtensionResponse renew() {
        LOGGER.info("Calendar Subscription: renewing expiring channels");
        try {
            calendarWatchService.renewExpiringChannels();
            return new ExtensionResponseBuilder()
                    .success(Map.of("Status", "Successfully renewed expiring watch channels."))
                    .build();
        } catch (Exception e) {
            LOGGER.error("Failed to renew channels: {}", e.getMessage(), e);
            return new ExtensionResponseBuilder()
                    .success(Map.of("Status", "Failed to renew: " + e.getMessage()))
                    .build();
        }
    }
}
