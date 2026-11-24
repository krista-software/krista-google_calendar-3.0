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
 * Helper class for handling Cancel Event operations with validation.
 * 
 * <p>This helper class encapsulates all the business logic for canceling calendar events,
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
 *   <li>Validate input parameters (Event ID)</li>
 *   <li>Execute cancel event operation via CalendarService</li>
 *   <li>Handle validation failures with SubCatalogRequest flow</li>
 *   <li>Build appropriate ExtensionResponse for success/failure cases</li>
 *   <li>Record telemetry metrics for monitoring</li>
 * </ul>
 * 
 * <h2>Important Notes:</h2>
 * <ul>
 *   <li><b>Destructive Operation:</b> Canceling an event is irreversible</li>
 *   <li><b>Notification:</b> All attendees receive cancellation notifications</li>
 *   <li><b>Calendar Impact:</b> Event is removed from all attendees' calendars</li>
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
public class CancelEventHelper {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(CancelEventHelper.class);
    
    private final CalendarService calendarService;
    private final ValidationOrchestrator validationOrchestrator;
    private final SubCatalogRequestImpl subCatalogRequestImpl;
    private final TelemetryMetrics telemetryMetrics;

    /**
     * Constructs a new CancelEventHelper with required dependencies.
     * 
     * @param calendarService the calendar service for performing operations, must not be null
     * @param validationOrchestrator the validation orchestrator for input validation, must not be null
     * @param subCatalogRequestImpl the subcatalog request handler for validation failures, must not be null
     * @param telemetryMetrics the telemetry metrics for recording operations, must not be null
     */
    @Inject
    public CancelEventHelper(CalendarService calendarService,
                            ValidationOrchestrator validationOrchestrator,
                            SubCatalogRequestImpl subCatalogRequestImpl,
                            TelemetryMetrics telemetryMetrics) {
        this.calendarService = calendarService;
        this.validationOrchestrator = validationOrchestrator;
        this.subCatalogRequestImpl = subCatalogRequestImpl;
        this.telemetryMetrics = telemetryMetrics;
    }

    /**
     * Cancels a calendar event with validation and error handling.
     * 
     * <p>This method performs the following steps:</p>
     * <ol>
     *   <li>Validates input parameters (Event ID)</li>
     *   <li>If validation passes, executes the cancel event operation</li>
     *   <li>If validation fails, initiates SubCatalogRequest flow for user correction</li>
     *   <li>Records telemetry metrics for monitoring</li>
     *   <li>Builds and returns appropriate ExtensionResponse</li>
     * </ol>
     * 
     * <h3>Validation Rules:</h3>
     * <ul>
     *   <li>Event ID must not be null or empty</li>
     *   <li>Event ID must be a valid Google Calendar event identifier</li>
     * </ul>
     * 
     * <h3>Success Response:</h3>
     * Returns ExtensionResponse with Event Details containing the canceled event information.
     * 
     * <h3>Validation Failure Response:</h3>
     * Returns ExtensionResponse with SubCatalogRequest for user to correct invalid inputs.
     * 
     * <h3>Error Response:</h3>
     * Returns ExtensionResponse with error message if operation fails.
     * 
     * <h3>Important Warnings:</h3>
     * <ul>
     *   <li><b>Irreversible:</b> This operation cannot be undone</li>
     *   <li><b>Notifications:</b> All attendees will be notified of the cancellation</li>
     *   <li><b>Calendar Removal:</b> Event will be removed from all calendars</li>
     * </ul>
     * 
     * @param eventId the unique identifier of the event to cancel, must not be null or empty
     * @return ExtensionResponse containing operation result (success, validation failure, or error)
     */
    public ExtensionResponse cancelEventWithValidation(String eventId) {
        LOGGER.info("cancelEventWithValidation called: EventId={}", eventId);
        telemetryMetrics.incrementCounter(TelemetryConstants.CANCEL_EVENT_REQUEST_ATTEMPT, 1L);

        try {
            // Build validation parameters
            Map<Validator.ValidationResource, String> validationParams = new HashMap<>();
            validationParams.put(Validator.ValidationResource.EVENT_ID, eventId);

            // Validate input parameters
            List<ValidationOrchestrator.ValidationResult> validationResults =
                    validationOrchestrator.validate(validationParams);

            if (validationResults.isEmpty()) {
                // Validation passed - proceed with operation
                LOGGER.info("Validation passed, proceeding with canceling event");
                return executeCancelEvent(eventId);
            } else {
                // Validation failed - initiate SubCatalogRequest flow
                LOGGER.info("Validation failed with {} errors, initiating SubCatalogRequest flow", 
                           validationResults.size());
                telemetryMetrics.incrementCounter(TelemetryConstants.CANCEL_EVENT_RETRY_ATTEMPT, 1L);
                return handleValidationFailure(validationResults, eventId);
            }

        } catch (Exception cause) {
            LOGGER.error("Error occurred while canceling event: eventId={}, error={}",
                        eventId, cause.getMessage(), cause);
            telemetryMetrics.incrementCounter(TelemetryConstants.CANCEL_EVENT_REQUEST_FAILURE, 1L);
            return buildErrorResponse(cause);
        }
    }

    /**
     * Executes the cancel event operation after validation passes.
     * 
     * <p>This private method calls the CalendarService to perform the actual operation
     * and builds a success response with the event details.</p>
     * 
     * <p><b>Warning:</b> This is a destructive operation that cannot be undone. The event
     * will be permanently removed from all attendees' calendars and cancellation notifications
     * will be sent to all participants.</p>
     * 
     * @param eventId the event identifier
     * @return ExtensionResponse with success result and event details
     */
    private ExtensionResponse executeCancelEvent(String eventId) {
        LOGGER.debug("Executing cancel event operation");
        
        // Call service layer to perform the operation
        EventDetails eventDetails = calendarService.cancelEvent(eventId);
        
        // Record success telemetry
        telemetryMetrics.incrementCounter(TelemetryConstants.CANCEL_EVENT_REQUEST_SUCCESS, 1L);
        
        LOGGER.info("Successfully canceled event with ID: {}", eventId);
        
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
     * @return ExtensionResponse with SubCatalogRequest for user correction
     */
    private ExtensionResponse handleValidationFailure(
            List<ValidationOrchestrator.ValidationResult> validationResults,
            String eventId) {
        
        LOGGER.debug("Handling validation failure with SubCatalogRequest");
        
        // Delegate to SubCatalogRequestImpl to handle validation failure
        ExtensionResponse response = subCatalogRequestImpl.handleCancelEvent(
                validationResults, eventId);
        
        // Record success telemetry (SubCatalogRequest was successfully created)
        telemetryMetrics.incrementCounter(TelemetryConstants.CANCEL_EVENT_REQUEST_SUCCESS, 1L);
        
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
                        .errorMessage("Error occurred in cancelEvent: " + cause.getMessage())
                        .stackTrace(cause.getStackTrace().toString())
                        .build())
                .build();
    }
}

