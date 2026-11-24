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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service;

import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.TrackMeetingEventStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarSyncTelemetryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarEventFetcher;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleEventToMeetingDtoMapper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleCalendarAuthenticationService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.MeetingKeyResolver;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.RegisteredSeatStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.TenantUserDirectory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.SendEventsToMAService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.testutil.TestCaGateway;

/**
 * KIM-474 — the safety story for every tenant that has NOT enabled Always On.
 *
 * <p>Tenant-wide discovery is additive by construction, but "by construction" is worth exactly
 * nothing without something that fails when the construction slips. Two kinds of proof here:
 *
 * <ul>
 *   <li><strong>Behavioural</strong> — with the flag off, the sync path resolves the same users,
 *       through the same collaborators, and never reaches the delegation credential or the Admin
 *       SDK. Not "the result matches" but "the new machinery was not touched": a service-account key
 *       read on the off path is a latent outage for tenants who have no key at all.</li>
 *   <li><strong>Structural</strong> — the Always On flag may be read in exactly three places, and
 *       the delegation credential referenced in exactly four. This is what stops the pattern this
 *       change was designed around from eroding: an {@code if (alwaysOn)} added to a sixth service
 *       months from now, on a path nobody re-tests, quietly reading the wrong calendar with the
 *       wrong credential.</li>
 * </ul>
 */
@DisplayName("Always On OFF is unchanged (KIM-474)")
class AlwaysOnOffUnchangedTest {

    // ── behavioural ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("The sync path with the flag off")
    class SyncPath {

        private SendEventsToMAService serviceWith(CalendarDiscoveryService discovery,
                                                  CalendarSyncTelemetryService telemetry) {
            Invoker invoker = mock(Invoker.class);
            return new SendEventsToMAService(
                    mock(GoogleProviderFactory.class),
                    mock(GoogleCalendarEventFetcher.class),
                    new GoogleEventToMeetingDtoMapper(),
                    invoker,
                    mock(MeetingKeyResolver.class),
                    mock(TrackMeetingEventStore.class),
                    telemetry,
                    mock(UserKeyIndexStore.class),
                    discovery,
                    TestCaGateway.unusedGateway());
        }

        @Test
        @DisplayName("An empty consented set aborts the sweep exactly as it always did")
        void emptySetAborts() {
            CalendarDiscoveryService discovery = mock(CalendarDiscoveryService.class);
            when(discovery.discover("full_sync"))
                    .thenReturn(new CalendarDiscoveryService.UserSet(CONSENTED_USERS, List.of(), null));
            CalendarSyncTelemetryService telemetry = mock(CalendarSyncTelemetryService.class);

            serviceWith(discovery, telemetry).sendEventsToMA(7);

            verify(telemetry).recordSyncSuccess(any());
            verify(telemetry, never()).recordUserProcessing(anyString(), org.mockito.ArgumentMatchers.anyInt(), anyLong());
        }

        /**
         * The gate on the single-user sync used to be {@code isUserAuthenticated}. It is now
         * {@code isSyncable}, whose off-path answer is that same call — so an unauthenticated user
         * must still be refused, and refused before any Google client is built.
         */
        @Test
        @DisplayName("An unauthenticated user is still refused, before any Google call")
        void unauthenticatedUserStillRefused() {
            CalendarDiscoveryService discovery = mock(CalendarDiscoveryService.class);
            when(discovery.isSyncable("stranger@acme.example")).thenReturn(false);
            CalendarSyncTelemetryService telemetry = mock(CalendarSyncTelemetryService.class);
            GoogleProviderFactory factory = mock(GoogleProviderFactory.class);

            SendEventsToMAService service = new SendEventsToMAService(factory,
                    mock(GoogleCalendarEventFetcher.class), new GoogleEventToMeetingDtoMapper(),
                    mock(Invoker.class), mock(MeetingKeyResolver.class), mock(TrackMeetingEventStore.class),
                    telemetry, mock(UserKeyIndexStore.class), discovery,
                    TestCaGateway.unusedGateway());

            service.sendEventsToMAByUser("stranger@acme.example", 7);

            verify(telemetry).recordSyncFailure(any(), any(SecurityException.class));
            verifyNoInteractions(factory);
            verify(discovery, never()).calendarClientFor(anyString(), any());
        }
    }

