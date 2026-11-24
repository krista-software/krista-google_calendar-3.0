# Change Log

All notable changes to the `google_calendar3` module are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [3.1.3] — 2026-11-10

Always On is now either/or (owner rulings, 2026-11-10). It replaces the "token fact" approach of the
unreleased 3.1.3 build, which tried to tell Meeting Agent which seats also held a personal sign-in.
That question has no good answer while both credentials are in play: one person could be Connected
because delegation read their calendar, or because they once signed in, and the Console could not
tell which. With one credential per mode, Connected means one thing.

### Changed

- **Always On reads calendars through domain-wide delegation and nothing else.** No calendar path
  builds a client from a personal (Opt-In) refresh token while the flag is on: discovery, watch
  channels, renewal, push-notification sync, full sync and single-user sync all use delegation, and
  `CalendarDiscoveryService.calendarClientFor` refuses a personal-token client
  (`PersonalTokenRefusedException`). This covers a source resolved just before the flag flipped too.
  `sourceFor` answers delegation for everybody under Always On. Whether a user may be read at all is
  `isSyncable`'s question: registered seats with delegation configured, never the consented list.
  The stored tokens are left alone, so switching Always On off goes straight back to Opt-In.
- **A degraded Always On sweep scans nothing.** When delegation is not configured or refused, the
  seat roster has never been read, or the Workspace directory cannot be listed, discovery returns an
  empty delegated scan set carrying `degradedReason`. It used to fall back to the consented users.
  The sweep publishes no statuses and revokes no channels. Held channels keep being renewed. The
  reason goes to Meeting Agent on an empty snapshot. The fallback made Connected mean two different
  things in one workspace, which is why it is gone.
- **Connected now changes when a calendar stops being readable.** Under Always On:
  - a successful delegated read, watch, renewal or sync re-asserts `CONNECTED`;
  - a mailbox previously `CONNECTED` whose read failed, which the Workspace directory no longer lists
    (`NOT_IN_DIRECTORY`), or which left the scanned set (`OUT_OF_SCOPE`) publishes `DISCONNECTED`
    with `reason=ACCESS_REVOKED` and the Google error in `errorCode`/`errorMessage`;
  - a seat never reached gets no status.

  The comparison is made against the local status store, so a repeat failure publishes once.
  Departures are published even on an empty roster, where the channels are deliberately kept. Our
  own missing `serviceUrl` is a configuration fault, not a fact about the mailbox, and publishes
  nothing.
- **Personal-token statuses stand down under Always On.** `CalendarConnectionStatusStore` reads the
  live mode (`setAlwaysOnMode`). While Always On is on, the OAuth-callback `CONNECTED`, the refresh
  path's `REAUTH_REQUIRED` / `RENEWAL_FAILED` and the user-disconnect `DISCONNECTED` neither write
  nor publish. Those statuses would ask a person to re-authenticate something they cannot. The
  personal-token health check (`CalendarConnectionReconciliationService`) does not run, and the
  legacy channel lookup does not read the consented list. Opt-In behaviour is unchanged.
- **The scanned-mailbox snapshot follows every Always On sweep that resolves a scope.** Watch
  subscribe, the seat reconcile, renewal and the full sync all report. Renewal had not, because it
  touches only expiring channels; untouched seats now report their last delegated outcome
  (`SUBSCRIBED` for a connected mailbox holding a watch, `FAILED` after a failed read, otherwise
  `PENDING`) rather than flipping to `PENDING`. A degraded sweep sends an empty snapshot with its
  reason. Opt-In sweeps send no snapshot, because Meeting Agent keeps no Always On inventory for
  them.

### Added

- **Meeting Agent can see seats Google does not have.** `reportScannedMailboxes` on
  `ca.identity-1-IN` carries `SUBSCRIBED`, `PENDING`, `FAILED` or `NOT_IN_DIRECTORY` rows. A seat
  reads `NOT_IN_DIRECTORY` only after a complete directory read. A listing that stopped early
  reports its absent seats as `PENDING`, and a failed read reports none. Reporting is best-effort
  and never fails a sweep.

### Removed

- `userTokenHeld` (field 10 of `CalendarConnectionStatusChanged`), added in the unreleased 3.1.3
  build. Under either/or a status describes exactly one credential, so the fact has nothing left to
  say. The proto reserves field 10 and the name so the number is never reused (Meeting Agent
  reserves it too).
- The renewal-time re-publish of `CONNECTED` for consented users, which existed only to deliver
  that field.
- The "dropped consented users" report (`droppedConsentedUsers`). It read the personal-consent list
  on every Always On sweep.

## [Unreleased]

### Changed

- Restage the CA contracts jar and fingerprint (`0bf40565…`, CA PR #446: `MeetingViewProto` fields
  20 and 21). The CA build that ships with KIM-237 reports that fingerprint, and against the
  previously staged `d1cb375…` the startup handshake refused to start this extension's ESB
  consumers. Deploy after that CA build; this extension reads neither new field.

- Google Calendar push channels now call Conversation Agent's provider callback
  (`<serviceUrl>/api/v1/calendar/provider/google/notification?workspaceId=&invokerId=`) instead of
  this extension's own `/rest/googlecalendar/calendar/notification`; CA relays them here. Existing
  channels move over at their next renewal, which stops the old channel and opens a new one.
  Without a `serviceUrl` attribute the watch is refused rather than registered at the old address.

### Fixed

- Google calendar sync review regressions: restore committed-only ESB refresh after Studio
  validation; invalidate conversational settings when committed Invoker attributes change (so
  Studio Always On OFF takes effect without a reload); refuse unscoped seat/daily queue bindings.
  Cache trusted lifecycle execution mode for background rebinds, emit binding-refusal/readiness
  telemetry (including failed context captures), and require both sync consumers for LIVE readiness.
  Do not activate merged OAuth candidates or continue invoker setup after an OAuth attribute save fails.

### Added

- Full and incremental sync send events without meeting links when another person is invited.
  Personal blocks are excluded before mailbox-owner enrichment.

- **A nightly full-tenant calendar sweep, driven by Meeting Agent** (KIM-492). New consumer on
  `ext.calendar.sync.daily.google` calling `sendEventsToMA(days)` — the same sweep the
  `Adaptive Calendar Sync` catalog request performs, now on a schedule again.

  **What was lost.** That catalog request is the only thing in this extension that sweeps every
  calendar in the scan set, and nothing here has ever run it on a timer: the 15-minute seat reconcile
  and the 6-hourly watch renewal create MISSING watch channels and never sync. A Krista conversation
  invoked it daily. Always On removes the conversations — the point of Always On — and took the daily
  sweep with it. Watch channels only deliver on CHANGE, and a channel can expire, be revoked, or miss
  a delivery, so the sweep is the reconciliation floor underneath all of it.

  **Meeting Agent drives it rather than a local timer** because this extension has no cross-replica
  protection of any kind — the renewal executor is started unconditionally on `INVOKER_LOADED`, with
  no lock and no leader election — so a midnight sweep here would run once per replica and multiply
  Google load at the worst possible moment. MA has a distributed scheduler lock, and the
  enable/disable and timing configuration lives there.

  Deliberately shaped as a near-copy of teams-transcribe's handler so a change to one is visibly a
  change the other needs: foreign workspaces ignored, a failed sweep **acknowledged rather than
  rethrown** (durable manual-ack queue; quota exhaustion fails every redelivery exactly as it failed
  the first — tomorrow night's command is the retry), the requested window clamped to 1–365, and a
  10-minute debounce because a whole-roster sweep repeated minutes later re-reads every calendar to
  learn nothing.


- **A seat registration can now BACK-FILL that user's upcoming calendar, not merely watch it**
  (KIM-492). Meeting Agent's `ext.calendar.seat.registered.google` event carries two new optional
  fields — `syncCalendar` and `syncDays` — and when `syncCalendar` is true this extension reads the
  newly seated user's calendar forward over `syncDays` (default 30, clamped to 365) and pushes those
  events to Meeting Agent, starting from the **start of today**.

  **The gap this closes.** Registering a seat established a Google *watch channel* and nothing else,
  and a watch channel only delivers on CHANGE. A meeting already sitting on a new user's calendar
  that nobody subsequently edits produced no notification at all. The sharpest form of it is that
  `createWatchChannel` already walks the user's calendar to establish a baseline sync token — and
  keeps only the token, discarding every event it just saw. That was masked for years by the "Connect
  My Calendar" conversation, which synced as a side effect; Always On removes that conversation
  deliberately, and removed the only thing that populated a new user's diary with it.

  **Syncability is checked explicitly rather than inferred.** `sendEventsToMAByUser` returns `void`
  and returns *quietly* for a user it cannot reach, which is indistinguishable from a successful
  back-fill of an empty diary — so the handler asks `isSyncable` first and reports the difference.
  (The Microsoft equivalent throws, which is why this check has no counterpart in KIP.)

  **Nothing retries a back-fill.** A failure is logged at WARN naming the user and the event is
  acknowledged, on the same durable manual-ack reasoning as the reconcile — but unlike the reconcile
  there is no scheduled pass behind it, so a lost back-fill stays lost until one of that user's
  meetings changes or an operator runs Calendar Sync By User. The watch channel, which is the durable
  half, is unaffected either way; a failing reconcile likewise does not suppress the back-fill.

  Absent fields mean "not asked for": an event from a Meeting Agent that predates them back-fills
  nothing, which is today's behaviour exactly. The staged contracts jar was refreshed to carry the
  two fields; **the `.fingerprint` sidecar is unchanged**, which is the mechanical confirmation that
  this is an additive change off the wire-contract hash rather than a renumbering.


- **KIM-492 — this extension now consumes `ext.calendar.workspace.messages.google`.** Meeting Agent
  broadcasts a workspace's two in-call messages (the standard Krista welcome and the customer's
  compliance notice) fire-and-forget to each of the workspace's *bound* calendar systems, on every
  admin write of `PUT`/`DELETE /api/v1/workspaces/{id}/settings/messages` and for every workspace at
  Meeting Agent's own boot.

  **Google Meet has nothing to present them on.** The Krista Bot carries both messages itself in its
  `BOT_CONFIG`, so this extension renders neither — but the destination is *durable* and addressed per
  bound system, so with no consumer every Google-bound workspace's broadcasts accumulate on the broker
  indefinitely, one per settings save plus one per Meeting Agent boot. That is the same hole EF-14
  closed on the seat-registered queue, and `GoogleCalendarWorkspaceMessagesHandler` is deliberately
  shaped as its near-copy so a change to one is obviously a change the other needs.

  So the broadcast is consumed **and stored** (`WorkspaceMessagesStore`, beside `RegisteredSeatStore`).
  Storing is what makes draining honest: a future Google-side surface reads state it was already sent
  rather than asking Meeting Agent for state that had been thrown away.

  - **An empty field CLEARS; it never means "no change".** Meeting Agent dispatches a withdrawn
    compliance notice as an *empty* notice on purpose, so it stops being presented at once rather than
    at the next scheduled refresh. Reading blank as "leave the previous value alone" would keep
    presenting legal copy the customer had retracted — the one direction in this feature whose
    staleness has consequences outside the product. "Received and cleared" stays distinguishable from
    "never received": the first is a present snapshot with an absent field, the second is no snapshot
    at all, and a future presenter needs the difference.
  - **A foreign workspace's broadcast is ignored and reported.** Isolation is by the consumer's tenant
    selector; a mismatch here means that selector is not working, and acting on it would overwrite this
    workspace's compliance notice with another customer's legal copy — silently, because the store
    write succeeds either way. The tenant check matters more here than on the seat consumer, where a
    misroute costs one redundant roster refresh.
  - **It catches `Throwable` and acknowledges a failed store.** The queue is durable and manual-ack, so
    an escaping `Error` — realistically a `NoClassDefFoundError` from a mis-staged contract jar —
    redelivers against conditions that fail every time, on a queue whose whole purpose here is to be
    drained. Meeting Agent re-broadcasts at its next boot and on the next settings save, which is the
    recovery path.
  - **The message text never reaches a log.** Lengths, sources and the correlation id do.

  The staged contract jar was refreshed for `CalendarWorkspaceMessagesProto`; its `.fingerprint` is
  unchanged because — exactly like the seat-registered schema — this proto sits **outside** the
  fingerprinted set, so the startup handshake cannot report it missing. `CaContractFingerprintTest`
  now asserts its four jar entries and its generated marshaller directly, and
  `CaContractProtoRegistrationTest` asserts the initializer is both registered and load-bearing.
  Those two guards are the only things that can catch a truncated stage.

