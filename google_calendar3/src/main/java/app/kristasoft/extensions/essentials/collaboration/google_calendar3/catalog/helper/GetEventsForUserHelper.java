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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.CalendarResource;
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
 * Helper class for handling Get Events For User operations with validation.
 * 
 * <p>This helper class encapsulates all the business logic for retrieving events for a specific user
 * within a date range, including input validation, error handling, and subcatalog request flows. 
 * It separates the business logic from the presentation layer (MeetingsArea), making the code more
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
 *   <li>Validate input parameters (User Email, From Date, To Date)</li>
 *   <li>Execute get events for user operation via CalendarService</li>
 *   <li>Handle validation failures with SubCatalogRequest flow</li>
 *   <li>Build appropriate ExtensionResponse for success/failure cases</li>
 *   <li>Record telemetry metrics for monitoring</li>
 *   <li>Track event count for analytics</li>
 * </ul>
 * 
 * <h2>Important Notes:</h2>
 * <ul>
 *   <li><b>System Account Required:</b> Setup must be done using a system account</li>
 *   <li><b>Date Range:</b> Returns events within the specified date range</li>
 *   <li><b>User Access:</b> Requires appropriate permissions to access user's calendar</li>
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
public class GetEventsForUserHelper {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(GetEventsForUserHelper.class);
    
    private final CalendarService calendarService;
    private final ValidationOrchestrator validationOrchestrator;
    private final SubCatalogRequestImpl subCatalogRequestImpl;
    private final TelemetryMetrics telemetryMetrics;

    /**
     * Constructs a new GetEventsForUserHelper with required dependencies.
     * 
     * @param calendarService the calendar service for performing operations, must not be null
     * @param validationOrchestrator the validation orchestrator for input validation, must not be null
     * @param subCatalogRequestImpl the subcatalog request handler for validation failures, must not be null
     * @param telemetryMetrics the telemetry metrics for recording operations, must not be null
     */
    @Inject
    public GetEventsForUserHelper(CalendarService calendarService,
                                  ValidationOrchestrator validationOrchestrator,
                                  SubCatalogRequestImpl subCatalogRequestImpl,
                                  TelemetryMetrics telemetryMetrics) {
        this.calendarService = calendarService;
        this.validationOrchestrator = validationOrchestrator;
        this.subCatalogRequestImpl = subCatalogRequestImpl;
        this.telemetryMetrics = telemetryMetrics;
    }

    /**
     * Retrieves events for a specific user within a date range with validation and error handling.
     * 
     * <p>This method performs the following steps:</p>
     * <ol>
     *   <li>Validates input parameters (User Email, From Date, To Date)</li>
     *   <li>If validation passes, executes the get events for user operation</li>
     *   <li>If validation fails, initiates SubCatalogRequest flow for user correction</li>
     *   <li>Records telemetry metrics for monitoring</li>
     *   <li>Tracks event count for analytics</li>
     *   <li>Builds and returns appropriate ExtensionResponse</li>
     * </ol>
     * 
     * <h3>Validation Rules:</h3>
     * <ul>
     *   <li>User Email must not be null or empty</li>
     *   <li>User Email must be a valid email format</li>
     *   <li>From Date must not be null and must be a valid timestamp</li>
     *   <li>To Date must not be null and must be a valid timestamp</li>
     *   <li>To Date must be after From Date</li>
     * </ul>
     * 
     * <h3>Success Response:</h3>
     * Returns ExtensionResponse with a list of Event Details for the specified user
     * within the date range. Each event includes:
     * <ul>
     *   <li>Event ID</li>
     *   <li>Title/Subject</li>
     *   <li>Location</li>
     *   <li>Start Date/Time</li>
     *   <li>End Date/Time</li>
     *   <li>Description/Details</li>
     *   <li>Attendee List</li>
     *   <li>Organizer Information</li>
     * </ul>
     * 
     * <h3>Validation Failure Response:</h3>
     * Returns ExtensionResponse with SubCatalogRequest for user to correct invalid inputs.
     * 
     * <h3>Error Response:</h3>
     * Returns ExtensionResponse with error message if operation fails.
     * 
     * <h3>Business Rules:</h3>
     * <ul>
     *   <li>Requires system account setup with appropriate permissions</li>
     *   <li>Returns all events within the specified date range (inclusive)</li>
     *   <li>Events are returned in chronological order</li>
     *   <li>Includes all event types (meetings, appointments, all-day events)</li>
     *   <li>Respects user's calendar privacy settings</li>
     *   <li>May include recurring event instances</li>
     * </ul>
     * 
     * <h3>Use Cases:</h3>
     * <ul>
     *   <li>View another user's schedule for meeting coordination</li>
     *   <li>Check availability before scheduling meetings</li>
     *   <li>Generate reports on user's calendar activities</li>
     *   <li>Audit scheduled events for a specific time period</li>
     *   <li>Delegate calendar management tasks</li>
     * </ul>
     * 
     * @param userEmail the email address of the user whose events to retrieve, must not be null or empty
     * @param from the start date/time as Unix timestamp in milliseconds, must not be null
     * @param to the end date/time as Unix timestamp in milliseconds, must not be null and after from
     * @return ExtensionResponse containing operation result (success, validation failure, or error)
     */
    public ExtensionResponse getEventsForUserWithValidation(String userEmail, Long from, Long to) {
        LOGGER.info("getEventsForUserWithValidation called: userEmail={}, from={}, to={}", userEmail, from, to);
        telemetryMetrics.incrementCounter(TelemetryConstants.GET_EVENTS_FOR_USER_REQUEST, 1L);

        try {
            // Build validation parameters
            Map<Validator.ValidationResource, String> validationParams = new HashMap<>();
            validationParams.put(Validator.ValidationResource.USER_EMAIL, userEmail);

            // Validate date range (required)
            if (from != null && to != null) {
                validationParams.put(Validator.ValidationResource.FROM_DATE, String.valueOf(from));
                validationParams.put(Validator.ValidationResource.TO_DATE, String.valueOf(to));
            }

            LOGGER.info("Starting validation for get events for user request");

            // Validate input parameters
            List<ValidationOrchestrator.ValidationResult> validationResults =
                    validationOrchestrator.validate(validationParams);

            if (validationResults.isEmpty()) {
                // Validation passed - proceed with operation
                LOGGER.info("Validation passed, proceeding with getting events for user");
                return executeGetEventsForUser(userEmail, from, to);
            } else {
                // Validation failed - initiate SubCatalogRequest flow
                LOGGER.info("Validation failed with {} errors, initiating SubCatalogRequest flow", 
                           validationResults.size());
                telemetryMetrics.incrementCounter(TelemetryConstants.GET_EVENTS_FOR_USER_RETRY_ATTEMPT, 1L);
                return handleValidationFailure(validationResults, userEmail, from, to);
            }

        } catch (Exception cause) {
            LOGGER.error("Error occurred while getting events for user: userEmail={}, from={}, to={}, error={}",
                        userEmail, from, to, cause.getMessage(), cause);
            telemetryMetrics.incrementCounter(TelemetryConstants.GET_EVENTS_FOR_USER_REQUEST_FAILURE, 1L);
            return buildErrorResponse(cause);
        }
    }

