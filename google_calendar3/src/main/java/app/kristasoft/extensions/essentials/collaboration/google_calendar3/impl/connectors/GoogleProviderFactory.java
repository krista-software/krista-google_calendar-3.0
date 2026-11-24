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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors;

import app.krista.ksdk.context.AuthorizationContext;
import app.krista.ksdk.context.RequestContext;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.GoogleAttributes;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.GoogleAttributeStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

@Service
public class GoogleProviderFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleProviderFactory.class);
    private final RefreshTokenStore refreshTokenStore;
    private final GoogleAttributeStore googleAttributeStore;
    private final RequestContext requestContext;
    private final AuthorizationContext authorizationContext;

    /**
     * Optional — wired via {@link #setConnectionStatusStore(CalendarConnectionStatusStore)}
     * after construction (HK2 doesn't have it ready at factory ctor time). Each
     * {@link GoogleProvider} instance the factory builds receives a reference so
     * the token-refresh path can record REAUTH_REQUIRED on invalid_grant (spec §F.2).
     */
    private CalendarConnectionStatusStore connectionStatusStore;

    @Inject
    public GoogleProviderFactory(RefreshTokenStore refreshTokenStore, GoogleAttributeStore googleAttributeStore, RequestContext requestContext, AuthorizationContext authorizationContext) {
        this.refreshTokenStore = refreshTokenStore;
        this.googleAttributeStore = googleAttributeStore;
        this.requestContext = requestContext;
        this.authorizationContext = authorizationContext;
    }

    /** Set after construction in {@code GoogleCalendarExtension}. */
    public void setConnectionStatusStore(CalendarConnectionStatusStore connectionStatusStore) {
        this.connectionStatusStore = connectionStatusStore;
    }

    public GoogleProvider create(GoogleAttributes gmailAttributes) {
        String authContextId = googleAttributeStore.save(gmailAttributes);
        return getGoogleClientProvider(gmailAttributes, authContextId);
    }

    private GoogleProvider getGoogleClientProvider(GoogleAttributes gmailAttributes, String authContextId) {
        GoogleProvider provider = new GoogleProvider(refreshTokenStore, gmailAttributes, requestContext, authorizationContext, authContextId);
        if (connectionStatusStore != null) {
            provider.setConnectionStatusStore(connectionStatusStore);
        }
        return provider;
    }

    public String createAttributes(GoogleAttributes gmailAttributes) {
        return googleAttributeStore.save(gmailAttributes);
    }

    public GoogleProvider create(String authContextId) {
        final GoogleAttributes attributes = googleAttributeStore.load(authContextId);
        return getGoogleClientProvider(attributes, authContextId);
    }

    public GoogleProvider create() {
        String invokerId = requestContext.getInvokerId();
        final GoogleAttributes attributes = googleAttributeStore.load(invokerId);
        return getGoogleClientProvider(attributes, invokerId);
    }

    /**
     * Loads the provider for an explicit {@code invokerId} — the
     * {@link RequestContext}-free counterpart to {@link #create()}.
     *
     * <p>The CA-initiated reconnect OAuth callback is forwarded to this invoker on a
     * thread where {@code requestContext.getInvokerId()} is unset (it is request-scoped;
     * see {@code Hk2ContextHolder}). The caller passes the id captured from the injected
     * (extension-scoped) {@code Invoker} instead, which is reliable on that thread.
     */
    public GoogleProvider createForInvoker(String invokerId) {
        final GoogleAttributes attributes = googleAttributeStore.load(invokerId);
        // Diagnostic: a null here is what produced the reconnect getOAuth20Service() NPE when the
        // forwarded callback resolved the wrong/empty invokerId. Make it explicit in prod logs.
        LOGGER.info("createForInvoker: invokerId={} attributesNull={}", invokerId, attributes == null);
        return getGoogleClientProvider(attributes, invokerId);
    }
}