    // ── the real off path ─────────────────────────────────────────────────

    /**
     * EF-24 — the off-path proof, driven through the REAL {@link CalendarDiscoveryService}.
     *
     * <h2>Why the old version did not prove what it claimed</h2>
     *
     * <p>{@link SyncPath} above mocks {@code discover(...)} and asserts what the sync service does
     * with the answer. That is a useful test of the sync service and <b>no test at all</b> of the
     * claim this class is named for: with discovery mocked, the seat store, the domain-wide-delegation
     * provider and the Admin SDK directory are not in the picture, so nothing here could fail if the
     * off path started reading a service-account key. And a service-account read on the off path is a
     * latent outage for every tenant that has no key at all — which is all of them, until they opt in.
     *
     * <p>So this drives the real {@code discover("full_sync")} with the flag off and asserts what the
     * Always On machinery was NOT asked. It is the one assertion in the class that a regression in the
     * off path cannot slip past.
     */
    @Nested
    @DisplayName("Discovery with the flag off, driven for real")
    class RealDiscoveryWithTheFlagOff {

        private static final String CONSENTED = "consented@acme.example";

        @Test
        @DisplayName("the consented set is returned without touching the seat store, the delegation "
                + "provider or the directory")
        void offPathTouchesNoneOfTheAlwaysOnMachinery() {
            Invoker invoker = mock(Invoker.class);
            // An invoker saved before Always On existed: no alwaysOn attribute at all, which
            // Constants.readAlwaysOn reads as false. This is the shape every existing tenant has.
            when(invoker.getAttributes()).thenReturn(Map.of(
                    Constants.SERVICE_URL, "https://ma.example",
                    Constants.SERVICE_API_KEY, "key"));

            GoogleCalendarAuthenticationService authentication =
                    mock(GoogleCalendarAuthenticationService.class);
            when(authentication.getAuthenticatedUsers()).thenReturn(List.of(CONSENTED));

            RegisteredSeatStore seatStore = mock(RegisteredSeatStore.class);
            DomainWideDelegationProvider delegation = mock(DomainWideDelegationProvider.class);
            TenantUserDirectory directory = mock(TenantUserDirectory.class);

            CalendarDiscoveryService discovery = new CalendarDiscoveryService(
                    invoker, authentication, mock(GoogleProviderFactory.class),
                    delegation, directory, seatStore);

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertEquals(CONSENTED_USERS, set.source());
            assertEquals(List.of(CONSENTED), set.userIds());
            assertTrue(set.tenantMembers().isEmpty());
            assertEquals(null, set.degradedReason(),
                    "a tenant that never enabled Always On is not DEGRADED; degradation is a "
                            + "failure of the on path and reporting one here would put an ERROR in "
                            + "every opt-in tenant's log");

            // The three collaborators that only exist for Always On. A single interaction here is
            // the regression: an unconfigured tenant has no service-account key, so reading one is
            // an outage for a population that never asked for the feature.
            verifyNoInteractions(seatStore);
            verifyNoInteractions(delegation);
            verifyNoInteractions(directory);
        }

        /**
         * Groups expansion is the ONE piece of KIM-474 machinery that legitimately runs with the flag
         * off — product owner, 2026-09-12: <i>"same behaviour regardless of mechanism"</i>. It is
         * therefore excluded from the assertion above rather than forgotten by it, and the exclusion
         * is asserted structurally: {@code GroupExpansionService} takes no discovery seam and no flag
         * reader, so it <em>cannot</em> branch on Always On even if somebody wanted it to.
         */
        @Test
        @DisplayName("Groups expansion is deliberately flag-independent, by construction")
        void groupExpansionCannotBranchOnTheFlag() {
            List<Class<?>> collaborators = java.util.Arrays.stream(
                            app.kristasoft.extensions.essentials.collaboration.google_calendar3
                                    .service.sync.GroupExpansionService.class.getDeclaredConstructors())
                    .filter(c -> c.getParameterCount() > 0)
                    .flatMap(c -> java.util.Arrays.stream(c.getParameterTypes()))
                    .toList();

            assertTrue(collaborators.stream().noneMatch(t -> t == CalendarDiscoveryService.class),
                    "GroupExpansionService must not hold the discovery seam: it is the only place "
                            + "the Always On flag is acted on, and holding it is how an ungated "
                            + "behaviour the owner ruled on becomes gated by accident. Collaborators: "
                            + collaborators);
            assertTrue(collaborators.stream().anyMatch(
                            t -> t == DomainWideDelegationProvider.class),
                    "it does legitimately use the delegated credential — that is why it is on "
                            + "MAY_USE_DELEGATION — which is a different thing from reading the flag");
        }
    }