- **Coverage for three previously-untested classes** (deps review): `CalendarNotificationHandler`
  (teams-transcribe has a counterpart; this had none) — the `resourceState` gate, the three-source
  cancellation precedence, EF-12's owner scoping, containment of a failing lookup and an unbuildable
  Google client, and sync-token advance; `GoogleCalendarReminderDecideService`/`…Definition` — the
  names that must line up for a message to be delivered at all, none of which is derived from another
  because the annotation processors cannot run in this build; and `InvokerDetails` — the Gson **parsing
  contract** behind the startup handshake, where a renamed key is not a parse error but a check that
  silently stops happening, an absent fingerprint must stay `null` rather than read as drift against
  every pre-WP-E2 Meeting Agent, and `toString` must not print the broker password.

- **KIM-474 / EF-14 — this extension now consumes `ext.calendar.seat.registered.google`.** Meeting
  Agent has been dispatching a licensed-seat notification to that destination for every bound Google
  workspace and nothing was reading it: the queue is durable, so the events accumulated on the broker,
  and a newly-licensed Google user stayed invisible to this extension until the next roster refresh.

  `GoogleCalendarSeatRegisteredHandler` refreshes the seat roster and reconciles the watch scope
  immediately, so a new seat is watched in seconds instead of waiting for a sweep. Three properties it
  gets right that its Teams counterpart does not:

  - **A foreign workspace's event is ignored and reported.** The destination is shared per calendar
    system with isolation by the consumer's tenant selector; a mismatch here means that selector is
    not working, and acting on the event anyway would make the misconfiguration invisible because the
    reconcile succeeds either way (teams-transcribe makes no such comparison — S-20). An event with a
    blank workspace is treated as ours: Meeting Agent populates it today, and refusing an unattributed
    hint would silently disable the fast path, which is worse than one redundant refresh.
  - **It catches `Throwable`, not `RuntimeException`** (EF-27). The queue is durable and manual-ack, so
    an escaping `Error` — realistically a `NoClassDefFoundError` from a mis-staged contract jar —
    redelivers exactly like an exception, against conditions that fail every redelivery.
  - **A failed reconcile is acknowledged, never rethrown.** The scheduled reconcile *is* the retry: it
    is idempotent and picks the seat up on its next run, so a redelivery loop would add nothing.

- **KIM-474 / EF-23 — a 15-minute registered-seat reconcile, matching teams-transcribe.** Added to
  the existing single-threaded scheduler (two tasks on one thread cannot overlap, which keeps a
  reconcile out of the middle of a renewal sweep — both walk the scan set and issue Google calls).

  The roster was previously refreshed only when a sweep happened to call discovery, and the only
  scheduled sweep is the six-hourly renewal — so a Google seat could wait six hours where a Teams seat
  waits fifteen minutes, and an operator had no way to tell a cadence difference from a fault.
  Deliberately *not* folded into the renewal cadence in either direction: renewal is three Google
  round trips per mailbox and channels live five days, while the reconcile is one Meeting Agent round
  trip plus a key-value read per user on an unchanged workspace, and free on an opt-in one. It also
  carries the out-of-scope revoke, so a **revoked** seat now stops being watched within fifteen
  minutes — Meeting Agent emits no revocation signal, so this timer is the only thing that can notice.

### Added

- **KIM-492 / P-01 — `broker.producer.session.pooling.enabled=true` in
  `esb/esb_default.properties`.** Required, not tuning. krista-infra defaults it to `false`, and
  with it off **every** request/reply call creates its own temporary Artemis reply queue
  (`<base>-REPLY-<ver>-<uuid>`, `auto-delete-delay=600000`) — so a reply queue outlives its call by
  ten minutes and steady-state live queues sit at roughly call-rate × 600 s. WP-E16 moved this
  extension's whole Meeting Agent surface onto that path, so the churn arrived with it. A broker
  carrying a large population of auto-delete queues has wedged in production before (2026-08-31),
  and the symptom was Meeting Agent answering 401 because the broker had stopped answering at all.
  The pooled path has a documented krista-infra fallback, so an environment where pooling cannot be
  established still works — it just goes back to a queue per call. Must be set in CA and in every
  extension together; one participant left unpooled still generates the churn.

  `EsbDefaultPropertiesTest` asserts this and the EF-02 budget keys against the packaged resource. A
  properties file is the easiest thing in a repository to lose in a merge — nothing references it by
  symbol and no compiler notices — and what it reintroduces is a slow failure nobody connects to a
  config diff weeks later.

- **KIM-492 / EF-02 — every Meeting Agent ESB call now carries a bounded, configurable reply
  budget.** Three new keys in `esb/esb_default.properties`, clamped by `CaCallBudget`:

  | Key | Default | Clamp | Applies to |
  |---|---|---|---|
  | `ca.esb.request.timeoutMs` | `30000` | `[1000, 120000]` | `upsertMeeting`, `cancelMeeting`, `findMeetings` |
  | `ca.esb.bulkRequest.timeoutMs` | `60000` | `[1000, 120000]` | `bulkUpsertMeetings`, one page of `listUsers` |
  | `ca.esb.bulkRequest.maxMeetings` | `50` | `[1, 1000]` | EF-29: MA's batch cap, no longer compiled in |

  Before WP-E16 every Meeting Agent call was HTTP: with Meeting Agent down the socket refused
  immediately and a sweep moved on. On the ESB the request is enqueued and the caller waits on a
  temporary reply queue, and the shipped krista-infra default `broker.response.timeout` is **ten
  minutes**. Nothing narrowed it — so a Meeting Agent outage turned each call into a ten-minute
  stall, on catalog sweeps that run on the Studio request thread with a person waiting.

  **The budget is applied through a per-call `ServiceBusConfig` copy carrying `setResponseTimeout`,
  NOT through `Input.setTimeout(...)`.** That matters enough to record. `Input` does declare
  `setTimeout(long)` and `QueueClient.executeInternal` does honour a positive `getTimeout()` by
  installing a reply-timeout override — but both methods are `default` interface methods with empty
  bodies, and Meeting Agent's generated request DTOs implement `Input` without overriding either
  (verified with `javap` against the staged `conversation-agent-contracts-2.2.0.jar`). Calling
  `request.setTimeout(30_000)` stores nothing. Writing it anyway would have produced a green
  assertion over a value nothing reads while the ten-minute wait stayed in force. The budget instead
  lands where `QRequester.receive()` actually reads it: `replyOverride != null ? replyOverride :
  config.getResponseTimeout()`. A **copy** of the service config, never the shared static instance
  `config()` hands out — mutating that in place would make the last budget set win for every
  subsequent call on every thread. All extension-side: no CA contract change, no Studio attribute.

  The ceiling is the point of the clamp: an operator who "fixes" a slow sweep by setting ten minutes
  has reinstated the bug, so configuration cannot express it. The floor stops a value meant as
  seconds from cancelling every healthy call. Garbage or blank falls back to the default with one
  WARN — an unreadable tunable must not stop the extension talking to Meeting Agent at all.

  `CaGatewayTimeoutBudgetTest` is parameterised over every public `CaGateway` operation with a
  reflection completeness check, so an operation added later is unbounded only until the build goes
  red. It also asserts that MA's contract requests still do **not** honour `Input.setTimeout` — the
  day CA adds the override, that test goes red and the simpler route becomes available, which beats a
  comment nobody rereads. And one case runs the **real** client factory and reads the config back off
  the client it built: a budget that reaches the factory but not the config would be the same silent
  no-op as `Input.setTimeout`, so it is asserted directly rather than inferred from two halves. (The
  same `Input.setTimeout` finding was reached independently by the MA-ext work; all three extensions
  land on the `ServiceBusConfig` route.)

### Changed

