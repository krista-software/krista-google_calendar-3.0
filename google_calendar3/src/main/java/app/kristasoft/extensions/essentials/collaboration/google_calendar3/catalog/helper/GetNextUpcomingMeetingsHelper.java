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
 * Helper class for handling Get Next Upcoming Meetings operations with validation.
 * 
 * <p>This helper class encapsulates all the business logic for retrieving upcoming calendar events,
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
 *   <li>Validate input parameters (Number of Meetings)</li>
 *   <li>Execute get next upcoming meetings operation via CalendarService</li>
 *   <li>Handle validation failures with SubCatalogRequest flow</li>
 *   <li>Build appropriate ExtensionResponse for success/failure cases</li>
 *   <li>Record telemetry metrics for monitoring</li>
 *   <li>Track event count for analytics</li>
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
public class GetNextUpcomingMeetingsHelper {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(GetNextUpcomingMeetingsHelper.class);
    
    private final CalendarService calendarService;
    private final ValidationOrchestrator validationOrchestrator;
    private final SubCatalogRequestImpl subCatalogRequestImpl;
    private final TelemetryMetrics telemetryMetrics;

    /**
     * Constructs a new GetNextUpcomingMeetingsHelper with required dependencies.
     * 
     * @param calendarService the calendar service for performing operations, must not be null
     * @param validationOrchestrator the validation orchestrator for input validation, must not be null
     * @param subCatalogRequestImpl the subcatalog request handler for validation failures, must not be null
     * @param telemetryMetrics the telemetry metrics for recording operations, must not be null
     */
    @Inject
    public GetNextUpcomingMeetingsHelper(CalendarService calendarService,
                                        ValidationOrchestrator validationOrchestrator,
                                        SubCatalogRequestImpl subCatalogRequestImpl,
                                        TelemetryMetrics telemetryMetrics) {
        this.calendarService = calendarService;
        this.validationOrchestrator = validationOrchestrator;
        this.subCatalogRequestImpl = subCatalogRequestImpl;
        this.telemetryMetrics = telemetryMetrics;
    }

    /**
     * Retrieves next upcoming meetings with validation and error handling.
     * 
     * <p>This method performs the following steps:</p>
     * <ol>
     *   <li>Validates input parameters (Number of Meetings)</li>
     *   <li>If validation passes, executes the get next upcoming meetings operation</li>
     *   <li>If validation fails, initiates SubCatalogRequest flow for user correction</li>
     *   <li>Records telemetry metrics for monitoring</li>
     *   <li>Tracks event count for analytics</li>
     *   <li>Builds and returns appropriate ExtensionResponse</li>
     * </ol>
     * 
     * <h3>Validation Rules:</h3>
     * <ul>
     *   <li>Number of Meetings must not be null</li>
     *   <li>Number of Meetings must be a positive number</li>
     *   <li>Number of Meetings must be greater than 0</li>
     *   <li>Number of Meetings should be reasonable (typically 1-100)</li>
     * </ul>
     * 
     * <h3>Success Response:</h3>
     * Returns ExtensionResponse with a list of Event Details containing the next upcoming meetings
     * in chronological order.
     * 
     * <h3>Validation Failure Response:</h3>
     * Returns ExtensionResponse with SubCatalogRequest for user to correct invalid inputs.
     * 
     * <h3>Error Response:</h3>
     * Returns ExtensionResponse with error message if operation fails.
     * 
     * <h3>Business Rules:</h3>
     * <ul>
     *   <li>Events are returned in chronological order (earliest first)</li>
     *   <li>Only future events are included (past events are excluded)</li>
     *   <li>All-day events and timed events are both included</li>
     *   <li>Canceled events are excluded from results</li>
     *   <li>If fewer events exist than requested, all available events are returned</li>
     * </ul>
     * 
     * @param numberOfMeetings the number of upcoming meetings to retrieve, must be positive
     * @return ExtensionResponse containing operation result (success, validation failure, or error)
     */
    public ExtensionResponse getNextUpcomingMeetingsWithValidation(Double numberOfMeetings) {
        LOGGER.info("getNextUpcomingMeetingsWithValidation called: numberOfMeetings={}", numberOfMeetings);
        telemetryMetrics.incrementCounter(TelemetryConstants.GET_NEXT_UPCOMING_MEETINGS_REQUEST, 1L);

        try {
            // Build validation parameters
            Map<Validator.ValidationResource, String> validationParams = new HashMap<>();
            validationParams.put(Validator.ValidationResource.NUMBER_OF_MEETINGS, 
                               numberOfMeetings != null ? numberOfMeetings.toString() : null);

            LOGGER.info("Starting validation for numberOfMeetings: {}", numberOfMeetings);

            // Validate input parameters
            List<ValidationOrchestrator.ValidationResult> validationResults =
                    validationOrchestrator.validate(validationParams);

            if (validationResults.isEmpty()) {
                // Validation passed - proceed with operation
                LOGGER.info("Validation passed, proceeding with getting next upcoming meetings");
                return executeGetNextUpcomingMeetings(numberOfMeetings);
            } else {
                // Validation failed - initiate SubCatalogRequest flow
                LOGGER.info("Validation failed with {} errors, initiating SubCatalogRequest flow", 
                           validationResults.size());
                telemetryMetrics.incrementCounter(TelemetryConstants.GET_NEXT_UPCOMING_MEETINGS_RETRY_ATTEMPT, 1L);
                return handleValidationFailure(validationResults, numberOfMeetings);
            }

        } catch (Exception cause) {
            LOGGER.error("Error occurred while getting next upcoming meetings: numberOfMeetings={}, error={}",
                        numberOfMeetings, cause.getMessage(), cause);
            telemetryMetrics.incrementCounter(TelemetryConstants.GET_NEXT_UPCOMING_MEETINGS_REQUEST_FAILURE, 1L);
            return buildErrorResponse(cause);
        }
    }

