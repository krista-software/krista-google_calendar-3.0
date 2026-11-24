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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RefreshTokenStore} encryption-at-rest (spec §F.2).
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenStoreTest {

    @Mock
    private KeyValueStore keyValueStore;

    /** Deterministic AES-256 key for tests. */
    private static final String TEST_AES_KEY =
            Base64.getEncoder().encodeToString(new byte[]{
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                    17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});

    private RefreshTokenStore plaintextStore;
    private RefreshTokenStore encryptedStore;

    @BeforeEach
    void setUp() {
        plaintextStore = new RefreshTokenStore(keyValueStore, null);
        encryptedStore = new RefreshTokenStore(keyValueStore, TEST_AES_KEY);
    }

    @Test
    @DisplayName("plaintext mode stores the token verbatim")
    void put_plaintextWhenNoKey() {
        plaintextStore.put("user@example.com", "1//some-refresh-token-value");

        verify(keyValueStore).put("user@example.com", "1//some-refresh-token-value");
    }

    @Test
    @DisplayName("encrypted mode writes a v1-prefixed ciphertext (not the plaintext)")
    void put_encryptsWhenKeyPresent() {
        encryptedStore.put("user@example.com", "1//some-refresh-token-value");

        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(keyValueStore).put(eq("user@example.com"), valueCaptor.capture());
        String written = (String) valueCaptor.getValue();
        assertTrue(written.startsWith(RefreshTokenStore.ENC_PREFIX),
                "Expected enc1: prefix, got: " + written);
        assertFalse(written.contains("some-refresh-token-value"),
                "Plaintext leaked into stored value: " + written);
    }

    @Test
    @DisplayName("round-trip: encrypted token decrypts back to original")
    void encryptDecryptRoundTrip() {
        // We can't easily simulate KeyValueStore state with mocks, so capture the
        // encrypted write and feed it back as the get() response.
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);

        encryptedStore.put("user@example.com", "1//some-refresh-token-value");
        verify(keyValueStore).put(eq("user@example.com"), valueCaptor.capture());

        when(keyValueStore.get("user@example.com")).thenReturn(valueCaptor.getValue());

        String roundTripped = encryptedStore.get("user@example.com");
        assertEquals("1//some-refresh-token-value", roundTripped);
    }

    @Test
    @DisplayName("backward-compat: plaintext stored values are read verbatim by encrypted store")
    void readBackwardCompatPlaintext() {
        when(keyValueStore.get("user@example.com")).thenReturn("legacy-plaintext-token");

        assertEquals("legacy-plaintext-token", encryptedStore.get("user@example.com"));
    }

    @Test
    @DisplayName("get returns null when no value stored")
    void getReturnsNullForMissing() {
        when(keyValueStore.get("missing")).thenReturn(null);

        assertNull(encryptedStore.get("missing"));
    }

    @Test
    @DisplayName("decryption failure (wrong key) returns null and does not throw")
    void wrongKeyReturnsNull() {
        // Encrypt with one key, attempt to decrypt with a different one.
        encryptedStore.put("k", "secret-token-data");
        ArgumentCaptor<Object> writeCaptor = ArgumentCaptor.forClass(Object.class);
        verify(keyValueStore).put(eq("k"), writeCaptor.capture());

        String differentKey = Base64.getEncoder().encodeToString(new byte[32]); // all zeros
        RefreshTokenStore wrongKeyStore = new RefreshTokenStore(keyValueStore, differentKey);
        when(keyValueStore.get("k")).thenReturn(writeCaptor.getValue());

        assertNull(wrongKeyStore.get("k"));
    }

    @Test
    @DisplayName("missing key but encrypted data in store returns null (operator must configure key)")
    void noKeyCannotDecryptCipherText() {
        encryptedStore.put("k", "secret");
        ArgumentCaptor<Object> writeCaptor = ArgumentCaptor.forClass(Object.class);
        verify(keyValueStore).put(eq("k"), writeCaptor.capture());

        when(keyValueStore.get("k")).thenReturn(writeCaptor.getValue());
        assertNull(plaintextStore.get("k"));
    }

    @Test
    @DisplayName("invalid base64 key falls back to plaintext mode")
    void invalidBase64KeyFallsBackToPlaintext() {
        RefreshTokenStore bogus = new RefreshTokenStore(keyValueStore, "not-base64!@#$");
        bogus.put("k", "tok");

        verify(keyValueStore).put("k", "tok");
    }

    @Test
    @DisplayName("wrong-length AES key falls back to plaintext mode")
    void wrongLengthKeyFallsBackToPlaintext() {
        // 8 bytes — not valid for AES
        String shortKey = Base64.getEncoder().encodeToString(new byte[8]);
        RefreshTokenStore bogus = new RefreshTokenStore(keyValueStore, shortKey);
        bogus.put("k", "tok");

        verify(keyValueStore).put("k", "tok");
    }

    @Test
    @DisplayName("remove delegates to KeyValueStore")
    void remove_delegates() {
        encryptedStore.remove("k");
        verify(keyValueStore).remove("k");
    }
}
