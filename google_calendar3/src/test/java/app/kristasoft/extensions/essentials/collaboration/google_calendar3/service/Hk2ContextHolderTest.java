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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Hk2ContextHolderTest {
    private Map<Class<?>, Object> original;

    @BeforeEach
    void saveContext() {
        original = ThreadLocalProxy.getAll();
        ThreadLocalProxy.removeAll();
    }

    @AfterEach
    void restoreContext() {
        ThreadLocalProxy.removeAll();
        ThreadLocalProxy.setAll(original == null ? Map.of() : original);
    }

    @Test
    void usesLifecycleSnapshotRatherThanForeignWorkerContextAndRestoresWorker() {
        Hk2ContextHolder holder = captured("trusted");
        ThreadLocalProxy.removeAll();
        ThreadLocalProxy.setAll(Map.of(String.class, "other-workspace", Long.class, 99L));
        assertTrue(holder.runWithCapturedContext(() -> {
            assertEquals("trusted", ThreadLocalProxy.getAll().get(String.class));
            assertFalse(ThreadLocalProxy.getAll().containsKey(Long.class));
            ThreadLocalProxy.setAll(Map.of(Integer.class, 42));
        }));
        assertEquals(Map.of(String.class, "other-workspace", Long.class, 99L), ThreadLocalProxy.getAll());
        holder.runWithCapturedContext(() -> assertFalse(ThreadLocalProxy.getAll().containsKey(Integer.class)));
    }

    @Test
    void restoresWorkerEvenWhenOperationThrows() {
        Hk2ContextHolder holder = captured("trusted");
        ThreadLocalProxy.setAll(Map.of(String.class, "worker"));
        assertThrows(IllegalStateException.class, () -> holder.runWithCapturedContext(() -> {
            throw new IllegalStateException("sync failed");
        }));
        assertEquals(Map.of(String.class, "worker"), ThreadLocalProxy.getAll());
    }

    @Test
    void missingSnapshotDoesNotUseExistingWorkerContext() {
        Hk2ContextHolder holder = new Hk2ContextHolder();
        ThreadLocalProxy.setAll(Map.of(String.class, "foreign"));
        AtomicBoolean ran = new AtomicBoolean();
        assertFalse(holder.runWithCapturedContext(() -> ran.set(true)));
        assertFalse(ran.get());
        assertEquals(Map.of(String.class, "foreign"), ThreadLocalProxy.getAll());
    }

    @Test
    void failedRecaptureClearsStaleLifecycleContext() {
        Hk2ContextHolder holder = captured("old");
        TelemetryMetrics metrics = mock(TelemetryMetrics.class);
        holder.setTelemetryMetrics(metrics);
        ThreadLocalProxy.removeAll();
        assertFalse(holder.captureFromCurrentThread());
        verify(metrics).incrementCounter("google_calendar.hk2.context_capture_failed", 1L);
        assertFalse(holder.hasValidSnapshot());
        assertFalse(holder.runWithCapturedContext(() -> fail("stale context must not run")));
    }

    @Test
    void failedCaptureTelemetryCannotPreventFailClosedCleanup() {
        Hk2ContextHolder holder = captured("old");
        TelemetryMetrics metrics = mock(TelemetryMetrics.class);
        holder.setTelemetryMetrics(metrics);
        doThrow(new IllegalStateException("telemetry unavailable"))
                .when(metrics).incrementCounter("google_calendar.hk2.context_capture_failed", 1L);
        ThreadLocalProxy.removeAll();
        assertFalse(holder.captureFromCurrentThread());
        assertFalse(holder.hasValidSnapshot());
    }

    @Test
    void differentInvokerHoldersKeepSeparateSnapshots() {
        Hk2ContextHolder first = captured("first");
        Hk2ContextHolder second = captured("second");
        ThreadLocalProxy.removeAll();
        first.runWithCapturedContext(() -> assertEquals("first", ThreadLocalProxy.getAll().get(String.class)));
        second.runWithCapturedContext(() -> assertEquals("second", ThreadLocalProxy.getAll().get(String.class)));
        assertTrue(ThreadLocalProxy.getAll().isEmpty());
    }

    @Test
    void holderIsSharedByLifecycleAndConsumersWithinTheInvokerLocator() {
        assertTrue(Hk2ContextHolder.class.isAnnotationPresent(Singleton.class));
    }

    private Hk2ContextHolder captured(String marker) {
        ThreadLocalProxy.setAll(new HashMap<>(Map.of(String.class, marker)));
        Hk2ContextHolder holder = new Hk2ContextHolder();
        assertTrue(holder.captureFromCurrentThread());
        return holder;
    }
}
