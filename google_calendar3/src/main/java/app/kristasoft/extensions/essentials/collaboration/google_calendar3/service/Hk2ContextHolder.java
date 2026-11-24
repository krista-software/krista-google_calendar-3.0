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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service;

import app.krista.ksdk.telemetry.TelemetryMetrics;
import com.kristasoft.common.holders.ThreadLocalProxy;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds a snapshot of the HK2 {@link ThreadLocalProxy} context captured during
 * extension initialization (on a thread that has full HK2 / invoker context).
 *
 * <p>Inbound Google push notifications are processed on async executor threads that
 * have <strong>no</strong> HK2 context, so calls like {@code requestContext.getInvokerId()}
 * (used by {@code GoogleProviderFactory.create()}) fail with a NullPointerException.
 * ESB sync consumers have the same requirement. Restoring this snapshot on the worker thread
 * before processing fixes that — mirrors
 * the proven {@code ContextSnapshotHolder} pattern in the Teams extension.
 */
@Service
@Singleton
public class Hk2ContextHolder {

    private static final Logger LOGGER = LoggerFactory.getLogger(Hk2ContextHolder.class);

    private final AtomicReference<Map<Class<?>, Object>> snapshotRef =
            new AtomicReference<>(Collections.emptyMap());
    private TelemetryMetrics telemetryMetrics;

    @Inject
    public void setTelemetryMetrics(TelemetryMetrics telemetryMetrics) {
        this.telemetryMetrics = telemetryMetrics;
    }

    private void recordCaptureFailure() {
        if (telemetryMetrics != null) {
            try {
                telemetryMetrics.incrementCounter("google_calendar.hk2.context_capture_failed", 1L);
            } catch (Exception failure) {
                LOGGER.warn("Could not record failed context capture telemetry: {}", failure.getMessage());
            }
        }
    }

    /**
     * Captures the HK2 context from the current thread. Must be called from a thread
     * that has a valid context (e.g. during {@code invokerLoaded}).
     *
     * @return true if a non-empty context was captured
     */
    public boolean captureFromCurrentThread() {
        try {
            Map<Class<?>, Object> snapshot = ThreadLocalProxy.getAll();
            if (snapshot != null && !snapshot.isEmpty()) {
                snapshotRef.set(new HashMap<>(snapshot));
                LOGGER.info("Captured HK2 context snapshot with {} entries", snapshot.size());
                return true;
            }
            snapshotRef.set(Collections.emptyMap());
            LOGGER.warn("HK2 context snapshot is empty on the current thread.");
            recordCaptureFailure();
            return false;
        } catch (Exception e) {
            snapshotRef.set(Collections.emptyMap());
            LOGGER.warn("Failed to capture HK2 context snapshot: {}", e.getMessage());
            recordCaptureFailure();
            return false;
        }
    }

    /**
     * Restores the captured snapshot onto the current thread. Safe to call on a
     * context-less worker thread; a no-op if no snapshot was captured.
     */
    public void restoreToCurrentThread() {
        Map<Class<?>, Object> snapshot = snapshotRef.get();
        if (!snapshot.isEmpty()) {
            ThreadLocalProxy.setAll(new HashMap<>(snapshot));
        }
    }

    public boolean hasValidSnapshot() {
        return !snapshotRef.get().isEmpty();
    }

    /**
     * Runs an ESB operation in this invoker's trusted lifecycle context, never a context taken from
     * the message payload. Restores the previous worker context even when processing fails, so a
     * pooled worker cannot carry this workspace's proxies into its next message.
     *
     * @return false without running the operation if lifecycle initialization has not captured a
     *         context yet; callers must not fall back to another workspace's worker context
     */
    public boolean runWithCapturedContext(Runnable operation) {
        Map<Class<?>, Object> snapshot = snapshotRef.get();
        if (snapshot.isEmpty()) {
            LOGGER.warn("No invoker context captured; refusing context-less ESB calendar processing.");
            return false;
        }
        Map<Class<?>, Object> previous = ThreadLocalProxy.getAll();
        try {
            // setAll overlays individual ThreadLocals; it does not replace the worker's context.
            ThreadLocalProxy.removeAll();
            ThreadLocalProxy.setAll(new HashMap<>(snapshot));
            operation.run();
            return true;
        } finally {
            ThreadLocalProxy.removeAll();
            ThreadLocalProxy.setAll(previous == null ? Collections.emptyMap() : previous);
        }
    }
}
