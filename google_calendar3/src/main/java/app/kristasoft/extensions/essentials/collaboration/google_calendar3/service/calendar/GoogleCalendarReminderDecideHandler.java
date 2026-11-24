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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar;

import app.krista.conversationagent.module.calendar.esb.dto.CalendarReminderDecideReply;
import app.krista.conversationagent.module.calendar.esb.dto.CalendarReminderDecideRequest;
import app.krista.conversationagent.module.shared.esb.ExtErrorCodes;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.notification.NotificationService;
import app.krista.infra.esb.service.ServiceEndpoint;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/**
 * Answers Meeting Agent's calendar-reconnect reminder question (KIM-492 WP-E16, spec §3.10).
 *
 * <p>Applies the two rules this extension owns — a one-hour dedup by {@code correlationId} and a
 * rolling limit of {@link NotificationService#MAX_NOTIFICATIONS} reminders per user per
 * {@link NotificationService#RATE_WINDOW_MILLIS} — and REPLIES with the verdict.
 *
 * <p><b>It never sends the email and never calls MA.</b> That is the whole point: the old one-way
 * listener applied the same two rules and then made a blocking HTTP callback from inside the JMS
 * consumer thread so MA would send. Now MA sends on {@code SEND} and does nothing on the other
 * three verdicts.
 *
 * <h2>Order of the checks</h2>
 *
 * <p>Dedup first, then rate limit — the order the one-way listener used, and the one that is
 * correct: a redelivered request for a decision already taken must not consume a rate-limit slot,
 * or an MA-side retry storm would silently exhaust a user's weekly allowance without a single email
 * being sent.
 *
 * <h2>Why a reservation, not a check</h2>
 *
 * <p>A {@code SEND} verdict reserves the slot immediately, because MA will act on it and nothing
 * comes back to tell us whether it did. Over-counting costs one missed nudge in a seven-day window;
 * under-counting mails somebody three times a day about a calendar they already know is broken.
 *
 * @since 3.1.0
 */
@Service
public class GoogleCalendarReminderDecideHandler
        implements GoogleCalendarReminderDecideService, ServiceEndpoint {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GoogleCalendarReminderDecideHandler.class);

    private final NotificationService notificationService;
    private final ReminderCorrelationDedup dedup;

    @Inject
    public GoogleCalendarReminderDecideHandler(NotificationService notificationService,
            ReminderCorrelationDedup dedup) {
        this.notificationService = notificationService;
        this.dedup = dedup;
    }

    @Override
    public CalendarReminderDecideReply googleCalendarReminderDecide(
            CalendarReminderDecideRequest request) {
        try {
            return decide(request);
        }
        catch (RuntimeException failure) {
            // A reply is mandatory: MA blocks on this call, and an exception here would leave it
            // waiting for its full timeout and then sending nothing at all. Answer REFUSED with an
            // INTERNAL_ERROR code so the sweeper can log a cause and try again next pass.
            LOGGER.error("Calendar reminder decision failed for {}: {}",
                    request == null ? "<null request>" : request.getEmail(), failure.getMessage(),
                    failure);
            return refused(request, ExtErrorCodes.INTERNAL_ERROR, failure.getMessage());
        }
    }

    private CalendarReminderDecideReply decide(CalendarReminderDecideRequest request) {
        if (request == null) {
            LOGGER.warn("Received a null CalendarReminderDecideRequest — refusing.");
            return refused(null, ExtErrorCodes.VALIDATION_ERROR, "no request payload");
        }

        String email = request.getEmail();
        String correlationId = request.getCorrelationId();

        LOGGER.info("Deciding calendar reminder: ws={} email={} system={} correlationId={}",
                request.getWorkspaceId(), email, request.getCalendarSystem(), correlationId);

        if (email == null || email.isBlank()) {
            return refused(request, ExtErrorCodes.VALIDATION_ERROR, "no recipient address");
        }

        if (dedup.isDuplicate(correlationId)) {
            LOGGER.info("Calendar reminder DEDUPED — correlationId={} already answered within {}ms",
                    correlationId, NotificationService.CORRELATION_DEDUP_MILLIS);
            return reply(CalendarReminderDecideReply.DEDUPED, correlationId,
                    "this correlationId was already answered", 0);
        }

        NotificationService.Outcome outcome = notificationService.reserveSendSlot(
                request.getWorkspaceId(), email, request.getCalendarSystem());

        return switch (outcome) {
            case SENT -> {
                LOGGER.info("Calendar reminder SEND for email={} correlationId={}", email, correlationId);
                yield reply(CalendarReminderDecideReply.SEND, correlationId, null, 0);
            }
            case RATE_LIMITED -> {
                long retryAfter = notificationService.secondsUntilSlotFree(email);
                LOGGER.info("Calendar reminder RATE_LIMITED for email={} correlationId={} retryAfter={}s",
                        email, correlationId, retryAfter);
                yield reply(CalendarReminderDecideReply.RATE_LIMITED, correlationId,
                        "at most " + NotificationService.MAX_NOTIFICATIONS
                                + " reminders per user per window",
                        retryAfter);
            }
            case FAILED -> refused(request, ExtErrorCodes.VALIDATION_ERROR,
                    "no usable recipient address");
        };
    }

    private static CalendarReminderDecideReply reply(String decision, String correlationId,
            String reason, long retryAfterSeconds) {

        CalendarReminderDecideReply reply = new CalendarReminderDecideReply();
        // Blank, not null: blank errorCode is the contract's success signal, and MA's reader treats
        // a null errorCode as "this reply never came off the wire" (a transport failure).
        reply.setErrorCode("");
        reply.setDecision(decision);
        reply.setReason(reason);
        reply.setCorrelationId(correlationId);
        reply.setRetryAfterSeconds((int) Math.min(retryAfterSeconds, Integer.MAX_VALUE));
        return reply;
    }

    private static CalendarReminderDecideReply refused(CalendarReminderDecideRequest request,
            String errorCode, String reason) {

        CalendarReminderDecideReply reply = new CalendarReminderDecideReply();
        reply.setErrorCode(errorCode);
        reply.setDecision(CalendarReminderDecideReply.REFUSED);
        reply.setReason(reason);
        reply.setCorrelationId(request == null ? null : request.getCorrelationId());
        reply.setRetryAfterSeconds(0);
        return reply;
    }
}
