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

package app.krista.conversationagent.module.calendar.esb;

/**
 * Backward-compatible alias so CA's {@code dtoClass} resolves on this extension's
 * classpath: CA dispatches the reminder-send command with {@code esb_protoMsg =
 * <this FQN>} and the ESB layer does {@code Class.forName(dtoClass)}, so this class
 * must exist or the message can't be read. Decoding uses the superclass marshaller.
 * Do not delete. Mirrors the KIP shim.
 */
public class CalendarReminderSendProto
        extends app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.CalendarReminderSendProto {
}
