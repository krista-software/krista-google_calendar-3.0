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

import app.krista.conversationagent.module.calendar.esb.CalendarDailySyncCommandProto;
import app.krista.infra.esb.annotation.KristaService;

/**
 * Consumes Meeting Agent's nightly request to sweep every calendar in this workspace's scan set
 * ({@code ext.calendar.sync.daily.google}, KIM-492).
 *
 * <h2>What this restores</h2>
 *
 * <p>The <b>Adaptive Calendar Sync</b> catalog request has always been the only thing that sweeps
 * every calendar in the scan set. Nothing in this extension runs it on a timer: the 15-minute seat
 * reconcile and the 6-hourly watch renewal create MISSING watch channels and never sync. A Krista
 * conversation invoked it daily — and Always On removes the conversations, which is the point of
 * Always On, and took the daily sweep with it.
 *
 * <p>The sweep matters because watch channels only deliver on CHANGE, and a channel can expire, be
 * revoked, or miss a delivery. It is the reconciliation floor: whatever the notification path lost,
 * the sweep finds within a day.
 *
 * <h2>One-way</h2>
 *
 * <p>{@code void}, deliberately: Meeting Agent does not wait for an answer, and a reply-returning
 * shape would make its nightly scheduler depend on this extension being reachable.
 *
 * @since 3.1.0
 */
@KristaService(version = 1, uniqueServiceName = "GoogleCalendarDailySync")
public interface GoogleCalendarDailySyncService {

    /**
     * Handles one nightly sweep request.
     *
     * <p>Method name MUST match the ESB API name Meeting Agent sends.
     *
     * @param event the request; {@code workspaceId}, {@code syncDays}, {@code correlationId}
     */
    void googleCalendarDailySync(CalendarDailySyncCommandProto event);
}
