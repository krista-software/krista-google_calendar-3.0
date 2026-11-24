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

import app.krista.ksdk.telemetry.TelemetryMetrics;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

@Service
public class CalendarSyncTelemetryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarSyncTelemetryService.class);
    private static final String PREFIX = "google_calendar.sync";

    private final TelemetryMetrics telemetryMetrics;

    @Inject
    public CalendarSyncTelemetryService(TelemetryMetrics telemetryMetrics) {
        this.telemetryMetrics = telemetryMetrics;
        LOGGER.info("CalendarSyncTelemetryService initialized");
    }

    public TelemetryContext recordSyncStart(String operation) {
        long startTime = System.currentTimeMillis();
        try {
            telemetryMetrics.incrementCounter(PREFIX + ".requests." + operation, 1L);
        } catch (Exception e) {
            LOGGER.warn("Failed to record sync start telemetry: {}", e.getMessage());
        }
        return new TelemetryContext(startTime, operation);
    }

    public void recordSyncSuccess(TelemetryContext context) {
        long duration = System.currentTimeMillis() - context.getStartTime();
        try {
            telemetryMetrics.incrementCounter(PREFIX + ".success." + context.getOperation(), 1L);
            telemetryMetrics.incrementCounter(PREFIX + ".duration." + context.getOperation(), duration);
        } catch (Exception e) {
            LOGGER.warn("Failed to record sync success telemetry: {}", e.getMessage());
        }
        LOGGER.debug("Sync completed successfully - Duration: {}ms", duration);
    }

    public void recordSyncFailure(TelemetryContext context, Exception exception) {
        long duration = System.currentTimeMillis() - context.getStartTime();
        try {
            telemetryMetrics.incrementCounter(PREFIX + ".failure." + context.getOperation(), 1L);
            telemetryMetrics.incrementCounter(PREFIX + ".duration." + context.getOperation(), duration);
            telemetryMetrics.incrementCounter(PREFIX + ".error." + exception.getClass().getSimpleName(), 1L);
        } catch (Exception e) {
            LOGGER.warn("Failed to record sync failure telemetry: {}", e.getMessage());
        }
        LOGGER.warn("Sync failed - Duration: {}ms, Error: {}", duration, exception.getMessage());
    }

    public void recordEventsProcessed(int count, String operation) {
        try {
            telemetryMetrics.incrementCounter(PREFIX + ".events.processed." + operation, (long) count);
        } catch (Exception e) {
            LOGGER.warn("Failed to record events processed telemetry: {}", e.getMessage());
        }
    }

    public void recordApiCall(String target, String operation, boolean success) {
        String status = success ? "success" : "failure";
        try {
            telemetryMetrics.incrementCounter(PREFIX + ".api." + target + "." + operation + "." + status, 1L);
        } catch (Exception e) {
            LOGGER.warn("Failed to record API call telemetry: {}", e.getMessage());
        }
    }

    public void recordUserProcessing(String userId, int eventCount, long durationMs) {
        try {
            telemetryMetrics.incrementCounter(PREFIX + ".events.processed.process_user", (long) eventCount);
            telemetryMetrics.incrementCounter(PREFIX + ".user.duration.process_user", durationMs);
        } catch (Exception e) {
            LOGGER.warn("Failed to record user processing telemetry: {}", e.getMessage());
        }
    }

    public static class TelemetryContext {
        private final long startTime;
        private final String operation;

        public TelemetryContext(long startTime, String operation) {
            this.startTime = startTime;
            this.operation = operation;
        }

        public long getStartTime() {
            return startTime;
        }

        public String getOperation() {
            return operation;
        }
    }
}