- **Validate Attributes no longer refuses to save Always On while the delegation credentials are
  missing; the delegation check runs once the tenant admin email and the service-account key are
  both there** (KIM-492, parity with teams-transcribe's optional service-account email). An admin can
  now switch Always On on in Studio, save, and add `tenantAdminEmail` and `serviceAccountKey` later —
  through Validate Attributes again, or through the Save Configuration catalog request, which then
  authenticates. Both attributes were already optional on the invoker and Always On without them was
  already a documented, working state (capture stays on the individually-authenticated users, and
  every sweep logs the gap), so the validation was stricter than the runtime it guarded: it demanded
  the whole configuration in one sitting and, worse, would not let a bad key be removed.

  **Exactly the form Studio sends is validated.** Studio's Setup form carries every attribute and
  omits a field the admin cleared; the check used to fill absent keys back in from the live invoker,
  so a removed key or admin email was validated anyway and, when the stale value failed, the cleared
  state could not be saved at all. Nothing is completed from the saved attributes on this path any
  more. The Save Configuration request — the other caller — completes its own partial form from the
  saved configuration before it calls the check, and still requires both credentials when Always On
  is on. A present-but-failing configuration surfaces exactly as before: the delegation-setup
  authorization signal, with the reason.

- **Save Configuration starts from the saved configuration and changes only what it is given, so it
  can be run with just the email** (KIM-492, parity with teams-transcribe). The request now completes
  what it was handed from everything already saved for the invoker *before* validating and
  authenticating: the Studio attributes (service URL and API key, the delegation credentials, Always
  On, retry) and the OAuth client configuration an earlier run of this request stored in the
  `GoogleAttributeStore` (client id and secret, mode, email — which Studio never sees). A form
  carrying only the email therefore uses the saved mode and client, adds the email, and goes through
  exactly the authentication a fully supplied form does: the per-user OAuth grant for that email
  (resuming the conversation after the callback, as before), or the domain-wide-delegation check
  when Always On is on. The saved result carries everything.

  **Blank keeps, it does not clear.** An untouched form field arrives blank, and a blank now keeps the
  saved value instead of overwriting it — previously a blank `serviceUrl` in the form was written
  over the saved one and even fed to the ESB rebind. Clearing a required attribute through this
  request was never a valid intent (validation refuses a blank anyway), so nothing is lost. Missing
  means missing from BOTH the form and the saved configuration, and the refusal names the fields.
  Only what the request actually carried decides whether the invoker map is rewritten and the CA
  connection rebound: an email-only run touches neither.

  **`alwaysOn=false` does not switch a saved Always On off.** An untouched toggle is
  indistinguishable from a deliberate one, and an email-only save that silently dropped a tenant from
  delegation back to per-user OAuth — prompting the admin to connect a personal calendar — would be
  the KIM-474 wipe in a new coat. Turning Always On ON through the request works as before; turning
  it OFF is the Studio toggle's job, and when the request declines to do it the response says
  *"saved successfully (Always On stays on; use the Studio toggle to turn it off)"* and the log
  records it.

  **A different email is a different grant.** The stored-token shortcut (same configuration, valid
  refresh token → no re-authentication) still applies only when the completed configuration matches
  what was saved; a new email never rides on the previous user's token.

### Fixed

- **Draft/test Google instances no longer consume live CA sync requests.** Only a known `LIVE`
  execution mode binds the seat-registration and daily-sync consumers, on both startup and broker
  reconnect. Previously the draft, test and live instances competed for the same tenant queue; an
  unconfigured instance could acknowledge the registration without refreshing the live roster,
  publishing `CONNECTED` or back-filling events. Missing/unreadable execution mode fails closed.
  OAuth, reminders and workspace-message consumers are unchanged. Regression tests exercise startup
  and reconnect with live, draft, test and unavailable modes, without opening broker connections.

- **CA-requested Google sync restores the invoker context on ESB workers.** Seat-registration and
  daily-sync handlers now use the same invoker-scoped context holder as webhook processing, with
  worker context restored in `finally`. Startup captures that context before starting consumers,
  including queued messages. A missing lifecycle context or unresolved local workspace does not
  permit calendar reads. Registration still refreshes licensed seats and establishes watches before
  its explicitly requested back-fill; setup remains watch-only, and sync windows/ownership rules
  are unchanged. Regression tests cover context-less workers, foreign workspaces and failures.

- **Google Always On setup no longer requires an extension upgrade to take effect.** After a
  successful Save Configuration request, discovery and delegated credentials use the committed
  settings immediately, even when the injected Invoker still has its pre-save snapshot. The
  extension starts registered-user watch reconciliation (which publishes verified `CONNECTED`
  status to CA). Save Configuration does not start event sync: existing CA seat-registration and
  daily-sync commands retain control of sync requests and their windows, with ACTIVE, licensed-user
  scope unchanged. Failed saves do not activate settings; opt-in authentication is unchanged.
  Validation neither clears committed settings nor rebinds the broker. Invoker load picks up committed
  Studio settings. Existing organizer mapping and CA meeting ownership rules are unchanged.

- **A user whose Google authorization had lapsed no longer floods the log with stack traces**
  (2026-10-06). `MustAuthorizeException` is control flow, not a failure: it is the signal that tells
  the platform to launch the OAuth consent flow. A user whose refresh token had expired or been
  revoked produced one on every attempt, and the seat reconcile retries every user in the scan set
  every 15 minutes — forever, until that user reconnects. It was logged at ERROR *with a full stack
  trace, twice*: once where the Calendar client was built (`getCalendarClient`) and again where the
  sweep caught the `RuntimeException` that wrapped it (`forEachUser`). About 33 log lines per stale
  user per tick, roughly 3,000 lines a day.

  The trace earned none of that. Every occurrence has the identical frame list — same scheduler,
  same sweep, same provider — and its deepest cause is our own `createMustAuthorizationException`
  rather than anything Google reported, so it never carried more than the class name and message
  already did. Nor was it the only record: `CalendarConnectionStatusStore` already publishes
  `REAUTH_REQUIRED`, which raises the user's reconnect prompt in Conversation Agent.

  Now: one WARN line per user carrying the user id and the reason, one INFO summary per sweep
  (`N of M user(s) must reauthorize…`), and the stack trace at DEBUG so a pod can get it back
  without a rebuild. Every *other* failure keeps its ERROR and its trace untouched — those are the
  ones where the trace is the diagnostic. The classifier is `AuthFailures`, which walks the cause
  chain because the sweeps only ever see a wrapper.

  Two latent problems fixed on the way. `getCalendarClient` wrapped the signal in a bare
  `RuntimeException`, which **hid its type from any caller that acts on it**; it now propagates
  unchanged. And both ends of that call logged every failure, so genuine errors were double-logged
  too — the client builders are now silent and the caller logs once, with the user id attached.

- **Stopped the endless Infinispan login failures from krista-infra's telemetry sink** (2026-09-21). krista-infra 2.0.434's `esb` module ships `:pubsub` as a runtime-only dependency so `ManagementTelemetryPublisher` can discover `PubSubManagementTelemetrySink` by `ServiceLoader`. That sink opens a data grid with an empty `DGConnectionConfig`, which falls back to infra's bundled `default_data_grid.properties` (`server_list=infinispan:11222`, `auth_username=infinispan`). In a customer appliance that bare name resolves to the appliance's own Infinispan, which rejects the user (`ELY05161: Unable to retrieve password for "infinispan"`); where no such service exists it fails with an unknown host. Either way it retried forever: roughly 1,000 errors per pod every two hours. This extension has no data grid, and infra has no property that disables the sink (it is found by classpath presence), so `build.gradle` now excludes `app.krista.infra:pubsub` from every configuration. ESB traffic and raw ESB management telemetry are unchanged; the ESB still binds only to the endpoint Meeting Agent's handshake names. **Deliberately temporary (owner decision 2026-09-21):** the grid credentials the sink needs (`conversation_agent`, a different password per environment) have no delivery route to the extension today. A later release will externalise them in a form the extension can consume and then re-enable the sink by removing this exclusion. No extension behaviour depends on it; today it only decides whether management telemetry is also written to the grid.

- **A disconnected user stayed registered, and a connected one could be read on the wrong
  credential** (2026-09-16). KIP parity: the same class of defect, found while fixing it there.

  The authenticated-user list is written **verbatim** at registration, in whatever case the OAuth
  flow supplied, while the other side of the same decisions lower-cases — `CalendarDiscoveryService`
  normalises tenant and scan members, and Meeting Agent lower-cases every email in the seat roster
  it sends us. Three comparisons spanned that asymmetry with exact string matching.

  - `isUserAuthenticated` decides which **credential** a calendar is read with: yes means the user's
    own OAuth token, no means the Always On path through the domain-wide-delegation service account.
    It compared raw key strings, so it missed the same person under any other key form. Casing was
    the mild case (`Ana@Contoso.com` vs `ana@contoso.com`); the sharp one is
    `workspace_auth:{ws}:{email}`, written by the Meeting-Agent-driven reconnect flow, which no
    casing rule could ever have bridged — the strings differ by a prefix. A user who reconnected
    through Meeting Agent therefore read as unauthenticated, and their valid token sat unread while
    we impersonated them through the service account. Domain-wide delegation usually covers the same
    mailbox, which is what kept this hidden rather than obvious — until delegation is scoped to an
    OU, or the person sits outside the delegated domain. All three key forms `UsersResource` already
    parses (bare email, `workspace_auth:`, legacy `email#suffix`) are now resolved to the same
    person. `wsContact_<id>` keys deliberately are not: they are platform accounts, excluded from
    the user listing for the same reason, and inventing an email for one risks matching a platform
    account to a human.
  - `unregisterUser` removed only a byte-identical entry, so a disconnect could leave the person
    registered. They keep being swept, their revoked token fails with `invalid_grant`, and Meeting
    Agent nags them to reconnect the calendar they deliberately disconnected. A disconnect that
    silently fails to take effect is worse than one that fails loudly.
  - `registerUser` could add a **second** entry for the same person, which then survives a
    disconnect one at a time and leaves the list disagreeing with itself.

  All three now compare on a trimmed, lower-cased key, and `unregisterUser` removes every entry
  naming that person. Comparison is normalised rather than the stored values rewritten: the list is
  durable state shared with the sweeps, and answering a question correctly does not need a
  migration.

  **Not applicable from the KIP fix:** the connection-status channel. Google's extension only ever
  publishes a status on genuinely delegated events — `registerUser` (OAuth completion, and the
  reconciliation that re-asserts over users who actually hold tokens) and `DisconnectHandler`. No
  service-account operation writes to it, so it never carried the app-credential facts KIP's did,
  and there are no corrupted rows here to reconcile.

- **An event with no `start.timeZone` was reported to Meeting Agent as `"UTC"`** (2026-09-15). Google
  only populates that field when the organizer explicitly picked a zone, so on most events it is
  null — and the mapper's initialiser was the literal string `"UTC"`, which MA then stored as the
  organizer's timezone and stamped onto every transcript line. A Chicago meeting came back reading
  `[13:31:00 UTC]` for something that happened at 08:31.

  The zone is not actually unknown in that case: it is carried in the `dateTime`'s **offset**, which
  `parseEventDateTime` has already applied. The mapper now reports that offset (`-05:00`) instead of
  defaulting to UTC. An offset is a real limitation — it cannot survive a DST boundary the way
  `America/Chicago` would — but it is the correct local time for that event, which is what a
  timestamp needs. Reading the calendar's own default zone would be better still and needs a Calendar
  API call this mapper does not have.

  A zero offset still reports UTC, because that one genuinely is UTC.


- **EF-34 — the webhook cancellation path now asks Meeting Agent, instead of holding an unused
  resolver.** `MeetingKeyResolver` was injected into `CalendarNotificationHandler` and never called.
  An unused collaborator is normally clutter; this one was a behaviour gap with its own fix sitting
  next to it: a cancellation whose key was in neither local store was **abandoned** — logged and
  returned — so a meeting that reached Meeting Agent by another path, or whose local mapping had been
  evicted, stayed on the dashboard forever. The batch-sync path has asked Meeting Agent since WP-E16
  (spec §6 R14); the webhook did not, and the webhook is the path most cancellations arrive on.

  Ordering is unchanged and deliberate — the two local stores first, because a key we recorded
  ourselves is stronger evidence than a lookup and asking Meeting Agent when we already know the
  answer is a round trip on the hot webhook path. The lookup is scoped by the **mailbox owner's
  address** (EF-12), never the scan-set id.

- **EF-34 — `CaGateway.upsertMeeting`'s javadoc no longer promises an `isCancelled` field.** There is
  no such field on `CreateMeetingRequestDto` and there never was: unlike the Teams extension, this one
  routes a cancelled Google event down `deleteMeeting`, and `toNativeMeeting` sets the wire type's
  `cancelled` to `false` unconditionally because it has nothing to read it from. The javadoc would have
  sent somebody looking for a cancellation path through an upsert that does not have one. The stale
  D-05 "composite gate" comment on the webhook handler is corrected for the same reason as S-14.

- **S-23 — a forged `X-Goog-Channel-Token` can no longer select a user.** `resolveUser`'s first limb
  trusted a token on the sole basis that it named a consented user
  (`isUserAuthenticated(channelToken)`); the channel-id correlation was applied only on the Always On
  path. Both headers arrive from **outside** — Google echoes them, but so does anybody who can reach
  the webhook, and its URL is a fixed, guessable path on the appliance — so a request carrying
  `X-Goog-Channel-Token: <any consented user's address>` was attributed to that user and triggered an
  incremental sync of their calendar on demand. The token was checked for being a *known name*, which
  is not the same as being *evidence*.

  There is now one rule for both populations: the token is trusted when, and only when, a watch row
  this extension wrote itself correlates it to that `channelId`. **The Always On gate could go because
  the correlation costs the consented population nothing** — `createWatchChannel` writes the reverse
  index for every channel it creates, consented or delegated, so a genuine notification the token limb
  now declines resolves to the same user one limb down. What stops resolving is a token with no
  matching channel: the forged case, plus a stale channel we no longer track and would not renew.

  Two existing tests asserted the pre-fix behaviour and were rewritten with the reasoning recorded in
  place, alongside a new case for the attack itself and one proving a real consented notification still
  resolves through the store.

- **S-14 — the configured admin email is no longer injected as an attendee of every meeting.**
  `GoogleEventToMeetingDtoMapper.mapWithCalendarOwner` injected two addresses: the mailbox owner
  (kept) and `gmailAttributes.getMailId()`, the Google Workspace admin or service account on the
  invoker (removed).

  The stated reason was that Meeting Agent's gate is a composite needing both an internal-domain
  participant and a licensed seat, with the admin address supplying the first. **Verified against
  Meeting Agent and that is not its gate.** `WorkspaceQualificationPolicy.decide` is
  `activeExtension && (seat || (tenantWideCapture && internalDomainMatch))`: the seat limb carries no
  internal-domain check by explicit design (CON-002, to avoid pruning live opt-in contexts), and the
  domain limb is reachable only when `tenantWideCapture` is true — which under the owner's SEAT
  capture scope (KIM-492 FR-012) is **false for every workspace**. The condition the admin address was
  injected to satisfy is evaluated on no live path, while the mailbox owner satisfies the limb that is.

  What the injection did instead was make the admin account an attendee of **every meeting this
  extension forwards**. Meeting Agent's visibility rules and recipient scopes key on the attendee row,
  so that account gained dashboard visibility of every captured meeting in the workspace and became a
  recipient candidate for every ATTENDEES-scoped action — an access widening nobody asked for, granted
  to a credential that participates in nothing. The Teams extension refuses the equivalent injection
  (`MailboxOwnerAttendee`); this one now matches.

  The **mailbox-owner injection is unchanged** and is what makes the removal safe: it is the address
  the seat limb matches, and it is required of every calendar extension
  (`docs/always-on-developer-guide.md` §7.3) because a seat invited through a distribution list
  otherwise arrives as an unregistered organizer plus a group address and the meeting is dropped. The
  `configuredEmail` parameter is kept on the three-argument overload and **ignored**, so a caller that
  still passes one is a no-op rather than a silent behaviour change; a test asserts the two overloads
  now produce identical attendee lists.

- **EF-24 — the Always-On-off proof now drives the real discovery service.** `AlwaysOnOffUnchangedTest`
  mocked `discover(...)` and asserted what the sync service did with the answer, which tested the sync
  service and **not** the claim the class is named for: with discovery mocked, nothing could fail if
  the off path started reading a service-account key — and a service-account read on the off path is a
  latent outage for every tenant that has no key, which is all of them until they opt in. A new case
  builds the real `CalendarDiscoveryService` with an attribute map shaped like an invoker saved before
  Always On existed, and asserts `verifyNoInteractions` on the seat store, the domain-wide-delegation
  provider and the tenant directory.

  Per the owner's 2026-09-12 ruling (*"same behaviour regardless of mechanism"*), **Google Groups
  expansion is NOT gated on Always On** and applies to Opt-In workspaces too. `GroupExpansionService`
  is therefore excluded from that assertion deliberately rather than forgotten by it, the allow-list
  entry that admits it to the delegated credential now records the ruling instead of reading as an
  unexplained widening, and the exclusion is asserted structurally: the service takes no discovery
  seam and no flag reader, so it cannot branch on the flag even if somebody wanted it to.

- **KIM-492 / EF-13 — the staged contract jar is re-staged from the same Meeting Agent build as the
  other extensions, and the divergence is now detectable.** This extension's
  `conversation-agent-contracts-2.2.0.jar` was **five entries short** of teams-transcribe's under a
  **byte-identical** `.fingerprint` sidecar: it was missing `CalendarSeatRegisteredCommandProto`, its
  generated marshaller, both proto initializers and `calendar-seat-registered.proto`. Replaced with
  the complete build (sha256 `648bf5ac…`, 451 entries); the sidecar is unchanged because it was
  already correct.

  Nothing could see that. The seat-registered schema sits **outside** the fingerprinted contract set
  (intentionally — `esb-transport-progress-report.md:147`), so a jar staged from a partial build
  verifies cleanly and then fails every message on a durable queue at runtime.
  `CaContractFingerprintTest` now asserts the four entries directly against the committed jar, which
  is the only check that can see a truncated stage; the generated marshaller is matched by prefix
  because its name carries a schema hash and pinning that would make an innocuous rebuild a red build.
  `CalendarSeatRegisteredProtoInitializerImpl` is registered in `CaContractProtoRegistration` — the
  only runtime registration path, since the shaded jar discards every dependency's ServiceLoader entry
  — and the registration test's load-bearing check now covers it, so deleting the line goes red.

- **KIM-492 / EF-08 — the reminder consumers now start as a pair, or neither, and a discarded
  reminder is logged at WARN with a running count.**

  The decide consumer refuses to start unless it is pinned to a single tenant (spec §6 R11 — its
  verdict comes from THIS pod's dedup and rate-limit memory, so serving another workspace's question
  means answering DEDUPED or RATE_LIMITED about a user this pod has never seen). The one-way DRAIN
  consumer started unconditionally, and the combination was the worst of both: Meeting Agent's decide
  request timed out with nothing listening, Meeting Agent fell back to producing on the one-way
  queue, and the drain consumer acknowledged and **discarded** it. **In that state 100% of
  calendar-reconnect reminders were destroyed, silently** — and that is the one email that tells a
  user their calendar has stopped syncing.

  `GoogleServiceBusManager.startReminderConsumers()` now evaluates the pinning once and starts both
  or neither, with a single ERROR naming the cause, the queue and the consequence. **Starting the
  decide consumer unpinned was not an option** — that is the cross-tenant hazard the pinning
  requirement exists for and it has not changed. With neither bound, Meeting Agent's fallback
  messages accumulate on the durable one-way queue instead of being destroyed: queue depth is a
  signal an operator can see, and the messages survive until the pinning is fixed, whereas a
  drained-and-dropped reminder leaves no trace anywhere.

  The drain now logs **WARN** rather than INFO, with `discardedTotal` on the line and a
  `reminder_send_discarded` metric. The old INFO was too generous about what happens next: Meeting
  Agent stamps `last_reminder_sent_at` when its fallback returns, so it believes the reminder was
  sent. One WARN reads as a blip; a running total is what distinguishes a decide timeout from a
  decide consumer that is not running at all. A metrics fault can never propagate — this listener's
  whole job is to acknowledge so a durable queue drains, and a throw would turn every discard into a
  redelivery loop.

  Two existing tests were edited with the reasoning recorded in place: the manager's refusal case now
  proves **neither** consumer starts (and proves it broker-independently, by verifying the refusal
  path never even asks for the send destination — `startConsumer` swallows its own failures, so a map
  assertion alone passes vacuously on a box with no broker), and the drain's "holds nothing it could
  send with" assertion moved from "zero fields" to an allow-list, because the counter and the metrics
  recorder are new state that demonstrably cannot send.

  New coverage for `GoogleCalendarReminderDecideService` / `…Definition`, previously untested: the
  identity's versioned service name, its base destination, the `apis` map key against the interface
  method name, and the durable / manual-ack queue properties the reminder loop depends on. Nothing in
  that pair derives one string from another — the annotation processors cannot run in this build — and
  a mismatch does not fail to start: the consumer binds, the queue fills, and nothing is ever handled.

- **KIM-492 / EF-12 — a client user's cancelled meeting is now actually deleted from Meeting
  Agent.** `SendEventsToMAService.handleCancelledEvent` passed the sweep's `userId` as the lookup's
  `userEmail`, and on the consented path that is an opaque `wsContact_<uuid>` client key. Meeting
  Agent applies `userEmail` as a **hard** filter on every branch of its lookup precedence, and a
  client key matches no attendee anywhere — so the lookup returned nothing, the caller logged "could
  not resolve a meetingKey" and skipped the delete. **The cancellation fix WP-E16 shipped was
  therefore inert for exactly the population whose keys are not addresses, and it failed silently: a
  lookup that finds nothing looks identical to a meeting Meeting Agent does not hold.**

  The cancelled branch now passes the **mailbox owner's real address**, resolved from the primary
  calendar for this sync — the same value the create/update path already injects as the calendar
  owner — or `null` when it could not be resolved, which widens the lookup to `iCalUId` rather than
  abandoning it. `userId` is still the right argument for the LOCAL tracking store, which is keyed by
  it.

  `MeetingKeyResolver.participantFilter(...)` is the backstop at the one place the field is
  populated: anything without an `@` is dropped rather than sent. The test is "is this an address"
  rather than "does this start with `wsContact`" because the store holds several key shapes
  (`email#suffix`, `wsContact_<uuid>`, `wsContact_<invoker>_<email>`) and the question that matters is
  whether Meeting Agent could ever match the value against an attendee address. The refusal when
  neither `iCalUId` nor `meetingUrl` is available is unchanged — dropping the participant filter must
  not turn an unanswerable lookup into a fishing expedition across the workspace.

- **EF-34 (doc drift) — the ESB pool-sizing comments no longer claim this extension hosts two
  consumers.** They said "only 2 consumers (auth-initiate + reminder-send)" while
  `GoogleServiceBusManager` starts four: auth-initiate, its legacy alias, reminder-send
  (drain-and-ack) and reminder-decide. The sizes (20 / 24) are unchanged and still ample; the number
  a reviewer would have sized against is no longer wrong.

- **KIM-492 / EF-28 — a lost chunk of a batched upsert is no longer masked by its healthy
  neighbours.** `bulkUpsertMeetings` now returns exactly one outcome per non-null input meeting: a
  chunk Meeting Agent refused, or that never reached it, contributes a FAILED outcome for each of ITS
  meetings instead of contributing nothing.

  A batch larger than the chunk cap is several ESB calls and the caller gets one concatenated list.
  With an empty list per failed chunk, two healthy chunks made the result non-empty — so the sync
  service's telemetry recorded a partially-delivered batch as a clean send, and the shortfall showed
  up as `accepted < sent`, which its own log line attributes to Meeting Agent's per-meeting
  `NO_REGISTERED_USER` gate. A broker outage was filed as a customer registration problem.

  `UpsertOutcome.caUnreached()` separates "we could not ask" from "Meeting Agent said no";
  `SendEventsToMAService` keys its telemetry and its `[GCAL-BATCH-RESULT]` line on it. Behaviour for
  the affected meetings is unchanged — FAILED is not `acceptedByCa()`, so nothing is tracked and the
  next sweep re-offers them; what changed is that the caller can tell it happened. Two existing tests
  asserted the old empty-list contract and were rewritten with the reasoning recorded in place.

- **KIM-474 / EF-07 — a calendar that leaves the scan set now has its watch channel revoked instead
  of silently lapsing.** `CalendarWatchService.revokeSubscriptionsOutOfScope(...)` reconciles
  "every user we hold a watch row for" against "every user in the freshly resolved scan set", and
  stops the channels of the difference. It runs at the end of the `watch_subscribe` sweep and at the
  end of the six-hourly `watch_renewal` sweep.

  Every other sweep in the class iterates the users currently IN scope, so a user who LEFT was never
  visited again: nothing renewed their channel and nothing stopped it either. Google kept sending
  notifications for a calendar we had already decided not to scan until the channel expired on its
  own up to five days later, and the key-value store kept a row nothing would ever revisit. Ported
  from teams-transcribe, same method name on purpose so both halves of one feature stay greppable;
  GLE's "subscription" is a watch channel.

  **Shipped with both guards from day one.** An **empty** scan set revokes nothing, and a
  **degraded** one revokes nothing either. The empty case is not hypothetical — under the owner's
  SEAT-scope ruling (2026-09-12) a workspace with no licensed Meeting Agent seat legitimately scans
  nobody, and it must stop *renewing* those channels by not visiting them, never by actively tearing
  down every channel it holds. The degraded case is worse: discovery fell back to the consented set
  after a Google-side fault, and on an Always On workspace the held keys (registered seats) and that
  fallback set are very nearly disjoint — without the guard one missing service-account key would
  revoke the workspace's entire push pipeline. A fallback set is a scan list, never a membership
  statement.

  Supporting change: `CalendarWatchStore.subscribedUserIds()`, a **prefix-filtered** enumerator over
  `gcal_watch:`. The key-value namespace is flat and shared with every other store in the extension,
  and this reconcile revokes what it does not recognise — so an unfiltered enumerator (the shape of
  `RefreshTokenStore.getKey()`, EF-26) would tear down a live channel on behalf of a sync token. An
  unreadable store yields an empty set, which the caller reads as "nothing to reconcile", never as
  "everyone has left".

  Comparison is case-insensitive: seats arrive lower-cased from Meeting Agent while a consented-era
  watch row carries whatever case the user authorised with, and a case-only mismatch would read as a
  departure.

- **KIM-474 — an Always On workspace with no licensed seats now says so on every sweep.** Per the
  owner's ruling the empty-roster rule is unchanged (an empty seat roster scans nobody, and that is
  an answer rather than a degradation), but `CalendarDiscoveryService.discover(...)` now splits its
  empty-scan-set WARN into its two distinct causes: *no ACTIVE licensed Meeting Agent user exists*
  (fixed in Meeting Agent) versus *seats exist but the Google Workspace directory does not list them*
  (fixed in the Google admin console). The single previous message read "0 registered user(s), 0 of
  them absent from the directory", which told an operator neither. `RegisteredSeatStore` already
  WARNed on the roster fetch; that fires once per six-hour refresh and was silent on every sweep in
  between.

