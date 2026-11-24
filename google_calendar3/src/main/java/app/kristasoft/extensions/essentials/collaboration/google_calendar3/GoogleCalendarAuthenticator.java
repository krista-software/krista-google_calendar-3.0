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

import app.krista.extension.authorization.MustAuthenticateException;
import app.krista.extension.authorization.MustAuthorizeException;
import app.krista.extension.authorization.RequestAuthenticator;
import app.krista.extension.request.ProtoRequest;
import app.krista.extension.request.ProtoResponse;
import app.krista.extension.request.protos.http.HttpRequest;
import app.krista.ksdk.accounts.Account;
import app.krista.ksdk.accounts.AccountProvider;
import app.krista.ksdk.context.AuthorizationContext;
import app.krista.model.field.NamedField;
import app.krista.model.field.NamedValuedField;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.GoogleAttributeStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.DwdSetupStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Validator;
import com.github.scribejava.core.oauth.OAuth20Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import java.util.*;

import static com.github.scribejava.core.model.OAuthConstants.STATE;

public class GoogleCalendarAuthenticator implements RequestAuthenticator {
    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleCalendarAuthenticator.class);
    public static final String EXTENSION_OAUTH_VERIFICATION_PATH_V3 = "/googlecalendar/v3/oauth/callback";
    public static final String EXTENSION_OAUTH_VERIFICATION_PATH_V2 = "/googlecalendar/callback";
    public static final String PLATFORM_OAUTH_VERIFICATION_PATH_V3 = "/v3/oauth/callback";
    private final GoogleAttributeStore googleAttributeStore;
    private final String invokerId;
    private final AccountProvider accountProvider;
    private final AuthorizationContext authorizationContext;
    private final DwdSetupStore dwdSetupStore;
    private final String routingUrl;

    public GoogleCalendarAuthenticator(GoogleAttributeStore googleAttributeStore, String invokerId) {
        this(googleAttributeStore, invokerId, null, null);
    }

    public GoogleCalendarAuthenticator(GoogleAttributeStore googleAttributeStore,
                                       String invokerId,
                                       AccountProvider accountProvider) {
        this(googleAttributeStore, invokerId, accountProvider, null);
    }

    public GoogleCalendarAuthenticator(GoogleAttributeStore googleAttributeStore,
                                       String invokerId,
                                       AccountProvider accountProvider,
                                       AuthorizationContext authorizationContext) {
        this(googleAttributeStore, invokerId, accountProvider, authorizationContext, null, null);
    }

    public GoogleCalendarAuthenticator(GoogleAttributeStore googleAttributeStore,
                                       String invokerId,
                                       AccountProvider accountProvider,
                                       AuthorizationContext authorizationContext,
                                       DwdSetupStore dwdSetupStore,
                                       String routingUrl) {
        this.googleAttributeStore = googleAttributeStore;
        this.invokerId = invokerId;
        this.accountProvider = accountProvider;
        this.authorizationContext = authorizationContext;
        this.dwdSetupStore = dwdSetupStore;
        this.routingUrl = routingUrl;
    }

    @Override
    public String getScheme() {
        return null;
    }

    @Override
    public Set<String> getSupportedProtocols() {
        return Set.of();
    }

    @Override
    public String getAuthenticatedAccountId(ProtoRequest protoRequest) {

        try {
            HttpRequest httpRequest = (HttpRequest) protoRequest;
            if (isDwdSetupPath(httpRequest.getUri().getPath()) && dwdSetupStore != null) {
                MultivaluedMap<String, String> query = httpRequest.getQueryParameters();
                if (query == null || query.getFirst("token") == null) {
                    return null;
                }
                DwdSetupStore.PendingSetup pending = dwdSetupStore.find(invokerId, query.getFirst("token"));
                return pending == null ? null : pending.accountId();
            }
            if (isOAuthVerificationPath(httpRequest.getUri().getPath())) {
                httpRequest.bufferBody();
                MultivaluedMap<String, String> queryParameters = httpRequest.getQueryParameters();
                String state = String.valueOf(queryParameters.get(STATE).getFirst());
                String[] parts = state.split(Constants.HASH);
                String authenticatedAccountId = getAuthenticatedAccountIdFromState(parts);
                if (authenticatedAccountId != null) {
                    return authenticatedAccountId;
                }
                return resolveAuthenticatedAccountId(parts[0], getGoogleAttributesFromState(parts));
            } else {
                return null;
            }
        } catch (RuntimeException | IOException cause) {
            LOGGER.error("Failed to get the Authenticated Account Id", cause);
            return null;
        }
    }

    private String getAuthenticatedAccountIdFromState(String[] parts) {
        for (String part : parts) {
            int markerIndex = part.indexOf(Constants.AUTHENTICATED_ACCOUNT_ID_STATE_MARKER);
            if (markerIndex != -1) {
                String accountId = part.substring(markerIndex + Constants.AUTHENTICATED_ACCOUNT_ID_STATE_MARKER.length());
                return accountId.isBlank() ? null : accountId;
            }
        }
        return null;
    }

    private GoogleAttributes getGoogleAttributesFromState(String[] parts) {
        if (parts.length < 2 || parts[1].isBlank()) {
            return null;
        }
        String authContextId = stripStateMetadata(parts[1]);
        if (!authContextId.startsWith("authContextId_") && !authContextId.startsWith("AUTH_CONTEXT_ID_")) {
            return null;
        }
        return googleAttributeStore.load(authContextId);
    }

    private String stripStateMetadata(String value) {
        int markerIndex = value.indexOf(Constants.AUTHENTICATED_ACCOUNT_ID_STATE_MARKER);
        return markerIndex == -1 ? value : value.substring(0, markerIndex);
    }

    private String resolveAuthenticatedAccountId(String userId, GoogleAttributes googleAttributes) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        String accountLookupId = extractAccountLookupId(userId, googleAttributes);
        if (accountProvider == null || !Validator.isValidEmail(accountLookupId)) {
            return userId;
        }

        Account account = accountProvider.lookupAccount(accountLookupId);
        if (account == null) {
            LOGGER.warn("OAuth callback email did not resolve to a Krista account: {}", accountLookupId);
            return null;
        }
        return account.getAccountId();
    }

    private String extractAccountLookupId(String userId, GoogleAttributes googleAttributes) {
        if (Validator.isValidEmail(userId)) {
            return userId;
        }
        String emailFromAttributes = extractEmailUsingAttributes(userId, googleAttributes);
        if (Validator.isValidEmail(emailFromAttributes)) {
            return emailFromAttributes;
        }
        return extractEmailFromTokenKey(userId);
    }

    private String extractEmailUsingAttributes(String userId, GoogleAttributes googleAttributes) {
        if (googleAttributes == null
                || googleAttributes.getClientId() == null
                || googleAttributes.getAuthType() == null) {
            return userId;
        }
        String suffix = Constants.UNDER_SCORE + googleAttributes.getClientId() + Constants.UNDER_SCORE + googleAttributes.getAuthType();
        if (userId.endsWith(suffix)) {
            return userId.substring(0, userId.length() - suffix.length());
        }
        return userId;
    }

    private String extractEmailFromTokenKey(String userId) {
        int clientIdMarker = userId.indexOf(".apps.googleusercontent.com" + Constants.UNDER_SCORE);
        if (clientIdMarker < 0) {
            return userId;
        }
        int clientIdSeparator = userId.lastIndexOf(Constants.UNDER_SCORE, clientIdMarker);
        if (clientIdSeparator <= 0) {
            return userId;
        }
        return userId.substring(0, clientIdSeparator);
    }

    private boolean isOAuthVerificationPath(String path) {
        return EXTENSION_OAUTH_VERIFICATION_PATH_V3.equals(path)
                || EXTENSION_OAUTH_VERIFICATION_PATH_V2.equals(path)
                || PLATFORM_OAUTH_VERIFICATION_PATH_V3.equals(path)
                || (path != null && (path.endsWith("/rest" + EXTENSION_OAUTH_VERIFICATION_PATH_V3)
                || path.endsWith("/rest" + EXTENSION_OAUTH_VERIFICATION_PATH_V2)
                || path.endsWith("/rest" + PLATFORM_OAUTH_VERIFICATION_PATH_V3)));
    }

    private static boolean isDwdSetupPath(String path) {
        return path != null && (path.endsWith("/googlecalendar/dwd/setup")
                || path.endsWith("/googlecalendar/dwd/continue"));
    }

    @Override
    public boolean setServiceAuthorization(String s) {
        return false;
    }

    @Override
    public Map<String, NamedField> getAttributeFields() {
        return Map.of();
    }

    @Override
    public ProtoResponse getMustAuthenticateResponse(MustAuthenticateException cause, ProtoRequest request) {
        return null;
    }

    @Override
    public AuthorizationResponse getMustAuthenticateResponse(MustAuthenticateException cause) {
        return null;
    }

    @Override
    public ProtoResponse getMustAuthorizeResponse(MustAuthorizeException cause, ProtoRequest request) {
        return null;
    }

    @Override
    public AuthorizationResponse getMustAuthorizeResponse(MustAuthorizeException cause) {
            if (isDwdSetupRequest(cause)) {
                if (dwdSetupStore == null || routingUrl == null || routingUrl.isBlank()) {
                    // Compatibility for older callers that do not wire the setup page.
                    return new AuthorizationResponse(Constants.GOOGLE_DWD_ADMIN_URL, List.of());
                }
                String clientId = cause.getDetails().stream()
                        .filter(field -> Objects.equals(field.getName(), Constants.DWD_CLIENT_ID_FIELD))
                        .map(field -> String.valueOf(field.getValue()))
                        .filter(value -> value.matches("[0-9]{10,30}"))
                        .findFirst().orElse(null);
                String accountId = getCurrentAuthenticatedAccountId();
                if (accountId == null && !cause.getDetails().isEmpty()) {
                    accountId = resolveAuthenticatedAccountId(
                            String.valueOf(cause.getDetails().getFirst().getValue()), null);
                }
                String token = dwdSetupStore.create(invokerId, clientId, accountId);
                String setupUrl = routingUrl.replaceAll("/+$", "")
                        + "/rest/googlecalendar/dwd/setup?token=" + token;
                LOGGER.info("Issuing Google Workspace DWD setup and continuation page");
                return new AuthorizationResponse(setupUrl, List.of());
            }
            String userId = (String) cause.getDetails().getFirst().getValue();
            Optional<NamedValuedField> authContextIdField = cause.getDetails().stream().filter(f -> Objects.equals(f.getName(), Constants.AUTH_CONTEXT_ID)).findFirst();

            GoogleAttributes effectiveGoogleAttributes;
            String state;
            if (authContextIdField.isPresent()) {
                String authContextId = (String) authContextIdField.get().getValue();
                effectiveGoogleAttributes = googleAttributeStore.load(authContextId);
                state = userId + Constants.HASH + authContextId;
            } else {
                effectiveGoogleAttributes = googleAttributeStore.load(invokerId);
                state = userId;
            }

            // The redirect_uri is environment-level (GoogleAttributes#getCallbackEndPoint),
            // so carry the per-instance forward path in the state to let the platform
            // route the callback back to this instance — matching the testConnection flow.
            state = appendAuthenticatedAccountId(state);
            state = effectiveGoogleAttributes.appendForwardPath(state);

            OAuth20Service oAuth20Service = effectiveGoogleAttributes.getOAuth20Service();
            String url = oAuth20Service.getAuthorizationUrl(state);
            String urlQueryParam = Constants.AUTH_URL_QUERY_PARAMS;
            return new AuthorizationResponse(url + urlQueryParam, List.of());
    }

    private static boolean isDwdSetupRequest(MustAuthorizeException cause) {
        if (cause == null || cause.getDetails() == null) {
            return false;
        }
        return cause.getDetails().stream()
                .anyMatch(field -> Objects.equals(field.getName(), Constants.DWD_SETUP_FIELD)
                        && Boolean.parseBoolean(String.valueOf(field.getValue())));
    }

    private String appendAuthenticatedAccountId(String state) {
        String accountId = getCurrentAuthenticatedAccountId();
        if (accountId == null || accountId.isBlank()) {
            return state;
        }

        int firstHashIndex = state.indexOf(Constants.HASH);
        if (firstHashIndex == -1) {
            return state;
        }

        int secondHashIndex = state.indexOf(Constants.HASH, firstHashIndex + 1);
        if (secondHashIndex == -1) {
            return state + Constants.AUTHENTICATED_ACCOUNT_ID_STATE_MARKER + accountId;
        }
        return state.substring(0, secondHashIndex)
                + Constants.AUTHENTICATED_ACCOUNT_ID_STATE_MARKER
                + accountId
                + state.substring(secondHashIndex);
    }

    private String getCurrentAuthenticatedAccountId() {
        try {
            if (authorizationContext == null || authorizationContext.getAuthorizedAccount() == null) {
                return null;
            }
            return authorizationContext.getAuthorizedAccount().getAccountId();
        } catch (RuntimeException cause) {
            LOGGER.debug("Unable to add current Krista account id to OAuth state: {}", cause.getMessage());
            return null;
        }
    }
}
