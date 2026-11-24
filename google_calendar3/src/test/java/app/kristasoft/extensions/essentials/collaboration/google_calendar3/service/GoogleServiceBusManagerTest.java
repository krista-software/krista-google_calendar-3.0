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

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.GoogleExtensionConfig;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarAuthInitiateHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarReminderDecideHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarReminderSendHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarSeatRegisteredHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarWorkspaceMessagesHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarConnectionReconciliationService;
import app.krista.infra.esb.ServiceBusConfig;
import app.krista.infra.esb.service.ServiceImpl;
import app.krista.ksdk.telemetry.TelemetryMetrics;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleServiceBusManagerTest {

    @Mock
    private GoogleExtensionConfig extensionConfig;
    @Mock
    private GoogleCalendarAuthInitiateHandler authInitiateHandler;
    @Mock
    private GoogleCalendarReminderSendHandler reminderSendHandler;
    @Mock
    private GoogleCalendarReminderDecideHandler reminderDecideHandler;
    @Mock
    private GoogleCalendarSeatRegisteredHandler seatRegisteredHandler;
    @Mock
    private app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar.GoogleCalendarDailySyncHandler dailySyncHandler;
    @Mock
    private GoogleCalendarWorkspaceMessagesHandler workspaceMessagesHandler;
    @Mock
    private CalendarConnectionReconciliationService connectionReconciliationService;
    @Mock
    private TelemetryMetrics telemetryMetrics;

    @Test
    void reconfigureEsbAttemptsRecoveryWhenStartupLeftNoConsumersRunning() {
        GoogleServiceBusManager manager = new GoogleServiceBusManager(
                extensionConfig, authInitiateHandler, reminderSendHandler, reminderDecideHandler,
                seatRegisteredHandler, dailySyncHandler, workspaceMessagesHandler, connectionReconciliationService,
                telemetryMetrics);
        when(extensionConfig.refreshEsbEndpoint("https://ca.example", "api-key")).thenReturn(true);
        // MA reporting no fingerprint is UNVERIFIED, which ADMITS — the handshake must not be what
        // stops this path (see ContractHandshakeTest for the decision table itself).
        when(extensionConfig.getReportedContractsFingerprint()).thenReturn(null);
        // Stop before ServiceImpl can open a real broker connection; reaching this call proves that
        // the zero-consumer state no longer causes reconfiguration to be skipped.
        when(extensionConfig.getCalendarAuthInitiateConfig())
                .thenThrow(new IllegalStateException("stop before broker connection"));

        assertThrows(IllegalStateException.class,
                () -> manager.reconfigureEsb("https://ca.example", "api-key"));

        verify(extensionConfig).refreshEsbEndpoint("https://ca.example", "api-key");
        verify(extensionConfig).getCalendarAuthInitiateConfig();
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthyEndpointReconcilesDurableCalendarConnectionsEvenWhenBrokerDidNotMove()
            throws ReflectiveOperationException {
        GoogleServiceBusManager manager = new GoogleServiceBusManager(
                extensionConfig, authInitiateHandler, reminderSendHandler, reminderDecideHandler,
                seatRegisteredHandler, dailySyncHandler, workspaceMessagesHandler, connectionReconciliationService,
                telemetryMetrics);
        when(extensionConfig.refreshEsbEndpoint("https://ca.example", "api-key")).thenReturn(false);

        Field servicesField = GoogleServiceBusManager.class.getDeclaredField("services");
        servicesField.setAccessible(true);
        Map<String, ServiceImpl> services = (Map<String, ServiceImpl>) servicesField.get(manager);
        // Three, not two: auth-initiate is bound on BOTH the standard and the pre-rename
        // destination since WP-E15, so EXPECTED_CONSUMER_COUNT is 3. With only two the manager
        // would (correctly) treat this as a degraded start and try to recover.
        services.put("auth", mock(ServiceImpl.class));
        services.put("authLegacy", mock(ServiceImpl.class));
        services.put("reminder", mock(ServiceImpl.class));

        assertFalse(manager.reconfigureEsb("https://ca.example", "api-key"));

        verify(connectionReconciliationService).reassertConnectedUsers();
    }

    // ── Reminder-decide tenant scope (KIM-492 WP-E16, spec §6 R11) ─────────
    //
    // The decide consumer is the ONE consumer this extension refuses to start unscoped. Its verdict
    // is derived from THIS pod's dedup memory and rate-limit history, so consuming another tenant's
    // question means answering DEDUPED or RATE_LIMITED about somebody this pod has never seen — and
    // a real user silently stops being told their calendar is broken. TenantFilterMode.ALL is the
    // enum's DEFAULT, so an edit that loses setFixedTenantId does not fail: it starts consuming
    // everybody's messages, quietly. These assertions are what WP-E16's commit message claimed
    // existed; they land here.

    /** Configs the guard must REFUSE, each one a way of arriving at "not pinned to one tenant". */
    static Stream<Arguments> unscopedConfigs() {
        return Stream.of(
                Arguments.of("null — no workspace resolved",
                        (ServiceBusConfig) null),
                Arguments.of("mode ALL — the enum's default",
                        decideConfig().setTenantFilterMode(ServiceBusConfig.TenantFilterMode.ALL)),
                Arguments.of("FIXED with no tenant id",
                        decideConfig().setTenantFilterMode(ServiceBusConfig.TenantFilterMode.FIXED)),
                Arguments.of("FIXED with a blank tenant id",
                        decideConfig().setTenantFilterMode(ServiceBusConfig.TenantFilterMode.FIXED)
                                .setFixedTenantId("   ")));
    }

    /**
     * A config on a destination no deployment uses, so a positive case that does reach the broker
     * binds a throwaway queue rather than the real {@code ext.calendar.reminder.decide.google}.
     */
    private static ServiceBusConfig decideConfig() {
        return new ServiceBusConfig()
                .setDestinationBaseName("test.decide." + UUID.randomUUID().toString().substring(0, 8))
                .setVersion(1)
                .setDestinationType(ServiceBusConfig.DestinationType.Queue);
    }

    private GoogleServiceBusManager manager() {
        return new GoogleServiceBusManager(
                extensionConfig, authInitiateHandler, reminderSendHandler, reminderDecideHandler,
                seatRegisteredHandler, dailySyncHandler, workspaceMessagesHandler, connectionReconciliationService,
                telemetryMetrics);
    }

    /**
     * Calls the private starter directly, so no unrelated consumer opens a broker connection.
     *
     * <p>EF-08 renamed this from {@code startReminderDecideConsumer}: the two reminder consumers are
     * now started as a PAIR or not at all, and the name says so.
     */
    private static void startReminderConsumers(GoogleServiceBusManager manager)
            throws ReflectiveOperationException {
        Method starter = GoogleServiceBusManager.class.getDeclaredMethod("startReminderConsumers");
        starter.setAccessible(true);
        starter.invoke(manager);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ServiceImpl> runningConsumers(GoogleServiceBusManager manager)
            throws ReflectiveOperationException {
        Field servicesField = GoogleServiceBusManager.class.getDeclaredField("services");
        servicesField.setAccessible(true);
        return (Map<String, ServiceImpl>) servicesField.get(manager);
    }

    /**
     * EF-08 — this now asserts that NEITHER reminder consumer starts, not just the decide one.
     *
     * <p>The send consumer used to start unconditionally beside a refused decide consumer, and that
     * combination destroyed every reminder: Meeting Agent's decide request timed out with nothing
     * listening, Meeting Agent fell back to producing on the one-way queue, and the drain consumer
     * acknowledged and discarded it. 100% loss, no trace, on the one email that tells a user their
     * calendar has stopped syncing. Unbound, those messages accumulate on a durable queue where an
     * operator can see them and they survive until the pinning is fixed.
     */
    @ParameterizedTest(name = "{0} -> neither consumer")
    @MethodSource("unscopedConfigs")
    @DisplayName("an unscoped reminder-decide config starts NEITHER reminder consumer (R11, EF-08)")
    void anUnscopedConfigStartsNoConsumer(String description, ServiceBusConfig config)
            throws ReflectiveOperationException {
        GoogleServiceBusManager manager = manager();
        lenient().when(extensionConfig.getCalendarReminderDecideConfig()).thenReturn(config);
        lenient().when(extensionConfig.getCalendarReminderSendConfig()).thenReturn(sendConfig());

        startReminderConsumers(manager);

        Map<String, ServiceImpl> running = runningConsumers(manager);
        assertFalse(running.containsKey("GoogleCalendarReminderDecideConsumer"),
                "A consumer started from a " + description + " config would read EVERY workspace's"
                        + " reminder questions off a shared destination and answer them from this"
                        + " pod's own dedup memory. Refusing is the deliberate behaviour — do not"
                        + " let this one fall back to TenantFilterMode.ALL the way its four"
                        + " siblings do.");
        assertFalse(running.containsKey("GoogleCalendarReminderSendConsumer"),
                "EF-08: the one-way DRAIN consumer must be refused WITH the decide consumer. Bound"
                        + " without it, it acknowledges and DISCARDS every reminder MA falls back to"
                        + " producing on that queue — destroying the reconnect email entirely, with"
                        + " nothing anywhere to show it happened.");
        assertTrue(running.isEmpty(), "no consumer at all should be running: " + running.keySet());
        // The assertions above are necessary but not SUFFICIENT: startConsumer swallows its own
        // failures, so on a box with no broker a send consumer that WAS started still leaves the
        // map empty and they pass vacuously. This is the broker-independent proof — the refusal
        // path must never even ask for the send destination, because asking is the first thing
        // starting it does.
        verify(extensionConfig, never()).getCalendarReminderSendConfig();
    }

    @Test
    @DisplayName("a tenant-pinned config starts BOTH reminder consumers and logs the tenant at INFO")
    void aTenantPinnedConfigStartsBothAndLogsTheResolvedTenantAtInfo()
            throws ReflectiveOperationException {
        ServiceBusConfig config = decideConfig()
                .setTenantFilterMode(ServiceBusConfig.TenantFilterMode.FIXED)
                .setFixedTenantId("ws-gcal-r11");
        GoogleServiceBusManager manager = manager();
        lenient().when(extensionConfig.getCalendarReminderDecideConfig()).thenReturn(config);
        lenient().when(extensionConfig.getCalendarReminderSendConfig()).thenReturn(sendConfig());

        CapturedLog log = CapturedLog.attachTo(GoogleServiceBusManager.class);
        try {
            startReminderConsumers(manager);
        }
        finally {
            log.detach();
            // A live broker on this box means the consumers really did start; close them either way.
            manager.stop();
        }

        assertTrue(log.anyInfoContaining("ws-gcal-r11", "FIXED", config.destinationBaseName()),
                "The resolved tenant must be on the INFO line the consumer prints as it starts."
                        + " It is the only place a misconfigured binding is visible BEFORE it shows"
                        + " up as a cross-tenant incident weeks later. Captured INFO lines: "
                        + log.infoMessages());
        // The send consumer is reached only AFTER the decide refusal check passes, which is the
        // coupling in the other direction: pinned means both, unpinned means neither.
        verify(extensionConfig).getCalendarReminderSendConfig();
    }

    /** Throwaway one-way destination, so a live broker binds a test queue and not the real one. */
    private static ServiceBusConfig sendConfig() {
        return new ServiceBusConfig()
                .setDestinationBaseName("test.send." + UUID.randomUUID().toString().substring(0, 8))
                .setVersion(1)
                .setDestinationType(ServiceBusConfig.DestinationType.Queue);
    }

    /**
     * Collects log4j2 events for one logger. The extension's own package logger is declared at
     * {@code info} in {@code log4j2.xml}, so an INFO event genuinely reaches an appender attached
     * here — this captures the same event a deployment would print.
     */
    private static final class CapturedLog extends AbstractAppender {

        private final List<String> infoMessages = new CopyOnWriteArrayList<>();
        private final LoggerConfig loggerConfig;

        private CapturedLog(String name, LoggerConfig loggerConfig) {
            super(name, null, null, true, null);
            this.loggerConfig = loggerConfig;
        }

        static CapturedLog attachTo(Class<?> loggerOwner) {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            LoggerConfig loggerConfig =
                    context.getConfiguration().getLoggerConfig(loggerOwner.getName());
            CapturedLog appender = new CapturedLog("captured-" + UUID.randomUUID(), loggerConfig);
            appender.start();
            context.getConfiguration().addAppender(appender);
            loggerConfig.addAppender(appender, Level.INFO, null);
            context.updateLoggers();
            return appender;
        }

        void detach() {
            loggerConfig.removeAppender(getName());
            stop();
            ((LoggerContext) LogManager.getContext(false)).updateLoggers();
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.INFO))
                infoMessages.add(event.getMessage().getFormattedMessage());
        }

        boolean anyInfoContaining(String... fragments) {
            return infoMessages.stream()
                    .anyMatch(message -> Stream.of(fragments).allMatch(message::contains));
        }

        List<String> infoMessages() {
            return infoMessages;
        }
    }
}
