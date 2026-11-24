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
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Persists Google Calendar OAuth refresh tokens.
 *
 * <p>Refresh tokens are encrypted at rest using AES-256/GCM when the key
 * environment variable {@value #KEY_ENV} is set (spec §F.2). Tokens written
 * before encryption was enabled (no {@code enc1:} prefix) are read in plaintext
 * for backwards compatibility — new writes encrypt unconditionally when the key
 * is present.
 *
 * <p>If the env var is missing, the store falls back to plaintext storage with
 * a one-time WARN so existing deployments keep working but operators see they
 * need to configure the key.
 */
@Service
public final class RefreshTokenStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefreshTokenStore.class);

    /**
     * Base64-encoded AES key (16, 24, or 32 bytes after decoding for AES-128,
     * AES-192, or AES-256). Read once at construction.
     */
    static final String KEY_ENV = "GOOGLE_REFRESH_TOKEN_AES_KEY";

    /** Marker on stored ciphertext so reads can distinguish encrypted from plaintext. */
    static final String ENC_PREFIX = "enc1:";

    /** GCM nonce length in bytes. 12 is the NIST-recommended size. */
    private static final int GCM_NONCE_BYTES = 12;
    /** GCM authentication-tag length in bits. */
    private static final int GCM_TAG_BITS = 128;

    private final KeyValueStore keyValueStore;
    private final SecretKey aesKey;
    private final SecureRandom rng = new SecureRandom();

    @Inject
    public RefreshTokenStore(KeyValueStore keyValueStore) {
        this(keyValueStore, System.getenv(KEY_ENV));
    }

    /** Package-private for tests so they can supply a deterministic key. */
    RefreshTokenStore(KeyValueStore keyValueStore, String keyEnvValue) {
        this.keyValueStore = keyValueStore;
        this.aesKey = decodeKeyOrNull(keyEnvValue);
        if (aesKey == null) {
            LOGGER.warn("RefreshTokenStore: {} not configured — refresh tokens will be stored "
                    + "in plaintext. Set the env var to a Base64-encoded 32-byte key to enable "
                    + "AES-256/GCM encryption at rest.", KEY_ENV);
        }
    }

    private static SecretKey decodeKeyOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(raw.trim());
            if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
                LOGGER.error("RefreshTokenStore: {} decoded to {} bytes; require 16/24/32 for "
                        + "AES-128/192/256. Falling back to plaintext.", KEY_ENV, keyBytes.length);
                return null;
            }
            return new SecretKeySpec(keyBytes, "AES");
        } catch (IllegalArgumentException e) {
            LOGGER.error("RefreshTokenStore: {} is not valid Base64. Falling back to plaintext.", KEY_ENV);
            return null;
        }
    }

    public void put(String key, String refToken) {
        if (refToken == null) {
            keyValueStore.put(key, null);
            return;
        }
        keyValueStore.put(key, encryptIfPossible(refToken));
    }

    public String get(String key) {
        Object raw = keyValueStore.get(key);
        if (raw == null) {
            return null;
        }
        return decryptIfNeeded(raw.toString());
    }

    public void remove(String key) {
        keyValueStore.remove(key);
    }

    /**
     * Retrieves a list of all refresh token keys.
     *
     * @return A list of all keys for which refresh tokens are stored.
     */
    public List<String> getKey() {
        Set<String> keys = keyValueStore.getKeys();
        return new ArrayList<>(keys);
    }

    // -------------------------------------------------------------------------
    // Encryption helpers
    // -------------------------------------------------------------------------

    private String encryptIfPossible(String plaintext) {
        if (aesKey == null) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[GCM_NONCE_BYTES];
            rng.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // Failing closed (refusing to store) would break the OAuth flow; failing
            // open (plaintext) preserves availability and is no worse than the
            // pre-encryption baseline. Log loudly so ops notice.
            LOGGER.error("RefreshTokenStore encryption failed; storing plaintext: {}", e.getMessage());
            return plaintext;
        }
    }

    private String decryptIfNeeded(String stored) {
        if (!stored.startsWith(ENC_PREFIX)) {
            // Plaintext (either by configuration or pre-encryption data).
            return stored;
        }
        if (aesKey == null) {
            LOGGER.error("RefreshTokenStore: encrypted value found in store but no key "
                    + "configured — cannot decrypt. Set {} to read existing data.", KEY_ENV);
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(ENC_PREFIX.length()));
            if (combined.length <= GCM_NONCE_BYTES) {
                LOGGER.error("RefreshTokenStore: ciphertext too short ({} bytes)", combined.length);
                return null;
            }
            byte[] iv = new byte[GCM_NONCE_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_NONCE_BYTES);
            byte[] ct = new byte[combined.length - GCM_NONCE_BYTES];
            System.arraycopy(combined, GCM_NONCE_BYTES, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ct);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("RefreshTokenStore decryption failed: {}", e.getMessage());
            return null;
        }
    }
}
