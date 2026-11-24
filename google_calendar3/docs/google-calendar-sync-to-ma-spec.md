# Google Calendar V3 — Calendar Sync to MA

## Specification Document

**Date:** 2026-03-24
**Status:** Draft
**Requirement:** Build Google Workplace extension to sync calendars to MA, similar to Krista In Post Meetings (KIP) extension. ONLY the sync portion — NOT the subscription to meetings for meeting events.

---

## 1. Executive Summary

This document outlines the work required to add **calendar sync-to-MA** capability to the existing Google Calendar V3 extension (`google_calendar3`), modeled after the equivalent sync functionality in the Teams Transcribe (`teams-transcribe`) KIP extension.

**Scope:** Sync Google Calendar events to the MA (Meeting Agent) service via its REST API (`/api/v1/meetings/batch`).

**Out of Scope:** Meeting subscriptions, meeting event lifecycle (join/leave), transcripts, recordings, bot commands, chat integration.

---

## 2. Reference: How Teams Transcribe Does Calendar Sync

The teams-transcribe KIP extension syncs Microsoft 365 calendar events to MA using this flow:

```
┌──────────────────────┐     ┌──────────────────────┐     ┌────────────┐
│  Catalog Request      │────▶│  SendEventsToCAService│────▶│  MA Service│
│  (AdaptiveCalendarSync│     │  (core sync engine)   │     │  REST API  │
│   or CalendarSyncBy   │     │                       │     │            │
│   User)               │     │  1. Get auth users    │     │  POST      │
│                       │     │  2. Fetch events      │     │  /api/v1/  │
│                       │     │  3. Map to DTO        │     │  meetings/ │
│                       │     │  4. Batch POST to MA  │     │  batch     │
│                       │     │  5. Track sent events │     │            │
│                       │     │  6. Handle deletions  │     │  DELETE    │
│                       │     │                       │     │  /api/v1/  │
└──────────────────────┘     └──────────────────────┘     │  meetings/ │
                                                           │  {key}     │
                                                           └────────────┘
```

### Key Components in Teams Transcribe (sync-related only)

| Component | Role |
|---|---|
| `AdaptiveCalendarSyncHandler` | Catalog request handler — triggers full sync for all authenticated users |
| `CalendarSyncByUserHandler` | Catalog request handler — triggers sync for a single user |
| `SendEventsToCAService` | Core sync engine — fetches events, maps to DTO, batch-sends to MA, tracks state |
| `EventFetcher` | Fetches calendar events from MS Graph API within a date window |
| `CreateMeetingRequestDto` | DTO sent to MA's `/api/v1/meetings/batch` endpoint |
| `Person` | Model for organizer/attendee (name + email) |
| `TrackMeetingEventStore` | Tracks which events have been synced (by iCalUId), stores meetingKey for deletions |
| `MeetingKeyResolver` | Resolves meetingKey from MA by URL + start time |
| `AuthenticationService` | Provides list of authenticated users |
| `CalendarSyncTelemetryService` | Telemetry tracking for sync operations |

### CreateMeetingRequestDto (the payload sent to MA)

```java
{
    String title;          // Event subject/title
    String startTime;      // UTC ISO 8601 (yyyy-MM-dd'T'HH:mm:ss)
    String endTime;        // UTC ISO 8601
    String timezone;       // IANA timezone ID
    String meetingUrl;     // Online meeting join URL
    Person organizer;      // {email, name}
    List<Person> attendees;// [{email, name}, ...]
    String calendarSystem; // Platform identifier (e.g., "google", "outlook", "zoom")
    boolean recurring;     // Is this a recurring event occurrence?
    String recurringSeriesId; // Series identifier for recurring events
    String body;           // Event body/description preview
    String iCalUId;        // Stable cross-platform calendar identifier
}
```

### Sync Algorithm (from SendEventsToCAService.processUser)