### Changed

- **Studio Setup: the `Always On` switch now renders at the bottom of the attribute list**, beneath
  the text fields, matching the KIP and Meeting Agent extensions (owner, 2026-09-11). The extension
  processor groups attributes by annotation type in first-appearance order, so `Allow retry` moves
  down with it (second-last). No attribute was added, removed or renamed; saved values are unaffected.

### Removed

- **KIM-492 — the `Ca Transport` invoker attribute is removed, and with it every HTTP call this
  extension made to Meeting Agent. The extension is ESB-only.**

  The attribute (`caTransport`, `esb` | `http`, WP-E16) was **not commissioned**. The product owner
  struck it on 2026-09-11: *"Remove it!"* It offered a choice nobody had asked for, on a surface
  where an operator could quietly select a transport whose routes were already scheduled for
  deletion.

  The consequence follows from the ruling rather than adding to it: with no attribute to select it,
  the HTTP implementation is unreachable code, so it is deleted now rather than left to WP-E20.
  Deleted outright — `CaTransport`, `CaTransportSelector` and `HttpCaGateway`, along with the
  reminder HTTP callback (`sendReconnectReminderCallback`) and its `CA_SERVICE_URL` / `CA_API_KEY`
  environment reads, `NotificationService.sendReconnectReminder`, and the `CaTransportSelectorTest`
  / `TestCaTransport` fixtures.

  `CaGateway` **survives the deletion on purpose.** It is not an abstraction over a choice — it is
  the vocabulary (`UpsertOutcome`, `MeetingLookupCriteria`, `MeetingKeyMatch`) that five call sites
  read, and it is what keeps Meeting Agent's wire shapes out of the calendar code. It now has
  exactly one implementation, `EsbCaGateway`, injected directly; there is no selector shell.

  **The legacy reminder consumer stays bound, and now drains.** `CalendarReminderSendListener` is
  still attached to `krista.calendar.reminder.send.google`, but it logs at INFO and acknowledges
  without acting. Two reasons, both load-bearing: Meeting Agent still falls back to producing on
  that one-way queue when a decide request times out (until WP-E20 retires the fallback), and the
  queue is **durable** — with no consumer attached those messages would accumulate on the broker
  indefinitely rather than being discarded. It holds no collaborator it could send with, which is
  what stops the double-send: MA has already had its answer from the decide consumer on
  `ext.calendar.reminder.decide.google`, and it sends the mail itself.

  **Rollback is a redeploy of the previous jar** — not an attribute flip. That is the whole
  substance of the change for an operator, and it is the trade the owner accepted.

  Definition of done: `grep -rn "/api/v1/" src/main/java` now returns only the ESB-details bootstrap
  (`GoogleExtensionConfig`, `GET /api/v1/extensions/invokers`), this extension's own
  `/rest/googlecalendar/api/v1/...` admin surface, and comments recording history.

  **Untouched, deliberately:** `Allow retry` (`ENABLE_ERROR_HANDLING`), `Tenant Admin Email` and
  `Tenant Domain` all pre-date this work and are a separate decision. So are the Google Calendar,
  Directory and OAuth clients, the webhook receivers and `AdminCalendarStatusResource` — only
  GLE→Meeting Agent calls were in scope.

  No version bump (stays 3.1.0), no new configuration property. Unit suite 828 tests, 0 failures.

