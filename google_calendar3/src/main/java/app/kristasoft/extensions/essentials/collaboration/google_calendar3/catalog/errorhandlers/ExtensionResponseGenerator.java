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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.errorhandlers;


import app.krista.extension.executor.*;
import app.krista.model.field.NamedField;
import app.krista.model.field.NamedValuedField;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.NamedFieldFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.RemediationActionFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.validators.ValidationOrchestrator;
import org.jvnet.hk2.annotations.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp.SubCatalogConstants.REENTER;


@Service
public class ExtensionResponseGenerator {

    private static final String ERROR_MESSAGE = "ErrorMessage";
    private static final String STEP_MESSAGE = "StepMessage";
    private static final String FIELD = "Field";

    public ExtensionResponse generateConfirmationResponse(
            ExtensionResponse.Error.ExceptionType exceptionType,
            List<ValidationOrchestrator.ValidationResult> validationResults,
            String subCatalogRequestName,
            Map<String, Object> state) {

        List<NamedField> fields = List.of(NamedFieldFactory.createSwitchField(REENTER));
        Map<String, Object> stringStringMap = generateResponse(validationResults, false);

        ExtensionResponse.Error error = new ErrorBuilder().exceptionType(exceptionType).build();
        return new ExtensionResponseBuilder().failure(error,
                new RemediationActions(List.of(RemediationActionFactory.createAskAction((String) stringStringMap.get(STEP_MESSAGE), fields)),
                        subCatalogRequestName), state).build();
    }

    private Map<String, Object> generateResponse(List<ValidationOrchestrator.ValidationResult> validationResults,
                                                 boolean fetchResponse) {
        StringBuilder stepMessage = new StringBuilder();
        StringBuilder errMessage = new StringBuilder();
        List<NamedField> fields = new ArrayList<>();
        for (ValidationOrchestrator.ValidationResult validationResult : validationResults) {
            stepMessage.append(!fetchResponse ? validationResult.confirmStepMessage()
                    : validationResult.fetchStepMessage()).append("\n");
            errMessage.append(validationResult.errMessage()).append("\n");
            if (fetchResponse) {
                fields.add(NamedFieldFactory.createFieldByType(validationResult.fetchFieldName(), validationResult.fieldType()));
            }
        }
        return Map.of(STEP_MESSAGE, stepMessage.toString(), ERROR_MESSAGE, errMessage.toString(), FIELD, fields);
    }

    @SuppressWarnings("unchecked")
    public ExtensionResponse generateFetchResponse(
            ExtensionResponse.Error.ExceptionType exceptionType,
            List<ValidationOrchestrator.ValidationResult> validationResults,
            String subCatalogRequestName,
            Map<String, Object> state) {

        Map<String, Object> stringStringMap = generateResponse(validationResults, true);
        ExtensionResponse.Error error = new ErrorBuilder().exceptionType(exceptionType).build();

        List<NamedField> fields = (List<NamedField>) stringStringMap.get(FIELD);
        return new ExtensionResponseBuilder()
                .failure(error, new RemediationActions(
                        List.of(RemediationActionFactory.createAskAction((String) stringStringMap.get(STEP_MESSAGE), fields)),
                        subCatalogRequestName), state)
                .build();
    }

    public ExtensionResponse generateFetchDenyResponse(
            ExtensionResponse.Error.ExceptionType exceptionType,
            List<ValidationOrchestrator.ValidationResult> validationResults,
            String subCatalogRequestName,
            Map<String, Object> state
    ) {
        StringBuilder stepMessage = new StringBuilder("Updated value for ");
        for (ValidationOrchestrator.ValidationResult validationResult : validationResults) {
            stepMessage.append("'").append(validationResult.fetchFieldName()).append("', ");
        }
        stepMessage.setLength(stepMessage.length() - 2); // Remove last ", "
        stepMessage.append(" were not provided.");

        ExtensionResponse.Error error = new ErrorBuilder().exceptionType(exceptionType).build();
        List<NamedValuedField> fields = List.of();

        return new ExtensionResponseBuilder()
                .failure(error, new RemediationActions(
                        List.of(RemediationActionFactory.createInformAction(stepMessage.toString(), RemediationAction.RecipientType.ALL_PARTICIPANTS, fields)),
                        subCatalogRequestName), state)
                .build();
    }
}