1. **Fetch events** — Get all events within `[today, today + N days]` from calendar API
2. **Load tracked state** — Get previously synced event IDs and metadata from `TrackMeetingEventStore`
3. **Detect deletions** — Compare tracked iCalUIds vs. current iCalUIds; events missing from current fetch (within the window) are deleted from MA
4. **Process current events** — For each event:
   - Skip events without a meeting URL
   - Handle cancelled events → delete from MA + remove from tracking store
   - Map active events to `CreateMeetingRequestDto`
5. **Batch send** — Send events in batches of 25 to MA's `/api/v1/meetings/batch`
6. **Track metadata** — Store meetingKey + startTime + iCalUId for each successfully synced event

### Concurrency Model

- Full sync processes **multiple users in parallel** using Virtual Threads with a `Semaphore` to limit concurrency
- ThreadLocal context is captured and propagated to virtual threads
- Single-user sync runs sequentially

---

## 3. What Google Calendar V3 Already Has

| Capability | Current State | Reusable? |
|---|---|---|
| Google Calendar API client | `GoogleCalendarRepositoryImpl` with full CRUD | ✅ Yes — `listEvents()` method exists |
| OAuth authentication | `GoogleCalendarAuthenticator` + `GoogleAttributeStore` | ✅ Yes — but single-user only currently |
| Event fetching | `CalendarRepository.listEvents()` with time range, pagination | ✅ Yes — needs minor adaptation |
| Event mapping | `EventDetailsMapper` (Google Event → EventDetails) | ⚠️ Partially — need new mapper to `CreateMeetingRequestDto` |
| Multi-user auth | Not implemented — current auth is per-request | ❌ Needs new implementation |
| Event tracking store | Does not exist | ❌ Needs new implementation |
| MA REST client | Does not exist | ❌ Needs new implementation |
| Sync catalog requests | Do not exist | ❌ Needs new implementation |
| Telemetry | `TelemetryHelper` exists for existing operations | ✅ Partially reusable |

---

## 4. Implementation Plan

### Phase 1: Infrastructure & Models

#### 4.1.1 New Extension Attribute: `serviceUrl`

Add a `serviceUrl` attribute to `GoogleCalendarExtension` for the MA service endpoint.

**Reference:** Teams Transcribe uses `SERVICE_URL` from `MicrosoftTeamsCalendarAttributes` (stored in invoker attributes).

**Changes:**
- Add `@Field.Text(value = "serviceUrl")` to `GoogleCalendarExtension`
- Add `getServiceUrl()` to `GoogleAttributes` (or a new config class)

#### 4.1.2 New Models (package: `impl.model`)

Create the following models (equivalent to teams-transcribe):

| Class | Fields | Purpose |
|---|---|---|
| `CreateMeetingRequestDto` | title, startTime, endTime, timezone, meetingUrl, organizer, attendees, calendarSystem, recurring, recurringSeriesId, body, iCalUId | Payload for MA batch endpoint |
| `Person` | email, name | Organizer/attendee model |
| `CAMeetingResponse` | meetingKey, iCalUId, status | Response from MA after sync |

These are **identical DTOs** to what teams-transcribe sends, ensuring MA compatibility.

#### 4.1.3 New Store: `TrackMeetingEventStore`

A KeyValueStore-backed tracking store for synced events.

**Purpose:** Track which Google Calendar events have been synced to MA, storing:
- Per-user index of tracked event IDs (by iCalUId)
- Per-event metadata: meetingKey (from MA), startTime, iCalUId
- Last full sync date per user

**Reference:** Directly modeled after `app.krista.global.impl.stores.TrackMeetingEventStore` in teams-transcribe.

**Key methods:**
```java
void trackEvent(String userId, String eventId, String meetingKey, String startTime, String iCalUId)
void removeTrackedEvent(String userId, String eventId)
Set<String> getTrackedEventIds(String userId)
Map<String, JsonObject> getTrackedEventsMeta(String userId)
String getMeetingKey(String userId, String eventId)
```

---