### Added

- **KIM-492 (WP-DL) — a person invited through a Google Group now reaches Meeting Agent as an
  attendee in their own right.**

  A meeting sent to `engineering@acme.example` records exactly one attendee: the group. Nobody on it
  is named. Meeting Agent's dashboard visibility, its `INTERNAL_ONLY` / `MEETING_AGENT_USERS`
  recipient scopes and its enrolment nudge all key on explicit attendee rows, so a person reached
  through a distribution list could not see the meeting and did not receive its report — while the
  answer to "were you invited?" was plainly yes.

  New `GroupExpansionService` resolves group attendees through the Admin SDK under domain-wide
  delegation (`groups.get`, then `members.list` with `includeDerivedMembership=true`, paged) and the
  mapper appends each member as an attendee carrying **`viaGroup`** — the group's address,
  lower-cased. Meeting Agent stores it on `meeting_attendee.via_group`, so a report can say
  *"invited via engineering@acme.example"* rather than implying somebody was named personally. The
  group address itself stays on the attendee list: that is what the calendar actually says, and the
  member rows are what MA's rules can act on.

  **One new scope, and it is additive by construction.**
  `https://www.googleapis.com/auth/admin.directory.group.readonly` — one scope rather than two,
  because Google accepts it for **both** calls (`admin.directory.group.member.readonly` covers only
  the second). It is requested on its **own** client, `directoryClientForGroups()`, and deliberately
  NOT folded into `directoryClientForAdmin()`: a delegated token is issued for the whole requested
  scope set or not at all, so sharing a scope list would have broken Always On user enumeration
  outright for every tenant that granted the original two — turning an additive feature into a
  fleet-wide outage on upgrade day. A tenant that has not re-granted loses exactly the expansion,
  and is told once, by name, which scope to add to the existing delegation entry. It retries within
  the cache window, so a re-grant takes effect with no restart.

  **What is and is not expanded.** Only addresses on the workspace's own domain (`tenantDomain`, or
  the `tenantAdminEmail` domain when it is blank) are probed — asking a customer's directory about an
  outsider's address is not something this needs to do in order to work, quite apart from the
  round-trip per external attendee per meeting. Nested-group entries are skipped (derived membership
  already flattens them, so the entry is a duplicate, and adding it would put a distribution list
  into the attendee list dressed as a person); suspended members are skipped, while an **absent**
  status reads as active, because "the API did not say" is not "suspended" and reading it that way
  would silently empty an expansion. Capped at 500 members — the cap stops the paging, not just the
  result — because a company-wide list becoming an attendee list makes a meeting "visible to
  everyone", which is not a permission anybody granted.

  **Nothing here can cost a meeting.** An unauthorised scope, a group that is not a group, a quota
  refusal, a malformed response: every one of them skips the expansion and sends the meeting exactly
  as the previous release would have. Expansions and "this address is not a group" answers are both
  cached in the KV store for six hours — matching `RegisteredSeatStore`, so "how stale may a roster
  of people be" has one answer — which is also what bounds the logging: a WARN is written when a
  cache entry is created, so a missing scope or a phantom group is reported once per window instead
  of once per meeting.

  **Point-in-time is the semantics, not a limitation.** The expansion is re-sent on every calendar
  update until the meeting happens, so the attendee set records who was invited *when the meeting
  took place* and does not change retroactively as a list churns — which is what an access set for a
  transcript should be. Current membership is a different question, and AIQA keeps answering that
  live.

  Compatibility: `viaGroup` is null for a directly-invited person and Gson omits nulls, so an
  ordinary meeting's payload is byte-identical to the previous release. Every Conversation Agent
  build since 2025-09-22 ignores unknown JSON properties (`CustomJacksonProvider` sets
  `FAIL_ON_UNKNOWN_PROPERTIES=false`), so an older MA drops the tag rather than rejecting the
  meeting. The tag is never set on an organizer — MA stores organizers through a path that has no
  `viaGroup` parameter, so one there would be silently discarded.

### Changed

- **KIM-492 (CTO review) — the ProtoStream registration list is now guarded by a test, and the
  WP-E16 assertions the commit message claimed actually exist.**

  **Correction to the record:** WP-E16's commit message said spec §6 R11 was *"asserted in
  `GoogleServiceBusManagerTest` and `EsbDestinationNamingTest`"*. It was not — no such assertion
  existed anywhere. It lands in THIS commit.

  `CaContractProtoRegistrationTest` closes the hole the review found: `CaContractProtoRegistration`
  names the contract jar's four ProtoStream initializers explicitly, because this module's shaded
  `jar` task drops every dependency's `META-INF/services` file on purpose, and nothing guarded that
  list — the reviewer deleted the `CalendarReminderDecideProtoInitializerImpl` line and the whole
  suite, live-broker round trip included, stayed green. The new test asserts a marshaller for all
  19 wire types this extension encodes or decodes (the A1/A2/A3/A5 request+reply pairs,
  `ListUsers*`, `CalendarReminderDecide*`, `CalendarAuthInitiate*`, and the nested
  `NativeMeetingProto` / `PersonProto` / `MeetingViewProto` / `UserViewProto` /
  `UpsertResultProto`) **against a context seeded from that list alone** — never ServiceLoader,
  which on the unshaded test classpath silently supplies what a deployed jar lacks and is exactly
  why the deletion went unnoticed. A companion case proves each listed initializer is load-bearing:
  drop any one and at least one wire type stops resolving. Re-running the reviewer's deletion now
  fails three cases.

  The R11 assertions: `GoogleExtensionConfig.getCalendarReminderDecideConfig()` returns `null` when
  this pod resolves no workspace (including a blank id) and a `FIXED` config pinned to the resolved
  workspace when it does; `GoogleServiceBusManager.startReminderDecideConsumer()` starts nothing on
  a `null` config, an `ALL`-mode config, or a `FIXED` config with no usable tenant id, and logs the
  resolved tenant at INFO when it does start. `EsbDestinationNamingTest` now also pins
  `ext.calendar.reminder.decide.google` — the one destination constant it was not covering.

  `EsbCaGatewayMappingTest` derives its error-code lists from `ExtErrorCodes` by reflection instead
  of repeating them, so a twelfth code MA adds is exercised on both the upsert and cancel paths the
  day the staged jar carries it. And `findMeetingKeys` / `MeetingKeyMatch` now document the
  single-page contract they have always had — one page read, `nextOffset` deliberately not
  followed, because the one caller takes `firstKey()` and MA returns its strongest match set first.
  No behaviour changed.

