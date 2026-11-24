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

import app.krista.extension.authorization.MustAuthorizeException;
import app.krista.extension.executor.ErrorBuilder;
import app.krista.extension.executor.ExtensionResponse;
import app.krista.extension.executor.ExtensionResponseBuilder;
import app.krista.extension.executor.Invoker;
import app.krista.extension.impl.anno.Attribute;
import app.krista.extension.impl.anno.CatalogRequest;
import app.krista.extension.impl.anno.Domain;
import app.krista.extension.impl.anno.Field;
import app.krista.model.field.NamedValuedField;
import app.krista.extension.request.RoutingInfo;
import app.krista.extension.request.protos.http.HttpProtocol;
import app.krista.ksdk.invoker.InvokerConfiguration;
import app.krista.ksdk.telemetry.TelemetryMetrics;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.GoogleAttributes;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.entity.EventDetails;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.CalendarResource;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.handler.AdaptiveCalendarSyncHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.handler.AuthenticateHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.handler.CalendarSyncByUserHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.handler.DisconnectHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.AcceptInvitationHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.AddAttendeesHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.CancelEventHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.DeclineInvitationHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.GetEventHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.GetEventsForUserHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.GetNextUpcomingMeetingsHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.RemoveAttendeeHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.ScheduleMeetingHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.TentativeInvitationHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.util.TelemetryHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.validators.ValidationOrchestrator;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.SubCatalogRequestImpl;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationException;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.GoogleAttributeStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.TelemetryConstants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Validator;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.CalendarService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.GoogleServiceBusManager;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarSetupService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.LiveGoogleCalendarConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Domain(id = "catEntryDomain_5fa2fc97-4b17-44cf-b98f-aa91a459a091",
        name = "Collaboration",
        ecosystemId = "catEntryEcosystem_84b53163-327b-4b1b-8c96-9334d292f9f5",
        ecosystemName = "Essentials",
        ecosystemVersion = "cbcf28a4-b28f-4c9d-8ebb-5ac3a73a6192")
public class MeetingsArea {

    private static final Logger LOGGER = LoggerFactory.getLogger(MeetingsArea.class);

    private final CalendarService calendarService;
    private final SubCatalogRequestImpl subCatalogRequestImpl;
    private final ValidationOrchestrator validationOrchestrator;
    private final TelemetryHelper telemetryHelper;
    private final AcceptInvitationHelper acceptInvitationHelper;
    private final AddAttendeesHelper addAttendeesHelper;
    private final CancelEventHelper cancelEventHelper;
    private final DeclineInvitationHelper declineInvitationHelper;
    private final GetEventHelper getEventHelper;
    private final GetEventsForUserHelper getEventsForUserHelper;
    private final GetNextUpcomingMeetingsHelper getNextUpcomingMeetingsHelper;
    private final RemoveAttendeeHelper removeAttendeeHelper;
    private final ScheduleMeetingHelper scheduleMeetingHelper;
    private final TentativeInvitationHelper tentativeInvitationHelper;
    private final AdaptiveCalendarSyncHandler adaptiveCalendarSyncHandler;
    private final AuthenticateHandler authenticateHandler;
    private final CalendarSyncByUserHandler calendarSyncByUserHandler;
    private final DisconnectHandler disconnectHandler;
    private final GoogleProviderFactory googleProviderFactory;
    private final GoogleAttributeStore googleAttributeStore;
    private final RefreshTokenStore refreshTokenStore;
    private final Invoker invoker;
    private final InvokerConfiguration invokerConfiguration;
    private final GoogleServiceBusManager serviceBusManager;
    private final TelemetryMetrics telemetryMetrics;
    private final CalendarDiscoveryService calendarDiscoveryService;
    private LiveGoogleCalendarConfiguration liveConfiguration;
    private GoogleCalendarSetupService setupService;

    @Inject
    public void setSetupServices(LiveGoogleCalendarConfiguration liveConfiguration,
                                GoogleCalendarSetupService setupService) {
        this.liveConfiguration = liveConfiguration;
        this.setupService = setupService;
    }

    private Map<String, Object> currentInvokerAttributes() {
        return liveConfiguration == null ? invoker.getAttributes() : liveConfiguration.attributes();
    }

    @Inject
    public MeetingsArea(CalendarService calendarService,
                        SubCatalogRequestImpl subCatalogRequestImpl,
                        ValidationOrchestrator validationOrchestrator,
                        TelemetryHelper telemetryHelper,
                        AcceptInvitationHelper acceptInvitationHelper,
                        AddAttendeesHelper addAttendeesHelper,
                        CancelEventHelper cancelEventHelper,
                        DeclineInvitationHelper declineInvitationHelper,
                        GetEventHelper getEventHelper,
                        GetEventsForUserHelper getEventsForUserHelper,
                        GetNextUpcomingMeetingsHelper getNextUpcomingMeetingsHelper,
                        RemoveAttendeeHelper removeAttendeeHelper,
                        ScheduleMeetingHelper scheduleMeetingHelper,
                        TentativeInvitationHelper tentativeInvitationHelper,
                        AdaptiveCalendarSyncHandler adaptiveCalendarSyncHandler,
                        AuthenticateHandler authenticateHandler,
                        CalendarSyncByUserHandler calendarSyncByUserHandler,
                        DisconnectHandler disconnectHandler,
                        GoogleProviderFactory googleProviderFactory,
                        GoogleAttributeStore googleAttributeStore,
                        RefreshTokenStore refreshTokenStore,
                        Invoker invoker,
                        InvokerConfiguration invokerConfiguration,
                        GoogleServiceBusManager serviceBusManager,
                        TelemetryMetrics telemetryMetrics,
                        CalendarDiscoveryService calendarDiscoveryService) {
        this.calendarService = calendarService;
        this.subCatalogRequestImpl = subCatalogRequestImpl;
        this.validationOrchestrator = validationOrchestrator;
        this.telemetryHelper = telemetryHelper;
        this.acceptInvitationHelper = acceptInvitationHelper;
        this.addAttendeesHelper = addAttendeesHelper;
        this.cancelEventHelper = cancelEventHelper;
        this.declineInvitationHelper = declineInvitationHelper;
        this.getEventHelper = getEventHelper;
        this.getEventsForUserHelper = getEventsForUserHelper;
        this.getNextUpcomingMeetingsHelper = getNextUpcomingMeetingsHelper;
        this.removeAttendeeHelper = removeAttendeeHelper;
        this.scheduleMeetingHelper = scheduleMeetingHelper;
        this.tentativeInvitationHelper = tentativeInvitationHelper;
        this.adaptiveCalendarSyncHandler = adaptiveCalendarSyncHandler;
        this.authenticateHandler = authenticateHandler;
        this.calendarSyncByUserHandler = calendarSyncByUserHandler;
        this.disconnectHandler = disconnectHandler;
        this.googleProviderFactory = googleProviderFactory;
        this.googleAttributeStore = googleAttributeStore;
        this.refreshTokenStore = refreshTokenStore;
        this.invoker = invoker;
        this.invokerConfiguration = invokerConfiguration;
        this.serviceBusManager = serviceBusManager;
        this.telemetryMetrics = telemetryMetrics;
        this.calendarDiscoveryService = calendarDiscoveryService;
    }

    /**
     * Compatibility constructor for callers that predate conversational DWD validation.
     * Production injection uses the constructor above; older test/harness callers can continue
     * to construct the catalog without a provider and retain the existing save-only behaviour.
     */
    public MeetingsArea(CalendarService calendarService,
                        SubCatalogRequestImpl subCatalogRequestImpl,
                        ValidationOrchestrator validationOrchestrator,
                        TelemetryHelper telemetryHelper,
                        AcceptInvitationHelper acceptInvitationHelper,
                        AddAttendeesHelper addAttendeesHelper,
                        CancelEventHelper cancelEventHelper,
                        DeclineInvitationHelper declineInvitationHelper,
                        GetEventHelper getEventHelper,
                        GetEventsForUserHelper getEventsForUserHelper,
                        GetNextUpcomingMeetingsHelper getNextUpcomingMeetingsHelper,
                        RemoveAttendeeHelper removeAttendeeHelper,
                        ScheduleMeetingHelper scheduleMeetingHelper,
                        TentativeInvitationHelper tentativeInvitationHelper,
                        AdaptiveCalendarSyncHandler adaptiveCalendarSyncHandler,
                        AuthenticateHandler authenticateHandler,
                        CalendarSyncByUserHandler calendarSyncByUserHandler,
                        DisconnectHandler disconnectHandler,
                        GoogleProviderFactory googleProviderFactory,
                        GoogleAttributeStore googleAttributeStore,
                        RefreshTokenStore refreshTokenStore,
                        Invoker invoker,
                        InvokerConfiguration invokerConfiguration,
                        GoogleServiceBusManager serviceBusManager,
                        TelemetryMetrics telemetryMetrics) {
        this(calendarService, subCatalogRequestImpl, validationOrchestrator, telemetryHelper,
                acceptInvitationHelper, addAttendeesHelper, cancelEventHelper,
                declineInvitationHelper, getEventHelper, getEventsForUserHelper,
                getNextUpcomingMeetingsHelper, removeAttendeeHelper, scheduleMeetingHelper,
                tentativeInvitationHelper, adaptiveCalendarSyncHandler, authenticateHandler,
                calendarSyncByUserHandler, disconnectHandler, googleProviderFactory,
                googleAttributeStore, refreshTokenStore, invoker, invokerConfiguration,
                serviceBusManager, telemetryMetrics, null);
    }

