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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync;

import java.util.List;

/**
 * The mailboxes one sweep resolved, in this extension's own vocabulary.
 *
 * <p>A snapshot rather than a stream of per-mailbox events: Meeting Agent replaces the workspace's
 * inventory with it, so a mailbox that leaves the scan set is simply absent from the next one and
 * nothing needs a "dropped" message that could be lost.
 *
 * @param mode           the discovery mode the sweep ran in; Meeting Agent stores it unvalidated
 * @param degradedReason why Always On fell back, or null
 * @param sweptAt        when the sweep ran, ISO-8601; older rows are pruned against it
 * @param mailboxes      one row per mailbox
 */
public record ScannedMailboxSnapshot(String mode, String degradedReason, String sweptAt,
                                     List<Mailbox> mailboxes) {

    public ScannedMailboxSnapshot {
        mailboxes = mailboxes == null ? List.of() : List.copyOf(mailboxes);
    }

    /**
     * One mailbox.
     *
     * @param email             the join key on Meeting Agent's side
     * @param calendarSystem    always {@code GOOGLE} from this extension
     * @param scopeReason       why the mailbox is in scope
     * @param subscriptionState see {@link ScannedMailboxReporter}
     * @param subscriptionId    the watch channel id, or null unless SUBSCRIBED
     * @param displayLabel      what an operator should see
     */
    public record Mailbox(String email, String calendarSystem, String scopeReason,
                          String subscriptionState, String subscriptionId, String displayLabel) {
    }

    /** @return true when there is nothing to report */
    public boolean isEmpty() {
        return mailboxes.isEmpty();
    }

    public int size() {
        return mailboxes.size();
    }
}
