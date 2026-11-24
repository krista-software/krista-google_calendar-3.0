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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog;

import app.krista.extension.executor.ExtensionResponse;
import app.krista.extension.executor.Invoker;
import app.krista.ksdk.invoker.InvokerConfiguration;
import app.krista.ksdk.telemetry.TelemetryMetrics;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.handler.AdaptiveCalendarSyncHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.handler.AuthenticateHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.handler.CalendarSyncByUserHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.handler.DisconnectHandler;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.AcceptInvitationHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.AddAttendeesHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.CancelEventHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.DeclineInvitationHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.GetEventHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.GetEventsForUserHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.GetNextUpcomingMeetingsHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.RemoveAttendeeHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.ScheduleMeetingHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.helper.TentativeInvitationHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.util.TelemetryHelper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.catalog.validators.ValidationOrchestrator;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.SubCatalogRequestImpl;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.GoogleAttributeStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.CalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KIM-474 — the {@code alwaysOn} attribute and the {@code Get Capture Configuration} catalog
 * request that carries it to the Meeting Agent wrapper.
 *
 * <p>This attribute is the authoritative source of the workspace's capture mode: when it is on,
 * Meeting Agent captures every meeting reachable through this calendar system rather than only the
 * meetings of licensed, individually-authenticated users. Reading it wrongly is not cosmetic in
 * either direction — a false negative silently disables a feature the tenant paid for, and a false
 * positive silently widens capture across a whole tenant. The rule is therefore deliberately
 * strict: only an unambiguously-on value reads as on.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Google Calendar — capture configuration (KIM-474)")
class CaptureConfigurationTest {

    private static final String INVOKER_ID = "INV_google_calendar";
    private static final String WORKSPACE_ID = "ws_google_calendar";

    @Mock
    private Invoker invoker;
    @Mock
    private InvokerConfiguration invokerConfiguration;

    private MeetingsArea meetingsArea;

    @BeforeEach
    void setUp() {
        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        lenient().when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        meetingsArea = new MeetingsArea(
                mock(CalendarService.class),
                mock(SubCatalogRequestImpl.class),
                mock(ValidationOrchestrator.class),
                mock(TelemetryHelper.class),
                mock(AcceptInvitationHelper.class),
                mock(AddAttendeesHelper.class),
                mock(CancelEventHelper.class),
                mock(DeclineInvitationHelper.class),
                mock(GetEventHelper.class),
                mock(GetEventsForUserHelper.class),
                mock(GetNextUpcomingMeetingsHelper.class),
                mock(RemoveAttendeeHelper.class),
                mock(ScheduleMeetingHelper.class),
                mock(TentativeInvitationHelper.class),
                mock(AdaptiveCalendarSyncHandler.class),
                mock(AuthenticateHandler.class),
                mock(CalendarSyncByUserHandler.class),
                mock(DisconnectHandler.class),
                mock(GoogleProviderFactory.class),
                mock(GoogleAttributeStore.class),
                mock(RefreshTokenStore.class),
                invoker,
                invokerConfiguration,
                mock(TelemetryMetrics.class));
    }

    private Object alwaysOnOf(ExtensionResponse response) {
        assertNotNull(response, "The capture-configuration request must always answer");
        assertEquals(ExtensionResponse.Result.SUCCESS, response.getResult(),
                "The request touches no external system, so it can only ever succeed");
        return response.getResponseValue().get(CaptureConfigurationContract.FIELD_ALWAYS_ON);
    }

    // ── attribute parsing ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Constants.readAlwaysOn")
    class ReadAlwaysOn {

        private Map<String, Object> withValue(Object value) {
            Map<String, Object> map = new HashMap<>();
            map.put(Constants.ALWAYS_ON, value);
            return map;
        }

        @Test
        @DisplayName("Studio's native Boolean is honoured in both directions")
        void booleans() {
            assertTrue(Constants.readAlwaysOn(withValue(Boolean.TRUE)));
            assertFalse(Constants.readAlwaysOn(withValue(Boolean.FALSE)));
        }

        @Test
        @DisplayName("A JSON round-trip that yields a string or number is still honoured")
        void stringsAndNumbers() {
            assertTrue(Constants.readAlwaysOn(withValue("true")));
            assertTrue(Constants.readAlwaysOn(withValue("TRUE")));
            assertTrue(Constants.readAlwaysOn(withValue("  true  ")));
            assertFalse(Constants.readAlwaysOn(withValue("false")));
            assertTrue(Constants.readAlwaysOn(withValue(1)));
            assertFalse(Constants.readAlwaysOn(withValue(0)));
        }

