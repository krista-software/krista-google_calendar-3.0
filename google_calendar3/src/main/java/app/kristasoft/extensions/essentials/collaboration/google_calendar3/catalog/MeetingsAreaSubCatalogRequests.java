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

import app.krista.extension.executor.ErrorBuilder;
import app.krista.extension.executor.ExtensionResponse;
import app.krista.extension.executor.ExtensionResponseBuilder;
import app.krista.extension.impl.anno.CatalogRequest;
import app.krista.extension.impl.anno.Field;
import app.krista.extension.impl.anno.SubCatalogRequest;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.entity.EventDetails;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.errorhandlers.ErrorHandlingStateManager;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.errorhandlers.ExtensionResponseGenerator;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.CalendarResource;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.SubCatalogConstants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.validators.ValidationOrchestrator;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.CalendarService;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.SubCatalogConstants.REENTER;

/**
 * SubCatalog request handlers for Google Calendar operations.
 * Handles validation failures and user input corrections through SubCatalogRequest flow.
 */
@Service
public class MeetingsAreaSubCatalogRequests {

    private static final Logger LOGGER = LoggerFactory.getLogger(MeetingsAreaSubCatalogRequests.class);

    private final CalendarService calendarService;
    private final ErrorHandlingStateManager internalStateManager;
    private final ExtensionResponseGenerator responseGenerator;

    @Inject
    public MeetingsAreaSubCatalogRequests(CalendarService calendarService,
                                         ErrorHandlingStateManager internalStateManager,
                                         ExtensionResponseGenerator responseGenerator) {
        this.calendarService = calendarService;
        this.internalStateManager = internalStateManager;
        this.responseGenerator = responseGenerator;
    }

    @NotNull
    private static Map<String, Object> addMetaDataToLinkedHashMap(Map<String, Object> map, String stateId) {
        Map<String, Object> metaData = new LinkedHashMap<>();
        metaData.put(CalendarResource.STATE_ID, stateId);
        metaData.putAll(map);
        return metaData;
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.CONFIRM_REENTER_ACCEPT_INVITATION,
            description = "Checks if user wants to re-enter event id and validates it",
            type = CatalogRequest.Type.QUERY_SYSTEM
    )
    public ExtensionResponse confirmReenterAcceptInvitation(@Field.Desc(
            name = "inputMap",
            type = "{ Reenter: Boolean, stateId: Text, Event Id: Text, Attendee Email: Email }",
            required = true) Map<String, Object> map) {

        Boolean reenter = (Boolean) map.get(REENTER);

        String stateId = (String) map.get(CalendarResource.STATE_ID);
        List<ValidationOrchestrator.ValidationResult> validationResults = internalStateManager.get(stateId);

        return getExtensionResponse(map, reenter, validationResults, stateId, "handleReenterAcceptInvitation");
    }

    private ExtensionResponse getExtensionResponse(Map<String, Object> map, Boolean reenter, List<ValidationOrchestrator.ValidationResult> validationResults, String stateId, String handleSubCatalogRequestName) {
        if (Boolean.FALSE.equals(reenter)) {
            return responseGenerator.generateFetchDenyResponse(
                    ExtensionResponse.Error.ExceptionType.INPUT_ERROR,
                    validationResults,
                    null,
                    Map.of());
        }

        return responseGenerator.generateFetchResponse(
                ExtensionResponse.Error.ExceptionType.INPUT_ERROR,
                validationResults,
                handleSubCatalogRequestName,
                addMetaDataToLinkedHashMap(map, stateId));
    }

