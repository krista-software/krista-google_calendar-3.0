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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service;

import app.krista.ksdk.telemetry.TelemetryMetrics;
import app.krista.ksdk.context.ExecutionMode;
import app.krista.ksdk.context.RequestContext;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.GoogleExtensionConfig;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.ContractHandshake;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarAuthInitiateHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarAuthInitiateServiceDefinition;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarReminderDecideHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarReminderDecideServiceDefinition;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarReminderSendHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarDailySyncHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarDailySyncServiceDefinition;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarSeatRegisteredHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarSeatRegisteredServiceDefinition;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarWorkspaceMessagesHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarWorkspaceMessagesServiceDefinition;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarReminderSendServiceDefinition;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarConnectionReconciliationService;
import app.krista.infra.esb.ServiceBusConfig;
import app.krista.infra.esb.ServiceIdentity;
import app.krista.infra.esb.resilience.JmsConnectionFactory;
import app.krista.infra.esb.service.ServiceDefinition;
import app.krista.infra.esb.service.ServiceEndpoint;
import app.krista.infra.esb.service.ServiceImpl;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts and stops the Google Calendar extension's calendar-reconnect ESB
 * consumers.
 *
 * <p>Mirrors KIP's {@code KipServiceBusManager} but omits the recovery layer
 * ({@code ConsumerThreadMonitor} / {@code ContextSnapshotHolder} /
 * {@code ContextRestoringServiceDefinition}) — those are KIP-private under
 * {@code teams-transcribe/}, and a cross-extension dependency is unsound.
 * Restart-on-pod-restart is the fallback for a wedged consumer.
 *
 * @since 3.0.3
 */