    /**
     * Compatibility constructor for callers compiled before the ESB reconfiguration service was
     * added. HK2 uses the injected constructor above; this overload keeps older unit/integration
     * harnesses source-compatible while they migrate to the new dependency.
     */
    @Deprecated
    public MeetingsArea(CalendarService calendarService,
                        SubCatalogRequestImpl subCatalogRequestImpl,
                        ValidationOrchestrator validationOrchestrator,
                        TelemetryHelper telemetryHelper,
                        AcceptInvitationHelper acceptInvitationHelper,
                        AddAttendeesHelper addAttendeesHelper,
                        CancelEventHelper cancelEventHelper,
                        DeclineInvitationHelper declineInvitationHelper,
                        GetEventHelper getEventHelper,
                        GetEventsForUserHelper getEventsForUserHelper,
                        GetNextUpcomingMeetingsHelper getNextUpcomingMeetingsHelper,
                        RemoveAttendeeHelper removeAttendeeHelper,
                        ScheduleMeetingHelper scheduleMeetingHelper,
                        TentativeInvitationHelper tentativeInvitationHelper,
                        AdaptiveCalendarSyncHandler adaptiveCalendarSyncHandler,
                        AuthenticateHandler authenticateHandler,
                        CalendarSyncByUserHandler calendarSyncByUserHandler,
                        DisconnectHandler disconnectHandler,
                        GoogleProviderFactory googleProviderFactory,
                        GoogleAttributeStore googleAttributeStore,
                        RefreshTokenStore refreshTokenStore,
                        Invoker invoker,
                        InvokerConfiguration invokerConfiguration,
                        TelemetryMetrics telemetryMetrics) {
        this(calendarService, subCatalogRequestImpl, validationOrchestrator, telemetryHelper,
                acceptInvitationHelper, addAttendeesHelper, cancelEventHelper,
                declineInvitationHelper, getEventHelper, getEventsForUserHelper,
                getNextUpcomingMeetingsHelper, removeAttendeeHelper, scheduleMeetingHelper,
                tentativeInvitationHelper, adaptiveCalendarSyncHandler, authenticateHandler,
                calendarSyncByUserHandler, disconnectHandler, googleProviderFactory,
                googleAttributeStore, refreshTokenStore, invoker, invokerConfiguration,
                null, telemetryMetrics, null);
    }

    @CatalogRequest(
            id = "localDomainRequest_9650a84d-7e4e-4758-83b0-70724eb32635",
            name = "Authenticate",
            description = "This catalog request is used to authenticate the user with Google Calendar. It validates the OAuth connection and registers the user for calendar sync.",
            area = "Meetings",
            type = CatalogRequest.Type.CHANGE_SYSTEM, tool = true)
    @Field.Text(name = "Status", required = false, attributes = {@Attribute(name = "visualWidth", value = "S")}, options = {})
    @Field.Text(name = "User Id", required = false, attributes = {@Attribute(name = "visualWidth", value = "S")}, options = {})
    @Field.Text(name = "Email", required = false, attributes = {@Attribute(name = "visualWidth", value = "S")}, options = {})
    public ExtensionResponse authenticate() {
        LOGGER.info("Starting authentication process for user");
        try {
            return authenticateHandler.execute();
        } catch (Exception cause) {
            LOGGER.error("Failed to authenticate user: {}", cause.getMessage(), cause);
            throw cause;
        }
    }

    /**
     * KIM-474: reports this calendar system's meeting-capture configuration to whoever binds it as
     * a dependency — in practice the Meeting Agent wrapper, which forwards the flag to Meeting
     * Agent as the top-level {@code alwaysOn} field of its registration payload.
     *
     * <p>Reads the invoker attribute LIVE, so an admin toggling the flag in Studio is visible on
     * the very next call.
     *
     * <p>Deliberately incapable of failing: it touches no external system, and an unreadable
     * attribute map degrades to {@code false} rather than propagating. The caller runs this during
     * its own attribute validation, and a failure here must never block that registration.
     *
     * @return a success response carrying {@link CaptureConfigurationContract#FIELD_ALWAYS_ON}
     */
    @CatalogRequest(
            id = CaptureConfigurationContract.CATALOG_REQUEST_ID,
            name = CaptureConfigurationContract.NAME,
            description = "Returns this calendar system's meeting-capture configuration, including whether Always On capture is enabled for the workspace.",
            area = "Meetings",
            type = CatalogRequest.Type.QUERY_SYSTEM)
    @Field.Boolean(name = CaptureConfigurationContract.FIELD_ALWAYS_ON, required = false, attributes = {@Attribute(name = "visualWidth", value = "S")}, options = {})
    public ExtensionResponse getCaptureConfiguration() {
        boolean alwaysOn = false;
        try {
            alwaysOn = Constants.readAlwaysOn(currentInvokerAttributes());
        } catch (Exception cause) {
            LOGGER.warn("Could not read the '{}' attribute; reporting Always On as off: {}",
                    Constants.ALWAYS_ON, cause.getMessage(), cause);
        }
        LOGGER.info("Reporting capture configuration: alwaysOn={}", alwaysOn);
        return new ExtensionResponseBuilder()
                .success(Map.of(CaptureConfigurationContract.FIELD_ALWAYS_ON, alwaysOn))
                .build();
    }

    @CatalogRequest(
            id = "localDomainRequest_019ae849-f13f-43db-944a-8d36b5d1dd78",
            name = "Disconnect My Calendar",
            description = "Disconnects the user's Google Calendar: revokes the stored authorization, stops calendar notifications, and removes the calendar connection so it no longer appears in Krista.",
            area = "Meetings",
            type = CatalogRequest.Type.CHANGE_SYSTEM, tool = true)
    @Field.Text(name = "Status", required = false, attributes = {@Attribute(name = "visualWidth", value = "S")}, options = {})
    public ExtensionResponse disconnectCalendar() {
        LOGGER.info("Starting Google Calendar disconnect for user");
        try {
            return disconnectHandler.execute();
        } catch (Exception cause) {
            LOGGER.error("Failed to disconnect Google Calendar: {}", cause.getMessage(), cause);
            throw cause;
        }
    }

    @CatalogRequest(
            id = "localDomainRequest_d564897b-ad17-4289-af2c-bd79425c5341",
            name = "Accept Invitation",
            description = "Accept a meeting invitation for a specific event by providing the event ID and attendee email address. This action confirms attendance and updates the event's attendee status.",
            area = "Meetings",
            type = CatalogRequest.Type.CHANGE_SYSTEM, tool = true)
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse acceptInvitation(
            @Field.Text(name = "Event Id", attributes = {@Attribute(name = "toolTip", value = "'Unique identifier of the meeting or event to accept'")}, options = {}) String eventId,
            @Field(name = "Attendee Email", type = "Email", attributes = {@Attribute(name = "visualWidth", value = "S"), @Attribute(name = "toolTip", value = "'Email address of the attendee accepting the invitation'")}) String attendeeEmail) {

        TelemetryHelper.TelemetryOperation operation = telemetryHelper.startOperation("acceptInvitation");

        try {
            ExtensionResponse response;
            if (isErrorHandlingEnabled()) {
                LOGGER.debug("Error handling is enabled, using AcceptInvitationHelper");
                response = acceptInvitationHelper.acceptInvitationWithValidation(eventId, attendeeEmail);
            } else {
                LOGGER.info("acceptInvitation called: EventId={}, AttendeeEmail={}", eventId, attendeeEmail);
                EventDetails eventDetails = calendarService.acceptInvitation(eventId, attendeeEmail);
                telemetryMetrics.incrementCounter(TelemetryConstants.ACCEPT_INVITATION_REQUEST_SUCCESS, 1L);
                response = new ExtensionResponseBuilder()
                        .success(Map.of("Event Details", eventDetails))
                        .build();
            }
            operation.recordSuccess();
            return response;
        } catch (Exception cause) {
            propagateAuthorizationRequired(cause);
            operation.recordFailure(cause);
            LOGGER.error("Error occurred while accepting invitation: {}", cause.getMessage(), cause);
            ExtensionResponse.Error error = new ErrorBuilder()
                    .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                    .errorMessage("Error occurred while accepting invitation: " + cause.getMessage())
                    .stackTrace(Arrays.toString(cause.getStackTrace()))
                    .build();
            return new ExtensionResponseBuilder().failure(error).build();
        }
    }

