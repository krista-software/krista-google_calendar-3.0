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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.entity;

import app.krista.extension.impl.anno.*;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.util.EventDetailsMapper;
import com.google.api.services.calendar.model.Event;

import java.util.List;

@Domain(id = "catEntryDomain_5fa2fc97-4b17-44cf-b98f-aa91a459a091",
        name = "Collaboration",
        ecosystemId = "catEntryEcosystem_84b53163-327b-4b1b-8c96-9334d292f9f5",
        ecosystemName = "Essentials",
        ecosystemVersion ="17a093a2-3059-4a0e-b441-d2248d456fdd")
@Entity(name = "Event Details", id = "localDomainEntity_b13aad88-786e-440a-8131-84351124a9cf", primaryKey = "Event Id", supportStore = false, options = {})
public class EventDetails {

    @Field.Text(name = "Event Id")
    public String eventId;

    @Field.Text(name = "Location")
    public String location;

    @Searchable
    @Field.Text(name = "Title")
    public String title;

    @Field.Desc(name = "Contacts", type = "[ Text ]", required = false)
    public List<String> contacts;

    @Field(name = "Details", type = "RichText", attributes = {@Attribute(name = "visualWidth", value = "L")})
    public String details;
    @Field.Date(name = "Date From", required = true, showHowManyDaysInViewer = 60, allowPast = false, attributes = {@Attribute(name = "visualWidth", value = "S")})
    public Long dateFrom;

    @Field.Date(name = "Date To", required = true, showHowManyDaysInViewer = 60, allowPast = false, attributes = {@Attribute(name = "visualWidth", value = "S")})
    public Long dateTo;

    /**
     * Converts a Google Calendar Event object to an EventDetails entity.
     *
     * <p>This method delegates to {@link EventDetailsMapper#mapEventToEventDetails(Event)}
     * for the actual conversion logic. It is maintained here for backward compatibility.</p>
     *
     * @param event the Google Calendar Event object to convert
     * @return EventDetails entity populated with event data
     * @deprecated Use {@link EventDetailsMapper#mapEventToEventDetails(Event)} instead.
     *             This method is maintained for backward compatibility only.
     * @see EventDetailsMapper#mapEventToEventDetails(Event)
     */
    @Deprecated
    public static EventDetails getEventDetailsByEvent(Event event) {
        return EventDetailsMapper.mapEventToEventDetails(event);
    }
}