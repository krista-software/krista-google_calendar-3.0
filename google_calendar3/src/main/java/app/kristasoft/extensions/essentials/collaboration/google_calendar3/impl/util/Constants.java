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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.Set;

public class Constants {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String HASH = "#";
    public static final String UNDER_SCORE = "_";
    public static final String WS_CONTACT = "wsContact";
    public static final String FAILED_TO_GET_ACCOUNT = "Failed to get account.";
    public static final String TEXT = "Text";
    public static final String USER_ID = "userId";
    public static final String GOT_ERROR_FOR_AUTHENTICATION_SO_SENDING_FOR_RE_AUTHENTICATION = "Got error for Authentication. So sending for re authentication";
    public static final String GOT_ERROR_FOR_AUTHENTICATION_SO_SENDING_FOR_AUTHENTICATION = "Got error for Authentication. So sending for authentication";
    public static final String REFRESH_TOKEN_EXPIRED_PLEASE_REAUTHORIZE_YOURSELF = "Refresh Token Expired. Please reauthorize yourself";
    public static final String AUTHORIZATION_PROMPT = "Authorize yourself and click 'Test Connection' before saving changes to proceed further.";
    /** Marker carried by validation failures that need Workspace-admin DWD setup, not user OAuth. */
    public static final String DWD_SETUP_FIELD = "googleDwdSetup";
    public static final String DWD_CLIENT_ID_FIELD = "googleDwdClientId";
    /** Workspace Admin Console page where a super-admin authorises a service-account client ID. */
    public static final String GOOGLE_DWD_ADMIN_URL = "https://admin.google.com/ac/owl/domainwidedelegation";
    public static final String DWD_SETUP_PROMPT =
            "Google Workspace domain-wide delegation is not authorised. Open the Google Admin Console "
                    + "and add the service account's numeric Client ID with these read-only scopes: "
                    + "https://www.googleapis.com/auth/calendar.readonly, "
                    + "https://www.googleapis.com/auth/admin.directory.user.readonly, and "
                    + "https://www.googleapis.com/auth/admin.directory.group.readonly. Then return and "
                    + "continue setup (or click 'Validate Attributes' again in Studio).";
    public static final String FAILED_TO_GET_GOOGLE_CLIENT = "Failed to get google client ";
    public static final String AUTH_CONTEXT_ID = "authContextId";
    /** Marker prefix for the invokerId carried in the reconnect OAuth {@code state} so the
     *  forwarded callback can load the OAuth client without depending on the (unreliable)
     *  forwarded-thread {@code Invoker}. State shape: {@code key#invokerId_<id>#forwardPath}. */
    public static final String INVOKER_ID_PREFIX = "invokerId_";
    public static final String AUTHENTICATED_ACCOUNT_ID_STATE_MARKER = "|kristaAccountId=";
    public static final String GOOGLE_CALENDAR = "Google Calendar";
    public static final String PRIMARY = "primary";
    public static final String PUBLIC = "Public";
    public static final String PRIVATE = "Private";

    public static final String UNSUPPORTED_AUTH = "Unsupported authentication type provided. Please select either Public or Private.";

    public static final String LOCAL_EXTN_URL = "https://extension.local.eng.krista.app";
    public static final String DEFAULT_CALLBACK_PATH = "/extension/api/rest/v3/oauth/callback";
    public static final String EXTENSION_FORWARD_PATH = "/rest/googlecalendar/v3/oauth/callback";
    public static final String LOCAL_EXTN_REPLACE_URL = "http://localhost:8765";
    public static final String EMPTY_STRING = "";
    public static final String AUTH_TYPE = "authType";
    public static final String FAILED_TO_SAVE_ATTRIBUTES = "Failed to save attributes";
    public static final String AUTH_URL_QUERY_PARAMS = "&access_type=offline&prompt=consent select_account";
    public static final String ERROR_OCCURRED_DURING_AUTHORIZATION = "Error occurred during authorization: ";
    public static final String ERROR_DESCRIPTION = "error_description";

    public static final String ENABLE_ERROR_HANDLING="Allow retry";

    public static final String SERVICE_URL = "serviceUrl";

