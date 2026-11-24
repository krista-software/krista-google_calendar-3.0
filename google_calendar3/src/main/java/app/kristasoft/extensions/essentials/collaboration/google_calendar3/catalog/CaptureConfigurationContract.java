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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog;

/**
 * KIM-474 — the wire contract for the {@code Get Capture Configuration} catalog request.
 *
 * <p>This is a <em>polymorphic</em> request in the Collaboration domain: every calendar extension
 * that Meeting Agent can bind as a calendar-system dependency declares the SAME catalog request id
 * and the SAME output field names, exactly as Outlook, Gmail and the generic Email extension all
 * declare one shared {@code Send Mail} request. The Meeting Agent wrapper calls this id on whichever
 * calendar extension happens to be bound, without knowing which one it is.
 *
 * <p><strong>These values are duplicated, by design, in three repositories.</strong> Changing one
 * without the others silently breaks the flag:
 * <ul>
 *   <li>{@code krista-global-catalog-gle} — this class (declared by
 *       {@link MeetingsArea#getCaptureConfiguration()}).</li>
 *   <li>{@code krista-global-catalog-kip} — {@code app.krista.global.catalog.CaptureConfigurationContract}
 *       (declared by {@code TeamsPostMeetingArea.getCaptureConfiguration()}).</li>
 *   <li>{@code krista-global-catalog-ca} — {@code app.krista.global.service.capture.CalendarCaptureConfigReader}
 *       (the caller).</li>
 * </ul>
 * There is no shared artifact between these repositories to hold it in, which is why the id is
 * pinned as a constant here rather than inlined at the annotation.
 */
public final class CaptureConfigurationContract {

    /**
     * Catalog request id, identical in every calendar extension that implements the request.
     *
     * <p>A caller invoking this id against an extension that predates KIM-474 gets a
     * {@link java.util.NoSuchElementException} — that is the expected "not reported" signal, not an
     * error, and the Meeting Agent wrapper degrades to {@code null} on it.
     */
    public static final String CATALOG_REQUEST_ID = "localDomainRequest_fcc54ad0-a246-42fc-97f2-dc740034f2a5";

    /** Display name of the request, as shown in Studio. */
    public static final String NAME = "Get Capture Configuration";

    /**
     * Output field carrying the Always On flag. The catalog response map is keyed by the declared
     * output field NAME, so this string is part of the wire contract.
     */
    public static final String FIELD_ALWAYS_ON = "Always On";

    private CaptureConfigurationContract() {
    }
}