### Phase 2: Core Sync Engine

#### 4.2.1 New Service: `GoogleCalendarEventFetcher`

Fetches Google Calendar events within a configurable date window.

**Responsibilities:**
- Use existing `CalendarRepository.listEvents()` to fetch events
- Handle pagination (Google Calendar uses `nextPageToken`)
- Return raw `com.google.api.services.calendar.model.Event` list
- Filter: include all events (including cancelled) within the window

**Reference:** Modeled after `EventFetcher` in teams-transcribe, adapted for Google Calendar API.

**Key difference from existing code:** The existing `CalendarEventService.getNextUpcomingMeetings()` already fetches events within a time window but returns `EventDetails`. The new fetcher operates at the raw Google `Event` level and does not filter by meeting URL (sync sends all events, not just online meetings).

#### 4.2.2 New Service: `MeetingKeyResolver`

Resolves the MA-assigned `meetingKey` for an event by querying MA's API.

**Purpose:** When a tracked event disappears from the calendar (deletion), we need the meetingKey to tell MA to delete it.

**Key method:**
```java
String getMeetingKeyByUrlAndStartTime(String meetingUrl, String startTime)
```

#### 4.2.3 New Mapper: `GoogleEventToMeetingDtoMapper`

Maps a Google Calendar `Event` to `CreateMeetingRequestDto`.

**Mapping logic:**

| CreateMeetingRequestDto field | Google Calendar Event source |
|---|---|
| `title` | `event.getSummary()` |
| `startTime` | `event.getStart().getDateTime()` → convert to UTC ISO 8601 |
| `endTime` | `event.getEnd().getDateTime()` → convert to UTC ISO 8601 |
| `timezone` | `event.getStart().getTimeZone()` or fallback to UTC |
| `meetingUrl` | `event.getHangoutLink()` or `event.getConferenceData().getEntryPoints()` or location URL |
| `organizer` | `event.getOrganizer().getEmail()` + `getDisplayName()` |
| `attendees` | `event.getAttendees()` → map each to `Person` |
| `calendarSystem` | Detect from meetingUrl: "google" for Meet, "zoom" for Zoom, etc. |
| `recurring` | `event.getRecurringEventId() != null` |
| `recurringSeriesId` | `event.getRecurringEventId()` |
| `body` | `event.getDescription()` (truncated to preview) |
| `iCalUId` | `event.getICalUID()` |

**Key differences from teams-transcribe mapper:**
- Google provides `iCalUID` directly (no need for AdditionalDataManager)
- Google provides `hangoutLink` for Google Meet URLs
- Google provides `conferenceData` for other conferencing providers (Zoom, etc.)
- Timezone handling is simpler — Google uses IANA timezone IDs (no Microsoft-specific conversion needed)

#### 4.2.4 New Service: `SendEventsToMAService` (Core Sync Engine)

The central sync engine, modeled after `SendEventsToCAService` in teams-transcribe.

**Responsibilities:**
1. Get list of authenticated users
2. For each user, fetch events within `[today, today + N days]`
3. Load tracked event state from `TrackMeetingEventStore`
4. Detect deletions (tracked events missing from current fetch window)
5. Map current events to `CreateMeetingRequestDto`
6. Batch POST to MA's `/api/v1/meetings/batch` (batch size: 25)
7. Handle cancelled events — DELETE from MA
8. Update tracking store with meetingKey + metadata

**Dependencies:**
- `AuthenticationService` (new — see 4.2.5)
- `GoogleCalendarEventFetcher`
- `GoogleEventToMeetingDtoMapper`
- `TrackMeetingEventStore`
- `MeetingKeyResolver`
- `CalendarSyncTelemetryService`
- `Invoker` (for serviceUrl)

**Concurrency:** Use Virtual Threads with Semaphore for multi-user sync (same pattern as teams-transcribe).

#### 4.2.5 New Service: `GoogleCalendarAuthenticationService`

Manages the list of authenticated Google Calendar users.

