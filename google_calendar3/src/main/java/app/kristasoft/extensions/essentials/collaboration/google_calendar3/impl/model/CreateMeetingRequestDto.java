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

import java.util.List;

public class CreateMeetingRequestDto {

    @SerializedName("title")
    private String title;

    @SerializedName("startTime")
    private String startTime;

    @SerializedName("endTime")
    private String endTime;

    @SerializedName("timezone")
    private String timezone;

    @SerializedName("meetingUrl")
    private String meetingUrl;

    @SerializedName("organizer")
    private Person organizer;

    @SerializedName("attendees")
    private List<Person> attendees;

    @SerializedName("calendarSystem")
    private String calendarSystem;

    @SerializedName("recurring")
    private boolean recurring;

    @SerializedName("recurringSeriesId")
    private String recurringSeriesId;

    @SerializedName("body")
    private String body;

    @SerializedName("iCalUId")
    private String iCalUId;

    /**
     * KIM-474 (FR-002): the id of the invoker that produced this upsert.
     *
     * <p>Informational only. Meeting Agent logs it, stamps it on the capture audit trail, and warns
     * when it does not match the invoker registered for the workspace — it is never a capture gate,
     * so an absent value changes nothing. Optional on both sides of the wire: Meeting Agent's
     * Jackson provider ignores unknown properties, so this field is safe to ship ahead of the
     * backend that reads it, and safe to omit.
     */
    @SerializedName("invokerId")
    private String invokerId;

    public CreateMeetingRequestDto() {
    }

    public CreateMeetingRequestDto(String title, String startTime, String endTime, String timezone,
                                   String meetingUrl, Person organizer, List<Person> attendees,
                                   String calendarSystem, boolean recurring, String recurringSeriesId,
                                   String body, String iCalUId) {
        this.title = title;
        this.startTime = startTime;
        this.endTime = endTime;
        this.timezone = timezone;
        this.meetingUrl = meetingUrl;
        this.organizer = organizer;
        this.attendees = attendees;
        this.calendarSystem = calendarSystem;
        this.recurring = recurring;
        this.recurringSeriesId = recurringSeriesId;
        this.body = body;
        this.iCalUId = iCalUId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getMeetingUrl() {
        return meetingUrl;
    }

    public void setMeetingUrl(String meetingUrl) {
        this.meetingUrl = meetingUrl;
    }

    public Person getOrganizer() {
        return organizer;
    }

    public void setOrganizer(Person organizer) {
        this.organizer = organizer;
    }

    public List<Person> getAttendees() {
        return attendees;
    }

    public void setAttendees(List<Person> attendees) {
        this.attendees = attendees;
    }

    public String getCalendarSystem() {
        return calendarSystem;
    }

    public void setCalendarSystem(String calendarSystem) {
        this.calendarSystem = calendarSystem;
    }

    public boolean isRecurring() {
        return recurring;
    }

    public void setRecurring(boolean recurring) {
        this.recurring = recurring;
    }

    public String getRecurringSeriesId() {
        return recurringSeriesId;
    }

    public void setRecurringSeriesId(String recurringSeriesId) {
        this.recurringSeriesId = recurringSeriesId;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getiCalUId() {
        return iCalUId;
    }

    public void setiCalUId(String iCalUId) {
        this.iCalUId = iCalUId;
    }

    public String getInvokerId() {
        return invokerId;
    }

    public void setInvokerId(String invokerId) {
        this.invokerId = invokerId;
    }
}
