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

import app.krista.infra.esb.client.Input;
import org.infinispan.protostream.annotations.ProtoField;

/**
 * ESB event received from CA requesting the Google extension to send a calendar
 * reconnect reminder to a user.
 *
 * <p>Consumed from queue: {@code krista.calendar.reminder.send.google} — the suffix is
 * load-bearing; unsuffixed, this extension and the Teams extension would compete on one queue.
 *
 * <p>Proto field numbers MUST match CA's CalendarReminderSendProto for wire
 * compatibility.
 *
 * @since 3.0.3
 */
public class CalendarReminderSendProto implements Input {

    private String workspaceId;
    private String email;
    private String calendarSystem;
    private String correlationId;

    public CalendarReminderSendProto() {
    }

    @Override
    @ProtoField(number = 1)
    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    @ProtoField(number = 2)
    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    @ProtoField(number = 3)
    public String getCalendarSystem() {
        return calendarSystem;
    }

    public void setCalendarSystem(String calendarSystem) {
        this.calendarSystem = calendarSystem;
    }

    @ProtoField(number = 4)
    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    @Override
    public String toString() {
        return "CalendarReminderSendProto{" +
                "workspaceId='" + workspaceId + '\'' +
                ", email='" + email + '\'' +
                ", calendarSystem='" + calendarSystem + '\'' +
                ", correlationId='" + correlationId + '\'' +
                '}';
    }
}
