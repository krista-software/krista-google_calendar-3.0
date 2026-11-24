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

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.CalendarReminderSendProto;
import app.krista.infra.esb.annotation.KristaService;

/**
 * ESB service interface for Google Calendar reconnect reminder-send events.
 *
 * <p>Consumed queue: {@code krista.calendar.reminder.send.google} — the suffix is load-bearing;
 * unsuffixed, this extension and the Teams extension would compete on one queue.
 *
 * @since 3.0.3
 */
@KristaService(version = 1, uniqueServiceName = "GoogleCalendarReminderSend")
public interface GoogleCalendarReminderSendService {

    /**
     * Method name MUST match the ESB API name sent by CA
     * ({@code googleCalendarReminderSend}, set by
     * {@code CalendarEsbApiNames.reminderSend(GOOGLE)} on the CA side).
     */
    void googleCalendarReminderSend(CalendarReminderSendProto evt);
}
