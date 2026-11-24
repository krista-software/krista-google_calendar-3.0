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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.extresp;

import app.krista.extension.executor.ErrorBuilder;
import app.krista.extension.executor.ExtensionResponse;
import app.krista.extension.executor.ExtensionResponseBuilder;

import java.util.Map;

/**
 * Factory class for creating ExtensionResponse objects
 */
public class ExtensionResponseFactory {

    private ExtensionResponseFactory() {
    }

    /**
     * Creates a successful ExtensionResponse with the given data
     *
     * @param data The data to include in the response
     * @return ExtensionResponse with success status
     */
    public static ExtensionResponse create(Map<String, Object> data) {
        return new ExtensionResponseBuilder()
                .success(data, null, null)
                .build();
    }

    /**
     * Creates a failed ExtensionResponse with the given exception and message
     *
     * @param cause         The exception that caused the failure
     * @param message       The error message
     * @param exceptionType The type of exception
     * @return ExtensionResponse with failure status
     */
    public static ExtensionResponse create(Exception cause, String message, ExtensionResponse.Error.ExceptionType exceptionType) {
        ExtensionResponse.Error error = new ErrorBuilder()
                .exceptionType(exceptionType)
                .errorMessage(message + ": " + cause.getMessage())
                .stackTrace(cause.getStackTrace().toString())
                .build();

        return new ExtensionResponseBuilder()
                .failure(error)
                .build();
    }
}
