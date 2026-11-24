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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.CalendarConnectionStatusEventPublisher;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.function.BooleanSupplier;

/**
 * Persists the last-known Google Calendar connection status for each user and publishes it to
 * Meeting Agent.
 *
 * <p>Key format: {@code gcal_conn_status:{userId}:{invokerId}}
 * <p>Value: {@link CalendarConnectionStatus#name()} string.
 *
 * <h2>Two writers, and exactly one of them is live at a time</h2>
 * Always On is either/or (owner ruling, 2026-11-10), and so is this store:
 * <ul>
 *   <li><b>Opt-In (Always On off)</b> — the personal-token writers: {@link #recordConnected} on a
 *       successful OAuth callback or reconciliation, and the {@code put} overloads on a refresh
 *       failure ({@code REAUTH_REQUIRED} / {@code RENEWAL_FAILED}) or a deliberate disconnect.
 *       {@link #put} publishes only on a real <em>transition</em>; {@link #recordConnected}
 *       publishes <em>unconditionally</em> so authentication always re-registers the user's
 *       calendar system with Meeting Agent.</li>
 *   <li><b>Always On</b> — the workspace-credential writers:
 *       {@link #recordDelegatedConnected} after a successful delegated read, and
 *       {@link #recordDelegatedUnreachable} when a mailbox can no longer be read. Neither ever
 *       publishes {@code REAUTH_REQUIRED} or {@code RENEWAL_FAILED}: those ask a person to
 *       re-authenticate something they cannot, because nobody signed in for a delegated read.</li>
 * </ul>
 * While Always On is on, every personal-token writer is a no-op: it neither writes the local
 * status nor publishes. "Connected" then means exactly one thing — this extension can read the
 * calendar through domain-wide delegation — and a sign-in or token failure on the side cannot
 * contradict it. The tokens themselves are untouched, so switching Always On off goes straight back
 * to Opt-In.
 *
 * @since 3.0.2
 */
