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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.GoogleAttributeStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GoogleProviderFactory#createForInvoker(String)} — the
 * {@link RequestContext}-free way to build a provider for the CA-initiated
 * reconnect OAuth callback, where {@code requestContext.getInvokerId()} is
 * unset on the forwarded callback thread.
 */
@ExtendWith(MockitoExtension.class)
class GoogleProviderFactoryTest {

    @Mock private RefreshTokenStore refreshTokenStore;
    @Mock private GoogleAttributeStore googleAttributeStore;
    @Mock private RequestContext requestContext;
    @Mock private AuthorizationContext authorizationContext;

    private GoogleProviderFactory factory;

    @BeforeEach
    void setUp() {
        factory = new GoogleProviderFactory(refreshTokenStore, googleAttributeStore, requestContext, authorizationContext);
    }

    @Test
    @DisplayName("createForInvoker loads attributes by the explicit invokerId, not via RequestContext")
    void createForInvokerLoadsByExplicitInvokerId() {
        GoogleAttributes attributes = mock(GoogleAttributes.class);
        when(googleAttributeStore.load("INV-1")).thenReturn(attributes);

        GoogleProvider provider = factory.createForInvoker("INV-1");

        assertNotNull(provider);
        // Provider is built from the invoker-loaded attributes...
        assertSame(attributes, provider.getGoogleAttributes());
        verify(googleAttributeStore).load("INV-1");
        // ...without ever touching the request-scoped invokerId (null on the forwarded callback).
        verify(requestContext, never()).getInvokerId();
    }
}