**Purpose:** The current Google Calendar V3 extension handles auth on a per-request basis. For sync, we need to know which users are authenticated so we can fetch their calendars.

**Key methods:**
```java
List<String> getAuthenticatedUsers()
```

**Implementation:** Back this with a KeyValueStore that records user IDs upon successful OAuth callback. The existing `GoogleCalendarAuthenticator` OAuth callback flow should be extended to register the user.

#### 4.2.6 New Service: `CalendarSyncTelemetryService`

Telemetry tracking for sync operations.

**Metrics:**
- `google_calendar.sync.requests` — Total sync requests
- `google_calendar.sync.success` / `failure` — Sync outcomes
- `google_calendar.sync.duration` — Sync duration (ms)
- `google_calendar.events.processed` — Events processed counter
- `google_calendar.api.calls` — API call counter (by target: google/ma)

**Reference:** Directly modeled after `CalendarSyncTelemetryService` in teams-transcribe, with `google_calendar` metric prefix instead of `office365`.

---

### Phase 3: Catalog Requests & Lifecycle

#### 4.3.1 New Catalog Request: "Adaptive Calendar Sync"

Syncs all authenticated users' calendars to MA within a configurable day window.

**Location:** New methods in `MeetingsArea` (or a new `CalendarSyncArea` class).

```java
@CatalogRequest(
    name = "Adaptive Calendar Sync",
    description = "Dynamically syncs Google Calendar data to MA based on a user-defined time window. Enter the number of days, and the system will fetch and sync events within that range.",
    area = "Meetings",
    type = CatalogRequest.Type.QUERY_SYSTEM)
@Field.Text(name = "Status", required = false)
public ExtensionResponse adaptiveCalendarSync(
    @Field(name = "Number of Days", type = "Number", required = false) Double numberOfDays)
```

**Default:** 30 days if not specified.

#### 4.3.2 New Catalog Request: "Calendar Sync by User"

Syncs a single user's calendar to MA.

```java
@CatalogRequest(
    name = "Calendar Sync by User",
    description = "Fetch and sync Google Calendar events for a specific user by entering their user email and the number of days to sync.",
    area = "Meetings",
    type = CatalogRequest.Type.CHANGE_SYSTEM)
@Field.Text(name = "Status", required = false)
public ExtensionResponse calendarSyncByUser(
    @Field(name = "User Email", type = "Email", required = true) String userEmail,
    @Field(name = "Number of Days", type = "Number", required = true) Double numberOfDays)
```

#### 4.3.3 New Catalog Request: "Save Configuration" (Optional)

Validates and saves extension attributes (serviceUrl, clientId, clientSecret, email).

```java
@CatalogRequest(
    name = "Save Configuration Google Calendar Sync",
    description = "Validate the provided attributes and save changes after successful validation.",
    area = "Meetings",
    type = CatalogRequest.Type.CHANGE_SYSTEM)
@Field.Text(name = "Response", required = false)
public ExtensionResponse saveConfiguration(
    @Field.Desc(name = "Attributes", type = "{ clientId: Text, clientSecret: Text(secured), email: Email, serviceUrl: Text }", required = true) Map<String, Object> attributes)
```

#### 4.3.4 Lifecycle Hooks

Add `@InvokerRequest(Type.INVOKER_LOADED)` and `@InvokerRequest(Type.INVOKER_UNLOADED)` to `GoogleCalendarExtension` for initialization and cleanup when the extension loads in live mode.

**On load:** Log initialization, optionally trigger initial sync.
**On unload:** Clean up resources.

---

## 5. New Dependencies

```groovy
// No new external dependencies required for the sync feature.
// The existing Google Calendar API client handles all Google API calls.
// MA communication uses standard HTTP client (javax.ws.rs.client or OkHttp).
// Jackson/Gson (already present) handles JSON serialization.
```

