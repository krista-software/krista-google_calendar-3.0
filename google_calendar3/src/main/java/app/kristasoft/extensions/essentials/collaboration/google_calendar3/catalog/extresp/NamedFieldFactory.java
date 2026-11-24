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

import app.krista.model.field.NamedField;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

public class NamedFieldFactory {

    private NamedFieldFactory() {
    }

    public static NamedField createSwitchField(String fieldName) {
        return new NamedField(fieldName, FieldTypes.SWITCH_FIELD, Map.of(), Map.of());
    }

    /**
     * Creates a text field with the specified name.
     *
     * @param fieldName the name of the field
     * @return a new NamedField instance configured as a text field
     */
    public static NamedField createTextField(String fieldName) {
        return new NamedField(fieldName, FieldTypes.TEXT_FIELD, Map.of(), Map.of());
    }

    /**
     * Creates a field based on the specified field type.
     * This method acts as a factory that delegates to the appropriate specific creation method.
     *
     * @param fieldName the name of the field
     * @param fieldType the type of field to create (Date, Email, Text, Number, Boolean, File, etc.)
     * @return a new NamedField instance configured with the specified type
     */
    public static NamedField createFieldByType(String fieldName, String fieldType) {
        return switch (fieldType.toLowerCase()) {
            case "file", "com.krista.fields.file" -> createFileField(fieldName);
            case "switch", "com.krista.fields.switch" -> createSwitchField(fieldName);
            case "text", "com.krista.fields.text" -> createTextField(fieldName);
            case "list", "com.krista.fields.list" -> createListField(fieldName); // Default list of text
            case "date", "com.krista.fields.date" -> createDateField(fieldName); // Default list of text
            default -> createField(fieldName, fieldType); // Default fallback to text field
        };
    }

    private static NamedField createDateField(String fieldName) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("visualWidth", "S");
        attributes.put("includeTimeOfDay", true);
        attributes.put("allowToday", true);
        attributes.put("allowPast", true);
        attributes.put("allowFuture", true);
        return new NamedField(fieldName, FieldTypes.DATE_FIELD, attributes, Map.of());
    }

    public static NamedField createField(String fieldName, String fieldType) {

        if (fieldType.equals(FieldTypes.LIST_FIELD)) {
            Map<String, Object> innerAttributes = new HashMap<>(); // optional config

            Map<String, Object> fieldMap = new HashMap<>();
            fieldMap.put("type", "Field");
            fieldMap.put("fieldType", "com.krista.fields.Text");
            fieldMap.put("attributes", innerAttributes);

            // This defines that the list holds Text fields
            String fieldMapJson = convertMapToJson(fieldMap);

            Map<String, Object> attributes = new HashMap<>();
            attributes.put("field", fieldMapJson);

            return new NamedField(fieldName, FieldTypes.LIST_FIELD, attributes, Map.of());
        }

        return new NamedField(fieldName, fieldType, Map.of(), Map.of());
    }

    public static NamedField createFileField(String fieldName) {
        Map<String, Object> fileUploadConfig = new HashMap<>();

        fileUploadConfig.put("visualWidth", "S");
        fileUploadConfig.put("multipleFileUpload", false);
        fileUploadConfig.put("minFileSizeUnit", "KB");
        fileUploadConfig.put("maxFileSizeUnit", "MB");
        fileUploadConfig.put("fileTypes", "[\"Document\",\"Image gallery\",\"Camera\",\"Contact\",\"Audio\"]");
        return new NamedField(fieldName, FieldTypes.FILE_FIELD, fileUploadConfig, Map.of());
    }

    public static NamedField createListField(String fieldName) {
        Map<String, Object> innerAttributes = new HashMap<>(); // optional config

        Map<String, Object> fieldMap = new HashMap<>();
        fieldMap.put("type", "Field");
        fieldMap.put("fieldType", "com.krista.fields.Text");
        fieldMap.put("attributes", innerAttributes);

        // This defines that the list holds Text fields
        String fieldMapJson = convertMapToJson(fieldMap);

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("field", fieldMapJson);

        return new NamedField(fieldName, FieldTypes.LIST_FIELD, attributes, Map.of());
    }


    private static String convertMapToJson(Map<String, Object> map) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(map);
        } catch (Exception cause) {
            throw new RuntimeException("Failed to convert map to JSON string", cause);
        }
    }

}


