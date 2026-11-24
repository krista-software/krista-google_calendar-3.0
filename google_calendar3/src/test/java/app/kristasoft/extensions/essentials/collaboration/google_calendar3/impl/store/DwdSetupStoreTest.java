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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store;

import app.krista.extensions.util.KeyValueStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DwdSetupStoreTest {

    @Test
    void setupTokenIsScopedToInvokerAndCanBeUsedOnlyOnce() {
        KeyValueStore keyValueStore = mock(KeyValueStore.class);
        Map<String, Object> values = new HashMap<>();
        doAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(keyValueStore).put(anyString(), any());
        when(keyValueStore.get(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        doAnswer(call -> {
            values.remove(call.getArgument(0));
            return null;
        }).when(keyValueStore).remove(anyString());

        DwdSetupStore setupStore = new DwdSetupStore(keyValueStore);
        String token = setupStore.create("invoker-a", "114009213202650966532", "wsContact_admin");

        assertNull(setupStore.find("invoker-b", token));
        assertEquals("wsContact_admin", setupStore.find("invoker-a", token).accountId());
        assertTrue(setupStore.consume("invoker-a", token));
        assertFalse(setupStore.consume("invoker-a", token));
    }
}