    /**
     * Executes the get events for user operation after validation passes.
     *
     * <p>This private method calls the CalendarService to perform the actual operation
     * and builds a success response with the list of events.</p>
     *
     * <p><b>Note:</b> This is a read-only operation that retrieves event information
     * without modifying any data. Requires system account with appropriate permissions.</p>
     *
     * @param userEmail the user email address
     * @param from the start date/time
     * @param to the end date/time
     * @return ExtensionResponse with success result and list of events
     */
    private ExtensionResponse executeGetEventsForUser(String userEmail, Long from, Long to) {
        LOGGER.debug("Executing get events for user operation");
        
        // Call service layer to perform the operation
        List<EventDetails> events = calendarService.getEventsForUser(userEmail, from, to);
        
        // Record success telemetry
        telemetryMetrics.incrementCounter(TelemetryConstants.GET_EVENTS_FOR_USER_REQUEST_SUCCESS, 1L);
        
        int eventCount = events != null ? events.size() : 0;
        LOGGER.info("Successfully retrieved {} events for user {}", eventCount, userEmail);
        
        // Build and return success response
        return new ExtensionResponseBuilder()
                .success(Map.of(CalendarResource.EVENTS, events))
                .build();
    }

    /**
     * Handles validation failure by initiating SubCatalogRequest flow.
     * 
     * <p>This private method delegates to SubCatalogRequestImpl to handle the validation
     * failure, which will prompt the user to correct invalid inputs.</p>
     * 
     * @param validationResults the list of validation errors
     * @param userEmail the user email address
     * @param from the start date/time
     * @param to the end date/time
     * @return ExtensionResponse with SubCatalogRequest for user correction
     */
    private ExtensionResponse handleValidationFailure(
            List<ValidationOrchestrator.ValidationResult> validationResults,
            String userEmail, Long from, Long to) {
        
        LOGGER.debug("Handling validation failure with SubCatalogRequest");
        
        // Delegate to SubCatalogRequestImpl to handle validation failure
        ExtensionResponse response = subCatalogRequestImpl.handleGetEventsForUser(
                validationResults, userEmail, from, to);
        
        // Record success telemetry (SubCatalogRequest was successfully created)
        telemetryMetrics.incrementCounter(TelemetryConstants.GET_EVENTS_FOR_USER_REQUEST_SUCCESS, 1L);
        
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

        ExtensionResponse.Error error = new ErrorBuilder()
                .exceptionType(ExtensionResponse.Error.ExceptionType.SYSTEM_ERROR)
                .errorMessage("Error occurred in get events for user: " + cause.getMessage())
                .stackTrace(cause.getStackTrace().toString())
                .build();

        return new ExtensionResponseBuilder()
                .failure(error)
                .build();
    }
}