    @CatalogRequest(
            id = "localDomainRequest_2e73b843-ace4-4b80-bfab-4f466706e5c5",
            name = "Add Attendees",
            description = "Add one or more attendees to an existing scheduled meeting by providing the event ID and a list of attendee email addresses. This action sends invitations to the new attendees and updates the event.",
            area = "Meetings",
            type = CatalogRequest.Type.CHANGE_SYSTEM, tool = true)
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse addAttendees(
            @Field.Text(name = "Event Id", attributes = {@Attribute(name = "toolTip", value = "'Unique identifier of the meeting or event to add attendees to'")}) String eventId,
            @Field.Desc(name = "Attendee Email", type = "[ Email ]") List<String> requiredAttendeeEmail) {

        TelemetryHelper.TelemetryOperation operation = telemetryHelper.startOperation("addAttendees");

        try {
            ExtensionResponse response;
            if (isErrorHandlingEnabled()) {
                LOGGER.debug("Error handling is enabled, using AddAttendeesHelper");
                response = addAttendeesHelper.addAttendeesWithValidation(eventId, requiredAttendeeEmail);
            } else {
                LOGGER.info("addAttendees: eventId={}, attendeeEmails={}", eventId, requiredAttendeeEmail);
                EventDetails eventDetails = calendarService.addAttendees(eventId, requiredAttendeeEmail);
                LOGGER.info("Successfully added {} attendees to event ID: {}", requiredAttendeeEmail.size(), eventId);
                response = new ExtensionResponseBuilder()
                        .success(Map.of("Event Details", eventDetails))
                        .build();
            }
            operation.recordSuccessWithAttendees(requiredAttendeeEmail != null ? requiredAttendeeEmail.size() : 0);
            return response;
        } catch (Exception cause) {
            propagateAuthorizationRequired(cause);
            operation.recordFailure(cause);
            LOGGER.error("Error occurred in addAttendees: eventId={}, attendeeEmails={}, error={}",
                    eventId, requiredAttendeeEmail, cause.getMessage(), cause);
            return new ExtensionResponseBuilder()
                    .failure(new ErrorBuilder()
                            .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                            .errorMessage("Error occurred in addAttendees: " + cause.getMessage())
                            .stackTrace(Arrays.toString(cause.getStackTrace()))
                            .build())
                    .build();
        }
    }

    @CatalogRequest(
            id = "localDomainRequest_44d8fe1f-3f2d-438d-bb9f-bb486b9a9e56",
            name = "Cancel Event",
            description = "Cancel a scheduled meeting or event by providing the event ID. This action removes the event from all attendees calendars and sends cancellation notifications to all participants.",
            area = "Meetings",
            type = CatalogRequest.Type.CHANGE_SYSTEM, tool = true)
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse cancelEvent(@Field.Text(name = "Event Id", attributes = {@Attribute(name = "toolTip", value = "'Unique identifier of the meeting or event to cancel'")}) String eventId) {

        TelemetryHelper.TelemetryOperation operation = telemetryHelper.startOperation("cancelEvent");

        try {
            ExtensionResponse response;
            if (isErrorHandlingEnabled()) {
                LOGGER.debug("Error handling is enabled, using CancelEventHelper");
                response = cancelEventHelper.cancelEventWithValidation(eventId);
            } else {
                LOGGER.info("cancelEvent: eventId={}", eventId);
                telemetryMetrics.incrementCounter(TelemetryConstants.CANCEL_EVENT_REQUEST_ATTEMPT, 1L);
                EventDetails eventDetails = calendarService.cancelEvent(eventId);
                telemetryMetrics.incrementCounter(TelemetryConstants.CANCEL_EVENT_REQUEST_SUCCESS, 1L);
                LOGGER.info("Successfully canceled event with ID: {}", eventId);
                response = new ExtensionResponseBuilder()
                        .success(Map.of("Event Details", eventDetails))
                        .build();
            }
            operation.recordSuccess();
            return response;
        } catch (Exception cause) {
            propagateAuthorizationRequired(cause);
            operation.recordFailure(cause);
            telemetryMetrics.incrementCounter(TelemetryConstants.CANCEL_EVENT_REQUEST_FAILURE, 1L);
            LOGGER.error("Error occurred in cancelEvent: eventId={}, error={}", eventId, cause.getMessage(), cause);
            return new ExtensionResponseBuilder()
                    .failure(new ErrorBuilder()
                            .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                            .errorMessage("Error occurred in cancelEvent: " + cause.getMessage())
                            .stackTrace(cause.getStackTrace().toString())
                            .build())
                    .build();
        }
    }

    @CatalogRequest(
            id = "localDomainRequest_9de59f5f-b77d-42e3-84da-63f587f65358",
            name = "Decline Invitation",
            description = "Decline a meeting invitation for a specific event by providing the event ID and attendee email address. This action updates the attendee's response status to declined and notifies the organizer.",
            area = "Meetings",
            type = CatalogRequest.Type.CHANGE_SYSTEM, tool = true)
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse declineInvitation(
            @Field.Text(name = "Event Id", attributes = {@Attribute(name = "toolTip", value = "'Unique identifier of the meeting or event to decline'")}) String eventId,
            @Field(name = "Attendee Email", type = "Email", attributes = {@Attribute(name = "visualWidth", value = "S"), @Attribute(name = "toolTip", value = "'Email address of the attendee declining the invitation'")}) String attendeeEmail) {

        TelemetryHelper.TelemetryOperation operation = telemetryHelper.startOperation("declineInvitation");

        try {
            ExtensionResponse response;
            if (isErrorHandlingEnabled()) {
                LOGGER.debug("Error handling is enabled, using DeclineInvitationHelper");
                response = declineInvitationHelper.declineInvitationWithValidation(eventId, attendeeEmail);
            } else {
                LOGGER.info("declineInvitation called: EventId={}, AttendeeEmail={}", eventId, attendeeEmail);
                telemetryMetrics.incrementCounter(TelemetryConstants.DECLINE_INVITATION_REQUEST_COUNT, 1L);
                EventDetails eventDetails = calendarService.declineInvitation(eventId, attendeeEmail);
                telemetryMetrics.incrementCounter(TelemetryConstants.DECLINE_INVITATION_REQUEST_SUCCESS, 1L);
                response = new ExtensionResponseBuilder()
                        .success(Map.of("Event Details", eventDetails))
                        .build();
            }
            operation.recordSuccess();
            return response;
        } catch (Exception cause) {
            propagateAuthorizationRequired(cause);
            operation.recordFailure(cause);
            telemetryMetrics.incrementCounter(TelemetryConstants.DECLINE_INVITATION_REQUEST_FAILURE, 1L);
            LOGGER.error("Error occurred in declineInvitation: {}", cause.getMessage(), cause);
            return new ExtensionResponseBuilder()
                    .failure(new ErrorBuilder()
                            .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                            .errorMessage("Error occurred in decline invitation: " + cause.getMessage())
                            .stackTrace(cause.getMessage())
                            .build())
                    .build();
        }
    }

