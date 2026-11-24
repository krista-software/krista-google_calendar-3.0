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

import org.infinispan.protostream.GeneratedSchema;
import org.infinispan.protostream.annotations.AutoProtoSchemaBuilder;

/**
 * Protobuf schema initializer for the Google Calendar reconnect DTOs.
 *
 * <p><b>Wire compatibility note:</b> {@code schemaPackageName} is intentionally set to
 * CA's package ({@code app.krista.conversationagent.module.calendar.esb}) so that the
 * protostream message names produced by this extension match CA's expectations on the wire.
 *
 * <p><b>Why the auth-initiate pair is no longer here (KIM-492 WP-E15).</b> CA's
 * {@code CalendarAuthInitiateCommandProto} / {@code CalendarAuthInitiateReplyProto} now ship in
 * the staged contract jar as {@code proto/ext/calendar/calendar-auth-initiate.proto} — under this
 * very same protostream package and with the same two message names. Two schemas declaring
 * {@code app.krista.conversationagent.module.calendar.esb.CalendarAuthInitiateCommandProto}
 * cannot both register in one {@code SerializationContext}: protostream rejects the duplicate and
 * the consumer never starts. The hand-authored DTOs and their marshallers are therefore deleted
 * and the jar's are registered instead, via {@link
 * app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.CaContractProtoRegistration}.
 * Field numbers, names and types were diffed against the jar's {@code .proto} before the swap and
 * are identical, so nothing on the wire moved.
 *
 * <p>{@code CalendarConnectionStatusChangedProto} and {@code CalendarReminderSendProto} stay:
 * the contract jar carries neither, so there is no collision and no replacement.
 *
 * <p><b>NOTE:</b> The {@code @AutoProtoSchemaBuilder} annotation processor cannot run
 * under Gradle because {@code ExtensionAnnotationProcessor} claims all annotations.
 * The implementation class ({@link CalendarReconnectEsbProtoInitializerImpl}) was
 * authored manually. If you change the DTOs, update the Impl and marshal classes too.
 *
 * @since 3.0.2
 */
@AutoProtoSchemaBuilder(
        includeClasses = {
                CalendarConnectionStatusChangedProto.class,
                CalendarReminderSendProto.class
        },
        schemaFileName = "calendar-reconnect.proto",
        schemaFilePath = "proto",
        schemaPackageName = "app.krista.conversationagent.module.calendar.esb"
)
public interface CalendarReconnectEsbProtoInitializer extends GeneratedSchema {
}
