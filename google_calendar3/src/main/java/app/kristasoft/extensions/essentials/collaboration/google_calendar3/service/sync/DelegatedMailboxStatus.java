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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync;

import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Always On's half of the connection-status channel: what a delegated sweep tells Meeting Agent
 * about each mailbox it reached, failed to reach, or stopped reaching.
 *
 * <p>The contract (owner rulings, 2026-11-10) is that <b>Connected means "this extension can read
 * this calendar and sync its events"</b>, and that it changes the moment that stops being true:
 * <ul>
 *   <li>a successful delegated read, watch, renewal or sync of an in-scope mailbox →
 *       {@code CONNECTED}, re-asserted on every sweep;</li>
 *   <li>a mailbox previously {@code CONNECTED} whose read failed, which the Workspace directory no
 *       longer lists, or which left the scanned set → {@code DISCONNECTED} with
 *       {@code reason=ACCESS_REVOKED} and the provider's error;</li>
 *   <li>a seat never reached → nothing. Meeting Agent learns about it from the scanned-mailbox
 *       snapshot.</li>
 * </ul>
 * The "previously CONNECTED" comparison is made against the local
 * {@link CalendarConnectionStatusStore}, by {@link CalendarConnectionStatusStore#recordDelegatedUnreachable}.
 *
 * <p>Callers use this only on a delegated, non-degraded sweep. A degraded sweep scanned nothing and
 * publishes nothing; an Opt-In sweep's statuses come from the personal-token paths.
 *
 * <p>Never throws: status delivery must not turn a sweep that did its work into a failed one, and a
 * failure here is retried by the next sweep anyway. Thread-safe: holds no mutable state, and the
 * store it writes is the invoker-scoped KV store the sweeps already share.
 */
public final class DelegatedMailboxStatus {

    private static final Logger LOGGER = LoggerFactory.getLogger(DelegatedMailboxStatus.class);

    /** errorCode for a registered seat a complete Workspace directory read no longer contains. */
    public static final String NOT_IN_DIRECTORY = "NOT_IN_DIRECTORY";

    /** errorCode for a mailbox that held a watch channel but is no longer in the scanned set. */
    public static final String OUT_OF_SCOPE = "OUT_OF_SCOPE";

    /** A provider message is diagnostic text for an admin, not a document; bound what is sent. */
    static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private static final int MAX_CAUSE_DEPTH = 10;

    /** {@code "error":"unauthorized_client"} inside a token-endpoint failure the auth library quotes. */
    private static final Pattern OAUTH_ERROR = Pattern.compile("\"error\"\\s*:\\s*\"([A-Za-z0-9_.-]+)\"");

    /**
     * A provider failure reduced to what the status event carries.
     *
     * @param code    Google's own error identifier where there is one, otherwise the failure's type
     * @param message Google's message where there is one, otherwise the failure's message; bounded
     */
    public record ProviderError(String code, String message) {
    }

    private final CalendarConnectionStatusStore store;
    private final Invoker invoker;

    /**
     * @param store   the status store; null means statuses are not wired (lightweight callers and
     *                tests), and every method becomes a no-op
     * @param invoker the invoker whose workspace the events carry
     */
    public DelegatedMailboxStatus(CalendarConnectionStatusStore store, Invoker invoker) {
        this.store = store;
        this.invoker = invoker;
    }

    /**
     * The mailbox was read through domain-wide delegation: publish {@code CONNECTED}.
     *
     * @param userId the mailbox, as the sweep holds it
     */
    public void reachable(String userId) {
        if (store == null || isBlank(userId)) {
            return;
        }
        try {
            store.recordDelegatedConnected(userId, invoker.getInvokerId(), invoker.getWorkspaceId(), null);
            LOGGER.debug("[GCAL-CONNECTION] Always On mailbox {} is readable: CONNECTED", userId);
        } catch (Exception statusFailure) {
            LOGGER.warn("[GCAL-CONNECTION] Could not record CONNECTED for Always On mailbox {}: {}",
                    userId, statusFailure.getMessage());
        }
    }

    /**
     * An attempt to read the mailbox through domain-wide delegation failed.
     *
     * @param userId the mailbox, as the sweep holds it
     * @param cause  what failed; its provider error is carried on the event
     */
    public void unreachable(String userId, Throwable cause) {
        ProviderError error = describe(cause);
        disconnect(userId, error.code(), error.message());
    }

    /**
     * Registered seats a complete read of the Workspace directory does not contain.
     *
     * @param seats the seats, lower-cased addresses
     */
    public void notInDirectory(Collection<String> seats) {
        for (String seat : seats) {
            disconnect(seat, NOT_IN_DIRECTORY, "The Google Workspace directory no longer lists this "
                    + "mailbox as an active user, so domain-wide delegation cannot read it.");
        }
    }

    /**
     * Mailboxes that held a watch channel but are no longer in the scanned set.
     *
     * @param userIds the mailbox keys, as the watch store holds them
     */
    public void leftScanSet(Collection<String> userIds) {
        for (String userId : userIds) {
            disconnect(userId, OUT_OF_SCOPE, "This mailbox is no longer in the set Always On scans "
                    + "(registered, licensed Meeting Agent users the Workspace directory lists), so its "
                    + "calendar is no longer read.");
        }
    }

    /**
     * Publishes the status of every mailbox one sweep attempted.
     *
     * @param outcomes what the sweep did to each mailbox
     */
    public void apply(ScannedMailboxReporter.SweepOutcomes outcomes) {
        if (outcomes == null) {
            return;
        }
        for (Map.Entry<String, ScannedMailboxReporter.SweepOutcomes.Outcome> entry : outcomes.all().entrySet()) {
            if (entry.getValue().proved()) {
                reachable(entry.getKey());
            }
            else {
                unreachable(entry.getKey(), entry.getValue().failure());
            }
        }
    }

    private void disconnect(String userId, String errorCode, String errorMessage) {
        if (store == null || isBlank(userId)) {
            return;
        }
        try {
            boolean published = store.recordDelegatedUnreachable(userId, invoker.getInvokerId(),
                    invoker.getWorkspaceId(), null, errorCode, errorMessage);
            if (published) {
                LOGGER.warn("[GCAL-CONNECTION] Always On mailbox {} can no longer be read: published "
                        + "DISCONNECTED ({}: {})", userId, errorCode, errorMessage);
            }
        } catch (Exception statusFailure) {
            LOGGER.warn("[GCAL-CONNECTION] Could not record DISCONNECTED for Always On mailbox {}: {}",
                    userId, statusFailure.getMessage());
        }
    }

    /**
     * Reduces a failure to the provider error a status event carries.
     *
     * <p>Google's own identifier wins wherever the cause chain carries one — the {@code reason} of a
     * Calendar API error ({@code forbidden}, {@code notFound}), the {@code error} of a token-endpoint
     * refusal ({@code unauthorized_client}, {@code invalid_grant}) — because that is what an admin
     * searches Google's documentation for. Otherwise the deepest failure's type and message.
     *
     * @param cause the failure; may be null
     * @return the error to publish, never null
     */
    public static ProviderError describe(Throwable cause) {
        if (cause == null) {
            return new ProviderError("UNKNOWN", "The mailbox could not be read.");
        }
        Throwable deepest = cause;
        String oauthCode = null;
        String oauthMessage = null;
        int depth = 0;
        for (Throwable current = cause; current != null && depth < MAX_CAUSE_DEPTH;
             current = current.getCause()) {
            depth++;
            deepest = current;
            if (current instanceof GoogleJsonResponseException response) {
                return fromApiError(response);
            }
            if (current instanceof TokenResponseException tokenFailure) {
                return fromTokenError(tokenFailure);
            }
            if (oauthCode == null && current.getMessage() != null) {
                Matcher matcher = OAUTH_ERROR.matcher(current.getMessage());
                if (matcher.find()) {
                    oauthCode = matcher.group(1);
                    oauthMessage = current.getMessage();
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        if (oauthCode != null) {
            return new ProviderError(oauthCode, bounded(oauthMessage));
        }
        String message = deepest.getMessage() != null ? deepest.getMessage() : cause.getMessage();
        return new ProviderError(deepest.getClass().getSimpleName(),
                bounded(message != null ? message : "The mailbox could not be read."));
    }

    private static ProviderError fromApiError(GoogleJsonResponseException response) {
        GoogleJsonError details = response.getDetails();
        String code = null;
        String message = null;
        if (details != null) {
            message = details.getMessage();
            if (details.getErrors() != null && !details.getErrors().isEmpty()) {
                code = details.getErrors().get(0).getReason();
            }
        }
        if (isBlank(code)) {
            code = "HTTP_" + response.getStatusCode();
        }
        if (isBlank(message)) {
            message = response.getStatusMessage() != null ? response.getStatusMessage() : response.getMessage();
        }
        return new ProviderError(code, bounded(message));
    }

    private static ProviderError fromTokenError(TokenResponseException tokenFailure) {
        String code = tokenFailure.getDetails() != null ? tokenFailure.getDetails().getError() : null;
        String message = tokenFailure.getDetails() != null
                ? tokenFailure.getDetails().getErrorDescription() : null;
        if (isBlank(code)) {
            code = "HTTP_" + tokenFailure.getStatusCode();
        }
        if (isBlank(message)) {
            message = tokenFailure.getMessage();
        }
        return new ProviderError(code, bounded(message));
    }

    private static String bounded(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.strip();
        return trimmed.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "…";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