    @CatalogRequest(
            id = "localDomainRequest_771024d0-02e5-4163-869e-8f8860a10b62",
            name = "Get Next Upcoming Meetings",
            description = "Retrieve a specified number of upcoming meetings and events from the calendar. Provide the number of meetings to fetch, and the system will return the next scheduled events in chronological order.",
            area = "Meetings",
            type = CatalogRequest.Type.CHANGE_SYSTEM, tool = true)
    @Field.Desc(name = "Event Details", type = "[ Entity(Event Details) ]", required = false)
    public ExtensionResponse getNextUpcomingMeetings(
            @Field(name = "Number of meeting", type = "Number", attributes = {@Attribute(name = "toolTip", value = "'Number of upcoming meetings to retrieve from the calendar'")}) Double numberOfMeeting) {

        TelemetryHelper.TelemetryOperation operation = telemetryHelper.startOperation("getNextUpcomingMeetings");

        try {
            ExtensionResponse response;

            if (isErrorHandlingEnabled()) {
                LOGGER.debug("Error handling is enabled, using GetNextUpcomingMeetingsHelper");
                response = getNextUpcomingMeetingsHelper.getNextUpcomingMeetingsWithValidation(numberOfMeeting);
            } else {
                LOGGER.info("getNextUpcomingMeetings: numberOfMeeting={}", numberOfMeeting);
                telemetryMetrics.incrementCounter(TelemetryConstants.GET_NEXT_UPCOMING_MEETINGS_REQUEST, 1L);
                List<EventDetails> eventDetailsList = calendarService.getNextUpcomingMeetings(numberOfMeeting);
                telemetryMetrics.incrementCounter(TelemetryConstants.GET_NEXT_UPCOMING_MEETINGS_REQUEST_SUCCESS, 1L);
                LOGGER.info("Successfully retrieved {} upcoming meetings", eventDetailsList.size());
                response = new ExtensionResponseBuilder()
                        .success(Map.of("Event Details", eventDetailsList))
                        .build();
            }
            operation.recordSuccess();
            return response;
        } catch (Exception cause) {
            propagateAuthorizationRequired(cause);
            operation.recordFailure(cause);
            telemetryMetrics.incrementCounter(TelemetryConstants.GET_NEXT_UPCOMING_MEETINGS_REQUEST_FAILURE, 1L);
            LOGGER.error("Error occurred in getNextUpcomingMeetings: numberOfMeeting={}, error={}",
                    numberOfMeeting, cause.getMessage(), cause);
            return new ExtensionResponseBuilder()
                    .failure(new ErrorBuilder()
                            .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                            .errorMessage("Error occurred in getNextUpcomingMeetings: " + cause.getMessage())
                            .stackTrace(Arrays.toString(cause.getStackTrace()))
                            .build())
                    .build();
        }
    }


    @CatalogRequest(
            id = "localDomainRequest_653a694e-2327-41d5-8a8c-48b30b07dc89",
            name = "Remove Attendee",
            description = "Remove a specific attendee from an existing scheduled meeting by providing the event ID and attendee email address. This action removes the attendee from the event and sends them a cancellation notification.",
            area = "Meetings",
            type = CatalogRequest.Type.CHANGE_SYSTEM, tool = true)
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse removeAttendee(
            @Field.Text(name = "Event Id", attributes = {@Attribute(name = "toolTip", value = "'Unique identifier of the meeting or event to remove attendee from'")}) String eventId,
            @Field(name = "Attendee Email", type = "Email", attributes = {@Attribute(name = "visualWidth", value = "S"), @Attribute(name = "toolTip", value = "'Email address of the attendee to remove from the meeting'")}) String attendeeEmail) {

        TelemetryHelper.TelemetryOperation operation = telemetryHelper.startOperation("removeAttendee");

        try {
            ExtensionResponse response;
            if (isErrorHandlingEnabled()) {
                LOGGER.debug("Error handling is enabled, using RemoveAttendeeHelper");
                response = removeAttendeeHelper.removeAttendeeWithValidation(eventId, attendeeEmail);
            } else {
                LOGGER.info("removeAttendee: eventId={}, attendeeEmail={}", eventId, attendeeEmail);
                telemetryMetrics.incrementCounter(TelemetryConstants.REMOVE_ATTENDEE_REQUEST_TOTAL, 1L);
                EventDetails eventDetails = calendarService.removeAttendee(eventId, attendeeEmail);
                telemetryMetrics.incrementCounter(TelemetryConstants.REMOVE_ATTENDEE_REQUEST_SUCCESS, 1L);
                LOGGER.info("Successfully removed attendee {} from event ID: {}", attendeeEmail, eventId);
                response = new ExtensionResponseBuilder()
                        .success(Map.of("Event Details", eventDetails))
                        .build();
            }
            operation.recordSuccess();
            return response;
        } catch (Exception cause) {
            propagateAuthorizationRequired(cause);
            operation.recordFailure(cause);
            telemetryMetrics.incrementCounter(TelemetryConstants.REMOVE_ATTENDEE_REQUEST_FAILURE, 1L);
            LOGGER.error("Error occurred in removeAttendee: eventId={}, attendeeEmail={}, error={}",
                    eventId, attendeeEmail, cause.getMessage(), cause);
            ExtensionResponse.Error error = new ErrorBuilder()
                    .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                    .errorMessage("Error occurred in removeAttendee: " + cause.getMessage())
                    .stackTrace(Arrays.toString(cause.getStackTrace()))
                    .build();
            return new ExtensionResponseBuilder()
                    .failure(error)
                    .build();
        }
    }

    @CatalogRequest(
            id = "localDomainRequest_f9cf27ee-ffe7-4b65-8e0a-ce0afa25f5dc",
            name = "Schedule Meeting",
            description = "Create and schedule a new meeting with comprehensive details including title, location, start/end times, description, and attendee list. This action creates the event and sends invitations to all specified attendees.",
            area = "Meetings",
            type = CatalogRequest.Type.CHANGE_SYSTEM, tool = true)
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse scheduleMeeting(
            @Field.Text(name = "Title", attributes = {@Attribute(name = "toolTip", value = "'Title or subject of the meeting'")}) String title,
            @Field.Text(name = "Location", required = false, attributes = {@Attribute(name = "toolTip", value = "'Physical or virtual location where the meeting will take place'")}) String location,
            @Field.Date(name = "Date From", required = true, attributes = {@Attribute(name = "toolTip", value = "'Start date and time of the meeting'")}) Long dateFrom,
            @Field.Date(name = "Date to", required = true, attributes = {@Attribute(name = "toolTip", value = "'End date and time of the meeting'")}) Long dateTo,
            @Field(name = "Details", type = "RichText", attributes = {@Attribute(name = "visualWidth", value = "L"), @Attribute(name = "toolTip", value = "'Additional description or agenda for the meeting'")}) String details,
            @Field.Desc(name = "Attendee Email", type = "[ Email ]") List<String> attendeeEmail) {

        TelemetryHelper.TelemetryOperation operation = telemetryHelper.startOperation("scheduleMeeting");

        try {
            ExtensionResponse response;
            if (isErrorHandlingEnabled()) {
                LOGGER.debug("Error handling is enabled, using ScheduleMeetingHelper");
                response = scheduleMeetingHelper.scheduleMeetingWithValidation(title, location, dateFrom, dateTo, details, attendeeEmail);
            } else {
                LOGGER.info("scheduleMeeting: title={}, location={}, dateFrom={}, dateTo={}, attendeeCount={}",
                        title, location, dateFrom, dateTo, attendeeEmail != null ? attendeeEmail.size() : 0);
                telemetryMetrics.incrementCounter(TelemetryConstants.SCHEDULE_MEETING_REQUEST, 1L);
                EventDetails eventDetails = calendarService.scheduleMeeting(title, location, dateFrom, dateTo, details, attendeeEmail);
                telemetryMetrics.incrementCounter(TelemetryConstants.SCHEDULE_MEETING_REQUEST_SUCCESS, 1L);
                LOGGER.info("Successfully scheduled meeting with ID: {}", eventDetails.eventId);
                response = new ExtensionResponseBuilder()
                        .success(Map.of("Event Details", eventDetails))
                        .build();
            }
            operation.recordSuccess();
            return response;
        } catch (Exception cause) {
            propagateAuthorizationRequired(cause);
            operation.recordFailure(cause);
            telemetryMetrics.incrementCounter(TelemetryConstants.SCHEDULE_MEETING_REQUEST_FAILURE, 1L);
            LOGGER.error("Error occurred in scheduleMeeting: title={}, location={}, dateFrom={}, dateTo={}, attendeeCount={}, error={}",
                    title, location, dateFrom, dateTo, attendeeEmail != null ? attendeeEmail.size() : 0, cause.getMessage(), cause);
            return new ExtensionResponseBuilder()
                    .failure(new ErrorBuilder()
                            .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                            .errorMessage("Error occurred in scheduleMeeting: " + cause.getMessage())
                            .stackTrace(Arrays.toString(cause.getStackTrace()))
                            .build())
                    .build();
        }
    }

