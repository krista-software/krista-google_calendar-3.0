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
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Store for managing Google Calendar push notification channel data per user.
 * Tracks channelId, resourceId, syncToken, and expiration for each user's watch subscription.
 */
@Service
public class CalendarWatchStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarWatchStore.class);
    private static final String KEY_PREFIX = "gcal_watch:";
    private static final String CHANNEL_INDEX_PREFIX = "gcal_channel:";

    private final KeyValueStore keyValueStore;
    private final Gson gson = new Gson();

    @Inject
    public CalendarWatchStore(KeyValueStore keyValueStore) {
        this.keyValueStore = keyValueStore;
    }

    /**
     * Stores watch channel data for a user.
     *
     * @param userId     the user ID (email)
     * @param channelId  the Google Calendar channel ID
     * @param resourceId the Google Calendar resource ID
     * @param expiration the channel expiration timestamp in millis
     */
    public void put(String userId, String channelId, String resourceId, long expiration) {
        if (userId == null || channelId == null) return;
        try {
            JsonObject data = new JsonObject();
            data.addProperty("channelId", channelId);
            data.addProperty("resourceId", resourceId);
            data.addProperty("expiration", expiration);
            keyValueStore.put(watchKey(userId), gson.toJson(data));
            LOGGER.debug("Stored watch channel for user {}: channelId={}", userId, channelId);
        } catch (Exception e) {
            LOGGER.warn("Failed to store watch channel for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Retrieves the channel ID for a user.
     */
    public String getChannelId(String userId) {
        JsonObject data = getData(userId);
        return data != null && data.has("channelId") ? data.get("channelId").getAsString() : null;
    }

    /**
     * Retrieves the resource ID for a user.
     */
    public String getResourceId(String userId) {
        JsonObject data = getData(userId);
        return data != null && data.has("resourceId") ? data.get("resourceId").getAsString() : null;
    }

    /**
     * Retrieves the channel expiration for a user.
     */
    public long getExpiration(String userId) {
        JsonObject data = getData(userId);
        return data != null && data.has("expiration") ? data.get("expiration").getAsLong() : 0L;
    }

    /**
     * Checks if a user has an active watch channel.
     */
    public boolean hasWatch(String userId) {
        return getChannelId(userId) != null;
    }

    /**
     * Removes the watch channel data for a user.
     */
    public void remove(String userId) {
        if (userId == null) return;
        try {
            keyValueStore.remove(watchKey(userId));
            LOGGER.debug("Removed watch channel for user {}", userId);
        } catch (Exception e) {
            LOGGER.warn("Failed to remove watch channel for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Stores the sync token for incremental sync after receiving a notification.
     */
    public void putSyncToken(String userId, String syncToken) {
        if (userId == null || syncToken == null) return;
        try {
            keyValueStore.put(syncTokenKey(userId), syncToken);
            LOGGER.debug("Stored sync token for user {}", userId);
        } catch (Exception e) {
            LOGGER.warn("Failed to store sync token for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Retrieves the sync token for a user.
     */
    public String getSyncToken(String userId) {
        if (userId == null) return null;
        try {
            Object raw = keyValueStore.get(syncTokenKey(userId));
            return raw != null ? raw.toString() : null;
        } catch (Exception e) {
            LOGGER.warn("Failed to get sync token for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * Removes the sync token for a user.
     */
    public void removeSyncToken(String userId) {
        if (userId == null) return;
        try {
            keyValueStore.remove(syncTokenKey(userId));
        } catch (Exception e) {
            LOGGER.warn("Failed to remove sync token for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Clears all watch-related data for a user.
     */
    public void clearAll(String userId) {
        remove(userId);
        removeSyncToken(userId);
    }

    /**
     * Stores a reverse mapping {@code channelId -> userId} so an incoming notification
     * can be routed to its owner in O(1), without scanning all users and without the
     * "only the latest channel per user is matchable" limitation of the forward map.
     */
    public void putChannelUser(String channelId, String userId) {
        if (channelId == null || userId == null) return;
        try {
            keyValueStore.put(channelKey(channelId), userId);
            LOGGER.debug("Indexed channel {} -> user {}", channelId, userId);
        } catch (Exception e) {
            LOGGER.warn("Failed to index channel {} for user {}: {}", channelId, userId, e.getMessage());
        }
    }

    /**
     * Resolves the userId that owns a given channelId via the reverse index, or {@code null}.
     */
    public String getUserByChannelId(String channelId) {
        if (channelId == null) return null;
        try {
            Object raw = keyValueStore.get(channelKey(channelId));
            return raw != null ? raw.toString() : null;
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve user for channel {}: {}", channelId, e.getMessage());
            return null;
        }
    }

    /**
     * Every user id this store currently holds a watch row for.
     *
     * <p>KIM-474 / EF-07: the out-of-scope reconcile in {@link CalendarWatchService} is
     * "everything we hold, minus everything in scope", and this is the "everything we hold" half.
     * Nothing else can supply it — every sweep in the extension iterates the users currently IN
     * scope, so a mailbox that LEAVES the scan set is never visited again by any of them.
     *
     * <p><b>Prefix-filtered on purpose.</b> {@link KeyValueStore#getKeys()} is a single flat
     * namespace shared by every store in this extension — refresh tokens, sync tokens, the
     * {@code gcal_channel:} reverse index, the seat roster, the user-key index. An unfiltered
     * enumerator (the shape of {@code RefreshTokenStore.getKey()}, EF-26) would hand the reconcile
     * keys that are not user ids at all, and the reconcile revokes what it does not recognise — so
     * it would tear down live channels on behalf of a sync token. Only keys carrying
     * {@value #KEY_PREFIX} are user ids of watch rows, and only the remainder after the prefix is
     * the user id.
     *
     * <p>An unreadable store yields an EMPTY set rather than throwing: the caller treats "nothing
     * held" as "nothing to reconcile" and revokes nothing, which is the safe direction. A store
     * failure must never be read as "every mailbox has left the scope".
     *
     * @return the user ids holding a watch row, in store iteration order; never null
     */
    public Set<String> subscribedUserIds() {
        Set<String> keys;
        try {
            keys = keyValueStore.getKeys();
        } catch (Exception e) {
            LOGGER.warn("Could not enumerate the held watch channels: {}", e.getMessage());
            return Set.of();
        }
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }

        Set<String> userIds = new LinkedHashSet<>();
        for (String key : keys) {
            if (key != null && key.startsWith(KEY_PREFIX) && key.length() > KEY_PREFIX.length()) {
                userIds.add(key.substring(KEY_PREFIX.length()));
            }
        }
        return userIds;
    }

    /**
     * Removes the reverse mapping for a channelId (called when a watch channel is stopped).
     */
    public void removeChannelUser(String channelId) {
        if (channelId == null) return;
        try {
            keyValueStore.remove(channelKey(channelId));
        } catch (Exception e) {
            LOGGER.warn("Failed to remove channel index {}: {}", channelId, e.getMessage());
        }
    }

    private JsonObject getData(String userId) {
        if (userId == null) return null;
        try {
            Object raw = keyValueStore.get(watchKey(userId));
            if (raw == null) return null;
            String json = raw.toString();
            if (json.isBlank()) return null;
            return gson.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            LOGGER.warn("Failed to read watch data for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    private String watchKey(String userId) {
        return KEY_PREFIX + userId;
    }

    private String syncTokenKey(String userId) {
        return "gcal_sync_token:" + userId;
    }

    private String channelKey(String channelId) {
        return CHANNEL_INDEX_PREFIX + channelId;
    }
}
