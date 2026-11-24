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
 * DTO for the CA Service invoker-discovery response
 * ({@code GET /api/v1/extensions/invokers?workspaceId={id}}).
 *
 * <p>Ported (trimmed) from KIP's {@code app.krista.global.esb.InvokerDetails}.
 * Plain POJO mapped by Gson (field names {@code id}, {@code connectionDetails}
 * match the JSON keys). Only the invoker id and the ESB connection block are
 * consumed by this extension.
 */
public class InvokerDetails {

    private String id;
    private ConnectionDetails connectionDetails;

    /**
     * SHA-256 of the ext↔MA wire contract the Meeting Agent that answered this bootstrap was
     * BUILT from (KIM-492 WP-E2/E15). Sits beside {@code extensionVersion} on each invoker
     * element.
     *
     * <p>Absent — {@code null} — on any MA older than WP-E2. That is not a mismatch and must not
     * be treated as one: the check it feeds
     * ({@link app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.ContractHandshake})
     * refuses to start consumers only when both sides report a fingerprint and the two DIFFER.
     *
     * <p>Parsed by Gson, which maps on field name and silently leaves the field null when the key
     * is missing — the additive-field property the spec relies on.
     */
    private String contractsFingerprint;

    public String getId() {
        return id;
    }

    /** @return MA's reported wire-contract fingerprint, or {@code null} when MA did not report one */
    public String getContractsFingerprint() {
        return contractsFingerprint;
    }

    /** ESB connection details, or {@code null} if absent. */
    public EsbConnectionDetails getEsbDetails() {
        return connectionDetails != null ? connectionDetails.getEsb() : null;
    }

    @Override
    public String toString() {
        return "InvokerDetails{id='" + id + "', connectionDetails=" + connectionDetails
                + ", contractsFingerprint='" + contractsFingerprint + "'}";
    }
}