- **KIM-492 (WP-E16) — every call this extension makes to Meeting Agent now runs over the ESB, and
  the calendar-reconnect reminder becomes a question instead of a callback.**

  All six MA call sites move onto the bus: the webhook upsert and delete (A1, A3), the batch sync
  upsert and delete (A2, A3), the cancelled-event key lookup (A5) and the registered-seat roster
  (C1). The only HTTP call this extension makes to MA is now the ESB-details bootstrap — the one
  the rule exempts, because it is what tells the extension where the broker is.

  **One gateway, no selection point.** Every call site injects `EsbCaGateway` and calls a method on
  it. WP-E16 originally shipped a `caTransport` invoker attribute selecting between this and an
  HTTP implementation; that attribute was never commissioned and was struck by the product owner
  before release — see the **Removed** entry below, which is the state that ships.

  **Reading a reply has exactly one rule, and it turns on a distinction that is easy to get
  backwards.** `ReplyBase.ok` carries no `@ProtoField` and reads `true` on every decoded reply, so
  nothing branches on it. The generated marshaller fills every absent string with `""`, so a reply
  that genuinely came from MA always has a non-null `errorCode` — blank on success. A **null**
  `errorCode` is only reachable on an object that was never decoded, i.e. the default-constructed
  reply the client returns when it catches its own exception. Collapsing null into blank would read
  every timeout as a successful upsert.

  **Two shapes changed rather than being ported.**
  - The cancelled-event lookup now sends **one** request carrying every criterion, including
    **`iCalUId`** — the strongest key, and one the old HTTP query string had no parameter for at
    all. MA applies them in precedence and reports which rule fired.
  - The batch upsert reads **per-meeting outcomes** instead of counting rows in a JSON array, so a
    meeting MA dropped is distinguishable from one it never saw. Batches are chunked to MA's cap of
    50 inside the gateway, because MA rejects an over-cap batch *whole* — one oversized page would
    otherwise lose every meeting on it rather than the surplus.

  **The reminder loop stops calling back over HTTP from inside a JMS consumer.** A new request/reply
  consumer on **`ext.calendar.reminder.decide.google`** answers `SEND` / `DEDUPED` /
  `RATE_LIMITED` / `REFUSED` and **MA sends the email**. The old one-way listener stays bound purely
  to DRAIN its durable queue — it logs at INFO and acknowledges — because handling a message there
  as well would double-send. A `SEND` verdict **reserves** the rate-limit slot immediately, because
  MA acts on the answer and nothing reports back.

  **The decide consumer REFUSES to start unscoped** (spec §6 R11). This extension's other consumers
  degrade to `TenantFilterMode.ALL` with a warning when no workspace resolves, which is right for
  them — their handlers re-read `workspaceId` from every payload. The decide handler cannot: its
  answer comes from *this pod's* dedup memory and rate-limit history, so serving another tenant's
  question means answering DEDUPED about somebody we have never seen, and a real user stops being
  told their calendar is broken. The resolved tenant is logged at INFO on every start.

  > **Release note — expect a one-off burst of deletions on the first sync.**
  > The cancelled-event lookup called `GET /api/v1/meetings/lookup`, **a route Meeting Agent has
  > never served**. It matched `getMeeting` with `meetingKey="lookup"` and returned 404 on every
  > call since the code was written, so a cancelled Google meeting whose key was not in the local
  > tracking store was never removed from MA. The first sync after this release drains that
  > backlog: meetings whose calendar event disappeared months ago will finally be deleted. That is
  > the fix working, not a fault. Counting the affected meetings before deploying is advisable so
  > the burst size is known rather than discovered.

  Also deleted: `impl/model/CAMeetingResponse.java`, dead with zero references.

  No version bump (stays 3.1.0). No new Studio invoker attribute (see **Removed**) and no new
  configuration property.

- **KIM-492 (WP-E15) — Meeting Agent's contract SDK is staged as a build input; the hand-carried
  auth-initiate DTOs are deleted and two queues are renamed.**

  `libs/conversation-agent-contracts-2.2.0.jar` and its `.fingerprint` sidecar are committed
  build inputs (`gradle.properties#caContractsVersion`) and are linked directly. This replaces the
  hand-carry model: the auth-initiate command and reply, their two hand-written marshallers, and
  the one-line subclass under `app.krista.conversationagent.module.calendar.esb` whose only job
  was to let `Class.forName(dtoClass)` resolve MA's FQN are all gone. Field numbers, names and
  types were diffed against the jar's generated `.proto` before the swap and are identical, so
  nothing on any queue changed shape.

  **Keeping a local copy alongside was never an option.** `calendar-reconnect.proto` declares its
  messages under MA's protostream package deliberately, for wire compatibility, and the jar's new
  schema declares that same package with the same two message names. Protostream rejects the
  duplicate and the consumer never starts. `CalendarConnectionStatusChangedProto` and
  `CalendarReminderSendProto` stay hand-authored — the jar carries neither, so there is no
  collision and no replacement.

  **The jar's initializers are registered explicitly**, not through `META-INF/services`. This
  module's `jar` task deliberately excludes every dependency's copy of that services file so its
  own always wins, which means nothing on the contract jar's list is discovered at runtime —
  verified against the built artifact, whose services file names only this extension's
  initializer. Without the explicit entry everything compiles, the consumer binds its queue and
  reports itself healthy, and the first reconnect request from MA fails to decode. CI cannot see
  that on its own, because the test classpath is *not* shaded and ServiceLoader quietly supplies
  there what a deployed jar would lack — so the test asserts the registration *list*, not the
  shared registry.

  **Drift protection, layers 2 and 3 of the spec's three.**
  `CaContractFingerprintTest` fails the build when the staged jar and its `.fingerprint` come from
  different MA builds, via the jar's own `ContractFingerprint.verifyStagedJar` rather than a second
  copy of the normalisation (proven red against a tampered sidecar). `ContractHandshake` compares
  MA's reported `contractsFingerprint` at bootstrap with the staged one and **refuses to start the
  ESB consumers** on a mismatch: proto3 has no field presence, so consuming across a contract
  mismatch yields silently wrong data rather than an error. An **absent** fingerprint is
  UNVERIFIED, not a mismatch — an MA older than WP-E2 reports none, and treating the absence of a
  signal as a signal would take a working extension offline on every MA blip. Bootstrap and
  registration are never gated, so the extension still reports itself and an operator sees it in
  the console with the reason in its logs. The verdict is re-evaluated on a rebind as well as at
  start, which both closes the hole where a Save Configuration would start the consumers the
  refusal held back, and lets a redeployed MA clear a mismatch with no extension restart.

  **Queue naming (spec §3.3).**
  - Calendar auth-initiate binds **`ext.calendar.auth.initiate.google`** *in addition to* the
    legacy `krista.*` name, same handler instance. A rename is a new destination, not a migration:
    MA keeps producing to the old name through Stage 2, so dropping it now would make this
    extension go deaf — silently. Stage 3 removes it.
  - Calendar connection status now publishes to **`events.calendar.connection.status.changed`**,
    a **single** publish. Unlike the consumer, a producer knows exactly where it is sending, and
    publishing to both names would double-deliver to an MA consuming both. **MA's Stage-1 build
    (WP-E7), which consumes both names, must therefore be deployed before this extension jar.**

  **Stale javadoc corrected**: three places described the reminder-send queue as
  `krista.calendar.reminder.send` while the consumer has always bound
  `krista.calendar.reminder.send.google`. Unsuffixed, this extension and the Teams extension would
  compete on one queue and each would steal half the other's reminders. Every consumer config is
  now built from a destination constant rather than a repeated string literal, which is what let
  the names drift apart in the first place.

  No version bump (stays 3.1.0) and no new configuration property. Unit suite 730 tests, 0
  failures. Zero fully-qualified class-name collisions between this extension's sources and the
  staged jar.

- **KIM-492 (WP-E14) — krista-infra moves from 1.1.433 to 2.0.434.**

  All four `app.krista.infra` declarations (`esb`, `esb-annotations-runtime`,
  `esb-annotation-processor`, `infra-core`) take the identical `2.0.434` pin. One version across
  the whole set is deliberate rather than tidy: `esb` transitively pulls the rest of the graph, so
  a mixed set splits the classpath and `ProtoWire.ensureRegisteredByName` surfaces that split as a
  `LinkageError`/`NoClassDefFoundError` at first use — not as a resolution warning anyone would
  see at build time. The resolved graph reports 2.0.434 on all **18** infra modules on every
  configuration (`runtimeClasspath`, `compileClasspath`, `testCompileClasspath`,
  `testRuntimeClasspath`); `annotationProcessor` carries no infra coordinate at all, because this
  extension's processor path is `extension-impl-anno-processors` only.

  **Why now.** Meeting Agent is on 2.0.434, and this extension has to sit on the same matched set
  before it can link MA's generated contract jar (WP-E15) instead of hand-carrying DTOs and
  marshallers.

  **Two floors that must never be crossed**, both recorded in MA's `dependencies.gradle` and now
  in `build.gradle`: **2.0.415 is quarantined upstream** and must never be pinned, and **2.0.421
  is the namespace-parity floor** — the 2.0 line was cut before the KIM-451 deployment-namespace
  work, so 2.0.418–2.0.420 have no `broker.dest.namespace` support and `EsbEndpointBinding`'s
  `NAMESPACE_PROPERTY` would silently stop prefixing every destination. 2.0.434 satisfies both.

  **This does NOT fix the fleet-wide `DIST_ASYNC` cache problem and must not be described as
  doing so.** infra `develop-1.1` has no `ReplicationMode`, so whichever 1.1 service mints a
  shared cache first fixes it as `DIST_ASYNC` and nothing reconciles. Moving this extension off
  1.1 removes **one** 1.1 cache-creator from the fleet; KA and RA remain on 1.1, so the underlying
  condition is unchanged. Do not close the fleet issue on the strength of this change.

  **Two third-party versions now move by Gradle conflict resolution rather than by an edited pin**,
  so the declarations in `build.gradle` are stale but harmless and are deliberately left alone:
  `protostream` declared 6.0.3 → resolves **6.0.7** (via `infinispan-commons`), and
  `artemis-jms-client` declared 2.41.0 → resolves **2.44.0** (via `esb-consumer`). Both are exactly
  what MA resolves, so the two ends of the wire agree — which is the property that actually
  matters. `app.krista:krista-apis:1.0.126` and `extension-impl-anno-processors:1.0.126` are leaf
  artifacts with no transitive dependencies, so neither drags a conflicting infra line.

  **No source change was forced** and no behaviour changes: the `ServiceImpl`, `ServiceDefinition`,
  `ServiceBusConfig`, `ProtoWire`, `QueueClient`/`QRequester` and publisher surfaces this module
  uses are source-compatible across the major move. Unit suite 703 tests, 0 failures — identical to
  the 1.1.433 baseline, with the same pre-existing warnings and no new ones.

  Verified on a live Artemis 2.44 broker, not only at compile time: both inbound consumers decoded
  an MA-shaped protobuf message and dispatched it to its service method (auth-initiate
  request/reply, reminder-send one-way), and the outbound connection-status publisher encoded and
  landed an event that decoded on the far side — each on a throwaway per-run destination under
  `TenantFilterMode.FIXED`.