    @CatalogRequest(
            id = "localDomainRequest_5bf7458f-953f-40c4-b75c-17b1f76a08b1",
            name = "Tentative Invitation",
            description = "Respond tentatively to a meeting invitation by providing the event ID and attendee email address. This action marks the attendee's response as tentative, indicating possible attendance pending confirmation.",
            area = "Meetings",
            type = CatalogRequest.Type.CHANGE_SYSTEM, tool = true)
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse tentativeInvitation(
            @Field.Text(name = "Event Id", attributes = {@Attribute(name = "toolTip", value = "'Unique identifier of the meeting or event to respond tentatively to'")}) String eventId,
            @Field(name = "Attendee Email", type = "Email", attributes = {@Attribute(name = "visualWidth", value = "S"), @Attribute(name = "toolTip", value = "'Email address of the attendee responding tentatively to the invitation'")}) String attendeeEmail) {

        TelemetryHelper.TelemetryOperation operation = telemetryHelper.startOperation("tentativeInvitation");

        try {
            ExtensionResponse response;
            if (isErrorHandlingEnabled()) {
                LOGGER.debug("Error handling is enabled, using TentativeInvitationHelper");
                response = tentativeInvitationHelper.tentativeInvitationWithValidation(eventId, attendeeEmail);
            } else {
                LOGGER.info("tentativeInvitation called: EventId={}, AttendeeEmail={}", eventId, attendeeEmail);
                telemetryMetrics.incrementCounter(TelemetryConstants.TENTATIVE_INVITATION_REQUEST_COUNT, 1L);
                EventDetails eventDetails = calendarService.tentativeInvitation(eventId, attendeeEmail);
                telemetryMetrics.incrementCounter(TelemetryConstants.TENTATIVE_INVITATION_REQUEST_SUCCESS, 1L);
                LOGGER.info("Successfully responded tentatively to event ID: {} for attendee: {}", eventId, attendeeEmail);
                response = new ExtensionResponseBuilder()
                        .success(Map.of("Event Details", eventDetails))
                        .build();
            }
            operation.recordSuccess();
            return response;
        } catch (Exception cause) {
            propagateAuthorizationRequired(cause);
            operation.recordFailure(cause);
            telemetryMetrics.incrementCounter(TelemetryConstants.TENTATIVE_INVITATION_REQUEST_FAILURE, 1L);
            LOGGER.error("Error occurred while processing tentative invitation: {}", cause.getMessage(), cause);
            ExtensionResponse.Error error = new ErrorBuilder()
                    .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                    .errorMessage("Error occurred while processing tentative invitation: " + cause.getMessage())
                    .stackTrace(Arrays.toString(cause.getStackTrace()))
                    .build();
            return new ExtensionResponseBuilder().failure(error).build();
        }
    }

    @CatalogRequest(
            id = "localDomainRequest_e0f748a2-17df-4e29-84e0-a1d0d06934d9",
            name = "Get All Unaccepted Events",
            description = "Retrieve a list of all meeting invitations that have not yet been accepted, declined, or responded to. This provides an overview of pending meeting invitations requiring attention.",
            area = "Meetings",
            type = CatalogRequest.Type.CHANGE_SYSTEM, tool = true)
    @Field.Desc(name = "Event Details", type = "[ Entity(Event Details) ]", required = false)
    public List<EventDetails> getAllUnacceptedEvents() {
        TelemetryHelper.TelemetryOperation operation = telemetryHelper.startOperation("getAllUnacceptedEvents");

        try {
            LOGGER.info("getAllUnacceptedEvents called");
            List<EventDetails> eventDetailsList = calendarService.getAllUnacceptedEvents();
            int eventCount = eventDetailsList != null ? eventDetailsList.size() : 0;
            LOGGER.info("Successfully retrieved {} unaccepted events", eventCount);
            operation.recordSuccess(eventCount);
            return eventDetailsList;
        } catch (Exception cause) {
            operation.recordFailure(cause);
            LOGGER.error("Error occurred in getAllUnacceptedEvents: {}", cause.getMessage(), cause);
            throw cause;
        }
    }

    @CatalogRequest(
            id = "localDomainRequest_48062a11-b237-4307-9394-a6c3496bff14",
            name = "Get Event",
            description = "Retrieve comprehensive details of a specific meeting or event by providing its unique event ID. This returns all event information including title, location, time, attendees, and description.",
            area = "Meetings",
            type = CatalogRequest.Type.QUERY_SYSTEM, tool = true)
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse getEvent(@Field(name = "Event Id", type = "Text", attributes = {@Attribute(name = "toolTip", value = "'Unique identifier of the meeting or event to retrieve'")}) String eventId) {
        TelemetryHelper.TelemetryOperation operation = telemetryHelper.startOperation("getEvent");

        try {
            ExtensionResponse response;
            if (isErrorHandlingEnabled()) {
                LOGGER.debug("Error handling is enabled, using GetEventHelper");
                response = getEventHelper.getEventWithValidation(eventId);
            } else {
                LOGGER.info("getEvent: eventId={}", eventId);
                telemetryMetrics.incrementCounter(TelemetryConstants.GET_EVENT_REQUEST_ATTEMPT, 1L);
                EventDetails eventDetails = calendarService.getEvent(eventId);
                telemetryMetrics.incrementCounter(TelemetryConstants.GET_EVENT_REQUEST_SUCCESS, 1L);
                LOGGER.info("Successfully retrieved event with ID: {}", eventId);
                response = new ExtensionResponseBuilder()
                        .success(Map.of("Event Details", eventDetails))
                        .build();
            }
            operation.recordSuccess();
            return response;
        } catch (Exception cause) {
            propagateAuthorizationRequired(cause);
            operation.recordFailure(cause);
            telemetryMetrics.incrementCounter(TelemetryConstants.GET_EVENT_REQUEST_FAILURE, 1L);
            LOGGER.error("Error occurred in getEvent: eventId={}, error={}", eventId, cause.getMessage(), cause);
            return new ExtensionResponseBuilder()
                    .failure(new ErrorBuilder()
                            .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                            .errorMessage("Error occurred in getEvent: " + cause.getMessage())
                            .stackTrace(Arrays.toString(cause.getStackTrace()))
                            .build())
                    .build();
        }
    }

    @CatalogRequest(
            id = "localDomainRequest_74cb78ed-7c37-4234-850b-29069186c9a8",
            name = "Get Events for a given User",
            description = "This request will return the events scheduled for a given user given that the setup is done using a system account.",
            area = "Meetings",
            type = CatalogRequest.Type.QUERY_SYSTEM, tool = true)
    @Field.Desc(name = CalendarResource.EVENTS, type = "[ Entity(Event Details) ]", required = false)
    public ExtensionResponse getEventsForAGivenUser(
            @Field(name = "User Email", type = "Email", required = true, attributes = {@Attribute(name = "visualWidth", value = "S")}, options = {}) String userEmail,
            @Field.Date(name = "From", required = true, includeTimeOfDay = true, showHowManyDaysInViewer = 60, allowPast = true, allowToday = true, allowFuture = true, defaultTimeSpan = 1, attributes = {@Attribute(name = "visualWidth", value = "S")}, options = {}) Long from,
            @Field.Date(name = "To", required = true, includeTimeOfDay = true, showHowManyDaysInViewer = 60, allowPast = true, allowToday = true, allowFuture = true, defaultTimeSpan = 1, attributes = {@Attribute(name = "visualWidth", value = "S")}, options = {}) Long to) {

        TelemetryHelper.TelemetryOperation operation = telemetryHelper.startOperation("getEventsForAGivenUser");

        try {
            ExtensionResponse response;
            if (isErrorHandlingEnabled()) {
                LOGGER.debug("Error handling is enabled, using GetEventsForUserHelper");
                response = getEventsForUserHelper.getEventsForUserWithValidation(userEmail, from, to);
            } else {
                LOGGER.info("getEventsForAGivenUser: userEmail={}, from={}, to={}", userEmail, from, to);
                telemetryMetrics.incrementCounter(TelemetryConstants.GET_EVENTS_FOR_USER_REQUEST, 1L);
                List<EventDetails> events = calendarService.getEventsForUser(userEmail, from, to);
                telemetryMetrics.incrementCounter(TelemetryConstants.GET_EVENTS_FOR_USER_REQUEST_SUCCESS, 1L);
                LOGGER.info("Successfully retrieved {} events for user {}", events.size(), userEmail);
                response = new ExtensionResponseBuilder()
                        .success(Map.of(CalendarResource.EVENTS, events))
                        .build();
            }
            operation.recordSuccess();
            return response;
        } catch (Exception cause) {
            propagateAuthorizationRequired(cause);
            operation.recordFailure(cause);
            telemetryMetrics.incrementCounter(TelemetryConstants.GET_EVENTS_FOR_USER_REQUEST_FAILURE, 1L);
            LOGGER.error("Error occurred in getEventsForAGivenUser: {}", cause.getMessage(), cause);
            ExtensionResponse.Error error = new ErrorBuilder()
                    .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                    .errorMessage("Error occurred in get events for user: " + cause.getMessage())
                    .stackTrace(Arrays.toString(cause.getStackTrace()))
                    .build();
            return new ExtensionResponseBuilder().failure(error).build();
        }
    }

