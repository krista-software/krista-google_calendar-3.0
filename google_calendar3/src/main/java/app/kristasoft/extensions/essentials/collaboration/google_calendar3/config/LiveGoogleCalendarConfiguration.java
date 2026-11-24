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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.config;

import app.krista.extension.executor.Invoker;
import org.jvnet.hk2.annotations.Service;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Invoker-scoped bridge between a successful conversational save and the running workers.
 * Krista's injected Invoker can retain the pre-save attributes until the next load. Never cache
 * a candidate validation map: only a successfully persisted configuration may replace it here.
 * The bridge lasts only while the injected Invoker still exposes the pre-save attributes. A change
 * to those committed attributes (including a Studio off-switch) invalidates it without a reload.
 * Validation candidates never enter the bridge.
 * Configuration values (including secured fields) stay in memory and are never logged.
 */
@Service
@Singleton
public class LiveGoogleCalendarConfiguration {
    private final Invoker invoker;
    private Map<String, Object> savedAttributes;
    private Map<String, Object> invokerAttributesAtSave;

    @Inject
    public LiveGoogleCalendarConfiguration(Invoker invoker) {
        this.invoker = invoker;
    }

    public synchronized Map<String, Object> attributes() {
        // A removed/unreadable invoker must still fail closed, even with a prior saved snapshot.
        Map<String, Object> current = invoker.getAttributes();
        if (savedAttributes != null && !java.util.Objects.equals(current, invokerAttributesAtSave)) {
            clearSaved();
        }
        return savedAttributes != null ? savedAttributes : current;
    }

    public synchronized void applySaved(Map<String, Object> attributes) {
        // The catalog applies a save before rebind and again before scheduling watches. Do not
        // rebase an identical save onto a newer Studio snapshot during that second application.
        if (attributes.equals(savedAttributes)) {
            return;
        }
        Map<String, Object> current = invoker.getAttributes();
        invokerAttributesAtSave = current == null ? null
                : Collections.unmodifiableMap(new HashMap<>(current));
        savedAttributes = Collections.unmodifiableMap(new HashMap<>(attributes));
    }

    public synchronized void clearSaved() {
        savedAttributes = null;
        invokerAttributesAtSave = null;
    }
}
