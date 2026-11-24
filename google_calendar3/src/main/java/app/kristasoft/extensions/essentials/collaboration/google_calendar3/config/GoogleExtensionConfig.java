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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.config;

import app.krista.extension.executor.Invoker;
import app.krista.infra.esb.ServiceBusConfig;
import app.krista.ksdk.workspace.Workspace;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.CaContractProtoRegistration;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.config.EsbConnectionDetails;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.config.InvokerDetails;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Provides {@link ServiceBusConfig} instances for each ESB consumer the Google
 * Calendar extension hosts.
 *
 * <p>Mirrors KIP's {@code KipExtensionConfig} for calendar-reconnect, stripped
 * to the two consumers this extension serves. {@code Workspace} is injected only
 * for the optional CA discovery lookup below; the consumers are NOT bound to a
 * fixed tenant. The Google extension is a single multi-tenant instance (one pod
 * serves many workspaces), so:
 *
 * <ul>
 *   <li>{@link ServiceBusConfig.TenantFilterMode#FIXED} is used when this pod HAS a workspace,
 *       which is how the extension is actually deployed — one invoker per workspace, as KIP is
 *       (KIM-460). The claim below that FIXED is "unusable" was wrong.</li>
 *   <li>{@link ServiceBusConfig.TenantFilterMode#ALL} is the correct choice —
 *       the consumer sees every tenant's messages; the handler resolves
 *       {@code workspaceId} from the proto payload per-message
 *       ({@code CalendarAuthInitiateCommandProto} field 1,
 *       {@code CalendarReminderSendProto} field 1).</li>
 * </ul>
 *
 * <p>Queue names are per-system (suffix {@code .google}) so that messages
 * destined for Google never compete with the KIP consumer on a shared queue.
 * CA's outbound notifier parameterizes the queue by {@code CalendarSystem}
 * identically to the existing auth-initiate path.
 *
 * <p><b>ESB connection bootstrap (ported from KIP).</b> Before any JMS
 * connection is attempted this class (a) loads {@code esb/esb_default.properties}
 * into JVM system properties via the static initializer below, and (b) on
 * construction calls {@link #loadEsbDetailsFromApi()} to fetch the broker host
 * from CA and qualify bare service names for cross-namespace DNS
 * ({@code artemis}/{@code activemq-artemis} ->
 * {@code activemq-artemis.<namespace>.svc.cluster.local}). Without this the
 * extension falls back to the bare host {@code artemis:61616}, which does NOT
 * resolve cross-namespace and causes consumers and event publishing to fail.
 *
 * <p><b>Multi-tenant adaptation (differs from KIP).</b> KIP is a per-workspace
 * pod and falls back to {@code localhost} when the API lookup fails. The Google
 * extension is multi-tenant with no fixed per-pod workspaceId and no local
 * broker, so the fallback here is the QUALIFIED cross-namespace FQDN
 * ({@code activemq-artemis.<namespace>.svc.cluster.local:61616} with default
 * credentials) — never {@code localhost}. The FQDN is set regardless of whether
 * the API call succeeds, and a failure never propagates out of the constructor.
 *
 * @since 3.0.3
 */
@Service
public class GoogleExtensionConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleExtensionConfig.class);

    private static final String DEFAULT_USERNAME = "artemis";
    private static final String DEFAULT_PASSWORD = "simetraehcapa";
    private static final int DEFAULT_PORT = 61616;
    private static final String DEFAULT_NAMESPACE = "conversation-agent";

    // CA Service attribute keys. The serviceUrl attribute key and the API-key header
    // are shared with the rest of the module via Constants.
    private static final String CA_SERVICE_API_KEY_KEY = "apiKey";

    /** Bound the CA discovery call so a slow/unreachable CA can't wedge extension load. */
    private static final int CA_HTTP_TIMEOUT_SECONDS = 10;

    // Static initializer to load ESB properties early (same as KIP).
    static {
        loadEsbPropertiesEarly();
    }

    private static void loadEsbPropertiesEarly() {
        try {
            Properties props = new Properties();
            try (InputStream is = GoogleExtensionConfig.class.getClassLoader()
                    .getResourceAsStream("esb/esb_default.properties")) {
                if (is != null) {
                    props.load(is);
                    for (String key : props.stringPropertyNames()) {
                        String value = props.getProperty(key);
                        System.setProperty(key, value);
                    }
                    LOGGER.info("ESB properties loaded early from esb_default.properties");
                }
            }

            // Disable monitoring to avoid SLF4J issues
            System.setProperty("krista.monitor.resource.enabled", "false");
            System.setProperty("krista.monitoring.enabled", "false");
            System.setProperty("krista.pulse.ResourceMonitor-HealthCheck.enabled", "false");
            System.setProperty("krista.diagnostics.enabled", "false");
        } catch (IOException e) {
            LOGGER.error("Failed to load esb_default.properties", e);
        }
    }

    private final Invoker invoker;
    private final Workspace workspace;

    /** @see #getReportedContractsFingerprint() */
    private volatile String reportedContractsFingerprint;

    @Inject
    public GoogleExtensionConfig(Workspace workspace, Invoker invoker) {
        this.workspace = workspace;
        this.invoker = invoker;

        // The staged contract jar's ProtoStream initializers are registered EXPLICITLY, because
        // this module's shaded jar deliberately drops every dependency's META-INF/services file
        // (see the jar task in build.gradle) — so nothing on the jar's own list is discovered at
        // runtime. Idempotent; the constructor runs once per tenant in a multi-tenant pod.
        CaContractProtoRegistration.registerAll();

        // Load ESB details dynamically from CA Service. This must never throw out of
        // the constructor — on any failure we fall back to the qualified FQDN.
        loadEsbDetailsFromApi();
    }

    /**
     * Re-runs the CA handshake and rebinds this JVM's ESB connection properties to whatever it
     * reports, without recreating anything.
     *
     * <p>This is the half of "reconfigure without a restart" that owns the <em>properties</em>;
     * recycling the consumers so they actually dial the new endpoint is
     * {@code GoogleServiceBusManager.reconfigureEsb()}'s job. Split that way because the properties
     * are process-global while the consumers are not, and because the caller needs the
     * changed/unchanged answer to decide whether recycling is warranted at all.
     *
     * <p>Unlike the constructor call, a failed handshake here does NOT fall back to the qualified
     * FQDN: at construction nothing is bound yet and a good guess beats nothing, but once the
     * extension is connected, a CA blip must not knock it off a broker it is happily talking to.
     *
     * @return true when the effective ESB endpoint moved and consumers must be recycled
     */
    public synchronized boolean refreshEsbEndpoint() {
        return refreshEsbEndpoint(fetchInvokerDetails());
    }

    /**
     * Refreshes the ESB endpoint using CA attributes that were just persisted by the Save
     * Configuration catalog request. The request-scoped {@link Invoker} can still expose its old
     * attribute snapshot immediately after {@code InvokerConfiguration.saveChanges}; accepting the
     * saved values directly avoids requiring a container restart before the CA handshake can run.
     */
    public synchronized boolean refreshEsbEndpoint(String caServiceUrl, String apiKey) {
        return refreshEsbEndpoint(fetchInvokerDetails(caServiceUrl, apiKey));
    }

    private boolean refreshEsbEndpoint(InvokerDetails details) {
        try {
            if (details == null) {
                LOGGER.warn("ESB endpoint refresh: no invoker details from CA — keeping the current binding.");
                return false;
            }
            EsbConnectionDetails esb = details.getEsbDetails();
            if (esb == null) {
                LOGGER.warn("ESB endpoint refresh: no ESB details in invoker metadata — keeping the current binding.");
                return false;
            }
            return applyEsbDetails(esb);
        } catch (Exception e) {
            LOGGER.error("ESB endpoint refresh failed; keeping the current binding", e);
            return false;
        }
    }

    /**
     * Destination base name for the calendar auth-initiate consumer, KIM-492 WP-E15 standard
     * name (spec §3.3 rename row 1). The {@code krista.*} family does not conform to the
     * five-family naming standard; an extension is the consumer here, so the family is
     * {@code ext.*}.
     */
    public static final String QUEUE_CALENDAR_AUTH_INITIATE = "ext.calendar.auth.initiate.google";

    /**
     * The PRE-RENAME destination for the same consumer, bound alongside the one above through
     * Stage 2. A rename is a new destination, not a migration: MA keeps producing to the old name
     * until Stage 3 (WP-E20), so an extension listening only on the new name would go deaf to
     * every MA that has not moved yet — and it would go deaf silently, which is exactly what
     * spec §6 R10 exists to prevent.
     */
    public static final String QUEUE_CALENDAR_AUTH_INITIATE_LEGACY = "krista.calendar.auth.initiate.google";

    /** Destination base name for the reminder-send consumer. Suffixed per calendar system. */
    public static final String QUEUE_CALENDAR_REMINDER_SEND = "krista.calendar.reminder.send.google";

    /**
     * Destination base name for the KIM-492 WP-E16 reminder-DECIDE consumer (spec §3.10). An
     * extension is the consumer, so the family is {@code ext.*}.
     */
    public static final String QUEUE_CALENDAR_REMINDER_DECIDE = "ext.calendar.reminder.decide.google";

    /**
     * KIM-474 / EF-14: Meeting Agent's licensed-seat registration notification, dispatched per BOUND
     * calendar system — so a Google-bound workspace receives it here and not on the {@code .microsoft}
     * sibling. {@code ext.*} because this extension is the consumer (spec §3.3).
     */
    public static final String QUEUE_CALENDAR_SEAT_REGISTERED = "ext.calendar.seat.registered.google";

    /**
     * Meeting Agent's nightly full-tenant calendar sweep request (KIM-492).
     *
     * <p>Replaces the daily Adaptive Calendar Sync a Krista conversation used to drive. Always On
     * removes the conversations and took that sweep with it; nothing in this extension runs a full
     * sweep on a timer, so this consumer is the only reconciliation floor under the watch-channel
     * path.
     */
    public static final String QUEUE_CALENDAR_DAILY_SYNC = "ext.calendar.sync.daily.google";

    /**
     * KIM-492: Meeting Agent's in-call messages broadcast (the standard Krista welcome and the
     * customer's compliance notice), dispatched per BOUND calendar system — so a Google-bound
     * workspace receives it here and not on the {@code .microsoft} sibling. {@code ext.*} because this
     * extension is the consumer (spec §3.3).
     */
    public static final String QUEUE_CALENDAR_WORKSPACE_MESSAGES =
            "ext.calendar.workspace.messages.google";

    /**
     * Inbound consumer for {@code CalendarAuthInitiateCommandProto} on the KIM-492 standard
     * destination {@code ext.calendar.auth.initiate.google}. MA dispatches as a sync
     * request/reply (≤10 s timeout on MA's side).
     */
    public ServiceBusConfig getCalendarAuthInitiateConfig() {
        return buildQueueConfig(QUEUE_CALENDAR_AUTH_INITIATE);
    }

    /**
     * Inbound consumer for the SAME command on the pre-rename destination
     * {@code krista.calendar.auth.initiate.google}, bound with the same handler instance through
     * Stage 2. Binding a second destination carries no hazard of its own; not binding it would
     * make this extension deaf to an MA that has not yet moved its producer.
     */
    public ServiceBusConfig getCalendarAuthInitiateLegacyConfig() {
        return buildQueueConfig(QUEUE_CALENDAR_AUTH_INITIATE_LEGACY);
    }

    /**
     * Inbound consumer for {@link app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.CalendarReminderSendProto}.
     * MA dispatches to {@code krista.calendar.reminder.send.google} as a
     * fire-and-forget command (no reply queue).
     */
    public ServiceBusConfig getCalendarReminderSendConfig() {
        return buildQueueConfig(QUEUE_CALENDAR_REMINDER_SEND);
    }

    /**
     * Inbound one-way consumer for {@code ext.calendar.seat.registered.google}
     * (KIM-474 / EF-14). Meeting Agent notifies that a licensed Meeting Agent seat was registered or
     * reactivated; this extension refreshes its seat roster and reconciles its watch scope.
     *
     * <p>The shared builder may return ALL when no workspace resolves. GoogleServiceBusManager
     * refuses that config before binding: ignoring a foreign message AFTER consumption would
     * acknowledge and destroy that workspace's one-shot back-fill request.
     *
     * @return the tenant-scoped config
     */
    public ServiceBusConfig getCalendarSeatRegisteredConfig() {
        return buildQueueConfig(QUEUE_CALENDAR_SEAT_REGISTERED);
    }

    /**
     * Inbound one-way consumer for {@code ext.calendar.sync.daily.google} (KIM-492).
     *
     * <p>GoogleServiceBusManager permits binding only in LIVE mode with FIXED, nonblank workspace
     * scoping. An unscoped shared-queue consumer would steal and discard other tenants' requests.
     *
     * @return the tenant-scoped config
     */
    public ServiceBusConfig getCalendarDailySyncConfig() {
        return buildQueueConfig(QUEUE_CALENDAR_DAILY_SYNC);
    }

    /**
     * Inbound one-way consumer for {@code ext.calendar.workspace.messages.google} (KIM-492). Meeting
     * Agent broadcasts the workspace's two in-call messages; this extension stores them.
     *
     * <p>Goes through {@link #buildQueueConfig}, so it degrades to {@code TenantFilterMode.ALL} with a
     * WARN on a pod that resolves no workspace — the same choice as the seat-registered consumer, and
     * for the same reason: the handler re-reads {@code workspaceId} from every broadcast and IGNORES
     * anything that is not its own, so an unscoped binding costs a log line rather than a wrong answer.
     * The reminder-decide consumer cannot do that and therefore refuses instead.
     *
     * @return the tenant-scoped config
     */
    public ServiceBusConfig getCalendarWorkspaceMessagesConfig() {
        return buildQueueConfig(QUEUE_CALENDAR_WORKSPACE_MESSAGES);
    }

    /**
     * Inbound request/reply consumer for {@code ext.calendar.reminder.decide.google}
     * (KIM-492 WP-E16, spec §3.10). Meeting Agent asks whether to send a calendar-reconnect
     * reminder; this extension answers and MA sends.
     *
     * <p><b>This one config REFUSES to fall back to {@code TenantFilterMode.ALL}</b>, and returns
     * {@code null} when this pod has no workspace — spec §6 R11. {@link #applyTenantScope} degrades
     * to ALL with a WARN, which is the right call for the two long-standing consumers whose
     * handlers re-read {@code workspaceId} from every payload; it is the wrong call here. ALL means
     * the broker stops filtering and this consumer answers <em>other tenants'</em> reminder
     * questions — consuming their rate-limit slots and returning DEDUPED verdicts keyed on this
     * pod's memory, so a tenant silently stops being nudged about a calendar that is genuinely
     * broken. A consumer that never starts is visible in the first line of the log; one that starts
     * unscoped is not visible at all.
     *
     * @return the tenant-pinned config, or {@code null} when this pod resolves no workspace and the
     *         consumer must not start
     */
    public ServiceBusConfig getCalendarReminderDecideConfig() {
        String workspaceId = resolveWorkspaceId();
        if (workspaceId == null) {
            LOGGER.error("Refusing to bind the calendar reminder-decide consumer on {}: this pod"
                            + " resolves no workspace id, so the consumer could only run at"
                            + " TenantFilterMode.ALL and would answer other tenants' reminder"
                            + " questions. Configure this invoker's workspace and save the"
                            + " attributes to bind it.", QUEUE_CALENDAR_REMINDER_DECIDE);
            return null;
        }
        return new ServiceBusConfig()
                .setDestinationBaseName(QUEUE_CALENDAR_REMINDER_DECIDE)
                .setVersion(1)
                .setDestinationType(ServiceBusConfig.DestinationType.Queue)
                .setTenantFilterMode(ServiceBusConfig.TenantFilterMode.FIXED)
                .setFixedTenantId(workspaceId);
    }

    /**
     * Builds a tenant-scoped inbound queue config from a destination CONSTANT.
     *
     * <p>Every consumer config goes through here rather than repeating its destination as a
     * literal at the call site. Repeating the literal is exactly what let KIP's two destination
     * constants drift away from the names its consumers actually bound (spec §3.3), and an
     * extension bound to a destination nobody produces to fails silently — it simply never
     * receives anything.
     *
     * @param destinationBaseName one of this class's {@code QUEUE_*} constants
     * @return the tenant-scoped config
     */
    private ServiceBusConfig buildQueueConfig(String destinationBaseName) {
        ServiceBusConfig config = new ServiceBusConfig()
                .setDestinationBaseName(destinationBaseName)
                .setVersion(1)
                .setDestinationType(ServiceBusConfig.DestinationType.Queue);
        return applyTenantScope(config);
    }

    /**
     * The {@code contractsFingerprint} the last successful MA bootstrap reported, or {@code null}
     * when MA reported none (it predates KIM-492 WP-E2) or the bootstrap did not complete.
     *
     * <p>Read once, at construction, from the same invoker element the ESB connection details come
     * from — there is no second call. {@code null} is UNVERIFIED, never a mismatch; see
     * {@link app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.ContractHandshake}.
     *
     * @return MA's reported wire-contract fingerprint, or {@code null}
     */
    public String getReportedContractsFingerprint() {
        return reportedContractsFingerprint;
    }

    // ------------------------------------------------------------------
    // ESB connection bootstrap (ported from KIP, multi-tenant adaptation)
    // ------------------------------------------------------------------

    /**
     * Resolve the workspaceId for the CA discovery call. The Google extension is
     * multi-tenant, so this may be absent: if {@link Workspace} is unavailable or
     * {@code getWorkspaceId()} is null/blank, the workspaceId is treated as
     * absent and the qualified-FQDN fallback is used.
     */
    private String resolveWorkspaceId() {
        if (workspace == null) {
            return null;
        }
        try {
            String id = workspace.getWorkspaceId();
            return (id != null && !id.isBlank()) ? id : null;
        } catch (RuntimeException e) {
            LOGGER.warn("Unable to read workspaceId from Workspace; treating as absent", e);
            return null;
        }
    }

    /**
     * Resolve the Artemis namespace for cross-namespace DNS qualification.
     * Priority: env {@code ARTEMIS_NAMESPACE}, sysprop {@code artemis.namespace},
     * default {@code conversation-agent}.
     */
    private static String resolveArtemisNamespace() {
        return System.getenv().getOrDefault("ARTEMIS_NAMESPACE",
                System.getProperty("artemis.namespace", DEFAULT_NAMESPACE));
    }

    /**
     * Qualify a bare Artemis service name to a cross-namespace FQDN.
     *
     * <p>{@code artemis}/{@code activemq-artemis} -> {@code activemq-artemis.<ns>.svc.cluster.local};
     * {@code artemis-service} -> {@code artemis-service.<ns>.svc.cluster.local};
     * any other dot-less, non-IP host -> {@code <host>.<ns>.svc.cluster.local}.
     * Hosts that already contain {@code .svc.cluster.local} are returned as-is.
     */
    private static String qualifyHost(String host) {
        if (host == null || host.contains(".svc.cluster.local")) {
            return host;
        }
        String namespace = resolveArtemisNamespace();
        String originalHost = host;
        String qualified;
        if ("activemq-artemis".equals(host) || "artemis".equals(host)) {
            qualified = "activemq-artemis." + namespace + ".svc.cluster.local";
        } else if ("artemis-service".equals(host)) {
            qualified = "artemis-service." + namespace + ".svc.cluster.local";
        } else if (!host.contains(".") && !host.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
            qualified = host + "." + namespace + ".svc.cluster.local";
        } else {
            // Already qualified-looking or an IP address — leave untouched.
            return host;
        }
        LOGGER.info("Qualified ESB host for cross-namespace resolution: {} -> {} (namespace: {})",
                originalHost, qualified, namespace);
        return qualified;
    }

    /**
     * Apply the robust, connection-stabilizing broker system properties shared by
     * both the API-success and the fallback paths.
     *
     * @return true when the endpoint differs from the one already bound, i.e. running consumers
     *         must be recycled for it to take effect
     */
    private static boolean applyBrokerSystemProperties(String host, int port, String namespace,
            String user, String pass) {
        // Connection timeout to prevent hanging when broker is unreachable (default 30s).
        String callTimeoutMs = System.getenv().getOrDefault("ARTEMIS_CONNECTION_TIMEOUT_MS",
                System.getProperty("artemis.connection.timeout.ms", "30000"));
        System.setProperty("broker.connection.call.timeout.ms", callTimeoutMs);

        // Robust connection: infinite reconnects to prevent silent death of consumers.
        System.setProperty("broker.reconnect.attempts", "-1");

        // These two are extension constants, not handshake output, so they are set unconditionally
        // and stay outside the changed/unchanged signal below.
        boolean changed = EsbEndpointBinding.apply(
                new EsbEndpointBinding.Endpoint(host, port, namespace, user, pass));

        LOGGER.info("Configured ESB broker system properties: host={}, port={}, reconnect=Infinite, callTimeoutMs={}",
                host, port, callTimeoutMs);
        return changed;
    }

    /**
     * Set the qualified-FQDN fallback. Unlike KIP (which falls back to
     * {@code localhost}), the Google extension has no local broker, so the
     * fallback host is the cross-namespace FQDN
     * {@code activemq-artemis.<namespace>.svc.cluster.local} on the default port
     * with the default credentials. This guarantees the cross-namespace FQDN is
     * always set, even when the CA API lookup is skipped or fails.
     *
     * <p>Bootstrap-only: {@link #refreshEsbEndpoint()} deliberately does not use this. Guessing is
     * the right move when nothing is bound yet, and the wrong one once consumers are connected.
     */
    private void applyQualifiedFallback(String reason) {
        String namespace = resolveArtemisNamespace();
        String host = "activemq-artemis." + namespace + ".svc.cluster.local";
        LOGGER.warn("Using qualified-FQDN ESB fallback ({}): host={}, port={}", reason, host, DEFAULT_PORT);
        applyBrokerSystemProperties(host, DEFAULT_PORT, null, DEFAULT_USERNAME, DEFAULT_PASSWORD);
    }

    /**
     * Binds the endpoint the CA handshake reported: host qualified for cross-namespace DNS,
     * credentials from the container environment, and the optional KIM-451 destination namespace
     * (null/blank leaves destination naming untouched — behavior unchanged from before).
     *
     * @return true when the endpoint moved and running consumers must be recycled
     */
    private boolean applyEsbDetails(EsbConnectionDetails esb) {
        String host = qualifyHost(esb.getHostOrDefault());
        int port = esb.getPortOrDefault();

        LOGGER.info("Applying ESB details from CA API. host={}, port={}", host, port);

        String esbUser = System.getenv().getOrDefault("KRISTA_ESB_USERNAME", DEFAULT_USERNAME);
        String esbPass = System.getenv().getOrDefault("KRISTA_ESB_PASSWORD", DEFAULT_PASSWORD);

        return applyBrokerSystemProperties(host, port, esb.getEsbNamespaceOrNull(), esbUser, esbPass);
    }

    private void loadEsbDetailsFromApi() {
        try {
            InvokerDetails details = fetchInvokerDetails();
            if (details == null) {
                applyQualifiedFallback("no invoker details from CA");
                return;
            }

            EsbConnectionDetails esb = details.getEsbDetails();
            if (esb == null) {
                applyQualifiedFallback("no ESB details in invoker metadata");
                return;
            }

            applyEsbDetails(esb);
        } catch (Exception e) {
            // Never let a bootstrap failure escape the constructor.
            LOGGER.error("Failed to load ESB details from CA API; falling back to qualified FQDN", e);
            applyQualifiedFallback("exception during ESB bootstrap");
        }
    }

    private static HttpRequest buildInvokerRequest(String url, String apiKey, String workspaceId) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(CA_HTTP_TIMEOUT_SECONDS))
                .header(Constants.MA_API_KEY_HEADER, apiKey)
                // KIM-457: CA's WorkspaceAuthorizationFilter rejects any PRODUCTION/TRIAL-tier
                // request without this header, BEFORE the resource runs. The caller already puts
                // the workspaceId in the query string, but that filter reads the HEADER only —
                // so without this the handshake 400s, no ESB details come back, and the extension
                // silently falls back to its localhost broker default.
                .header(Constants.WORKSPACE_ID_HEADER, workspaceId)
                .GET()
                .build();
    }

    private InvokerDetails fetchInvokerDetails() {
        try {
            String caServiceUrl = (String) invoker.getAttributes().get(Constants.SERVICE_URL);
            if (caServiceUrl == null) {
                LOGGER.error("CA service URL ({}) not found in invoker attributes", Constants.SERVICE_URL);
                return null;
            }
            String apiKey = Constants.resolveMaApiKey(invoker);
            if (apiKey.isEmpty()) {
                LOGGER.error("No MA API key configured (invoker attribute '{}' or CA_API_KEY env);"
                        + " the invoker lookup will be rejected", Constants.SERVICE_API_KEY);
                return null;
            }

            return fetchInvokerDetails(caServiceUrl, apiKey);
        } catch (Exception e) {
            LOGGER.error("Error reading CA connection attributes", e);
            return null;
        }
    }

    private InvokerDetails fetchInvokerDetails(String caServiceUrl, String apiKey) {
        try {
            String workspaceId = resolveWorkspaceId();
            if (workspaceId == null) {
                LOGGER.warn("No workspaceId available (multi-tenant pod); skipping CA invoker lookup");
                return null;
            }
            if (caServiceUrl == null || caServiceUrl.isBlank()) {
                LOGGER.error("CA service URL ({}) is blank", Constants.SERVICE_URL);
                return null;
            }
            if (apiKey == null || apiKey.isBlank()) {
                LOGGER.error("MA API key is blank; the invoker lookup will be rejected");
                return null;
            }

            String url = caServiceUrl + "/api/v1/extensions/invokers?workspaceId=" + workspaceId;
            LOGGER.info("Fetching invoker details from {}", url);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(CA_HTTP_TIMEOUT_SECONDS))
                    .build();
            HttpResponse<String> response = client.send(buildInvokerRequest(url, apiKey, workspaceId),
                    HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            if (response.statusCode() != 200) {
                LOGGER.error("Failed to fetch invoker details. Status: {}, Response: {}", response.statusCode(), body);
                return null;
            }

            Gson gson = new GsonBuilder().create();
            Type listType = new TypeToken<List<InvokerDetails>>() {
            }.getType();
            List<InvokerDetails> invokers = gson.fromJson(body, listType);

            if (invokers == null || invokers.isEmpty()) {
                LOGGER.error("No invoker details found in CA response");
                return null;
            }

            String currentInvokerId = invoker.getInvokerId();
            InvokerDetails matched = invokers.stream()
                    .filter(i -> currentInvokerId != null && currentInvokerId.equals(i.getId()))
                    .findFirst()
                    .orElse(invokers.get(0));

            // Captured from the SAME element the ESB details come from, so the fingerprint and the
            // broker binding always describe one MA. Absent on any MA older than WP-E2, which is
            // UNVERIFIED rather than a mismatch.
            this.reportedContractsFingerprint = matched.getContractsFingerprint();

            LOGGER.info("Found invoker details: {}", matched);
            return matched;
        } catch (Exception e) {
            LOGGER.error("Error fetching invoker details", e);
            return null;
        }
    }

    /**
     * KIM-460: bind an inbound consumer to THIS pod's workspace when there is one.
     *
     * <p>Both consumers used {@code TenantFilterMode.ALL} on the stated grounds that this
     * extension is "a single multi-tenant instance (one pod serves many workspaces)" and so has no
     * workspaceId to bind to. Deployment does not match that: production runs ONE Google invoker
     * bound to ONE workspace, exactly like KIP, which uses {@code FIXED} with
     * {@code setFixedTenantId}. The comment and the config had drifted from reality.
     *
     * <p>{@code ALL} means the consumer receives EVERY tenant's messages and filters them in the
     * handler — isolation by convention rather than by the broker. {@code FIXED} makes Artemis do
     * it, which is both stronger and cheaper.
     *
     * <p>Falls back to {@code ALL} when no workspace resolves, so a genuinely multi-tenant
     * deployment (if one ever exists) keeps working rather than silently binding to a null tenant
     * and receiving nothing. The fallback is WARN-logged — it should never fire in the deployments
     * we run, and if it does that is worth knowing.
     */
    private ServiceBusConfig applyTenantScope(ServiceBusConfig config) {
        String workspaceId = resolveWorkspaceId();
        if (workspaceId == null) {
            LOGGER.warn("No workspaceId for this pod — ESB consumers fall back to TenantFilterMode.ALL "
                    + "(every tenant's messages, filtered in the handler). Expected only on a "
                    + "multi-tenant deployment.");
            return config.setTenantFilterMode(ServiceBusConfig.TenantFilterMode.ALL);
        }
        return config.setTenantFilterMode(ServiceBusConfig.TenantFilterMode.FIXED)
                .setFixedTenantId(workspaceId);
    }
}