    // ── structural ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("The one-branch rule")
    class OneBranchRule {

        private static final Path MAIN_SOURCES = Path.of("src/main/java");

        /**
         * {@code Constants} declares the reader; {@code MeetingsArea} reports the flag to the Meeting
         * Agent wrapper over the shared catalog request; {@code CalendarDiscoveryService} is the one
         * place that acts on it.
         */
        private static final List<String> MAY_READ_THE_FLAG = List.of(
                "Constants.java", "MeetingsArea.java", "CalendarDiscoveryService.java");

        /**
         * The credential is built in the connectors package, consumed by the enumerator and by the
         * seam, declared as an attribute on the extension class, and named in {@code Constants}
         * (which declares the key but never reads it — {@code readTextAttribute} is generic).
         * Nothing else may reach for it: a service that builds its own delegated client is a service
         * that can drift onto the wrong credential.
         *
         * <p>{@code GroupExpansionService} (WP-DL) is the one addition, and it is a deliberate one.
         * Expanding a distribution list is an Admin SDK question that only the delegated credential
         * can ask, and it needs a scope neither existing client requests — folding it into
         * {@code directoryClientForAdmin} would have made a token the super-admin had not authorised
         * break user enumeration for every Always On tenant. It reaches the provider through
         * {@code directoryClientForGroups()} and builds no credential of its own, which is what this
         * rule actually protects.
         *
         * <p><b>And the widening is now a RECORDED DECISION rather than an unexplained entry (EF-24).</b>
         * The regression report asked whether Google Groups expansion should be gated on Always On,
         * since an ungated expansion changes opt-in tenants' attendee sets, meeting types and mail
         * audiences. The product owner ruled on 2026-09-12: <i>"same behaviour regardless of
         * mechanism"</i> — Groups expansion applies to Opt-In workspaces too and must NOT be gated on
         * the flag. So this entry is correct and permanent: {@code GroupExpansionService} legitimately
         * uses the delegated credential on both paths, and {@link OneBranchRule#flagIsReadInOnePlace()}
         * separately proves it does not read the flag, which is what "ungated" has to mean in code.
         */
        private static final List<String> MAY_USE_DELEGATION = List.of(
                "DomainWideDelegationProvider.java", "DomainWideDelegationException.java",
                "TenantUserDirectory.java", "CalendarDiscoveryService.java",
                "GoogleCalendarExtension.java", "Constants.java",
                "GroupExpansionService.java");