    /**
     * KIM-474: "Always On" meeting capture for this workspace. When the tenant switches this on,
     * Meeting Agent captures every meeting reachable through this calendar system rather than only
     * the meetings of licensed, individually-authenticated users.
     *
     * <p>This attribute is the AUTHORITATIVE source of the flag. It is not sent to Meeting Agent
     * from here — this extension never registers itself with MA, it only reads its own registration
     * back. The Meeting Agent wrapper extension reads the flag over the shared
     * {@code Get Capture Configuration} catalog request when it registers, and forwards it as the
     * top-level {@code alwaysOn} field of the registration payload.
     *
     * <p>Declared {@code required = false} and defaulting to off, so existing invokers keep their
     * current (per-user) capture behaviour until an admin opts in.
     */
    public static final String ALWAYS_ON = "alwaysOn";

    /**
     * Reads the {@link #ALWAYS_ON} flag out of a raw invoker-attribute map.
     *
     * <p>Studio stores a {@code @Field.Boolean} as a {@link Boolean}, but attribute maps also
     * arrive from JSON round-trips where the same value can surface as a string or a number.
     * Anything unrecognised — and an absent key — reads as {@code false}: capture must never be
     * widened by a value we could not interpret.
     *
     * @param attributes the invoker attribute map; may be null
     * @return true only when the attribute is unambiguously on
     */
    public static boolean readAlwaysOn(java.util.Map<String, Object> attributes) {
        if (attributes == null) {
            return false;
        }
        Object raw = attributes.get(ALWAYS_ON);
        if (raw instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (raw instanceof Number numberValue) {
            return numberValue.intValue() != 0;
        }
        if (raw instanceof String stringValue) {
            return "true".equalsIgnoreCase(stringValue.trim());
        }
        return false;
    }

    /**
     * KIM-474: the Google Cloud <strong>service-account JSON key</strong> used for domain-wide
     * delegation (DWD), pasted verbatim by the administrator.
     *
     * <p><strong>Why a second credential exists at all.</strong> The OAuth client id/secret this
     * extension already holds ({@link app.kristasoft.extensions.essentials.collaboration.google_calendar3.GoogleAttributes})
     * can only ever mint tokens for a user who personally consented — it is structurally incapable
     * of acting for anyone else, unlike a Microsoft application permission. Reading a colleague's
     * mailbox in a Google Workspace account requires a service account whose numeric client id a
     * Workspace super-admin has authorised against specific scopes. That is a different credential artefact,
     * so it needs its own home.
     *
     * <p>Declared {@code isSecured = true}, exactly like {@link #SERVICE_API_KEY}: Studio masks it,
     * and nothing in this codebase may log, echo, or place it in an exception message. See
     * {@code DomainWideDelegationProvider.redact}.
     *
     * <p>Optional. Absent means "delegated discovery is not available" — Always On then degrades
     * to the consented-user set rather than failing.
     */
    public static final String SERVICE_ACCOUNT_KEY = "serviceAccountKey";

    /**
     * KIM-474: the Google Workspace <strong>super-admin</strong> whose identity the service account
     * impersonates for Admin SDK Directory calls. Directory {@code users.list} is an admin-only API;
     * a delegated credential is always minted <em>for a user</em>, so tenant enumeration has to
     * borrow an administrator's identity.
     *
     * <p>Not a secret — it is an email address — so it is declared unmasked. Required only when
     * {@link #SERVICE_ACCOUNT_KEY} is set; absent, delegated discovery degrades.
     */
    public static final String TENANT_ADMIN_EMAIL = "tenantAdminEmail";

    /**
     * KIM-474: optional single-domain restriction for the Workspace directory lookup. Blank (the default) lists
     * every domain in the Workspace account ({@code customer=my_customer}); a value restricts
     * {@code users.list} to that one domain, which is what a multi-domain tenant wants when only
     * part of the account is licensed for Meeting Agent.
     */
    public static final String TENANT_DOMAIN = "tenantDomain";

    /**
     * Invoker-level settings that may be overlaid by a configuration save. Keeping this list next
     * to the attribute names prevents catalog code from handling the service-account key ad hoc.
     */
    public static Set<String> invokerConfigurationAttributeNames() {
        return Set.of(SERVICE_URL, SERVICE_API_KEY, SERVICE_ACCOUNT_KEY, TENANT_ADMIN_EMAIL,
                TENANT_DOMAIN, ALWAYS_ON, ENABLE_ERROR_HANDLING);
    }

    /**
     * Returns the Always On delegation fields missing from an effective configuration, or null
     * when both are present. The caller can include the returned names in a validation response
     * without ever reading the private-key attribute itself.
     */
    public static String missingDelegationFields(Map<String, Object> attributes) {
        StringBuilder missing = new StringBuilder();
        if (readTextAttribute(attributes, SERVICE_ACCOUNT_KEY) == null) {
            missing.append("serviceAccountKey, ");
        }
        if (readTextAttribute(attributes, TENANT_ADMIN_EMAIL) == null) {
            missing.append("tenantAdminEmail, ");
        }
        if (missing.isEmpty()) {
            return null;
        }
        missing.setLength(missing.length() - 2);
        return missing.toString();
    }

    /** Extract only the public numeric client ID; never put the service-account JSON in a URL. */
    public static String serviceAccountClientId(Map<String, Object> attributes) {
        String key = readTextAttribute(attributes, SERVICE_ACCOUNT_KEY);
        if (key == null) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(key).getAsJsonObject();
            String clientId = json.get("client_id").getAsString();
            return clientId.matches("[0-9]{10,30}") ? clientId : null;
        } catch (RuntimeException cause) {
            return null;
        }
    }

