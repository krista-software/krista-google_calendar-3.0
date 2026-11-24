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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper;

import app.krista.extension.executor.ErrorBuilder;
import app.krista.extension.executor.ExtensionResponse;
import app.krista.extension.executor.ExtensionResponseBuilder;
import app.krista.ksdk.telemetry.TelemetryMetrics;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.entity.EventDetails;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.validators.ValidationOrchestrator;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.validators.Validator;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.SubCatalogRequestImpl;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.TelemetryConstants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.CalendarService;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper class for handling Decline Invitation operations with validation.
 * 
 * <p>This helper class encapsulates all the business logic for declining calendar event invitations,
 * including input validation, error handling, and subcatalog request flows. It separates
 * the business logic from the presentation layer (MeetingsArea), making the code more
 * maintainable and testable.</p>
 * 
 * <h2>Design Pattern: Helper/Utility Pattern with Dependency Injection</h2>
 * <ul>
 *   <li><b>Separation of Concerns:</b> Business logic separated from presentation layer</li>
 *   <li><b>Validation Integration:</b> Uses ValidationOrchestrator for input validation</li>
 *   <li><b>Error Handling:</b> Centralized error handling and response building</li>
 *   <li><b>Telemetry:</b> Integrated telemetry for monitoring and debugging</li>
 * </ul>
 * 
 * <h2>Responsibilities:</h2>
 * <ul>
 *   <li>Validate input parameters (Event ID, Attendee Email)</li>
 *   <li>Execute decline invitation operation via CalendarService</li>
 *   <li>Handle validation failures with SubCatalogRequest flow</li>
 *   <li>Build appropriate ExtensionResponse for success/failure cases</li>
 *   <li>Record telemetry metrics for monitoring</li>
 * </ul>
 * 
 * @author Krista Software
 * @version 3.0.0
 * @since 3.0.0
 * @see CalendarService
 * @see ValidationOrchestrator
 * @see SubCatalogRequestImpl
 */
@Service
public class DeclineInvitationHelper {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(DeclineInvitationHelper.class);
    
    private final CalendarService calendarService;
    private final ValidationOrchestrator validationOrchestrator;
    private final SubCatalogRequestImpl subCatalogRequestImpl;
    private final TelemetryMetrics telemetryMetrics;

    /**
     * Constructs a new DeclineInvitationHelper with required dependencies.
     * 
     * @param calendarService the calendar service for performing operations, must not be null
     * @param validationOrchestrator the validation orchestrator for input validation, must not be null
     * @param subCatalogRequestImpl the subcatalog request handler for validation failures, must not be null
     * @param telemetryMetrics the telemetry metrics for recording operations, must not be null
     */
    @Inject
    public DeclineInvitationHelper(CalendarService calendarService,
                                  ValidationOrchestrator validationOrchestrator,
                                  SubCatalogRequestImpl subCatalogRequestImpl,
                                  TelemetryMetrics telemetryMetrics) {
        this.calendarService = calendarService;
        this.validationOrchestrator = validationOrchestrator;
        this.subCatalogRequestImpl = subCatalogRequestImpl;
        this.telemetryMetrics = telemetryMetrics;
    }

