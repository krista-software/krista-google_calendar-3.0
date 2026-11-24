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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * KIM-492 WP-E16, spec §6 R11 — the reminder-DECIDE consumer's config REFUSES to exist unscoped.
 *
 * <h2>Why this one config is different from its four siblings</h2>
 *
 * <p>Every other consumer config goes through {@code applyTenantScope}, which degrades to
 * {@code TenantFilterMode.ALL} with a WARN when this pod resolves no workspace. That is the right
 * call there: those handlers re-read {@code workspaceId} from every payload, so an unscoped binding
 * costs throughput and nothing else.
 *
 * <p>The decide handler cannot do that. Its verdict is derived from THIS pod's dedup memory and
 * rate-limit history, so answering another tenant's question means replying DEDUPED or
 * RATE_LIMITED about a user this pod has never seen — and a real person silently stops being told
 * their calendar is broken. {@code ALL} is also the DEFAULT on {@link ServiceBusConfig}, so a
 * future edit that drops the {@code setFixedTenantId} call does not fail: it starts consuming
 * everybody's messages, quietly.
 *
 * <p>Hence a hard null and a matching refusal in the manager. These assertions are what WP-E16's
 * commit message claimed existed; they land here.
 */
@DisplayName("Reminder-decide config — tenant scope (spec §6 R11)")
class CalendarReminderDecideTenantScopeTest {

    private static final String WORKSPACE = "ws-gcal-r11";

    /**
     * Constructing the config runs the ESB bootstrap, which writes these process-global properties
     * on its fallback path. Snapshot and restore them so this class cannot leak a broker binding
     * into a later test — {@code EsbCaGatewayBrokerRoundTripTest} reads exactly these.
     */
    private static final String[] BOOTSTRAP_PROPERTIES = {
            "broker.primary.host", "broker.primary.port", "broker.user.name", "broker.user.pwd",
            "krista.esb.host", "krista.esb.port", "krista.esb.username", "krista.esb.password",
            "broker.dest.namespace"
    };

    private final Map<String, String> savedProperties = new HashMap<>();

    @BeforeEach
    void rememberBootstrapProperties() {
        savedProperties.clear();
        for (String property : BOOTSTRAP_PROPERTIES)
            savedProperties.put(property, System.getProperty(property));
    }

    @AfterEach
    void restoreBootstrapProperties() {
        savedProperties.forEach((property, value) -> {
            if (value == null)
                System.clearProperty(property);
            else
                System.setProperty(property, value);
        });
    }

    /**
     * @param workspaceId the workspace this pod resolves, or null for a pod that resolves none
     * @return a config built the way the platform builds it, with no CA handshake reachable
     */
    private static GoogleExtensionConfig configFor(String workspaceId) {
        Workspace workspace = mock(Workspace.class);
        lenient().when(workspace.getWorkspaceId()).thenReturn(workspaceId);

        Invoker invoker = mock(Invoker.class);
        // No serviceUrl attribute, so the CA handshake short-circuits before any HTTP call and the
        // constructor takes its qualified-FQDN fallback. Nothing here reaches the network.
        lenient().when(invoker.getAttributes()).thenReturn(new HashMap<>());

        return new GoogleExtensionConfig(workspace, invoker);
    }

    @Test
    @DisplayName("no workspace resolves -> null, so the consumer cannot start at all")
    void noWorkspaceYieldsNoConfig() {
        assertNull(configFor(null).getCalendarReminderDecideConfig(),
                "Returning a config here would hand the manager an ALL-mode binding, and this"
                        + " extension would answer every other tenant's reminder questions from"
                        + " this pod's own dedup memory. A consumer that never starts is visible"
                        + " in the first line of the log; one that starts unscoped is not visible"
                        + " at all.");
    }

    @Test
    @DisplayName("a blank workspace id is 'no workspace', not a tenant named ''")
    void blankWorkspaceIdIsTreatedAsAbsent() {
        assertNull(configFor("   ").getCalendarReminderDecideConfig(),
                "A blank id would bind the consumer to a tenant nobody produces to — the silent"
                        + " failure spec §6 R10 exists to prevent — or, worse, be normalised away"
                        + " into an unscoped binding downstream.");
    }

    @Test
    @DisplayName("a workspace resolves -> FIXED mode pinned to that workspace, on the ext.* destination")
    void aResolvedWorkspacePinsTheConsumer() {
        ServiceBusConfig config = configFor(WORKSPACE).getCalendarReminderDecideConfig();

        assertNotNull(config, "This pod resolves a workspace, so the consumer must bind.");
        assertEquals(ServiceBusConfig.TenantFilterMode.FIXED, config.tenantFilterMode(),
                "FIXED makes Artemis do the isolation. ALL is the enum's default and leaves it to"
                        + " a handler that, for this one consumer, cannot perform it.");
        assertEquals(WORKSPACE, config.fixedTenantId().orElse(null),
                "FIXED without a tenant id is indistinguishable from unscoped at the broker.");
        assertEquals(GoogleExtensionConfig.QUEUE_CALENDAR_REMINDER_DECIDE,
                config.destinationBaseName(),
                "The destination comes from the shared constant, never a literal at the call site"
                        + " — a drifted name binds a queue nobody produces to and fails silently.");
    }
}
