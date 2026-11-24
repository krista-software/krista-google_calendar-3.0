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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarSyncTelemetryService;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Draining consumer for the legacy {@code krista.calendar.reminder.send.google} queue (Google
 * Calendar variant).
 *
 * <p>The SUFFIX is load-bearing and the javadoc used to omit it. Unsuffixed, this extension and
 * the Teams extension would compete on one queue and each would steal half the other's reminders
 * — the destination the consumer actually binds has always carried {@code .google}
 * ({@code GoogleExtensionConfig#QUEUE_CALENDAR_REMINDER_SEND}).
 *
 * <h2>Why this listener still exists, and why it does nothing</h2>
 *
 * <p>The reminder loop is a request/reply exchange on
 * {@code ext.calendar.reminder.decide.google}: Meeting Agent asks this extension for a verdict and
 * MA sends the mail itself. This one-way queue is what that arrangement REPLACED, and the
 * {@code caTransport=http} branch that used to act on it went with the uncommissioned
 * {@code caTransport} attribute (owner ruling, 2026-09-11) — the extension is ESB-only now, so
 * there is no longer any path on which a message here should be handled. Handling it would
 * double-send: MA has already had its answer from the decide consumer.
 *
 * <p><b>It stays BOUND rather than being unbound, for two reasons.</b> Meeting Agent still falls
 * back to producing on this one-way queue when a decide request times out, and will until WP-E20
 * retires that fallback — so messages genuinely still arrive. And
 * {@code krista.calendar.reminder.send.google} is a DURABLE queue: with no consumer attached those
 * messages would accumulate on the broker indefinitely rather than being discarded. A consumer that
 * acknowledges is what keeps the queue drained and the operator able to see that MA's producer has
 * not moved yet.
 *
 * <p><b>With one exception, EF-08:</b> this consumer is bound only when the decide consumer is bound
 * too ({@code GoogleServiceBusManager.startReminderConsumers()}). Draining a queue whose
 * request/reply counterpart is not running does not tidy anything up — it destroys every reminder
 * Meeting Agent falls back to producing, with nothing anywhere to show it happened.
 *
 * <h2>WARN, not INFO — and counted (EF-08)</h2>
 *
 * <p>This used to log at INFO, on the reasoning that Meeting Agent falling back to its one-way
 * producer is an expected timeout path rather than a fault of this extension. That reading was too
 * generous about what happens next. Meeting Agent stamps {@code last_reminder_sent_at} when its
 * fallback returns, so it believes the reminder was sent — while what actually happened here is that
 * a <b>calendar-reconnect reminder was discarded</b>, and that is the one email that tells a user
 * their calendar has stopped syncing. Every message on this queue is a reminder nobody received, and
 * a message arriving here at all means Meeting Agent's decide request did not get an answer in time.
 * Both facts deserve to be visible.
 *
 * <p>The running total goes on the line for the same reason. One WARN reads as a blip; "discarded
 * 412 so far" is the signal that the decide consumer is not answering at all — which is exactly the
 * state {@code GoogleServiceBusManager.startReminderConsumers()} now refuses to start into, and the
 * state this counter would have made obvious earlier.
 *
 * @since 3.0.3
 */
@Service
public class CalendarReminderSendListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarReminderSendListener.class);

    /**
     * How many reminders this process has drained and discarded.
     *
     * <p>Process-local and deliberately not persisted: the question it answers is "is the decide
     * path answering right now", which a total surviving restarts would blur rather than sharpen.
     */
    private final AtomicLong discarded = new AtomicLong();

    private final CalendarSyncTelemetryService telemetryService;

    @Inject
    public CalendarReminderSendListener(CalendarSyncTelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    /**
     * Acknowledges and discards one {@link CalendarReminderSendProto} event from Meeting Agent.
     *
     * <p>No dedup, no rate-limit bookkeeping and no send: the decide consumer on
     * {@code ext.calendar.reminder.decide.google} owns all three, and touching any of them here
     * would either double-send the reminder or consume a rate-limit slot for a mail nobody sent.
     *
     * @param evt the event (no-op if {@code null})
     */
    public void handle(CalendarReminderSendProto evt) {
        if (evt == null) {
            LOGGER.warn("Received null CalendarReminderSendProto — ignoring");
            return;
        }

        long total = discarded.incrementAndGet();
        LOGGER.warn("A reconnect reminder was discarded (discardedTotal={}): ws={} email={}"
                        + " system={} correlationId={}. This extension is ESB-only, so the"
                        + " request/reply decide consumer on ext.calendar.reminder.decide.google is"
                        + " the only path that acts on a reminder — a message arriving on the one-way"
                        + " queue means Meeting Agent's decide request went unanswered and it fell"
                        + " back to producing here, having already stamped the reminder as sent."
                        + " Nobody received this email.",
                total, evt.getWorkspaceId(), evt.getEmail(), evt.getCalendarSystem(),
                evt.getCorrelationId());

        recordDiscard();
    }

    /** @return how many reminders this process has discarded; for tests and diagnostics */
    public long discardedCount() {
        return discarded.get();
    }

    /**
     * Counts the discard as a failed Meeting Agent interaction.
     *
     * <p>Never allowed to throw: this listener's whole job is to acknowledge the message so a durable
     * queue drains, and an unavailable metrics backend must not turn that into a redelivery loop.
     */
    private void recordDiscard() {
        try {
            telemetryService.recordApiCall("ma", "reminder_send_discarded", false);
        }
        catch (Exception metricsUnavailable) {
            LOGGER.debug("Could not record the discarded-reminder metric: {}",
                    metricsUnavailable.getMessage());
        }
    }
}
