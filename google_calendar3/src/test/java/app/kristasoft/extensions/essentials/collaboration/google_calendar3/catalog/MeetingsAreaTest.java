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

import app.krista.extension.authorization.MustAuthorizeException;
import app.krista.extension.executor.ExtensionResponse;
import app.krista.extension.executor.Invoker;
import app.krista.extension.request.RoutingInfo;
import app.krista.extension.request.protos.http.HttpProtocol;
import app.krista.ksdk.invoker.InvokerConfiguration;
import app.krista.ksdk.telemetry.TelemetryMetrics;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.GoogleAttributes;
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
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationException;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.GoogleAttributeStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.RefreshTokenStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.CalendarService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.GoogleServiceBusManager;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarSetupService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.config.LiveGoogleCalendarConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingsAreaTest {

    private static final String INVOKER_ID = "INV_google_calendar";
    private static final String WORKSPACE_ID = "ws_google_calendar";
    private static final String BASE_ROUTING_URL = "https://extension.example/extension/api/routing-id";
    private static final String AUTH_CONTEXT_ID = "authContextId_test";
    private static final String SERVICE_URL = "https://conversation-agent.qa.krista.app";
    private static final String SERVICE_API_KEY = "ma-api-key-test";

    @Mock
    private GoogleProviderFactory googleProviderFactory;
    @Mock
    private CalendarService calendarService;
    @Mock
    private GoogleAttributeStore googleAttributeStore;
    @Mock
    private RefreshTokenStore refreshTokenStore;
    @Mock
    private Invoker invoker;
    @Mock
    private InvokerConfiguration invokerConfiguration;
    @Mock
    private RoutingInfo routingInfo;
    @Mock
    private GoogleServiceBusManager serviceBusManager;
    @Mock
    private CalendarDiscoveryService calendarDiscoveryService;
    @Mock
    private CalendarSyncByUserHandler calendarSyncByUserHandler;

    private MeetingsArea meetingsArea;

    @BeforeEach
    void setUp() {
        meetingsArea = new MeetingsArea(
                calendarService,
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
                calendarSyncByUserHandler,
                mock(DisconnectHandler.class),
                googleProviderFactory,
                googleAttributeStore,
                refreshTokenStore,
                invoker,
                invokerConfiguration,
                serviceBusManager,
                mock(TelemetryMetrics.class),
                calendarDiscoveryService);
    }

    @Test
    void calendarSyncByUserPropagatesMustAuthorizeExceptionForConversationButton() {
        MustAuthorizeException authorizationRequired =
                new MustAuthorizeException("Authorization required");
        when(calendarSyncByUserHandler.execute("user@example.com", 30.0))
                .thenThrow(authorizationRequired);

        MustAuthorizeException thrown = assertThrows(MustAuthorizeException.class,
                () -> meetingsArea.calendarSyncByUser("user@example.com", 30.0));

        assertEquals(authorizationRequired, thrown);
    }

    @Test
    void otherConversationActionsPropagateWrappedMustAuthorizeException() {
        MustAuthorizeException authorizationRequired =
                new MustAuthorizeException("Authorization required");
        when(invoker.getAttributes()).thenReturn(Map.of(Constants.ENABLE_ERROR_HANDLING, false));
        when(calendarService.getEvent("event-1"))
                .thenThrow(new RuntimeException("wrapped", authorizationRequired));

        MustAuthorizeException thrown = assertThrows(MustAuthorizeException.class,
                () -> meetingsArea.getEvent("event-1"));

        assertEquals(authorizationRequired, thrown);
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveConfigurationGoogleCalendar_savesPrivateCredentialsAfterValidation() throws IOException {
        stubRouting();
        stubInvokerServiceUrlSave(true);
        GoogleProvider googleProvider = stubConnectionSuccess();

        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                "mode", "Private",
                GoogleAttributes.EMAIL, "admin@example.com",
                GoogleAttributes.CLIENT_ID, "client-id.apps.googleusercontent.com",
                GoogleAttributes.CLIENT_SECRET, "012345678901234567890123",
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY));

        assertEquals("saved successfully", response.getResponseValue().get("Response"));

        ArgumentCaptor<GoogleAttributes> attributesCaptor = ArgumentCaptor.forClass(GoogleAttributes.class);
        verify(googleProviderFactory).createAttributes(attributesCaptor.capture());
        GoogleAttributes savedAttributes = attributesCaptor.getValue();
        assertEquals(Constants.PRIVATE, savedAttributes.getAuthType());
        assertEquals("admin@example.com", savedAttributes.getMailId());
        assertEquals("client-id.apps.googleusercontent.com", savedAttributes.getClientId());
        assertEquals("012345678901234567890123", savedAttributes.getClientSecret());
        verify(googleProviderFactory).create(AUTH_CONTEXT_ID);
        verify(googleProvider).testConnection();
        verify(googleAttributeStore).save(same(savedAttributes), eq(INVOKER_ID));
        verify(googleAttributeStore).remove(AUTH_CONTEXT_ID);

        ArgumentCaptor<Map<String, Object>> invokerAttributesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(invokerConfiguration).saveChanges(
                eq(WORKSPACE_ID),
                eq(INVOKER_ID),
                invokerAttributesCaptor.capture(),
                eq("live"),
                eq(false));
        Map<String, Object> savedInvokerAttributes = invokerAttributesCaptor.getValue();
        assertEquals(SERVICE_URL, savedInvokerAttributes.get(Constants.SERVICE_URL));
        assertEquals(SERVICE_API_KEY, savedInvokerAttributes.get(Constants.SERVICE_API_KEY));
        assertEquals(true, savedInvokerAttributes.get(Constants.ENABLE_ERROR_HANDLING));
        assertFalse(savedInvokerAttributes.containsKey(GoogleAttributes.CLIENT_SECRET));
        verify(serviceBusManager).reconfigureEsb(SERVICE_URL, SERVICE_API_KEY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveConfigurationGoogleCalendar_persistsDelegationAttributes() throws IOException {
        stubRouting();
        stubInvokerServiceUrlSave(true);
        stubConnectionSuccess();

        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                "mode", Constants.PRIVATE,
                GoogleAttributes.EMAIL, "admin@example.com",
                GoogleAttributes.CLIENT_ID, "client-id.apps.googleusercontent.com",
                GoogleAttributes.CLIENT_SECRET, "012345678901234567890123",
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY,
                Constants.SERVICE_ACCOUNT_KEY, "{\"type\":\"service_account\"}",
                Constants.TENANT_ADMIN_EMAIL, "workspace-admin@example.com",
                Constants.TENANT_DOMAIN, "example.com",
                Constants.ALWAYS_ON, false));

        assertEquals("saved successfully", response.getResponseValue().get("Response"));

        ArgumentCaptor<Map<String, Object>> invokerAttributesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(invokerConfiguration).saveChanges(
                eq(WORKSPACE_ID), eq(INVOKER_ID), invokerAttributesCaptor.capture(), eq("live"), eq(false));
        Map<String, Object> savedInvokerAttributes = invokerAttributesCaptor.getValue();
        assertEquals("{\"type\":\"service_account\"}",
                savedInvokerAttributes.get(Constants.SERVICE_ACCOUNT_KEY));
        assertEquals("workspace-admin@example.com", savedInvokerAttributes.get(Constants.TENANT_ADMIN_EMAIL));
        assertEquals("example.com", savedInvokerAttributes.get(Constants.TENANT_DOMAIN));
        assertEquals(false, savedInvokerAttributes.get(Constants.ALWAYS_ON));
        assertEquals(SERVICE_URL, savedInvokerAttributes.get(Constants.SERVICE_URL));
        assertEquals(SERVICE_API_KEY, savedInvokerAttributes.get(Constants.SERVICE_API_KEY));
        assertFalse(savedInvokerAttributes.containsKey(GoogleAttributes.CLIENT_SECRET));
    }

    @Test
    void saveConfigurationGoogleCalendar_alwaysOnDoesNotStartUserOAuth() throws DomainWideDelegationException {
        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        lenient().when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        stubInvokerServiceUrlSave(true);
        GoogleCalendarSetupService setup = mock(GoogleCalendarSetupService.class);
        LiveGoogleCalendarConfiguration live = new LiveGoogleCalendarConfiguration(invoker);
        meetingsArea.setSetupServices(live, setup);
        org.mockito.Mockito.doAnswer(ignored -> {
            assertTrue(Constants.readAlwaysOn(live.attributes()),
                    "Queued CA ESB commands must see committed settings before consumers rebind");
            assertEquals("workspace-admin@example.com", live.attributes().get(Constants.TENANT_ADMIN_EMAIL));
            return null;
        }).when(serviceBusManager).reconfigureEsb(SERVICE_URL, SERVICE_API_KEY);

        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY,
                Constants.SERVICE_ACCOUNT_KEY, "{\"type\":\"service_account\"}",
                Constants.TENANT_ADMIN_EMAIL, "workspace-admin@example.com",
                Constants.TENANT_DOMAIN, "example.com",
                Constants.ALWAYS_ON, true));

        assertEquals("saved successfully", response.getResponseValue().get("Response"));
        verifyNoInteractions(googleProviderFactory, refreshTokenStore);
        verifyNothingPersistedInStore();
        verify(serviceBusManager).reconfigureEsb(SERVICE_URL, SERVICE_API_KEY);
        var order = org.mockito.Mockito.inOrder(invokerConfiguration, serviceBusManager, setup);
        order.verify(invokerConfiguration).saveChanges(eq(WORKSPACE_ID), eq(INVOKER_ID), any(), eq("live"), eq(false));
        order.verify(serviceBusManager).reconfigureEsb(SERVICE_URL, SERVICE_API_KEY);
        order.verify(setup).applySavedConfiguration(org.mockito.ArgumentMatchers.argThat(saved ->
                Constants.readAlwaysOn(saved) && "workspace-admin@example.com".equals(saved.get(Constants.TENANT_ADMIN_EMAIL))));
    }

    @Test
    void failedAlwaysOnSaveDoesNotActivateRuntimeConfigurationOrSync() {
        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        lenient().when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        stubInvokerServiceUrlSave(false);
        GoogleCalendarSetupService setup = mock(GoogleCalendarSetupService.class);
        LiveGoogleCalendarConfiguration live = new LiveGoogleCalendarConfiguration(invoker);
        meetingsArea.setSetupServices(live, setup);
        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                Constants.SERVICE_URL, SERVICE_URL, Constants.SERVICE_API_KEY, SERVICE_API_KEY,
                Constants.SERVICE_ACCOUNT_KEY, "configured-account", Constants.TENANT_ADMIN_EMAIL, "admin@example.com",
                Constants.ALWAYS_ON, true));
        assertEquals("failed to save", response.getResponseValue().get("Response"));
        verifyNoInteractions(setup, serviceBusManager);
        assertFalse(Constants.readAlwaysOn(live.attributes()));
    }

    @Test
    void saveConfigurationGoogleCalendar_alwaysOnDwdMissingRaisesConversationAuthorization()
            throws DomainWideDelegationException {
        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        lenient().when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        doThrow(new DomainWideDelegationException("DWD is not authorised", null))
                .when(calendarDiscoveryService).validateDelegatedAccessIfAlwaysOn(any());

        MustAuthorizeException thrown = assertThrows(MustAuthorizeException.class,
                () -> meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                        Constants.SERVICE_URL, SERVICE_URL,
                        Constants.SERVICE_API_KEY, SERVICE_API_KEY,
                        Constants.SERVICE_ACCOUNT_KEY, "{\"type\":\"service_account\"}",
                        Constants.TENANT_ADMIN_EMAIL, "workspace-admin@example.com",
                        Constants.TENANT_DOMAIN, "example.com",
                        Constants.ALWAYS_ON, true)));

        assertTrue(thrown.getDetails().stream()
                .anyMatch(field -> Constants.DWD_SETUP_FIELD.equals(field.getName())
                        && "true".equals(String.valueOf(field.getValue()))));
        verifyNoInteractions(invokerConfiguration, googleProviderFactory, refreshTokenStore);
        verifyNothingPersistedInStore();
    }

    @Test
    void saveConfigurationGoogleCalendar_requiresDelegationCredentialsWhenAlwaysOnIsEnabled() {
        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                "mode", Constants.PUBLIC,
                GoogleAttributes.EMAIL, "admin@example.com",
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY,
                Constants.ALWAYS_ON, true));

        assertEquals("Missing required field(s): serviceAccountKey, tenantAdminEmail",
                response.getResponseValue().get("Response"));
        verifyNoInteractions(googleProviderFactory, invokerConfiguration);
        verifyNothingPersistedInStore();
    }

    @Test
    void saveConfigurationGoogleCalendar_doesNotAuthenticateAgainWhenSameConfigAlreadyHasToken()
            throws IOException {
        stubRouting();
        stubInvokerServiceUrlSave(true);
        GoogleProvider savedProvider = mock(GoogleProvider.class);
        GoogleAttributes savedAttributes = new GoogleAttributes(
                "client-id.apps.googleusercontent.com",
                "012345678901234567890123",
                "admin@example.com",
                Constants.PRIVATE,
                BASE_ROUTING_URL);
        when(googleAttributeStore.load(INVOKER_ID)).thenReturn(savedAttributes);
        when(refreshTokenStore.get("admin@example.com_client-id.apps.googleusercontent.com_Private"))
                .thenReturn("refresh-token");
        when(googleProviderFactory.createForInvoker(INVOKER_ID)).thenReturn(savedProvider);

        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                "mode", Constants.PRIVATE,
                GoogleAttributes.EMAIL, "admin@example.com",
                GoogleAttributes.CLIENT_ID, "client-id.apps.googleusercontent.com",
                GoogleAttributes.CLIENT_SECRET, "012345678901234567890123",
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY));

        assertEquals("saved successfully", response.getResponseValue().get("Response"));
        verify(savedProvider).testConnection();
        verify(googleProviderFactory, never()).createAttributes(any(GoogleAttributes.class));
        verify(googleProviderFactory, never()).create(anyString());
        verify(googleAttributeStore, never()).save(any(GoogleAttributes.class), anyString());
        verify(googleAttributeStore, never()).remove(anyString());
        verify(serviceBusManager).reconfigureEsb(SERVICE_URL, SERVICE_API_KEY);
    }

    @Test
    void saveConfigurationGoogleCalendar_revokedStoredTokenThrowsMustAuthorizeForConversationResume()
            throws IOException {
        stubRouting();
        GoogleAttributes savedAttributes = new GoogleAttributes(
                "client-id.apps.googleusercontent.com",
                "012345678901234567890123",
                "admin@example.com",
                Constants.PRIVATE,
                BASE_ROUTING_URL);
        when(googleAttributeStore.load(INVOKER_ID)).thenReturn(savedAttributes);
        when(refreshTokenStore.get("admin@example.com_client-id.apps.googleusercontent.com_Private"))
                .thenReturn("revoked-refresh-token");

        MustAuthorizeException revokedGrant = new MustAuthorizeException("Refresh Token Expired");
        GoogleProvider savedProvider = mock(GoogleProvider.class);
        when(googleProviderFactory.createForInvoker(INVOKER_ID)).thenReturn(savedProvider);
        doThrow(revokedGrant).when(savedProvider).testConnection();

        MustAuthorizeException conversationAuthorization =
                new MustAuthorizeException("Authorization required");
        GoogleProvider temporaryProvider = mock(GoogleProvider.class);
        when(googleProviderFactory.createAttributes(any(GoogleAttributes.class)))
                .thenReturn(AUTH_CONTEXT_ID);
        when(googleProviderFactory.create(AUTH_CONTEXT_ID)).thenReturn(temporaryProvider);
        doThrow(conversationAuthorization).when(temporaryProvider).testConnection();

        MustAuthorizeException thrown = assertThrows(MustAuthorizeException.class,
                () -> meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                        "mode", Constants.PRIVATE,
                        GoogleAttributes.EMAIL, "admin@example.com",
                        GoogleAttributes.CLIENT_ID, "client-id.apps.googleusercontent.com",
                        GoogleAttributes.CLIENT_SECRET, "012345678901234567890123",
                        Constants.SERVICE_URL, SERVICE_URL,
                        Constants.SERVICE_API_KEY, SERVICE_API_KEY)));

        assertEquals(conversationAuthorization, thrown);
        verify(googleProviderFactory).createForInvoker(INVOKER_ID);
        verify(googleProviderFactory).createAttributes(any(GoogleAttributes.class));
        verify(googleAttributeStore, never()).remove(AUTH_CONTEXT_ID);
        verifyNoInteractions(serviceBusManager);
    }

    // ── completing the request from the saved configuration (KIM-492) ────────

    /**
     * Service details configured in Studio, OAuth client saved by an earlier run of this request,
     * and now a NEW email arrives on its own: the saved client id/secret/mode are used, the email is
     * added, and the user OAuth flow runs for it — the same authentication a full form gets.
     */
    @Test
    void saveConfigurationGoogleCalendar_emailOnlyUsesSavedDetailsAndAuthenticates() throws IOException {
        stubRouting();
        when(invoker.getAttributes()).thenReturn(Map.of(
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY,
                Constants.ENABLE_ERROR_HANDLING, true));
        when(googleAttributeStore.load(INVOKER_ID)).thenReturn(new GoogleAttributes(
                "client-id.apps.googleusercontent.com",
                "012345678901234567890123",
                "old-admin@example.com",
                Constants.PRIVATE,
                BASE_ROUTING_URL));
        MustAuthorizeException conversationAuthorization = new MustAuthorizeException("Authorization required");
        GoogleProvider temporaryProvider = mock(GoogleProvider.class);
        when(googleProviderFactory.createAttributes(any(GoogleAttributes.class))).thenReturn(AUTH_CONTEXT_ID);
        when(googleProviderFactory.create(AUTH_CONTEXT_ID)).thenReturn(temporaryProvider);
        doThrow(conversationAuthorization).when(temporaryProvider).testConnection();

        MustAuthorizeException thrown = assertThrows(MustAuthorizeException.class,
                () -> meetingsArea.saveConfigurationGoogleCalendar(
                        Map.of(GoogleAttributes.EMAIL, "new-admin@example.com")));

        assertEquals(conversationAuthorization, thrown);
        ArgumentCaptor<GoogleAttributes> usedCaptor = ArgumentCaptor.forClass(GoogleAttributes.class);
        verify(googleProviderFactory).createAttributes(usedCaptor.capture());
        GoogleAttributes used = usedCaptor.getValue();
        assertEquals("new-admin@example.com", used.getMailId(), "the email comes from the request");
        assertEquals(Constants.PRIVATE, used.getAuthType(), "the mode comes from the saved configuration");
        assertEquals("client-id.apps.googleusercontent.com", used.getClientId());
        assertEquals("012345678901234567890123", used.getClientSecret());
        // A different email is a different grant: the stored-token shortcut must not apply.
        verify(googleProviderFactory, never()).createForInvoker(anyString());
        verify(googleAttributeStore, never()).remove(AUTH_CONTEXT_ID);
        verifyNoInteractions(invokerConfiguration, serviceBusManager);
    }

    /** The email-only run, once the OAuth grant exists, saves everything as a full form would. */
    @Test
    @SuppressWarnings("unchecked")
    void saveConfigurationGoogleCalendar_emailOnlyRunSavesTheCompletedConfiguration() throws IOException {
        stubRouting();
        when(invoker.getAttributes()).thenReturn(Map.of(
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY));
        when(googleAttributeStore.load(INVOKER_ID)).thenReturn(new GoogleAttributes(
                null, null, "old-admin@example.com", Constants.PUBLIC, BASE_ROUTING_URL));
        GoogleProvider googleProvider = stubConnectionSuccess();
        GoogleCalendarSetupService setup = mock(GoogleCalendarSetupService.class);
        meetingsArea.setSetupServices(new LiveGoogleCalendarConfiguration(invoker), setup);

        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(
                Map.of(GoogleAttributes.EMAIL, "new-admin@example.com"));

        assertEquals("saved successfully", response.getResponseValue().get("Response"));
        verify(googleProvider).testConnection();
        ArgumentCaptor<GoogleAttributes> savedCaptor = ArgumentCaptor.forClass(GoogleAttributes.class);
        verify(googleAttributeStore).save(savedCaptor.capture(), eq(INVOKER_ID));
        assertEquals("new-admin@example.com", savedCaptor.getValue().getMailId());
        assertEquals(Constants.PUBLIC, savedCaptor.getValue().getAuthType());
        // Nothing invoker-level was supplied, so nothing invoker-level is rewritten or rebound.
        verifyNoInteractions(invokerConfiguration, serviceBusManager);
        verify(setup).applySavedConfiguration(org.mockito.ArgumentMatchers.argThat(saved ->
                SERVICE_URL.equals(saved.get(Constants.SERVICE_URL))
                        && !saved.containsKey(GoogleAttributes.EMAIL)
                        && !saved.containsKey(GoogleAttributes.CLIENT_SECRET)
                        && !saved.containsKey("mode")));
    }

    @Test
    void failedOAuthAttributeSaveDoesNotActivateSetupOrSaveInvokerCandidates() {
        stubRouting();
        when(invoker.getAttributes()).thenReturn(Map.of(Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY));
        when(googleProviderFactory.createAttributes(any(GoogleAttributes.class))).thenReturn(AUTH_CONTEXT_ID);
        when(googleProviderFactory.create(AUTH_CONTEXT_ID)).thenReturn(mock(GoogleProvider.class));
        when(googleAttributeStore.save(any(GoogleAttributes.class), eq(INVOKER_ID))).thenReturn(false);
        GoogleCalendarSetupService setup = mock(GoogleCalendarSetupService.class);
        LiveGoogleCalendarConfiguration live = new LiveGoogleCalendarConfiguration(invoker);
        meetingsArea.setSetupServices(live, setup);

        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                "mode", Constants.PRIVATE, GoogleAttributes.EMAIL, "admin@example.com",
                GoogleAttributes.CLIENT_ID, "client-id.apps.googleusercontent.com",
                GoogleAttributes.CLIENT_SECRET, "012345678901234567890123",
                Constants.SERVICE_URL, "https://candidate.example", Constants.SERVICE_API_KEY, "candidate-key"));

        assertEquals("failed to save", response.getResponseValue().get("Response"));
        verifyNoInteractions(setup, invokerConfiguration, serviceBusManager);
        assertEquals(SERVICE_URL, live.attributes().get(Constants.SERVICE_URL));
        assertFalse(live.attributes().containsKey(GoogleAttributes.CLIENT_SECRET));
        verify(googleAttributeStore).remove(AUTH_CONTEXT_ID);
    }

    /** Untouched form fields arrive blank; blank must mean "keep what is saved", not "erase it". */
    @Test
    @SuppressWarnings("unchecked")
    void saveConfigurationGoogleCalendar_blankSuppliedFieldsKeepTheSavedValues() throws IOException {
        stubRouting();
        stubInvokerServiceUrlSave(true);
        when(googleAttributeStore.load(INVOKER_ID)).thenReturn(new GoogleAttributes(
                "client-id.apps.googleusercontent.com",
                "012345678901234567890123",
                "admin@example.com",
                Constants.PRIVATE,
                BASE_ROUTING_URL));
        GoogleProvider googleProvider = stubConnectionSuccess();
        Map<String, Object> form = new HashMap<>();
        form.put("mode", "");
        form.put(GoogleAttributes.CLIENT_ID, "   ");
        form.put(GoogleAttributes.CLIENT_SECRET, null);
        form.put(GoogleAttributes.EMAIL, "admin@example.com");
        form.put(Constants.SERVICE_URL, SERVICE_URL);
        form.put(Constants.SERVICE_API_KEY, SERVICE_API_KEY);

        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(form);

        assertEquals("saved successfully", response.getResponseValue().get("Response"));
        verify(googleProvider).testConnection();
        ArgumentCaptor<GoogleAttributes> savedCaptor = ArgumentCaptor.forClass(GoogleAttributes.class);
        verify(googleAttributeStore).save(savedCaptor.capture(), eq(INVOKER_ID));
        assertEquals(Constants.PRIVATE, savedCaptor.getValue().getAuthType());
        assertEquals("client-id.apps.googleusercontent.com", savedCaptor.getValue().getClientId());
        assertEquals("012345678901234567890123", savedCaptor.getValue().getClientSecret());
        verify(serviceBusManager).reconfigureEsb(SERVICE_URL, SERVICE_API_KEY);
    }

    /** Nothing saved anywhere and only the email supplied: refused before Google, naming the gaps. */
    @Test
    void saveConfigurationGoogleCalendar_emailOnlyWithNothingSavedIsRefusedBeforeConnecting() {
        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(
                Map.of(GoogleAttributes.EMAIL, "admin@example.com"));

        assertEquals("Missing required field(s): serviceUrl, serviceApiKey, mode",
                response.getResponseValue().get("Response"));
        verifyNoInteractions(googleProviderFactory, invokerConfiguration);
        verifyNothingPersistedInStore();
    }

    /**
     * An untouched toggle reads false. On an invoker saved with Always On ON that must not drop
     * the tenant from delegation back to per-user OAuth; turning it off is the Studio toggle's job.
     */
    @Test
    @SuppressWarnings("unchecked")
    void saveConfigurationGoogleCalendar_alwaysOnFalseDoesNotSwitchASavedAlwaysOnOff() throws Exception {
        // No routing stub: the Always On path never builds OAuth attributes.
        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        lenient().when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        Map<String, Object> saved = new HashMap<>();
        saved.put(Constants.SERVICE_URL, SERVICE_URL);
        saved.put(Constants.SERVICE_API_KEY, SERVICE_API_KEY);
        saved.put(Constants.ALWAYS_ON, true);
        saved.put(Constants.SERVICE_ACCOUNT_KEY, "{\"client_id\":\"123456789012\"}");
        saved.put(Constants.TENANT_ADMIN_EMAIL, "admin@example.com");
        when(invoker.getAttributes()).thenReturn(saved);
        when(invokerConfiguration.saveChanges(eq(WORKSPACE_ID), eq(INVOKER_ID), any(), eq("live"), eq(false)))
                .thenReturn(true);

        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                GoogleAttributes.EMAIL, "someone@example.com",
                Constants.ALWAYS_ON, false));

        assertEquals("saved successfully (Always On stays on; use the Studio toggle to turn it off)",
                response.getResponseValue().get("Response"));
        verify(calendarDiscoveryService).validateDelegatedAccessIfAlwaysOn(any());
        verifyNoInteractions(googleProviderFactory);
        ArgumentCaptor<Map<String, Object>> invokerAttributesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(invokerConfiguration).saveChanges(
                eq(WORKSPACE_ID), eq(INVOKER_ID), invokerAttributesCaptor.capture(), eq("live"), eq(false));
        assertEquals(true, invokerAttributesCaptor.getValue().get(Constants.ALWAYS_ON));
    }

    @Test
    void saveConfigurationGoogleCalendar_allowsPublicModeWithoutClientCredentials() {
        stubRouting();
        stubInvokerServiceUrlSave(true);
        stubConnectionSuccess();

        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                "mode", "public",
                GoogleAttributes.EMAIL, "admin@example.com",
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY));

        assertEquals("saved successfully", response.getResponseValue().get("Response"));

        ArgumentCaptor<GoogleAttributes> attributesCaptor = ArgumentCaptor.forClass(GoogleAttributes.class);
        verify(googleProviderFactory).createAttributes(attributesCaptor.capture());
        GoogleAttributes savedAttributes = attributesCaptor.getValue();
        assertEquals(Constants.PUBLIC, savedAttributes.getAuthType());
        assertEquals("admin@example.com", savedAttributes.getMailId());
        verify(googleAttributeStore).save(same(savedAttributes), eq(INVOKER_ID));
    }

    @Test
    void saveConfigurationGoogleCalendar_acceptsAuthTypeFallbackForCompatibility() {
        stubRouting();
        stubInvokerServiceUrlSave(true);
        stubConnectionSuccess();

        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                Constants.AUTH_TYPE, Constants.PRIVATE,
                GoogleAttributes.EMAIL, "admin@example.com",
                GoogleAttributes.CLIENT_ID, "client-id.apps.googleusercontent.com",
                GoogleAttributes.CLIENT_SECRET, "012345678901234567890123",
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY));

        assertEquals("saved successfully", response.getResponseValue().get("Response"));

        ArgumentCaptor<GoogleAttributes> attributesCaptor = ArgumentCaptor.forClass(GoogleAttributes.class);
        verify(googleProviderFactory).createAttributes(attributesCaptor.capture());
        assertEquals(Constants.PRIVATE, attributesCaptor.getValue().getAuthType());
    }

    @Test
    void saveConfigurationGoogleCalendar_acceptsPickOneAndSecuredAttributeObjects() {
        stubRouting();
        stubInvokerServiceUrlSave(true);
        stubConnectionSuccess();

        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                "mode", Map.of("value", Constants.PRIVATE),
                GoogleAttributes.EMAIL, Map.of("value", "admin@example.com"),
                GoogleAttributes.CLIENT_ID, Map.of("value", "client-id.apps.googleusercontent.com"),
                GoogleAttributes.CLIENT_SECRET, Map.of("value", "012345678901234567890123"),
                Constants.SERVICE_URL, Map.of("value", SERVICE_URL),
                Constants.SERVICE_API_KEY, Map.of("value", SERVICE_API_KEY)));

        assertEquals("saved successfully", response.getResponseValue().get("Response"));

        ArgumentCaptor<GoogleAttributes> attributesCaptor = ArgumentCaptor.forClass(GoogleAttributes.class);
        verify(googleProviderFactory).createAttributes(attributesCaptor.capture());
        GoogleAttributes savedAttributes = attributesCaptor.getValue();
        assertEquals(Constants.PRIVATE, savedAttributes.getAuthType());
        assertEquals("admin@example.com", savedAttributes.getMailId());
        assertEquals("client-id.apps.googleusercontent.com", savedAttributes.getClientId());
        assertEquals("012345678901234567890123", savedAttributes.getClientSecret());
    }

    @Test
    void saveConfigurationGoogleCalendar_returnsFailureWhenServiceUrlSaveFails() {
        stubRouting();
        stubInvokerServiceUrlSave(false);
        stubConnectionSuccess();

        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                "mode", Constants.PUBLIC,
                GoogleAttributes.EMAIL, "admin@example.com",
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY));

        assertEquals("failed to save", response.getResponseValue().get("Response"));
        verify(googleAttributeStore).save(any(GoogleAttributes.class), eq(INVOKER_ID));
        verifyNoInteractions(serviceBusManager);
    }

    @Test
    void saveConfigurationGoogleCalendar_keepsSuccessfulSaveWhenImmediateEsbReconfigureFails() {
        stubRouting();
        stubInvokerServiceUrlSave(true);
        stubConnectionSuccess();
        doThrow(new IllegalStateException("AMQ unavailable"))
                .when(serviceBusManager).reconfigureEsb(SERVICE_URL, SERVICE_API_KEY);

        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                "mode", Constants.PUBLIC,
                GoogleAttributes.EMAIL, "admin@example.com",
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY));

        assertEquals("saved successfully", response.getResponseValue().get("Response"));
        verify(serviceBusManager).reconfigureEsb(SERVICE_URL, SERVICE_API_KEY);
    }

    @Test
    void saveConfigurationGoogleCalendar_rejectsMissingModeBeforeConnecting() {
        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                GoogleAttributes.EMAIL, "admin@example.com",
                GoogleAttributes.CLIENT_ID, "client-id.apps.googleusercontent.com",
                GoogleAttributes.CLIENT_SECRET, "012345678901234567890123",
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY));

        assertEquals("Missing required field(s): mode", response.getResponseValue().get("Response"));
        verifyNoInteractions(googleProviderFactory, invokerConfiguration);
        verifyNothingPersistedInStore();
    }

    @Test
    void saveConfigurationGoogleCalendar_rejectsMissingServiceUrlBeforeConnecting() {
        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                "mode", Constants.PUBLIC,
                GoogleAttributes.EMAIL, "admin@example.com",
                Constants.SERVICE_API_KEY, SERVICE_API_KEY));

        assertEquals("Missing required field(s): serviceUrl", response.getResponseValue().get("Response"));
        verifyNoInteractions(googleProviderFactory, invokerConfiguration);
        verifyNothingPersistedInStore();
    }

    @Test
    void saveConfigurationGoogleCalendar_rejectsMissingServiceApiKeyBeforeConnecting() {
        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                "mode", Constants.PUBLIC,
                GoogleAttributes.EMAIL, "admin@example.com",
                Constants.SERVICE_URL, SERVICE_URL));

        assertEquals("Missing required field(s): serviceApiKey", response.getResponseValue().get("Response"));
        verifyNoInteractions(googleProviderFactory, invokerConfiguration);
        verifyNothingPersistedInStore();
    }

    @Test
    void saveConfigurationGoogleCalendar_rejectsMissingPrivateCredentialsBeforeConnecting() {
        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                "mode", Constants.PRIVATE,
                GoogleAttributes.EMAIL, "admin@example.com",
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY));

        assertEquals("Missing required field(s): clientId, clientSecret", response.getResponseValue().get("Response"));
        verifyNoInteractions(googleProviderFactory, invokerConfiguration);
        verifyNothingPersistedInStore();
    }

    @Test
    void saveConfigurationGoogleCalendar_rejectsInvalidEmailBeforeConnecting() {
        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                "mode", Constants.PUBLIC,
                GoogleAttributes.EMAIL, "not-an-email",
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY));

        assertEquals("Invalid email format: not-an-email", response.getResponseValue().get("Response"));
        verifyNoInteractions(googleProviderFactory, invokerConfiguration);
        verifyNothingPersistedInStore();
    }

    @Test
    void saveConfigurationGoogleCalendar_keepsTemporaryAuthContextWhenAuthorizationIsRequired() throws IOException {
        stubRouting();
        GoogleProvider googleProvider = mock(GoogleProvider.class);
        when(googleProviderFactory.createAttributes(any(GoogleAttributes.class))).thenReturn(AUTH_CONTEXT_ID);
        when(googleProviderFactory.create(AUTH_CONTEXT_ID)).thenReturn(googleProvider);
        doThrow(new MustAuthorizeException("Authorization required")).when(googleProvider).testConnection();

        MustAuthorizeException thrown = assertThrows(MustAuthorizeException.class,
                () -> meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                        "mode", Constants.PUBLIC,
                        GoogleAttributes.EMAIL, "admin@example.com",
                        Constants.SERVICE_URL, SERVICE_URL,
                        Constants.SERVICE_API_KEY, SERVICE_API_KEY)));

        assertNotNull(thrown);
        verify(googleAttributeStore, never()).save(any(GoogleAttributes.class), anyString());
        verify(googleAttributeStore, never()).remove(AUTH_CONTEXT_ID);
    }

    @Test
    void saveConfigurationGoogleCalendar_removesTemporaryAuthContextWhenConnectionFails() throws IOException {
        stubRouting();
        GoogleProvider googleProvider = mock(GoogleProvider.class);
        when(googleProviderFactory.createAttributes(any(GoogleAttributes.class))).thenReturn(AUTH_CONTEXT_ID);
        when(googleProviderFactory.create(AUTH_CONTEXT_ID)).thenReturn(googleProvider);
        doThrow(new IOException("connection failed")).when(googleProvider).testConnection();

        ExtensionResponse response = meetingsArea.saveConfigurationGoogleCalendar(Map.of(
                "mode", Constants.PUBLIC,
                GoogleAttributes.EMAIL, "admin@example.com",
                Constants.SERVICE_URL, SERVICE_URL,
                Constants.SERVICE_API_KEY, SERVICE_API_KEY));

        assertEquals("connection failed", response.getResponseValue().get("Response"));
        verify(googleAttributeStore, never()).save(any(GoogleAttributes.class), anyString());
        verify(googleAttributeStore).remove(AUTH_CONTEXT_ID);
    }

    /**
     * The store is READ on every run — the request completes its form from the OAuth configuration
     * an earlier run saved — so "nothing happened" means nothing was written or removed, not that
     * the store was never touched.
     */
    private void verifyNothingPersistedInStore() {
        verify(googleAttributeStore, never()).save(any(GoogleAttributes.class), anyString());
        verify(googleAttributeStore, never()).save(any(GoogleAttributes.class));
        verify(googleAttributeStore, never()).remove(anyString());
    }

    private void stubRouting() {
        when(invoker.getRoutingInfo()).thenReturn(routingInfo);
        when(routingInfo.getRoutingURL(HttpProtocol.PROTOCOL_NAME, RoutingInfo.Type.APPLIANCE))
                .thenReturn(BASE_ROUTING_URL);
        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        lenient().when(invoker.getWorkspaceId()).thenReturn(WORKSPACE_ID);
    }

    private void stubInvokerServiceUrlSave(boolean isSaved) {
        when(invoker.getAttributes()).thenReturn(Map.of(Constants.ENABLE_ERROR_HANDLING, true));
        when(invokerConfiguration.saveChanges(eq(WORKSPACE_ID), eq(INVOKER_ID), any(), eq("live"), eq(false)))
                .thenReturn(isSaved);
    }

    private GoogleProvider stubConnectionSuccess() {
        GoogleProvider googleProvider = mock(GoogleProvider.class);
        when(googleProviderFactory.createAttributes(any(GoogleAttributes.class))).thenReturn(AUTH_CONTEXT_ID);
        when(googleProviderFactory.create(AUTH_CONTEXT_ID)).thenReturn(googleProvider);
        when(googleAttributeStore.save(any(GoogleAttributes.class), eq(INVOKER_ID))).thenReturn(true);
        return googleProvider;
    }
}