    @CatalogRequest(
            id = "localDomainRequest_a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            name = "Adaptive Calendar Sync",
            description = "Dynamically syncs Google Calendar data to MA based on a user-defined time window. Enter the number of days (default 30), and the system will fetch and sync events within that range for all authenticated users.",
            area = "Meetings",
            type = CatalogRequest.Type.QUERY_SYSTEM, tool = true)
    @Field.Text(name = "Status", required = false, attributes = {@Attribute(name = "visualWidth", value = "S")}, options = {})
    public ExtensionResponse adaptiveCalendarSync(
            @Field(name = "Number of Days", type = "Number", required = false, attributes = {@Attribute(name = "visualWidth", value = "S"), @Attribute(name = "toolTip", value = "'Number of future days to sync (default: 30)'")}) Double numberOfDays) {

        LOGGER.info("adaptiveCalendarSync called: numberOfDays={}", numberOfDays);
        try {
            return adaptiveCalendarSyncHandler.execute(numberOfDays);
        } catch (Exception cause) {
            propagateAuthorizationRequired(cause);
            LOGGER.error("Error occurred during adaptive calendar sync: {}", cause.getMessage(), cause);
            ExtensionResponse.Error error = new ErrorBuilder()
                    .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                    .errorMessage("Error occurred during adaptive calendar sync: " + cause.getMessage())
                    .stackTrace(Arrays.toString(cause.getStackTrace()))
                    .build();
            return new ExtensionResponseBuilder().failure(error).build();
        }
    }

    @CatalogRequest(
            id = "localDomainRequest_b2c3d4e5-f6a7-8901-bcde-f12345678901",
            name = "Calendar Sync by User",
            description = "Fetch and sync Google Calendar events for a specific authenticated user by entering their email address and the number of days to sync.",
            area = "Meetings",
            type = CatalogRequest.Type.CHANGE_SYSTEM, tool = true)
    @Field.Text(name = "Status", required = false, attributes = {@Attribute(name = "visualWidth", value = "S")}, options = {})
    public ExtensionResponse calendarSyncByUser(
            @Field(name = "User Email", type = "Email", required = true, attributes = {@Attribute(name = "visualWidth", value = "S"), @Attribute(name = "toolTip", value = "'Email address of the user whose calendar to sync'")}) String userEmail,
            @Field(name = "Number of Days", type = "Number", required = true, attributes = {@Attribute(name = "visualWidth", value = "S"), @Attribute(name = "toolTip", value = "'Number of future days to sync'")}) Double numberOfDays) {

        LOGGER.info("calendarSyncByUser called: userEmail={}, numberOfDays={}", userEmail, numberOfDays);
        try {
            return calendarSyncByUserHandler.execute(userEmail, numberOfDays);
        } catch (MustAuthorizeException cause) {
            // Must reach the extension executor unchanged; it is the platform signal that renders
            // the Authenticate button inside the conversation.
            throw cause;
        } catch (Exception cause) {
            LOGGER.error("Error occurred during calendar sync by user: {}", cause.getMessage(), cause);
            ExtensionResponse.Error error = new ErrorBuilder()
                    .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                    .errorMessage("Error occurred during calendar sync by user: " + cause.getMessage())
                    .stackTrace(Arrays.toString(cause.getStackTrace()))
                    .build();
            return new ExtensionResponseBuilder().failure(error).build();
        }
    }


    @CatalogRequest(
            id = "localDomainRequest_496c4d65-3ea7-4900-8c20-c3547c245ca1",
            name = "Save Configuration Google Calendar",
            description = "Completes the supplied attributes from the configuration already saved for this "
                    + "invoker (so only the email is needed once the rest is configured), authenticates with "
                    + "Google, and saves the result after authentication succeeds.",
            area = "Meetings",
            type = CatalogRequest.Type.CHANGE_SYSTEM)
    @Field.Text(name = "Response", required = false, description = "", attributes = {@Attribute(name = "visualWidth", value = "S")}, options = {})
    public ExtensionResponse saveConfigurationGoogleCalendar(
            @Field.Desc(name = "Attributes", type = "{ clientId: Text, clientSecret: Text(secured), email: Text, serviceUrl: Text, serviceApiKey: Text(secured), serviceAccountKey: Text(secured), tenantAdminEmail: Email, tenantDomain: Text, alwaysOn: Boolean, mode: PickOne(Public|Private) }", required = true, description = "") Map<String, Object> attributes) {
        LOGGER.info("Entered saveConfigurationGoogleCalendar with attribute keys: {}",
                attributes != null ? attributes.keySet() : "null");

        // The request is validated as the configuration it will PRODUCE, not as the form it was
        // handed: what was supplied, completed from what is already saved — the invoker's Studio
        // attributes (service URL and key, delegation credentials, Always On) and the OAuth client
        // configuration an earlier run of this request stored. That is what lets an admin set the
        // service details up first and later run this request with just the email: the saved
        // details are used, the email is added, and the authentication below is the same one a
        // fully supplied form goes through.
        EffectiveConfiguration effective;
        try {
            if (attributes == null || attributes.isEmpty()) {
                throw new IllegalArgumentException("Attributes are required");
            }
            effective = completeFromSavedConfiguration(attributes);
            validateSaveConfigurationAttributes(effective.attributes());
        } catch (Exception e) {
            LOGGER.error("Failed to validate Google Calendar save-configuration attributes", e);
            return new ExtensionResponseBuilder()
                    .success(Map.of("Response", getErrorMessage(e)))
                    .build();
        }
        Map<String, Object> configuration = effective.attributes();

        String invokerId = invoker.getInvokerId();
        GoogleAttributes googleAttributes;
        // Always On is the administrator/DWD path. It must never launch the individual-user
        // OAuth flow (which would incorrectly ask the admin to connect their personal calendar).
        // The DWD credentials are validated by the extension's VALIDATE_ATTRIBUTES hook; this
        // request also validates them when invoked directly from a conversation. The latter is
        // important because conversational catalog requests do not necessarily pass through the
        // Studio invoker-validation hook first.
        if (Constants.readAlwaysOn(configuration)) {
            if (calendarDiscoveryService != null) {
                try {
                    calendarDiscoveryService.validateDelegatedAccessIfAlwaysOn(configuration);
                } catch (DomainWideDelegationException cause) {
                    LOGGER.warn("Google Workspace DWD is not ready for conversational save: {}",
                            cause.getMessage());
                    throw dwdSetupRequired(configuration, cause);
                }
            }
            boolean isSaved = saveInvokerServiceAttributes(configuration, attributes, invokerId);
            String message = isSaved ? savedMessage(effective) : "failed to save";
            return new ExtensionResponseBuilder()
                    .success(Map.of("Response", message))
                    .build();
        }

        try {
            googleAttributes = createGoogleAttributes(configuration);
        } catch (Exception e) {
            LOGGER.error("Failed to prepare Google Calendar save-configuration attributes", e);
            return new ExtensionResponseBuilder()
                    .success(Map.of("Response", getErrorMessage(e)))
                    .build();
        }

        try {
            if (isAlreadySavedAndAuthenticated(googleAttributes, invokerId)) {
                boolean isServiceSaved = saveInvokerServiceAttributes(configuration, attributes, invokerId);
                String message = isServiceSaved ? savedMessage(effective) : "failed to save";
                return new ExtensionResponseBuilder()
                        .success(Map.of("Response", message))
                        .build();
            }
        } catch (Exception e) {
            LOGGER.warn("Unable to check existing Google Calendar authentication. Continuing with validation flow: {}",
                    e.getMessage());
        }

        String authContextId = googleProviderFactory.createAttributes(googleAttributes);
        boolean keepAuthContext = false;
        try {
            googleProviderFactory.create(authContextId).testConnection();

            boolean isSaved = googleAttributeStore.save(googleAttributes, invokerId);
            if (!isSaved) {
                return new ExtensionResponseBuilder().success(Map.of("Response", "failed to save")).build();
            }
            boolean isServiceSaved = saveInvokerServiceAttributes(configuration, attributes, invokerId);
            String message = isSaved && isServiceSaved ? savedMessage(effective) : "failed to save";

            return new ExtensionResponseBuilder()
                    .success(Map.of("Response", message))
                    .build();
        } catch (MustAuthorizeException e) {
            keepAuthContext = true;
            throw e;
        } catch (Exception e) {
            LOGGER.error("Failed to save Google Calendar configuration", e);
            return new ExtensionResponseBuilder()
                    .success(Map.of("Response", getErrorMessage(e)))
                    .build();
        } finally {
            if (!keepAuthContext) {
                googleAttributeStore.remove(authContextId);
            }
        }
    }

