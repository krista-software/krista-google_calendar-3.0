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
 * Wrapper DTO holding the ESB connection block of an invoker-discovery response.
 *
 * <p>Ported (trimmed) from KIP's {@code app.krista.global.esb.ConnectionDetails}.
 * Plain POJO mapped by Gson (field name {@code esb} matches the JSON key). The
 * Google extension does not use the cache block, so it is not modelled.
 */
public class ConnectionDetails {

    private EsbConnectionDetails esb;

    public EsbConnectionDetails getEsb() {
        return esb;
    }

    @Override
    public String toString() {
        return "ConnectionDetails{esb=" + esb + '}';
    }
}
