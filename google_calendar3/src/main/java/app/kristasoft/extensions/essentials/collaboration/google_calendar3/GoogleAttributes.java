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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3;


import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import com.github.scribejava.apis.GoogleApi20;
import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.api.services.calendar.CalendarScopes;
import com.google.gson.JsonObject;
import org.jvnet.hk2.annotations.Service;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants.*;

@Service
public class GoogleAttributes {
    public static final String CLIENT_ID = "clientId";
    public static final String CLIENT_SECRET = "clientSecret";
    public static final String EMAIL = "email";

    private final String routingUrl;
    private final String clientId;
    private final String clientSecret;
    private final String mailId;
    private final String authType;

    private String publicClientId;
    private String publicClientSecret;

    private OAuth20Service oAuth20Service;

    @Inject
    public GoogleAttributes(String clientId, String clientSecret, String mailId, String authType, String routingUrl) {
        loadPublicConfig();
        this.clientId = clientId == null ? publicClientId : clientId;
        this.clientSecret = clientSecret == null ? publicClientSecret : clientSecret;
        this.routingUrl = routingUrl.replace(LOCAL_EXTN_URL, LOCAL_EXTN_REPLACE_URL);
        this.mailId = mailId;
        this.authType = authType;
    }

    public static GoogleAttributes create(JsonObject authPayload, String baseurl) {
        String authType = authPayload.get("authType").getAsString();
        if (Constants.PUBLIC.equals(authType)) {
            return new GoogleAttributes(null, null, authPayload.get(EMAIL).getAsString(), authType, baseurl);
        } else if (Constants.PRIVATE.equals(authType)) {
            return new GoogleAttributes(authPayload.get(CLIENT_ID).getAsString(), authPayload.get(CLIENT_SECRET).getAsString(),
                    authPayload.get(EMAIL).getAsString(), authType, baseurl);
        } else {
            throw new IllegalArgumentException(UNSUPPORTED_AUTH);
        }
    }

    private void loadPublicConfig() {
        Properties properties = new Properties();
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("config.properties")) {
            properties.load(inputStream);
            this.publicClientId = properties.getProperty("public.clientId");
            this.publicClientSecret = properties.getProperty("public.clientSecret");
        } catch (IOException cause) {
            throw new RuntimeException("Failed to load public configuration file.", cause);
        }
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public String getMailId() {
        return mailId;
    }

    public String getAuthType() {
        return authType;
    }

    public String getCallbackUrl() {

        if (Constants.PUBLIC.equals(authType)) {
            return routingUrl.replaceAll("/extension/api/.*", DEFAULT_CALLBACK_PATH);
        } else if (Constants.PRIVATE.equals(authType)) {
            return routingUrl + "/rest/googlecalendar/callback";
        } else {
            return EMPTY_STRING;
        }
    }

    /**
     * Returns the OAuth {@code redirect_uri} registered with Google. This is
     * always the environment-level platform callback path, independent of auth
     * type: the per-instance routing id is collapsed out so a single redirect
     * URI is registered once per environment. The platform forwards the callback
     * to the owning extension instance using the {@code forwardPath} carried in
     * the OAuth {@code state} (see {@link #getForwardPath()}).
     *
     * <p>This mirrors the Microsoft Teams calendar extension's
     * {@code getCallbackEndPoint()} and removes the per-instance
     * {@code redirect_uri_mismatch} that occurred for {@code Private} auth.
     */
    public String getCallbackEndPoint() {
        return getCallbackUrl().replaceAll("/extension/api/.*", DEFAULT_CALLBACK_PATH);
    }

    public String getForwardPath() {
        return routingUrl + EXTENSION_FORWARD_PATH;
    }

    /**
     * Appends this instance's {@link #getForwardPath() forward path} to an OAuth
     * {@code state}, so the platform can route the environment-level callback (see
     * {@link #getCallbackEndPoint()}) back to this instance. No-op when no forward
     * path is set. Single source for the {@code …#forwardPath} suffix shared by the
     * setup, studio re-auth, and CA-initiated reconnect flows.
     */
    public String appendForwardPath(String state) {
        String forwardPath = getForwardPath();
        if (forwardPath != null && !forwardPath.isEmpty()) {
            return state + HASH + forwardPath;
        }
        return state;
    }

    public synchronized OAuth20Service getOAuth20Service() {
        if (oAuth20Service == null) {
            oAuth20Service = new ServiceBuilder(clientId)
                    .apiSecret(clientSecret)
                    .defaultScope(CalendarScopes.CALENDAR)
                    .callback(getCallbackEndPoint())
                    .build(GoogleApi20.instance());
        }
        return oAuth20Service;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> attributesMap = new HashMap<>();
        addAttributeIfNotNull(attributesMap, CLIENT_ID, getClientId());
        addAttributeIfNotNull(attributesMap, CLIENT_SECRET, getClientSecret());
        addAttributeIfNotNull(attributesMap, EMAIL, getMailId());
        addAttributeIfNotNull(attributesMap, AUTH_TYPE, getAuthType());
        return attributesMap;
    }

    public void addAttributeIfNotNull(Map<String, Object> attributeMap, String attributeName, Object attributeValue) {
        if (attributeValue != null) {
            attributeMap.put(attributeName, attributeValue);
        }
    }
}
