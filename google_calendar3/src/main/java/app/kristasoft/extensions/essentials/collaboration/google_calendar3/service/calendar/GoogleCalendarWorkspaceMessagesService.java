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

import app.krista.conversationagent.module.calendar.esb.CalendarWorkspaceMessagesProto;
import app.krista.infra.esb.annotation.KristaService;

/**
 * Consumes Meeting Agent's in-call messages broadcast
 * ({@code ext.calendar.workspace.messages.google}, KIM-492).
 *
 * <h2>Why this exists when Google has nothing to present</h2>
 *
 * <p>Meeting Agent broadcasts a workspace's two in-call messages — the standard Krista welcome and the
 * customer's compliance notice — fire-and-forget to each of the workspace's BOUND calendar systems, so
 * a Google-bound workspace's copy lands here. Google Meet gives this extension no in-call chat surface:
 * the Krista Bot presents both messages itself from its own {@code BOT_CONFIG}, so there is nothing for
 * this consumer to render.
 *
 * <p>It still has to exist. The destination is durable and addressed per system, so without a consumer
 * every Google-bound workspace's broadcasts accumulate on the broker indefinitely — one per admin save
 * of the messages settings, plus one per workspace on every Meeting Agent boot. This is the same hole
 * {@link GoogleCalendarSeatRegisteredService} was written to close, and it is deliberately shaped the
 * same way so a change to one is obviously a change the other needs.
 *
 * <p>So the command is consumed and STORED (see
 * {@code app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.WorkspaceMessagesStore}).
 * Storing is what makes draining honest: a future Google-side surface reads what it was already sent
 * rather than asking Meeting Agent for state that had been discarded.
 *
 * <h2>One-way</h2>
 *
 * <p>{@code void}, deliberately: Meeting Agent does not await the dispatch, and a reply-returning shape
 * would make an admin's settings save depend on this extension being reachable — exactly the coupling a
 * fire-and-forget broadcast avoids. Meeting Agent has already committed the write by the time it
 * dispatches.
 *
 * @since 3.1.0
 */
@KristaService(version = 1, uniqueServiceName = "GoogleCalendarWorkspaceMessages")
public interface GoogleCalendarWorkspaceMessagesService {

    /**
     * Handles one in-call messages broadcast.
     *
     * <p>Method name MUST match the ESB API name Meeting Agent sends
     * ({@code CalendarEsbApiNames.workspaceMessages(GOOGLE)}).
     *
     * @param command the broadcast; {@code workspaceId}, {@code welcomeMessage},
     *                {@code complianceNotice}, {@code correlationId}, {@code emittedAt}. An EMPTY
     *                message field means "present nothing", never "no change"
     */
    void googleCalendarWorkspaceMessages(CalendarWorkspaceMessagesProto command);
}
