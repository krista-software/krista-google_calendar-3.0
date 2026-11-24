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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * The JVM system properties that bind this extension's krista-infra ESB layer to a broker,
 * plus the rules for <em>re</em>binding them while the extension is running.
 *
 * <p><b>Why this can be done at runtime at all.</b> krista-infra's {@code Configuration}
 * resolves every property on each read (environment variable, then System property, then the
 * bundled properties file), so a {@code ServiceBusConfig} built long ago still sees a System
 * property written a second ago. The broker connection cache is keyed by
 * {@code ConnectionKey(host + ":" + port, backup, clientId)}, so once the properties change, the
 * next {@code getOrCreateConnection} opens a connection to the NEW endpoint rather than handing
 * back the old one. Rebinding is therefore "write the properties, then recycle the consumers" —
 * no container restart, which is the whole point of this class.
 *
 * <p><b>What counts as a change.</b> Host, port, credentials and destination namespace. Only
 * host and port participate in the connection-cache key, but a credential or namespace change is
 * equally invisible to an already-started consumer, so all four are treated as "you must recycle".
 *
 * <p><b>Namespace is set-only, never cleared</b> (KIM-451 contract): a null/blank
 * {@code esbNamespace} from the CA handshake means "leave destination naming exactly as it is",
 * NOT "clear the namespace". A handshake that stops reporting the field must not silently move
 * every queue name, so a blank value is neither applied nor counted as a change.
 *
 * <p>Note this binds only the endpoint itself. The connection-hardening knobs this extension also
 * sets ({@code broker.reconnect.attempts}, {@code broker.connection.call.timeout.ms}) are constants
 * of the extension rather than handshake output, so they stay in {@code GoogleExtensionConfig} and
 * are deliberately outside the changed/unchanged signal.
 */
public final class EsbEndpointBinding {

    private static final Logger LOGGER = LoggerFactory.getLogger(EsbEndpointBinding.class);

    /** Broker host, as read by krista-infra's {@code ServiceBusConfig.primaryHost()}. */
    public static final String HOST_PROPERTY = "broker.primary.host";
    /** Broker port, as read by krista-infra's {@code ServiceBusConfig.primaryPort()}. */
    public static final String PORT_PROPERTY = "broker.primary.port";
    /** Destination namespace prefix (KIM-451), as read by {@code ServiceBusConfig}. */
    public static final String NAMESPACE_PROPERTY = "broker.dest.namespace";
    /** Broker user, as read by krista-infra. */
    public static final String USER_PROPERTY = "broker.user.name";
    /** Broker password, as read by krista-infra. */
    public static final String PASSWORD_PROPERTY = "broker.user.pwd";

    // The krista.esb.* mirrors are what this extension's own diagnostics and the older
    // esb_default.properties refer to. They are kept in lockstep with the broker.* pair so a
    // rebind cannot leave the two describing different brokers.
    private static final String ESB_HOST_PROPERTY = "krista.esb.host";
    private static final String ESB_PORT_PROPERTY = "krista.esb.port";
    private static final String ESB_USER_PROPERTY = "krista.esb.username";
    private static final String ESB_PASSWORD_PROPERTY = "krista.esb.password";

    private EsbEndpointBinding() {
    }

    /**
     * A broker endpoint as this extension binds it.
     *
     * @param host      broker host; never null when applied
     * @param port      broker port
     * @param namespace destination-namespace prefix, or null/blank for "leave as-is"
     * @param username  broker user
     * @param password  broker password
     */
    public record Endpoint(String host, int port, String namespace, String username, String password) {
    }

    /**
     * Reads the endpoint the JVM is bound to right now. Used to decide whether a freshly-fetched
     * endpoint actually differs — an unchanged handshake result must not recycle live consumers.
     *
     * <p>An unset or unparseable port reads as {@code -1}, which never equals a real port, so the
     * first bind always registers as a change.
     */
    public static Endpoint current() {
        return new Endpoint(
                System.getProperty(HOST_PROPERTY),
                intProperty(PORT_PROPERTY),
                System.getProperty(NAMESPACE_PROPERTY),
                System.getProperty(USER_PROPERTY),
                System.getProperty(PASSWORD_PROPERTY));
    }

    /**
     * Binds the JVM to {@code next} and reports whether anything a running consumer would have to
     * reconnect for actually moved.
     *
     * <p>Always writes the properties (so the two property families cannot drift), but returns
     * {@code false} when the effective endpoint is identical — the caller uses that to leave
     * healthy consumers alone instead of churning their broker connections on every refresh tick.
     *
     * @param next the endpoint to bind; {@code host} must be non-blank
     * @return true when host, port, credentials or namespace changed
     */
    public static synchronized boolean apply(Endpoint next) {
        Objects.requireNonNull(next, "endpoint cannot be null");
        if (next.host() == null || next.host().isBlank()) {
            throw new IllegalArgumentException("ESB endpoint host cannot be blank");
        }

        Endpoint current = current();
        String namespace = trimToNull(next.namespace());

        boolean changed = !Objects.equals(current.host(), next.host())
                || current.port() != next.port()
                || !Objects.equals(current.username(), next.username())
                || !Objects.equals(current.password(), next.password())
                // Set-only: a blank incoming namespace is "unchanged", never "cleared".
                || (namespace != null && !namespace.equals(current.namespace()));

        System.setProperty(HOST_PROPERTY, next.host());
        System.setProperty(PORT_PROPERTY, String.valueOf(next.port()));
        System.setProperty(ESB_HOST_PROPERTY, next.host());
        System.setProperty(ESB_PORT_PROPERTY, String.valueOf(next.port()));

        if (next.username() != null) {
            System.setProperty(USER_PROPERTY, next.username());
            System.setProperty(ESB_USER_PROPERTY, next.username());
        }
        if (next.password() != null) {
            System.setProperty(PASSWORD_PROPERTY, next.password());
            System.setProperty(ESB_PASSWORD_PROPERTY, next.password());
        }
        if (namespace != null) {
            System.setProperty(NAMESPACE_PROPERTY, namespace);
        }

        if (changed) {
            LOGGER.info("ESB endpoint bound: host={}, port={}, destNamespace={}",
                    next.host(), next.port(), namespace != null ? namespace : System.getProperty(NAMESPACE_PROPERTY));
        } else {
            LOGGER.debug("ESB endpoint unchanged: host={}, port={}", next.host(), next.port());
        }
        return changed;
    }

    private static int intProperty(String name) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