- **KIM-492 (WP-G1) — Always On now scans registered Meeting Agent users, not the whole Google
  Workspace account.**

  The product owner withdrew tenant-wide scanning on 2026-09-10: *"we won't scan an entire tenant
  blindly … keep it for if maybe one day we change it."* Always On therefore means **every
  registered, licensed Meeting Agent user of this workspace**, and there is **no setting anywhere**
  that selects a tenant-wide mode — no new attribute, no scope query, no per-workspace option. Always
  On off is unchanged in every respect.

  **Where the roster comes from.** A new `RegisteredSeatStore` reads
  `GET {serviceUrl}/api/v1/users?workspaceId=…&status=ACTIVE` on the same `x-api-key` /
  `X-Workspace-ID` plumbing every other MA call already uses, pages through it, and keeps only rows
  that are **both** `status == ACTIVE` **and** `licensedSeat == true`. Meeting Agent's list endpoint
  defaults to *every* status, so `status=ACTIVE` is asked for on the wire and re-checked locally —
  the narrowing is a guarantee, not a query parameter somebody might change. An Observer or a
  Delegate Admin has a registry row, deliberately has no synced calendar, and is not scanned.

  **Refreshed on startup and every six hours.** The invoker-load hook forces one refresh before the
  first sweep registers watch channels (`CalendarDiscoveryService.primeRegisteredSeats`, a no-op
  while Always On is off, so the flag stays read in exactly one place). After that,
  `discover(...)` — the one call every sweep already makes — refreshes on a six-hour TTL. That bounds
  staleness on the batch sync, watch subscribe, renewal and teardown from a single place, and
  deliberately adds **no timer thread**: this extension has one scheduler already (watch renewal,
  itself six-hourly), and a second one refreshing on a cadence unrelated to when the roster is used
  would be a thread to reason about for no gain.

  **A failed refresh keeps the last good roster**, and the roster is persisted to the KV store so a
  container restart during a Meeting Agent outage starts from the last known set rather than from
  nothing. The distinction that drives everything downstream is **"never fetched"** versus
  **"fetched, and empty"**: the first is a missing signal and degrades to the consented users with
  `degradedReason="registered-seats-unavailable"`; the second is an answer — a workspace with no
  licensed seats scans nobody, loudly, rather than reverting to calendars the customer chose not to
  enrol.

  **Discovery.** `DiscoverySource.ENROLLED_USERS` is new and is the only source Always On produces.
  The scan set is *registered seats ∩ the Google Workspace directory*: domain-wide delegation can
  only ever mint a token for a member of the delegating account, so a seat the directory does not
  list is skipped with a WARN naming the usual causes (an alias rather than the primary email, a
  suspended or archived mailbox, a domain excluded by `tenantDomain`) rather than failing the sweep.
  The Admin SDK listing is now a **membership check**, not the scan set. Strict replacement of the
  consented set is preserved exactly as before, with the same per-sweep WARN — the dropped population
  simply widens to include anyone without a licensed seat. Every degradation path is unchanged and
  still returns the full consented set.

  `sourceFor` and `isSyncable` follow the same rule, so a push notification from a channel whose
  owner has since lost their seat no longer yields a delegated credential. The check is in-memory and
  adds no round-trip to the notification path.

  **`DiscoverySource.TENANT_DIRECTORY` and `TenantUserDirectory` are retained**, compiled and
  unit-tested, so a future tenant-wide mode is a deliberate change rather than a rediscovery. Nothing
  produces that source today, and a structural test fails the build if any file outside the enum's
  own declaration so much as names it. Scale behaviour (parallel fan-out, the rate-limit backoff, the
  primary-calendar-id fallback) now keys on `DiscoverySource.delegated()` rather than on a single
  constant — comparing against one constant is how one of those quietly stops applying the day a
  second delegated source appears, which is exactly what `ENROLLED_USERS` is.

  Docs: `AlwaysOnTenantWideCapture.md` → `AlwaysOnRegisteredUsers.md`, rewritten around the corrected
  model with a who-gets-captured table, the new log lines, and why the directory scope is still
  needed when the scan is not tenant-wide.

### Added

- **KIM-474 — tenant-wide calendar discovery for Always On.** With the `alwaysOn` attribute switched
  on, this extension no longer scans only the users who individually authorised it: it enumerates
  every active mailbox in the customer's Google Workspace account and scans all of them, registering
  push-notification channels for each. Always On off is unchanged.

  **Google is not Microsoft here, and the difference is structural.** A Microsoft app registration
  can be granted *application permissions* and act tenant-wide with no user involved. A Google OAuth
  client id and secret cannot — they only ever mint tokens for a user who personally consented.
  Tenant-wide access on Google therefore requires a **service account with domain-wide delegation**
  (DWD): a different credential artefact, plus a Google Workspace super-admin authorising that
  service account's numeric Client ID against named scopes. The admin-consent *ceremony* mirrors
  Microsoft's; the credential type does not.

  Three new **optional** invoker attributes carry it:

  | Attribute | Purpose |
  |---|---|
  | `serviceAccountKey` | The service-account JSON key. Declared `isSecured = true`, like `serviceApiKey` — masked in Studio, and never logged, echoed, or placed in an exception message. |
  | `tenantAdminEmail` | The Workspace super-admin the service account impersonates for Admin SDK Directory calls (`users.list` is admin-only). Not a secret. |
  | `tenantDomain` | Optional. Restricts enumeration to one domain; blank lists the whole account (`customer=my_customer`). |

  Customer prerequisites: create a service account in Google Cloud and download its JSON key; in the
  Google Workspace Admin console under **Security → Access and data control → API controls →
  Domain-wide delegation**, authorise that service account's numeric Client ID for exactly
  `https://www.googleapis.com/auth/calendar.readonly` and
  `https://www.googleapis.com/auth/admin.directory.user.readonly` (read-only — the tenant-wide path
  only reads calendars and registers channels); paste the JSON key and the admin address into the
  attributes above.

  **All discovery goes through one seam.** `CalendarDiscoveryService` is the single answer to "whose
  calendars do I scan, and with what credential?" — used by the batch sync, the single-user sync,
  watch-channel creation, renewal and teardown, and push-notification handling. There is no
  `if (alwaysOn)` anywhere else, and `AlwaysOnOffUnchangedTest` fails the build if one appears.

  **Tenant-wide discovery REPLACES the individually-authorised set — it is not a union.** With the
  flag on and the Workspace account enumerated cleanly, the scan set is the directory and only the
  directory, and every calendar in it is read on a delegated credential (immune to any individual's
  token expiring). Anyone who authorised individually but is not in the directory — someone on a
  **personal Google account**, and every **`wsContact_` client key**, which is not an address at all
  and cannot be impersonated — **stops being captured the moment the flag goes on.** Nothing errors;
  their meetings simply stop appearing, which is the shape of regression that gets blamed on
  everything except the switch that caused it. So it is stated in the customer documentation under
  its own heading, and every sweep counts and names the users concerned at WARN.
  `UserSet.droppedConsentedUsers()` carries the same list as a value, so it is assertable rather
  than only loggable.

  **It degrades rather than failing** — and that is a different thing from the replacement above.
  Degradation is a FAILURE path and still scans everyone who authorised individually. Creating the service account and getting it authorised are
  customer actions that routinely have not happened when the flag is first switched on. A missing
  key, an unauthorised delegation (`unauthorized_client` / 403), a transient Directory fault, or an
  account that reports no users all fall back to the consented-user set with **one actionable ERROR**
  naming the exact console location and scope strings. Never an empty scan, never a crash loop. A
  per-mailbox 403/404 skips that mailbox and the sweep continues. The effective mode and set size
  are logged once per sweep as `[GCAL-DISCOVERY] sweep=… mode=… users=…`.

  Unchanged by design: the URL-less event skip (Meeting Agent does the capture gating, FR-009),
  `esb_tenantId` handling, and the absence of any arming concept — arming is Teams-only, and Google
  only registers meetings in its scan path.

  New dependency: `com.google.apis:google-api-services-admin-directory` (Admin SDK Directory),
  reached only on the DWD path.

- **KIM-474 — tenant-wide sweeps now fan out, and wait out Google's rate limiting.**

  Two changes, both confined to the Always-On-on path. With the flag off every one of these calls is
  what it was, on the same thread, with the same single attempt.

  *Bounded parallelism.* Watch-channel creation, renewal and teardown each walked their population
  one user at a time. A watch channel is three Google round-trips (`calendars.get`, `events.list` for
  the baseline sync token, `events.watch`); across a Workspace account of thousands that is a startup
  measured in hours — and because channels expire after five days, a renewal pass that cannot get
  round the account in time silently stops push notifications for whoever was at the back of the
  queue, roughly five days after the flag was switched on and with nothing to connect the outage to
  the change. All three now fan out over virtual threads behind a semaphore of **5**, deliberately
  the same number as `SendEventsToMAService.MAX_CONCURRENT_USERS`: both push on the same Calendar API
  for the same account, and two ceilings would mean the effective limit depended on which sweep
  happened to be running. No new pool, no new tunable. The HK2 context is captured on the calling
  thread and restored on each virtual thread — every store here is invoker-scoped — and cleared
  afterwards so a carrier thread cannot leak one invoker's context into another's work.

  *`Retry-After`-aware backoff*, in `GoogleThrottleBackoff`, applied to Directory enumeration,
  calendar reads and watch create/list/stop. Honours Google's own `Retry-After` in both documented
  forms (delta-seconds and HTTP-date), falls back to bounded exponential backoff when there is none,
  and logs at WARN (`[GCAL-THROTTLE]`) whenever it waits so throttling is visible rather than
  inferred from a slow sweep. It **degrades rather than hangs**: at most 4 attempts, no single wait
  over 60s, no more than 120s of waiting per call, and a `Retry-After` larger than that is not obeyed
  *and not clamped down* — retrying earlier than Google said it would be ready buys a refusal we
  already know is coming, so the call gives up and the next sweep retries the mailbox.

  **The Google-specific hazard, and why this is not a copy of the Teams version:** the Calendar and
  Admin SDK APIs signal a quota refusal as **403 with a rate-limit reason** at least as often as they
  signal it as 429. A bare 403 therefore decides nothing, and guessing is expensive in both
  directions — retry a genuine "your super-admin has not authorised this client id" three times per
  mailbox and one clear configuration error becomes a flood, while the discovery seam never gets to
  degrade cleanly; refuse to retry a real quota refusal and a large account loses whichever mailboxes
  were unlucky. So a 403 is retried only when its body names `rateLimitExceeded`,
  `userRateLimitExceeded`, `quotaExceeded` or `backendError`, and never otherwise. 401 is never
  retried.

### Documentation

- **KIM-474 — *Always On — Tenant-Wide Capture* gains a "Who stops being captured" section.** Names
  the two affected populations (personal Google accounts, `wsContact_` client users) in plain terms,
  shows the exact `[GCAL-DISCOVERY]` WARN that lists them, and states the only two remedies: turn
  Always On off, or move the account into the Workspace domain. A customer must not discover this by
  noticing missing meetings.

### Fixed

