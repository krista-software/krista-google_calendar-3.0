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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook;

import app.krista.extensions.util.KeyValueStore;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Store for mapping Google Calendar eventId to MA service meetingKey.
 * Used to handle event deletion when the event is no longer fetchable from Google Calendar.
 * Mirrors the CalendarEventMappingStore in teams-transcribe.
 */
@Service
public class CalendarEventMappingStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarEventMappingStore.class);

    private final KeyValueStore store;

    @Inject
    public CalendarEventMappingStore(KeyValueStore store) {
        this.store = store;
    }

    /**
     * Stores the mapping between eventId and meetingKey.
     *
     * @param eventId    the Google Calendar event ID
     * @param meetingKey the MA service meeting key
     */
    public void put(String eventId, String meetingKey) {
        if (eventId != null && meetingKey != null) {
            store.put(prepareKey(eventId), meetingKey);
        }
    }

    /**
     * Retrieves the meetingKey for a given eventId.
     *
     * @param eventId the Google Calendar event ID
     * @return the meetingKey, or null if not found
     */
    public String get(String eventId) {
        if (eventId == null) return null;
        try {
            Object raw = store.get(prepareKey(eventId));
            return raw != null ? raw.toString() : null;
        } catch (Exception e) {
            LOGGER.warn("Failed to get mapping for eventId {}: {}", eventId, e.getMessage());
            return null;
        }
    }

    /**
     * Removes the mapping for a given eventId.
     *
     * @param eventId the Google Calendar event ID
     */
    public void remove(String eventId) {
        if (eventId != null) {
            try {
                store.remove(prepareKey(eventId));
            } catch (Exception e) {
                LOGGER.warn("Failed to remove mapping for eventId {}: {}", eventId, e.getMessage());
            }
        }
    }

    /**
     * Stores the list of instance IDs for a recurring series.
     *
     * @param seriesMasterId the series master event ID (recurringEventId)
     * @param instanceIds    the list of instance event IDs
     */
    public void putSeriesInstances(String seriesMasterId, List<String> instanceIds) {
        if (seriesMasterId != null && instanceIds != null) {
            String joinedIds = String.join(",", instanceIds);
            store.put(prepareSeriesKey(seriesMasterId), joinedIds);
        }
    }

    /**
     * Retrieves the list of instance IDs for a recurring series.
     *
     * @param seriesMasterId the series master event ID
     * @return the list of instance IDs, or empty list if not found
     */
    public List<String> getSeriesInstances(String seriesMasterId) {
        if (seriesMasterId == null) return new ArrayList<>();
        try {
            Object raw = store.get(prepareSeriesKey(seriesMasterId));
            if (raw == null) return new ArrayList<>();
            String joinedIds = raw.toString();
            if (joinedIds.isBlank()) return new ArrayList<>();
            return new ArrayList<>(Arrays.asList(joinedIds.split(",")));
        } catch (Exception e) {
            LOGGER.warn("Failed to get series instances for {}: {}", seriesMasterId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private String prepareKey(String key) {
        return "gcal_event_map:" + key;
    }

    private String prepareSeriesKey(String key) {
        return "gcal_series:" + key;
    }
}
