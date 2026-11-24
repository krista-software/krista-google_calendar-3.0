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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserKeyIndexStore} — maps a user's primary-calendar email
 * to the refresh-token-store key (e.g. {@code wsContact_<UUID>}) so the reconnect
 * flow can resolve the key the sync path reads, rather than falling back to the
 * bare email.
 */
@ExtendWith(MockitoExtension.class)
class UserKeyIndexStoreTest {

    @Mock
    private KeyValueStore keyValueStore;

    private UserKeyIndexStore store;

    @BeforeEach
    void setUp() {
        store = new UserKeyIndexStore(keyValueStore);
    }

    @Test
    void put_writesNormalizedEmailKey() {
        store.put("INV-1", "  Foo@Bar.com ", "wsContact_1ba35a88");

        verify(keyValueStore).put("gcal_user_key_index:INV-1:foo@bar.com", "wsContact_1ba35a88");
    }

    @Test
    void get_returnsStoredKey_normalizingEmail() {
        when(keyValueStore.get("gcal_user_key_index:INV-1:foo@bar.com")).thenReturn("wsContact_1ba35a88");

        assertEquals("wsContact_1ba35a88", store.get("INV-1", "Foo@Bar.com"));
    }

    @Test
    void get_returnsNullWhenAbsent() {
        when(keyValueStore.get(anyString())).thenReturn(null);

        assertNull(store.get("INV-1", "foo@bar.com"));
    }

    @Test
    void put_skipsBlankEmail() {
        store.put("INV-1", "  ", "wsContact_1ba35a88");

        verify(keyValueStore, never()).put(anyString(), anyString());
    }

    @Test
    void put_skipsBlankTokenKey() {
        store.put("INV-1", "foo@bar.com", "  ");

        verify(keyValueStore, never()).put(anyString(), anyString());
    }

    @Test
    void differentInvokersDoNotCollide() {
        store.put("INV-1", "foo@bar.com", "wsContact_a");
        store.put("INV-2", "foo@bar.com", "wsContact_b");

        verify(keyValueStore).put("gcal_user_key_index:INV-1:foo@bar.com", "wsContact_a");
        verify(keyValueStore).put("gcal_user_key_index:INV-2:foo@bar.com", "wsContact_b");
    }

    @Test
    void get_returnsNullForBlankEmail() {
        assertNull(store.get("INV-1", "  "));
        verify(keyValueStore, never()).get(anyString());
    }

    @Test
    void put_skipsRedundantWriteForSameMapping() {
        store.put("INV-1", "foo@bar.com", "wsContact_a");
        store.put("INV-1", "Foo@Bar.com", "wsContact_a"); // same normalized mapping

        verify(keyValueStore, times(1)).put("gcal_user_key_index:INV-1:foo@bar.com", "wsContact_a");
    }

    @Test
    void put_writesAgainWhenTokenKeyChanges() {
        store.put("INV-1", "foo@bar.com", "wsContact_a");
        store.put("INV-1", "foo@bar.com", "wsContact_b"); // re-key -> writes through

        verify(keyValueStore).put("gcal_user_key_index:INV-1:foo@bar.com", "wsContact_a");
        verify(keyValueStore).put("gcal_user_key_index:INV-1:foo@bar.com", "wsContact_b");
    }

    @Test
    void put_writesReverseKeyToEmailMapping_normalized() {
        store.put("INV-1", "  Foo@Bar.com ", "wsContact_1ba35a88");

        // reverse: tokenKey -> normalized email, so the reconnect/failure path can recover the
        // email from a wsContact_<UUID> key (which carries no email).
        verify(keyValueStore).put("gcal_email_by_key:INV-1:wsContact_1ba35a88", "foo@bar.com");
    }

    @Test
    void getEmailByKey_returnsStoredEmail() {
        when(keyValueStore.get("gcal_email_by_key:INV-1:wsContact_1ba35a88")).thenReturn("foo@bar.com");

        assertEquals("foo@bar.com", store.getEmailByKey("INV-1", "wsContact_1ba35a88"));
    }

    @Test
    void getEmailByKey_returnsNullWhenAbsent() {
        when(keyValueStore.get(anyString())).thenReturn(null);

        assertNull(store.getEmailByKey("INV-1", "wsContact_1ba35a88"));
    }

    @Test
    void getEmailByKey_returnsNullForBlankTokenKey() {
        assertNull(store.getEmailByKey("INV-1", "  "));
        verify(keyValueStore, never()).get(anyString());
    }

    @Test
    void put_skipsRedundantReverseWriteForSameMapping() {
        store.put("INV-1", "foo@bar.com", "wsContact_a");
        store.put("INV-1", "Foo@Bar.com", "wsContact_a"); // same normalized mapping

        verify(keyValueStore, times(1)).put("gcal_email_by_key:INV-1:wsContact_a", "foo@bar.com");
    }
}