    /**
     * Reads a text invoker attribute, normalising every "absent" spelling to {@code null}.
     *
     * <p>Attribute maps reach us from Studio and from JSON round-trips, so an unset text field can
     * arrive as a missing key, {@code null}, {@code ""}, whitespace, or the literal string
     * {@code "null"}. Callers gate configuration on this, so every one of those must mean the same
     * thing. Never logs the value — this is the accessor used for the service-account key.
     *
     * @param attributes the invoker attribute map; may be null
     * @param key        the attribute key
     * @return the trimmed value, or {@code null} when it is absent by any spelling
     */
    public static String readTextAttribute(java.util.Map<String, Object> attributes, String key) {
        if (attributes == null) {
            return null;
        }
        Object raw = attributes.get(key);
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }

    public static final String INVALID_STATE_PARAMETERS = "Invalid state parameters!";

    /** Header and key for authenticating calls to the MA (Conversation Agent) service. */
    public static final String MA_API_KEY_HEADER = "x-api-key";
    public static final String MA_API_KEY = resolveMaApiKey();

    /**
     * KIM-457: the MA API key, configured per-invoker in Krista Studio. Named to match the
     * Meeting Agent wrapper's attribute so all three extensions are configured identically.
     *
     * <p>Until now this extension declared no api-key field at all while its code read a
     * non-existent {@code "apiKey"} attribute — so the value was ALWAYS null and every MA call
     * fell through to the hardcoded ops key. Removing that fallback without exposing this field
     * would have left the extension with no key whatsoever.
     */
    public static final String SERVICE_API_KEY = "serviceApiKey";

    /**
     * Resolve the MA API key: Studio-configured invoker attribute first, then the
     * {@code CA_API_KEY} environment variable. Returns "" rather than null when neither is set —
     * a null value on a JAX-RS builder means "remove this header", which yields a confusing
     * no-key request instead of a clean 401. No ops-key fallback: FULL tier bypasses workspace
     * isolation and the licence-admin gate, so escalating on misconfiguration is a tenant hole.
     */
    public static String resolveMaApiKey(app.krista.extension.executor.Invoker invoker) {
        if (invoker != null && invoker.getAttributes() != null) {
            Object v = invoker.getAttributes().get(SERVICE_API_KEY);
            String fromAttribute = v == null ? "" : v.toString().trim();
            if (!fromAttribute.isEmpty() && !"null".equalsIgnoreCase(fromAttribute)) {
                return fromAttribute;
            }
        }
        String fromEnv = System.getenv("CA_API_KEY");
        return (fromEnv != null && !fromEnv.isBlank()) ? fromEnv : "";
    }

    /**
     * Tenant context header for calls to MA. Mandatory on every PRODUCTION/TRIAL-tier
     * request — MA's WorkspaceAuthorizationFilter 400s without it before the resource
     * runs — and optional only for FULL/ops keys (KIM-457).
     */
    public static final String WORKSPACE_ID_HEADER = "X-Workspace-ID";

    /**
     * KIM-457: the CONFIGURED key only. This used to fall back to the ops key
     * {@code dev-full-key-12345} when {@code CA_API_KEY} was unset. FULL tier bypasses
     * workspace isolation and the licence-admin gate, so that fallback was a cross-tenant
     * escalation shipped in a customer-facing extension — and it silently masked
     * misconfiguration instead of surfacing it. Returns null when unset, so the call
     * fails loudly at MA rather than succeeding with ops privileges.
     */
    private static String resolveMaApiKey() {
        String key = System.getenv("CA_API_KEY");
        return (key != null && !key.isBlank()) ? key : null;
    }

    private Constants() {
    }

}