If using the same HTTP client pattern as teams-transcribe (JAX-RS client):
```groovy
// Already present via jersey-media-multipart → transitively provides javax.ws.rs.client
```

---

## 6. Files to Create (New)

| # | File | Purpose |
|---|---|---|
| 1 | `impl/model/CreateMeetingRequestDto.java` | DTO for MA batch endpoint |
| 2 | `impl/model/Person.java` | Organizer/attendee model |
| 3 | `impl/model/CAMeetingResponse.java` | Response model from MA |
| 4 | `impl/stores/TrackMeetingEventStore.java` | Tracks synced events per user |
| 5 | `service/sync/GoogleCalendarEventFetcher.java` | Fetches events from Google Calendar API |
| 6 | `service/sync/GoogleEventToMeetingDtoMapper.java` | Maps Google Event → CreateMeetingRequestDto |
| 7 | `service/sync/SendEventsToMAService.java` | Core sync engine |
| 8 | `service/sync/MeetingKeyResolver.java` | Resolves meetingKey from MA |
| 9 | `service/sync/GoogleCalendarAuthenticationService.java` | Manages authenticated user list |
| 10 | `service/sync/CalendarSyncTelemetryService.java` | Telemetry for sync ops |
| 11 | `catalog/handler/AdaptiveCalendarSyncHandler.java` | Handler for adaptive sync catalog request |
| 12 | `catalog/handler/CalendarSyncByUserHandler.java` | Handler for per-user sync catalog request |

All files are under: `src/main/java/app/kristasoft/extensions/essentials/collaboration/google_calendar3/`

---

## 7. Files to Modify (Existing)

| # | File | Change |
|---|---|---|
| 1 | `GoogleCalendarExtension.java` | Add `serviceUrl` attribute, lifecycle hooks (`INVOKER_LOADED`, `INVOKER_UNLOADED`) |
| 2 | `GoogleAttributes.java` | Add `serviceUrl` field and getter |
| 3 | `GoogleCalendarAuthenticator.java` | Register user in `GoogleCalendarAuthenticationService` on successful OAuth callback |
| 4 | `catalog/MeetingsArea.java` | Add new catalog request methods for sync operations |
| 5 | `build.gradle` | No new dependencies expected; verify Jackson/Gson availability |

**No existing interfaces, contracts, or catalog requests are modified.** All changes are additive.

---

## 8. Testing Plan

| # | Test Class | Scope |
|---|---|---|
| 1 | `GoogleEventToMeetingDtoMapperTest` | Mapping Google Event → DTO (all field combinations, nulls, timezones) |
| 2 | `SendEventsToMAServiceTest` | Sync engine logic (create, update, delete flows, batch splitting) |
| 3 | `TrackMeetingEventStoreTest` | Store operations (track, remove, index, metadata, tombstones) |
| 4 | `GoogleCalendarEventFetcherTest` | Event fetching with pagination, time windows, filtering |
| 5 | `CalendarSyncTelemetryServiceTest` | Telemetry metric recording |
| 6 | `AdaptiveCalendarSyncHandlerTest` | Catalog request handler delegation |
| 7 | `CalendarSyncByUserHandlerTest` | Catalog request handler delegation |
| 8 | `MeetingKeyResolverTest` | Meeting key resolution from MA |

---

## 9. Key Design Decisions

### 9.1 No ESB Required

The teams-transcribe extension uses ESB (Apache Artemis) for async communication with the CA Service for meeting lifecycle events (transcripts, recording status, etc.). **Calendar sync does not use ESB** — it communicates with MA via direct REST API calls (`/api/v1/meetings/batch`). Since we're only implementing sync (not subscription/meeting events), ESB infrastructure is not needed.

### 9.2 Meeting URL Extraction (Google-specific)

Google Calendar provides meeting URLs through multiple paths:
1. `event.getHangoutLink()` — Google Meet link (deprecated but still populated)
2. `event.getConferenceData().getEntryPoints()` — Structured conference data (Google Meet, Zoom, etc.)
3. `event.getLocation()` — Location field may contain a URL

