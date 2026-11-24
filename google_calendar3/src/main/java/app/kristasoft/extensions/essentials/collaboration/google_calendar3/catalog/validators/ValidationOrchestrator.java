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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.validators;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.CalendarService;
import org.jvnet.hk2.annotations.Service;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrator for managing and executing validators for Google Calendar operations.
 *
 * <p>This class centralizes all validation logic by maintaining a registry of validators
 * and providing a unified interface for validating multiple input parameters.</p>
 */
@Service
public class ValidationOrchestrator {

    private final Map<Validator.ValidationResource, Validator> validators = new ConcurrentHashMap<>();

    @Inject
    public ValidationOrchestrator(CalendarService calendarService) {
        // Register validators with CalendarService dependency
        validators.put(Validator.ValidationResource.EVENT_ID, new EventIdValidators(calendarService));
        validators.put(Validator.ValidationResource.ATTENDEE_EMAIL, new AttendeeEmailValidator(calendarService));

        // Register validators without dependencies
        validators.put(Validator.ValidationResource.ATTENDEE_EMAIL_LIST, new AttendeeEmailListValidator());
        validators.put(Validator.ValidationResource.TITLE, new TitleValidator());
        validators.put(Validator.ValidationResource.DATE_FROM, new DateFromValidator());
        validators.put(Validator.ValidationResource.DATE_TO, new DateToValidator());
        validators.put(Validator.ValidationResource.NUMBER_OF_MEETINGS, new NumberOfMeetingsValidator());
        validators.put(Validator.ValidationResource.USER_EMAIL, new UserEmailValidator());
        validators.put(Validator.ValidationResource.FROM_DATE, new FromDateValidator());
        validators.put(Validator.ValidationResource.TO_DATE, new ToDateValidator());
    }

    public record ValidationResult(String confirmStepMessage, String fetchFieldName, String fetchStepMessage,
                                   String errMessage, String fieldType) {
    }

    /**
     * Validates the given resources using the corresponding validators.
     *
     * @param resources Map of ValidationResource to their corresponding data.
     * @return A list of ValidationResult for failed validations.
     */
    public List<ValidationResult> validate(Map<Validator.ValidationResource, String> resources) {
        List<ValidationResult> results = new ArrayList<>();

        for (Map.Entry<Validator.ValidationResource, String> entry : resources.entrySet()) {
            Validator.ValidationResource resourceKey = entry.getKey();
            String resourceValue = entry.getValue();

            Validator validator = validators.get(resourceKey);

            if (validator == null) {
                throw new IllegalArgumentException("No validator found for resource: " + resourceKey);
            }

            if (!validator.validate(resourceValue, resources)) {
                String confirmStepMessage = validator.getConfirmationStepMessage(resourceValue, resources);
                String fetchFieldName = validator.getFetchFieldName();
                String fetchStepMessage = validator.getFetchStepMessage();
                String errMessage = validator.getErrMessage(resourceValue);
                String fieldType = validator.getFieldType();
                results.add(new ValidationResult(confirmStepMessage, fetchFieldName, fetchStepMessage, errMessage, fieldType));
            }
        }
        return results;
    }
}
