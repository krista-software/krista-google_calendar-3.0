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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync;

import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationException;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import com.google.api.services.calendar.Calendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS;
import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.ENROLLED_USERS;
import static app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService.DiscoverySource.TENANT_DIRECTORY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * KIM-474 / WP-G1 — the discovery seam: the single place that answers "whose calendars do I scan,
 * and with what credential?".
 *
 * <p>Three things are being protected here, and they pull against each other.
 *
 * <p><strong>Always On off must be indistinguishable from before.</strong> Every existing tenant is
 * on this path. It is not enough that the answer happens to be the consented set — the delegated
 * machinery must not be <em>touched</em>, which is why several tests assert on
 * {@code verifyNoInteractions} rather than on the returned list. A Directory call, or a call to
 * Meeting Agent's user registry, on the off path would be a latent outage the day a customer's key
 * expires or MA is briefly down.
 *
 * <p><strong>Always On on must scan registered Meeting Agent users and nobody else.</strong> The
 * product owner withdrew tenant-wide scanning on 2026-09-10. The failure mode this guards against is
 * not an exception — it is a sweep that quietly reads the calendar of somebody the customer never
 * enrolled, which is exactly what the previous implementation did by design.
 *
 * <p><strong>Always On is either/or (owner ruling, 2026-11-10).</strong> With the flag on, no
 * personal token is consulted on any path — not for discovery, not as a fallback, not for a single
 * user. Every failure mode (no service-account key, refused delegation, unreadable roster, a
 * directory read that failed) lands on an EMPTY, delegated set carrying a degraded reason and an
 * actionable error — never on the consented users, and never on an exception.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Calendar discovery seam (KIM-474 / WP-G1)")
class CalendarDiscoveryServiceTest {

    private static final List<String> CONSENTED = List.of("consented@acme.example", "wsContact_abc123");
    private static final List<String> DIRECTORY = List.of(
            "alice@acme.example", "bob@acme.example", "carol@acme.example");

    @Mock
    private Invoker invoker;
    @Mock
    private GoogleCalendarAuthenticationService authenticationService;
    @Mock
    private GoogleProviderFactory googleProviderFactory;
    @Mock
    private DomainWideDelegationProvider delegationProvider;
    @Mock
    private TenantUserDirectory tenantUserDirectory;
    @Mock
    private RegisteredSeatStore registeredSeatStore;

    private CalendarDiscoveryService discovery;

    @BeforeEach
    void setUp() {
        discovery = new CalendarDiscoveryService(invoker, authenticationService, googleProviderFactory,
                delegationProvider, tenantUserDirectory, registeredSeatStore);
    }

    private void alwaysOn(boolean enabled) {
        lenient().when(invoker.getAttributes()).thenReturn(Map.of(Constants.ALWAYS_ON, enabled));
    }

    private void consentedUsersAre(List<String> users) {
        lenient().when(authenticationService.getAuthenticatedUsers()).thenReturn(users);
    }

    private void delegationConfigured() {
        lenient().when(delegationProvider.isConfigured()).thenReturn(true);
        lenient().when(delegationProvider.configurationGap()).thenReturn(null);
    }

    /** A roster Meeting Agent has answered with, in its own order. */
    private void registeredSeatsAre(String... emails) {
        Set<String> seats = new LinkedHashSet<>(List.of(emails));
        lenient().when(registeredSeatStore.seats()).thenReturn(Optional.of(seats));
        for (String email : emails) {
            lenient().when(registeredSeatStore.isRegisteredSeat(email)).thenReturn(true);
        }
    }

    /** Meeting Agent has never answered — the absence of a signal, not "there are no seats". */
    private void registeredSeatsNeverFetched() {
        lenient().when(registeredSeatStore.seats()).thenReturn(Optional.empty());
    }

    // ── Always On OFF ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Always On OFF — the path every existing tenant is on")
    class AlwaysOnOff {

        @Test
        @DisplayName("Scans exactly the consented users")
        void scansConsentedUsers() {
            alwaysOn(false);
            consentedUsersAre(CONSENTED);

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertEquals(CONSENTED_USERS, set.source());
            assertEquals(CONSENTED, set.userIds());
            assertFalse(set.degraded());
            assertNull(set.degradedReason());
        }

        /**
         * The heart of the safety story. Not "the answer is the same" but "the delegated code was
         * never reached" — no Admin SDK call, no service-account key read, and (WP-G1) no call to
         * Meeting Agent's user registry either. An opt-in tenant must not depend on MA being
         * reachable to sync a calendar.
         */
        @Test
        @DisplayName("Never touches the directory, the delegation credential, or MA's user registry")
        void neverTouchesDelegatedMachinery() {
            alwaysOn(false);
            consentedUsersAre(CONSENTED);

            discovery.discover("full_sync");

            verifyNoInteractions(tenantUserDirectory);
            verifyNoInteractions(delegationProvider);
            verifyNoInteractions(registeredSeatStore);
        }