    @SubCatalogRequest(
            name = "handleReenterAcceptInvitation",
            description = "Handle reenter accept invitation",
            type = CatalogRequest.Type.CHANGE_SYSTEM
    )

    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse handleReenterAcceptInvitation(
            @Field.Desc(name = "inputMap", type = "{ stateId: Text, Event Id: Text, Attendee Email: Email }") Map<String, Object> map) {

        String eventId = (String) map.get(CalendarResource.EVENT_ID);
        String attendeeEmail = (String) map.get(CalendarResource.ATTENDEE_EMAIL);

        try {

            EventDetails eventDetails = calendarService.acceptInvitation(eventId, attendeeEmail);
            LOGGER.info("Successfully accepted invitation for event id: {}", eventId);
            return new ExtensionResponseBuilder()
                    .success(Map.of("Event Details", eventDetails))
                    .build();
        } catch (Exception cause) {
            LOGGER.error("Failed to accept invitation for event id and attendee email: {} , {} ", eventId, attendeeEmail, cause);

            ExtensionResponse.Error error = new ErrorBuilder()
                    .exceptionType(ExtensionResponse.Error.ExceptionType.LOGIC_ERROR)
                    .errorMessage("Failed to accept invitation: Please enter valid inputs and try again with a new request")
                    .stackTrace("Cause")
                    .build();

            return new ExtensionResponseBuilder()
                    .failure(error)
                    .build();
        }
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.CONFIRM_REENTER_DECLINE_INVITATION,
            description = "Checks if user wants to re-enter event id and attendee email for decline invitation and validates it",
            type = CatalogRequest.Type.QUERY_SYSTEM
    )
    public ExtensionResponse confirmReenterDeclineInvitation(@Field.Desc(
            name = "inputMap",
            type = "{ Reenter: Boolean, stateId: Text, Event Id: Text, Attendee Email: Email }",
            required = true) Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest confirmReenterDeclineInvitation start: {}", map);

        Boolean reenter = (Boolean) map.get(REENTER);
        String stateId = (String) map.get(CalendarResource.STATE_ID);
        List<ValidationOrchestrator.ValidationResult> validationResults = internalStateManager.get(stateId);

        LOGGER.info("Reenter flag: {}, StateId: {}, Validation results count: {}",
                   reenter, stateId, validationResults != null ? validationResults.size() : 0);

        return getExtensionResponse(map, reenter, validationResults, stateId, SubCatalogConstants.HANDLE_REENTER_DECLINE_INVITATION);
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.HANDLE_REENTER_DECLINE_INVITATION,
            description = "Handle re-entered input for decline invitation",
            type = CatalogRequest.Type.CHANGE_SYSTEM
    )
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse handleReenterDeclineInvitation(
            @Field.Desc(name = "inputMap", type = "{ stateId: Text, Event Id: Text, Attendee Email: Email }") Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest handleReenterDeclineInvitation start: {}", map);

        String eventId = (String) map.get(CalendarResource.EVENT_ID);
        String attendeeEmail = (String) map.get(CalendarResource.ATTENDEE_EMAIL);

        LOGGER.info("Processing decline invitation for eventId: {}, attendeeEmail: {}", eventId, attendeeEmail);

        try {
            EventDetails eventDetails = calendarService.declineInvitation(eventId, attendeeEmail);
            LOGGER.info("Successfully declined invitation for event id: {}", eventId);
            return new ExtensionResponseBuilder()
                    .success(Map.of("Event Details", eventDetails))
                    .build();
        } catch (Exception cause) {
            LOGGER.error("Failed to decline invitation for event id and attendee email: {} , {} ", eventId, attendeeEmail, cause);

            ExtensionResponse.Error error = new ErrorBuilder()
                    .exceptionType(ExtensionResponse.Error.ExceptionType.LOGIC_ERROR)
                    .errorMessage("Failed to decline invitation: Please enter valid inputs and try again with a new request")
                    .stackTrace("Cause")
                    .build();

            return new ExtensionResponseBuilder()
                    .failure(error)
                    .build();
        }
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.CONFIRM_REENTER_ADD_ATTENDEES,
            description = "Checks if user wants to re-enter input for adding attendees and if yes, sends prompt to do so",
            type = CatalogRequest.Type.QUERY_SYSTEM
    )
    @SuppressWarnings("unchecked")
    public ExtensionResponse confirmReenterAddAttendees(@Field.Desc(
            name = "inputMap",
            type = "{ Reenter: Boolean, stateId: Text, Event Id: Text, Attendee Email: [ Email ] }",
            required = true) Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest confirmReenterAddAttendees start: {}", map);

        Boolean reenter = (Boolean) map.get(REENTER);
        String stateId = (String) map.get(CalendarResource.STATE_ID);
        List<ValidationOrchestrator.ValidationResult> validationResults = internalStateManager.get(stateId);

        LOGGER.info("Reenter flag: {}, StateId: {}, Validation results count: {} for confirmReenterAddAttendees",
                   reenter, stateId, validationResults != null ? validationResults.size() : 0);

        return getExtensionResponse(map, reenter, validationResults, stateId, SubCatalogConstants.HANDLE_REENTER_ADD_ATTENDEES);
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.HANDLE_REENTER_ADD_ATTENDEES,
            description = "Handle re-entered input for adding attendees",
            type = CatalogRequest.Type.CHANGE_SYSTEM
    )
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse handleReenterAddAttendees(
            @Field.Desc(name = "inputMap", type = "{ stateId: Text, Event Id: Text, Attendee Email: [ Email ] }") Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest handleReenterAddAttendees start: {}", map);

        String eventId = (String) map.get(CalendarResource.EVENT_ID);
        Object attendeeEmailObj = map.get(CalendarResource.ATTENDEE_EMAIL);

        try {
            // Convert attendee emails to List<String>
            List<String> attendeeEmails;
            if (attendeeEmailObj instanceof List<?>) {
                @SuppressWarnings("unchecked")
                List<String> emailList = (List<String>) attendeeEmailObj;
                attendeeEmails = emailList;
            } else if (attendeeEmailObj instanceof String) {
                // Handle single email as string
                attendeeEmails = List.of((String) attendeeEmailObj);
            } else {
                throw new IllegalArgumentException("Invalid attendee email format");
            }

            LOGGER.info("Processing add attendees for event ID: {} with {} attendees", eventId, attendeeEmails.size());

            EventDetails eventDetails = calendarService.addAttendees(eventId, attendeeEmails);
            LOGGER.info("Successfully added attendees to event ID: {}", eventId);

            return new ExtensionResponseBuilder()
                    .success(Map.of("Event Details", eventDetails))
                    .build();

        } catch (Exception cause) {
            LOGGER.error("Failed to add attendees for event ID: {} with attendee emails: {}", eventId, attendeeEmailObj, cause);

            ExtensionResponse.Error error = new ErrorBuilder()
                    .exceptionType(ExtensionResponse.Error.ExceptionType.LOGIC_ERROR)
                    .errorMessage("Failed to add attendees: Please enter valid inputs and try again with a new request")
                    .stackTrace(Arrays.toString(cause.getStackTrace()))
                    .build();

            return new ExtensionResponseBuilder()
                    .failure(error)
                    .build();
        }
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.CONFIRM_REENTER_REMOVE_ATTENDEE,
            description = "Checks if user wants to re-enter event id and attendee email for remove attendee and validates it",
            type = CatalogRequest.Type.QUERY_SYSTEM
    )
    @SuppressWarnings("unchecked")
    public ExtensionResponse confirmReenterRemoveAttendee(@Field.Desc(
            name = "inputMap",
            type = "{ Reenter: Boolean, stateId: Text, Event Id: Text, Attendee Email: Email }",
            required = true) Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest confirmReenterRemoveAttendee start: {}", map);

        Boolean reenter = (Boolean) map.get(REENTER);
        String stateId = (String) map.get(CalendarResource.STATE_ID);
        List<ValidationOrchestrator.ValidationResult> validationResults = internalStateManager.get(stateId);

        LOGGER.info("Reenter flag: {}, StateId: {}, Validation results count: {} for confirmReenterRemoveAttendee",
                   reenter, stateId, validationResults != null ? validationResults.size() : 0);

        return getExtensionResponse(map, reenter, validationResults, stateId, SubCatalogConstants.HANDLE_REENTER_REMOVE_ATTENDEE);
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.HANDLE_REENTER_REMOVE_ATTENDEE,
            description = "Handle re-entered input for removing attendee",
            type = CatalogRequest.Type.CHANGE_SYSTEM
    )
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse handleReenterRemoveAttendee(
            @Field.Desc(name = "inputMap", type = "{ stateId: Text, Event Id: Text, Attendee Email: Email }") Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest handleReenterRemoveAttendee start: {}", map);

        String eventId = (String) map.get(CalendarResource.EVENT_ID);
        String attendeeEmail = (String) map.get(CalendarResource.ATTENDEE_EMAIL);

        LOGGER.info("Processing remove attendee with eventId: {}, attendeeEmail: {}", eventId, attendeeEmail);

        try {
            EventDetails eventDetails = calendarService.removeAttendee(eventId, attendeeEmail);
            LOGGER.info("Successfully removed attendee {} from event ID: {}", attendeeEmail, eventId);

            return new ExtensionResponseBuilder()
                    .success(Map.of("Event Details", eventDetails))
                    .build();

        } catch (Exception cause) {
            LOGGER.error("Failed to remove attendee {} from event ID: {}", attendeeEmail, eventId, cause);

            ExtensionResponse.Error error = new ErrorBuilder()
                    .exceptionType(ExtensionResponse.Error.ExceptionType.LOGIC_ERROR)
                    .errorMessage("Failed to remove attendee: Please enter valid inputs and try again with a new request")
                    .stackTrace(Arrays.toString(cause.getStackTrace()))
                    .build();

            return new ExtensionResponseBuilder()
                    .failure(error)
                    .build();
        }
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.CONFIRM_REENTER_TENTATIVE_INVITATION,
            description = "Checks if user wants to re-enter event id and attendee email for tentative invitation and validates it",
            type = CatalogRequest.Type.QUERY_SYSTEM
    )
    @SuppressWarnings("unchecked")
    public ExtensionResponse confirmReenterTentativeInvitation(@Field.Desc(
            name = "inputMap",
            type = "{ Reenter: Boolean, stateId: Text, Event Id: Text, Attendee Email: Email }",
            required = true) Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest confirmReenterTentativeInvitation start: {}", map);

        Boolean reenter = (Boolean) map.get(REENTER);
        String stateId = (String) map.get(CalendarResource.STATE_ID);
        List<ValidationOrchestrator.ValidationResult> validationResults = internalStateManager.get(stateId);

        LOGGER.info("Reenter flag: {}, StateId: {}, Validation results count: {} for confirmReenterTentativeInvitation",
                   reenter, stateId, validationResults != null ? validationResults.size() : 0);

        return getExtensionResponse(map, reenter, validationResults, stateId, SubCatalogConstants.HANDLE_REENTER_TENTATIVE_INVITATION);
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.CONFIRM_REENTER_SCHEDULE_MEETING,
            description = "Checks if user wants to re-enter input for schedule meeting and if yes, sends prompt to do so",
            type = CatalogRequest.Type.QUERY_SYSTEM
    )
    @SuppressWarnings("unchecked")
    public ExtensionResponse confirmReenterScheduleMeeting(@Field.Desc(name = "inputMap",
            type = "{ Reenter: Boolean, stateId: Text, Title: Text, Location: Text, Date From: Date, Date To: Date, Details: RichText, Attendee Email: [ Email ] }", required = true) Map<String, Object> map) {
        LOGGER.info("SubCatalogRequest confirmReenterScheduleMeeting start: {}", map);

        Boolean reenter = (Boolean) map.get(REENTER);
        String stateId = (String) map.get(CalendarResource.STATE_ID);
        List<ValidationOrchestrator.ValidationResult> validationResults = internalStateManager.get(stateId);

        LOGGER.info("Retrieved validation results for stateId {}: {} results", stateId, validationResults.size());

        return getExtensionResponse(map, reenter, validationResults, stateId, SubCatalogConstants.HANDLE_REENTER_SCHEDULE_MEETING);
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.HANDLE_REENTER_TENTATIVE_INVITATION,
            description = "Handle re-entered input for tentative invitation",
            type = CatalogRequest.Type.CHANGE_SYSTEM
    )
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse handleReenterTentativeInvitation(
            @Field.Desc(name = "inputMap", type = "{ stateId: Text, Event Id: Text, Attendee Email: Email }") Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest handleReenterTentativeInvitation start: {}", map);

        // Extract parameters from the map
        String eventId = (String) map.get(CalendarResource.EVENT_ID);
        String attendeeEmail = (String) map.get(CalendarResource.ATTENDEE_EMAIL);

        LOGGER.info("Processing tentative invitation for Event ID: {}, Attendee Email: {}", eventId, attendeeEmail);

        try {
            // Call the actual implementation
            EventDetails eventDetails = calendarService.tentativeInvitation(eventId, attendeeEmail);

            LOGGER.info("Successfully processed tentative invitation for Event ID: {}", eventId);

            return new ExtensionResponseBuilder()
                    .success(Map.of("Event Details", eventDetails))
                    .build();

        } catch (Exception cause) {
            LOGGER.error("Failed to process tentative invitation for event ID: {} with attendee email: {}", eventId, attendeeEmail, cause);

            ExtensionResponse.Error error = new ErrorBuilder()
                    .exceptionType(ExtensionResponse.Error.ExceptionType.LOGIC_ERROR)
                    .errorMessage("Failed to process tentative invitation: Please enter valid inputs and try again with a new request")
                    .stackTrace(Arrays.toString(cause.getStackTrace()))
                    .build();

            return new ExtensionResponseBuilder()
                    .failure(error)
                    .build();
        }
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.HANDLE_REENTER_SCHEDULE_MEETING,
            description = "Handle re-entered input for schedule meeting",
            type = CatalogRequest.Type.CHANGE_SYSTEM
    )
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    @SuppressWarnings("unchecked")
    public ExtensionResponse handleReenterScheduleMeeting(
            @Field.Desc(name = "inputMap", type = "{ stateId: Text, Title: Text, Location: Text, Date From: Date, Date To: Date, Details: RichText, Attendee Email: [ Email ] }") Map<String, Object> map) {
        LOGGER.info("SubCatalogRequest handleReenterScheduleMeeting start: {}", map);

        // Extract parameters from the map
        String title = (String) map.get(CalendarResource.TITLE);
        String location = (String) map.get(CalendarResource.LOCATION);
        Long dateFrom = (Long) map.get(CalendarResource.DATE_FROM);
        Long dateTo = (Long) map.get(CalendarResource.DATE_TO);
        String details = (String) map.get(CalendarResource.DETAILS);
        List<String> attendeeEmail = (List<String>) map.get(CalendarResource.ATTENDEE_EMAIL);

        LOGGER.info("Extracted parameters: title={}, location={}, dateFrom={}, dateTo={}, details={}, attendeeEmails={}",
                title, location, dateFrom, dateTo, details, attendeeEmail);

        try {
            // Call the actual meeting scheduling implementation
            EventDetails eventDetails = calendarService.scheduleMeeting(title, location, dateFrom, dateTo, details, attendeeEmail);
            LOGGER.info("Successfully scheduled meeting with ID: {}", eventDetails.eventId);

            return new ExtensionResponseBuilder()
                    .success(Map.of("Event Details", eventDetails))
                    .build();

        } catch (Exception cause) {
            LOGGER.error("Failed to schedule meeting: title={}, location={}, dateFrom={}, dateTo={}, details={}, attendeeEmails={}",
                    title, location, dateFrom, dateTo, details, attendeeEmail, cause);

            ExtensionResponse.Error error = new ErrorBuilder()
                    .exceptionType(ExtensionResponse.Error.ExceptionType.LOGIC_ERROR)
                    .errorMessage("Failed to schedule meeting: Please enter valid inputs and try again with a new request")
                    .stackTrace(Arrays.toString(cause.getStackTrace()))
                    .build();

            return new ExtensionResponseBuilder()
                    .failure(error)
                    .build();
        }
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.CONFIRM_REENTER_GET_NEXT_UPCOMING_MEETINGS,
            description = "Checks if user wants to re-enter number of meetings and validates it",
            type = CatalogRequest.Type.QUERY_SYSTEM
    )
    @SuppressWarnings("unchecked")
    public ExtensionResponse confirmReenterGetNextUpcomingMeetings(@Field.Desc(
            name = "inputMap",
            type = "{ Reenter: Boolean, stateId: Text, Number of meeting: Number }",
            required = true) Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest confirmReenterGetNextUpcomingMeetings start: {}", map);

        Boolean reenter = (Boolean) map.get(REENTER);
        String stateId = (String) map.get(CalendarResource.STATE_ID);
        List<ValidationOrchestrator.ValidationResult> validationResults = internalStateManager.get(stateId);

        LOGGER.info("Reenter flag: {}, StateId: {}, Validation results count: {} for confirmReenterGetNextUpcomingMeetings",
                   reenter, stateId, validationResults != null ? validationResults.size() : 0);

        return getExtensionResponse(map, reenter, validationResults, stateId, SubCatalogConstants.HANDLE_REENTER_GET_NEXT_UPCOMING_MEETINGS);
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.HANDLE_REENTER_GET_NEXT_UPCOMING_MEETINGS,
            description = "Handle re-entered input for get next upcoming meetings",
            type = CatalogRequest.Type.CHANGE_SYSTEM
    )
    @Field.Desc(name = "Event Details", type = "[ Entity(Event Details) ]", required = false)
    public ExtensionResponse handleReenterGetNextUpcomingMeetings(
            @Field.Desc(name = "inputMap", type = "{ stateId: Text, Number of meeting: Number }") Map<String, Object> map) {
        LOGGER.info("SubCatalogRequest handleReenterGetNextUpcomingMeetings start: {}", map);

        // Extract parameters from the map
        Object numberOfMeetingObj = map.get(CalendarResource.NUMBER_OF_MEETINGS);
        Double numberOfMeeting;

        if (numberOfMeetingObj instanceof Double) {
            numberOfMeeting = (Double) numberOfMeetingObj;
        } else if (numberOfMeetingObj instanceof Integer) {
            numberOfMeeting = ((Integer) numberOfMeetingObj).doubleValue();
        } else if (numberOfMeetingObj instanceof String) {
            try {
                numberOfMeeting = Double.parseDouble((String) numberOfMeetingObj);
            } catch (NumberFormatException e) {
                LOGGER.error("Invalid number format for numberOfMeeting: {}", numberOfMeetingObj);
                return new ExtensionResponseBuilder()
                        .failure(new ErrorBuilder()
                                .exceptionType(ExtensionResponse.Error.ExceptionType.INPUT_ERROR)
                                .errorMessage("Invalid number format for number of meetings")
                                .build())
                        .build();
            }
        } else {
            LOGGER.error("Unexpected type for numberOfMeeting: {}", numberOfMeetingObj != null ? numberOfMeetingObj.getClass() : "null");
            return new ExtensionResponseBuilder()
                    .failure(new ErrorBuilder()
                            .exceptionType(ExtensionResponse.Error.ExceptionType.INPUT_ERROR)
                            .errorMessage("Invalid type for number of meetings")
                            .build())
                    .build();
        }

        LOGGER.info("Extracted parameter: numberOfMeeting={}", numberOfMeeting);

        try {
            // Call the actual get next upcoming meetings implementation
            List<EventDetails> eventDetailsList = calendarService.getNextUpcomingMeetings(numberOfMeeting);
            LOGGER.info("Successfully retrieved {} upcoming meetings", eventDetailsList.size());

            return new ExtensionResponseBuilder()
                    .success(Map.of("Event Details", eventDetailsList))
                    .build();

        } catch (Exception cause) {
            LOGGER.error("Failed to get next upcoming meetings: numberOfMeeting={}, error={}",
                    numberOfMeeting, cause.getMessage(), cause);

            ExtensionResponse.Error error = new ErrorBuilder()
                    .exceptionType(ExtensionResponse.Error.ExceptionType.LOGIC_ERROR)
                    .errorMessage("Failed to get next upcoming meetings: Please enter valid inputs and try again with a new request")
                    .stackTrace(Arrays.toString(cause.getStackTrace()))
                    .build();

            return new ExtensionResponseBuilder()
                    .failure(error)
                    .build();
        }
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.CONFIRM_REENTER_CANCEL_EVENT,
            description = "Checks if user wants to re-enter event id for cancel event and validates it",
            type = CatalogRequest.Type.QUERY_SYSTEM
    )
    @SuppressWarnings("unchecked")
    public ExtensionResponse confirmReenterCancelEvent(@Field.Desc(
            name = "inputMap",
            type = "{ Reenter: Boolean, stateId: Text, Event Id: Text }",
            required = true) Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest confirmReenterCancelEvent start: {}", map);

        Boolean reenter = (Boolean) map.get(REENTER);
        String stateId = (String) map.get(CalendarResource.STATE_ID);
        List<ValidationOrchestrator.ValidationResult> validationResults = internalStateManager.get(stateId);

        LOGGER.info("Reenter flag: {}, StateId: {}, Validation results count: {} for confirmReenterCancelEvent",
                   reenter, stateId, validationResults != null ? validationResults.size() : 0);

        return getExtensionResponse(map, reenter, validationResults, stateId, SubCatalogConstants.HANDLE_REENTER_CANCEL_EVENT);
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.HANDLE_REENTER_CANCEL_EVENT,
            description = "Handle re-entered input for cancel event",
            type = CatalogRequest.Type.CHANGE_SYSTEM
    )
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse handleReenterCancelEvent(@Field.Desc(
            name = "inputMap",
            type = "{ stateId: Text, Event Id: Text }",
            required = true) Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest handleReenterCancelEvent start: {}", map);

        // Extract parameters from the map
        String eventId = (String) map.get(CalendarResource.EVENT_ID);

        try {
            EventDetails eventDetails = calendarService.cancelEvent(eventId);
            LOGGER.info("Successfully canceled event with ID: {} in SubCatalogRequest", eventId);

            return new ExtensionResponseBuilder()
                    .success(Map.of("Event Details", eventDetails))
                    .build();
        } catch (Exception cause) {
            LOGGER.error("Error occurred in handleReenterCancelEvent: eventId={}, error={}", eventId, cause.getMessage(), cause);

            return new ExtensionResponseBuilder()
                    .failure(new ErrorBuilder()
                            .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                            .errorMessage("Failed to cancel event")
                            .stackTrace(Arrays.toString(cause.getStackTrace()))
                            .build())
                    .build();
        }
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.CONFIRM_REENTER_GET_EVENT,
            description = "Checks if user wants to re-enter event id for get event and validates it",
            type = CatalogRequest.Type.QUERY_SYSTEM
    )
    @SuppressWarnings("unchecked")
    public ExtensionResponse confirmReenterGetEvent(@Field.Desc(
            name = "inputMap",
            type = "{ Reenter: Boolean, stateId: Text, Event Id: Text }",
            required = true) Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest confirmReenterGetEvent start: {}", map);

        Boolean reenter = (Boolean) map.get(REENTER);
        String stateId = (String) map.get(CalendarResource.STATE_ID);
        List<ValidationOrchestrator.ValidationResult> validationResults = internalStateManager.get(stateId);

        LOGGER.info("Reenter flag: {}, StateId: {}, Validation results count: {} for confirmReenterGetEvent",
                   reenter, stateId, validationResults != null ? validationResults.size() : 0);

        return getExtensionResponse(map, reenter, validationResults, stateId, SubCatalogConstants.HANDLE_REENTER_GET_EVENT);
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.HANDLE_REENTER_GET_EVENT,
            description = "Handle re-entered input for get event",
            type = CatalogRequest.Type.QUERY_SYSTEM
    )
    @Field.Desc(name = "Event Details", type = "Entity(Event Details)", required = false)
    public ExtensionResponse handleReenterGetEvent(@Field.Desc(
            name = "inputMap",
            type = "{ stateId: Text, Event Id: Text }",
            required = true) Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest handleReenterGetEvent start: {}", map);

        // Extract parameters from the map
        String eventId = (String) map.get(CalendarResource.EVENT_ID);

        try {
            EventDetails eventDetails = calendarService.getEvent(eventId);
            LOGGER.info("Successfully retrieved event with ID: {} in SubCatalogRequest", eventId);

            return new ExtensionResponseBuilder()
                    .success(Map.of("Event Details", eventDetails))
                    .build();
        } catch (Exception cause) {
            LOGGER.error("Error occurred in handleReenterGetEvent: eventId={}, error={}", eventId, cause.getMessage(), cause);

            return new ExtensionResponseBuilder()
                    .failure(new ErrorBuilder()
                            .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                            .errorMessage("Failed to get event")
                            .stackTrace(Arrays.toString(cause.getStackTrace()))
                            .build())
                    .build();
        }
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.CONFIRM_REENTER_GET_EVENTS_FOR_USER,
            description = "Checks if user wants to re-enter parameters for get events for user and validates them",
            type = CatalogRequest.Type.QUERY_SYSTEM
    )
    @SuppressWarnings("unchecked")
    public ExtensionResponse confirmReenterGetEventsForUser(@Field.Desc(
            name = "inputMap",
            type = "{ Reenter: Boolean, stateId: Text, User Email: Email, From: Date, To: Date }",
            required = true) Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest confirmReenterGetEventsForUser start: {}", map);

        Boolean reenter = (Boolean) map.get(REENTER);
        String stateId = (String) map.get(CalendarResource.STATE_ID);
        List<ValidationOrchestrator.ValidationResult> validationResults = internalStateManager.get(stateId);

        LOGGER.info("Reenter flag: {}, StateId: {}, Validation results count: {} for confirmReenterGetEventsForUser",
                   reenter, stateId, validationResults != null ? validationResults.size() : 0);

        return getExtensionResponse(map, reenter, validationResults, stateId, SubCatalogConstants.HANDLE_REENTER_GET_EVENTS_FOR_USER);
    }

