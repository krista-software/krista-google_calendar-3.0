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
import org.jvnet.hk2.annotations.Service;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a user's primary-calendar email to the refresh-token-store key the
 * sync/refresh path reads (for platform/client users that key is
 * {@code wsContact_<contactUUID>}, which carries no email).
 *
 * <p>The mapping is written while the token is healthy — the sync and OAuth
 * callback paths already resolve {@code wsContact_<UUID> → primary-calendar
 * email} — so the CA-initiated reconnect (which runs on an ESB thread with a
 * broken token and only the email to go on) can recover the correct key instead
 * of falling back to the bare email and parking the refreshed token under a key
 * nothing reads.
 *
 * <p>Key format: {@code gcal_user_key_index:{invokerId}:{emailLower}}.
 *
 * @since 3.0.4
 */
@Service
@Singleton
public class UserKeyIndexStore {

    private final KeyValueStore keyValueStore;

    /**
     * Mappings already persisted this process lifetime ({@code key=tokenKey}), so the
     * recurring sync path (which calls {@link #put} for every user on every cycle) does
     * not re-write an unchanged mapping. A genuine re-key (new tokenKey) has a different
     * entry and still writes through; a process restart re-writes once per user.
     */
    private final Set<String> writtenThisProcess = ConcurrentHashMap.newKeySet();

    @Inject
    public UserKeyIndexStore(KeyValueStore keyValueStore) {
        this.keyValueStore = keyValueStore;
    }

    /**
     * Records the token-store key for a user's primary-calendar email. No-op when
     * {@code email} or {@code tokenKey} is null/blank.
     *
     * @param invokerId scopes the index key (an extension instance may share the KV store)
     * @param email     the user's primary-calendar email (normalized lower-case)
     * @param tokenKey  the refresh-token-store key (e.g. {@code wsContact_<UUID>})
     */
    public void put(String invokerId, String email, String tokenKey) {
        if (email == null || email.isBlank() || tokenKey == null || tokenKey.isBlank()) {
            return;
        }
        String emailLower = email.trim().toLowerCase(Locale.ROOT);
        String storeKey = key(invokerId, emailLower);
        if (!writtenThisProcess.add(storeKey + '=' + tokenKey)) {
            return; // same mapping already persisted this process — skip the redundant writes
        }
        keyValueStore.put(storeKey, tokenKey);                              // email -> tokenKey
        keyValueStore.put(reverseKey(invokerId, tokenKey), emailLower);     // tokenKey -> email
    }

    /**
     * Returns the token-store key recorded for {@code email}, or {@code null} if
     * none has been recorded (or {@code email} is blank).
     */
    public String get(String invokerId, String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        Object raw = keyValueStore.get(key(invokerId, email.trim().toLowerCase(Locale.ROOT)));
        return raw == null ? null : raw.toString();
    }

    /**
     * Reverse lookup: returns the primary-calendar email recorded for {@code tokenKey}
     * (e.g. {@code wsContact_<UUID>}), or {@code null} if none has been recorded (or
     * {@code tokenKey} is blank).
     *
     * <p>Used by the token-failure path: a {@code wsContact_<UUID>} key carries no email,
     * and the broken token can't be used to look one up live, so the CA-bound
     * REAUTH_REQUIRED publish recovers the email from this mapping (written while the
     * token was healthy).
     */
    public String getEmailByKey(String invokerId, String tokenKey) {
        if (tokenKey == null || tokenKey.isBlank()) {
            return null;
        }
        Object raw = keyValueStore.get(reverseKey(invokerId, tokenKey));
        return raw == null ? null : raw.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static String key(String invokerId, String emailLower) {
        return "gcal_user_key_index:" + invokerId + ":" + emailLower;
    }

    private static String reverseKey(String invokerId, String tokenKey) {
        return "gcal_email_by_key:" + invokerId + ":" + tokenKey;
    }
}