        @Test
        @DisplayName("Stays on the consented path even when delegation is fully configured")
        void configurationDoesNotEnableIt() {
            alwaysOn(false);
            consentedUsersAre(CONSENTED);
            delegationConfigured();

            assertEquals(CONSENTED_USERS, discovery.discover("full_sync").source());
            assertEquals(CONSENTED_USERS, discovery.sourceFor("alice@acme.example"));
            verifyNoInteractions(tenantUserDirectory);
            verifyNoInteractions(registeredSeatStore);
        }

        @Test
        @DisplayName("The syncable gate is exactly the pre-existing 'is this user authenticated?'")
        void syncableGateIsUnchanged() {
            alwaysOn(false);
            when(authenticationService.isUserAuthenticated("consented@acme.example")).thenReturn(true);
            when(authenticationService.isUserAuthenticated("stranger@acme.example")).thenReturn(false);

            assertTrue(discovery.isSyncable("consented@acme.example"));
            assertFalse(discovery.isSyncable("stranger@acme.example"));
        }

        @Test
        @DisplayName("Reads the calendar with the user's own token, on the provider already built")
        void usesTheConsentedProvider() {
            alwaysOn(false);
            GoogleProvider provider = mock(GoogleProvider.class);
            Calendar expected = mock(Calendar.class);
            when(provider.getGoogleClientForUser("consented@acme.example")).thenReturn(expected);

            Calendar actual = discovery.calendarClientFor(provider, "consented@acme.example", CONSENTED_USERS);

            assertSame(expected, actual);
            verifyNoInteractions(delegationProvider);
            verifyNoInteractions(googleProviderFactory);
        }

        @Test
        @DisplayName("An empty consented list is reported as empty, not silently widened")
        void emptyStaysEmpty() {
            alwaysOn(false);
            consentedUsersAre(List.of());

            assertTrue(discovery.discover("full_sync").isEmpty());
        }

        /**
         * The load-time prime exists to have the roster in hand before the first watch-channel
         * sweep. For an opt-in tenant there is no roster to fetch, and issuing the call anyway would
         * make every such extension's startup depend on Meeting Agent being up.
         */
        @Test
        @DisplayName("Priming the roster at load is a no-op for an opt-in tenant")
        void primingIsANoOpWhenOff() {
            alwaysOn(false);

            discovery.primeRegisteredSeats();

            verifyNoInteractions(registeredSeatStore);
        }
    }

    // ── Always On ON, working ─────────────────────────────────────────────

    @Nested
    @DisplayName("Always On ON — registered Meeting Agent users only (WP-G1)")
    class EnrolledUsers {

        /**
         * The central assertion of WP-G1. {@code carol} is an active mailbox in the Workspace
         * directory and is NOT scanned, because she holds no Meeting Agent seat. Before this change
         * she was scanned precisely because she existed.
         */
        @Test
        @DisplayName("Scans the registered seats, not every mailbox in the directory")
        void scansRegisteredSeatsOnly() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example", "bob@acme.example");
            when(tenantUserDirectory.listActiveUsers()).thenReturn(TenantUserDirectory.Listing.complete(DIRECTORY));

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertEquals(ENROLLED_USERS, set.source());
            assertEquals(List.of("alice@acme.example", "bob@acme.example"), set.userIds());
            assertFalse(set.userIds().contains("carol@acme.example"),
                    "carol is an active mailbox with no Meeting Agent seat — the tenant never "
                            + "enrolled her, so her calendar is not read");
            assertFalse(set.degraded());
        }

        @Test
        @DisplayName("The withdrawn tenant-wide mode is never the answer")
        void tenantWideIsNeverProduced() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example");
            when(tenantUserDirectory.listActiveUsers()).thenReturn(TenantUserDirectory.Listing.complete(DIRECTORY));

