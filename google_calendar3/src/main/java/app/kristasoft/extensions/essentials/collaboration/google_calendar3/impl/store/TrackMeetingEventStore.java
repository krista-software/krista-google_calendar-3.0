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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store;

import app.krista.extensions.util.KeyValueStore;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.*;

@Service
public class TrackMeetingEventStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrackMeetingEventStore.class);

    private final KeyValueStore keyValueStore;
    private final Gson gson = new Gson();
    private final Type listType = new TypeToken<List<String>>() {}.getType();

    @Inject
    public TrackMeetingEventStore(KeyValueStore keyValueStore) {
        this.keyValueStore = keyValueStore;
    }

    public void trackEvent(String userId, String eventId, String meetingKey, String startTime, String iCalUId) {
        if (userId == null || eventId == null) return;
        try {
            List<String> index = readIndex(userId);
            if (!index.contains(eventId)) {
                index.add(eventId);
                writeIndex(userId, index);
            }

            JsonObject meta = new JsonObject();
            if (meetingKey != null) meta.addProperty("meetingKey", meetingKey);
            if (startTime != null) meta.addProperty("start", startTime);
            if (iCalUId != null) meta.addProperty("iCalUId", iCalUId);
            meta.addProperty("trackedAt", Instant.now().toString());

            keyValueStore.put(eventKey(userId, eventId), gson.toJson(meta));
            LOGGER.debug("Tracked event {} for user {} (meetingKey={}, iCalUId={})", eventId, userId, meetingKey, iCalUId);
        } catch (Exception e) {
            LOGGER.warn("Failed to track event {} for user {}: {}", eventId, userId, e.getMessage());
        }
    }

    public void removeTrackedEvent(String userId, String eventId) {
        if (userId == null || eventId == null) return;
        try {
            List<String> index = readIndex(userId);
            if (index.remove(eventId)) {
                writeIndex(userId, index);
            }
            safeDeleteKey(eventKey(userId, eventId));
            LOGGER.debug("Removed tracked event {} for user {}", eventId, userId);
        } catch (Exception e) {
            LOGGER.warn("Failed to remove tracked event {} for user {}: {}", eventId, userId, e.getMessage());
        }
    }

    public Set<String> getTrackedEventIds(String userId) {
        if (userId == null) return Collections.emptySet();
        try {
            List<String> index = readIndex(userId);
            return new HashSet<>(index);
        } catch (Exception e) {
            LOGGER.warn("Failed to get tracked event IDs for {}: {}", userId, e.getMessage());
            return Collections.emptySet();
        }
    }

    public Map<String, JsonObject> getTrackedEventsMeta(String userId) {
        Map<String, JsonObject> out = new HashMap<>();
        if (userId == null) return out;
        try {
            List<String> index = readIndex(userId);
            for (String eventId : index) {
                try {
                    Object raw = keyValueStore.get(eventKey(userId, eventId));
                    if (raw == null) {
                        out.put(eventId, new JsonObject());
                        continue;
                    }
                    String json = raw.toString();
                    if (json.isBlank()) {
                        out.put(eventId, new JsonObject());
                        continue;
                    }
                    JsonObject obj = gson.fromJson(json, JsonObject.class);
                    if (obj == null || (obj.has("deleted") && obj.get("deleted").getAsBoolean())) {
                        out.put(eventId, new JsonObject());
                        continue;
                    }
                    out.put(eventId, obj);
                } catch (Exception e) {
                    LOGGER.warn("Failed to read tracked event meta for {}/{}: {}", userId, eventId, e.getMessage());
                    out.put(eventId, new JsonObject());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to build tracked events meta for {}: {}", userId, e.getMessage());
        }
        return out;
    }

    public String getMeetingKey(String userId, String eventId) {
        JsonObject meta = getMetaObject(userId, eventId);
        if (meta == null || !meta.has("meetingKey")) return null;
        String mk = meta.get("meetingKey").getAsString();
        return mk == null || mk.isBlank() ? null : mk;
    }

    /** Remember the parent explicitly: a series tombstone need not carry occurrence IDs. */
    public void trackSeries(String userId, String eventId, String recurringSeriesId) {
        if (recurringSeriesId == null || recurringSeriesId.isBlank()) return;
        try {
            JsonObject meta = getMetaObject(userId, eventId);
            if (meta == null) return;
            meta.addProperty("recurringSeriesId", recurringSeriesId);
            keyValueStore.put(eventKey(userId, eventId), gson.toJson(meta));
        } catch (Exception e) {
            // Tracking metadata is best effort. A cache/KV hiccup must not abort the remaining
            // accepted outcomes in the current batch; the next sync can rebuild this field.
            LOGGER.warn("Failed to track recurring series {} for {}/{}: {}",
                    recurringSeriesId, userId, eventId, e.getMessage());
        }
    }

    public String getStartTime(String userId, String eventId) {
        JsonObject meta = getMetaObject(userId, eventId);
        if (meta == null || !meta.has("start")) return null;
        String v = meta.get("start").getAsString();
        return v == null || v.isBlank() ? null : v;
    }

    public String getLastFullSyncDate(String userId) {
        if (userId == null) return null;
        try {
            Object val = keyValueStore.get("gcal_last_full_sync:" + userId);
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            LOGGER.warn("Failed to get last full sync date for {}: {}", userId, e.getMessage());
            return null;
        }
    }

    public void setLastFullSyncDate(String userId, String date) {
        if (userId == null || date == null) return;
        try {
            keyValueStore.put("gcal_last_full_sync:" + userId, date);
        } catch (Exception e) {
            LOGGER.warn("Failed to set last full sync date for {}: {}", userId, e.getMessage());
        }
    }

    public void clearAllForUser(String userId) {
        if (userId == null) return;
        try {
            List<String> index = readIndex(userId);
            for (String eventId : index) {
                try {
                    safeDeleteKey(eventKey(userId, eventId));
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
            safeDeleteKey(indexKey(userId));
            safeDeleteKey("gcal_last_full_sync:" + userId);
        } catch (Exception e) {
            LOGGER.warn("Failed to clear tracked events for {}: {}", userId, e.getMessage());
        }
    }

    /* ---------- internal helpers ---------- */

    private JsonObject getMetaObject(String userId, String eventId) {
        if (userId == null || eventId == null) return null;
        try {
            Object raw = keyValueStore.get(eventKey(userId, eventId));
            if (raw == null) return null;
            String json = raw.toString();
            if (json.isBlank()) return null;
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            if (obj == null || (obj.has("deleted") && obj.get("deleted").getAsBoolean())) return null;
            return obj;
        } catch (Exception e) {
            LOGGER.warn("Failed to read meta object for {}/{}: {}", userId, eventId, e.getMessage());
            return null;
        }
    }

    private List<String> readIndex(String userId) {
        try {
            Object raw = keyValueStore.get(indexKey(userId));
            if (raw == null) return new ArrayList<>();

            // KeyValueStore may return a deserialized List directly instead of a JSON string
            if (raw instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) raw;
                return new ArrayList<>(list);
            }

            String json = raw.toString();
            if (json.isBlank()) return new ArrayList<>();
            List<String> list = gson.fromJson(json, listType);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Exception e) {
            LOGGER.warn("Failed to read index for {}: {}", userId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeIndex(String userId, List<String> index) {
        try {
            if (index == null) index = Collections.emptyList();
            keyValueStore.put(indexKey(userId), gson.toJson(index));
        } catch (Exception e) {
            LOGGER.warn("Failed to write index for {}: {}", userId, e.getMessage());
        }
    }

    private String indexKey(String userId) {
        return "gcal_tracked_index:" + userId;
    }

    private String eventKey(String userId, String eventId) {
        return "gcal_tracked:" + userId + ":" + eventId;
    }

    private void safeDeleteKey(String key) {
        if (key == null) return;
        try {
            keyValueStore.remove(key);
        } catch (Exception e) {
            LOGGER.warn("safeDeleteKey: failed for key {}: {}", key, e.getMessage());
        }
    }
}