@Service
@Singleton
public class GoogleServiceBusManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleServiceBusManager.class);

    /** Baseline for OAuth/message consumers. LIVE readiness additionally requires BOTH sync queues. */
    private static final int MIN_CONSUMER_COUNT = 3;
    private static final String SEAT_CONSUMER = "GoogleCalendarSeatRegisteredConsumer";
    private static final String DAILY_CONSUMER = "GoogleCalendarDailySyncConsumer";

    /**
     * Metric name emitted once per start when the contract handshake refuses. Deliberately a
     * counter and not only a log line: a refusal leaves an extension that looks alive (it
     * bootstrapped and registered) but consumes nothing, and that is not a state anyone will find
     * by reading logs they had no reason to open.
     */
    private static final String METRIC_CONTRACT_HANDSHAKE = "google_calendar.esb.contract_handshake";

    private final GoogleExtensionConfig extensionConfig;
    private final GoogleCalendarAuthInitiateHandler authInitiateHandler;
    private final GoogleCalendarReminderSendHandler reminderSendHandler;
    private final GoogleCalendarReminderDecideHandler reminderDecideHandler;
    private final GoogleCalendarSeatRegisteredHandler seatRegisteredHandler;
    private final GoogleCalendarDailySyncHandler dailySyncHandler;
    private final GoogleCalendarWorkspaceMessagesHandler workspaceMessagesHandler;
    private final CalendarConnectionReconciliationService connectionReconciliationService;
    private final TelemetryMetrics telemetryMetrics;
    private final RequestContext requestContext;
    // Trusted invoker lifecycle state, not a value from an ESB payload. Rebinds on background
    // threads must not need to dereference a ThreadLocalProxy to rediscover this invoker's stage.
    private volatile ExecutionMode executionMode;

    private final Map<String, ServiceImpl> services = new ConcurrentHashMap<>();
    /**
     * The {@link ServiceBusConfig} each consumer was actually started with, in start order.
     *
     * <p>{@code GoogleExtensionConfig} builds a fresh config object per call, so a rebind must
     * evict the connections belonging to the objects that are live right now — asking the config
     * for new ones would produce different instances and, after the properties move, a different
     * cache key. Kept insertion-ordered so a restart replays the original start order.
     */
    private final Map<String, ServiceBusConfig> startedConfigs = new LinkedHashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    @Inject
    public GoogleServiceBusManager(GoogleExtensionConfig extensionConfig,
                                    GoogleCalendarAuthInitiateHandler authInitiateHandler,
                                    GoogleCalendarReminderSendHandler reminderSendHandler,
                                    GoogleCalendarReminderDecideHandler reminderDecideHandler,
                                    GoogleCalendarSeatRegisteredHandler seatRegisteredHandler,
                                    GoogleCalendarDailySyncHandler dailySyncHandler,
                                    GoogleCalendarWorkspaceMessagesHandler workspaceMessagesHandler,
                                    CalendarConnectionReconciliationService connectionReconciliationService,
                                    TelemetryMetrics telemetryMetrics,
                                    RequestContext requestContext) {
        this.extensionConfig = extensionConfig;
        this.authInitiateHandler = authInitiateHandler;
        this.reminderSendHandler = reminderSendHandler;
        this.reminderDecideHandler = reminderDecideHandler;
        this.seatRegisteredHandler = seatRegisteredHandler;
        this.dailySyncHandler = dailySyncHandler;
        this.workspaceMessagesHandler = workspaceMessagesHandler;
        this.connectionReconciliationService = connectionReconciliationService;
        this.telemetryMetrics = telemetryMetrics;
        this.requestContext = requestContext;
    }

    /** Standalone callers without a platform stage cannot bind live sync queues. */
    public GoogleServiceBusManager(GoogleExtensionConfig extensionConfig,
                                    GoogleCalendarAuthInitiateHandler authInitiateHandler,
                                    GoogleCalendarReminderSendHandler reminderSendHandler,
                                    GoogleCalendarReminderDecideHandler reminderDecideHandler,
                                    GoogleCalendarSeatRegisteredHandler seatRegisteredHandler,
                                    GoogleCalendarDailySyncHandler dailySyncHandler,
                                    GoogleCalendarWorkspaceMessagesHandler workspaceMessagesHandler,
                                    CalendarConnectionReconciliationService connectionReconciliationService,
                                    TelemetryMetrics telemetryMetrics) {
        this(extensionConfig, authInitiateHandler, reminderSendHandler, reminderDecideHandler,
                seatRegisteredHandler, dailySyncHandler, workspaceMessagesHandler,
                connectionReconciliationService, telemetryMetrics, null);
    }

    /**
     * Start both ESB consumers. Safe to call once per extension load; a
     * subsequent call will reopen the brokers only if {@link #stop()} ran in
     * between (the {@code services} map is the source of truth).
     */
    public synchronized void start() {
        LOGGER.info("Starting GoogleServiceBusManager...");
        resolveExecutionModeIfNeeded();
        // Re-arm the idempotence latch so a stop/start cycle — which is what a rebind is — can
        // actually stop again afterwards. Without this, the first stop() would permanently
        // disarm shutdown and leave the second generation of consumers running.
        stopped.set(false);

        if (!contractHandshakePasses()) {
            return;
        }

        // Calendar auth-initiate is bound on BOTH the KIM-492 standard destination and the
        // pre-rename one, with the SAME handler instance. A rename is a new destination, not a
        // migration: MA keeps producing to the old name through Stage 2, so an extension that only
        // listened on the new one would go deaf to every MA that has not moved yet — and it would
        // go deaf silently, which is exactly what spec §6 R10 exists to prevent. Binding a second
        // destination carries no hazard of its own. Stage 3 (WP-E20) deletes the legacy binding
        // once MA's producer for it is gone.
        startConsumer("GoogleCalendarAuthInitiateConsumer",
                GoogleCalendarAuthInitiateServiceDefinition.identity(),
                new GoogleCalendarAuthInitiateServiceDefinition(),
                extensionConfig.getCalendarAuthInitiateConfig(),
                authInitiateHandler);

        startConsumer("GoogleCalendarAuthInitiateLegacyConsumer",
                GoogleCalendarAuthInitiateServiceDefinition.identity(),
                new GoogleCalendarAuthInitiateServiceDefinition(),
                extensionConfig.getCalendarAuthInitiateLegacyConfig(),
                authInitiateHandler);

        startReminderConsumers();

        // KIM-474 / EF-14: Meeting Agent has been producing licensed-seat notifications to
        // ext.calendar.seat.registered.google with nothing consuming them. The queue is durable, so
        // they accumulated on the broker, and a newly-licensed Google user was invisible to this
        // extension until the next scheduled reconcile.
        startConsumer("GoogleCalendarSeatRegisteredConsumer",
                GoogleCalendarSeatRegisteredServiceDefinition.identity(),
                new GoogleCalendarSeatRegisteredServiceDefinition(),
                extensionConfig.getCalendarSeatRegisteredConfig(),
                seatRegisteredHandler);

        // KIM-492: Meeting Agent's nightly full-tenant calendar sweep, replacing the daily Adaptive
        // Calendar Sync a Krista conversation used to drive. Without a consumer here nothing sweeps
        // at all — the seat reconcile and watch renewal only create MISSING channels.
        startConsumer("GoogleCalendarDailySyncConsumer",
                GoogleCalendarDailySyncServiceDefinition.identity(),
                new GoogleCalendarDailySyncServiceDefinition(),
                extensionConfig.getCalendarDailySyncConfig(),
                dailySyncHandler);

        startWorkspaceMessagesConsumer();

        LOGGER.info("GoogleServiceBusManager started: {} consumer(s) running.", services.size());
        reconcileConnectionStatusesIfReady();
    }

    /**
     * Compares the wire contract this build was compiled against with the one Meeting Agent
     * reported at bootstrap, and decides whether the ESB consumers may start at all.
     *
     * <p>Only a genuine MISMATCH refuses. An unverifiable handshake — an older MA that reports no
     * fingerprint, a bootstrap that did not complete, a jar with no staged fingerprint — starts
     * the consumers and says so at WARN. The asymmetry is deliberate: refusing on a mismatch
     * prevents silently wrong data (proto3 has no field presence), whereas refusing on the
     * ABSENCE of a signal would take a working extension offline every time MA had a blip.
     *
     * <p>See {@link ContractHandshake} for the full reasoning.
     *
     * @return true when consumers may start
     */
    boolean contractHandshakePasses() {
        String staged = ContractHandshake.stagedFingerprint();
        String reported = extensionConfig.getReportedContractsFingerprint();
        ContractHandshake.Verdict verdict = ContractHandshake.compare(reported, staged);

        switch (verdict) {
            case MATCH -> {
                LOGGER.info("Contract handshake OK — Meeting Agent and this extension share wire"
                        + " contract {}.", staged);
                recordHandshake("match");
                return true;
            }
            case MISMATCH -> {
                LOGGER.error(ContractHandshake.mismatchMessage(reported, staged));
                recordHandshake("mismatch");
                return false;
            }
            default -> {
                LOGGER.warn("Contract handshake UNVERIFIED — Meeting Agent reported"
                        + " contractsFingerprint={}, this build has {}. Starting consumers anyway:"
                        + " an MA that predates KIM-492 WP-E2 reports no fingerprint, and the"
                        + " ABSENCE of a signal is not evidence of drift. Only a reported"
                        + " fingerprint that DIFFERS from ours refuses to start.", reported, staged);
                recordHandshake("unverified");
                return true;
            }
        }
    }

    /**
     * Best-effort handshake counter. Telemetry must never be the reason an extension fails to
     * start, so every failure here is swallowed with a WARN — the log line above already carries
     * the decision.
     */
    private void recordHandshake(String outcome) {
        if (telemetryMetrics == null) {
            return;
        }
        try {
            telemetryMetrics.incrementCounter(METRIC_CONTRACT_HANDSHAKE + "." + outcome, 1L);
        }
        catch (Exception e) {
            LOGGER.warn("Failed to record contract-handshake telemetry: {}", e.getMessage());
        }
    }

    /**
     * Re-runs the CA handshake and, if the broker moved, reconnects both consumers to the new
     * endpoint — in place, with no container restart.
     *
     * <p>This is what removes the "upgrade extension" step that previously followed configuring
     * the extension: saving attributes is what lets CA answer the handshake, and recycling the
     * container was the only way to make a fresh {@code GoogleExtensionConfig} pick the answer up.
     * It works just as well the second and third time — when a broker is moved, the extension
     * re-fetches and re-dials rather than sitting on a dead endpoint.
     *
     * <p>Consumers are only recycled when the endpoint actually <em>moved</em>. Saving attributes
     * that did not touch the broker must not churn live JMS connections — that would drop in-flight
     * work for no reason.
     *
     * <p>Old connections are evicted <em>before</em> the properties change, because the connection
     * cache is keyed by host:port: once the properties move, the previous connection can no longer
     * be addressed by key and would linger with a health watcher pinging a broker nobody uses.
     *
     * @return true when consumers were reconnected to a new endpoint
     */
    public synchronized boolean reconfigureEsb() {
        return reconfigureEsb(extensionConfig.refreshEsbEndpoint());
    }

    /**
     * Reconfigures ESB using CA attributes that were just saved by the catalog configuration flow.
     */
    public synchronized boolean reconfigureEsb(String caServiceUrl, String apiKey) {
        return reconfigureEsb(extensionConfig.refreshEsbEndpoint(caServiceUrl, apiKey));
    }

    private boolean reconfigureEsb(boolean endpointChanged) {
        resolveExecutionModeIfNeeded();
        if (!consumersReady()) {
            LOGGER.warn("ESB consumers are incomplete ({} running, baseline {}, stage {}) — restarting against the {} endpoint.",
                    services.size(), MIN_CONSUMER_COUNT, executionMode, endpointChanged ? "refreshed" : "current");
            if (endpointChanged) {
                evictStaleBrokerConnections();
            }
            restartConsumers();
            boolean recovered = consumersReady();
            if (recovered) {
                reconcileConnectionStatusesIfReady();
            }
            return recovered;
        }

        if (!endpointChanged) {
            LOGGER.info("ESB endpoint refresh: no change — consumers left connected.");
            // The broker may have been healthy while a previous CONNECTED event was lost. A save
            // is an explicit, safe opportunity to reconcile durable auth state even without a
            // broker move.
            reconcileConnectionStatusesIfReady();
            return false;
        }

        LOGGER.info("ESB endpoint changed — reconnecting {} consumer(s) to the new broker.", services.size());
        evictStaleBrokerConnections();
        restartConsumers();
        reconcileConnectionStatusesIfReady();
        return true;
    }

    /**
     * Replays durable Google authentication state only after both ESB consumers have connected.
     * Best-effort: calendar sync/watch startup must remain available even if CA rejects a status
     * event or one user's identity cannot be resolved.
     */
    private void reconcileConnectionStatusesIfReady() {
        if (!consumersReady()) {
            LOGGER.warn("Skipping calendar connection reconciliation — consumers incomplete: stage={}, running={}.",
                    executionMode, services.keySet());
            recordSyncMetric("not_ready");
            return;
        }
        try {
            connectionReconciliationService.reassertConnectedUsers();
        } catch (Exception e) {
            LOGGER.error("Failed to reconcile authenticated calendar users with CA: {}",
                    e.getMessage(), e);
        }
    }

    /**
     * Closes the broker connections the previous endpoint left in krista-infra's static cache.
     * Best-effort throughout — failing to tidy up an old connection must never stop us from
     * connecting to the new one.
     */
    private void evictStaleBrokerConnections() {
        for (Map.Entry<String, ServiceBusConfig> entry : startedConfigs.entrySet()) {
            try {
                JmsConnectionFactory.closeAndRemoveConnection(entry.getValue());
            } catch (Exception e) {
                LOGGER.warn("Could not evict the previous broker connection for {}: {}",
                        entry.getKey(), e.getMessage());
            }
        }
    }

    /**
     * Stops both consumers and starts them again against freshly-built configs, so each opens a
     * connection to the newly-bound endpoint.
     */
    private void restartConsumers() {
        services.forEach((name, svc) -> {
            try {
                svc.destroy();
            } catch (Exception e) {
                LOGGER.error("Error stopping consumer {} during rebind: {}", name, e.getMessage(), e);
            }
        });
        services.clear();
        startedConfigs.clear();

        // The handshake is re-evaluated on a rebind, not only at first start. This path is
        // reachable with NO consumers running — which is precisely the state a refusal leaves
        // behind — so skipping it here would let a Save Configuration start the very consumers
        // start() declined to start. Re-running it is also strictly more useful than caching the
        // first verdict: the rebind re-fetches the invoker record, so an MA redeployed onto a
        // matching contract clears the refusal without an extension restart.
        if (!contractHandshakePasses()) {
            return;
        }

        // Calendar auth-initiate is bound on BOTH the KIM-492 standard destination and the
        // pre-rename one, with the SAME handler instance. A rename is a new destination, not a
        // migration: MA keeps producing to the old name through Stage 2, so an extension that only
        // listened on the new one would go deaf to every MA that has not moved yet — and it would
        // go deaf silently, which is exactly what spec §6 R10 exists to prevent. Binding a second
        // destination carries no hazard of its own. Stage 3 (WP-E20) deletes the legacy binding
        // once MA's producer for it is gone.
        startConsumer("GoogleCalendarAuthInitiateConsumer",
                GoogleCalendarAuthInitiateServiceDefinition.identity(),
                new GoogleCalendarAuthInitiateServiceDefinition(),
                extensionConfig.getCalendarAuthInitiateConfig(),
                authInitiateHandler);

        startConsumer("GoogleCalendarAuthInitiateLegacyConsumer",
                GoogleCalendarAuthInitiateServiceDefinition.identity(),
                new GoogleCalendarAuthInitiateServiceDefinition(),
                extensionConfig.getCalendarAuthInitiateLegacyConfig(),
                authInitiateHandler);

        startReminderConsumers();

        // KIM-474 / EF-14: Meeting Agent has been producing licensed-seat notifications to
        // ext.calendar.seat.registered.google with nothing consuming them. The queue is durable, so
        // they accumulated on the broker, and a newly-licensed Google user was invisible to this
        // extension until the next scheduled reconcile.
        startConsumer("GoogleCalendarSeatRegisteredConsumer",
                GoogleCalendarSeatRegisteredServiceDefinition.identity(),
                new GoogleCalendarSeatRegisteredServiceDefinition(),
                extensionConfig.getCalendarSeatRegisteredConfig(),
                seatRegisteredHandler);

        // KIM-492: Meeting Agent's nightly full-tenant calendar sweep, replacing the daily Adaptive
        // Calendar Sync a Krista conversation used to drive. Without a consumer here nothing sweeps
        // at all — the seat reconcile and watch renewal only create MISSING channels.
        startConsumer("GoogleCalendarDailySyncConsumer",
                GoogleCalendarDailySyncServiceDefinition.identity(),
                new GoogleCalendarDailySyncServiceDefinition(),
                extensionConfig.getCalendarDailySyncConfig(),
                dailySyncHandler);

        startWorkspaceMessagesConsumer();

        LOGGER.info("Reconnected {} consumer(s) to the new ESB endpoint.", services.size());
    }

    /**
     * KIM-492: binds the in-call messages consumer on {@code ext.calendar.workspace.messages.google}.
     *
     * <p>A helper rather than a sixth inline block, because it is started from both {@link #start()} and
     * {@link #restartConsumers()} and those two lists have to stay identical — a consumer present in one
     * and not the other binds on boot and then silently disappears on the first Save Configuration.
     *
     * <p>Google Meet has no extension-side chat surface, so this consumer PRESENTS nothing: the Krista
     * Bot carries both messages in its own {@code BOT_CONFIG}. It exists because the destination is
     * durable and addressed per bound calendar system — unbound, every Google workspace's broadcasts
     * accumulate on the broker forever, one per settings save plus one per Meeting Agent boot. It stores
     * what it drains so a future Google-side surface reads state it was already sent.
     */
    private void startWorkspaceMessagesConsumer() {
        startConsumer("GoogleCalendarWorkspaceMessagesConsumer",
                GoogleCalendarWorkspaceMessagesServiceDefinition.identity(),
                new GoogleCalendarWorkspaceMessagesServiceDefinition(),
                extensionConfig.getCalendarWorkspaceMessagesConfig(),
                workspaceMessagesHandler);
    }

    /**
     * Stop both ESB consumers. Idempotent: a second invocation is a no-op so
     * the explicit {@code invokerUnloaded()} call and the HK2 locator's
     * shutdown firing this {@code @PreDestroy} are both safe.
     */
    @PreDestroy
    public synchronized void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info("Stopping GoogleServiceBusManager ({} consumer(s))...", services.size());

        services.forEach((name, svc) -> {
            try {
                svc.destroy();
                LOGGER.info("Stopped consumer: {}", name);
            } catch (Exception e) {
                LOGGER.error("Error stopping consumer {}: {}", name, e.getMessage(), e);
            }
        });
        services.clear();
        startedConfigs.clear();
    }

    /**
     * Starts the reminder-DECIDE consumer, and REFUSES to start it unscoped (spec §6 R11).
     *
     * <p>{@code TenantFilterMode} defaults to {@code ALL}, so a config that lost its
     * {@code setFixedTenantId} does not fail — it quietly starts consuming every tenant's messages
     * off a shared destination. This extension already fails open exactly that way for its two
     * long-standing consumers, whose handlers re-read {@code workspaceId} from every payload. The
     * decide handler cannot: its answer is derived from THIS pod's dedup memory and rate-limit
     * history, so serving another tenant's question means answering DEDUPED or RATE_LIMITED about
     * somebody we have never seen, and a real user stops being told their calendar is broken.
     *
     * <p>So this one consumer refuses rather than degrading, and the resolved tenant is logged at
     * INFO on every start — the misconfiguration is then visible in the extension's own first log
     * lines rather than in a cross-tenant incident weeks later.
     */
    /**
     * EF-08: starts the reminder consumers as a PAIR, or neither.
     *
     * <h2>Why they are coupled</h2>
     *
     * <p>The reminder loop is request/reply on {@code ext.calendar.reminder.decide.google}: Meeting
     * Agent asks this extension for a verdict and sends the mail itself. The one-way
     * {@code krista.calendar.reminder.send.google} queue is what that replaced, and its consumer
     * DRAINS rather than acts — the extension is ESB-only, so acting there would double-send.
     *
     * <p>The decide consumer refuses to start unless it is pinned to a single tenant (below). The
     * send consumer used to start unconditionally, and the combination was the worst of both: Meeting
     * Agent's decide request timed out with nothing listening, Meeting Agent fell back to producing on
     * the one-way queue, and this extension's drain consumer acknowledged and DISCARDED it. <b>Every
     * calendar-reconnect reminder was destroyed, silently, for as long as the misconfiguration
     * lasted</b> — and that is the one email that tells a user their calendar has stopped syncing.
     *
     * <p><b>So an unpinnable decide consumer now refuses BOTH, with one error.</b> The alternative —
     * starting the decide consumer unpinned — is not available: its answer is derived from THIS pod's
     * dedup memory and rate-limit history, so serving another tenant's question means answering
     * DEDUPED or RATE_LIMITED about a workspace this pod has never seen, and a real user stops being
     * told their calendar is broken. That is the hazard the pinning requirement exists for and it has
     * not changed.
     *
     * <p>With neither consumer bound, Meeting Agent's fallback messages ACCUMULATE on the durable
     * one-way queue instead of being destroyed. That is deliberately the better failure: queue depth
     * is a signal an operator can see and the messages are still there when the pinning is fixed,
     * whereas a drained-and-dropped reminder leaves no trace anywhere. Unbounded growth is the price,
     * and it is bounded in practice by Meeting Agent's own per-user reminder cadence — and by this
     * refusal being an ERROR in the extension's first log lines rather than a silent state.
     */
    private void startReminderConsumers() {
        ServiceBusConfig decideConfig = extensionConfig.getCalendarReminderDecideConfig();
        String refusal = reminderDecideRefusal(decideConfig);
        if (refusal != null) {
            LOGGER.error("Refusing to start EITHER reminder consumer: {}. The decide consumer would"
                            + " answer other workspaces' reminder questions from this pod's dedup and"
                            + " rate-limit memory. The one-way drain consumer on '{}' is refused WITH"
                            + " it, deliberately: bound without a decide consumer it acknowledges and"
                            + " DISCARDS every reminder Meeting Agent falls back to producing there,"
                            + " destroying the one email that tells a user their calendar stopped"
                            + " syncing. Unbound, those messages accumulate on that durable queue"
                            + " where an operator can see them and they survive until this is fixed."
                            + " Pin the extension to a single tenant to restore reminders.",
                    refusal, GoogleExtensionConfig.QUEUE_CALENDAR_REMINDER_SEND);
            return;
        }

        // The one-way consumer exists purely to DRAIN its durable queue: MA still falls back to
        // producing there on a decide timeout, and an unconsumed durable queue accumulates forever.
        // It never acts, so binding both cannot double-send.
        startConsumer("GoogleCalendarReminderSendConsumer",
                GoogleCalendarReminderSendServiceDefinition.identity(),
                new GoogleCalendarReminderSendServiceDefinition(),
                extensionConfig.getCalendarReminderSendConfig(),
                reminderSendHandler);

        // KIM-492 WP-E16 (spec §3.10): the request/reply reminder loop, and the only path that acts
        // on a reminder.
        LOGGER.info("GoogleCalendarReminderDecideConsumer is tenant-scoped: mode={} tenant={}"
                        + " destination={}", decideConfig.tenantFilterMode(),
                decideConfig.fixedTenantId().orElse(null), decideConfig.destinationBaseName());

        startConsumer("GoogleCalendarReminderDecideConsumer",
                GoogleCalendarReminderDecideServiceDefinition.identity(),
                new GoogleCalendarReminderDecideServiceDefinition(),
                decideConfig,
                reminderDecideHandler);
    }

    /**
     * Why the decide consumer may not start, or null when it may.
     *
     * <p>Separated from the starting so the refusal is one testable decision rather than a branch
     * buried in a void method — and so the send consumer can be refused on the SAME answer rather
     * than on a re-derived copy of it (EF-08).
     *
     * <p>Only a genuine tenant-scoping failure refuses. The contract handshake is a different gate,
     * evaluated by the caller: an unverifiable handshake — an older Meeting Agent that reports no
     * fingerprint, a bootstrap that did not complete — starts consumers off a shared destination,
     * which the two long-standing consumers can tolerate because their handlers re-read
     * {@code workspaceId} from every payload.
     *
     * @param config the decide consumer's resolved config, or null when it could not be built
     * @return the reason to refuse, phrased to be logged, or null
     */
    private static String reminderDecideRefusal(ServiceBusConfig config) {
        if (config == null) {
            // getCalendarReminderDecideConfig already logged the specific cause at ERROR.
            return "the reminder-decide destination could not be resolved";
        }
        ServiceBusConfig.TenantFilterMode mode = config.tenantFilterMode();
        String tenant = config.fixedTenantId().orElse(null);
        if (mode != ServiceBusConfig.TenantFilterMode.FIXED || tenant == null || tenant.isBlank()) {
            return "the reminder-decide consumer is NOT pinned to a single tenant (mode=" + mode
                    + ", fixedTenantId=" + tenant + ") on destination "
                    + config.destinationBaseName();
        }
        return null;
    }

    /** Max attempts to start a consumer, to ride out the platform shutdown-hook start race. */
    private static final int START_MAX_ATTEMPTS = 3;

    // Failure on one consumer must not prevent the other from starting, so
    // exceptions are logged and swallowed at this boundary.
    private void startConsumer(String consumerName,
                                ServiceIdentity identity,
                                ServiceDefinition definition,
                                ServiceBusConfig config,
                                ServiceEndpoint handler) {
        // Guard the BINDING, not the handler: draft/test stages otherwise win a live CA message
        // on the competing-consumer queue and acknowledge it without using committed settings.
        // This boundary is shared by initial startup and broker rebind, so neither can bypass it.
        if (SEAT_CONSUMER.equals(consumerName) || DAILY_CONSUMER.equals(consumerName)) {
            if (!liveSyncMode(consumerName)) {
                return;
            }
            // A shared ALL-tenant consumer steals and ACK-drops another workspace's commands.
            // Refuse BEFORE constructing ServiceImpl so commands remain on the durable queue.
            if (config == null || config.tenantFilterMode() != ServiceBusConfig.TenantFilterMode.FIXED
                    || config.fixedTenantId().filter(id -> !id.isBlank()).isEmpty()) {
                LOGGER.error("Refusing {}: sync consumer must be pinned to a nonblank workspace.", consumerName);
                recordSyncMetric("workspace_refused");
                return;
            }
        }
        for (int attempt = 1; attempt <= START_MAX_ATTEMPTS; attempt++) {
            try {
                ServiceImpl svc = new ServiceImpl(config, definition);
                svc.addService(identity.serviceName(), handler);
                svc.start();

                services.put(consumerName, svc);
                // Remember the exact config instance this consumer connected with, so a later
                // rebind can evict that connection by its current key (see reconfigureEsb).
                startedConfigs.put(consumerName, config);
                LOGGER.info("{} started on queue '{}' (serviceName={})",
                        consumerName, config.destinationBaseName(), identity.serviceName());
                return;
            } catch (java.util.ConcurrentModificationException race) {
                // Transient: the platform's ShutdownManagement.hasHook iterates a shared hook list
                // while the sibling invoker stages (draft/test/published) register shutdown hooks
                // concurrently during start. Back off briefly and retry so the consumer still comes up.
                LOGGER.warn("Transient ConcurrentModificationException starting {} (attempt {}/{}); retrying",
                        consumerName, attempt, START_MAX_ATTEMPTS);
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to start {}: {}", consumerName, e.getMessage(), e);
                return;
            }
        }
        LOGGER.error("Failed to start {} after {} attempts (ShutdownManagement start race)",
                consumerName, START_MAX_ATTEMPTS);
    }

    private boolean liveSyncMode(String consumerName) {
        ExecutionMode mode = executionMode;
        if (mode == ExecutionMode.LIVE) {
            LOGGER.info("Binding {}: executionMode=LIVE.", consumerName);
            return true;
        }
        LOGGER.info("Skipping {}: executionMode={} (only LIVE may consume CA sync requests).",
                consumerName, mode);
        recordSyncMetric(mode == null ? "mode_unavailable" : "non_live_skipped");
        return false;
    }

    /** Capture on INVOKER_LOADED while the platform context is available; refresh on each load. */
    public synchronized void captureExecutionMode() {
        executionMode = null;
        try {
            executionMode = requestContext == null ? null : requestContext.getExecutionMode();
        } catch (Exception unavailable) {
            LOGGER.warn("Execution mode unavailable; CA sync queues remain unbound: {}", unavailable.getMessage());
        }
        if (executionMode == null) {
            recordSyncMetric("mode_unavailable");
        }
    }

    private void resolveExecutionModeIfNeeded() {
        if (executionMode == null) {
            captureExecutionMode();
        }
    }

    private boolean consumersReady() {
        if (services.size() < MIN_CONSUMER_COUNT) {
            return false;
        }
        // Compatibility standalone callers have no platform lifecycle. Production always injects
        // RequestContext and cannot appear ready when its stage or required LIVE queues are missing.
        if (requestContext == null) {
            return true;
        }
        return executionMode != null && (executionMode != ExecutionMode.LIVE
                || (services.containsKey(SEAT_CONSUMER) && services.containsKey(DAILY_CONSUMER)));
    }

    private void recordSyncMetric(String outcome) {
        if (telemetryMetrics != null) {
            try {
                telemetryMetrics.incrementCounter("google_calendar.esb.sync_binding." + outcome, 1L);
            } catch (Exception failure) {
                LOGGER.warn("Could not record sync-binding telemetry: {}", failure.getMessage());
            }
        }
    }
}
