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

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Deserialization tests for {@link EsbConnectionDetails}, focused on the KIM-451
 * {@code esbNamespace} field. Uses Gson directly (the same library the extension's
 * {@code GoogleExtensionConfig} uses to parse the CA invoker-discovery response), so
 * the JSON contract exercised here mirrors {@code connectionDetails.esb} on the wire.
 */
class EsbConnectionDetailsTest {

    private static final Gson GSON = new Gson();

    @Test
    void deserialize_withNamespace_returnsTrimmedValue() {
        String json = "{\"host\":\"broker\",\"port\":61616,\"esbNamespace\":\"tenant-a\"}";

        EsbConnectionDetails details = GSON.fromJson(json, EsbConnectionDetails.class);

        assertEquals("broker", details.getHostOrDefault());
        assertEquals(61616, details.getPortOrDefault());
        assertEquals("tenant-a", details.getEsbNamespaceOrNull());
    }

    @Test
    void deserialize_withSurroundingWhitespace_isTrimmed() {
        String json = "{\"esbNamespace\":\"  tenant-b  \"}";

        EsbConnectionDetails details = GSON.fromJson(json, EsbConnectionDetails.class);

        assertEquals("tenant-b", details.getEsbNamespaceOrNull());
    }

    @Test
    void deserialize_fieldAbsent_returnsNull() {
        String json = "{\"host\":\"broker\",\"port\":61616}";

        EsbConnectionDetails details = GSON.fromJson(json, EsbConnectionDetails.class);

        assertNull(details.getEsbNamespaceOrNull());
    }

    @Test
    void deserialize_fieldExplicitlyNull_returnsNull() {
        String json = "{\"esbNamespace\":null}";

        EsbConnectionDetails details = GSON.fromJson(json, EsbConnectionDetails.class);

        assertNull(details.getEsbNamespaceOrNull());
    }

    @Test
    void deserialize_fieldBlank_returnsNull() {
        String json = "{\"esbNamespace\":\"   \"}";

        EsbConnectionDetails details = GSON.fromJson(json, EsbConnectionDetails.class);

        assertNull(details.getEsbNamespaceOrNull());
    }

    @Test
    void deserialize_valueVerbatim_notReValidated() {
        // The server validates [a-z0-9-]+; the client passes through whatever it
        // receives (only trimmed). A value the server would never emit is still
        // returned as-is — the extension does not re-validate.
        String json = "{\"esbNamespace\":\"Not_Valid!\"}";

        EsbConnectionDetails details = GSON.fromJson(json, EsbConnectionDetails.class);

        assertEquals("Not_Valid!", details.getEsbNamespaceOrNull());
    }
}
