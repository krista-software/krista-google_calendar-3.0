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

import app.krista.extension.authorization.RequestAuthenticator;
import app.krista.extension.authorization.MustAuthorizeException;
import app.krista.extension.executor.Invoker;
import app.krista.extension.impl.anno.*;
import app.krista.ksdk.accounts.AccountProvider;
import app.krista.ksdk.context.AuthorizationContext;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.CalendarAuthInitiateEsbListener;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.CalendarConnectionStatusEventPublisher;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.EsbProtoRegistrar;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.GoogleAttributeStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.DwdSetupStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.LiveGoogleCalendarConfiguration;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.OtelAgentLogFilter;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.StudioLogMirror;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.GoogleServiceBusManager;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.Hk2ContextHolder;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarAuthenticationService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook.CalendarWatchService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import app.krista.model.field.NamedValuedField;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationException;

/**
 * Main extension class for Google Calendar integration.
 *
 * <p>This class serves as the entry point for the Google Calendar extension,
 * providing authentication, custom tabs, and test connection functionality.</p>
 *
 * @author Krista Software
 * @version 3.0.7
 * @since 3.0.0
 */
@Java(version = Java.Version.JAVA_21)
// Field order below is Studio display order, BUT the extension processor emits the fields grouped
// by annotation type in the order each type first appears — so every @Field.Text is listed before
// the @Field.Boolean group on purpose, to render the two switches beneath the text fields as the
// KIP and Meeting Agent extensions do (owner, 2026-09-11).
@Field.Text(value = Constants.SERVICE_URL, required = false)
@Field.Text(value = Constants.SERVICE_API_KEY, isSecured = true, required = false)
// KIM-474: domain-wide-delegation credentials, used ONLY when Always On is enabled. All three are
// optional: without them Always On still works, it just scans the individually-authenticated users
// exactly as it does today. The key is declared isSecured like serviceApiKey — Studio masks it and
// nothing may log or echo it.
@Field.Text(value = Constants.SERVICE_ACCOUNT_KEY, isSecured = true, required = false)
@Field.Text(value = Constants.TENANT_ADMIN_EMAIL, required = false)
@Field.Text(value = Constants.TENANT_DOMAIN, required = false)
@Field.Boolean(value = Constants.ENABLE_ERROR_HANDLING,required = false)
// KIM-474: Always On meeting capture. Optional and off by default, so existing invokers keep
// their current per-user capture behaviour until an admin opts in. The Meeting Agent wrapper
// reads this over the shared "Get Capture Configuration" catalog request at registration time.
// Declared LAST so Studio renders the checkbox at the bottom, as the KIP and Meeting Agent
// extensions do (owner, 2026-09-11).
@Field.Boolean(value = Constants.ALWAYS_ON, required = false)
@Extension(version = "3.1.3", name = "Google Calendar-V3", requireWorkspaceAdminRights = true,
        implementationModel = Extension.ImplementationModel.Krista_4_O)
