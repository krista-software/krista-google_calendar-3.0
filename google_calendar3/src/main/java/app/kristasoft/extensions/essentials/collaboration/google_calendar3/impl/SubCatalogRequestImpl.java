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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl;

import app.krista.extension.executor.ExtensionResponse;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.errorhandlers.ErrorHandlingStateManager;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.errorhandlers.ExtensionResponseGenerator;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.CalendarResource;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.SubCatalogConstants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.validators.ValidationOrchestrator;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SubCatalogRequestImpl {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubCatalogRequestImpl.class);

    private final ErrorHandlingStateManager internalStateManager;
    private final ExtensionResponseGenerator responseGenerator;

    @Inject
    public SubCatalogRequestImpl(ErrorHandlingStateManager internalStateManager, ExtensionResponseGenerator responseGenerator) {
        this.internalStateManager = internalStateManager;
        this.responseGenerator = responseGenerator;
    }

    public ExtensionResponse handleAcceptInvitation(List<ValidationOrchestrator.ValidationResult> validationResults, String eventId, String attendeeEmail) {
        LOGGER.info("handleAcceptInvitation called: validationResults count={}, eventId={}, attendeeEmail={}",
                validationResults.size(), eventId, attendeeEmail);

        // Store validation results and call confirmation SubCatalogRequest
        String stateId = UUID.randomUUID().toString();
        internalStateManager.put(stateId, Constants.GSON.toJson(validationResults));

        LOGGER.debug("Stored validation results with stateId: {}", stateId);
        return responseGenerator.generateConfirmationResponse(
                ExtensionResponse.Error.ExceptionType.INPUT_ERROR,
                validationResults,
                SubCatalogConstants.CONFIRM_REENTER_ACCEPT_INVITATION,
                Map.of(CalendarResource.STATE_ID, stateId,
                        CalendarResource.EVENT_ID, eventId,
                        CalendarResource.ATTENDEE_EMAIL, attendeeEmail));
    }

    public ExtensionResponse handleAddAttendees(List<ValidationOrchestrator.ValidationResult> validationResults, String eventId, List<String> attendeeEmails) {
        LOGGER.info("handleAddAttendees called: validationResults count={}, eventId={}, attendeeEmails={}",
                validationResults.size(), eventId, attendeeEmails);

        // Store validation results and call confirmation SubCatalogRequest
        String stateId = UUID.randomUUID().toString();
        internalStateManager.put(stateId, Constants.GSON.toJson(validationResults));

        LOGGER.debug("Stored validation results with stateId: {}", stateId);
        return responseGenerator.generateConfirmationResponse(
                ExtensionResponse.Error.ExceptionType.INPUT_ERROR,
                validationResults,
                SubCatalogConstants.CONFIRM_REENTER_ADD_ATTENDEES,
                Map.of(CalendarResource.STATE_ID, stateId,
                        CalendarResource.EVENT_ID, eventId,
                        CalendarResource.ATTENDEE_EMAIL, attendeeEmails));
    }

    public ExtensionResponse handleDeclineInvitation(List<ValidationOrchestrator.ValidationResult> validationResults, String eventId, String attendeeEmail) {
        LOGGER.info("handleDeclineInvitation called: validationResults count={}, eventId={}, attendeeEmail={}",
                validationResults.size(), eventId, attendeeEmail);

        // Store validation results and call confirmation SubCatalogRequest
        String stateId = UUID.randomUUID().toString();
        internalStateManager.put(stateId, Constants.GSON.toJson(validationResults));

        LOGGER.debug("Stored validation results with stateId: {}", stateId);
        return responseGenerator.generateConfirmationResponse(
                ExtensionResponse.Error.ExceptionType.INPUT_ERROR,
                validationResults,
                SubCatalogConstants.CONFIRM_REENTER_DECLINE_INVITATION,
                Map.of(CalendarResource.STATE_ID, stateId,
                        CalendarResource.EVENT_ID, eventId,
                        CalendarResource.ATTENDEE_EMAIL, attendeeEmail));
    }

    public ExtensionResponse handleRemoveAttendee(List<ValidationOrchestrator.ValidationResult> validationResults, String eventId, String attendeeEmail) {
        LOGGER.info("handleRemoveAttendee called: validationResults count={}, eventId={}, attendeeEmail={}",
                validationResults.size(), eventId, attendeeEmail);

        // Store validation results and call confirmation SubCatalogRequest
        String stateId = UUID.randomUUID().toString();
        internalStateManager.put(stateId, Constants.GSON.toJson(validationResults));

        LOGGER.debug("Stored validation results with stateId: {}", stateId);
        return responseGenerator.generateConfirmationResponse(
                ExtensionResponse.Error.ExceptionType.INPUT_ERROR,
                validationResults,
                SubCatalogConstants.CONFIRM_REENTER_REMOVE_ATTENDEE,
                Map.of(CalendarResource.STATE_ID, stateId,
                        CalendarResource.EVENT_ID, eventId,
                        CalendarResource.ATTENDEE_EMAIL, attendeeEmail));
    }

    public ExtensionResponse handleTentativeInvitation(List<ValidationOrchestrator.ValidationResult> validationResults, String eventId, String attendeeEmail) {
        LOGGER.info("handleTentativeInvitation called: validationResults count={}, eventId={}, attendeeEmail={}",
                validationResults.size(), eventId, attendeeEmail);

        // Store validation results and call confirmation SubCatalogRequest
        String stateId = UUID.randomUUID().toString();
        internalStateManager.put(stateId, Constants.GSON.toJson(validationResults));

        LOGGER.debug("Stored validation results with stateId: {}", stateId);
        return responseGenerator.generateConfirmationResponse(
                ExtensionResponse.Error.ExceptionType.INPUT_ERROR,
                validationResults,
                SubCatalogConstants.CONFIRM_REENTER_TENTATIVE_INVITATION,
                Map.of(CalendarResource.STATE_ID, stateId,
                        CalendarResource.EVENT_ID, eventId,
                        CalendarResource.ATTENDEE_EMAIL, attendeeEmail));
    }

    public ExtensionResponse handleScheduleMeeting(List<ValidationOrchestrator.ValidationResult> validationResults,
                                                   String title, String location, Long dateFrom, Long dateTo,
                                                   String details, List<String> attendeeEmail) {
        LOGGER.info("handleScheduleMeeting called: validationResults count={}, title={}, location={}, dateFrom={}, dateTo={}, details={}, attendeeEmails={}",
                validationResults.size(), title, location, dateFrom, dateTo, details, attendeeEmail);

        // Store validation results and call confirmation SubCatalogRequest
        String stateId = UUID.randomUUID().toString();
        internalStateManager.put(stateId, Constants.GSON.toJson(validationResults));

        LOGGER.debug("Stored validation results with stateId: {}", stateId);
        return responseGenerator.generateConfirmationResponse(
                ExtensionResponse.Error.ExceptionType.INPUT_ERROR,
                validationResults,
                SubCatalogConstants.CONFIRM_REENTER_SCHEDULE_MEETING,
                Map.of(CalendarResource.STATE_ID, stateId,
                        CalendarResource.TITLE, title,
                        CalendarResource.LOCATION, location,
                        CalendarResource.DATE_FROM, dateFrom,
                        CalendarResource.DATE_TO, dateTo,
                        CalendarResource.DETAILS, details,
                        CalendarResource.ATTENDEE_EMAIL, attendeeEmail));
    }

    public ExtensionResponse handleGetNextUpcomingMeetings(List<ValidationOrchestrator.ValidationResult> validationResults, Double numberOfMeeting) {
        LOGGER.info("handleGetNextUpcomingMeetings called: validationResults count={}, numberOfMeeting={}",
                validationResults.size(), numberOfMeeting);

        // Store validation results and call confirmation SubCatalogRequest
        String stateId = UUID.randomUUID().toString();
        internalStateManager.put(stateId, Constants.GSON.toJson(validationResults));

        LOGGER.debug("Stored validation results with stateId: {}", stateId);
        return responseGenerator.generateConfirmationResponse(
                ExtensionResponse.Error.ExceptionType.INPUT_ERROR,
                validationResults,
                SubCatalogConstants.CONFIRM_REENTER_GET_NEXT_UPCOMING_MEETINGS,
                Map.of(CalendarResource.STATE_ID, stateId,
                        CalendarResource.NUMBER_OF_MEETINGS, numberOfMeeting));
    }

    public ExtensionResponse handleCancelEvent(List<ValidationOrchestrator.ValidationResult> validationResults, String eventId) {
        LOGGER.info("handleCancelEvent called: validationResults count={}, eventId={}",
                validationResults.size(), eventId);

        // Store validation results and call confirmation SubCatalogRequest
        String stateId = UUID.randomUUID().toString();
        internalStateManager.put(stateId, Constants.GSON.toJson(validationResults));

        LOGGER.debug("Stored validation results with stateId: {}", stateId);
        return responseGenerator.generateConfirmationResponse(
                ExtensionResponse.Error.ExceptionType.INPUT_ERROR,
                validationResults,
                SubCatalogConstants.CONFIRM_REENTER_CANCEL_EVENT,
                Map.of(CalendarResource.STATE_ID, stateId,
                        CalendarResource.EVENT_ID, eventId));
    }

    public ExtensionResponse handleGetEvent(List<ValidationOrchestrator.ValidationResult> validationResults, String eventId) {
        LOGGER.info("handleGetEvent called: validationResults count={}, eventId={}",
                validationResults.size(), eventId);

        // Store validation results and call confirmation SubCatalogRequest
        String stateId = UUID.randomUUID().toString();
        internalStateManager.put(stateId, Constants.GSON.toJson(validationResults));

        LOGGER.debug("Stored validation results with stateId: {}", stateId);
        return responseGenerator.generateConfirmationResponse(
                ExtensionResponse.Error.ExceptionType.INPUT_ERROR,
                validationResults,
                SubCatalogConstants.CONFIRM_REENTER_GET_EVENT,
                Map.of(CalendarResource.STATE_ID, stateId,
                        CalendarResource.EVENT_ID, eventId));
    }

    public ExtensionResponse handleGetEventsForUser(List<ValidationOrchestrator.ValidationResult> validationResults,
                                                   String userEmail, Long fromDate, Long toDate) {
        LOGGER.info("handleGetEventsForUser called: validationResults count={}, userEmail={}, fromDate={}, toDate={}",
                validationResults.size(), userEmail, fromDate, toDate);

        // Store validation results and call confirmation SubCatalogRequest
        String stateId = UUID.randomUUID().toString();
        internalStateManager.put(stateId, Constants.GSON.toJson(validationResults));

        LOGGER.debug("Stored validation results with stateId: {}", stateId);
        return responseGenerator.generateConfirmationResponse(
                ExtensionResponse.Error.ExceptionType.INPUT_ERROR,
                validationResults,
                SubCatalogConstants.CONFIRM_REENTER_GET_EVENTS_FOR_USER,
                Map.of(CalendarResource.STATE_ID, stateId,
                        CalendarResource.USER_EMAIL, userEmail != null ? userEmail : "",
                        CalendarResource.FROM, fromDate != null ? fromDate : 0L,
                        CalendarResource.TO, toDate != null ? toDate : 0L));
    }
}