    private boolean isAlreadySavedAndAuthenticated(GoogleAttributes googleAttributes, String invokerId)
            throws java.io.IOException {
        GoogleAttributes savedAttributes = googleAttributeStore.load(invokerId);
        if (!isSameGoogleConfiguration(savedAttributes, googleAttributes)) {
            return false;
        }
        String refreshTokenKey = getRefreshTokenStoreKey(googleAttributes);
        String refreshToken = refreshTokenStore.get(refreshTokenKey);
        if (refreshToken == null || refreshToken.isBlank()) {
            return false;
        }

        // A stored string is not proof of authentication: Google may have revoked/expired it.
        // Validate the persisted grant before using the shortcut. MustAuthorizeException is caught
        // by the caller only to enter the temporary auth-context flow below, where it is thrown
        // again with the correct context so the conversation renders the button and resumes after
        // the OAuth callback.
        googleProviderFactory.createForInvoker(invokerId).testConnection();
        return true;
    }

    private boolean isSameGoogleConfiguration(GoogleAttributes savedAttributes, GoogleAttributes requestedAttributes) {
        return savedAttributes != null
                && requestedAttributes != null
                && Objects.equals(savedAttributes.getMailId(), requestedAttributes.getMailId())
                && Objects.equals(savedAttributes.getAuthType(), requestedAttributes.getAuthType())
                && Objects.equals(savedAttributes.getClientId(), requestedAttributes.getClientId())
                && Objects.equals(savedAttributes.getClientSecret(), requestedAttributes.getClientSecret());
    }

    private String getRefreshTokenStoreKey(GoogleAttributes googleAttributes) {
        return googleAttributes.getMailId()
                + Constants.UNDER_SCORE
                + googleAttributes.getClientId()
                + Constants.UNDER_SCORE
                + googleAttributes.getAuthType();
    }

    private String getErrorMessage(Exception e) {
        return e.getMessage() != null
                ? e.getMessage()
                : "Unknown error: " + e.getClass().getSimpleName();
    }

    /** The configuration this request will save, and whether it declined to switch Always On off. */
    private record EffectiveConfiguration(Map<String, Object> attributes, boolean alwaysOnKeptOn) { }

    private static String savedMessage(EffectiveConfiguration effective) {
        return effective.alwaysOnKeptOn()
                ? "saved successfully (Always On stays on; use the Studio toggle to turn it off)"
                : "saved successfully";
    }

    /**
     * Completes the attributes supplied to the Save Configuration request from everything already
     * saved for this invoker, so the request changes only what it actually carries.
     *
     * <p>Saved means two places. The invoker's attribute map holds what Studio configures — service
     * URL and API key, the delegation credentials, Always On, retry. The {@link GoogleAttributeStore}
     * holds the OAuth client configuration an earlier run of this request stored — client id and
     * secret, mode, email — which Studio never sees. Both are the starting point; the form is laid
     * over them.
     *
     * <p>Rules. A supplied value wins when it says something: null and blank values are what an
     * untouched form field looks like, so they keep the saved value rather than erase it (clearing a
     * required attribute through this request is never a valid intent, and validation would refuse
     * the blank anyway). {@code alwaysOn} is the one exception to "supplied wins": a {@code false}
     * does not switch a saved {@code true} off, because an untouched toggle is indistinguishable from
     * a deliberate one and an email-only save that silently dropped a tenant from delegation back to
     * per-user OAuth — prompting the admin to connect a personal calendar — is exactly the wipe the
     * merge exists to prevent. Turning Always On ON through this request works; turning it OFF is
     * the Studio toggle's job, and the response says so.
     *
     * @param attributes the attributes supplied to this catalog request; never null or empty here
     * @return the completed configuration, safe to validate, authenticate and save
     */
    private EffectiveConfiguration completeFromSavedConfiguration(Map<String, Object> attributes) {
        Map<String, Object> effective = currentAttributesWith(Map.of());

        try {
            GoogleAttributes saved = googleAttributeStore.load(invoker.getInvokerId());
            if (saved != null) {
                putIfPresent(effective, GoogleAttributes.CLIENT_ID, saved.getClientId());
                putIfPresent(effective, GoogleAttributes.CLIENT_SECRET, saved.getClientSecret());
                putIfPresent(effective, GoogleAttributes.EMAIL, saved.getMailId());
                putIfPresent(effective, Constants.AUTH_TYPE, saved.getAuthType());
            }
        } catch (RuntimeException cause) {
            LOGGER.debug("Could not read the saved Google OAuth configuration during save validation: {}",
                    cause.getMessage());
        }

        boolean alwaysOnKeptOn = false;
        List<String> completedFromSaved = new ArrayList<>();
        for (Map.Entry<String, Object> supplied : attributes.entrySet()) {
            String key = supplied.getKey();
            if (supplied.getValue() == null || isBlankAttribute(attributes, key)) {
                if (effective.get(key) != null) {
                    completedFromSaved.add(key);
                }
                continue;
            }
            if (Constants.ALWAYS_ON.equals(key)
                    && !Constants.readAlwaysOn(attributes)
                    && Constants.readAlwaysOn(effective)) {
                alwaysOnKeptOn = true;
                continue;
            }
            effective.put(key, supplied.getValue());
        }
        if (!completedFromSaved.isEmpty()) {
            LOGGER.info("Save Configuration completed {} from the saved configuration", completedFromSaved);
        }
        if (alwaysOnKeptOn) {
            LOGGER.warn("Save Configuration carried alwaysOn=false for an invoker saved with Always On ON; "
                    + "keeping it ON — the Studio toggle is where Always On is turned off");
        }
        return new EffectiveConfiguration(effective, alwaysOnKeptOn);
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /**
     * Validates the configuration the request will produce — the supplied attributes completed from
     * the saved ones — so "missing" means missing from both.
     */
    private void validateSaveConfigurationAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            throw new IllegalArgumentException("Attributes are required");
        }

        StringBuilder missing = new StringBuilder();
        Map<String, Object> effectiveAttributes = currentAttributesWith(attributes);
        boolean alwaysOn = Constants.readAlwaysOn(effectiveAttributes);
        if (isBlankAttribute(attributes, Constants.SERVICE_URL)) {
            missing.append("serviceUrl, ");
        }
        // KIM-457: serviceApiKey is declared on the extension but was absent from this catalog
        // request, so an admin configuring Google Calendar here had no way to supply the MA key —
        // and every MA call went out unauthenticated.
        if (isBlankAttribute(attributes, Constants.SERVICE_API_KEY)) {
            missing.append("serviceApiKey, ");
        }

        // Always On uses Google Workspace domain-wide delegation instead of per-user OAuth.
        // Require the two credentials that make delegation possible, but leave them optional when
        // the flag is off so the existing individually-authenticated flow is unchanged.
        if (alwaysOn) {
            String missingDelegation = Constants.missingDelegationFields(effectiveAttributes);
            if (missingDelegation != null) {
                missing.append(missingDelegation).append(", ");
            }
        } else {
            if (isBlankAttribute(attributes, GoogleAttributes.EMAIL)) {
                missing.append("email, ");
            }
            boolean missingAuthType = isBlankAttribute(attributes, "mode")
                    && isBlankAttribute(attributes, Constants.AUTH_TYPE);
            if (missingAuthType) {
                missing.append("mode, ");
            }

            String authType = missingAuthType ? Constants.EMPTY_STRING : resolveAuthType(attributes);
            if (Constants.PRIVATE.equals(authType)) {
                if (isBlankAttribute(attributes, GoogleAttributes.CLIENT_ID)) {
                    missing.append("clientId, ");
                }
                if (isBlankAttribute(attributes, GoogleAttributes.CLIENT_SECRET)) {
                    missing.append("clientSecret, ");
                }
            }
        }

        if (!missing.isEmpty()) {
            missing.setLength(missing.length() - 2);
            throw new IllegalArgumentException("Missing required field(s): " + missing);
        }

