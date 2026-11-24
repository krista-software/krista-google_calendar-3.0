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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;
import javax.ws.rs.Path;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the HK2 wiring contract of {@link GoogleCalendarExtension}.
 *
 * <p>Regression for: {@code UnsatisfiedDependencyException ... requiredType=AuthenticationResource,
 * parent=GoogleCalendarExtension}. A JAX-RS {@code @Path} resource is managed by Jersey and is NOT
 * bound in the HK2 {@code ServiceLocator}, so it cannot be a constructor dependency of an
 * HK2-instantiated bean such as the extension. Such resources must obtain their own dependencies
 * via their own {@code @Inject} constructor instead of being injected into the extension.
 */
class GoogleCalendarExtensionInjectionTest {

    @Test
    @DisplayName("@Inject constructor must not depend on a JAX-RS @Path resource (HK2 cannot satisfy those)")
    void injectConstructorHasNoJaxRsResourceParams() {
        Constructor<?> injectCtor = null;
        for (Constructor<?> c : GoogleCalendarExtension.class.getDeclaredConstructors()) {
            if (c.isAnnotationPresent(Inject.class)) {
                injectCtor = c;
                break;
            }
        }
        assertNotNull(injectCtor, "GoogleCalendarExtension must declare an @Inject constructor");

        List<String> jaxRsParams = new ArrayList<>();
        for (Parameter p : injectCtor.getParameters()) {
            if (p.getType().isAnnotationPresent(Path.class)) {
                jaxRsParams.add(p.getType().getSimpleName());
            }
        }

        assertTrue(jaxRsParams.isEmpty(),
                "GoogleCalendarExtension's @Inject constructor depends on JAX-RS @Path resource(s) "
                        + jaxRsParams + ", which HK2 cannot inject — this throws "
                        + "UnsatisfiedDependencyException at extension load. Have the resource @Inject "
                        + "its own dependencies instead of injecting it into the extension.");
    }
}