    @SubCatalogRequest(
            name = SubCatalogConstants.HANDLE_REENTER_GET_EVENTS_FOR_USER,
            description = "Handle re-entered input for get events for user",
            type = CatalogRequest.Type.QUERY_SYSTEM
    )
    @Field.Desc(name = "Events", type = "[ Entity(Event Details) ]", required = false)
    public ExtensionResponse handleReenterGetEventsForUser(@Field.Desc(
            name = "inputMap",
            type = "{ stateId: Text, User Email: Email, From: Date, To: Date }",
            required = true) Map<String, Object> map) {

        LOGGER.info("SubCatalogRequest handleReenterGetEventsForUser start: {}", map);

        // Extract parameters from the map
        String userEmail = (String) map.get(CalendarResource.USER_EMAIL);
        Object fromObj = map.get(CalendarResource.FROM);
        Object toObj = map.get(CalendarResource.TO);

        Long fromDate = null;
        Long toDate = null;

        // Handle different types for date parameters
        if (fromObj instanceof Number) {
            fromDate = ((Number) fromObj).longValue();
        } else if (fromObj instanceof String) {
            try {
                fromDate = Long.parseLong((String) fromObj);
            } catch (NumberFormatException e) {
                LOGGER.error("Invalid from date format: {}", fromObj);
                return new ExtensionResponseBuilder()
                        .failure(new ErrorBuilder()
                                .exceptionType(ExtensionResponse.Error.ExceptionType.INPUT_ERROR)
                                .errorMessage("Invalid from date format")
                                .build())
                        .build();
            }
        }

        if (toObj instanceof Number) {
            toDate = ((Number) toObj).longValue();
        } else if (toObj instanceof String) {
            try {
                toDate = Long.parseLong((String) toObj);
            } catch (NumberFormatException e) {
                LOGGER.error("Invalid to date format: {}", toObj);
                return new ExtensionResponseBuilder()
                        .failure(new ErrorBuilder()
                                .exceptionType(ExtensionResponse.Error.ExceptionType.INPUT_ERROR)
                                .errorMessage("Invalid to date format")
                                .build())
                        .build();
            }
        }

        LOGGER.info("Extracted parameters: userEmail={}, fromDate={}, toDate={}", userEmail, fromDate, toDate);

        try {
            // Call the actual get events for user implementation
            List<EventDetails> eventDetailsList = calendarService.getEventsForUser(userEmail, fromDate, toDate);
            LOGGER.info("Successfully retrieved {} events for user {}", eventDetailsList.size(), userEmail);

            return new ExtensionResponseBuilder()
                    .success(Map.of("Events", eventDetailsList))
                    .build();

        } catch (Exception cause) {
            LOGGER.error("Failed to get events for user: userEmail={}, fromDate={}, toDate={}, error={}",
                    userEmail, fromDate, toDate, cause.getMessage(), cause);

            ExtensionResponse.Error error = new ErrorBuilder()
                    .exceptionType(ExtensionResponse.Error.ExceptionType.LOGIC_ERROR)
                    .errorMessage("Failed to get events for user: Please enter valid inputs and try again with a new request")
                    .stackTrace(Arrays.toString(cause.getStackTrace()))
                    .build();

            return new ExtensionResponseBuilder()
                    .failure(error)
                    .build();
        }
    }
}