    /**
     * Executes the get next upcoming meetings operation after validation passes.
     * 
     * <p>This private method calls the CalendarService to perform the actual operation
     * and builds a success response with the list of event details.</p>
     * 
     * @param numberOfMeetings the number of meetings to retrieve
     * @return ExtensionResponse with success result and list of event details
     */
    private ExtensionResponse executeGetNextUpcomingMeetings(Double numberOfMeetings) {
        LOGGER.debug("Executing get next upcoming meetings operation");
        
        // Call service layer to perform the operation
        List<EventDetails> eventDetailsList = calendarService.getNextUpcomingMeetings(numberOfMeetings);
        
        // Record success telemetry
        telemetryMetrics.incrementCounter(TelemetryConstants.GET_NEXT_UPCOMING_MEETINGS_REQUEST_SUCCESS, 1L);
        
        LOGGER.info("Successfully retrieved {} upcoming meetings", eventDetailsList.size());
        
        // Build and return success response
        return new ExtensionResponseBuilder()
                .success(Map.of("Event Details", eventDetailsList))
                .build();
    }

    /**
     * Handles validation failure by initiating SubCatalogRequest flow.
     * 
     * <p>This private method delegates to SubCatalogRequestImpl to handle the validation
     * failure, which will prompt the user to correct invalid inputs.</p>
     * 
     * @param validationResults the list of validation errors
     * @param numberOfMeetings the number of meetings requested
     * @return ExtensionResponse with SubCatalogRequest for user correction
     */
    private ExtensionResponse handleValidationFailure(
            List<ValidationOrchestrator.ValidationResult> validationResults,
            Double numberOfMeetings) {
        
        LOGGER.debug("Handling validation failure with SubCatalogRequest");
        
        // Delegate to SubCatalogRequestImpl to handle validation failure
        ExtensionResponse response = subCatalogRequestImpl.handleGetNextUpcomingMeetings(
                validationResults, numberOfMeetings);
        
        // Record success telemetry (SubCatalogRequest was successfully created)
        telemetryMetrics.incrementCounter(TelemetryConstants.GET_NEXT_UPCOMING_MEETINGS_REQUEST_SUCCESS, 1L);
        
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
                        .errorMessage("Error occurred in getNextUpcomingMeetings: " + cause.getMessage())
                        .stackTrace(cause.getStackTrace().toString())
                        .build())
                .build();
    }
}