            assertFalse(discovery.discover("full_sync").source() == TENANT_DIRECTORY,
                    "Tenant-wide discovery was withdrawn by the product owner on 2026-09-10; the "
                            + "constant is retained for a possible future mode and must not be "
                            + "produced by any code path today");
        }

        /**
         * Domain-wide delegation only ever mints a token for a member of the delegating Workspace
         * account, so a seat the directory does not list is unreadable however registered it is in
         * Meeting Agent. Skipping one must not cost the sweep.
         */
        @Test
        @DisplayName("A seat the Workspace directory does not list is skipped, not fatal")
        void seatOutsideTheDirectoryIsSkipped() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example", "ghost@acme.example", "bob@acme.example");
            when(tenantUserDirectory.listActiveUsers()).thenReturn(TenantUserDirectory.Listing.complete(DIRECTORY));

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertEquals(List.of("alice@acme.example", "bob@acme.example"), set.userIds());
            assertFalse(set.degraded(), "one unreadable seat is that seat's problem, not the sweep's");
        }

        @Test
        @DisplayName("Seat matching is case-insensitive, as Google addresses are")
        void directoryMatchIsCaseInsensitive() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example");
            when(tenantUserDirectory.listActiveUsers())
                    .thenReturn(TenantUserDirectory.Listing.complete(List.of("ALICE@Acme.Example", "bob@acme.example")));

            assertEquals(List.of("alice@acme.example"), discovery.discover("full_sync").userIds());
        }

        /**
         * The product decision is <b>strict replacement</b>: with Always On on and the set resolving
         * cleanly, the scan set is the enrolled users and only them. A consented user on a personal
         * Google account, a {@code wsContact_} client key, or anyone without a licensed seat, is not
         * in that set and stops being scanned.
         *
         * <p>That is a real, one-directional cost, so it is asserted rather than assumed.
         */
        @Test
        @DisplayName("Consented users outside the enrolled set are REPLACED, not merged in")
        void strictReplacementNotUnion() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            consentedUsersAre(List.of("personal@gmail.example", "wsContact_abc123", "ALICE@acme.example"));
            registeredSeatsAre("alice@acme.example", "bob@acme.example");
            when(tenantUserDirectory.listActiveUsers()).thenReturn(TenantUserDirectory.Listing.complete(DIRECTORY));

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertEquals(List.of("alice@acme.example", "bob@acme.example"), set.userIds());
            assertFalse(set.userIds().contains("personal@gmail.example"));
            assertFalse(set.userIds().contains("wsContact_abc123"));
        }

        /**
         * Either/or: the consented list is not even read on an Always On sweep. Reading it to
         * report who "dropped" out was the last place a personal-token store sat on the Always On
         * path, and it is gone with the fallback it served.
         */
        @Test
        @DisplayName("An Always On sweep never reads the individually-authorised users")
        void alwaysOnNeverReadsTheConsentedList() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example");
            when(tenantUserDirectory.listActiveUsers()).thenReturn(TenantUserDirectory.Listing.complete(DIRECTORY));

            discovery.discover("full_sync");

            verify(authenticationService, never()).getAuthenticatedUsers();
            verify(authenticationService, never()).isUserAuthenticated(anyString());
        }

        @Test
        @DisplayName("Every user of an enrolled sweep is read via delegation")
        void perUserCredentialSelection() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            consentedUsersAre(List.of("personal@gmail.example", "wsContact_abc123"));
            registeredSeatsAre("alice@acme.example", "bob@acme.example");
            when(tenantUserDirectory.listActiveUsers()).thenReturn(TenantUserDirectory.Listing.complete(DIRECTORY));

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            for (String user : set.userIds()) {
                assertEquals(ENROLLED_USERS, set.sourceOf(user),
                        "an enrolled Workspace member is read via delegation, which no individual "
                                + "token expiry can break: " + user);
            }
            assertEquals(ENROLLED_USERS, set.sourceOf("personal@gmail.example"),
                    "either/or: a delegated set never hands out a personal token, even for an "
                            + "address it does not contain — that address is simply not read");
            assertTrue(set.source().delegated(),
                    "the scale behaviours — parallel fan-out, throttle backoff, the "
                            + "primary-calendar-id fallback — all key on this, not on a constant");
        }

        @Test
        @DisplayName("A complete directory read names the seats Google does not hold")
        void completeDirectoryNamesAbsentSeats() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example", "ghost@acme.example");
            when(tenantUserDirectory.listActiveUsers())
                    .thenReturn(TenantUserDirectory.Listing.complete(List.of("alice@acme.example")));

            CalendarDiscoveryService.UserSet set = discovery.discover("watch_subscribe");

            assertEquals(List.of("alice@acme.example"), set.userIds());
            assertEquals(List.of("ghost@acme.example"), set.seatsNotInDirectory());
            assertTrue(set.seatsUnconfirmed().isEmpty());
        }

        @Test
        @DisplayName("A directory read that stopped early proves nothing about an absent seat")
        void partialDirectoryNamesNobodyAsAbsent() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example", "beyond-the-page@acme.example");
            when(tenantUserDirectory.listActiveUsers())
                    .thenReturn(new TenantUserDirectory.Listing(List.of("alice@acme.example"), false));

            CalendarDiscoveryService.UserSet set = discovery.discover("watch_subscribe");

            assertTrue(set.seatsNotInDirectory().isEmpty());
            assertEquals(List.of("beyond-the-page@acme.example"), set.seatsUnconfirmed());
        }

        @Test
        @DisplayName("A failed directory read marks nobody as absent, however many seats there are")
        void failedDirectoryNamesNobodyAsAbsent() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example", "bob@acme.example");
            consentedUsersAre(CONSENTED);
            when(tenantUserDirectory.listActiveUsers())
                    .thenThrow(new DomainWideDelegationException("network", new IOException("reset")));

            CalendarDiscoveryService.UserSet set = discovery.discover("watch_subscribe");

            assertTrue(set.degraded());
            assertTrue(set.seatsNotInDirectory().isEmpty());
            assertTrue(set.seatsUnconfirmed().isEmpty());
        }

        @Test
        @DisplayName("A delegated set answers the delegated credential for every input")
        void delegatedSetNeverAnswersConsented() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example");
            when(tenantUserDirectory.listActiveUsers()).thenReturn(TenantUserDirectory.Listing.complete(List.of("alice@acme.example")));

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertEquals(ENROLLED_USERS, set.sourceOf("Alice@Acme.Example"));
            assertEquals(ENROLLED_USERS, set.sourceOf(null));
        }

        @Test
        @DisplayName("On a consented sweep every user resolves to the consented credential")
        void consentedSweepResolvesConsented() {
            alwaysOn(false);
            consentedUsersAre(CONSENTED);

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertEquals(CONSENTED_USERS, set.sourceOf("consented@acme.example"));
            assertEquals(CONSENTED_USERS, set.sourceOf("anyone@anywhere.example"));
            assertFalse(set.source().delegated());
        }

        /**
         * Either/or on the single-user paths: a stored refresh token is not evidence of anything
         * under Always On, because it is not used.
         */
        @Test
        @DisplayName("Always On uses delegation even when the seat has an old refresh token")
        void singleUserPrefersDelegation() {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("consented@acme.example", "seated@acme.example");
            lenient().when(authenticationService.isUserAuthenticated("consented@acme.example")).thenReturn(true);
            lenient().when(authenticationService.isUserAuthenticated("seated@acme.example")).thenReturn(false);

            assertEquals(ENROLLED_USERS, discovery.sourceFor("consented@acme.example"));
            assertEquals(ENROLLED_USERS, discovery.sourceFor("seated@acme.example"));
        }

        /**
         * The single-user counterpart of the sweep rule. A push notification can arrive from a
         * channel registered for somebody who has since lost their seat; reading their calendar then
         * would be the exact thing WP-G1 exists to stop, on the one path that has no scan set to
         * consult.
         */
        @Test
        @DisplayName("A stranger with no seat is not syncable, and is never offered a personal token")
        void unseatedStrangerIsRefused() {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example");

            assertFalse(discovery.isSyncable("stranger@acme.example"));
            assertEquals(ENROLLED_USERS, discovery.sourceFor("stranger@acme.example"));
            verify(authenticationService, never()).isUserAuthenticated(anyString());
        }

        @Test
        @DisplayName("A consented user with no seat is not syncable under Always On")
        void consentedUserWithoutASeatIsNotSyncable() {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example");
            lenient().when(authenticationService.isUserAuthenticated("carol@acme.example")).thenReturn(true);

            assertFalse(discovery.isSyncable("carol@acme.example"),
                    "her personal sign-in is not a way in while Always On is on");
        }

        @Test
        @DisplayName("A registered seat who never authorised anything is syncable")
        void seatedUserIsSyncable() {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example");
            lenient().when(authenticationService.isUserAuthenticated("alice@acme.example")).thenReturn(false);

            assertTrue(discovery.isSyncable("alice@acme.example"));
            assertEquals(ENROLLED_USERS, discovery.sourceFor("alice@acme.example"));
        }

        @Test
        @DisplayName("Reads each calendar on a delegated credential, not a stored refresh token")
        void usesDelegatedCredentials() throws Exception {
            Calendar expected = mock(Calendar.class);
            when(delegationProvider.calendarClientFor("alice@acme.example")).thenReturn(expected);

            Calendar actual = discovery.calendarClientFor("alice@acme.example", ENROLLED_USERS);

            assertSame(expected, actual);
            verifyNoInteractions(googleProviderFactory);
        }

        /**
         * {@code wsContact_<uuid>} keys identify client users who authorised individually. They are
         * not addresses, so nothing can impersonate them — and under Always On their own token is
         * not used either, so they are simply not read.
         */
        @Test
        @DisplayName("A wsContact key is not syncable under Always On, and gets no personal token")
        void wsContactKeysAreNotReadUnderAlwaysOn() {
            alwaysOn(true);
            delegationConfigured();
            lenient().when(registeredSeatStore.seats()).thenReturn(Optional.of(Set.of("alice@acme.example")));

            assertFalse(discovery.isSyncable("wsContact_abc123"));
            assertEquals(ENROLLED_USERS, discovery.sourceFor("wsContact_abc123"));
            assertEquals(ENROLLED_USERS, discovery.sourceFor(null));
        }

        @Test
        @DisplayName("No personal-token client is built under Always On, on either overload")
        void personalClientIsRefusedUnderAlwaysOn() {
            alwaysOn(true);
            GoogleProvider provider = mock(GoogleProvider.class);

            org.junit.jupiter.api.Assertions.assertThrows(
                    CalendarDiscoveryService.PersonalTokenRefusedException.class,
                    () -> discovery.calendarClientFor("consented@acme.example", CONSENTED_USERS));
            org.junit.jupiter.api.Assertions.assertThrows(
                    CalendarDiscoveryService.PersonalTokenRefusedException.class,
                    () -> discovery.calendarClientFor(provider, "consented@acme.example", CONSENTED_USERS));

            verifyNoInteractions(googleProviderFactory);
            verifyNoInteractions(provider);
        }

        @Test
        @DisplayName("A failure building the delegated client names the user it was for")
        void delegatedClientFailureIsDiagnosable() throws Exception {
            when(delegationProvider.calendarClientFor("alice@acme.example"))
                    .thenThrow(new DomainWideDelegationException("key unreadable", null));

            IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> discovery.calendarClientFor("alice@acme.example", ENROLLED_USERS));

            assertTrue(failure.getMessage().contains("alice@acme.example"), failure.getMessage());
        }
    }

    // ── the roster ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("The registered-seat roster")
    class Roster {

        /**
         * Discovery is the one call every sweep already makes, so refreshing here bounds staleness
         * on all of them — batch sync, watch subscribe, renewal, teardown — from a single place,
         * with no timer thread of its own. The store decides whether the refresh is actually due.
         */
        @Test
        @DisplayName("Every Always On sweep asks the store to refresh if it is stale")
        void everySweepRefreshesIfStale() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example");
            when(tenantUserDirectory.listActiveUsers()).thenReturn(TenantUserDirectory.Listing.complete(DIRECTORY));

            discovery.discover("full_sync");
            discovery.discover("watch_renewal");

            verify(registeredSeatStore, org.mockito.Mockito.times(2)).refreshIfStale();
        }

        /**
         * Startup forces a refresh whatever the roster's age: the process has just come up,
         * {@code serviceUrl} / {@code serviceApiKey} may have changed while it was down, and the
         * next thing the extension does is register watch channels for the scan set.
         */
        @Test
        @DisplayName("Priming at load forces a refresh, not a stale-gated one")
        void primingForcesARefresh() {
            alwaysOn(true);

            discovery.primeRegisteredSeats();

            verify(registeredSeatStore).refresh();
            verify(registeredSeatStore, never()).refreshIfStale();
        }

        /**
         * A refresh that fails leaves the previous roster in force, which the store owns. Discovery's
         * side of that contract is simply that it reads whatever {@code seats()} answers and does not
         * treat a failed refresh as a reason to degrade — capture must not narrow because Meeting
         * Agent was briefly unreachable.
         */
        @Test
        @DisplayName("A failed refresh does not degrade the sweep while a roster is still held")
        void failedRefreshKeepsScanning() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            when(registeredSeatStore.refreshIfStale()).thenReturn(false);
            registeredSeatsAre("alice@acme.example");
            when(tenantUserDirectory.listActiveUsers()).thenReturn(TenantUserDirectory.Listing.complete(DIRECTORY));

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertEquals(ENROLLED_USERS, set.source());
            assertEquals(List.of("alice@acme.example"), set.userIds());
            assertFalse(set.degraded());
        }

        /**
         * The one case that legitimately scans nobody. A workspace with no ACTIVE, licensed Meeting
         * Agent user has enrolled nobody, and reverting to the consented users would read calendars
         * the customer has not asked to capture. It is a fetched answer, not a missing signal — so
         * it is an empty enrolled set, not a degradation.
         */
        @Test
        @DisplayName("A workspace with no licensed seats scans nobody, and does not degrade")
        void emptyRosterScansNobody() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            consentedUsersAre(CONSENTED);
            when(registeredSeatStore.seats()).thenReturn(Optional.of(Set.of()));
            when(tenantUserDirectory.listActiveUsers()).thenReturn(TenantUserDirectory.Listing.complete(DIRECTORY));

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertEquals(ENROLLED_USERS, set.source());
            assertTrue(set.isEmpty());
            assertFalse(set.degraded(),
                    "'nobody is enrolled' is an answer from Meeting Agent, not a fault — degrading "
                            + "here would scan the consented users the tenant chose not to enrol");
        }
    }

    // ── Always On ON, degrading ───────────────────────────────────────────

    /**
     * Each of these is a state a real tenant lands in between switching the flag on and finishing
     * the Google-side configuration — often for days. Under the either/or rule every one of them
     * scans NOTHING, carries a reason the scanned-mailbox report hands to Meeting Agent, and never
     * falls back to the individually-authorised users.
     */
    @Nested
    @DisplayName("Always On ON but discovery unavailable — scan nothing, say why")
    class Degradation {

        private void assertDegradedEmpty(CalendarDiscoveryService.UserSet set) {
            assertEquals(ENROLLED_USERS, set.source(), "still the delegated mode, never CONSENTED_USERS");
            assertTrue(set.isEmpty(), "a degraded sweep scans nobody");
            assertTrue(set.degraded());
            assertTrue(set.seatsNotInDirectory().isEmpty());
            verify(authenticationService, never()).getAuthenticatedUsers();
        }

        @Test
        @DisplayName("No service-account key configured — empty, names the gap")
        void notConfigured() {
            alwaysOn(true);
            consentedUsersAre(CONSENTED);
            when(delegationProvider.configurationGap())
                    .thenReturn("the 'serviceAccountKey' service-account key is not configured");

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertDegradedEmpty(set);
            assertTrue(set.degradedReason().contains("serviceAccountKey"), set.degradedReason());
            verifyNoInteractions(tenantUserDirectory);
            verifyNoInteractions(registeredSeatStore);
        }

        @Test
        @DisplayName("The roster was never fetched — empty, with a greppable reason")
        void registeredSeatsUnavailable() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            consentedUsersAre(CONSENTED);
            registeredSeatsNeverFetched();

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertDegradedEmpty(set);
            assertEquals("registered-seats-unavailable", set.degradedReason(),
                    "the token is a contract with the alignment plan and with the KIP extension; "
                            + "both degrade under the same name");
            verify(tenantUserDirectory, never()).listActiveUsers();
        }

        @Test
        @DisplayName("Delegation not authorised by the super-admin — empty, says so explicitly")
        void delegationDenied() throws Exception {
            alwaysOn(true);
            consentedUsersAre(CONSENTED);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example");
            when(tenantUserDirectory.listActiveUsers()).thenThrow(new DomainWideDelegationException(
                    "refused", new IOException("unauthorized_client")));

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertDegradedEmpty(set);
            assertTrue(set.degradedReason().contains("Client ID is not authorised"), set.degradedReason());
        }

        @Test
        @DisplayName("A transient Directory fault is empty too, without blaming the admin")
        void transientFailure() throws Exception {
            alwaysOn(true);
            consentedUsersAre(CONSENTED);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example");
            when(tenantUserDirectory.listActiveUsers())
                    .thenThrow(new DomainWideDelegationException("network", new IOException("Connection reset")));

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertDegradedEmpty(set);
            assertFalse(set.degradedReason().contains("not authorised"), set.degradedReason());
        }

        /**
         * A Workspace account with zero active users is not a real state — it means the enumeration
         * looked in the wrong place (wrong customer, wrong domain filter). It is a degradation, not
         * an answer, so it names the problem — and like every degradation it scans nothing.
         */
        @Test
        @DisplayName("An empty Workspace directory is a misconfiguration, reported as such")
        void emptyDirectoryDegrades() throws Exception {
            alwaysOn(true);
            consentedUsersAre(CONSENTED);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example");
            when(tenantUserDirectory.listActiveUsers()).thenReturn(TenantUserDirectory.Listing.complete(List.of()));

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertDegradedEmpty(set);
            assertTrue(set.degradedReason().contains("no active users"), set.degradedReason());
        }

        @Test
        @DisplayName("Degrading never throws")
        void neverThrows() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsAre("alice@acme.example");
            when(tenantUserDirectory.listActiveUsers())
                    .thenThrow(new DomainWideDelegationException("refused", null));

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertTrue(set.isEmpty());
            assertTrue(set.degraded());
        }

        /**
         * The rule this release exists for. The old fallback read the consented users with their own
         * tokens whenever the delegated sweep could not run, which made Connected mean two different
         * things in one workspace — "delegation can read it" for some rows, "this person once signed
         * in" for others.
         */
        @Test
        @DisplayName("Degrading never falls back to the individually-authorised users")
        void degradationNeverFallsBackToConsentedUsers() throws Exception {
            alwaysOn(true);
            delegationConfigured();
            consentedUsersAre(List.of("personal@gmail.example", "wsContact_abc123", "alice@acme.example"));
            registeredSeatsAre("alice@acme.example");
            when(tenantUserDirectory.listActiveUsers())
                    .thenThrow(new DomainWideDelegationException("refused", new IOException("unauthorized_client")));

            CalendarDiscoveryService.UserSet set = discovery.discover("full_sync");

            assertDegradedEmpty(set);
            assertFalse(set.userIds().contains("personal@gmail.example"));
            verify(authenticationService, never()).isUserAuthenticated(anyString());
        }

        @Test
        @DisplayName("With delegation unconfigured nobody is syncable — not even a consented user")
        void nobodySyncableWhenUnconfigured() {
            alwaysOn(true);
            lenient().when(delegationProvider.isConfigured()).thenReturn(false);
            registeredSeatsAre("alice@acme.example");
            lenient().when(authenticationService.isUserAuthenticated("alice@acme.example")).thenReturn(true);

            assertFalse(discovery.isSyncable("alice@acme.example"));
            assertEquals(ENROLLED_USERS, discovery.sourceFor("alice@acme.example"));
        }

        @Test
        @DisplayName("With no roster yet nobody is syncable")
        void nobodySyncableWithoutARoster() {
            alwaysOn(true);
            delegationConfigured();
            registeredSeatsNeverFetched();

            assertFalse(discovery.isSyncable("alice@acme.example"));
        }
    }

    // ── flag reading ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Reading the flag")
    class FlagReading {

        @Test
        @DisplayName("Read live, so a Studio toggle takes effect on the next sweep")
        void readLive() {
            when(invoker.getAttributes())
                    .thenReturn(Map.of(Constants.ALWAYS_ON, false))
                    .thenReturn(Map.of(Constants.ALWAYS_ON, true));

            assertFalse(discovery.isAlwaysOn());
            assertTrue(discovery.isAlwaysOn());
        }

        @Test
        @DisplayName("An unreadable invoker reads as OFF — capture is never widened by an error")
        void unreadableInvokerReadsOff() throws Exception {
            when(invoker.getAttributes()).thenThrow(new IllegalStateException("invoker was removed"));
            when(authenticationService.getAuthenticatedUsers()).thenReturn(CONSENTED);

            assertFalse(discovery.isAlwaysOn());
            assertEquals(CONSENTED_USERS, discovery.discover("full_sync").source());
            verify(delegationProvider, never()).calendarClientFor(anyString());
            verify(tenantUserDirectory, never()).listActiveUsers();
            verifyNoInteractions(registeredSeatStore);
        }

        @Test
        @DisplayName("An absent attribute reads as OFF")
        void absentAttributeReadsOff() {
            when(invoker.getAttributes()).thenReturn(Map.of(Constants.SERVICE_URL, "https://ma.example"));
            when(authenticationService.getAuthenticatedUsers()).thenReturn(CONSENTED);

            assertEquals(CONSENTED_USERS, discovery.discover("full_sync").source());
        }
    }

    // ── HK2 wiring ────────────────────────────────────────────────────────

    /**
     * The seam and its collaborators are injected into three services that the extension loads at
     * startup. A dependency HK2 cannot satisfy is not a degraded feature — it is an
     * {@code UnsatisfiedDependencyException} at invoker load, i.e. the whole extension failing to
     * start, for tenants who never enabled Always On. Cheap to guard, expensive to discover in a
     * customer's environment.
     */
    @Nested
    @DisplayName("HK2 wiring")
    class Hk2Wiring {

        private java.lang.reflect.Constructor<?> injectConstructorOf(Class<?> type) {
            assertTrue(type.isAnnotationPresent(org.jvnet.hk2.annotations.Service.class),
                    type.getSimpleName() + " must be an HK2 @Service to be injectable");
            for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (constructor.isAnnotationPresent(javax.inject.Inject.class)) {
                    return constructor;
                }
            }
            throw new AssertionError(type.getSimpleName() + " must declare an @Inject constructor");
        }

        @Test
        @DisplayName("Every new service is an @Service with a single @Inject constructor")
        void newServicesAreInjectable() {
            for (Class<?> type : List.of(CalendarDiscoveryService.class, TenantUserDirectory.class,
                    DomainWideDelegationProvider.class, RegisteredSeatStore.class)) {
                java.lang.reflect.Constructor<?> constructor = injectConstructorOf(type);
                for (java.lang.reflect.Parameter parameter : constructor.getParameters()) {
                    assertFalse(parameter.getType().isAnnotationPresent(javax.ws.rs.Path.class),
                            type.getSimpleName() + " depends on the JAX-RS resource "
                                    + parameter.getType().getSimpleName()
                                    + ", which Jersey owns and HK2 cannot inject");
                }
            }
        }

        @Test
        @DisplayName("The seam's own dependencies are all HK2-resolvable")
        void seamDependenciesAreResolvable() {
            for (java.lang.reflect.Parameter parameter : injectConstructorOf(CalendarDiscoveryService.class)
                    .getParameters()) {
                Class<?> type = parameter.getType();
                boolean platformProvided = type.isInterface();
                assertTrue(platformProvided || type.isAnnotationPresent(org.jvnet.hk2.annotations.Service.class),
                        type.getSimpleName() + " is neither a platform-provided interface nor an "
                                + "@Service, so HK2 cannot supply it to CalendarDiscoveryService");
            }
        }
    }

    // ── the withdrawn mode ────────────────────────────────────────────────

    /**
     * WP-G1 keeps {@code TENANT_DIRECTORY} and {@link TenantUserDirectory} compiled and tested, for
     * a possible future mode, and that is a deliberate choice rather than dead code nobody removed.
     * The risk in keeping it is that something starts producing it again — a copy-paste, a merge,
     * a "restore the old behaviour" patch — with no test failing, because the constant is still a
     * legal value everywhere. So the guard is structural: nothing outside the enum's own declaration
     * may even name it.
     */
    @Test
    @DisplayName("Nothing outside the enum declaration names the withdrawn tenant-wide mode")
    void tenantWideModeIsRetainedButUnreferenced() throws IOException {
        Path mainSources = Path.of("src/main/java");
        assertTrue(Files.isDirectory(mainSources),
                "Expected to run from the module directory; got " + Path.of(".").toAbsolutePath());

        List<String> referencing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("CalendarDiscoveryService.java")) {
                    continue; // declares the constant and documents why it survives
                }
                if (Files.readString(file).contains("TENANT_DIRECTORY")) {
                    referencing.add(name);
                }
            }
        }

        assertEquals(List.of(), referencing,
                "Tenant-wide discovery was withdrawn by the product owner on 2026-09-10. The "
                        + "constant is retained for a possible future mode; producing or branching "
                        + "on it would scan mailboxes the customer never enrolled. Key scale "
                        + "behaviour on DiscoverySource.delegated() instead.");
    }

    // ── Validate Attributes / Save Configuration: the delegation check ────

    @Nested
    @DisplayName("validateDelegatedAccessIfAlwaysOn — Studio's Validate Attributes and Save Configuration")
    class DelegationValidation {

        private final Map<String, Object> savedCredentials = Map.of(
                Constants.ALWAYS_ON, true,
                Constants.SERVICE_ACCOUNT_KEY, "{\"client_id\":\"111111111111\"}",
                Constants.TENANT_ADMIN_EMAIL, "old-admin@acme.example");

        @Test
        @DisplayName("Always On off: nothing is validated, whatever else the form carries")
        void alwaysOnOffValidatesNothing() throws DomainWideDelegationException {
            discovery.validateDelegatedAccessIfAlwaysOn(Map.of(
                    Constants.ALWAYS_ON, false,
                    Constants.SERVICE_ACCOUNT_KEY, "{\"client_id\":\"111111111111\"}",
                    Constants.TENANT_ADMIN_EMAIL, "admin@acme.example"));

            verifyNoInteractions(delegationProvider);
        }

        @Test
        @DisplayName("Always On on with both credentials: the delegation check runs on exactly that map")
        void bothCredentialsPresentRunTheCheck() throws DomainWideDelegationException {
            Map<String, Object> form = Map.of(
                    Constants.ALWAYS_ON, true,
                    Constants.SERVICE_ACCOUNT_KEY, "{\"client_id\":\"222222222222\"}",
                    Constants.TENANT_ADMIN_EMAIL, "admin@acme.example");

            discovery.validateDelegatedAccessIfAlwaysOn(form);

            verify(delegationProvider).validateDelegatedAccess(form);
        }

        @Test
        @DisplayName("Always On on without the credentials: nothing to validate yet, so the save goes through")
        void missingCredentialsSkipTheCheck() throws DomainWideDelegationException {
            discovery.validateDelegatedAccessIfAlwaysOn(Map.of(Constants.ALWAYS_ON, true));
            discovery.validateDelegatedAccessIfAlwaysOn(Map.of(
                    Constants.ALWAYS_ON, true,
                    Constants.TENANT_ADMIN_EMAIL, "admin@acme.example"));
            discovery.validateDelegatedAccessIfAlwaysOn(Map.of(
                    Constants.ALWAYS_ON, true,
                    Constants.SERVICE_ACCOUNT_KEY, "{\"client_id\":\"222222222222\"}",
                    Constants.TENANT_ADMIN_EMAIL, "   "));

            verifyNoInteractions(delegationProvider);
        }

        @Test
        @DisplayName("A cleared credential is NOT read back from the live invoker — the removed value stays removed")
        void clearedCredentialIsNotResurrectedFromTheInvoker() throws DomainWideDelegationException {
            lenient().when(invoker.getAttributes()).thenReturn(savedCredentials);

            // Studio omits a cleared field: the admin removed the key, only the email remains.
            discovery.validateDelegatedAccessIfAlwaysOn(Map.of(
                    Constants.ALWAYS_ON, true,
                    Constants.TENANT_ADMIN_EMAIL, "old-admin@acme.example"));

            verifyNoInteractions(delegationProvider);
        }

        @Test
        @DisplayName("A changed credential is validated as submitted, not as saved")
        void changedCredentialIsValidatedAsSubmitted() throws DomainWideDelegationException {
            lenient().when(invoker.getAttributes()).thenReturn(savedCredentials);
            Map<String, Object> form = Map.of(
                    Constants.ALWAYS_ON, true,
                    Constants.SERVICE_ACCOUNT_KEY, "{\"client_id\":\"333333333333\"}",
                    Constants.TENANT_ADMIN_EMAIL, "new-admin@acme.example");

            discovery.validateDelegatedAccessIfAlwaysOn(form);

            verify(delegationProvider).validateDelegatedAccess(form);
        }

        @Test
        @DisplayName("A failed delegation check still surfaces as the setup signal")
        void failedCheckPropagates() throws DomainWideDelegationException {
            Map<String, Object> form = Map.of(
                    Constants.ALWAYS_ON, true,
                    Constants.SERVICE_ACCOUNT_KEY, "{\"client_id\":\"222222222222\"}",
                    Constants.TENANT_ADMIN_EMAIL, "admin@acme.example");
            org.mockito.Mockito.doThrow(new DomainWideDelegationException("not authorised", null))
                    .when(delegationProvider).validateDelegatedAccess(form);

            org.junit.jupiter.api.Assertions.assertThrows(DomainWideDelegationException.class,
                    () -> discovery.validateDelegatedAccessIfAlwaysOn(form));
        }
    }

    // ── the value type ────────────────────────────────────────────────────

    @Test
    @DisplayName("A resolved scan set is immutable, so a sweep cannot mutate it under itself")
    void userSetIsImmutable() {
        List<String> mutable = new java.util.ArrayList<>(List.of("a@acme.example"));
        CalendarDiscoveryService.UserSet set =
                new CalendarDiscoveryService.UserSet(ENROLLED_USERS, mutable, null);

        mutable.add("b@acme.example");

        assertEquals(1, set.size());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> set.userIds().add("c@acme.example"));
        assertFalse(set.userIds().isEmpty());
        assertFalse(set.degraded());
    }
}