@Containerize(baseImageVersion = "3.6.2-sp3", scalable = false)
@StaticResource(path = "docs", file = "docs")
@ChangeLog(file = "docs/pages/release-notes.md")
public class GoogleCalendarExtension {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleCalendarExtension.class);

    static {
        // Two stderr-related fixes, both installed at class load — the earliest point the
        // extension controls. Background: the invoker base image captures stderr into
        // /opt/krista/var/log/debug.log, which is exactly what the Studio 'Extension Log'
        // diagnostics panel displays; log4j output goes to stdout (container logs/SigNoz)
        // and never reaches that panel.
        //  1. OtelAgentLogFilter — the otel javaagent floods stderr (and therefore the Studio
        //     panel) with "Failed to export spans" WARNs whenever the collector sheds load.
        //     Its internal logger bypasses log4j2 and reads no config from this jar, so
        //     filtering stderr is the only in-jar remedy.
        //  2. StudioLogMirror — mirrors this extension's log4j lines to stderr so they DO
        //     appear in the Studio panel (the base image regenerates log4j2.xml with a
        //     stdout-only appender, so this cannot be done via resources/log4j2.xml).
        // Failure must never prevent the extension class from loading, hence the catch-alls.
        try {
            boolean installed = OtelAgentLogFilter.install();
            LOGGER.info("Google Calendar Extension class loaded; otel stderr noise filter {}",
                    installed ? "installed" : "DISABLED via env var");
        } catch (Throwable t) {
            LOGGER.warn("Could not install otel stderr noise filter — continuing without it", t);
        }
        try {
            StudioLogMirror.install();
            LOGGER.info("Studio log mirror installed — extension INFO+ logs now appear in the Studio Extension Log panel");
        } catch (Throwable t) {
            LOGGER.warn("Could not install Studio log mirror — extension logs remain stdout-only", t);
        }
    }

    /** Request authenticator for OAuth authentication */
    private final GoogleCalendarAuthenticator requestAuthenticator;

    /** Service for managing Google Calendar push notification channels */
    private final CalendarWatchService calendarWatchService;

    /** Store that persists per-user calendar connection status and publishes ESB events */
    private final CalendarConnectionStatusStore calendarConnectionStatusStore;

    /** Authentication service — receives the connection-status store post-construction */
    private final GoogleCalendarAuthenticationService googleCalendarAuthenticationService;

    /** ESB consumer manager — starts/stops the calendar-reconnect inbound listeners */
    private final GoogleServiceBusManager serviceBusManager;

    /** Holds an HK2 context snapshot for async (webhook/scheduler) threads. */
    private final Hk2ContextHolder hk2ContextHolder;

    /** The discovery seam — primes the registered Meeting Agent roster at load (WP-G1). */
    private final CalendarDiscoveryService calendarDiscoveryService;
    private LiveGoogleCalendarConfiguration liveConfiguration;

    @Inject
    public void setLiveConfiguration(LiveGoogleCalendarConfiguration configuration) {
        this.liveConfiguration = configuration;
    }

    /**
     * Constructs a GoogleCalendarExtension with dependency injection.
     *
     * @param invoker                          invoker providing routing information and invoker ID
     * @param gmailAttributeStore              store for Google authentication attributes
     * @param calendarWatchService             service for managing calendar push notification channels
     * @param calendarConnectionStatusStore    per-user connection status store (WP10)
     * @param googleCalendarAuthenticationService sync authentication service (WP10)
     * @param serviceBusManager                ESB consumer manager for calendar-reconnect (auth-initiate + reminder-send)
     * @param hk2ContextHolder                 holder for the HK2 context snapshot used by async threads
     * @param calendarDiscoveryService         the discovery seam, primed at load (WP-G1)
     */
    @Inject
    public GoogleCalendarExtension(Invoker invoker,
                                   GoogleAttributeStore gmailAttributeStore,
                                   AccountProvider accountProvider,
                                   AuthorizationContext authorizationContext,
                                   CalendarWatchService calendarWatchService,
                                   CalendarConnectionStatusStore calendarConnectionStatusStore,
                                   GoogleCalendarAuthenticationService googleCalendarAuthenticationService,
                                   GoogleProviderFactory googleProviderFactory,
                                   GoogleServiceBusManager serviceBusManager,
                                   UserKeyIndexStore userKeyIndexStore,
                                   Hk2ContextHolder hk2ContextHolder,
                                   CalendarDiscoveryService calendarDiscoveryService,
                                   DwdSetupStore dwdSetupStore) {
        this.requestAuthenticator = new GoogleCalendarAuthenticator(
                gmailAttributeStore,
                invoker.getInvokerId(),
                accountProvider,
                authorizationContext,
                dwdSetupStore,
                invoker.getRoutingInfo().getRoutingURL(
                        app.krista.extension.request.protos.http.HttpProtocol.PROTOCOL_NAME,
                        app.krista.extension.request.RoutingInfo.Type.APPLIANCE));
        this.calendarWatchService = calendarWatchService;
        this.calendarConnectionStatusStore = calendarConnectionStatusStore;
        this.googleCalendarAuthenticationService = googleCalendarAuthenticationService;
        this.serviceBusManager = serviceBusManager;
        this.hk2ContextHolder = hk2ContextHolder;
        this.calendarDiscoveryService = calendarDiscoveryService;

        // Wire the publisher into the status store and dependent resources
        CalendarConnectionStatusEventPublisher publisher = new CalendarConnectionStatusEventPublisher();
        this.calendarConnectionStatusStore.setPublisher(publisher);
        // Reverse email index: lets an auto-detected REAUTH_REQUIRED for an opaque
        // wsContact_<UUID> key recover the user's email so the publish reaches CA.
        this.calendarConnectionStatusStore.setUserKeyIndexStore(userKeyIndexStore);
        // Either/or (owner ruling, 2026-11-10): while Always On is on, the personal-token status
        // writers (OAuth callback, refresh failure, user disconnect) stand down, so a status only
        // ever describes what domain-wide delegation can read. Read live on every write.
        this.calendarConnectionStatusStore.setAlwaysOnMode(calendarDiscoveryService::isAlwaysOn);
        // Always On users do not complete the per-user OAuth callback. The watch service publishes
        // CONNECTED after a delegated read, and DISCONNECTED when a connected mailbox stops being
        // readable.
        this.calendarWatchService.setConnectionStatusStore(calendarConnectionStatusStore);
        this.googleCalendarAuthenticationService.setConnectionStatusStore(calendarConnectionStatusStore);
        // AuthenticationResource gets this same authentication service via its own @Inject
        // constructor (it is a JAX-RS @Path resource and cannot be injected into the extension),
        // so callback registration uses the status store/publisher wired just above.
        // GC-4: every GoogleProvider built by the factory needs the status store so
        // invalid_grant on refresh can flip the row to REAUTH_REQUIRED (spec §F.2).
        googleProviderFactory.setConnectionStatusStore(calendarConnectionStatusStore);

        // Register our proto marshallers onto the ESB's shared ProtoWire serialization
        // context — the one ProtoWire.encode() uses on the publish path. Without this the
        // CalendarConnectionStatusChanged publish fails with "No marshaller registered".
        EsbProtoRegistrar.ensureRegistered();

        LOGGER.info("GoogleCalendarExtension initialized with CalendarConnectionStatus publisher (WP10)");
    }

    /**
     * Gets the request authenticator for OAuth authentication.
     *
     * @return RequestAuthenticator instance
     */
    @InvokerRequest(InvokerRequest.Type.AUTHENTICATOR)
    public RequestAuthenticator getRequestAuthenticator() {
        return requestAuthenticator;
    }

    /**
     * Defines custom tabs for the extension UI.
     *
     * @return map of tab names to their paths
     */
    @InvokerRequest(InvokerRequest.Type.CUSTOM_TABS)
    public Map<String, String> customTabs() {
        return Map.of("Authentication", "rest/googlecalendar/docs/", "Documentation", "static/docs");
    }

    /**
     * Test connection method that redirects users to the Authentication tab.
     *
     * @throws IllegalArgumentException always, to redirect users to Authentication tab
     */
    @InvokerRequest(InvokerRequest.Type.TEST_CONNECTION)
    public void testConnection() throws IOException {
        throw new IllegalArgumentException("Please use Authentication tab for test connection.");
    }

    /**
     * Validates candidate CA service and delegated-access attributes without activating them.
     * A validation request may be for an unsaved draft. The ESB refresh below reads only committed
     * Invoker attributes, never this candidate map, so Studio saves do not require an upgrade.
     *
     * <p>Both attributes are optional on this extension ({@code required = false}), so a blank
     * value is accepted and simply means "no CA discovery" — only a malformed URL is rejected.
     */
    @InvokerRequest(InvokerRequest.Type.VALIDATE_ATTRIBUTES)
    public void validateAttributes(Map<String, Object> attributes) {
        Object rawServiceUrl = attributes == null ? null : attributes.get(Constants.SERVICE_URL);
        String serviceUrl = rawServiceUrl == null ? "" : String.valueOf(rawServiceUrl).trim();

        if (!serviceUrl.isBlank()) {
            try {
                java.net.URI uri = new java.net.URI(serviceUrl);
                if (uri.getScheme() == null || uri.getHost() == null) {
                    throw new IllegalArgumentException(
                            "MA service URL is not a valid absolute URL: " + serviceUrl);
                }
            } catch (java.net.URISyntaxException e) {
                throw new IllegalArgumentException("MA service URL is invalid: " + serviceUrl, e);
            }
        }

        try {
            calendarDiscoveryService.validateDelegatedAccessIfAlwaysOn(attributes);
        } catch (DomainWideDelegationException cause) {
            LOGGER.warn("Google Workspace DWD validation requires administrator action: {}", cause.getMessage());
            throw dwdSetupRequired(attributes, cause);
        }

        try {
            serviceBusManager.reconfigureEsb();
        } catch (Exception e) {
            LOGGER.error("Could not apply committed ESB connection details after attribute validation: {}",
                    e.getMessage(), e);
        }
    }

    private MustAuthorizeException dwdSetupRequired(Map<String, Object> attributes,
                                                    DomainWideDelegationException cause) {
        List<NamedValuedField> details = new ArrayList<>();
        String adminEmail = Constants.readTextAttribute(attributes, Constants.TENANT_ADMIN_EMAIL);
        details.add(new NamedValuedField(Constants.USER_ID, Constants.TEXT,
                adminEmail == null ? "workspace-admin" : adminEmail, new HashMap<>(), new HashMap<>()));
        details.add(new NamedValuedField(Constants.DWD_SETUP_FIELD, Constants.TEXT,
                "true", new HashMap<>(), new HashMap<>()));
        String clientId = Constants.serviceAccountClientId(attributes);
        if (clientId != null) {
            details.add(new NamedValuedField(Constants.DWD_CLIENT_ID_FIELD, Constants.TEXT,
                    clientId, new HashMap<>(), new HashMap<>()));
        }
        return new MustAuthorizeException(Constants.DWD_SETUP_PROMPT + " " + cause.getMessage(), details);
    }

    /**
     * Lifecycle hook called when the extension invoker is loaded in live mode.
     * Initializes calendar sync resources and creates watch channels for push notifications.
     */
    @InvokerRequest(InvokerRequest.Type.INVOKER_LOADED)
    public void invokerLoaded() {
        LOGGER.info("Google Calendar extension loaded. Calendar sync to MA is available.");
        if (liveConfiguration != null) {
            liveConfiguration.clearSaved();
        }

        // Capture before starting consumers: a durable queued registration may be delivered as
        // soon as start() returns (or while it runs), on a thread with no invoker/HK2 context.
        hk2ContextHolder.captureFromCurrentThread();
        serviceBusManager.captureExecutionMode();

        // Start the ESB consumers FIRST: broker startup is ~ms, while
        // subscribeAllUsers() makes slow Google-API calls. Starting ESB first
        // means CA's auth-initiate replies aren't queued behind the watch
        // channel work. Independent try/catch so either failure mode is
        // contained.
        try {
            serviceBusManager.start();
        } catch (Exception e) {
            LOGGER.error("GoogleServiceBusManager failed to start: {}", e.getMessage(), e);
        }

        // WP-G1: read the workspace's registered Meeting Agent users BEFORE the first sweep, since
        // that sweep is what registers watch channels — and under Always On the roster IS the scan
        // set. A no-op while Always On is off (the seam checks the flag, so it stays read in one
        // place), and a failure here is logged by the store and degrades the next sweep, never
        // blocks startup. Thereafter discovery refreshes it on a six-hour TTL.
        try {
            calendarDiscoveryService.primeRegisteredSeats();
        } catch (Exception e) {
            LOGGER.error("Failed to read the registered Meeting Agent users at load: {}",
                    e.getMessage(), e);
        }

        try {
            LOGGER.info("Creating calendar watch channels for push notifications...");
            calendarWatchService.subscribeAllUsers();
            LOGGER.info("Calendar watch channels created successfully.");
        } catch (Exception e) {
            LOGGER.error("Failed to create calendar watch channels: {}", e.getMessage(), e);
        }
        // Start the background scheduler that renews channels before they expire (~5 days),
        // since Google does not auto-renew. Always started, even if initial subscribe failed.
        calendarWatchService.startRenewalScheduler();
    }

    /**
     * Lifecycle hook called when the extension invoker is unloaded.
     * Cleans up calendar sync resources and stops watch channels.
     */
    @InvokerRequest(InvokerRequest.Type.INVOKER_UNLOADED)
    public void invokerUnloaded() {
        LOGGER.info("Google Calendar extension unloading. Stopping watch channels...");
        calendarWatchService.stopRenewalScheduler();
        try {
            calendarWatchService.unsubscribeAllUsers();
            LOGGER.info("Calendar watch channels stopped successfully.");
        } catch (Exception e) {
            LOGGER.error("Failed to stop calendar watch channels: {}", e.getMessage(), e);
        }

        // LIFO of the start order in invokerLoaded. The manager's @PreDestroy
        // makes stop() idempotent, so the HK2 locator's later shutdown will
        // be a no-op.
        try {
            serviceBusManager.stop();
        } catch (Exception e) {
            LOGGER.error("GoogleServiceBusManager failed to stop cleanly: {}", e.getMessage(), e);
        }

        LOGGER.info("Google Calendar extension unloaded. Calendar sync resources released.");
    }
}