- **Conversation reauthentication now returns the platform authorization prompt.**

  A revoked/expired Google grant raised `MustAuthorizeException`, but single-user calendar sync
  caught it as a generic exception and returned zero events; the catalog boundary then converted
  any remaining exception into a normal failure response. The conversation therefore had no
  authorization signal from which to render its Authenticate button. The exact
  `MustAuthorizeException` now propagates through both layers. Refresh failures also publish
  `REAUTH_REQUIRED` with the real user email rather than the internal
  `email_clientId_authType` token-store key.

  The `Save Configuration Google Calendar` request used by the conversation's **Connect My
  Calendar** flow now validates an existing stored token with Google instead of treating any
  nonblank token string as authenticated. A revoked grant enters the temporary authorization
  context and throws `MustAuthorizeException`, allowing the conversation to render the button,
  complete OAuth, and resume the original request.

- **Startup reconciliation validates the stored Google grant before replaying CONNECTED.**

  The durable authenticated-user list can outlive a revoked Google token. Reconciliation now
  refreshes each user's credential before re-asserting CONNECTED; an invalid grant remains
  `REAUTH_REQUIRED` instead of being incorrectly changed back to CONNECTED during startup or Save
  Configuration.

- **Persisted Google authentication is now reconciled with Calendar Systems after AMQ starts.**

  OAuth tokens and the local authenticated-user list survive a restart, but a `CONNECTED` ESB
  event published while AMQ was unavailable could be lost. The Google client would then sync
  successfully while Conversation Agent continued to show no connected calendar system. Once
  both AMQ consumers are ready, startup now re-asserts `CONNECTED` for every durable authenticated
  user. Save Configuration performs the same idempotent reconciliation after refreshing AMQ, so an
  already-running invoker can recover without another OAuth flow.

- **Save Configuration now makes the CA/AMQ endpoint available without a restart.**

  A brand-new invoker starts before `serviceUrl` and `serviceApiKey` have ever been saved, so ESB
  bootstrap falls back to the cluster-local Artemis address. The catalog save path previously
  persisted the CA attributes without triggering the live endpoint refresh; additionally,
  `GoogleServiceBusManager` skipped refresh whenever the failed startup left zero consumers.
  Successful saves now pass the newly persisted values directly into the CA handshake and recover
  missing consumers against the returned broker endpoint. Future startups load the persisted
  attributes normally.

- **Save Configuration OAuth now registers the authenticated Google user for calendar sync.**

  A successful OAuth callback stored a valid token and published `CONNECTED`, but it did not add
  the user to `gcal_sync_authenticated_users`; single-user and scheduled sync therefore reported
  "User is not authenticated" after a clean install or upgrade. The callback now registers the
  canonical user through `GoogleCalendarAuthenticationService`, which both persists the sync user
  and re-asserts `CONNECTED` to CA. Studio token keys are also converted back to their configured
  email before provider lookup, preventing the client-id/auth-type suffix from being appended twice.

- **krista-infra 1.1.432 → 1.1.433 — 1.1.432 silently ignored `broker.dest.namespace` on consumer
  queue names, so this extension's KIM-451 namespace support was inert.** Version bumped to
  **3.0.9**.

  The extension bound the MA handshake's `esbNamespace` into the `broker.dest.namespace` system
  property correctly, but infra 1.1.432's `ServiceBusConfig` did not apply the property when
  composing the physical consumer queue names — consumers attached to the **un-prefixed** queues
  of the shared broker regardless. On a namespaced deployment (the preview instance) that means MA
  publishes calendar auth-initiate and reminder commands to `preview.krista.calendar.*` while this
  extension listens on the eng-deployment names and never receives them. Un-namespaced deployments
  (eng, prod) are unaffected — the bug is a no-op there, which is why it went unnoticed since the
  KIM-451 rollout. 1.1.433 resolves the namespace live in `inQueueName()`; the new
  `EsbNamespaceInfraContractTest` locks the contract against the classpath (fails on 1.1.432,
  passes on 1.1.433) so a future infra pin cannot silently regress preview isolation.

### Changed

- **ESB connection details now bind dynamically — configuring the extension no longer needs a
  follow-up "upgrade extension" to take effect.**

  `GoogleExtensionConfig` performed the MA handshake **once, in its constructor**, and wrote the
  broker host/port to JVM system properties. Saving attributes is the moment `serviceUrl` /
  `apiKey` can change — and on a freshly configured invoker it is the first moment the handshake
  can answer at all — but by then the constructor had already run and fallen back to the qualified
  FQDN guess. The only way to re-run it was to recycle the container, which is what "upgrade
  extension" was really being used for.

  The extension now has a `VALIDATE_ATTRIBUTES` hook (it previously had none) that validates the
  MA service URL and then re-runs the handshake and reconnects both consumers in place
  (`GoogleExtensionConfig.refreshEsbEndpoint` + `GoogleServiceBusManager.reconfigureEsb`). Both
  attributes remain optional, so a blank value is still accepted and simply means "no MA
  discovery"; only a malformed URL is rejected.

  This works because krista-infra's `Configuration` resolves properties on every read rather than
  snapshotting them, and its connection cache is keyed by `host:port` — so once the properties
  change, restarted consumers dial the new endpoint. Consumers are only recycled when the endpoint
  actually **moved**; saving attributes that did not touch the broker leaves live JMS connections
  alone rather than dropping in-flight work for no reason. The previous connection is evicted
  *before* the properties change, because after the rebind it can no longer be addressed by cache
  key and would linger with a health watcher pinging a broker nobody uses. Eviction uses the exact
  `ServiceBusConfig` instances the consumers started with (now retained), since this class builds
  a fresh config object per call.

  Unlike the constructor path, a failed refresh does **not** fall back to the qualified FQDN: at
  construction nothing is bound yet and a good guess beats nothing, but once the extension is
  connected, an MA blip must not knock it off a broker it is happily talking to.

### Added

- `EsbEndpointBinding` — one place that owns which JVM properties constitute "the broker we are
  bound to" and what counts as a change (host, port, credentials, destination namespace). It also
  now owns the KIM-451 set-only namespace rule that `GoogleExtensionConfig.applyDestinationNamespace`
  used to hold: a null/blank `esbNamespace` means "leave destination naming exactly as it is",
  never "clear it". That helper is gone rather than left alongside — two implementations of the
  same rule is exactly how they drift apart.

- **`serviceApiKey` — the Meeting Agent API key is now a configurable Studio attribute.**

  Same gap as KIP. This extension declared no api-key field at all, while its code read an invoker
  attribute named `apiKey` that nothing ever declares — so the value was always null and every MA
  call fell through to a hardcoded ops key, including an automatic 401 retry onto it. The attribute
  is declared as `@Field.Text(isSecured = true, required = false)` on `GoogleCalendarExtension`,
  deliberately under the SAME name the Meeting Agent wrapper already uses, so all three extensions
  (this one, the wrapper, KIP) are configured identically rather than each inventing its own.

  Resolution is centralised in `Constants.resolveMaApiKey(Invoker)`: the Studio attribute first,
  then the `CA_API_KEY` environment variable, then `""` — never null (a null on a JAX-RS builder
  means "remove this header", which produces a confusing no-key request instead of a clean 401),
  and never an ops key. There is no ops-key fallback by design: FULL tier bypasses workspace
  isolation and the licence-admin gate, so escalating on misconfiguration would be a cross-tenant
  hole.

### Fixed

- **Save Configuration Google Calendar now collects and persists `serviceApiKey`.**

  `serviceApiKey` was added to the extension but never to the **Save Configuration Google Calendar**
  catalog request, which is the surface an admin actually configures MA through. The field was not
  in the request's declared shape, so there was nowhere to enter the key, and the save path only
  ever wrote `serviceUrl` back onto the invoker — leaving the extension with no key and every MA
  call going out unauthenticated.

  The attribute is now part of the request's declared shape, validated alongside `serviceUrl`
  (missing ⇒ `Missing required field(s): serviceApiKey`, rejected before any Google connection is
  attempted), and written back by `saveInvokerServiceAttributes` — the renamed
  `saveInvokerServiceUrlIfPresent`, which still layers onto a copy of the CURRENT invoker
  attributes so saving never clears anything the form does not carry, and still leaves an
  absent key untouched rather than blanking it.

- **`GoogleServiceBusManager.stop()` could only ever run once.** Its `stopped` latch was set on
  the first stop and never re-armed, so a stop/start cycle left the second generation of consumers
  running with shutdown permanently disarmed. Harmless while the only stop was at unload; a
  blocker for reconnect-in-place, which is a stop/start cycle by definition. `start()` now re-arms
  the latch.

- **ESB consumers are now bound to this pod's workspace (KIM-460).** Both inbound consumers used
  `TenantFilterMode.ALL`, justified in the class javadoc by the claim that this extension is *"a
  single multi-tenant instance (one pod serves many workspaces)"* with no workspaceId to bind to.

  Deployment does not match that claim: production runs **one Google invoker bound to one
  workspace**, exactly like KIP — which uses `FIXED` with `setFixedTenantId`. The comment and the
  config had drifted from reality.

  `ALL` means the consumer receives **every tenant's** messages and filters them in the handler —
  isolation by convention rather than by the broker. `FIXED` makes Artemis enforce it, which is both
  stronger and cheaper.

  Falls back to `ALL` (WARN-logged) when no workspace resolves, so a genuinely multi-tenant
  deployment would keep working rather than silently bind to a null tenant and receive nothing.

- **Calendar-reconnect reminder now uses the MACHINE route (KIM-460).** This called
  `POST /api/v1/operations/extensions/calendar-notification/{email}/send`, which sits behind
  MA's Krista-only Ops Console gate. That was an oversight — a machine-to-machine notification
  living on the operator surface — and it only worked because this extension holds a `FULL` key.

  MA now exposes the same behaviour at
  `POST /api/v1/extensions/calendar-notification/{email}/send` (both routes delegate to one
  sender, and the operator route is kept for the console). An `EXTENSION`-tier key is denied on
  `/operations/**` by design, so this repoint is a prerequisite for moving off the `FULL` key.

  Request, response and error handling are unchanged — only the path.

- **The CA invoker handshake now sends `X-Workspace-ID` — without it a workspace-bound PRODUCTION
  key could not fetch its ESB details (KIM-457).** Version bumped to **3.0.8**.

  `GoogleExtensionConfig.fetchInvokerDetails` already resolved the workspace id and put it in the
  query string (`?workspaceId=…`), but CA's `WorkspaceAuthorizationFilter` reads the **header**
  only. It rejects any PRODUCTION/TRIAL-tier request without it, and it runs **before** the
  resource — so the call died in the filter chain with a 400 and never reached the licensing logic.
  `buildInvokerRequest` now takes the workspace id and sets the header on both the initial request
  and the 401 retry.

  When that call fails the extension logs "Failed to fetch invoker details" and falls back to the
  qualified FQDN broker, so the symptom surfaces as an ESB connectivity failure rather than a
  licensing one.

  The gap was invisible until now because FULL/ops keys make the header optional, and every
  workspace was running on one. It is only exposed by the move to workspace-bound PRODUCTION keys.

### Added

- `Constants.WORKSPACE_ID_HEADER` — the tenant context header name, alongside the existing
  `MA_API_KEY_HEADER`, so future CA calls from this extension have one place to take it from.

### Known issues

- `GoogleExtensionConfig` still hardcodes the ops key `dev-full-key-12345` (`DEV_API_KEY`) and
  **automatically retries with it on a 401**, silently escalating to FULL tier. FULL bypasses
  workspace isolation and the licence-admin gate, so this is a cross-tenant escalation path — and
  it is what masked the header bug above. `Constants.resolveMaApiKey()` falls back to the same
  constant. Tracked separately from KIM-457; removing it is a behaviour change for any environment
  whose invoker lacks the `apiKey` attribute.
