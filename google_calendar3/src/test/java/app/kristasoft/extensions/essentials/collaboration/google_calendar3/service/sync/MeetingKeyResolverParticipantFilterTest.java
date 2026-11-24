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

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.CaGateway;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.EsbCaGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EF-12 — the cancellation lookup must never be scoped by something Meeting Agent cannot match.
 *
 * <h2>The bug</h2>
 *
 * <p>WP-E16 fixed the cancellation backlog: {@code MeetingKeyResolver} stopped calling a route
 * Meeting Agent has never served and started asking by {@code iCalUId} over the ESB. But the caller
 * passed its scan-set {@code userId} as the lookup's {@code userEmail}, and Meeting Agent applies
 * that as a <b>hard</b> filter on every branch of its precedence. On the consented path a scan-set id
 * can be an opaque {@code wsContact_<uuid>} client key, which is not an address and matches no
 * attendee anywhere — so the lookup returned zero matches, the caller logged "could not resolve a
 * meetingKey" and skipped the delete.
 *
 * <p>The fix reads as a no-change from the outside, which is why it is tested at the filter: nothing
 * throws, nothing logs an error, and a lookup that finds nothing is indistinguishable from a meeting
 * Meeting Agent genuinely does not hold. The whole cancellation fix was inert for every client user
 * and looked like it worked.
 */
@DisplayName("EF-12 — the cancellation lookup's participant filter")
class MeetingKeyResolverParticipantFilterTest {

    private static final String ICAL = "ical-abc@google.com";
    private static final String URL = "https://meet.google.com/abc-defg-hij";
    private static final String START = "2026-09-15T10:00:00Z";

    private EsbCaGateway caGateway;
    private MeetingKeyResolver resolver;

    @BeforeEach
    void setUp() {
        caGateway = mock(EsbCaGateway.class);
        resolver = new MeetingKeyResolver(mock(CalendarSyncTelemetryService.class), caGateway);
        when(caGateway.findMeetingKeys(any()))
                .thenReturn(new CaGateway.MeetingKeyMatch(Set.of("mk-1"), "ICAL_UID"));
    }

    private CaGateway.MeetingLookupCriteria criteriaSentFor(String userEmail) {
        resolver.resolveMeetingKey(ICAL, URL, START, userEmail);
        ArgumentCaptor<CaGateway.MeetingLookupCriteria> sent =
                ArgumentCaptor.forClass(CaGateway.MeetingLookupCriteria.class);
        verify(caGateway).findMeetingKeys(sent.capture());
        return sent.getValue();
    }

    /**
     * The exact shape that made the fix inert. A client user's store key is a UUID behind a prefix —
     * there is no attendee address anywhere in Meeting Agent that could equal it.
     */
    @ParameterizedTest(name = "\"{0}\" is not sent as userEmail")
    @ValueSource(strings = {
            "wsContact_7f3c1e2a-9b4d-4a10-8f55-1c2d3e4f5a6b",
            "wsContact_INV_google_calendar_1234",
            "  wsContact_7f3c1e2a  ",
            "not-an-address",
            "@leading-at-is-not-a-local-part"
    })
    @DisplayName("a non-address scan-set id is dropped, and the lookup still runs on iCalUId")
    void nonAddressIdsAreDropped(String userId) {
        CaGateway.MeetingLookupCriteria criteria = criteriaSentFor(userId);

        assertNull(criteria.userEmail(),
                "MA applies userEmail as a HARD filter, so sending a client key returns nothing and "
                        + "the cancellation is skipped for exactly the users whose keys are not "
                        + "addresses");
        assertEquals(ICAL, criteria.iCalUId(),
                "dropping the filter must WIDEN the lookup, not abandon it — iCalUId is the "
                        + "strongest criterion and is workspace-scoped MA-side already");
        assertEquals(URL, criteria.meetingUrl());
    }

    @Test
    @DisplayName("a real address IS sent, so the lookup stays as narrow as it can be")
    void addressesAreKept() {
        CaGateway.MeetingLookupCriteria criteria = criteriaSentFor("alice@acme.example");

        assertEquals("alice@acme.example", criteria.userEmail());
    }

    @Test
    @DisplayName("an address with surrounding whitespace is trimmed, not dropped")
    void addressesAreTrimmed() {
        assertEquals("alice@acme.example", criteriaSentFor("  alice@acme.example  ").userEmail());
    }

    @Test
    @DisplayName("null and blank stay null — an unresolvable calendar owner widens the lookup")
    void nullStaysNull() {
        // handleCancelledEvent passes the resolved calendar-owner address, and calendars.get can
        // fail. An unscoped lookup on iCalUId is the right degradation: the alternative is no
        // cancellation at all.
        assertNull(criteriaSentFor(null).userEmail());

        resolver = new MeetingKeyResolver(mock(CalendarSyncTelemetryService.class), caGateway);
        assertNull(MeetingKeyResolver.participantFilter("   "));
    }

    @Test
    @DisplayName("the lookup is still refused when neither iCalUId nor meetingUrl is available")
    void refusesWithNoStrongCriterion() {
        // Dropping the participant filter must not turn an unanswerable lookup into an unscoped
        // fishing expedition across the workspace.
        assertNull(resolver.resolveMeetingKey(null, null, START, "alice@acme.example"));
        org.mockito.Mockito.verifyNoInteractions(caGateway);
    }
}
