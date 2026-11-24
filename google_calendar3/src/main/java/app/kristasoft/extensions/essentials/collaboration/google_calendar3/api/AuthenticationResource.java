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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.api;

import app.krista.extension.authorization.MustAuthorizeException;
import app.krista.extension.executor.Invoker;
import app.krista.extension.request.RoutingInfo;
import app.krista.extension.request.protos.http.HttpProtocol;
import app.krista.ksdk.authentication.AuthorizationListener;
import app.krista.ksdk.context.AuthorizationContext;
import app.krista.model.field.NamedValuedField;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.GoogleAttributes;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.GoogleAttributeStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.DwdSetupStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.notification.NotificationService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarAuthenticationService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.AuthenticationResponse;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import com.github.scribejava.core.exceptions.OAuthException;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.api.client.util.DateTime;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants.*;
import static com.github.scribejava.core.model.OAuthConstants.CODE;
import static com.github.scribejava.core.model.OAuthConstants.STATE;

/**
 * REST resource for handling Google Calendar OAuth authentication and authorization.
 */
@Path("/")
public final class AuthenticationResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationResource.class);
    /** Embedded Krista wordmark used by the self-contained DWD setup page. */
    private static final String KRISTA_LOGO_DATA_URI = "data:image/png;base64,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";
    private final GoogleAttributeStore googleAttributeStore;
    private final RefreshTokenStore refreshTokenStore;
    private final GoogleProviderFactory providerFactory;
    private final AuthorizationListener authorizationListener;
    private final AuthorizationContext context;
    private final DwdSetupStore dwdSetupStore;
    private final String baseRoutingUrl;
    private final String invokerId;
    /** Real workspace id ({@code ws_…}) published in the CA event — distinct from {@link #invokerId}. */
    private final String workspaceId;

    /** Registers successful OAuth users for sync and publishes CONNECTED to CA. */
    private final GoogleCalendarAuthenticationService authenticationService;

    /** Email→token-key index, written on auth success so the CA-initiated reconnect can
     *  resolve this user's token-store key from just their email (see {@code GoogleOAuthUrlService}). */
    private final UserKeyIndexStore userKeyIndexStore;

    /** Reconnect-reminder sender. On a successful reconnect we reset the user's reminder
     *  rate-limit so a future breakage gets fresh reminders (KIP parity). */
    private final NotificationService notificationService;


    /**
     * Constructs an AuthenticationResource with required dependencies.
     *
     * @param googleAttributeStore store for Google authentication attributes
     * @param refreshTokenStore store for OAuth refresh tokens
     * @param providerFactory factory for creating Google provider instances
     * @param authorizationListener listener for authorization events
     * @param context authorization context for the current session
     * @param invoker invoker providing routing information
     * @param authenticationService sync-user registry and CA connection-status publisher
     */
    @Inject
    public AuthenticationResource(GoogleAttributeStore googleAttributeStore, RefreshTokenStore refreshTokenStore, GoogleProviderFactory providerFactory, AuthorizationListener authorizationListener, AuthorizationContext context, Invoker invoker, GoogleCalendarAuthenticationService authenticationService, UserKeyIndexStore userKeyIndexStore, NotificationService notificationService, DwdSetupStore dwdSetupStore) {
        this.googleAttributeStore = googleAttributeStore;
        this.refreshTokenStore = refreshTokenStore;
        this.providerFactory = providerFactory;
        this.authorizationListener = authorizationListener;
        this.context = context;
        this.dwdSetupStore = dwdSetupStore;
        this.baseRoutingUrl = invoker.getRoutingInfo().getRoutingURL(HttpProtocol.PROTOCOL_NAME, RoutingInfo.Type.APPLIANCE);
        this.invokerId = invoker.getInvokerId();
        this.workspaceId = invoker.getWorkspaceId();
        this.authenticationService = authenticationService;
        this.userKeyIndexStore = userKeyIndexStore;
        this.notificationService = notificationService;
    }

    /** Keeps older callers of this resource source-compatible. HK2 uses the injected constructor. */
    public AuthenticationResource(GoogleAttributeStore googleAttributeStore, RefreshTokenStore refreshTokenStore,
                                  GoogleProviderFactory providerFactory, AuthorizationListener authorizationListener,
                                  AuthorizationContext context, Invoker invoker,
                                  GoogleCalendarAuthenticationService authenticationService,
                                  UserKeyIndexStore userKeyIndexStore, NotificationService notificationService) {
        this(googleAttributeStore, refreshTokenStore, providerFactory, authorizationListener, context,
                invoker, authenticationService, userKeyIndexStore, notificationService, null);
    }

    /**
     * Manual DWD setup has no Google callback. This page keeps the Krista authorization context
     * open while the super admin adds our client ID and scopes in a separate Admin Console tab.
     */
    @GET
    @Path("dwd/setup")
    @Produces(MediaType.TEXT_HTML)
    public Response dwdSetup(@QueryParam("token") String token) {
        DwdSetupStore.PendingSetup pending = dwdSetupStore == null
                ? null : dwdSetupStore.find(invokerId, token);
        if (pending == null) {
            return dwdPage(Response.Status.NOT_FOUND,
                    "This setup link expired. Return to Krista and run Save Configuration again.");
        }

        String clientId = pending.clientId();
        String clientIdBlock = clientId == null
                ? "<p class=\"muted\">Copy the numeric OAuth 2 Client ID from Krista's service account.</p>"
                : "<div class=\"client-id\"><span class=\"client-id-label\">OAuth 2 Client ID</span>"
                + "<code>" + clientId + "</code><button type=\"button\" class=\"copy-button\" data-copy=\""
                + clientId + "\">Copy</button></div>";
        String body = "<main class=\"shell\">"
                + "<header class=\"brand\"><div class=\"brand-identity\"><img class=\"brand-logo\" src=\"" + KRISTA_LOGO_DATA_URI + "\" alt=\"Krista\"><div class=\"brand-product\">Google Workspace integration</div></div>"
                + "<span class=\"secure-badge\">SECURE SETUP</span></header>"
                + "<section class=\"hero\"><div class=\"eyebrow\">ADMINISTRATOR SETUP REQUIRED</div>"
                + "<h1>Connect Google Workspace calendars</h1>"
                + "<p>Authorize Krista to read calendars for your organization. This takes about two minutes and only needs to be completed once per tenant.</p></section>"
                + "<div class=\"progress\"><span class=\"progress-dot active\">1</span><span class=\"progress-line\"></span><span class=\"progress-dot\">2</span><span class=\"progress-label\">Authorize &nbsp;→&nbsp; Finish in Krista</span></div>"
                + "<section class=\"card\"><div class=\"step-heading\"><span class=\"step-number\">1</span><div><h2>Authorize in Google Admin Console</h2><p>Sign in with a Google Workspace super administrator account.</p></div></div>"
                + "<div class=\"instruction\"><span class=\"instruction-icon\">1</span><div><strong>Open Domain-wide delegation</strong><p>Go to <b>Security → API controls → Domain-wide delegation</b>, then choose <b>Add new</b>.</p></div></div>"
                + "<div class=\"instruction\"><span class=\"instruction-icon\">2</span><div><strong>Add Krista's service account</strong>" + clientIdBlock + "</div></div>"
                + "<div class=\"instruction\"><span class=\"instruction-icon\">3</span><div><strong>Allow these read-only permissions</strong><div class=\"scope-list\">"
                + "<div class=\"scope\"><span>✓</span><code>https://www.googleapis.com/auth/calendar.readonly</code><small>Read calendar events</small><button type=\"button\" class=\"copy-button\" data-copy=\"https://www.googleapis.com/auth/calendar.readonly\">Copy</button></div>"
                + "<div class=\"scope\"><span>✓</span><code>https://www.googleapis.com/auth/admin.directory.user.readonly</code><small>Read Workspace users</small><button type=\"button\" class=\"copy-button\" data-copy=\"https://www.googleapis.com/auth/admin.directory.user.readonly\">Copy</button></div>"
                + "<div class=\"scope\"><span>✓</span><code>https://www.googleapis.com/auth/admin.directory.group.readonly</code><small>Read Workspace groups</small><button type=\"button\" class=\"copy-button\" data-copy=\"https://www.googleapis.com/auth/admin.directory.group.readonly\">Copy</button></div>"
                + "</div></div></div>"
                + "<a class=\"primary-button\" href=\"" + Constants.GOOGLE_DWD_ADMIN_URL
                + "\" target=\"_blank\" rel=\"noopener noreferrer\">Open Google Admin Console <span>↗</span></a></section>"
                + "<section class=\"card finish-card\"><div class=\"step-heading\"><span class=\"step-number\">2</span><div><h2>Finish setup in Krista</h2><p>Return here after saving the authorization. Krista will verify the grant before enabling calendar sync.</p></div></div>"
                + "<form method=\"post\" action=\"continue?token=" + token + "\"><button class=\"secondary-button continue-button\" type=\"submit\">I've added the permissions — continue <span>→</span></button></form></section>"
                + "<div class=\"privacy\"><span>▣</span><div><strong>Your data stays protected</strong><p>Krista requests read-only access and never stores your Google password. You can revoke access at any time from Google Admin Console.</p></div></div>"
                + "<footer>Need help? Return to the Krista conversation and run <b>Save Configuration</b> again.</footer></main>";
        return dwdPage(Response.Status.OK, body);
    }

    /** Signals Krista to retry the original catalog request, which probes DWD before saving. */
    @POST
    @Path("dwd/continue")
    @Produces(MediaType.TEXT_HTML)
    public Response continueDwdSetup(@QueryParam("token") String token) {
        if (dwdSetupStore == null || dwdSetupStore.find(invokerId, token) == null) {
            return dwdPage(Response.Status.NOT_FOUND,
                    "This setup link expired. Return to Krista and run Save Configuration again.");
        }
        if (!context.isAuthenticated()) {
            return dwdPage(Response.Status.FORBIDDEN,
                    "Krista could not identify the pending conversation. Return to Krista and run "
                            + "Save Configuration again.");
        }
        if (!dwdSetupStore.consume(invokerId, token)) {
            return dwdPage(Response.Status.CONFLICT,
                    "This setup link has already been used. Return to the Krista conversation.");
        }
        authorizationListener.authorized();
        return dwdPage(Response.Status.OK,
                "Krista is checking the Google Workspace grant. Return to the conversation to "
                        + "see the result. If Google has not propagated the authorization yet, "
                        + "repeat this step after a few minutes.");
    }

    private static Response dwdPage(Response.Status status, String body) {
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Krista Google Workspace setup</title>"
                + "<style>"
                + ":root{color-scheme:light;--ink:#172033;--muted:#667085;--line:#e5e9f0;--blue:#2563eb;--blue-dark:#1d4ed8;--soft:#f6f8fc}"
                + "*{box-sizing:border-box}body{margin:0;background:linear-gradient(145deg,#f5f8ff 0%,#fff 58%);color:var(--ink);font:15px/1.55 Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif}"
                /* Layout styles are defined in the declarations below. */
                + ".shell{max-width:760px;margin:0 auto;padding:34px 24px 42px}.brand{display:flex;align-items:center;gap:12px;padding-bottom:28px;border-bottom:1px solid var(--line)}.brand-identity{display:flex;align-items:center;gap:14px;min-width:0}.brand-logo{display:block;width:142px;height:auto;max-height:42px;object-fit:contain;object-position:left center}.brand-product{border-left:1px solid var(--line);padding-left:14px;color:var(--muted);font-size:12px;white-space:nowrap}.secure-badge{margin-left:auto;border:1px solid #cfe0ff;border-radius:999px;color:#3568c9;background:#eff5ff;font-size:10px;font-weight:750;letter-spacing:.08em;padding:5px 9px}.hero{padding:40px 3px 22px}.eyebrow{color:#3568c9;font-size:11px;font-weight:800;letter-spacing:.1em}.hero h1{font-size:34px;line-height:1.15;letter-spacing:-.04em;margin:10px 0 12px}.hero p{color:var(--muted);font-size:16px;max-width:650px;margin:0}.progress{display:flex;align-items:center;position:relative;margin:0 0 18px;color:var(--muted);font-size:12px}.progress-dot{display:grid;place-items:center;width:26px;height:26px;border:1px solid #ccd4e2;border-radius:50%;background:#fff;color:#8a94a6;font-weight:750}.progress-dot.active{border-color:var(--blue);background:var(--blue);color:#fff}.progress-line{height:1px;width:42px;background:#ccd4e2;margin:0 8px}.progress-label{margin-left:10px;color:#7a8496}"
                + ".card{background:#fff;border:1px solid var(--line);border-radius:16px;padding:25px 26px;box-shadow:0 12px 32px #243b6810;margin-top:14px}.step-heading{display:flex;gap:13px;align-items:flex-start}.step-number{display:grid;place-items:center;flex:0 0 30px;height:30px;border-radius:9px;background:#eaf1ff;color:var(--blue);font-weight:800}.muted-step{background:#f0f2f6;color:#798397}.step-heading h2{font-size:18px;line-height:1.25;margin:1px 0 4px;letter-spacing:-.02em}.step-heading p{color:var(--muted);margin:0}.instruction{display:flex;gap:12px;padding:19px 0;border-top:1px solid var(--line);margin-top:20px}.instruction-icon{display:grid;place-items:center;flex:0 0 22px;height:22px;border-radius:50%;background:#f0f4fb;color:#45628e;font-size:11px;font-weight:800}.instruction strong{display:block;font-size:14px}.instruction p{color:var(--muted);margin:4px 0 0}.client-id{display:flex;align-items:center;gap:12px;margin-top:10px;background:#f7f9fc;border:1px solid #e3e8f1;border-radius:9px;padding:11px 13px}.client-id-label{color:var(--muted);font-size:12px}.client-id code{color:#172033;font-size:14px;font-weight:700;word-break:break-all}.copy-button{border:1px solid #c8d5e8;border-radius:7px;background:#fff;color:#31578e;font-size:11px;font-weight:750;padding:6px 9px;cursor:pointer;white-space:nowrap}.copy-button:hover{background:#eef4ff;border-color:#8eaddb}.muted{color:var(--muted);margin:7px 0 0}.scope-list{display:grid;gap:8px;margin-top:11px}.scope{display:grid;grid-template-columns:20px minmax(0,1fr) auto;column-gap:8px;align-items:center;padding:9px 11px;border:1px solid #e7ebf2;border-radius:8px;background:#fbfcfe}.scope span{grid-row:span 2;color:#16834b;font-weight:800}.scope code{font-size:12px;overflow-wrap:anywhere}.scope small{color:var(--muted);font-size:11px}.scope .copy-button{grid-column:3;grid-row:1 / span 2}.primary-button,.secondary-button{display:inline-flex;align-items:center;justify-content:center;gap:10px;border-radius:9px;font-weight:700;text-decoration:none;cursor:pointer;transition:background .15s,box-shadow .15s}.primary-button{margin:22px 0 0 35px;background:var(--blue);color:#fff;padding:12px 17px;box-shadow:0 5px 12px #2563eb2b}.primary-button:hover{background:var(--blue-dark);box-shadow:0 7px 16px #2563eb3b}.secondary-button{border:1px solid #b8c5da;background:#fff;color:#244572;padding:11px 16px}.secondary-button:hover{background:#f5f8fd;border-color:#8fa6c7}.finish-card{position:relative;overflow:hidden;padding-top:21px;padding-bottom:21px;border-color:#cfe0ff;background:linear-gradient(135deg,#f8fbff,#fff);box-shadow:0 14px 34px #2563eb18}.finish-card:before{content:\"\";position:absolute;inset:0 auto 0 0;width:4px;background:linear-gradient(180deg,#2563eb,#60a5fa)}.finish-card .step-number{background:#eaf1ff;color:var(--blue)}.finish-card form{margin:18px 0 0 43px}.continue-button{background:var(--blue);border-color:var(--blue);color:#fff;box-shadow:0 5px 12px #2563eb2b}.continue-button:hover{background:var(--blue-dark);border-color:var(--blue-dark);color:#fff;box-shadow:0 7px 16px #2563eb3b}.privacy{display:flex;gap:11px;align-items:flex-start;margin:20px 2px 0;padding:15px 16px;border:1px solid #dcebe4;border-radius:11px;background:#f4fbf7;color:#24573d}.privacy>span{color:#16834b;font-size:18px;line-height:1.3}.privacy strong{font-size:13px}.privacy p{color:#557564;font-size:12px;margin:2px 0 0}footer{text-align:center;color:#8992a1;font-size:12px;margin-top:25px}footer b{color:#657087}@media(max-width:600px){.shell{padding:22px 16px 32px}.hero h1{font-size:28px}.card{padding:20px 17px}.secure-badge{display:none}.primary-button,.finish-card form{margin-left:0;width:100%}.progress-label{font-size:11px}}"
                + "</style></head><body>"
                + body + "</body><script>document.addEventListener('click',function(event){var button=event.target.closest('[data-copy]');if(!button)return;var value=button.getAttribute('data-copy');var done=function(){var original=button.textContent;button.textContent='Copied';setTimeout(function(){button.textContent=original},1400)};if(navigator.clipboard&&window.isSecureContext){navigator.clipboard.writeText(value).then(done);return}var area=document.createElement('textarea');area.value=value;area.style.position='fixed';area.style.opacity='0';document.body.appendChild(area);area.select();try{document.execCommand('copy');done()}finally{area.remove()}});</script></html>";
        return Response.status(status).type(MediaType.TEXT_HTML).entity(html)
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy",
                        "default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'; base-uri 'none'")
                .build();
    }

    /**
     * Handles OAuth callback for V2 authentication flow.
     *
     * @param code authorization code from Google OAuth
     * @param state state parameter containing user and context information
     * @return authentication response message
     */
    @GET
    @Path("callback")
    public String getCallBackForV2Auth(@QueryParam(CODE) String code, @QueryParam(STATE) String state) {
        return getAuthenticationResponseMessage(code, state);
    }

    /**
     * Handles OAuth callback for V3 authentication flow.
     *
     * @param code authorization code from Google OAuth
     * @param state state parameter containing user and context information
     * @return authentication response message
     */
    @GET
    @Path("v3/oauth/callback")
    public String getCallbackForV3Auth(@QueryParam(CODE) String code, @QueryParam(STATE) String state) {
        return getAuthenticationResponseMessage(code, state);
    }

    /**
     * Processes OAuth authentication callback and exchanges authorization code for tokens.
     *
     * @param code authorization code from Google OAuth
     * @param state state parameter containing user and context information
     * @return authentication response message indicating success or failure
     */
    private String getAuthenticationResponseMessage(String code, String state) {
        LOGGER.info("=== OAUTH CALLBACK RECEIVED ===");
        LOGGER.info("Callback endpoint hit - state: [{}]", state);
        LOGGER.info("Authorization code: {}", code != null ? "present (length: " + code.length() + ")" : "MISSING");
        LOGGER.info("Base routing URL: {}", baseRoutingUrl);
        LOGGER.info("================================");

        if (code == null) {
            LOGGER.error("Missing authorization code in callback. Authentication failed.");
            return "Authentication Failed. Please re-authorize.";
        }

        String[] parts = state.split(Constants.HASH);
        if (parts[0].isBlank() || parts.length > 3) {
            LOGGER.error("State parameter is invalid or empty.");
            throw new BadRequestException(Constants.INVALID_STATE_PARAMETERS);
        }
        String key = parts[0];
        String authContextId = AuthHelper.getAuthContextId(state);
        // The reconnect callback is forwarded to whichever stage built the OAuth URL, where the
        // request/injected Invoker id is unreliable. buildAuthUrl stamped the reliable invokerId
        // into the state, so prefer it; fall back to this.invokerId for the in-context setup flow.
        String stateInvokerId = AuthHelper.getInvokerId(state);
        String effectiveInvokerId = stateInvokerId != null ? stateInvokerId : this.invokerId;
        LOGGER.debug("Parsed key: [{}] from state. AuthContextId resolved: [{}], effectiveInvokerId: [{}]",
                key, authContextId, effectiveInvokerId);
        try {
            LOGGER.info("Retrieving GoogleClientProvider using AuthContextId: {} (invokerId={})",
                    authContextId, effectiveInvokerId);
            GoogleProvider clientProvider = getGoogleClientProvider(authContextId, effectiveInvokerId);

            LOGGER.debug("Fetching Google attributes for authentication...");
            GoogleAttributes googleAttributes = clientProvider.getGoogleAttributes();

            LOGGER.debug("Initializing OAuth20Service for token exchange...");
            OAuth20Service oAuth20Service = googleAttributes.getOAuth20Service();

            LOGGER.info("Requesting access token from Google...");
            OAuth2AccessToken accessToken = oAuth20Service.getAccessToken(code);
            LOGGER.info("Access token retrieved successfully.");


            String refreshTokenString = accessToken.getRefreshToken();
            String accessTokenString = accessToken.getAccessToken();
            String refreshTokenStored = refreshTokenStore.get(key);

            // Google Provides 1 refresh token at a time
            // For studio user key starts with setup emailId
            // For client user key starts with wsContact
            // if the refresh token fetched is for client then store access token
            if (key.startsWith(Constants.WS_CONTACT) && refreshTokenString == null) {
                refreshTokenStore.put(key, accessTokenString); // Storing access token for client
            }
            // if the refresh token stored for studio user is access token then revoke the token
            else if (refreshTokenStored != null && refreshTokenStored.startsWith("ya29")) {
                LOGGER.error("Refresh token access removed. Re-authorization is needed.");
                // Revoking access token to get refresh token for existing studio users
                clientProvider.getGoogleAttributes().getOAuth20Service().revokeToken(accessToken.getAccessToken());
                refreshTokenStore.remove(key);
                return "We need to remove your access token permissions. Please validate again to regain access.";
            }
            // if the refresh token is not null and key doesn't start with wsContact
            // store refresh token for studio user
            else {
                refreshTokenStore.put(key, refreshTokenString);
            }

            if (!key.startsWith(Constants.WS_CONTACT) && !hasUserAccess(clientProvider)) {
                refreshTokenStore.remove(key);
                return "Unauthorised user. User email '" + key.split("#")[0] + "' configured in the setup does not match with authenticated user.";
            }
            // Save Configuration reaches this callback directly, without executing
            // AuthenticateHandler. Register the user here so a clean install/upgrade does not
            // retain a valid Google token while leaving gcal_sync_authenticated_users empty.
            // registerUser also re-asserts CONNECTED to CA on every successful OAuth callback.
            String authenticatedUserId = resolveAuthenticatedUserId(key, googleAttributes);
            String authenticatedEmail = clientProvider.getPrimaryCalendarEmail(authenticatedUserId);
            if ((authenticatedEmail == null || authenticatedEmail.isBlank())
                    && !authenticatedUserId.startsWith(Constants.WS_CONTACT)) {
                // hasUserAccess already proved that the configured account owns a usable calendar.
                // Keep sync available even if the optional primary-calendar identity lookup fails.
                authenticatedEmail = authenticatedUserId;
            }
            authenticationService.registerUser(authenticatedUserId, effectiveInvokerId,
                    workspaceId, authenticatedEmail);

            try {
                // Index email->token-key while we have both (and a healthy token) so a later
                // CA-initiated reconnect can recover this exact key from just the email. Use the
                // effective (state-supplied) invokerId so the KV scope is correct on the forwarded thread.
                userKeyIndexStore.put(effectiveInvokerId, authenticatedEmail, key);
                // Successful reconnect: clear the reminder rate-limit so a future
                // breakage gets fresh reminders instead of staying capped (KIP parity).
                if (authenticatedEmail != null && !authenticatedEmail.isBlank()) {
                    notificationService.resetCount(authenticatedEmail);
                }
            } catch (Exception statusEx) {
                LOGGER.warn("User {} was registered, but post-auth indexing failed for key={}: {}",
                        authenticatedUserId, key, statusEx.getMessage());
            }
            if (context.isAuthenticated()) {
                authorizationListener.authorized();
                return "User authenticated successfully.";
            }
            return "User authenticated successfully. Save the changes.";
        } catch (OAuthException cause) {
            throw new IllegalStateException(getErrorDescription(cause), cause.getCause());
        } catch (IOException | ExecutionException cause) {
            throw new IllegalStateException("Error occurred during authorization ", cause.getCause());
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Error occurred during authorization ", interruptedException.getCause());
        } finally {
            AuthHelper.cleanupAuthContext(googleAttributeStore, authContextId);
        }
    }

    /**
     * Converts the OAuth state's refresh-token key back to the identifier consumed by
     * {@code GoogleProvider#getGoogleClientForUser}. Studio setup stores the token under
     * {@code email_clientId_authType}; passing that composite key to the provider would append
     * the suffix a second time and immediately produce MustAuthorizeException. Client users use
     * an opaque {@code wsContact_...} key and must keep it unchanged.
     */
    private String resolveAuthenticatedUserId(String tokenKey, GoogleAttributes googleAttributes) {
        if (tokenKey.startsWith(Constants.WS_CONTACT)) {
            return tokenKey;
        }

        String configuredEmail = googleAttributes.getMailId();
        if (configuredEmail == null || configuredEmail.isBlank()) {
            return tokenKey;
        }

        String configuredTokenKey = configuredEmail + Constants.UNDER_SCORE
                + googleAttributes.getClientId() + Constants.UNDER_SCORE
                + googleAttributes.getAuthType();
        return configuredTokenKey.equals(tokenKey) ? configuredEmail : tokenKey;
    }

    /**
     * Creates a Google provider instance based on auth context ID.
     *
     * @param authContextId authentication context identifier, can be null
     * @return GoogleProvider instance
     * @throws IOException if provider creation fails
     */
    private GoogleProvider getGoogleClientProvider(String authContextId, String effectiveInvokerId) throws IOException {
        GoogleProvider clientProvider;
        if (authContextId == null) {
            // CA-initiated reconnect: the forwarded callback thread has no reliable request/injected
            // invokerId, so load the invoker's persisted OAuth client by the invokerId carried in the
            // state (stamped by buildAuthUrl on the ESB thread), not the forwarded-thread Invoker.
            clientProvider = providerFactory.createForInvoker(effectiveInvokerId);
        } else {
            clientProvider = providerFactory.create(authContextId);
        }
        return clientProvider;
    }

    /**
     * Extracts error description from OAuth exception.
     *
     * @param cause OAuth exception containing error details
     * @return formatted error description message
     */
    private String getErrorDescription(OAuthException cause) {
        String errorDescription = ERROR_OCCURRED_DURING_AUTHORIZATION;
        if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> exceptionMap = (Map<String, Object>) Constants.GSON.fromJson(cause.getMessage(), Map.class);
            if (exceptionMap.containsKey(ERROR_DESCRIPTION)) {
                errorDescription = errorDescription + exceptionMap.get(ERROR_DESCRIPTION);
            }
        }
        return errorDescription;
    }

    /**
     * Verifies if the authenticated user has access to Google Calendar.
     *
     * @param googleProvider Google provider instance to test
     * @return true if user has access, false otherwise
     */
    private boolean hasUserAccess(GoogleProvider googleProvider) {
        try {
            googleProvider.getGoogleClient().events().list("primary").setMaxResults(Math.toIntExact(1L))
                    .setTimeMin(new DateTime(System.currentTimeMillis()))
                    .setOrderBy("startTime")
                    .setSingleEvents(true).execute();

            return true;
        } catch (IOException cause) {
            LOGGER.error("-------IGNORED EXCEPTION-----{}", cause.getMessage());
            return false;
        }
    }

    /**
     * Serves custom documentation and UI resources.
     *
     * @param subPath path to the requested resource
     * @return input stream of the requested resource
     */
    @GET
    @Path("/docs/{subPath:.*}")
    public InputStream customTabs(@PathParam("subPath") String subPath) {
        String filePath = "ui/googlecalendaroauth/" + (subPath.isEmpty() ? "index.html" : subPath);
        return getClass().getClassLoader().getResourceAsStream(filePath);
    }

    /**
     * Saves Google Calendar authentication credentials.
     *
     * @param authPayload JSON object containing authentication credentials
     * @return JSON response indicating success or failure
     */
    @POST
    @Path("/saveCredentials")
    @Produces("text/plain")
    public String saveCredentials(JsonObject authPayload) {
        GoogleAttributes attributes = GoogleAttributes.create(authPayload, baseRoutingUrl);
        String authContextId = providerFactory.createAttributes(attributes);
        try {
            providerFactory.create(attributes).testConnection();
            boolean isSaved = googleAttributeStore.save(attributes, invokerId);
            return isSaved
                    ? GSON.toJson(new AuthenticationResponse(true, null, null))
                    : GSON.toJson(new AuthenticationResponse(false, FAILED_TO_SAVE_ATTRIBUTES, null));
        } catch (Exception cause) {
            LOGGER.debug(FAILED_TO_SAVE_ATTRIBUTES);
            return GSON.toJson(new AuthenticationResponse(false, FAILED_TO_SAVE_ATTRIBUTES, null));
        } finally {
            AuthHelper.cleanupAuthContext(googleAttributeStore, authContextId);
        }
    }

    /**
     * Tests connection to Google Calendar with provided credentials.
     *
     * @param authPayload JSON object containing authentication credentials
     * @return JSON response with test result and authorization URL if needed
     */
    @POST
    @Path("/testConnection")
    @Produces("text/plain")
    public String testConnection(JsonObject authPayload) {
        GoogleAttributes attributes = GoogleAttributes.create(authPayload, baseRoutingUrl);
        String authContextId = providerFactory.createAttributes(attributes);
        String authUrl = null;
        try {
            providerFactory.create(authContextId).testConnection();
            LOGGER.info("Test connection successful");
            return createTestConnectionResponse(true, null, null);
        } catch (MustAuthorizeException cause) {
            String state = createStateParameter(cause, attributes);
            authUrl = attributes.getOAuth20Service().getAuthorizationUrl(state) + AUTH_URL_QUERY_PARAMS;
            LOGGER.info("=== GOOGLE OAUTH AUTHORIZATION URL ===");
            LOGGER.info("Auth URL: {}", authUrl);
            LOGGER.info("Callback URL configured: {}", attributes.getCallbackUrl());
            LOGGER.info("Auth Type: {}", attributes.getAuthType());
            LOGGER.info("======================================");
            return createTestConnectionResponse(false, AUTHORIZATION_PROMPT, authUrl);
        } catch (IOException exception) {
            LOGGER.error(exception.getMessage(), exception);
            return createTestConnectionResponse(false, "An error occurred during test connection." + exception.getMessage(), null);
        } finally {
            AuthHelper.cleanupAuthContextIf(googleAttributeStore, authContextId, authUrl == null);
        }
    }

    /**
     * Creates state parameter for OAuth flow from exception details.
     *
     * @param cause authorization exception containing user details
     * @param googleAttributes Google authentication attributes
     * @return formatted state parameter string
     */
    private String createStateParameter(MustAuthorizeException cause, GoogleAttributes googleAttributes) {
        String userId = (String) cause.getDetails().getFirst().getValue();
        return createStateParameter(cause, googleAttributes, userId);
    }

    /**
     * Creates state parameter for OAuth flow with explicit user ID.
     *
     * @param cause authorization exception containing context details
     * @param googleAttributes Google authentication attributes
     * @param userId user identifier to include in state
     * @return formatted state parameter string
     */
    private String createStateParameter(MustAuthorizeException cause, GoogleAttributes googleAttributes, String userId) {
        Optional<NamedValuedField> authContextIdField = cause.getDetails().stream()
                .filter(namedValuedField -> Objects.equals(namedValuedField.getName(), Constants.AUTH_CONTEXT_ID))
                .findFirst();
        String state = userId;
        LOGGER.info("State parameter userId: {}", userId);
        if (authContextIdField.isPresent()) {
            LOGGER.info("State parameter authContextId: {}", authContextIdField.get().getValue());
            String authContextId = (String) authContextIdField.get().getValue();
            state += Constants.HASH + authContextId;
        }
        // Carry the per-instance forward path in the OAuth state for every auth
        // type. The redirect_uri registered with Google is now environment-level
        // (see GoogleAttributes#getCallbackEndPoint), so the platform relies on
        // this forwardPath to route the callback back to the owning instance.
        state = googleAttributes.appendForwardPath(state);
        LOGGER.info("State parameter final: {}", state);
        return state;
    }

    /**
     * Creates JSON response for test connection operation.
     *
     * @param isSuccess true if connection test succeeded
     * @param errorMessage error message if connection failed, can be null
     * @param url authorization URL if authorization is required, can be null
     * @return JSON formatted response string
     */
    private String createTestConnectionResponse(boolean isSuccess, String errorMessage, String url) {
        if (isSuccess) {
            return GSON.toJson(new AuthenticationResponse(true, null, null));
        } else if (url != null) {
            return GSON.toJson(new AuthenticationResponse(false, errorMessage, url));
        } else
            return GSON.toJson(new AuthenticationResponse(false,
                    Objects.requireNonNullElse(errorMessage, "Unknown Error"), null));
    }

    /**
     * Retrieves stored Google Calendar credentials for the specified auth type.
     *
     * @param authType authentication type (e.g., "public" or "private")
     * @return JSON formatted credentials or empty string if not found
     */
    @GET
    @javax.ws.rs.Path("/getCredentials")
    @Produces("text/plain")
    public String getCredentials(@QueryParam(AUTH_TYPE) String authType) {
        LOGGER.info("Loading attributes for auth-type: {} for invoker {}", authType, invokerId);
        final GoogleAttributes loadedAttributes = googleAttributeStore.load(invokerId);
        if (loadedAttributes != null && authType.equals(loadedAttributes.getAuthType())) {
            return GSON.toJson(loadedAttributes.toMap());
        } else {
            return "";
        }
    }

    /**
     * Retrieves the stored authentication key type.
     *
     * @return JSON formatted auth key type or empty string if not found
     */
    @GET
    @javax.ws.rs.Path("/getAuthKey")
    @Produces("text/plain")
    public String getAuthKey() {
        LOGGER.info("Loading stored authentication key.");
        final GoogleAttributes attributes = googleAttributeStore.load(invokerId);
        if (attributes != null) {
            final String authType = attributes.getAuthType();
            return GSON.toJson(Objects.requireNonNullElse(authType, Constants.PUBLIC));
        } else {
            return "";
        }
    }

}
