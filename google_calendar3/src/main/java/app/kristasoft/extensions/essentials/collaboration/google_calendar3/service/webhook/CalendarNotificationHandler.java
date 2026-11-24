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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.webhook;

import app.krista.extension.authorization.MustAuthorizeException;
import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProvider;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.GoogleProviderFactory;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.TrackMeetingEventStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.store.UserKeyIndexStore;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util.AuthFailures;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarDiscoveryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleEventToMeetingDtoMapper;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.MeetingKeyResolver;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.GoogleEventIdentity;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles Google Calendar push notification events.
 * Uses incremental sync (sync tokens) to fetch only changed events,
 * then sends creates/updates/deletes to the MA service.
 *
 * <p>Google Calendar push notifications differ from Microsoft Graph:
 * <ul>
 *   <li>Google only notifies that "something changed" — no event details in the notification</li>
 *   <li>We must use incremental sync with a sync token to fetch the actual changes</li>
 *   <li>The sync response includes created, updated, and cancelled events</li>
 *   <li>Cancelled events have status "cancelled" and may not have full details</li>
 * </ul>
 * </p>
 */
@Service
public class CalendarNotificationHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarNotificationHandler.class);
    private static final String PRIMARY_CALENDAR = "primary";
    // Resource/service objects may be recreated per request. Striped locks bound memory while
    // serializing cursor reads and commits for a mailbox across those objects in this process.
    private static final java.util.concurrent.locks.ReentrantLock[] SYNC_LOCKS =
            java.util.stream.IntStream.range(0, 256)
                    .mapToObj(i -> new java.util.concurrent.locks.ReentrantLock())
                    .toArray(java.util.concurrent.locks.ReentrantLock[]::new);

    private final GoogleProviderFactory googleProviderFactory;
    private final CalendarEventToMAService maService;
    private final CalendarWatchStore watchStore;
    private final CalendarEventMappingStore eventMappingStore;
    private final TrackMeetingEventStore trackMeetingEventStore;
    private final GoogleEventToMeetingDtoMapper eventMapper;
    private final MeetingKeyResolver meetingKeyResolver;
    private final Invoker invoker;
    private final UserKeyIndexStore userKeyIndexStore;
    private final CalendarDiscoveryService discoveryService;

    @Inject
    public CalendarNotificationHandler(GoogleProviderFactory googleProviderFactory,
                                       CalendarEventToMAService maService,
                                       CalendarWatchStore watchStore,
                                       CalendarEventMappingStore eventMappingStore,
                                       TrackMeetingEventStore trackMeetingEventStore,
                                       GoogleEventToMeetingDtoMapper eventMapper,
                                       MeetingKeyResolver meetingKeyResolver,
                                       Invoker invoker,
                                       UserKeyIndexStore userKeyIndexStore,
                                       CalendarDiscoveryService discoveryService) {
        this.googleProviderFactory = googleProviderFactory;
        this.maService = maService;
        this.watchStore = watchStore;
        this.eventMappingStore = eventMappingStore;
        this.trackMeetingEventStore = trackMeetingEventStore;
        this.eventMapper = eventMapper;
        this.meetingKeyResolver = meetingKeyResolver;
        this.invoker = invoker;
        this.userKeyIndexStore = userKeyIndexStore;
        this.discoveryService = discoveryService;
    }

    /**
     * Handles a push notification for a user by performing incremental sync.
     *
     * @param userId       the user ID (email) whose calendar changed
     * @param channelId    the channel ID from the notification
     * @param resourceState the resource state (e.g., "sync", "exists", "not_exists")
     */
    public boolean handleNotification(String userId, String channelId, String resourceState) {
        LOGGER.info("Processing calendar notification: userId={}, channelId={}, resourceState={}",
                userId, channelId, resourceState);

        // "sync" is the initial sync message when the channel is first created — skip it
        if ("sync".equals(resourceState)) {
            LOGGER.info("Received sync confirmation for channel {}. No action needed.", channelId);
            return true;
        }

        java.util.concurrent.locks.ReentrantLock lock = SYNC_LOCKS[Math.floorMod(
                java.util.Objects.hash(invoker.getInvokerId(), userId), SYNC_LOCKS.length)];
        lock.lock();
        try {
            if (!discoveryService.isSyncable(userId)) {
                LOGGER.info("Ignoring notification for user {} outside the active sync scope", userId);
                return true;
            }
            Calendar calendarService = getCalendarClient(userId);
            // S-14: Meeting Agent's live gate is the SEAT limb alone -
            // activeExtension && (seat || (tenantWideCapture && internalDomainMatch)), and
            // tenantWideCapture is false for every workspace under the SEAT capture scope. So
            // primaryCalendarId - the mailbox owner, a registered licensed seat - is what qualifies
            // the meeting. configuredEmail is still resolved and passed for log parity, but the
            // mapper no longer injects it as an attendee: it satisfied nothing and made the admin
            // account an attendee of every meeting in the workspace.
            String primaryCalendarId = resolvePrimaryCalendarId(calendarService);
            if (primaryCalendarId == null && discoveryService.sourceFor(userId).delegated()) {
                primaryCalendarId = userId;
            }
            String configuredEmail   = resolveConfiguredEmail();
            LOGGER.info("[GCAL-IDENTITY-D05] webhook: primaryCalendarId={} configuredEmail={}", primaryCalendarId, configuredEmail);
            // Record email<->key while the token is healthy so a later auto-detected
            // REAUTH_REQUIRED can recover the email for this wsContact_<UUID> key.
            if (primaryCalendarId != null) {
                userKeyIndexStore.put(invoker.getInvokerId(), primaryCalendarId, userId);
            }
            return performIncrementalSync(calendarService, userId, primaryCalendarId, configuredEmail);
        } catch (Exception e) {
            if (AuthFailures.needsReauthorization(e)) {
                // Expected and user-actionable, not a defect: the notification cannot be read until
                // the user reconnects, and the refresh path has already published REAUTH_REQUIRED.
                // One line, trace at DEBUG — see AuthFailures.
                LOGGER.warn("Cannot read the notification for user {}: Google authorization has "
                        + "lapsed, the user must reauthorize ({})", userId, AuthFailures.describe(e));
                LOGGER.debug("Authorization-failure detail handling notification for user {}", userId, e);
            } else {
                LOGGER.error("Error handling notification for user {}: {}", userId, e.getMessage(), e);
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resolves the primary calendar id for the per-user Calendar client —
     * the actual Google account email (e.g. the Gmail). Satisfies D-05 condition (b).
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
     * Resolves the configured workspace email from the extension attributes
     * ({@code gmailAttributes.getMailId()}), e.g. the Google Workspace account
     * on the company domain. Satisfies D-05 condition (a). Returns null on failure.
     */
    private String resolveConfiguredEmail() {
        try {
            String email = googleProviderFactory.create().getGoogleAttributes().getMailId();
            return (email != null && !email.isBlank()) ? email.trim() : null;
        } catch (Exception e) {
            LOGGER.warn("Could not resolve configured email for D-05 condition (a): {}", e.getMessage());
            return null;
        }
    }

    /**
     * Performs incremental sync using the stored sync token.
     * Fetches only changes since the last sync and processes each changed event.
     *
     * @param calendarService    authenticated Calendar client for this user
     * @param userId             the user ID (wsContact or email)
     * @param calendarOwnerEmail email from gmailAttributes — injected into event
     *                           attendees by the MA sender when the owner is absent
     */
    private boolean performIncrementalSync(Calendar calendarService, String userId,
                                        String primaryCalendarId, String configuredEmail) {
        String syncToken = watchStore.getSyncToken(userId);

        if (syncToken == null) {
            LOGGER.warn("No sync token found for user {}. Performing full sync to establish baseline.", userId);
            return syncPages(calendarService, userId, primaryCalendarId, configuredEmail, null);
        }
        return syncPages(calendarService, userId, primaryCalendarId, configuredEmail, syncToken);
    }

    private boolean syncPages(Calendar calendarService, String userId, String ownerEmail,
                              String workspaceEmail, String syncToken) {
        try {
            String pageToken = null;
            String newSyncToken = null;
            boolean delivered = true;

            do {
                Events events = calendarService.events().list(PRIMARY_CALENDAR)
                        .setSyncToken(syncToken)
                        .setPageToken(pageToken)
                        .setShowDeleted(true)
                        .execute();

                if (events.getItems() != null) {
                    for (Event event : events.getItems()) {
                        try {
                            delivered &= processChangedEvent(calendarService, event, userId, ownerEmail, workspaceEmail);
                        } catch (Exception e) {
                            delivered = false;
                            LOGGER.error("Could not apply Google event for {}: {}", userId, e.getMessage(), e);
                        }
                    }
                }

                pageToken = events.getNextPageToken();
                newSyncToken = events.getNextSyncToken();
            } while (pageToken != null);

            // Commit only after every page and CA operation succeeds. Replaying an accepted
            // upsert/delete is idempotent; skipping a failed operation permanently loses it.
            if (delivered && newSyncToken != null) {
                watchStore.putSyncToken(userId, newSyncToken);
                return true;
            }
            LOGGER.warn("Keeping sync cursor for {} because delivery or the final sync token is incomplete", userId);
            return false;

        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 410 && syncToken != null) {
                // 410 Gone — sync token is invalid, need full re-sync
                LOGGER.warn("Sync token expired for user {} (410 Gone). Performing full sync.", userId);
                watchStore.removeSyncToken(userId);
                return syncPages(calendarService, userId, ownerEmail, workspaceEmail, null);
            } else {
                LOGGER.error("Google API error during incremental sync for user {}: {}", userId, e.getMessage(), e);
            }
        } catch (IOException e) {
            LOGGER.error("IO error during incremental sync for user {}: {}", userId, e.getMessage(), e);
        }
        return false;
    }

    /**
     * Processes a single changed event based on its status.
     */
    private boolean processChangedEvent(Calendar calendarService, Event event, String userId,
                                     String calendarOwnerEmail, String configuredEmail) {
        if (event == null || event.getId() == null) {
            LOGGER.warn("Received null event or event without ID. Skipping.");
            return true;
        }

        String eventId = event.getId();
        String status = event.getStatus();

        // DIAGNOSTIC: PII-free decision summary confirming the event was received from Google
        // (webhook incremental sync) BEFORE the meeting-link filter, to isolate where
        // link-less meetings are dropped.
        LOGGER.info("[GCAL-RECV] webhook: received=true id={} status={} attendees={} recurring={}",
                eventId, status, attendeeCount(event), event.getRecurringEventId() != null);

        if ("cancelled".equals(status)) {
            return handleCancelledEvent(event, userId, calendarOwnerEmail);
        } else if (event.getRecurrence() != null && !event.getRecurrence().isEmpty()) {
            // The incremental collection contains series masters. CA needs actual occurrences.
            // Bound expansion to the normal 30-day scan horizon so an endless RRULE stays finite.
            java.time.Instant from = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            String page = null;
            boolean delivered = true;
            try {
                do {
                    Events instances = calendarService.events().instances(PRIMARY_CALENDAR, event.getId())
                            .setTimeMin(new com.google.api.client.util.DateTime(from.toEpochMilli()))
                            .setTimeMax(new com.google.api.client.util.DateTime(from.plus(30, java.time.temporal.ChronoUnit.DAYS).toEpochMilli()))
                            .setShowDeleted(true).setPageToken(page).execute();
                    if (instances.getItems() != null) {
                        for (Event instance : instances.getItems()) {
                            delivered &= processChangedEvent(calendarService, instance, userId,
                                    calendarOwnerEmail, configuredEmail);
                        }
                    }
                    page = instances.getNextPageToken();
                } while (page != null);
                return delivered;
            } catch (IOException e) {
                LOGGER.warn("Could not expand recurring event {}: {}", event.getId(), e.getMessage());
                return false;
            }
        } else {
            // "confirmed" or "tentative" — treat as create/update
            return handleCreatedOrUpdatedEvent(event, userId, calendarOwnerEmail, configuredEmail);
        }
    }

    /**
     * Handles a created or updated event — sends it to MA service.
     *
     * @param calendarOwnerEmail the registered Google account email; injected into
     *                           the attendees list by the MA sender when absent
     */
    private boolean handleCreatedOrUpdatedEvent(Event event, String userId,
                                              String calendarOwnerEmail, String configuredEmail) {
        try {
            // Check if this event has a meeting URL — only sync events with meeting links
            String meetingUrl = eventMapper.extractMeetingUrl(event);
            boolean hasMeetingLink = meetingUrl != null && !meetingUrl.isBlank();
            LOGGER.info("[GCAL-LINK] webhook: id={} hasMeetingLink={} platform={}",
                    event.getId(), hasMeetingLink,
                    hasMeetingLink ? eventMapper.detectPlatformFromUrl(meetingUrl) : "none");
            if (!eventMapper.isEligibleCalendarEvent(event)) {
                LOGGER.info("[GCAL-SKIP] webhook: id={} reason=PERSONAL_CALENDAR_BLOCK action=NOT_SENT_TO_MA", event.getId());
                return true;
            }

            LOGGER.info("Sending created/updated event to MA: subject='{}', id={}",
                    safe(event.getSummary()), event.getId());
            return maService.sendEventToMA(event, userId, calendarOwnerEmail, configuredEmail);

        } catch (Exception e) {
            LOGGER.error("Error handling created/updated event {}: {}", event.getId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Handles a cancelled (deleted) event — deletes it from Meeting Agent.
     *
     * <p>EF-34: this path now uses {@link MeetingKeyResolver}, which was injected into this class and
     * never called. An unused collaborator is normally just clutter; here it was a behaviour gap with
     * a fix sitting next to it. A cancellation whose key is in neither local store was simply
     * <b>abandoned</b> — logged and returned — so a meeting that reached Meeting Agent through some
     * other path, or whose local mapping had been evicted, stayed on the dashboard forever. The
     * batch-sync path has asked Meeting Agent since WP-E16 (spec §6 R14); the webhook did not, and the
     * webhook is the path most cancellations arrive on.
     *
     * <p>Ordering is unchanged and deliberate: the two LOCAL stores first, because a key we recorded
     * ourselves is stronger evidence than a lookup, and only then the remote resolve.
     *
     * @param event              the cancelled event
     * @param userId             the scan-set user id — keys the LOCAL stores, which are keyed by it
     * @param calendarOwnerEmail EF-12: the mailbox owner's real address, which is what scopes the
     *                           Meeting Agent lookup. Never {@code userId}: Meeting Agent applies
     *                           {@code userEmail} as a hard filter and a {@code wsContact_} client key
     *                           matches no attendee, so passing it would make the lookup find nothing
     */
    private boolean handleCancelledEvent(Event event, String userId, String calendarOwnerEmail) {
        String eventId = event.getId();
        LOGGER.info("Processing cancelled event: id={}", eventId);

        try {
            // An entire-series deletion arrives as a sparse master tombstone. Match only the
            // parent IDs saved from Google; never use a title or take the first iCalUID match.
            boolean foundSeries = false;
            boolean seriesDeleted = true;
            if (event.getRecurringEventId() == null) {
                for (var entry : trackMeetingEventStore.getTrackedEventsMeta(userId).entrySet()) {
                    var meta = entry.getValue();
                    if (!meta.has("recurringSeriesId") || meta.get("recurringSeriesId").isJsonNull()
                            || !eventId.equals(meta.get("recurringSeriesId").getAsString())) continue;
                    foundSeries = true;
                    String key = trackMeetingEventStore.getMeetingKey(userId, entry.getKey());
                    if (key == null || !maService.deleteEventFromMA(key)) {
                        seriesDeleted = false;
                    } else {
                        eventMappingStore.remove(entry.getKey());
                        trackMeetingEventStore.removeTrackedEvent(userId, entry.getKey());
                    }
                }
            }
            if (foundSeries) return seriesDeleted;

            // 1. Try CalendarEventMappingStore (webhook-specific mapping)
            String meetingKey = eventMappingStore.get(eventId);

            // 2. Fallback to TrackMeetingEventStore (from batch sync)
            if (meetingKey == null) {
                meetingKey = trackMeetingEventStore.getMeetingKey(userId, eventId);
                if (meetingKey != null) {
                    LOGGER.info("Found meetingKey in TrackMeetingEventStore fallback: {}", meetingKey);
                }
            }

            // 3. EF-34: ask Meeting Agent. iCalUId is the strongest criterion and is the reason this
            // resolves at all — the HTTP route this replaced had no parameter for it.
            if (meetingKey == null) {
                String meetingUrl = eventMapper.extractMeetingUrl(event);
                String startTime = GoogleEventIdentity.originalStart(event);
                meetingKey = meetingKeyResolver.resolveMeetingKey(
                        GoogleEventIdentity.meetingUid(event), meetingUrl, startTime, calendarOwnerEmail);
                if (meetingKey != null) {
                    LOGGER.info("Resolved meetingKey={} from Meeting Agent for cancelled event {}",
                            meetingKey, eventId);
                }
            }

            if (meetingKey == null) {
                LOGGER.warn("No meetingKey found for cancelled event {} — not in either local store"
                        + " and Meeting Agent matched nothing. Event may never have been synced.", eventId);
                return true;
            }

            LOGGER.info("Deleting from MA: eventId={} -> meetingKey={}", eventId, meetingKey);
            if (!maService.deleteEventFromMA(meetingKey)) return false;

            // Clean up mappings
            eventMappingStore.remove(eventId);
            trackMeetingEventStore.removeTrackedEvent(userId, eventId);
            LOGGER.info("Successfully deleted meeting and cleaned up mapping for eventId={}", eventId);
            return true;

        } catch (Exception e) {
            LOGGER.error("Error handling cancelled event {}: {}", eventId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Builds the Calendar client that reads the incremental sync for a notification.
     *
     * <p>KIM-474: the credential is chosen by {@link CalendarDiscoveryService#sourceFor(String)} —
     * the user's own token with Always On off, domain-wide delegation for everybody with it on.
     * A delegated channel's owner has no stored refresh token — they never authorised anything —
     * so without this every notification from a delegated channel would fail with a re-authorise
     * prompt for a user who has nothing to re-authorise.
     */
    private Calendar getCalendarClient(String userId) {
        try {
            CalendarDiscoveryService.DiscoverySource source = discoveryService.sourceFor(userId);
            if (source == CalendarDiscoveryService.DiscoverySource.CONSENTED_USERS) {
                // Either/or: no personal token is used while Always On is on (owner ruling,
                // 2026-11-10), even if a caller resolved the source a moment before the flag moved.
                if (discoveryService.isAlwaysOn()) {
                    throw CalendarDiscoveryService.personalTokenRefused(userId);
                }
                GoogleProvider provider = googleProviderFactory.create();
                // Per-user token so the incremental sync reads THIS user's calendar.
                return provider.getGoogleClientForUser(userId);
            }
            return discoveryService.calendarClientFor(userId, source);
        } catch (MustAuthorizeException mustAuthorize) {
            // Propagate the platform's reauthorization signal unchanged, and unlogged — the caller
            // logs every failure it gets, and wrapping this one hid its type.
            throw mustAuthorize;
        } catch (CalendarDiscoveryService.PersonalTokenRefusedException refused) {
            throw refused;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Google Calendar client for user " + userId, e);
        }
    }

    private String safe(String value) {
        return value != null ? value : "<null>";
    }

    // ---- Diagnostic helper (used by [GCAL-RECV] logging) ----

    private static int attendeeCount(Event e) {
        return e.getAttendees() != null ? e.getAttendees().size() : 0;
    }
}