    /**
     * Declines a calendar event invitation with validation and error handling.
     * 
     * <p>This method performs the following steps:</p>
     * <ol>
     *   <li>Validates input parameters (Event ID and Attendee Email)</li>
     *   <li>If validation passes, executes the decline invitation operation</li>
     *   <li>If validation fails, initiates SubCatalogRequest flow for user correction</li>
     *   <li>Records telemetry metrics for monitoring</li>
     *   <li>Builds and returns appropriate ExtensionResponse</li>
     * </ol>
     * 
     * <h3>Validation Rules:</h3>
     * <ul>
     *   <li>Event ID must not be null or empty</li>
     *   <li>Attendee Email must not be null or empty</li>
     *   <li>Attendee Email must be a valid email format</li>
     * </ul>
     * 
     * <h3>Success Response:</h3>
     * Returns ExtensionResponse with Event Details containing updated event information
     * with the attendee's response status set to "declined".
     * 
     * <h3>Validation Failure Response:</h3>
     * Returns ExtensionResponse with SubCatalogRequest for user to correct invalid inputs.
     * 
     * <h3>Error Response:</h3>
     * Returns ExtensionResponse with error message if operation fails.
     * 
     * <h3>Business Rules:</h3>
     * <ul>
     *   <li>Attendee's response status is updated to "declined"</li>
     *   <li>Event organizer is notified of the decline</li>
     *   <li>Event remains on the attendee's calendar (marked as declined)</li>
     *   <li>Attendee can change their response later if needed</li>
     * </ul>
     * 
     * @param eventId the unique identifier of the event, must not be null or empty
     * @param attendeeEmail the email address of the attendee declining the invitation, must not be null or empty
     * @return ExtensionResponse containing operation result (success, validation failure, or error)
     */
    public ExtensionResponse declineInvitationWithValidation(String eventId, String attendeeEmail) {
        LOGGER.info("declineInvitationWithValidation called: EventId={}, AttendeeEmail={}", eventId, attendeeEmail);
        telemetryMetrics.incrementCounter(TelemetryConstants.DECLINE_INVITATION_REQUEST_COUNT, 1L);

        try {
            // Build validation parameters
            Map<Validator.ValidationResource, String> validationParams = new HashMap<>();
            validationParams.put(Validator.ValidationResource.EVENT_ID, eventId);
            validationParams.put(Validator.ValidationResource.ATTENDEE_EMAIL, attendeeEmail);

            // Validate input parameters
            List<ValidationOrchestrator.ValidationResult> validationResults =
                    validationOrchestrator.validate(validationParams);

            if (validationResults.isEmpty()) {
                // Validation passed - proceed with operation
                LOGGER.info("Validation passed, proceeding with declining invitation");
                return executeDeclineInvitation(eventId, attendeeEmail);
            } else {
                // Validation failed - initiate SubCatalogRequest flow
                LOGGER.info("Validation failed with {} errors, initiating SubCatalogRequest flow", 
                           validationResults.size());
                telemetryMetrics.incrementCounter(TelemetryConstants.DECLINE_INVITATION_RETRY_ATTEMPT, 1L);
                return handleValidationFailure(validationResults, eventId, attendeeEmail);
            }

        } catch (Exception cause) {
            LOGGER.error("Error occurred while declining invitation: eventId={}, attendeeEmail={}, error={}",
                        eventId, attendeeEmail, cause.getMessage(), cause);
            telemetryMetrics.incrementCounter(TelemetryConstants.DECLINE_INVITATION_REQUEST_FAILURE, 1L);
            return buildErrorResponse(cause);
        }
    }

    /**
     * Executes the decline invitation operation after validation passes.
     * 
     * <p>This private method calls the CalendarService to perform the actual operation
     * and builds a success response with the event details.</p>
     * 
     * @param eventId the event identifier
     * @param attendeeEmail the attendee email address
     * @return ExtensionResponse with success result and event details
     */
    private ExtensionResponse executeDeclineInvitation(String eventId, String attendeeEmail) {
        LOGGER.debug("Executing decline invitation operation");
        
        // Call service layer to perform the operation
        EventDetails eventDetails = calendarService.declineInvitation(eventId, attendeeEmail);
        
        // Record success telemetry
        telemetryMetrics.incrementCounter(TelemetryConstants.DECLINE_INVITATION_REQUEST_SUCCESS, 1L);
        
        LOGGER.info("Successfully declined invitation for event ID: {} by attendee: {}", eventId, attendeeEmail);
        
        // Build and return success response
        return new ExtensionResponseBuilder()
                .success(Map.of("Event Details", eventDetails))
                .build();
    }

    /**
     * Handles validation failure by initiating SubCatalogRequest flow.
     * 
     * <p>This private method delegates to SubCatalogRequestImpl to handle the validation
     * failure, which will prompt the user to correct invalid inputs.</p>
     * 
     * @param validationResults the list of validation errors
     * @param eventId the event identifier
     * @param attendeeEmail the attendee email address
     * @return ExtensionResponse with SubCatalogRequest for user correction
     */
    private ExtensionResponse handleValidationFailure(
            List<ValidationOrchestrator.ValidationResult> validationResults,
            String eventId,
            String attendeeEmail) {
        
        LOGGER.debug("Handling validation failure with SubCatalogRequest");
        
        // Delegate to SubCatalogRequestImpl to handle validation failure
        ExtensionResponse response = subCatalogRequestImpl.handleDeclineInvitation(
                validationResults, eventId, attendeeEmail);
        
        // Record success telemetry (SubCatalogRequest was successfully created)
        telemetryMetrics.incrementCounter(TelemetryConstants.DECLINE_INVITATION_REQUEST_SUCCESS, 1L);
        
        return response;
    }

    /**
     * Builds an error response for exception cases.
     *
     * <p>This private method creates a standardized error response with the exception
     * message and stack trace for debugging purposes.</p>
     *
     * @param cause the exception that caused the error
     * @return ExtensionResponse with error details
     */
    private ExtensionResponse buildErrorResponse(Exception cause) {
        LOGGER.debug("Building error response for exception: {}", cause.getClass().getSimpleName());

        return new ExtensionResponseBuilder()
                .failure(new ErrorBuilder()
                        .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                        .errorMessage("Error occurred in decline invitation: " + cause.getMessage())
                        .stackTrace(cause.getMessage())
                        .build())
                .build();
    }
}

