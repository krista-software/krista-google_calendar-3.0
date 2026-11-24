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
 * ESB event published by the Google Calendar extension when a user's calendar
 * connection status transitions.
 *
 * <p>Published to queue: {@code krista.calendar.connection.status.changed}
 *
 * <p>Proto field numbers MUST match CA's CalendarConnectionStatusChangedProto for wire
 * compatibility. The {@code schemaPackageName} in the proto initializer is set to CA's
 * package so the protostream message name agrees on both ends.
 *
 * @since 3.0.2
 */
public class CalendarConnectionStatusChangedProto implements Input {

    private String workspaceId;
    private String email;
    private String calendarSystem;
    private String status;
    private String lastErrorCode;
    private String lastErrorMessage;
    private long occurredAtEpochMillis;
    private String correlationId;
    private String reason;

    public CalendarConnectionStatusChangedProto() {
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
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @ProtoField(number = 5)
    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    @ProtoField(number = 6)
    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
    }

    @ProtoField(number = 7, defaultValue = "0")
    public long getOccurredAtEpochMillis() {
        return occurredAtEpochMillis;
    }

    public void setOccurredAtEpochMillis(long occurredAtEpochMillis) {
        this.occurredAtEpochMillis = occurredAtEpochMillis;
    }

    @ProtoField(number = 8)
    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    /**
     * Disconnect intent — meaningful only when {@code status=DISCONNECTED}.
     * {@code "USER_INITIATED"} (deliberate disconnect) tells CA to delete the row;
     * {@code "ACCESS_REVOKED"} or {@code null} is treated as an involuntary disconnect
     * (CA keeps the row as broken). Optional/additive; older publishers omit it.
     */
    @ProtoField(number = 9)
    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "CalendarConnectionStatusChangedProto{" +
                "workspaceId='" + workspaceId + '\'' +
                ", email='" + email + '\'' +
                ", calendarSystem='" + calendarSystem + '\'' +
                ", status='" + status + '\'' +
                ", lastErrorCode='" + lastErrorCode + '\'' +
                ", occurredAtEpochMillis=" + occurredAtEpochMillis +
                ", correlationId='" + correlationId + '\'' +
                ", reason='" + reason + '\'' +
                '}';
    }
}