        if (!alwaysOn) {
            String email = getTrimmedAttribute(attributes, GoogleAttributes.EMAIL);
            if (!Validator.isValidEmail(email)) {
                throw new IllegalArgumentException("Invalid email format: " + email);
            }
        }
    }

    private GoogleAttributes createGoogleAttributes(Map<String, Object> attributes) {
        String authType = resolveAuthType(attributes);
        String clientId = Constants.PRIVATE.equals(authType)
                ? getTrimmedAttribute(attributes, GoogleAttributes.CLIENT_ID)
                : null;
        String clientSecret = Constants.PRIVATE.equals(authType)
                ? getTrimmedAttribute(attributes, GoogleAttributes.CLIENT_SECRET)
                : null;
        String email = getTrimmedAttribute(attributes, GoogleAttributes.EMAIL);
        String baseRoutingUrl = invoker.getRoutingInfo()
                .getRoutingURL(HttpProtocol.PROTOCOL_NAME, RoutingInfo.Type.APPLIANCE);

        return new GoogleAttributes(clientId, clientSecret, email, authType, baseRoutingUrl);
    }

    /**
     * Persists the invoker-level settings collected by this catalog request. Values are overlaid
     * onto a copy of the CURRENT invoker attributes rather than a fresh map, so saving never
     * clears a setting the request does not carry. OAuth client credentials remain in the
     * {@link GoogleAttributeStore}, not in the invoker map.
     *
     * @param configuration the completed configuration — the values to write
     * @param supplied      what the request itself carried — decides whether there is anything
     *                      invoker-level to write at all, and whether the CA connection must be
     *                      rebound (a request that only brought the email touches neither)
     */
    private boolean saveInvokerServiceAttributes(Map<String, Object> configuration, Map<String, Object> supplied,
                                                 String invokerId) {
        boolean hasConfigurationAttribute = Constants.invokerConfigurationAttributeNames().stream()
                .anyMatch(supplied::containsKey);
        if (!hasConfigurationAttribute) {
            // No invoker settings were written. Reconcile from committed invoker attributes,
            // not the merged OAuth request (which also contains the separate client secret).
            applySavedConfiguration(currentInvokerAttributes());
            return true;
        }

        Map<String, Object> currentAttributes = currentInvokerAttributes();
        Map<String, Object> invokerAttributes = new HashMap<>();
        if (currentAttributes != null) {
            invokerAttributes.putAll(currentAttributes);
        }

        // Overlay only invoker-level settings. In particular, do not copy OAuth clientSecret into
        // the invoker map; it is persisted in GoogleAttributeStore and remains secured there.
        for (String key : Constants.invokerConfigurationAttributeNames()) {
            if (configuration.containsKey(key)) {
                Object value = configuration.get(key);
                if (Constants.ALWAYS_ON.equals(key) || Constants.ENABLE_ERROR_HANDLING.equals(key)) {
                    invokerAttributes.put(key, value);
                } else {
                    invokerAttributes.put(key, getTrimmedAttribute(configuration, key));
                }
            }
        }

        boolean saved = invokerConfiguration.saveChanges(
                invoker.getWorkspaceId(),
                invokerId,
                invokerAttributes,
                "live",
                false);
        if (saved) {
            // Consumers can drain queued CA commands as soon as reconfigureEsb starts them. Make
            // committed settings visible first so those commands cannot see the pre-save snapshot.
            if (liveConfiguration != null) {
                liveConfiguration.applySaved(invokerAttributes);
            }
            try {
                // Use the just-saved values directly: the request-scoped Invoker may still expose
                // its pre-save attribute snapshot until the next request/container lifecycle.
                boolean hasServiceUrl = supplied.containsKey(Constants.SERVICE_URL);
                boolean hasServiceApiKey = supplied.containsKey(Constants.SERVICE_API_KEY);
                if (serviceBusManager != null && (hasServiceUrl || hasServiceApiKey)) {
                    String serviceUrl = Constants.readTextAttribute(configuration, Constants.SERVICE_URL);
                    String serviceApiKey = Constants.readTextAttribute(configuration, Constants.SERVICE_API_KEY);
                    serviceBusManager.reconfigureEsb(
                            serviceUrl == null ? Constants.EMPTY_STRING : serviceUrl,
                            serviceApiKey == null ? Constants.EMPTY_STRING : serviceApiKey);
                }
            } catch (Exception e) {
                // Configuration is safely persisted; a transient CA/AMQ failure must not turn a
                // successful save into a false failure. A later save or startup retries it.
                LOGGER.error("Saved CA attributes but could not reconfigure ESB immediately: {}",
                        e.getMessage(), e);
            }
            applySavedConfiguration(invokerAttributes);
        }
        return saved;
    }

    private void applySavedConfiguration(Map<String, Object> configuration) {
        if (setupService != null) {
            try {
                setupService.applySavedConfiguration(configuration);
            } catch (Exception failure) {
                LOGGER.warn("Configuration saved but immediate calendar setup could not start.", failure);
            }
        }
    }

    /** Merge a partial catalog request over the current invoker settings for validation. */
    private Map<String, Object> currentAttributesWith(Map<String, Object> attributes) {
        Map<String, Object> effective = new HashMap<>();
        try {
            Map<String, Object> current = currentInvokerAttributes();
            if (current != null) {
                effective.putAll(current);
            }
        } catch (Exception cause) {
            LOGGER.debug("Could not read current invoker attributes during save validation: {}",
                    cause.getMessage());
        }
        effective.putAll(attributes);
        return effective;
    }

    /**
     * Converts a failed tenant DWD probe into Krista's authorization signal. The authenticator
     * recognizes the marker field and returns the setup/continuation page URL, which the
     * conversation renders as its authorization button; this is deliberately not a user OAuth flow.
     */
    private MustAuthorizeException dwdSetupRequired(Map<String, Object> attributes,
                                                    DomainWideDelegationException cause) {
        Map<String, Object> effective = currentAttributesWith(attributes);
        String adminEmail = getTrimmedAttribute(effective, Constants.TENANT_ADMIN_EMAIL);
        List<NamedValuedField> details = new ArrayList<>();
        details.add(new NamedValuedField(Constants.USER_ID, Constants.TEXT,
                adminEmail.isBlank() ? "workspace-admin" : adminEmail,
                new HashMap<>(), new HashMap<>()));
        details.add(new NamedValuedField(Constants.DWD_SETUP_FIELD, Constants.TEXT,
                "true", new HashMap<>(), new HashMap<>()));
        String clientId = Constants.serviceAccountClientId(effective);
        if (clientId != null) {
            details.add(new NamedValuedField(Constants.DWD_CLIENT_ID_FIELD, Constants.TEXT,
                    clientId, new HashMap<>(), new HashMap<>()));
        }
        return new MustAuthorizeException(Constants.DWD_SETUP_PROMPT + " " + cause.getMessage(), details);
    }

    private String resolveAuthType(Map<String, Object> attributes) {
        String authType = getTrimmedAttribute(attributes, "mode");
        if (authType.isBlank()) {
            authType = getTrimmedAttribute(attributes, Constants.AUTH_TYPE);
        }
        if (Constants.PUBLIC.equalsIgnoreCase(authType)) {
            return Constants.PUBLIC;
        }
        if (Constants.PRIVATE.equalsIgnoreCase(authType)) {
            return Constants.PRIVATE;
        }
        throw new IllegalArgumentException(Constants.UNSUPPORTED_AUTH);
    }

    private boolean isBlankAttribute(Map<String, Object> attributes, String key) {
        return getTrimmedAttribute(attributes, key).isBlank();
    }

    private String getTrimmedAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value == null ? "" : getAttributeValueAsString(value).trim();
    }

    private String getAttributeValueAsString(Object value) {
        if (value instanceof Map<?, ?> attributeMap) {
            for (String key : List.of("value", "name", "label", "id")) {
                Object nestedValue = attributeMap.get(key);
                if (nestedValue != null && !nestedValue.toString().isBlank()) {
                    return nestedValue.toString();
                }
            }
        }
        return value.toString();
    }

    /**
     * The extension executor converts {@link MustAuthorizeException} into the authentication
     * button rendered in a conversation. Calendar helpers occasionally wrap the original cause,
     * so inspect the full chain before converting an operational failure into ExtensionResponse.
     */
    private static void propagateAuthorizationRequired(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof MustAuthorizeException authorizationRequired) {
                throw authorizationRequired;
            }
        }
    }

    public boolean isErrorHandlingEnabled() {
        final Object enableErrorHandling = invoker.getAttributes().get(Constants.ENABLE_ERROR_HANDLING);
        boolean errorHandlingFlag = enableErrorHandling != null && (Boolean) enableErrorHandling;
        LOGGER.info("Enable Error Handling Flag:{}", errorHandlingFlag);
        return errorHandlingFlag;
    }
}
