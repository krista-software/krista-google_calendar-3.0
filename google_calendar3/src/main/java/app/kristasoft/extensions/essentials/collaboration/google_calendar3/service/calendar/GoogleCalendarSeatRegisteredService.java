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

import app.krista.conversationagent.module.calendar.esb.CalendarSeatRegisteredCommandProto;
import app.krista.infra.esb.annotation.KristaService;

/**
 * Consumes Meeting Agent's licensed-seat registration notification
 * ({@code ext.calendar.seat.registered.google}, KIM-474 / EF-14).
 *
 * <h2>Why this exists</h2>
 *
 * <p>Meeting Agent dispatches this event, fire-and-forget, when a licensed Meeting Agent seat is
 * registered or reactivated — to each of the workspace's BOUND calendar systems, so a Google-bound
 * workspace gets it on the {@code .google} destination. Before this consumer existed the destination
 * had a producer and no consumer: the queue is durable, so the events accumulated on the broker
 * indefinitely, and the seat itself was invisible to this extension until the next roster refresh.
 *
 * <p>It is a <b>hint</b>, not the reconciliation path. The scheduled refresh remains the
 * reconciliation path, and a missed notification costs nothing beyond the gap until the next sweep.
 * That is what lets this consumer acknowledge unconditionally rather than retry.
 *
 * <h2>One-way</h2>
 *
 * <p>{@code void}, deliberately: Meeting Agent does not wait for an answer and there is nothing
 * useful to tell it. A reply-returning shape would make Meeting Agent's seat registration depend on
 * this extension being reachable, which is exactly the coupling a fire-and-forget hint avoids.
 *
 * @since 3.1.0
 */
@KristaService(version = 1, uniqueServiceName = "GoogleCalendarSeatRegistered")
public interface GoogleCalendarSeatRegisteredService {

    /**
     * Handles one licensed-seat notification.
     *
     * <p>Method name MUST match the ESB API name Meeting Agent sends.
     *
     * @param event the notification; {@code workspaceId}, {@code userEmail}, {@code reason},
     *              {@code correlationId}
     */
    void googleCalendarSeatRegistered(CalendarSeatRegisteredCommandProto event);
}
