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
 * Helper class for handling Schedule Meeting operations with validation.
 * 
 * <p>This helper class encapsulates all the business logic for creating and scheduling new calendar events,
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
 *   <li>Validate input parameters (Title, Date From, Date To, Attendee Email List)</li>
 *   <li>Execute schedule meeting operation via CalendarService</li>
 *   <li>Handle validation failures with SubCatalogRequest flow</li>
 *   <li>Build appropriate ExtensionResponse for success/failure cases</li>
 *   <li>Record telemetry metrics for monitoring</li>
 *   <li>Track attendee count for analytics</li>
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
public class ScheduleMeetingHelper {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduleMeetingHelper.class);
    
    private final CalendarService calendarService;
    private final ValidationOrchestrator validationOrchestrator;
    private final SubCatalogRequestImpl subCatalogRequestImpl;
    private final TelemetryMetrics telemetryMetrics;

    /**
     * Constructs a new ScheduleMeetingHelper with required dependencies.
     * 
     * @param calendarService the calendar service for performing operations, must not be null
     * @param validationOrchestrator the validation orchestrator for input validation, must not be null
     * @param subCatalogRequestImpl the subcatalog request handler for validation failures, must not be null
     * @param telemetryMetrics the telemetry metrics for recording operations, must not be null
     */
    @Inject
    public ScheduleMeetingHelper(CalendarService calendarService,
                                 ValidationOrchestrator validationOrchestrator,
                                 SubCatalogRequestImpl subCatalogRequestImpl,
                                 TelemetryMetrics telemetryMetrics) {
        this.calendarService = calendarService;
        this.validationOrchestrator = validationOrchestrator;
        this.subCatalogRequestImpl = subCatalogRequestImpl;
        this.telemetryMetrics = telemetryMetrics;
    }

    /**
     * Schedules a new meeting with validation and error handling.
     * 
     * <p>This method performs the following steps:</p>
     * <ol>
     *   <li>Validates input parameters (Title, Date From, Date To, Attendee Email List)</li>
     *   <li>If validation passes, executes the schedule meeting operation</li>
     *   <li>If validation fails, initiates SubCatalogRequest flow for user correction</li>
     *   <li>Records telemetry metrics for monitoring</li>
     *   <li>Tracks attendee count for analytics</li>
     *   <li>Builds and returns appropriate ExtensionResponse</li>
     * </ol>
     * 
     * <h3>Validation Rules:</h3>
     * <ul>
     *   <li>Title must not be null or empty</li>
     *   <li>Date From must not be null and must be a valid timestamp</li>
     *   <li>Date To must not be null and must be a valid timestamp</li>
     *   <li>Date To must be after Date From</li>
     *   <li>Attendee Email List must contain valid email addresses</li>
     *   <li>Location is optional</li>
     *   <li>Details is optional</li>
     * </ul>
     * 
     * <h3>Success Response:</h3>
     * Returns ExtensionResponse with Event Details containing the newly created event information
     * including event ID, title, location, times, and attendee list.
     * 
     * <h3>Validation Failure Response:</h3>
     * Returns ExtensionResponse with SubCatalogRequest for user to correct invalid inputs.
     * 
     * <h3>Error Response:</h3>
     * Returns ExtensionResponse with error message if operation fails.
     * 
     * <h3>Business Rules:</h3>
     * <ul>
     *   <li>Event is created in the organizer's primary calendar</li>
     *   <li>All attendees receive invitation notifications</li>
     *   <li>Event appears on all attendees' calendars (pending acceptance)</li>
     *   <li>Organizer is automatically added as an attendee</li>
     *   <li>Time zone is based on calendar settings</li>
     * </ul>
     * 
     * @param title the title or subject of the meeting, must not be null or empty
     * @param location the physical or virtual location, can be null
     * @param dateFrom the start date and time as Unix timestamp in milliseconds, must not be null
     * @param dateTo the end date and time as Unix timestamp in milliseconds, must not be null and after dateFrom
     * @param details additional description or agenda in rich text format, can be null
     * @param attendeeEmails list of attendee email addresses, can be empty but not null
     * @return ExtensionResponse containing operation result (success, validation failure, or error)
     */
    public ExtensionResponse scheduleMeetingWithValidation(String title, String location, Long dateFrom, 
                                                          Long dateTo, String details, List<String> attendeeEmails) {
        LOGGER.info("scheduleMeetingWithValidation called: title={}, location={}, dateFrom={}, dateTo={}, attendeeCount={}", 
                   title, location, dateFrom, dateTo, attendeeEmails != null ? attendeeEmails.size() : 0);
        telemetryMetrics.incrementCounter(TelemetryConstants.SCHEDULE_MEETING_REQUEST, 1L);

        try {
            // Convert List<String> to comma-separated string for validation
            String attendeeEmailsStr = attendeeEmails != null ? String.join(",", attendeeEmails) : "";

            // Build validation parameters
            Map<Validator.ValidationResource, String> validationParams = new HashMap<>();
            validationParams.put(Validator.ValidationResource.TITLE, title);
            validationParams.put(Validator.ValidationResource.DATE_FROM, dateFrom != null ? dateFrom.toString() : "");
            validationParams.put(Validator.ValidationResource.DATE_TO, dateTo != null ? dateTo.toString() : "");
            validationParams.put(Validator.ValidationResource.ATTENDEE_EMAIL_LIST, attendeeEmailsStr);

            // Validate input parameters
            List<ValidationOrchestrator.ValidationResult> validationResults =
                    validationOrchestrator.validate(validationParams);

            if (validationResults.isEmpty()) {
                // Validation passed - proceed with operation
                LOGGER.info("Validation passed, proceeding with scheduling meeting");
                return executeScheduleMeeting(title, location, dateFrom, dateTo, details, attendeeEmails);
            } else {
                // Validation failed - initiate SubCatalogRequest flow
                LOGGER.info("Validation failed with {} errors, initiating SubCatalogRequest flow", 
                           validationResults.size());
                telemetryMetrics.incrementCounter(TelemetryConstants.SCHEDULE_MEETING_RETRY_ATTEMPT, 1L);
                return handleValidationFailure(validationResults, title, location, dateFrom, dateTo, details, attendeeEmails);
            }

        } catch (Exception cause) {
            LOGGER.error("Error occurred while scheduling meeting: title={}, location={}, dateFrom={}, dateTo={}, details={}, attendeeEmails={}, error={}",
                        title, location, dateFrom, dateTo, details, attendeeEmails, cause.getMessage(), cause);
            telemetryMetrics.incrementCounter(TelemetryConstants.SCHEDULE_MEETING_REQUEST_FAILURE, 1L);
            return buildErrorResponse(cause);
        }
    }

    /**
     * Executes the schedule meeting operation after validation passes.
     * 
     * <p>This private method calls the CalendarService to perform the actual operation
     * and builds a success response with the event details.</p>
     * 
     * @param title the meeting title
     * @param location the meeting location
     * @param dateFrom the start date/time
     * @param dateTo the end date/time
     * @param details the meeting details
     * @param attendeeEmails the list of attendee emails
     * @return ExtensionResponse with success result and event details
     */
    private ExtensionResponse executeScheduleMeeting(String title, String location, Long dateFrom, 
                                                     Long dateTo, String details, List<String> attendeeEmails) {
        LOGGER.debug("Executing schedule meeting operation");
        
        // Call service layer to perform the operation
        EventDetails eventDetails = calendarService.scheduleMeeting(title, location, dateFrom, dateTo, details, attendeeEmails);
        
        // Record success telemetry
        telemetryMetrics.incrementCounter(TelemetryConstants.SCHEDULE_MEETING_REQUEST_SUCCESS, 1L);
        
        LOGGER.info("Successfully scheduled meeting with ID: {}", eventDetails.eventId);
        
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
     * @param title the meeting title
     * @param location the meeting location
     * @param dateFrom the start date/time
     * @param dateTo the end date/time
     * @param details the meeting details
     * @param attendeeEmails the list of attendee emails
     * @return ExtensionResponse with SubCatalogRequest for user correction
     */
    private ExtensionResponse handleValidationFailure(
            List<ValidationOrchestrator.ValidationResult> validationResults,
            String title, String location, Long dateFrom, Long dateTo, String details, List<String> attendeeEmails) {
        
        LOGGER.debug("Handling validation failure with SubCatalogRequest");
        
        // Delegate to SubCatalogRequestImpl to handle validation failure
        ExtensionResponse response = subCatalogRequestImpl.handleScheduleMeeting(
                validationResults, title, location, dateFrom, dateTo, details, attendeeEmails);
        
        // Record success telemetry (SubCatalogRequest was successfully created)
        telemetryMetrics.incrementCounter(TelemetryConstants.SCHEDULE_MEETING_REQUEST_SUCCESS, 1L);
        
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
                        .errorMessage("Error occurred in scheduleMeeting: " + cause.getMessage())
                        .stackTrace(cause.getStackTrace().toString())
                        .build())
                .build();
    }
}

