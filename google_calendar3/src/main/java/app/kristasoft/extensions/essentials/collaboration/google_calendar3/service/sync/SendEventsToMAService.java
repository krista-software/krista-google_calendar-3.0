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

import app.krista.extension.authorization.MustAuthorizeException;
import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.CaGateway;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.EsbCaGateway;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.CalendarConnectionStatusStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.model.CreateMeetingRequestDto;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.TrackMeetingEventStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.Constants;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.gson.JsonObject;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Service
public class SendEventsToMAService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SendEventsToMAService.class);
    private static final int BATCH_SIZE_LIMIT = 25;
    private static final int MAX_CONCURRENT_USERS = 5;
    private static final DateTimeFormatter UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final GoogleProviderFactory googleProviderFactory;
    private final GoogleCalendarEventFetcher eventFetcher;
    private final GoogleEventToMeetingDtoMapper eventMapper;
    private final Invoker invoker;
    private final MeetingKeyResolver meetingKeyResolver;
    private final TrackMeetingEventStore trackMeetingEventStore;
    private final CalendarSyncTelemetryService telemetryService;
    private final UserKeyIndexStore userKeyIndexStore;
    private final CalendarDiscoveryService discoveryService;
    private final EsbCaGateway caGateway;

    /** Reports each Always On full sync to Meeting Agent; null only for source-compatible callers. */
    private final ScannedMailboxReporter scannedMailboxReporter;

    /** Always On statuses for the mailboxes a sync read; null only for source-compatible callers. */
    private final CalendarConnectionStatusStore connectionStatusStore;

    /**
     * KIM-474: {@code GoogleCalendarAuthenticationService} is no longer injected here. The
     * consented-user list is one of two possible scan sources now, and both the list and the
     * "is this user syncable?" gate are owned by {@link CalendarDiscoveryService} — reading the
     * consented store directly from here is exactly the scattered-branch mistake that seam exists
     * to prevent.
     */
    @Inject
    public SendEventsToMAService(GoogleProviderFactory googleProviderFactory,
                                 GoogleCalendarEventFetcher eventFetcher,
                                 GoogleEventToMeetingDtoMapper eventMapper,
                                 Invoker invoker,
                                 MeetingKeyResolver meetingKeyResolver,
                                 TrackMeetingEventStore trackMeetingEventStore,
                                 CalendarSyncTelemetryService telemetryService,
                                 UserKeyIndexStore userKeyIndexStore,
                                 CalendarDiscoveryService discoveryService,
                                 EsbCaGateway caGateway,
                                 ScannedMailboxReporter scannedMailboxReporter,
                                 CalendarConnectionStatusStore connectionStatusStore) {
        this.googleProviderFactory = googleProviderFactory;
        this.eventFetcher = eventFetcher;
        this.eventMapper = eventMapper;
        this.invoker = invoker;
        this.meetingKeyResolver = meetingKeyResolver;
        this.trackMeetingEventStore = trackMeetingEventStore;
        this.telemetryService = telemetryService;
        this.userKeyIndexStore = userKeyIndexStore;
        this.discoveryService = discoveryService;
        this.caGateway = caGateway;
        this.scannedMailboxReporter = scannedMailboxReporter;
        this.connectionStatusStore = connectionStatusStore;
    }

    /** Keeps lightweight callers source-compatible. HK2 uses the injected constructor. */
    public SendEventsToMAService(GoogleProviderFactory googleProviderFactory,
                                 GoogleCalendarEventFetcher eventFetcher,
                                 GoogleEventToMeetingDtoMapper eventMapper,
                                 Invoker invoker,
                                 MeetingKeyResolver meetingKeyResolver,
                                 TrackMeetingEventStore trackMeetingEventStore,
                                 CalendarSyncTelemetryService telemetryService,
                                 UserKeyIndexStore userKeyIndexStore,
                                 CalendarDiscoveryService discoveryService,
                                 EsbCaGateway caGateway) {
        this(googleProviderFactory, eventFetcher, eventMapper, invoker, meetingKeyResolver,
                trackMeetingEventStore, telemetryService, userKeyIndexStore, discoveryService, caGateway,
                null, null);
    }

    /**
     * Syncs calendar events to MA for a single user.
     *
     * <p>KIM-474: the "may this user be synced?" gate and the credential choice both come from
     * {@link CalendarDiscoveryService}. With Always On off both answers are exactly what they were
     * before — the consented-user list and the user's own refresh token.
     */
    public void sendEventsToMAByUser(String userId, long numberOfDays) {
        LOGGER.info("Starting single-user sync for user: {}", userId);
        long startTime = System.currentTimeMillis();

        CalendarSyncTelemetryService.TelemetryContext syncContext = telemetryService.recordSyncStart("single_user_sync");

        if (!discoveryService.isSyncable(userId)) {
            LOGGER.warn("User {} is not authenticated. Aborting sync.", userId);
            telemetryService.recordSyncFailure(syncContext, new SecurityException("User not authenticated"));
            return;
        }

        CalendarDiscoveryService.DiscoverySource source = discoveryService.sourceFor(userId);
        ScannedMailboxReporter.SweepOutcomes outcomes = new ScannedMailboxReporter.SweepOutcomes();
        try {
            int eventCount;
            try {
                eventCount = processUser(userId, numberOfDays, source, outcomes);
            } finally {
                // Always On: a single-user read is a read like any other, so it moves the
                // mailbox's status too. Opt-In publishes nothing from here, as before.
                if (source.delegated()) {
                    new DelegatedMailboxStatus(connectionStatusStore, invoker).apply(outcomes);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            telemetryService.recordSyncSuccess(syncContext);
            telemetryService.recordUserProcessing(userId, eventCount, duration);
            telemetryService.recordEventsProcessed(eventCount, "single_user_sync");

            LOGGER.info("Finished single-user sync for user: {} ({} events) in {} ms", userId, eventCount, duration);
        } catch (MustAuthorizeException e) {
            LOGGER.warn("Google authorization is required for user {}", userId);
            telemetryService.recordSyncFailure(syncContext, e);
            throw e;
        } catch (Exception e) {
            LOGGER.error("Error during single-user sync for user {}: {}", userId, e.getMessage(), e);
            telemetryService.recordSyncFailure(syncContext, e);
            throw new RuntimeException("Error during single-user sync", e);
        }
    }

    /**
     * Syncs calendar events to MA for every user in this sweep's scan set.
     *
     * <p>KIM-474 / WP-G1: the set comes from {@link CalendarDiscoveryService#discover(String)} — the
     * consented users while Always On is off; when it is on, this workspace's registered, licensed
     * Meeting Agent users read through domain-wide delegation and <em>only</em> those — or nobody,
     * when the delegated scope cannot be resolved this sweep. The set and each user's credential are
     * resolved ONCE into an immutable value, so an admin toggling the flag mid-sweep cannot change
     * what a running sweep is reading or how.
     *
     * <p>An Always On sync is a sweep that resolves a scope, so it publishes each mailbox's status
     * (CONNECTED when its calendar was read, DISCONNECTED when a previously-connected one could not
     * be) and reports the snapshot to Meeting Agent — an empty one carrying the reason when the
     * sweep was degraded. An Opt-In sync does neither, as before Always On existed.
     *
     * <p>The fan-out is unchanged: the same {@link #MAX_CONCURRENT_USERS} semaphore over virtual
     * threads bounds concurrent Google API pressure regardless of how large the set is.
     */
    public void sendEventsToMA(long numberOfDays) {
        LOGGER.info("Starting process to sync Google Calendar events with MA service...");
        long startTime = System.currentTimeMillis();

        CalendarSyncTelemetryService.TelemetryContext syncContext = telemetryService.recordSyncStart("full_sync");

        CalendarDiscoveryService.UserSet scanSet = discoveryService.discover("full_sync");
        List<String> authenticatedUsers = scanSet.userIds();
        ScannedMailboxReporter.SweepOutcomes outcomes = new ScannedMailboxReporter.SweepOutcomes();
        if (authenticatedUsers.isEmpty()) {
            LOGGER.warn("No users in scope. Aborting sync process.");
            finishSweep(scanSet, outcomes);
            telemetryService.recordSyncSuccess(syncContext);
            return;
        }
        LOGGER.info("Found {} user(s) to process (source={}).", authenticatedUsers.size(), scanSet.source());

        Map<String, Long> userProcessingTimes = new ConcurrentHashMap<>();
        Map<String, Integer> userEventCounts = new ConcurrentHashMap<>();

        Semaphore semaphore = new Semaphore(MAX_CONCURRENT_USERS);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = authenticatedUsers.stream()
                    .map(user -> CompletableFuture.runAsync(() -> {
                        try {
                            semaphore.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            LOGGER.error("Interrupted while waiting for semaphore for user: {}", user);
                            return;
                        }

                        try {
                            long userStartTime = System.currentTimeMillis();
                            int eventCount = processUser(user, numberOfDays, scanSet.sourceOf(user), outcomes);
                            long userDuration = System.currentTimeMillis() - userStartTime;

                            userProcessingTimes.put(user, userDuration);
                            userEventCounts.put(user, eventCount);

                            LOGGER.info("Processed user {} ({} events in {} ms)", user, eventCount, userDuration);
                        } catch (Exception e) {
                            LOGGER.error("Error processing user {}: {}", user, e.getMessage(), e);
                            // A failure before the calendar was read is a failed read; one after it
                            // (Meeting Agent refusing an upsert) says nothing about the mailbox.
                            if (!outcomes.recorded(user)) {
                                outcomes.failed(user, e);
                            }
                        } finally {
                            semaphore.release();
                        }
                    }, executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        // TelemetryMetrics is request-context scoped and cannot be invoked from the
        // virtual user-processing threads. Flush the per-user metrics on the caller
        // thread after all workers have completed and their results are available.
        authenticatedUsers.forEach(user -> {
            Integer eventCount = userEventCounts.get(user);
            Long userDuration = userProcessingTimes.get(user);
            if (eventCount != null && userDuration != null) {
                telemetryService.recordUserProcessing(user, eventCount, userDuration);
            }
        });

        finishSweep(scanSet, outcomes);

        long totalDuration = System.currentTimeMillis() - startTime;
        int totalEvents = userEventCounts.values().stream().mapToInt(Integer::intValue).sum();

        telemetryService.recordSyncSuccess(syncContext);
        telemetryService.recordEventsProcessed(totalEvents, "full_sync");

        logSyncStatistics(authenticatedUsers, userProcessingTimes, userEventCounts, totalDuration, totalEvents);
    }

    /**
     * Always On: publishes the status of every mailbox this sync attempted, plus the registered
     * seats the directory no longer lists, then reports the snapshot. Runs on the calling thread,
     * after every worker has finished, because the status and report paths need the invoker
     * context the virtual threads do not carry. A degraded sync attempted nobody and publishes
     * nothing; its snapshot is empty and carries the reason. Opt-In does neither.
     */
    private void finishSweep(CalendarDiscoveryService.UserSet scanSet,
                             ScannedMailboxReporter.SweepOutcomes outcomes) {
        if (!scanSet.source().delegated()) {
            return;
        }
        if (!scanSet.degraded()) {
            DelegatedMailboxStatus status = new DelegatedMailboxStatus(connectionStatusStore, invoker);
            status.apply(outcomes);
            status.notInDirectory(scanSet.seatsNotInDirectory());
        }
        if (scannedMailboxReporter != null) {
            scannedMailboxReporter.report(scanSet, outcomes, "full_sync");
        }
    }

    /**
     * Syncs one user's calendar.
     *
     * @param userId       the user id to scan — an email on the delegated path, an email or
     *                     {@code wsContact_<id>} key on the consented path
     * @param numberOfDays how many future days to fetch
     * @param source       which credential reads this calendar, resolved for this user from the
     *                     sweep's immutable set ({@code UserSet.sourceOf})
     * @param outcomes     where to record whether the calendar was read: proved once the events
     *                     are in hand, failed when the client or the fetch fails
     * @return the number of events fetched
     */
    private int processUser(String userId, long numberOfDays, CalendarDiscoveryService.DiscoverySource source,
                            ScannedMailboxReporter.SweepOutcomes outcomes) {
        LOGGER.info("Processing calendar events for user: {}", userId);

        Calendar calendarService;
        // MA D-05 requires BOTH:
        //  (a) at least one participant on an internal domain  → configuredEmail (getMailId, e.g. @kristasw.com)
        //  (b) at least one licensed, ACTIVE user in registry → primaryCalendarId (actual Google account)
        String primaryCalendarId  = null; // satisfies (b)
        String configuredEmail    = null; // satisfies (a)
        try {
            // Adaptive sync can run on virtual threads, where Krista's request-scoped
            // RequestContext is not populated. Use the stable extension-scoped invoker id.
            GoogleProvider provider = googleProviderFactory.createForInvoker(invoker.getInvokerId());
            // Consented source: the AUTHENTICATED USER's own token, so the sync reads THEIR calendar
            // and not the invoker-configured account (getGoogleClientForAdmin).
            // Enrolled source: a domain-wide-delegation credential impersonating this registered
            // user's mailbox — "primary" then resolves to their own calendar exactly the same way.
            // The discovery service selects the user's refresh token or the delegated credential
            // according to the immutable source resolved for this sync.
            calendarService = discoveryService.calendarClientFor(provider, userId, source);
            primaryCalendarId = resolvePrimaryCalendarId(calendarService);
            if (primaryCalendarId == null && source.delegated()) {
                // Under delegation the primary calendar's id IS the impersonated address, which the
                // Directory already told us. Recovering it here keeps D-05 condition (b) satisfied
                // when the extra calendars.get round-trip fails, rather than sending an ownerless
                // upsert that MA would reject.
                primaryCalendarId = userId;
                LOGGER.info("[GCAL-IDENTITY] sync: using the enumerated address as the primary calendar "
                        + "id for {} (calendars.get did not answer)", userId);
            }
            configuredEmail   = resolveConfiguredEmail(provider);
            // DIAGNOSTIC: confirm which Google calendar this client actually reads.
            logActiveCalendarIdentity(userId, provider, primaryCalendarId);
            // Persist email->token-key while the token is healthy so a later CA-initiated
            // reconnect (ESB thread, broken token, email only) can recover this user's
            // token-store key instead of falling back to the bare email.
            if (primaryCalendarId != null) {
                userKeyIndexStore.put(invoker.getInvokerId(), primaryCalendarId, userId);
            }
            LOGGER.info("[GCAL-IDENTITY-D05] primaryCalendarId={} configuredEmail={}", primaryCalendarId, configuredEmail);
        } catch (MustAuthorizeException e) {
            // Do not turn this into "0 events". The single-user catalog request must receive the
            // original MustAuthorizeException so the conversation can render its auth button.
            outcomes.failed(userId, e);
            throw e;
        } catch (Exception e) {
            LOGGER.error("Failed to create Google Calendar client for user {}: {}", userId, e.getMessage(), e);
            outcomes.failed(userId, e);
            return 0;
        }
        final String calendarOwnerEmail = primaryCalendarId;
        final String workspaceEmail     = configuredEmail;

        // KIM-474 (scale): only a delegated sweep reads enough calendars to provoke a Google quota
        // refusal, and only it may wait one out. The consented path issues the same single call it
        // always has.
        List<Event> events;
        try {
            events = eventFetcher.fetchEventsForDays(calendarService, numberOfDays, source.delegated());
        } catch (RuntimeException fetchFailure) {
            outcomes.failed(userId, fetchFailure);
            throw fetchFailure;
        }
        if (events == null) {
            IllegalStateException incomplete =
                    new IllegalStateException("Google Calendar returned no complete snapshot");
            outcomes.failed(userId, incomplete);
            throw incomplete;
        }
        outcomes.proved(userId);
        LOGGER.info("Fetched {} event(s) for user {}", events.size(), userId);
        LOGGER.info("Preparing to send {} events to MA service for user {}", events.size(), userId);

        // 1) Load tracked state
        Set<String> allTrackedIds = trackMeetingEventStore.getTrackedEventIds(userId);
        Map<String, JsonObject> trackedMeta = trackMeetingEventStore.getTrackedEventsMeta(userId);
        LOGGER.info("Tracked entries: {}", allTrackedIds.size());

        // Google gives every occurrence in a series the SAME iCalUID. Event IDs identify
        // occurrences, so one remaining sibling must not hide a deleted occurrence.
        Set<String> currentEventIds = new HashSet<>();
        for (Event e : events) {
            if (e != null && e.getId() != null) currentEventIds.add(e.getId());
        }

        // 3) Detect deletions
        LOGGER.info("Detecting deleted events for user {}", userId);
        detectAndProcessDeletions(userId, allTrackedIds, trackedMeta, currentEventIds,
                numberOfDays, calendarService);

        // 4) Process current events (create/update/handle cancelled)
        LOGGER.info("Processing {} current event(s) for user {}", events.size(), userId);
        LOGGER.info("Starting event processing phase: create/update/cancelled for user {}", userId);

        List<CreateMeetingRequestDto> meetingsToSend = new ArrayList<>();
        Map<CreateMeetingRequestDto, String> eventIds = new HashMap<>();

        // Per-user diagnostic counters (summarised in the [GCAL-SUMMARY] line below).
        int receivedCount = 0;
        int skippedNoLinkCount = 0;
        int cancelledCount = 0;

        for (Event event : events) {
            if (event == null) continue;
            receivedCount++;

            // DIAGNOSTIC: PII-free decision summary for every event received from Google,
            // BEFORE the meeting-link filter — confirms receipt and isolates whether link-less
            // meetings are dropped here (the filter) or somewhere downstream in MA.
            LOGGER.info("[GCAL-RECV] sync: received=true id={} status={} attendees={} recurring={}",
                    safe(event.getId()), safe(event.getStatus()), attendeeCount(event),
                    event.getRecurringEventId() != null);

            // Deleted instances may contain only id/recurringEventId/originalStartTime.
            // They must reach deletion even when Google has removed the meeting link.
            if ("cancelled".equals(event.getStatus())) {
                cancelledCount++;
                handleCancelledEvent(userId, event, calendarOwnerEmail);
                continue;
            }

            // Check for meeting URL
            String meetingUrl = eventMapper.extractMeetingUrl(event);
            boolean hasMeetingLink = meetingUrl != null && !meetingUrl.isBlank();
            LOGGER.info("[GCAL-LINK] sync: id={} hasMeetingLink={} platform={}",
                    safe(event.getId()), hasMeetingLink,
                    hasMeetingLink ? eventMapper.detectPlatformFromUrl(meetingUrl) : "none");
            if (!eventMapper.isEligibleCalendarEvent(event)) {
                skippedNoLinkCount++;
                LOGGER.info("[GCAL-SKIP] sync: id={} reason=PERSONAL_CALENDAR_BLOCK action=NOT_SENT_TO_MA", safe(event.getId()));
                continue;
            }

            // Map and inject both emails:
            //   calendarOwnerEmail → registered Gmail user → satisfies D-05 condition (b)
            //   workspaceEmail     → internal-domain email → satisfies D-05 condition (a)
            CreateMeetingRequestDto dto = stampInvokerId(
                    eventMapper.mapWithCalendarOwner(event, calendarOwnerEmail, workspaceEmail));
            if (dto != null && dto.getiCalUId() != null && event.getId() != null) {
                meetingsToSend.add(dto);
                eventIds.put(dto, event.getId());
                LOGGER.debug("Mapped event for batch processing: {} (ID: {})", safe(event.getSummary()), safe(event.getId()));
            } else if (dto != null) {
                LOGGER.warn("Event {} missing iCalUID or Event ID. Skipping batch inclusion.", safe(event.getId()));
            }
        }

        LOGGER.info("Master list contains {} event(s) to be batched.", meetingsToSend.size());

        // 5) Batch send
        if (!meetingsToSend.isEmpty()) {
            int totalMeetings = meetingsToSend.size();
            int batchCount = (int) Math.ceil((double) totalMeetings / BATCH_SIZE_LIMIT);
            LOGGER.info("Starting batch transmission: {} events in {} batches", totalMeetings, batchCount);
            
            // The reply identifies rows by iCalUID, with no occurrence ID. Keep that identifier
            // unique within each request so reordered replies cannot overwrite sibling mappings.
            List<CreateMeetingRequestDto> batch = new ArrayList<>();
            Set<String> batchUids = new HashSet<>();
            for (CreateMeetingRequestDto meeting : meetingsToSend) {
                if (batch.size() == BATCH_SIZE_LIMIT || batchUids.contains(meeting.getiCalUId())) {
                    sendMeetingEventsBatchToMAAndStoreMeta(userId, batch, eventIds);
                    batch = new ArrayList<>();
                    batchUids.clear();
                }
                batch.add(meeting);
                batchUids.add(meeting.getiCalUId());
            }
            sendMeetingEventsBatchToMAAndStoreMeta(userId, batch, eventIds);
            LOGGER.info("Completed all batch transmissions for user {}", userId);
        } else {
            LOGGER.info("No events to send to MA service for user {}", userId);
        }

        int sentToMA = meetingsToSend.size();
        int mappingFailed = Math.max(0, receivedCount - skippedNoLinkCount - cancelledCount - sentToMA);
        LOGGER.info("[GCAL-SUMMARY] sync: user={} received={} sentToMA={} skippedNoLink={} cancelled={} mappingFailed={}",
                userId, receivedCount, sentToMA, skippedNoLinkCount, cancelledCount, mappingFailed);

        LOGGER.info("Finished processing user {}", userId);
        return events.size();
    }

    private void detectAndProcessDeletions(String userId, Set<String> allTrackedIds,
                                           Map<String, JsonObject> trackedMeta,
                                           Set<String> currentEventIds, long numberOfDays,
                                           Calendar calendarService) {
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
        Instant todayStartUtc = nowUtc.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant fetchWindowEndUtc = nowUtc.plusDays(numberOfDays).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        // Filter tracked IDs to only those within the fetch window
        for (String trackedId : allTrackedIds) {
            if (currentEventIds.contains(trackedId)) continue;
            JsonObject meta = trackedMeta.getOrDefault(trackedId, new JsonObject());

            // Check if event is within the fetch window
            String storedStart = meta.has("start") ? meta.get("start").getAsString() : null;
            if (storedStart != null && !storedStart.isBlank()) {
                try {
                    Instant startInstant = parseStoredStartInstant(storedStart);
                    if (startInstant.isBefore(todayStartUtc) || !startInstant.isBefore(fetchWindowEndUtc)) {
                        continue; // Outside fetch window — don't consider for deletion
                    }
                } catch (DateTimeParseException e) {
                    LOGGER.warn("Failed to parse stored start time '{}' for event {}.", storedStart, trackedId);
                }
            }

            try {
                // Absence from a time window can also mean a reschedule. Confirm deletion before
                // removing the CA meeting; quota/permission/network failures are NOT deletions.
                boolean deleted;
                try {
                    Event current = calendarService.events().get("primary", trackedId).execute();
                    deleted = current != null && "cancelled".equals(current.getStatus());
                } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
                    if (e.getStatusCode() != 404 && e.getStatusCode() != 410) throw e;
                    deleted = true;
                }
                if (!deleted) continue;
                String storedKey = trackMeetingEventStore.getMeetingKey(userId, trackedId);
                String iCalUId = meta.has("iCalUId") && !meta.get("iCalUId").isJsonNull()
                        ? meta.get("iCalUId").getAsString() : null;
                if (storedKey != null && !storedKey.isBlank()
                        && deleteMeetingFromMAByKey(storedKey, iCalUId, "calendar event no longer present")) {
                    trackMeetingEventStore.removeTrackedEvent(userId, trackedId);
                }
            } catch (Exception e) {
                LOGGER.warn("Could not reconcile missing event {} for {}: {}", trackedId, userId, e.getMessage());
            }
        }
    }

    /**
     * Deletes a cancelled Google event from Meeting Agent.
     *
     * @param userId             the scan-set user id — an email on the delegated path, an email or an
     *                           opaque {@code wsContact_<uuid>} client key on the consented one. Used
     *                           only to key the LOCAL tracking store, which is keyed by it
     * @param event              the cancelled event
     * @param calendarOwnerEmail EF-12: the mailbox owner's real address, resolved from the primary
     *                           calendar for this sync. This — never {@code userId} — is what scopes
     *                           the Meeting Agent lookup, because Meeting Agent applies
     *                           {@code userEmail} as a HARD filter and a client key is not an address,
     *                           so it matched no attendee and the lookup returned nothing. May be
     *                           null, in which case the lookup runs unscoped on {@code iCalUId}
     */
    private void handleCancelledEvent(String userId, Event event, String calendarOwnerEmail) {
        LOGGER.info("Event '{}' (ID: {}) is cancelled. Deleting from MA...", safe(event.getSummary()), safe(event.getId()));

        boolean deleted = false;
        String storedKey = trackMeetingEventStore.getMeetingKey(userId, event.getId());
        if (storedKey != null && !storedKey.isBlank()) {
            deleted = deleteMeetingFromMAByKey(storedKey, null, "calendar event cancelled");
        } else {
            // KIM-492 WP-E16 (A5): ONE lookup carrying every criterion this extension holds, not a
            // URL-and-start probe against a route MA has never served. iCalUId is the strongest key
            // and was not even expressible over HTTP — supplying it is what makes this branch
            // resolve at all, and why the first sync after this release deletes meetings whose
            // calendar event went away months ago (spec §6 R14).
            String meetingUrl = eventMapper.extractMeetingUrl(event);
            String startTime = GoogleEventIdentity.originalStart(event);

            // EF-12: the mailbox OWNER's address, not the scan-set user id. MA applies userEmail
            // as a hard filter on every branch of its lookup precedence, and on the consented path
            // userId can be an opaque wsContact_<uuid> client key that matches no attendee anywhere
            // — so this branch found nothing and skipped the delete for exactly the users whose keys
            // are not addresses. MeetingKeyResolver drops a non-address filter as a backstop.
            String mk = meetingKeyResolver.resolveMeetingKey(
                    GoogleEventIdentity.meetingUid(event), meetingUrl, startTime, calendarOwnerEmail);
            if (mk != null && !mk.isBlank()) {
                deleted = deleteMeetingFromMAByKey(mk, null, "calendar event cancelled");
            } else {
                LOGGER.warn("Could not resolve a meetingKey for cancelled event {} (user {}).",
                        event.getId(), userId);
            }
        }

        if (!deleted) return;
        try {
            trackMeetingEventStore.removeTrackedEvent(userId, event.getId());
        } catch (Exception ignored) {
            LOGGER.debug("Ignored exception while removing cancelled event {} for {}", event.getId(), userId);
        }
    }

    private void sendMeetingEventsBatchToMAAndStoreMeta(String userId,
                                                        List<CreateMeetingRequestDto> meetingEvents,
                                                        Map<CreateMeetingRequestDto, String> eventIds) {
        if (meetingEvents.isEmpty()) return;

        try {
            // KIM-492 WP-E16 (A2): per-meeting outcomes, not a count. The HTTP batch route answered
            // with an array the caller had to parse for (meetingKey, iCalUId); the contract reply
            // carries those as rows, each with its own errorCode, so a meeting MA dropped is
            // distinguishable from one it never saw instead of only showing up as created<sent.
            List<CaGateway.UpsertOutcome> outcomes =
                    caGateway.bulkUpsertMeetings(meetingEvents);

            long upserted = outcomes.stream().filter(CaGateway.UpsertOutcome::acceptedByCa).count();
            // EF-28: a batch over the chunk cap is several ESB calls, and one of them can fail while
            // the others succeed. "accepted < sent" alone cannot tell those apart from MA's D-05
            // gate, so the transport failures are counted separately and the telemetry keys on them.
            // Reporting a partially-delivered batch as a clean one filed a broker outage as a
            // customer registration problem.
            long unreached = outcomes.stream().filter(CaGateway.UpsertOutcome::caUnreached).count();
            if (unreached > 0) {
                LOGGER.warn("[GCAL-BATCH-RESULT] sent={} accepted={} unreached={} — part of this"
                                + " batch never reached MA. Those meetings are NOT tracked and the"
                                + " next sweep re-offers them.",
                        meetingEvents.size(), upserted, unreached);
            }
            else {
                LOGGER.info("[GCAL-BATCH-RESULT] sent={} accepted={} — if accepted<sent, MA rejected"
                                + " events (most often its D-05 / NO_REGISTERED_USER gate)",
                        meetingEvents.size(), upserted);
            }
            telemetryService.recordApiCall("ma", "batch_create", !outcomes.isEmpty() && unreached == 0);

            for (CaGateway.UpsertOutcome outcome : outcomes) {
                if (!outcome.acceptedByCa()) {
                    // Deliberately NOT tracked. A dropped meeting keeps no tracking entry, so the
                    // next sweep re-offers it once somebody on it registers — the behaviour the
                    // HTTP path had by accident (its response array simply omitted the row) and
                    // this one has on purpose.
                    LOGGER.debug("MA did not accept iCalUId={}: {} {}",
                            outcome.iCalUId(), outcome.errorCode(), outcome.errorMessage());
                    continue;
                }

                String iCalUId = outcome.iCalUId();
                CreateMeetingRequestDto matched = meetingEvents.stream()
                        .filter(dto -> java.util.Objects.equals(iCalUId, dto.getiCalUId()))
                        .findFirst().orElse(null);
                if (iCalUId == null || matched == null || outcome.meetingKey() == null
                        || outcome.meetingKey().isBlank()) {
                    continue;
                }
                String eventId = eventIds.get(matched);
                String startTime = matched.getStartTime();

                trackMeetingEventStore.trackEvent(userId, eventId, outcome.meetingKey(), startTime, iCalUId);
                trackMeetingEventStore.trackSeries(userId, eventId, matched.getRecurringSeriesId());
                LOGGER.debug("Tracked event {} with meetingKey={} iCalUId={}",
                        eventId, outcome.meetingKey(), iCalUId);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to send batch to MA service: {}", e.getMessage(), e);
            telemetryService.recordApiCall("ma", "batch_create", false);
        }
    }

    /**
     * Deletes one meeting from Meeting Agent (KIM-492 WP-E16, A3).
     *
     * @param meetingKey MA's key for the meeting
     * @param iCalUId    the calendar event's identifier, for MA's own verification and its audit
     *                   trail; may be null
     * @param reason     why, for MA's audit trail
     */
    private boolean deleteMeetingFromMAByKey(String meetingKey, String iCalUId, String reason) {
        try {
            // "Not found" counts as accepted on both transports: the meeting is already gone, which
            // is the state this call wanted.
            boolean accepted = caGateway.deleteMeeting(meetingKey, iCalUId, reason);
            if (accepted) {
                LOGGER.info("Successfully deleted meeting {} from MA", meetingKey);
                telemetryService.recordApiCall("ma", "delete_meeting", true);
            } else {
                LOGGER.warn("MA refused the delete for meetingKey={}", meetingKey);
                telemetryService.recordApiCall("ma", "delete_meeting", false);
            }
            return accepted;
        } catch (Exception e) {
            LOGGER.error("Failed to delete meeting {} from MA: {}", meetingKey, e.getMessage(), e);
            telemetryService.recordApiCall("ma", "delete_meeting", false);
            return false;
        }
    }

    /**
     * KIM-474 (FR-002): stamps this invoker's id on an outbound upsert so Meeting Agent can
     * attribute a captured meeting to the extension instance that reported it.
     *
     * <p>Never throws. The id is informational on Meeting Agent's side — never a capture gate — so
     * an invoker that cannot report its own id (removed mid-sync, which makes every call on it
     * throw {@link IllegalStateException}) must leave the field null rather than drop the meeting.
     *
     * @param dto the mapped upsert, or null when mapping failed
     * @return the same instance, for call-site chaining
     */
    private CreateMeetingRequestDto stampInvokerId(CreateMeetingRequestDto dto) {
        if (dto == null) {
            return null;
        }
        try {
            dto.setInvokerId(invoker.getInvokerId());
        } catch (Exception e) {
            LOGGER.warn("Could not read invokerId for the meeting upsert; sending without it: {}", e.getMessage());
        }
        return dto;
    }

    private void logSyncStatistics(List<String> authenticatedUsers,
                                   Map<String, Long> userProcessingTimes,
                                   Map<String, Integer> userEventCounts,
                                   long totalDuration, int totalEvents) {
        LOGGER.info("==========================================");
        LOGGER.info("Sync Statistics:");
        LOGGER.info("Users processed: {}", authenticatedUsers.size());
        LOGGER.info("Total events processed: {}", totalEvents);
        LOGGER.info("Total execution time: {} ms ({} seconds)", totalDuration, totalDuration / 1000.0);

        if (totalEvents > 0 && totalDuration > 0) {
            double eventsPerSecond = (totalEvents * 1000.0) / totalDuration;
            LOGGER.info("Throughput: {} events/second", String.format("%.2f", eventsPerSecond));
        }

        if (!userProcessingTimes.isEmpty()) {
            userProcessingTimes.forEach((user, time) -> {
                int evts = userEventCounts.getOrDefault(user, 0);
                LOGGER.info("  - {}: {} events in {} ms", user, evts, time);
            });
        }
        LOGGER.info("==========================================");
    }

    private String safe(String value) {
        return value != null ? value : "<null>";
    }

    /**
     * Parses a tracked-event "start" timestamp into an {@link Instant}, tolerant of every form the
     * store may hold:
     * <ul>
     *   <li>UTC instant with a trailing {@code Z} — the current mapper output
     *       ({@code yyyy-MM-dd'T'HH:mm:ss'Z'});</li>
     *   <li>offset form (e.g. {@code ...+05:30}) — written by the webhook path from Google's
     *       {@code toStringRfc3339()};</li>
     *   <li>legacy zone-less form ({@code yyyy-MM-dd'T'HH:mm:ss}) — entries tracked before the
     *       UTC-marker fix; interpreted as UTC wall-clock.</li>
     * </ul>
     * Throws {@link DateTimeParseException} only when the value matches none of these.
     */
    private Instant parseStoredStartInstant(String storedStart) {
        String s = storedStart.trim();
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException notOffset) {
            return ZonedDateTime.parse(s, UTC_FORMATTER.withZone(ZoneOffset.UTC)).toInstant();
        }
    }

    // ---- Diagnostic helper (used by [GCAL-RECV] logging) ----

    private static int attendeeCount(Event e) {
        return e.getAttendees() != null ? e.getAttendees().size() : 0;
    }

    /**
     * Logs which Google account/calendar the sync client actually reads:
     * the requested user id, the email the client was built for, and the real
     * primary-calendar id (the account email per Google). A mismatch between the
     * requested user and the primary calendar means the sync is reading the
     * wrong calendar.
     */
    private void logActiveCalendarIdentity(String userId, GoogleProvider provider, String primaryCalendarId) {
        String clientGoogleAccount;
        try {
            clientGoogleAccount = provider.getGoogleAttributes().getMailId();
        } catch (Exception e) {
            clientGoogleAccount = "<error>";
        }
        LOGGER.info("[GCAL-IDENTITY] sync: requestedUserId={} clientGoogleAccount={} primaryCalendarId={}",
                userId, clientGoogleAccount, primaryCalendarId == null ? "<error>" : primaryCalendarId);
    }

    /**
     * Resolves the primary calendar's id (= the calendar owner's email for the
     * "invoke as user" token, e.g. the Gmail account). Returns null on failure.
     */
    private String resolvePrimaryCalendarId(Calendar calendarService) {
        try {
            return calendarService.calendars().get("primary").execute().getId();
        } catch (Exception e) {
            LOGGER.warn("Could not resolve primary calendar id for owner injection: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the configured workspace / admin email from the extension attributes
     * ({@code gmailAttributes.getMailId()}). This is typically on the company's
     * internal domain (e.g. {@code @kristasw.com}) and satisfies MA's D-05
     * condition (a). Returns null if the factory or attributes are unavailable.
     */
    private String resolveConfiguredEmail(GoogleProvider provider) {
        try {
            String email = provider.getGoogleAttributes().getMailId();
            return (email != null && !email.isBlank()) ? email.trim() : null;
        } catch (Exception e) {
            LOGGER.warn("Could not resolve configured email for D-05 condition (a): {}", e.getMessage());
            return null;
        }
    }
}
