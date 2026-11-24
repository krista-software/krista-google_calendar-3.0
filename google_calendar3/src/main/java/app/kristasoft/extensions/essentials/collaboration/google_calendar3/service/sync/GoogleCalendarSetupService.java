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

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.LiveGoogleCalendarConfiguration;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarWatchService;
import com.kristasoft.common.holders.ThreadLocalProxy;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Applies committed settings and establishes watches. Event sync is driven by CA commands. */
@Service
@Singleton
public class GoogleCalendarSetupService {
    private static final Logger LOG = LoggerFactory.getLogger(GoogleCalendarSetupService.class);
    private final LiveGoogleCalendarConfiguration configuration;
    private final CalendarDiscoveryService discovery;
    private final CalendarWatchService watches;
    private final Executor executor;

    @Inject
    public GoogleCalendarSetupService(LiveGoogleCalendarConfiguration configuration,
                                     CalendarDiscoveryService discovery,
                                     CalendarWatchService watches) {
        this(configuration, discovery, watches, task -> Thread.startVirtualThread(task));
    }

    GoogleCalendarSetupService(LiveGoogleCalendarConfiguration configuration,
                              CalendarDiscoveryService discovery,
                              CalendarWatchService watches,
                              Executor executor) {
        this.configuration = configuration;
        this.discovery = discovery;
        this.watches = watches;
        this.executor = executor;
    }

    public void applySavedConfiguration(Map<String, Object> attributes) {
        configuration.applySaved(attributes);
        if (!discovery.isAlwaysOn()) {
            return; // opt-in keeps its existing authentication/sync flow
        }
        Map<Class<?>, Object> context = ThreadLocalProxy.getAll();
        Map<Class<?>, Object> captured = context == null ? Map.of() : new HashMap<>(context);
        executor.execute(() -> {
            Map<Class<?>, Object> previous = ThreadLocalProxy.getAll();
            try {
                ThreadLocalProxy.removeAll();
                ThreadLocalProxy.setAll(captured);
                initializeRegisteredUsers();
            } catch (Exception failure) {
                LOG.warn("[GCAL-SETUP] Registered-user initialization failed; settings remain saved. "
                        + "The scheduled watch reconcile can retry.", failure);
            } finally {
                ThreadLocalProxy.removeAll();
                ThreadLocalProxy.setAll(previous == null ? Map.of() : previous);
            }
        });
    }

    private synchronized void initializeRegisteredUsers() {
        // Recheck on the worker: a newer save may have disabled Always On while this was queued.
        if (!discovery.isAlwaysOn()) {
            return;
        }
        watches.reconcileRegisteredSeatsNow();
    }
}