@Service
@Singleton
public class CalendarConnectionStatusStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarConnectionStatusStore.class);

    private final KeyValueStore keyValueStore;

    /**
     * Optional publisher. Null-safe: if the publisher is absent the store still
     * functions, just without ESB notifications (e.g. in unit tests).
     */
    private CalendarConnectionStatusEventPublisher publisher;

    /**
     * Optional reverse {@code tokenKey -> email} index. Null-safe. Lets the publish path
     * recover an email for opaque client {@code wsContact_<UUID>} keys (which carry no email)
     * when {@link #resolveEmail} can't derive one — populated while the token is healthy.
     */
    private UserKeyIndexStore userKeyIndexStore;

    /**
     * Answers "is Always On on right now?". Optional: until it is wired the store behaves as it
     * always has (Opt-In), which is the safe reading for tests and lightweight callers.
     */
    private BooleanSupplier alwaysOnMode;

    @Inject
    public CalendarConnectionStatusStore(KeyValueStore keyValueStore) {
        this.keyValueStore = keyValueStore;
    }

    /**
     * Inject the publisher after construction. Called from {@code GoogleCalendarExtension}
     * once both beans are available.
     */
    public void setPublisher(CalendarConnectionStatusEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Inject the reverse email index after construction (same wiring point as
     * {@link #setPublisher}). Optional — when absent the publish falls back to
     * {@link #resolveEmail} only.
     */
    public void setUserKeyIndexStore(UserKeyIndexStore userKeyIndexStore) {
        this.userKeyIndexStore = userKeyIndexStore;
    }

    /**
     * Inject the live Always On reading after construction (same wiring point as
     * {@link #setPublisher}). It switches the personal-token writers off while Always On is on.
     *
     * @param alwaysOnMode true while this extension is in Always On mode; read on every write
     */
    public void setAlwaysOnMode(BooleanSupplier alwaysOnMode) {
        this.alwaysOnMode = alwaysOnMode;
    }

    /**
     * Persists the status. If a publisher is configured, publishes to CA on transitions only.
     *
     * <p>{@code invokerId} scopes the local KV-store key; {@code workspaceId} ({@code ws_…}) is
     * what the published event carries and what CA keys the user/tenant on. They are distinct —
     * passing the invoker id as the workspaceId makes CA drop the event. The publish is skipped
     * when {@code workspaceId} is null/blank (we never fall back to the invoker id).
     *
     * @param userId      the user key (email or key stored in RefreshTokenStore)
     * @param invokerId   the invoker identifier — scopes the local KV-store key only
     * @param status      the new connection status
     * @param workspaceId the real workspace id published in the event
     */
    public void put(String userId, String invokerId, CalendarConnectionStatus status, String workspaceId) {
        put(userId, invokerId, status, workspaceId, null);
    }

    /**
     * Overload carrying the optional disconnect-intent {@code reason} (proto field 9).
     * Meaningful only when {@code status=DISCONNECTED}: {@code "USER_INITIATED"} tells CA to
     * delete the row; {@code null} / {@code "ACCESS_REVOKED"} is the involuntary default.
     */
    public void put(String userId, String invokerId, CalendarConnectionStatus status, String workspaceId,
                    String reason) {
        if (personalWriterSuppressed("put " + status, userId)) {
            return;
        }
        CalendarConnectionStatus prior = get(userId, invokerId);
        if (status == prior) {
            return;
        }
        writeResolveAndPublish(userId, invokerId, workspaceId, prior, status,
                null, null, null, true, System.currentTimeMillis(), reason);
    }

    /**
     * Records {@link CalendarConnectionStatus#CONNECTED} and re-asserts it to CA
     * <strong>unconditionally</strong> — unlike {@link #put}, this is NOT transition-gated.
     *
     * <p>Used on explicit authentication success so the Conversation Agent registers the
     * user's Google calendar system on <em>every</em> authentication. A user may already
     * be CONNECTED in this store (the {@code KeyValueStore} write happens regardless of
     * whether the publish reached CA), in which case a transition-gated {@link #put} would
     * skip the publish and the user would never get registered by re-authenticating. This
     * mirrors the KIP (Microsoft Teams) extension, which publishes a connected event on
     * every auth.
     *
     * <p>{@code invokerId} scopes the local {@link KeyValueStore} key only; {@code workspaceId}
     * is what the published event carries (and what CA keys the user/tenant on). They are
     * distinct: passing the invoker id ({@code INV_…}) as the workspaceId makes CA unable to
     * match the event to the user, so the calendar system is never registered.
     *
     * @param userId      the user key (email, {@code email#suffix}, or {@code wsContact_<id>})
     * @param invokerId   the invoker identifier — scopes the local KV-store key only
     * @param workspaceId the real workspace id ({@code ws_…}) published in the event; the send is
     *                    skipped if this is null/blank (we never fall back to the invoker id)
     * @param email       the authenticated user's email, or {@code null} to resolve it from the key
     *                    (required for client {@code wsContact_<id>} keys that carry no email)
     */
    public void recordConnected(String userId, String invokerId, String workspaceId, String email) {
        if (personalWriterSuppressed("recordConnected", userId)) {
            return;
        }
        CalendarConnectionStatus prior = get(userId, invokerId);
        writeResolveAndPublish(userId, invokerId, workspaceId, prior, CalendarConnectionStatus.CONNECTED,
                email, null, null, false, System.currentTimeMillis(), null);
    }

    /**
     * Overload that lets callers supply rich context when the workspaceId, email, or
     * errorCode are known at the call-site (e.g. on explicit failure paths).
     */
    public void put(String userId, String invokerId, CalendarConnectionStatus status,
                    String workspaceId, String email, String errorCode, String errorMessage) {
        if (personalWriterSuppressed("put " + status, userId)) {
            return;
        }
        long transitionAt = System.currentTimeMillis();
        CalendarConnectionStatus prior = get(userId, invokerId);
        if (status == prior) {
            return;
        }
        keyValueStore.put(key(userId, invokerId), status.name());

        if (publisher != null) {
            String priorStr = prior != null ? prior.name() : null;
            publisher.publishOnTransition(workspaceId, email, priorStr, status.name(),
                    errorCode, errorMessage, transitionAt);
        }
    }

    /**
     * Convenience overload for failure paths that have errorCode/errorMessage but
     * only a raw {@code userId} (e.g. {@code wsContact_{invokerId}_{email}}). The
     * email is resolved via the same {@link #resolveEmail} logic the 3-arg
     * {@link #put(String, String, CalendarConnectionStatus)} overload uses, so
     * the published event carries a real email rather than the compound userId.
     *
     * <p>Skips publish when {@code resolveEmail} returns {@code null} (logs a
     * warning at the same severity as the 3-arg overload).
     */
    public void put(String userId, String invokerId, CalendarConnectionStatus status,
                    String workspaceId, String errorCode, String errorMessage) {
        if (personalWriterSuppressed("put " + status, userId)) {
            return;
        }
        CalendarConnectionStatus prior = get(userId, invokerId);
        if (status == prior) {
            return;
        }
        writeResolveAndPublish(userId, invokerId, workspaceId, prior, status,
                null, errorCode, errorMessage, true, System.currentTimeMillis(), null);
    }

    // ── Always On: the workspace-credential writers ───────────────────────

    /**
     * Always On: records {@link CalendarConnectionStatus#CONNECTED} after this extension read the
     * mailbox through domain-wide delegation, and publishes it <strong>unconditionally</strong>.
     *
     * <p>Re-asserted on every successful sweep rather than transition-gated: the local write
     * happens whether or not the publish reached Meeting Agent, so a gated publish would let one
     * lost event stand for ever. Meeting Agent de-duplicates.
     *
     * <p>Deliberately not subject to the personal-writer switch: this is the Always On writer.
     *
     * @param userId      the mailbox key, as the sweep holds it (a seat address)
     * @param invokerId   the invoker identifier — scopes the local KV-store key only
     * @param workspaceId the real workspace id published in the event
     * @param email       the mailbox address, or {@code null} to resolve it from the key
     * @return {@code true} if the event was published
     */
    public boolean recordDelegatedConnected(String userId, String invokerId, String workspaceId,
                                            String email) {
        CalendarConnectionStatus prior = get(userId, invokerId);
        return writeResolveAndPublish(userId, invokerId, workspaceId, prior,
                CalendarConnectionStatus.CONNECTED, email, null, null, false,
                System.currentTimeMillis(), null);
    }

    /**
     * Always On: records that a mailbox can no longer be read through domain-wide delegation — the
     * attempt failed, the Workspace directory no longer lists it, or it left the scanned set.
     *
     * <p>The local status becomes {@link CalendarConnectionStatus#DISCONNECTED} either way, so the
     * next successful read is a real transition and the scanned-mailbox report can tell "last read
     * failed" from "never tried". The event is published only when the mailbox was previously
     * {@link CalendarConnectionStatus#CONNECTED}: Connected must stop being true the moment it
     * stops being true, but a seat this extension never reached gets no status at all — Meeting
     * Agent learns about it from the scanned-mailbox snapshot instead.
     *
     * <p>Always {@code reason=}{@link CalendarDisconnectReason#ACCESS_REVOKED}: nobody chose this,
     * and nobody can fix it by signing in, so it is never {@code REAUTH_REQUIRED}.
     *
     * @param userId       the mailbox key, as the sweep holds it
     * @param invokerId    the invoker identifier — scopes the local KV-store key only
     * @param workspaceId  the real workspace id published in the event
     * @param email        the mailbox address, or {@code null} to resolve it from the key
     * @param errorCode    the provider's error identifier, or this extension's reason code
     * @param errorMessage the provider's error message, or a sentence explaining the reason code
     * @return {@code true} if a DISCONNECTED event was published
     */
    public boolean recordDelegatedUnreachable(String userId, String invokerId, String workspaceId,
                                              String email, String errorCode, String errorMessage) {
        CalendarConnectionStatus prior = get(userId, invokerId);
        if (prior != CalendarConnectionStatus.CONNECTED) {
            if (prior != CalendarConnectionStatus.DISCONNECTED) {
                keyValueStore.put(key(userId, invokerId), CalendarConnectionStatus.DISCONNECTED.name());
            }
            return false;
        }
        return writeResolveAndPublish(userId, invokerId, workspaceId, prior,
                CalendarConnectionStatus.DISCONNECTED, email, errorCode, errorMessage, false,
                System.currentTimeMillis(), CalendarDisconnectReason.ACCESS_REVOKED);
    }

    /**
     * Whether a personal-token writer must stand down because Always On is on.
     *
     * <p>An unreadable mode reads as Opt-In, matching the discovery seam: the failure it guards
     * against is a stale personal status, and suppressing every status on a read error would be
     * worse.
     */
    private boolean personalWriterSuppressed(String operation, String userId) {
        if (alwaysOnMode == null) {
            return false;
        }
        boolean alwaysOn;
        try {
            alwaysOn = alwaysOnMode.getAsBoolean();
        } catch (RuntimeException modeFailure) {
            LOGGER.warn("Could not read the Always On mode before {} for userId='{}'; treating it as off: {}",
                    operation, userId, modeFailure.getMessage());
            return false;
        }
        if (alwaysOn) {
            LOGGER.debug("Always On is on: personal-token status write '{}' for userId='{}' is not applied",
                    operation, userId);
        }
        return alwaysOn;
    }

    /**
     * Forces {@code status} and publishes it to CA <strong>unconditionally</strong>
     * (NOT transition-gated) — for the admin/testing endpoint that exercises the
     * various connection-status flows on demand. Mirrors the KIP (Microsoft Teams)
     * admin force-status endpoint.
     *
     * <p>The status is always persisted to the {@link KeyValueStore} (even when the
     * publish is skipped). The email is taken from {@code email} when supplied —
     * required for client {@code wsContact_<id>} keys that carry no email — otherwise
     * resolved from {@code userId} via {@link #resolveEmail}.
     *
     * @param userId       the user key (email, {@code email#suffix}, or {@code wsContact_<id>})
     * @param invokerId    the invoker identifier — scopes the local KV-store key only
     * @param status       the status to force
     * @param workspaceId  the real workspace id ({@code ws_…}) published in the event
     * @param email        the user's email, or {@code null} to resolve it from the key
     * @param errorCode    optional error code carried in the event (e.g. {@code ADMIN_FORCE_SET})
     * @param errorMessage optional error message carried in the event
     * @return {@code true} if the event was published; {@code false} if it was skipped
     *         (no publisher configured, email unresolvable, or publisher validation failed)
     */
    public boolean forceStatus(String userId, String invokerId, CalendarConnectionStatus status,
                               String workspaceId, String email, String errorCode, String errorMessage) {
        CalendarConnectionStatus prior = get(userId, invokerId);
        return writeResolveAndPublish(userId, invokerId, workspaceId, prior, status,
                email, errorCode, errorMessage, false, System.currentTimeMillis(), null);
    }

    /**
     * Shared tail for the status writes that resolve the email from {@code userId}:
     * persists {@code status}, resolves the email (skipping the publish with a WARN when
     * it cannot be derived), then publishes to CA. {@code gated == true} routes through
     * {@link CalendarConnectionStatusEventPublisher#publishOnTransition} (callers pre-gate
     * on prior != next); {@code gated == false} publishes unconditionally
     * ({@link #recordConnected}, {@link #forceStatus}).
     *
     * @return {@code true} if the event was published; {@code false} if it was skipped
     *         (no publisher, email unresolvable, or publisher validation/transition gate).
     */
    private boolean writeResolveAndPublish(String userId, String invokerId, String workspaceId,
                                           CalendarConnectionStatus prior, CalendarConnectionStatus status,
                                           String explicitEmail, String errorCode, String errorMessage,
                                           boolean gated, long occurredAt, String reason) {
        keyValueStore.put(key(userId, invokerId), status.name());
        if (publisher == null) {
            return false;
        }
        String email = (explicitEmail != null && !explicitEmail.isBlank())
                ? explicitEmail.trim().toLowerCase(java.util.Locale.ROOT)
                : resolveEmail(userId);
        if (email == null && userKeyIndexStore != null && userId != null) {
            // Opaque client key (wsContact_<UUID>) carries no email and the token may be broken,
            // so recover the email from the reverse index written while the token was healthy.
            // This is what lets an auto-detected REAUTH_REQUIRED reach CA for client users.
            email = userKeyIndexStore.getEmailByKey(invokerId, userId);
        }
        if (email == null) {
            LOGGER.warn("Skipping status publish to CA — cannot resolve email from userId='{}' "
                    + "(status {} -> {}, errorCode={}); the transition will not appear in CA for this user",
                    userId, prior, status, errorCode);
            return false;
        }
        String priorStr = prior != null ? prior.name() : null;
        if (gated) {
            return publisher.publishOnTransition(workspaceId, email, priorStr, status.name(),
                    errorCode, errorMessage, occurredAt, reason);
        }
        return publisher.publish(workspaceId, email, priorStr, status.name(),
                errorCode, errorMessage, occurredAt, reason);
    }

    /** Returns the stored status, or {@code null} if none has been recorded yet. */
    public CalendarConnectionStatus get(String userId, String invokerId) {
        Object raw = keyValueStore.get(key(userId, invokerId));
        if (raw == null) {
            return null;
        }
        try {
            return CalendarConnectionStatus.valueOf(raw.toString());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unknown CalendarConnectionStatus value '{}' for userId={}", raw, userId);
            return null;
        }
    }

    public void remove(String userId, String invokerId) {
        keyValueStore.remove(key(userId, invokerId));
    }

    private static String key(String userId, String invokerId) {
        return "gcal_conn_status:" + userId + ":" + invokerId;
    }

    /** Workspace-contact prefix per {@code Constants.WS_CONTACT}; matched explicitly
     *  so plain emails containing {@code _} (legal in the local part) aren't
     *  incorrectly split. */
    private static final String WS_CONTACT_PREFIX = "wsContact_";

    /** The Meeting-Agent-driven reconnect key form: {@code workspace_auth:{workspaceId}:{email}}. */
    private static final String WORKSPACE_AUTH_PREFIX = "workspace_auth:";

    /**
     * Derives an email address from the userId. The Google module passes one of:
     * <ul>
     *   <li>a plain email — {@code foo@bar.com} — possibly with an
     *       AuthenticationResource-style {@code #suffix}.</li>
     *   <li>a workspace-contact compound key — {@code wsContact_{invokerId}_{email}}.</li>
     * </ul>
     *
     * <p>Returns {@code null} when no {@code @} can be located.
     *
     * @param userId the user key
     * @return the lower-cased address, or {@code null} when the key carries none
     */
    public static String resolveEmail(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        if (userId.startsWith(WORKSPACE_AUTH_PREFIX)) {
            int separator = userId.indexOf(':', WORKSPACE_AUTH_PREFIX.length());
            String address = separator > 0 ? userId.substring(separator + 1).trim() : "";
            return address.contains("@") ? address.toLowerCase(java.util.Locale.ROOT) : null;
        }
        // AuthenticationResource splits keys on '#' (see key.split("#")[0]);
        // mirror that here so "email#suffix" resolves correctly.
        String bare = userId;
        int hashIdx = bare.indexOf('#');
        if (hashIdx >= 0) {
            bare = bare.substring(0, hashIdx);
        }
        if (!bare.contains("@")) {
            return null;
        }
        // Workspace-contact compound key: scan from the last underscore-delimited
        // segment forward for the email. Only applies to keys with the known
        // prefix — a plain email such as "foo_bar@baz.com" must NOT be split.
        if (bare.startsWith(WS_CONTACT_PREFIX)) {
            String[] parts = bare.split("_");
            for (int i = parts.length - 1; i >= 0; i--) {
                if (parts[i].contains("@")) {
                    return parts[i].trim().toLowerCase(java.util.Locale.ROOT);
                }
            }
            return null;
        }
        return bare.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
