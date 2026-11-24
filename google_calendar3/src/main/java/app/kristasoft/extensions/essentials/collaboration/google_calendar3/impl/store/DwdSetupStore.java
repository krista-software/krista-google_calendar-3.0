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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import org.jvnet.hk2.annotations.Service;

import javax.inject.Inject;
import java.time.Duration;
import java.util.UUID;

/**
 * Short-lived, one-use state for the manual Google Workspace DWD setup page. The service-account
 * key stays in the original catalog request; only its public numeric client ID is stored here.
 */
@Service
public class DwdSetupStore {
    private static final String PREFIX = "gcal_dwd_setup:";
    private static final long MAX_AGE_MILLIS = Duration.ofHours(2).toMillis();

    private final KeyValueStore store;

    @Inject
    public DwdSetupStore(KeyValueStore store) {
        this.store = store;
    }

    public String create(String invokerId, String clientId, String accountId) {
        String token = UUID.randomUUID().toString();
        store.put(key(invokerId, token), Constants.GSON.toJson(
                new PendingSetup(clientId, accountId, System.currentTimeMillis() + MAX_AGE_MILLIS)));
        return token;
    }

    public PendingSetup find(String invokerId, String token) {
        if (!validToken(token)) {
            return null;
        }
        Object raw = store.get(key(invokerId, token));
        if (!(raw instanceof String value)) {
            return null;
        }
        try {
            PendingSetup pending = Constants.GSON.fromJson(value, PendingSetup.class);
            if (pending != null && pending.expiresAtMillis() > System.currentTimeMillis()) {
                return pending;
            }
        } catch (RuntimeException ignored) {
            // An unreadable or expired setup token is never authorization evidence.
        }
        return null;
    }

    public boolean consume(String invokerId, String token) {
        if (find(invokerId, token) == null) {
            return false;
        }
        store.remove(key(invokerId, token));
        return true;
    }

    private static boolean validToken(String token) {
        return token != null && token.matches("[0-9a-f-]{36}");
    }

    private static String key(String invokerId, String token) {
        return PREFIX + invokerId + ":" + token;
    }

    public record PendingSetup(String clientId, String accountId, long expiresAtMillis) { }
}
