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

import app.krista.extension.executor.Invoker;
import app.krista.extension.request.RoutingInfo;
import app.krista.extension.request.protos.http.HttpProtocol;
import app.krista.extensions.util.KeyValueStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.GoogleAttributes;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Map;
import java.util.UUID;

import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.GoogleAttributes.*;
import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants.AUTH_TYPE;
import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants.GSON;

@Service
public class GoogleAttributeStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleAttributeStore.class);
    private final Invoker invoker;
    private final KeyValueStore store;

    @Inject
    public GoogleAttributeStore(Invoker invoker, KeyValueStore store) {
        this.invoker = invoker;
        this.store = store;
    }

    @SuppressWarnings("unchecked")
    public GoogleAttributes load(String authContextId) {
        String loadedAttributes = null;
        if (store.get(authContextId) != null) {
            loadedAttributes = (String) store.get(authContextId);
        }
        if (loadedAttributes == null || loadedAttributes.isBlank()) {
            return null;
        } else {
            Map<String, Object> attributes = GSON.fromJson(loadedAttributes, Map.class);
            String authType = attributes.get(AUTH_TYPE).toString();
            String baseUrl = invoker.getRoutingInfo().getRoutingURL(HttpProtocol.PROTOCOL_NAME, RoutingInfo.Type.APPLIANCE);

            if (Constants.PUBLIC.equals(authType)) {
                return new GoogleAttributes((String) attributes.get(CLIENT_ID), (String) attributes.get(CLIENT_SECRET),
                        (String) attributes.get(EMAIL), authType, baseUrl);
            } else {
                return new GoogleAttributes((String) attributes.get(CLIENT_ID), (String) attributes.get(CLIENT_SECRET),
                        (String) attributes.get(EMAIL), authType, baseUrl);
            }
        }
    }

    public String save(GoogleAttributes gmailAttributes) {
        String authContextId = Constants.AUTH_CONTEXT_ID + Constants.UNDER_SCORE + UUID.randomUUID();
        store.put(authContextId, GSON.toJson(gmailAttributes.toMap()));
        return authContextId;
    }

    public boolean save(GoogleAttributes attributes, String invokerId) {
        if (attributes == null) {
            throw new IllegalArgumentException("Google Calendar Attributes cannot be null");
        }
        Map<String, Object> attributeMap = attributes.toMap();
        store.put(invokerId, GSON.toJson(attributeMap));
        return true;
    }

    /**
     * ¸
     * Removes given authContext id from KeyValueStore
     *
     * @param authContextId authContext id
     */
    public void remove(String authContextId) {
        store.remove(authContextId);
    }

}
