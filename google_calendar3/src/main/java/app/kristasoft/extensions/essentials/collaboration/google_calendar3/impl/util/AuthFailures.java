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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util;

import app.krista.extension.authorization.MustAuthorizeException;

/**
 * Classifies a failure as "this user must reauthorize", so the background sweeps can log it as the
 * expected, user-actionable state it is instead of as a defect.
 *
 * <p><b>Why this exists.</b> {@link MustAuthorizeException} is control flow, not a failure: it is
 * the signal that tells the platform to launch the OAuth consent flow. A user whose Google refresh
 * token has expired or been revoked produces one on every attempt, and the timer-driven sweeps in
 * {@code CalendarWatchService} retry every user in the scan set on every tick — forever, until that
 * user reconnects. Logged with a stack trace at ERROR, and logged twice (once where the client is
 * built, again where the sweep catches the wrapper), one stale user cost roughly 33 log lines per
 * tick, about 3,000 lines a day.
 *
 * <p>The trace carried nothing worth that: every occurrence has the identical frame list — same
 * scheduler, same sweep, same provider — and its deepest cause is our own
 * {@code createMustAuthorizationException} rather than anything Google reported. The class name and
 * message are the whole diagnostic content.
 *
 * <p>Nor is the log line the only record. The condition is already published twice over:
 * {@code CalendarConnectionStatusStore} records {@code REAUTH_REQUIRED}, which raises the user's
 * reconnect banner in Conversation Agent. The log is the least load-bearing of the three channels.
 */
public final class AuthFailures {

    /** Bounds the cause-chain walk, so a cyclic chain cannot hang a sweep. */
    private static final int MAX_CAUSE_DEPTH = 10;

    private AuthFailures() {
    }

    /**
     * True when {@code failure}, or anything in its cause chain, is the platform's "user must
     * reauthorize" signal.
     *
     * <p>The chain has to be walked rather than type-tested directly: a sweep can reach this
     * through a {@code RuntimeException} wrapper added where the Calendar client is built, and
     * {@code GoogleProvider} classifies a refresh failure one layer below that again.
     *
     * @param failure the failure to classify; may be null
     * @return true when the user must reauthorize before this call can ever succeed
     */
    public static boolean needsReauthorization(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof MustAuthorizeException) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                return false;
            }
            current = next;
        }
        return false;
    }

    /**
     * The reauthorization reason as the one line worth keeping: the message the platform will show
     * the user. Falls back to the signal's simple name when it carries no message, so a log line is
     * never left saying {@code null}.
     *
     * @param failure the failure to describe; may be null
     * @return a non-null, single-line description
     */
    public static String describe(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof MustAuthorizeException) {
                String message = current.getMessage();
                return message != null && !message.isBlank()
                        ? message.trim()
                        : current.getClass().getSimpleName();
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        if (failure == null) {
            return "unknown";
        }
        String message = failure.getMessage();
        return message != null && !message.isBlank()
                ? message.trim()
                : failure.getClass().getSimpleName();
    }
}
