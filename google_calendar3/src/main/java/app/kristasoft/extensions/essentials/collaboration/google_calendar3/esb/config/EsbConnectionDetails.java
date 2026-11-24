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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.config;

/**
 * ESB connection details returned by CA Service's invoker-discovery API
 * ({@code GET /api/v1/extensions/invokers?workspaceId={id}}).
 *
 * <p>Ported (trimmed) from KIP's {@code app.krista.global.esb.EsbConnectionDetails}.
 * The Google module uses Gson, so this is a plain POJO whose field names
 * ({@code host}, {@code port}, {@code esbNamespace}) match the JSON keys. Only the
 * broker host/port and the optional destination namespace are consumed here;
 * credentials come from env / {@code GoogleExtensionConfig} defaults.
 *
 * <p>{@code esbNamespace} (KIM-451) is a nullable multi-tenant destination prefix.
 * When non-blank, {@code GoogleExtensionConfig} sets it as the JVM system property
 * {@code broker.dest.namespace} so the (namespace-aware) krista-infra
 * {@code ServiceBusConfig} derives destinations as
 * {@code {namespace}.{destName}-{version}-IN}. When absent/blank, behavior is
 * byte-identical to pre-KIM-451.
 */
public class EsbConnectionDetails {

    static final String DEFAULT_HOST = "localhost";
    static final int DEFAULT_PORT = 61616;

    private String host;
    private int port;
    private String esbNamespace;

    /** Get host with fallback to default. */
    public String getHostOrDefault() {
        return (host != null && !host.isEmpty()) ? host : DEFAULT_HOST;
    }

    /** Get port with fallback to default. */
    public int getPortOrDefault() {
        return (port > 0) ? port : DEFAULT_PORT;
    }

    /**
     * The trimmed ESB destination namespace, or {@code null} when absent or blank.
     * The server validates the value ({@code [a-z0-9-]+}); it is passed through
     * verbatim (only surrounding whitespace stripped) and not re-validated here.
     */
    public String getEsbNamespaceOrNull() {
        if (esbNamespace == null) {
            return null;
        }
        String trimmed = esbNamespace.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public String toString() {
        return "EsbConnectionDetails{host='" + host + "', port=" + port
                + ", esbNamespace='" + esbNamespace + "'}";
    }
}
