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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca;

import app.krista.conversationagent.module.calendar.esb.ExtensionCalendarServiceClient;
import app.krista.conversationagent.module.calendar.esb.dto.BulkUpsertMeetingsReply;
import app.krista.conversationagent.module.calendar.esb.dto.CancelMeetingReply;
import app.krista.conversationagent.module.calendar.esb.dto.FindMeetingsReply;
import app.krista.conversationagent.module.calendar.esb.dto.UpsertMeetingReply;
import app.krista.conversationagent.module.shared.esb.ext.ExtensionIdentityServiceClient;
import app.krista.conversationagent.module.shared.esb.ext.dto.ListUsersReply;
import app.krista.extension.executor.Invoker;
import app.krista.infra.esb.ServiceBusConfig;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model.CreateMeetingRequestDto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.ScannedMailboxSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * T11 / EF-02 — every Meeting Agent ESB call is bounded, and bounded by something an operator can
 * change.
 *
 * <h2>The bug this pins</h2>
 *
 * <p>Before WP-E16 every Meeting Agent call was HTTP: with Meeting Agent down the socket refused
 * immediately and a calendar sweep moved on. On the ESB the request is enqueued and the caller waits
 * on a temporary reply queue, and the shipped krista-infra default
 * ({@code broker.response.timeout}) is <b>ten minutes</b>. Nothing in this extension narrowed it, so
 * a Meeting Agent outage turned every call into a ten-minute stall — including the catalog sweeps,
 * which run on the Studio request thread with a person waiting at the other end.
 *
 * <h2>Why the assertion is on the client's config and not on {@code Input.setTimeout}</h2>
 *
 * <p>{@code Input} does declare {@code setTimeout(long)}, and {@code QueueClient.executeInternal}
 * does honour a positive {@code getTimeout()}. But both are {@code default} methods with empty
 * bodies, and Meeting Agent's generated request DTOs implement {@code Input} without overriding
 * either — {@link #maContractRequestsDoNotHonourInputTimeout()} proves that against the staged jar,
 * so that the day CA adds the override this test says so rather than leaving a note nobody rereads.
 * A budget written there would be a green assertion over a value nothing reads, and the ten-minute
 * wait would still be in force. The budget therefore lands on the per-call {@link ServiceBusConfig}
 * copy, which is what {@code QRequester.receive()} actually waits on.
 *
 * <h2>Completeness</h2>
 *
 * <p>Parameterised over every public {@link CaGateway} operation, with
 * {@link #everyGatewayOperationIsExercised()} reading the interface reflectively — so an operation
 * added later is unbounded-until-someone-remembers for exactly as long as it takes the build to go
 * red.
 */
@DisplayName("CA gateway — every ESB call carries a bounded, configurable reply budget (T11)")
class CaGatewayTimeoutBudgetTest {

    private static final String WORKSPACE = "ws-gcal-1";
    private static final String INVOKER_ID = "INV_gcal";

    /** The hard ceiling the backlog names: no call may be allowed to wait longer than this. */
    private static final long CEILING_MILLIS = 120_000L;

    /** Records the reply budget each operation asks for, instead of building a real client. */
    private static final class BudgetCapturingGateway extends EsbCaGateway {

        private final Map<String, Long> budgets = new LinkedHashMap<>();
        private final ExtensionCalendarServiceClient calendar =
                mock(ExtensionCalendarServiceClient.class);
        private final ExtensionIdentityServiceClient identity =
                mock(ExtensionIdentityServiceClient.class);

        BudgetCapturingGateway(Invoker invoker) {
            super(invoker);
            lenient().when(calendar.upsertMeeting(any())).thenReturn(new UpsertMeetingReply());
            lenient().when(calendar.bulkUpsertMeetings(any())).thenReturn(new BulkUpsertMeetingsReply());
            lenient().when(calendar.cancelMeeting(any())).thenReturn(new CancelMeetingReply());
            lenient().when(calendar.findMeetings(any())).thenReturn(new FindMeetingsReply());
            lenient().when(identity.listUsers(any())).thenReturn(new ListUsersReply());
        }

        @Override
        protected ExtensionCalendarServiceClient calendarClient(long replyBudgetMillis) {
            budgets.put("calendar#" + budgets.size(), replyBudgetMillis);
            return calendar;
        }

        @Override
        protected ExtensionIdentityServiceClient identityClient(long replyBudgetMillis) {
            budgets.put("identity#" + budgets.size(), replyBudgetMillis);
            return identity;
        }

        List<Long> captured() {
            return new ArrayList<>(budgets.values());
        }
    }

    private BudgetCapturingGateway gateway;

    /** Stands in for the real client once its config has been read back. */
    private final ExtensionCalendarServiceClient calendarStub =
            mock(ExtensionCalendarServiceClient.class);

    @BeforeEach
    void setUp() {
        Invoker invoker = mock(Invoker.class);
        lenient().when(invoker.getWorkspaceId()).thenReturn(WORKSPACE);
        lenient().when(invoker.getInvokerId()).thenReturn(INVOKER_ID);
        gateway = new BudgetCapturingGateway(invoker);
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(CaCallBudget.READ_TIMEOUT_KEY);
        System.clearProperty(CaCallBudget.BULK_TIMEOUT_KEY);
        System.clearProperty(CaCallBudget.MAX_BULK_KEY);
    }

    private static CreateMeetingRequestDto meeting(String iCalUId) {
        CreateMeetingRequestDto dto = new CreateMeetingRequestDto();
        dto.setiCalUId(iCalUId);
        dto.setTitle("Sync");
        return dto;
    }

    // ── every operation, bounded ───────────────────────────────────────────

    /**
     * One entry per public {@link CaGateway} operation. The name is the method name so
     * {@link #everyGatewayOperationIsExercised()} can diff this list against the interface.
     */
    private static Stream<org.junit.jupiter.params.provider.Arguments> operations() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("upsertMeeting",
                        (Consumer<CaGateway>) g -> g.upsertMeeting(meeting("ical-1"))),
                org.junit.jupiter.params.provider.Arguments.of("bulkUpsertMeetings",
                        (Consumer<CaGateway>) g -> g.bulkUpsertMeetings(List.of(meeting("ical-1")))),
                org.junit.jupiter.params.provider.Arguments.of("deleteMeeting",
                        (Consumer<CaGateway>) g -> g.deleteMeeting("mk-1", "ical-1", "cancelled")),
                org.junit.jupiter.params.provider.Arguments.of("findMeetingKeys",
                        (Consumer<CaGateway>) g -> g.findMeetingKeys(new CaGateway.MeetingLookupCriteria(
                                "ical-1", null, null, null, null))),
                org.junit.jupiter.params.provider.Arguments.of("listLicensedSeatEmails",
                        (Consumer<CaGateway>) g -> g.listLicensedSeatEmails()),
                org.junit.jupiter.params.provider.Arguments.of("reportScannedMailboxes",
                        (Consumer<CaGateway>) g -> g.reportScannedMailboxes(new ScannedMailboxSnapshot(
                                "ENROLLED_USERS", null, "2026-11-09T10:00:00Z",
                                List.of(new ScannedMailboxSnapshot.Mailbox("a@acme.example", "GOOGLE",
                                        "ENROLLED_USER", "SUBSCRIBED", "ch-1", "a@acme.example"))))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("operations")
    @DisplayName("carries a budget in (0, 120s]")
    void everyOperationIsBounded(String name, Consumer<CaGateway> operation) {
        operation.accept(gateway);

        List<Long> captured = gateway.captured();
        assertFalse(captured.isEmpty(),
                name + " built no client, so nothing proved it asks for a budget at all");
        for (Long budget : captured) {
            assertNotNull(budget, name + " asked for a null budget");
            assertTrue(budget > 0,
                    name + " asked for a budget of " + budget + " ms. Zero or negative means "
                            + "krista-infra falls back to broker.response.timeout, which ships at "
                            + "ten minutes — the exact bug EF-02 reported.");
            assertTrue(budget <= CEILING_MILLIS,
                    name + " asked for " + budget + " ms, above the " + CEILING_MILLIS
                            + " ms ceiling. A budget this large is not 'slow', it is unbounded.");
        }
    }

    /**
     * A hand-written list of operations is how the one that nobody bounded ships. Read the interface
     * instead: an operation added without a row above fails here.
     */
    @Test
    @DisplayName("every public CaGateway operation is exercised above")
    void everyGatewayOperationIsExercised() {
        Set<String> exercised = operations()
                .map(args -> (String) args.get()[0])
                .collect(java.util.stream.Collectors.toSet());

        List<String> declared = new ArrayList<>();
        for (Method method : CaGateway.class.getDeclaredMethods()) {
            if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            declared.add(method.getName());
        }

        assertFalse(declared.isEmpty(), "reflection found no CaGateway operations");
        for (String operation : declared) {
            assertTrue(exercised.contains(operation),
                    "CaGateway." + operation + " is not covered by this test, so nothing proves it "
                            + "carries a reply budget. Add a row to operations().");
        }
    }

    // ── the budget actually reaches krista-infra ───────────────────────────

    @Nested
    @DisplayName("The budget lands where krista-infra reads it")
    class Application {

        @Test
        @DisplayName("withReplyBudget sets the config's responseTimeout")
        void budgetBecomesResponseTimeout() {
            // QRequester.receive() waits replyOverride != null ? replyOverride :
            // config.getResponseTimeout(). This is the second limb, and it is the one that applies
            // to a CA contract request.
            ServiceBusConfig budgeted =
                    EsbCaGateway.withReplyBudget(new ServiceBusConfig(), 30_000L);

            assertEquals(Duration.ofMillis(30_000L), budgeted.getResponseTimeout());
        }

        @Test
        @DisplayName("withReplyBudget copies, so one call's budget is not every call's budget")
        void budgetDoesNotMutateTheSharedConfig() {
            // config() hands out the ONE instance every client of that service is built from.
            // Mutating it in place would make the last budget set win for every subsequent call on
            // every thread — a 30 s read would silently narrow the next batched upsert.
            ServiceBusConfig shared = new ServiceBusConfig();
            shared.setResponseTimeout(Duration.ofMinutes(10));

            ServiceBusConfig budgeted = EsbCaGateway.withReplyBudget(shared, 30_000L);

            assertEquals(Duration.ofMillis(30_000L), budgeted.getResponseTimeout());
            assertEquals(Duration.ofMinutes(10), shared.getResponseTimeout(),
                    "the service's shared config must be left exactly as it was");
        }

        /**
         * The end-to-end link: the budget an operation asks for becomes the {@code responseTimeout}
         * on the config the client is actually built from.
         *
         * <p>{@link CaGatewayTimeoutBudgetTest#everyOperationIsBounded} captures the budget handed to
         * the factory and {@link #budgetBecomesResponseTimeout()} shows the helper maps it — but
         * neither, alone, proves the production factory uses the helper. A budget threaded to a
         * factory that ignores it is precisely the failure mode {@code Input.setTimeout} would have
         * been, so it is worth one assertion rather than two halves.
         */
        @Test
        @DisplayName("the production client factory builds its client from the budgeted config")
        void productionFactoryAppliesTheBudget() {
            Invoker invoker = mock(Invoker.class);
            lenient().when(invoker.getWorkspaceId()).thenReturn(WORKSPACE);
            java.util.List<ServiceBusConfig> built = new java.util.ArrayList<>();

            // Subclasses ONLY the config-capturing seam: calendarClient(long) below runs the real
            // production body, and the client it builds is intercepted to read its config back.
            EsbCaGateway gateway = new EsbCaGateway(invoker) {
                @Override
                protected ExtensionCalendarServiceClient calendarClient(long replyBudgetMillis) {
                    ExtensionCalendarServiceClient client = super.calendarClient(replyBudgetMillis);
                    built.add(client.serviceConfig());
                    lenient().when(calendarStub.cancelMeeting(any())).thenReturn(new CancelMeetingReply());
                    client.close();
                    return calendarStub;
                }
            };
            System.setProperty(CaCallBudget.READ_TIMEOUT_KEY, "9000");

            gateway.deleteMeeting("mk-1", "ical-1", "cancelled");

            assertEquals(1, built.size(), "the production factory did not build a client");
            assertEquals(Duration.ofMillis(9_000L), built.get(0).getResponseTimeout(),
                    "the config the client is built from must carry this call's budget — that value "
                            + "is what QRequester.receive() waits on. A budget that reaches the "
                            + "factory and not the config is the same silent no-op as "
                            + "Input.setTimeout.");
        }

        /**
         * The reason this whole test asserts on the config rather than the request. If CA ever gives
         * its request DTOs a real {@code setTimeout}, this goes red and the simpler route becomes
         * available — which is a better outcome than a comment nobody rereads.
         */
        @Test
        @DisplayName("MA's contract requests still do NOT honour Input.setTimeout")
        void maContractRequestsDoNotHonourInputTimeout() throws Exception {
            Class<?> request =
                    Class.forName("app.krista.conversationagent.module.calendar.esb.dto.UpsertMeetingRequest");

            boolean overridesSetTimeout = declaresOwn(request, "setTimeout", long.class);
            boolean overridesGetTimeout = declaresOwn(request, "getTimeout");

            assertFalse(overridesSetTimeout || overridesGetTimeout,
                    "UpsertMeetingRequest now overrides Input's timeout methods. Input.setTimeout is "
                            + "the route QueueClient.executeInternal honours via a reply-timeout "
                            + "override, so the per-call ServiceBusConfig copy can be replaced with a "
                            + "plain request.setTimeout(...). Re-read CaCallBudget's javadoc and "
                            + "simplify.");

            // And the default really is inert, so the assertion above is about behaviour and not
            // merely about declarations.
            Object instance = request.getDeclaredConstructor().newInstance();
            request.getMethod("setTimeout", long.class).invoke(instance, 30_000L);
            assertEquals(0L, request.getMethod("getTimeout").invoke(instance),
                    "Input's default setTimeout stored a value — see above");
        }

        private boolean declaresOwn(Class<?> type, String name, Class<?>... params) {
            try {
                type.getDeclaredMethod(name, params);
                return true;
            }
            catch (NoSuchMethodException absent) {
                return false;
            }
        }
    }

    // ── configuration ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Budgets come from configuration, clamped")
    class Configuration {

        @Test
        @DisplayName("defaults are the packaged 30 s read / 60 s bulk")
        void packagedDefaults() {
            assertEquals(30_000L, CaCallBudget.readMillis());
            assertEquals(60_000L, CaCallBudget.bulkMillis());
            assertEquals(50, CaCallBudget.maxMeetingsPerBulk());
        }

        @Test
        @DisplayName("a configured value is honoured")
        void configuredValueWins() {
            System.setProperty(CaCallBudget.READ_TIMEOUT_KEY, "5000");
            System.setProperty(CaCallBudget.BULK_TIMEOUT_KEY, "45000");
            System.setProperty(CaCallBudget.MAX_BULK_KEY, "25");

            assertEquals(5_000L, CaCallBudget.readMillis());
            assertEquals(45_000L, CaCallBudget.bulkMillis());
            assertEquals(25, CaCallBudget.maxMeetingsPerBulk());
        }

        @Test
        @DisplayName("a value above the ceiling is clamped — the ten-minute wait cannot be restored")
        void ceilingIsEnforced() {
            // The whole point of the change. An operator who "fixes" a slow sweep by setting ten
            // minutes has reinstated EF-02, so configuration cannot express it.
            System.setProperty(CaCallBudget.READ_TIMEOUT_KEY, "600000");
            System.setProperty(CaCallBudget.BULK_TIMEOUT_KEY, "600000");

            assertEquals(CaCallBudget.MAX_MILLIS, CaCallBudget.readMillis());
            assertEquals(CaCallBudget.MAX_MILLIS, CaCallBudget.bulkMillis());
            assertTrue(CaCallBudget.MAX_MILLIS <= CEILING_MILLIS);
        }

        @Test
        @DisplayName("a value below the floor is clamped — a typo cannot refuse every call")
        void floorIsEnforced() {
            System.setProperty(CaCallBudget.READ_TIMEOUT_KEY, "30");

            assertEquals(CaCallBudget.MIN_MILLIS, CaCallBudget.readMillis());
        }

        @Test
        @DisplayName("garbage and blanks fall back to the default rather than failing the call")
        void unparseableFallsBack() {
            System.setProperty(CaCallBudget.READ_TIMEOUT_KEY, "thirty seconds");
            assertEquals(30_000L, CaCallBudget.readMillis());

            System.setProperty(CaCallBudget.READ_TIMEOUT_KEY, "   ");
            assertEquals(30_000L, CaCallBudget.readMillis());
        }

        @Test
        @DisplayName("the configured budget is what the call asks for, not the default")
        void configuredBudgetReachesTheCall() {
            System.setProperty(CaCallBudget.READ_TIMEOUT_KEY, "7000");

            gateway.deleteMeeting("mk-1", "ical-1", "cancelled");

            assertEquals(List.of(7_000L), gateway.captured());
        }

        @Test
        @DisplayName("EF-29: the bulk cap is configuration, and the chunking obeys it")
        void bulkCapIsConfigurable() {
            System.setProperty(CaCallBudget.MAX_BULK_KEY, "2");
            List<CreateMeetingRequestDto> five = List.of(meeting("a"), meeting("b"), meeting("c"),
                    meeting("d"), meeting("e"));

            gateway.bulkUpsertMeetings(five);

            assertEquals(3, gateway.captured().size(),
                    "five meetings at a cap of two is three requests — the cap must come from the "
                            + "property, not from a compiled-in 50");
        }

        @Test
        @DisplayName("bulk calls get the bulk budget and single-row calls the read budget")
        void bulkAndReadBudgetsAreDistinct() {
            System.setProperty(CaCallBudget.READ_TIMEOUT_KEY, "11000");
            System.setProperty(CaCallBudget.BULK_TIMEOUT_KEY, "22000");

            gateway.upsertMeeting(meeting("ical-1"));
            gateway.bulkUpsertMeetings(List.of(meeting("ical-2")));
            gateway.listLicensedSeatEmails();

            assertEquals(List.of(11_000L, 22_000L, 22_000L), gateway.captured(),
                    "a batched upsert and a 1000-row seat page must not be held to a single-row "
                            + "budget, and a single-row call must not be held to the batch's");
        }
    }
}
