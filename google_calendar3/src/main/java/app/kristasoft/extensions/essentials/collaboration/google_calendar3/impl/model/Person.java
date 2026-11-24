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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model;

import com.google.gson.annotations.SerializedName;

public class Person {

    @SerializedName("email")
    private final String email;

    @SerializedName("name")
    private final String name;

    /**
     * WP-DL: the Google Group this person was invited through, lower-cased, or null when they were
     * invited directly.
     *
     * <p>Meeting Agent's dashboard visibility, its {@code INTERNAL_ONLY} / {@code MEETING_AGENT_USERS}
     * recipient scopes and its enrolment nudge all key on explicit attendee rows. Without this field
     * a person invited via a distribution list produces no row of their own, so they cannot see the
     * meeting and do not receive its report. The tag is what lets MA treat them as an invitee while
     * still being able to say <i>"invited via engineering@acme.example"</i> rather than implying they
     * were named personally.
     *
     * <p><b>Null on a direct invitation, and null is not sent.</b> Gson omits null fields, so an
     * ordinary attendee's payload is byte-identical to what this extension sent before WP-DL. Meeting
     * Agent reads an absent key as "invited directly" ({@code PersonDto.viaGroup}, which trims and
     * lower-cases on receipt), and every Conversation Agent build since 2025-09-22 ignores JSON
     * properties it does not know, so an older Meeting Agent simply drops the tag rather than
     * rejecting the meeting.
     *
     * <p><b>Never set on an organizer.</b> A calendar organizer is named personally by definition,
     * and MA stores the organizer through a path that has no {@code viaGroup} parameter at all — so a
     * tag there would be silently discarded, which is worse than never sending it.
     */
    @SerializedName("viaGroup")
    private final String viaGroup;

    public Person() {
        this.email = null;
        this.name = null;
        this.viaGroup = null;
    }

    /** A directly-invited person: {@link #getViaGroup()} is null. */
    public Person(String email, String name) {
        this(email, name, null);
    }

    /**
     * @param viaGroup the group address this person was reached through, or null when invited
     *                 directly; lower-cased by the caller that resolved the group
     */
    public Person(String email, String name, String viaGroup) {
        this.email = email;
        this.name = name;
        this.viaGroup = viaGroup;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    /** See {@link #viaGroup}. */
    public String getViaGroup() {
        return viaGroup;
    }

    @Override
    public String toString() {
        return "Person{email='" + email + "', name='" + name + "'"
                + (viaGroup == null ? "" : ", viaGroup='" + viaGroup + "'") + "}";
    }
}
