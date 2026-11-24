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
import app.krista.infra.esb.annotation.KristaService;

/**
 * The calendar-reconnect reminder decision, as a request/reply ESB service
 * (KIM-492 WP-E16, spec §3.10).
 *
 * <p>Bound to {@code ext.calendar.reminder.decide.google}. Meeting Agent asks; this extension
 * answers {@code SEND} / {@code DEDUPED} / {@code RATE_LIMITED} / {@code REFUSED}; <b>MA sends the
 * email itself</b>.
 *
 * <h2>What this replaces, and why</h2>
 *
 * <p>The old shape — deleted, not merely superseded — was a one-way event on
 * {@code krista.calendar.reminder.send.google} whose handler applied the same dedup and rate limit
 * and then called <em>back over HTTP</em> to
 * {@code POST /api/v1/extensions/calendar-notification/{email}/send} so that MA would send the
 * mail. That blocked the JMS consumer thread on an HTTP call for up to 30 seconds and never replied
 * on the bus — a nested HTTP call inside an ESB consumer, which is exactly the shape this migration
 * exists to remove.
 *
 * <p>A NEW destination rather than a changed return type on the old one: turning a {@code void}
 * service method into a reply-returning one is a breaking change against every deployed extension,
 * whereas a new destination simply idles until a caller arrives. The old destination, its consumer
 * and its HTTP callback all go at Stage 3.
 *
 * <p>The dedup window and rate limit stay HERE — they are this extension's state, keyed by the
 * mailbox it watches — and the reply is how MA learns the verdict.
 *
 * @since 3.1.0
 */
@KristaService(version = 1, uniqueServiceName = "GoogleCalendarReminderDecide")
public interface GoogleCalendarReminderDecideService {

    /**
     * Decides whether Meeting Agent should send a calendar-reconnect reminder.
     *
     * <p>Method name MUST match the ESB API name MA sends.
     *
     * @param request the decision request; {@code workspaceId}, {@code email},
     *                {@code calendarSystem}, {@code correlationId}
     * @return the decision, never null
     */
    CalendarReminderDecideReply googleCalendarReminderDecide(CalendarReminderDecideRequest request);
}
