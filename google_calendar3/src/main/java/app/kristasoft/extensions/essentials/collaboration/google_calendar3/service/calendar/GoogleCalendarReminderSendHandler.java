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

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.CalendarReminderSendListener;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.CalendarReminderSendProto;
import app.krista.infra.esb.service.ServiceEndpoint;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/**
 * HK2-injectable handler that bridges the ESB service interface with
 * {@link CalendarReminderSendListener}.
 *
 * <p>Since the extension became ESB-only that listener DRAINS the legacy one-way queue rather than
 * acting on it — the request/reply decide consumer answers reminders and Meeting Agent sends the
 * mail. This handler stays so the durable queue keeps a consumer; see the listener for why.
 *
 * @since 3.0.3
 */
@Service
public class GoogleCalendarReminderSendHandler implements GoogleCalendarReminderSendService, ServiceEndpoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleCalendarReminderSendHandler.class);

    private final CalendarReminderSendListener listener;

    @Inject
    public GoogleCalendarReminderSendHandler(CalendarReminderSendListener listener) {
        this.listener = listener;
    }

    @Override
    public void googleCalendarReminderSend(CalendarReminderSendProto evt) {
        LOGGER.debug("Delegating googleCalendarReminderSend to CalendarReminderSendListener");
        listener.handle(evt);
    }
}