        @Test
        @DisplayName("Absent, null, and uninterpretable values all read as off")
        void offByDefault() {
            assertFalse(Constants.readAlwaysOn(null));
            assertFalse(Constants.readAlwaysOn(new HashMap<>()));
            assertFalse(Constants.readAlwaysOn(withValue(null)));
            assertFalse(Constants.readAlwaysOn(withValue("yes")));
            assertFalse(Constants.readAlwaysOn(withValue("on")));
            assertFalse(Constants.readAlwaysOn(withValue("")));
            assertFalse(Constants.readAlwaysOn(withValue(new Object())));
        }

        @Test
        @DisplayName("The attribute key matches the one the Teams extension declares")
        void attributeKeyIsShared() {
            assertEquals("alwaysOn", Constants.ALWAYS_ON,
                    "Both calendar extensions declare the same attribute key so a tenant configures "
                            + "the same thing in the same place regardless of calendar system.");
        }
    }

    // ── the catalog request ───────────────────────────────────────────────

    @Test
    @DisplayName("Reports true when the tenant has opted in")
    void reportsOptIn() {
        when(invoker.getAttributes()).thenReturn(Map.of(Constants.ALWAYS_ON, true));

        assertEquals(Boolean.TRUE, alwaysOnOf(meetingsArea.getCaptureConfiguration()));
    }

    @Test
    @DisplayName("Reports false when the toggle is off — an answer, not silence")
    void reportsOptOut() {
        when(invoker.getAttributes()).thenReturn(Map.of(Constants.ALWAYS_ON, false));

        assertEquals(Boolean.FALSE, alwaysOnOf(meetingsArea.getCaptureConfiguration()));
    }

    @Test
    @DisplayName("Reports false when the attribute was never set")
    void reportsFalseWhenUnset() {
        when(invoker.getAttributes()).thenReturn(Map.of(Constants.SERVICE_URL, "https://ma.example"));

        assertEquals(Boolean.FALSE, alwaysOnOf(meetingsArea.getCaptureConfiguration()));
    }

    @Test
    @DisplayName("Reads the attribute LIVE, so a Studio toggle takes effect on the next call")
    void readsLive() {
        when(invoker.getAttributes())
                .thenReturn(Map.of(Constants.ALWAYS_ON, false))
                .thenReturn(Map.of(Constants.ALWAYS_ON, true));

        assertEquals(Boolean.FALSE, alwaysOnOf(meetingsArea.getCaptureConfiguration()));
        assertEquals(Boolean.TRUE, alwaysOnOf(meetingsArea.getCaptureConfiguration()),
                "The flag must not be cached from a stale attributes snapshot");
    }

    /**
     * The Meeting Agent wrapper calls this during its own attribute validation, so a throw here
     * would fail an admin's Studio save on the OTHER extension. It must degrade instead.
     */
    @Test
    @DisplayName("An unreadable attribute map degrades to false rather than throwing")
    void unreadableAttributesDegrade() {
        when(invoker.getAttributes()).thenThrow(new IllegalStateException("invoker was removed"));

        assertEquals(Boolean.FALSE, alwaysOnOf(meetingsArea.getCaptureConfiguration()));
    }

    @Test
    @DisplayName("A null attribute map degrades to false rather than throwing")
    void nullAttributesDegrade() {
        when(invoker.getAttributes()).thenReturn(null);

        assertEquals(Boolean.FALSE, alwaysOnOf(meetingsArea.getCaptureConfiguration()));
    }

    @Test
    @DisplayName("The contract matches what the Teams extension and the MA wrapper use")
    void contractIsShared() {
        assertEquals("localDomainRequest_fcc54ad0-a246-42fc-97f2-dc740034f2a5",
                CaptureConfigurationContract.CATALOG_REQUEST_ID,
                "Duplicated in krista-global-catalog-kip and -ca; changing it in one place only "
                        + "silently stops the flag reaching Meeting Agent.");
        assertEquals("Always On", CaptureConfigurationContract.FIELD_ALWAYS_ON,
                "The catalog response is keyed by the output field NAME, so this is wire contract.");
    }
}
