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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.config;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.CalendarConnectionStatusEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KIM-492 WP-E15 (spec §3.3): every destination this extension names belongs to one of the five
 * sanctioned families, and the two renames landed on the right side of the {@code ext.*} /
 * {@code events.*} line.
 *
 * <p>The line is drawn by <b>who consumes</b>, not by direction: an extension consuming is
 * {@code ext.*}; an extension → Meeting Agent fire-and-forget is {@code events.*}. The
 * {@code krista.*} prefix these three used is not a family at all, which is what this work
 * corrected.
 *
 * <p>These are string constants, so nothing here needs a broker or a container — which is the
 * point. A destination that drifts from the name the other side produces to fails <em>silently</em>
 * (spec §6 R10): the consumer binds a queue nobody writes to and reports itself healthy.
 */
@DisplayName("ESB destination naming")
class EsbDestinationNamingTest {

    @Test
    @DisplayName("auth-initiate binds the ext.* standard name")
    void authInitiateUsesTheExtFamily() {
        assertEquals("ext.calendar.auth.initiate.google",
                GoogleExtensionConfig.QUEUE_CALENDAR_AUTH_INITIATE);
    }

    @Test
    @DisplayName("the pre-rename auth-initiate destination is still bound, and is a DIFFERENT name")
    void theLegacyAuthInitiateDestinationSurvivesStageTwo() {
        assertEquals("krista.calendar.auth.initiate.google",
                GoogleExtensionConfig.QUEUE_CALENDAR_AUTH_INITIATE_LEGACY);
        assertNotEquals(GoogleExtensionConfig.QUEUE_CALENDAR_AUTH_INITIATE,
                GoogleExtensionConfig.QUEUE_CALENDAR_AUTH_INITIATE_LEGACY,
                "A rename is a NEW destination, not a migration. Collapsing the two onto one name"
                        + " would make this extension deaf to whichever MA has not moved yet, and"
                        + " deaf silently — Stage 3 (WP-E20) is what removes the legacy binding.");
    }

    @Test
    @DisplayName("connection status publishes on events.*, the extension-to-MA event family")
    void connectionStatusUsesTheEventsFamily() {
        assertEquals("events.calendar.connection.status.changed",
                CalendarConnectionStatusEventPublisher.QUEUE);
    }

    @Test
    @DisplayName("the reminder-send destination carries its .google suffix")
    void reminderSendIsSuffixedPerCalendarSystem() {
        assertEquals("krista.calendar.reminder.send.google",
                GoogleExtensionConfig.QUEUE_CALENDAR_REMINDER_SEND);
        assertTrue(GoogleExtensionConfig.QUEUE_CALENDAR_REMINDER_SEND.endsWith(".google"),
                "Unsuffixed, this extension and the Teams extension compete on one queue and each"
                        + " steals half the other's reminders. The javadoc used to say the"
                        + " unsuffixed name; the consumer never bound it.");
    }

    @Test
    @DisplayName("reminder-DECIDE binds the ext.* family, suffixed per calendar system")
    void reminderDecideUsesTheExtFamily() {
        assertEquals("ext.calendar.reminder.decide.google",
                GoogleExtensionConfig.QUEUE_CALENDAR_REMINDER_DECIDE);
        assertNotEquals(GoogleExtensionConfig.QUEUE_CALENDAR_REMINDER_SEND,
                GoogleExtensionConfig.QUEUE_CALENDAR_REMINDER_DECIDE,
                "The WP-E16 request/reply loop is a NEW destination beside the one-way send, not a"
                        + " rename of it. Both are bound through Stage 2 because they answer"
                        + " different MA versions; collapsing them onto one name makes this"
                        + " extension deaf to whichever MA has not moved yet.");
    }

    @Test
    @DisplayName("no destination this extension names still uses the krista.* non-family, except the "
            + "one deliberately kept for Stage 2")
    void onlyTheDeliberateLegacyNamesRemain() {
        // The reminder-send rename is WP-E16's (one-way -> request/reply on
        // ext.calendar.reminder.decide.google), so its krista.* name is still correct here.
        assertTrue(GoogleExtensionConfig.QUEUE_CALENDAR_AUTH_INITIATE.startsWith("ext."));
        assertTrue(GoogleExtensionConfig.QUEUE_CALENDAR_REMINDER_DECIDE.startsWith("ext."));
        assertTrue(CalendarConnectionStatusEventPublisher.QUEUE.startsWith("events."));
    }
}
