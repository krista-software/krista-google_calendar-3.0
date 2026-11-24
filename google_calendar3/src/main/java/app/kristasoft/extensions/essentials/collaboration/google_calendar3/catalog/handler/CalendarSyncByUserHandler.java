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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.SendEventsToMAService;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Map;

@Service
public class CalendarSyncByUserHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarSyncByUserHandler.class);
    private static final long DEFAULT_NUMBER_OF_DAYS = 30L;

    private final SendEventsToMAService sendEventsToMAService;

    @Inject
    public CalendarSyncByUserHandler(SendEventsToMAService sendEventsToMAService) {
        this.sendEventsToMAService = sendEventsToMAService;
    }

    public ExtensionResponse execute(String userEmail, Double numberOfDays) {
        if (userEmail == null || userEmail.trim().isEmpty()) {
            throw new IllegalArgumentException("User Email is required");
        }

        long days = DEFAULT_NUMBER_OF_DAYS;
        if (numberOfDays != null) {
            days = numberOfDays.longValue();
            if (days <= 0) {
                days = DEFAULT_NUMBER_OF_DAYS;
                LOGGER.warn("Invalid numberOfDays value: {}. Using default: {}", numberOfDays, days);
            }
        }

        LOGGER.info("Calendar Sync by User triggered for user: {}, days: {}", userEmail, days);
        LOGGER.info("Initiating calendar sync process for user: {}", userEmail);

        sendEventsToMAService.sendEventsToMAByUser(userEmail, days);

        return new ExtensionResponseBuilder()
                .success(Map.of("Status", "Successfully sent events to MA."))
                .build();
    }
}