        private List<String> filesReferencing(String needle, List<String> allowed) throws IOException {
            assertTrue(Files.isDirectory(MAIN_SOURCES),
                    "Expected to run from the module directory; got " + Path.of(".").toAbsolutePath());
            List<String> offenders = new ArrayList<>();
            try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String name = file.getFileName().toString();
                    if (allowed.contains(name)) {
                        continue;
                    }
                    if (Files.readString(file).contains(needle)) {
                        offenders.add(name);
                    }
                }
            }
            return offenders;
        }

        @Test
        @DisplayName("Only three files read the Always On flag")
        void flagIsReadInOnePlace() throws IOException {
            assertEquals(List.of(), filesReferencing("readAlwaysOn", MAY_READ_THE_FLAG),
                    "Acting on the Always On flag belongs in CalendarDiscoveryService. A branch "
                            + "elsewhere is how one scan path ends up on the wrong credential.");
        }

        @Test
        @DisplayName("Only the seam and its own collaborators touch the delegation credential")
        void delegationIsReachedThroughTheSeam() throws IOException {
            assertEquals(List.of(), filesReferencing("DomainWideDelegationProvider", MAY_USE_DELEGATION),
                    "Build delegated clients through CalendarDiscoveryService.calendarClientFor so "
                            + "the credential always matches the discovered set.");
        }

        @Test
        @DisplayName("Only the seam and its collaborators name the service-account key attribute")
        void serviceAccountKeyIsNotReadAdHoc() throws IOException {
            assertEquals(List.of(), filesReferencing("SERVICE_ACCOUNT_KEY", MAY_USE_DELEGATION),
                    "The service-account key is a private key. Every read of it must go through "
                            + "DomainWideDelegationProvider, which never logs or echoes it.");
        }

        /**
         * Arming a meeting for automatic recording is a Microsoft-only capability that the product
         * owner has explicitly said Google will never need — it only registers meetings in its scan
         * path. Codifying that here keeps it from being "helpfully" added later.
         */
        @Test
        @DisplayName("No arming concept exists in this extension, and none was introduced")
        void noArmingOnGoogle() throws IOException {
            for (String armingTerm : List.of("recordAutomatically", "ArmOutcome",
                    "AUTO_RECORD_ARM", "armMeeting")) {
                assertEquals(List.of(), filesReferencing(armingTerm, List.of()),
                        "Arming happens only in the Teams extension: " + armingTerm);
            }
        }

        /**
         * Meeting Agent, not this extension, decides what is capturable (FR-009). Tenant-wide
         * discovery widens WHOSE calendars are read, never WHICH events are forwarded — no
         * attendee, domain, or seat filtering may appear here. Since KIM-505 a link-less event
         * with another invitee reaches Meeting Agent too, so the one remaining event filter is the
         * personal-calendar-block eligibility check.
         */
        @Test
        @DisplayName("The personal-calendar-block skip is still the only event filter")
        void eligibilitySkipIsTheOnlyFilter() throws IOException {
            String sync = Files.readString(MAIN_SOURCES.resolve(
                    "app/kristasoft/extensions/essentials/collaboration/google_calendar3/"
                            + "service/sync/SendEventsToMAService.java"));

            assertTrue(sync.contains("!eventMapper.isEligibleCalendarEvent(event)")
                            && sync.contains("reason=PERSONAL_CALENDAR_BLOCK action=NOT_SENT_TO_MA"),
                    "The eligibility skip is the contract Meeting Agent expects (KIM-505).");
            assertFalse(sync.contains("reason=NO_MEETING_LINK"),
                    "A missing meeting link alone must not stop an event reaching Meeting Agent (KIM-505).");
            assertTrue(sync.contains("[GCAL-SUMMARY]"),
                    "The per-user diagnostic summary is what makes a tenant-wide sweep auditable.");
        }

        @Test
        @DisplayName("The ESB tenant header handling is untouched")
        void esbTenantHandlingUntouched() throws IOException {
            assertEquals(List.of(), filesReferencing("esb_tenantId", List.of("EventProducer.java")),
                    "esb_tenantId is out of scope for KIM-474 and must not be read or written here.");
        }
    }

    // ── the attribute surface ─────────────────────────────────────────────

    @Test
    @DisplayName("Every new attribute is optional, so an existing invoker keeps working untouched")
    void newAttributesAreOptional() {
        Map<String, Object> asExistingInvokersLookToday = Map.of(
                Constants.SERVICE_URL, "https://ma.example",
                Constants.SERVICE_API_KEY, "key");

        assertEquals(null, Constants.readTextAttribute(asExistingInvokersLookToday, Constants.SERVICE_ACCOUNT_KEY));
        assertEquals(null, Constants.readTextAttribute(asExistingInvokersLookToday, Constants.TENANT_ADMIN_EMAIL));
        assertEquals(null, Constants.readTextAttribute(asExistingInvokersLookToday, Constants.TENANT_DOMAIN));
        assertTrue(!Constants.readAlwaysOn(asExistingInvokersLookToday),
                "An invoker saved before this change captures exactly what it captured before");
    }

    @Test
    @DisplayName("The service-account key is declared as a secured field, like the API key")
    void serviceAccountKeyIsSecured() throws IOException {
        String extension = Files.readString(Path.of("src/main/java/app/kristasoft/extensions/essentials/"
                + "collaboration/google_calendar3/GoogleCalendarExtension.java"));

        assertTrue(extension.contains("@Field.Text(value = Constants.SERVICE_ACCOUNT_KEY, isSecured = true"),
                "A service-account private key declared unmasked would be readable in Studio by "
                        + "anyone who can open the invoker.");
        assertTrue(extension.contains("@Field.Text(value = Constants.SERVICE_API_KEY, isSecured = true"),
                "Mirrors the existing convention for sensitive attributes.");
    }
}