The mapper should check all three in priority order.

### 9.3 iCalUID Availability

Google Calendar provides `iCalUID` directly on every event (`event.getICalUID()`). This is the stable cross-platform identifier used for deduplication and tracking. Teams Transcribe had to extract this from `AdditionalDataManager` — this is simpler with Google.

### 9.4 Timezone Handling

Google Calendar uses standard IANA timezone IDs (e.g., `America/New_York`). No Microsoft-specific timezone conversion is needed. The mapper converts event times to UTC ISO 8601 format for MA.

### 9.5 calendarSystem Value

The `calendarSystem` field in the DTO tells MA which platform the meeting is on. Detection logic:
- `meet.google.com` → `"google"`
- `zoom.us` / `zoom.com` → `"zoom"`
- `teams.microsoft.com` → `"outlook"`
- `webex.com` → `"WEBEX"`
- Other/none → `"google"` (default, since events originate from Google Calendar)

### 9.6 Backward Compatibility

- **All 11 existing catalog requests** remain unchanged
- **`CalendarService` interface** is NOT modified
- **`CalendarRepository` interface** is NOT modified
- **`EventDetails` entity** is NOT modified
- New sync functionality is added as a **parallel feature path** with its own service classes

---

## 10. Comparison: Teams Transcribe vs. Google Calendar V3 Sync

| Aspect | Teams Transcribe (KIP) | Google Calendar V3 (Proposed) |
|---|---|---|
| Calendar API | MS Graph | Google Calendar API v3 |
| Auth model | Per-user OAuth + admin service account | Per-user OAuth (public/private) |
| Event fetch | `calendarView` with pagination | `events().list()` with pagination |
| iCalUID source | `AdditionalDataManager.get("uid")` | `event.getICalUID()` (direct) |
| Meeting URL source | `onlineMeeting.joinUrl` + location fallback | `hangoutLink` + `conferenceData` + location fallback |
| Timezone handling | Microsoft-specific → IANA conversion | Already IANA (no conversion) |
| MA endpoint | `POST /api/v1/meetings/batch` | `POST /api/v1/meetings/batch` (same) |
| Batch size | 25 | 25 (same) |
| Deletion | `DELETE /api/v1/meetings/{key}` | `DELETE /api/v1/meetings/{key}` (same) |
| Tracking store | `TrackMeetingEventStore` (KeyValueStore) | `TrackMeetingEventStore` (KeyValueStore, same pattern) |
| Concurrency | Virtual Threads + Semaphore | Virtual Threads + Semaphore (same pattern) |
| ESB | Used for meeting lifecycle (NOT for sync) | Not needed |

---

## 11. Estimated Effort

| Phase | Components | Estimate |
|---|---|---|
| Phase 1: Infrastructure & Models | DTOs, Person, Store, config changes | 2-3 days |
| Phase 2: Core Sync Engine | Fetcher, Mapper, SendEventsToMAService, AuthService, Telemetry, MeetingKeyResolver | 4-5 days |
| Phase 3: Catalog Requests & Lifecycle | Handlers, MeetingsArea changes, lifecycle hooks | 1-2 days |
| Testing | Unit tests for all new components | 2-3 days |
| **Total** | | **9-13 days** |

---

## 12. Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Google Calendar API rate limits | Sync may be throttled for users with many events | Implement exponential backoff; limit concurrency via Semaphore |
| Multi-user auth registration | Current auth flow doesn't track authenticated users | Extend OAuth callback to register users (low-risk, additive change) |
| MA API compatibility | DTO format must match exactly | Use identical `CreateMeetingRequestDto` structure as teams-transcribe |
| Recurring event handling | Google represents recurring events differently than MS Graph | Map `recurringEventId` to `recurringSeriesId`; use `iCalUID` for stable identity |
| Event pagination | Large calendars may have thousands of events | Use Google's `nextPageToken` pagination; process in batches |
