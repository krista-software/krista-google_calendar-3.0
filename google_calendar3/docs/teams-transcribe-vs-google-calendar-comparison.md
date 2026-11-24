# Teams-Transcribe vs Google Calendar V3 — Architectural Comparison

> **Last Updated:** March 2025  
> **Purpose:** Living reference document for tracking differences and alignment roadmap between the two calendar extension implementations.  
> **Teams-Transcribe repo:** `KristaGlobalCatalog2/teams-transcribe`  
> **Google Calendar V3 repo:** `KristaGlobalCatalog/google_calendar3`

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Architecture Overview](#2-architecture-overview)
3. [Feature Comparison Matrix](#3-feature-comparison-matrix)
4. [Detailed Comparison by Area](#4-detailed-comparison-by-area)
   - 4.1 [Extension Entry Point](#41-extension-entry-point)
   - 4.2 [Authentication](#42-authentication)
   - 4.3 [Calendar Sync to MA/CA Service](#43-calendar-sync-to-maca-service)
   - 4.4 [ESB / Event-Driven Architecture](#44-esb--event-driven-architecture)
   - 4.5 [Meeting Scheduling (ESB Commands)](#45-meeting-scheduling-esb-commands)
   - 4.6 [Transcript Handling](#46-transcript-handling)
   - 4.7 [Meeting Status & Cancellation](#47-meeting-status--cancellation)
   - 4.8 [Event Subscription & Webhooks](#48-event-subscription--webhooks)
   - 4.9 [Meeting Recording](#49-meeting-recording)
   - 4.10 [Telemetry](#410-telemetry)
   - 4.11 [Shared Models](#411-shared-models)
   - 4.12 [Catalog / Sub-Catalog Handlers](#412-catalog--sub-catalog-handlers)
5. [Code Structure Comparison](#5-code-structure-comparison)
6. [What Is Aligned Today](#6-what-is-aligned-today)
7. [Key Differences Summary](#7-key-differences-summary)
8. [Alignment Roadmap](#8-alignment-roadmap)
   - 8.1 [Align Now (High Priority)](#81-align-now-high-priority)
   - 8.2 [Align Next (Medium Priority)](#82-align-next-medium-priority)
   - 8.3 [Future / Low Priority](#83-future--low-priority)
9. [Risk Areas](#9-risk-areas)

---

## 1. Executive Summary

Both extensions are **Krista platform extensions** that integrate with external calendar systems and sync meeting data to a central service (MA for Google Calendar, CA for Teams). They share a remarkably similar high-level pattern:

1. Authenticate users via OAuth
2. Fetch calendar events from external API
3. Map events to a common DTO (`CreateMeetingRequestDto`)
4. Batch-send to a central meetings API
5. Track synced events for change/deletion detection

However, **Teams-Transcribe is significantly more feature-rich** — it has a full ESB (Enterprise Service Bus) integration layer, meeting subscription/scheduling via commands, transcript retrieval, meeting recording control, and real-time webhook-based event subscriptions. Google Calendar V3 is currently a **sync-only** extension with direct catalog operations.

---

## 2. Architecture Overview

### Google Calendar V3

```
┌──────────────────────────────────────────────────┐
│           GoogleCalendarExtension                │
│  (Entry Point, OAuth, Custom Tabs)               │
├──────────────────────────────────────────────────┤
│                                                  │
│  ┌─────────────────┐   ┌──────────────────────┐  │
│  │  MeetingsArea    │   │ AdaptiveCalendarSync │  │
│  │  (Catalog)       │   │ Handler              │  │
│  └──────┬──────────┘   └──────────┬───────────┘  │
│         │                         │               │
│  ┌──────▼──────────┐   ┌──────────▼───────────┐  │
│  │ CalendarService  │   │ SendEventsToMA       │  │
│  │ (CRUD ops)       │   │ Service              │  │
│  └──────┬──────────┘   └──────────┬───────────┘  │
│         │                         │               │
│  ┌──────▼──────────┐   ┌──────────▼───────────┐  │
│  │ GoogleCalendar   │   │ GoogleCalendarEvent  │  │
│  │ RepositoryImpl   │   │ Fetcher + Mapper     │  │
│  └──────┬──────────┘   └──────────┬───────────┘  │
│         │                         │               │
│         ▼                         ▼               │
│   Google Calendar API      MA REST API            │
│                            (/api/v1/meetings)     │
└──────────────────────────────────────────────────┘
```

### Teams-Transcribe

```
┌──────────────────────────────────────────────────────────────┐
│         MicrosoftTeamsCalendarExtension                       │
│  (Entry Point, OAuth, Custom Tabs, ESB Init)                 │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────┐  ┌──────────────────┐  ┌────────────┐  │
│  │ TeamsPostMeeting │  │ AdaptiveCalendar │  │ KipService │  │
│  │ Area (Catalog)   │  │ SyncHandler      │  │ BusManager │  │
│  └─────────────────┘  └────────┬─────────┘  └─────┬──────┘  │
│                                │                    │         │
│  ┌─────────────────────────────▼─────┐  ┌──────────▼──────┐  │
│  │  SendEventsToCAService            │  │ ESB Consumers   │  │
│  │  (Calendar sync to CA)            │  │ (5 queues)      │  │
│  └───────────────────┬───────────────┘  └──────────┬──────┘  │
│                      │                             │         │
│  ┌───────────────────▼───────────────┐  ┌──────────▼──────┐  │
│  │  EventFetcher                     │  │ KipExtension    │  │
│  │  (Graph API fetch + map)          │  │ EsbAdapter      │  │
│  └───────────────────┬───────────────┘  └──────────┬──────┘  │
│                      │                             │         │
│  ┌───────────────────▼───┐  ┌──────────────────────▼──────┐  │
│  │ Microsoft Graph API   │  │ EventSubscriptionFacade     │  │
│  │                       │  │ MeetingProcessor            │  │
│  │                       │  │ MeetingRecordService        │  │
│  │                       │  │ TranscriptService           │  │
│  │                       │  │ CalendarSubscriptionService │  │
│  └───────────────────────┘  └─────────────────────────────┘  │
│                                                              │
│         ▼                              ▼                     │
│   CA REST API                    Artemis ESB                 │
│   (/api/v1/meetings)             (5 inbound + 4 outbound)   │
└──────────────────────────────────────────────────────────────┘
```

---

## 3. Feature Comparison Matrix

| Feature / Capability                  | Google Calendar V3       | Teams-Transcribe         | Alignment Status     |
|---------------------------------------|--------------------------|--------------------------|----------------------|
| **OAuth Authentication**              | ✅ Google OAuth2 (ScribeJava) | ✅ Microsoft OAuth2 (ScribeJava) | ✅ Aligned pattern |
| **Calendar Sync to Central Service**  | ✅ MA (`/api/v1/meetings/batch`) | ✅ CA (`/api/v1/meetings/batch`) | ✅ Aligned pattern |
| **Per-User Sync**                     | ✅ `CalendarSyncByUserHandler` | ✅ `CalendarSyncByUserHandler`  | ✅ Aligned |
| **Adaptive Multi-User Sync**          | ✅ `AdaptiveCalendarSyncHandler` | ✅ `AdaptiveCalendarSyncHandler` | ✅ Aligned |
| **Change/Deletion Detection**         | ✅ iCalUID-based tracking  | ✅ iCalUID-based tracking   | ✅ Aligned |
| **Batch Send (25 per batch)**         | ✅                         | ✅                          | ✅ Aligned |
| **Virtual Thread Parallelism**        | ✅                         | ✅                          | ✅ Aligned |
| **Telemetry Service**                 | ✅ `CalendarSyncTelemetryService` | ✅ `CalendarSyncTelemetryService` | ✅ Aligned |
| **TrackMeetingEventStore**            | ✅                         | ✅                          | ✅ Aligned |
| **MeetingKeyResolver**                | ✅                         | ✅                          | ✅ Aligned |
| **CreateMeetingRequestDto**           | ✅ (Gson `@SerializedName`) | ✅ (javax `@NotBlank` validation) | ⚠️ Partial |
| **ESB Integration**                   | ❌ None                    | ✅ Full (Artemis + krista-infra ESB) | ❌ Major gap |
| **Meeting Scheduling (ESB command)**  | ❌                         | ✅ `KipScheduleMeetingHandler` | ❌ Major gap |
| **Transcript Retrieval**              | ❌                         | ✅ `KipTranscriptRequestHandler` + `TranscriptService` | ❌ Major gap |
| **Meeting Cancellation (ESB)**        | ❌                         | ✅ `KipCancelHandler`       | ❌ Major gap |
| **Meeting Status Query (ESB)**        | ❌                         | ✅ `KipMeetingStatusQueryHandler` | ❌ Major gap |
| **Auth Initiate (ESB command)**       | ❌                         | ✅ `KipAuthInitiateHandler` | ❌ Major gap |
| **Webhook Event Subscriptions**       | ❌                         | ✅ `CalendarSubscriptionService` + Graph webhooks | ❌ Major gap |
| **Real-time Meeting Subscription**    | ❌                         | ✅ `EventSubscriptionFacade.subscribeToSingleMeeting()` | ❌ Major gap |
| **Meeting Recording Control**         | ❌                         | ✅ `MeetingProcessor` (auto-record enable) | ❌ N/A (platform) |
| **Chat Subscription & Messages**      | ❌                         | ✅ `ChatService`, `ChatSubscribeStore` | ❌ N/A (platform) |
| **AI Assistant Join Management**      | ❌                         | ✅ `AssistantLifecycleService`, `AssistantSubscriptionStateStore` | ❌ N/A (platform) |
| **Account Manager (multi-user)**      | ❌                         | ✅ `AccountManager` (workspace-auth keys) | ❌ Gap |
| **Consumer Thread Monitoring**        | ❌                         | ✅ `ConsumerThreadMonitor` with recovery | ❌ Gap |
| **HK2 Context Preservation**          | ❌                         | ✅ `ContextSnapshotHolder`, `ContextRestoringServiceDefinition` | ❌ Gap |
| **Calendar CRUD Operations**          | ✅ Full (accept/decline/tentative/add attendees/schedule/cancel/get) | ❌ No direct calendar CRUD | Reversed gap |
| **API Key Authentication**            | ❌ Not used                | ✅ `x-api-key` header on all CA calls | ⚠️ Difference |
| **Service URL Config**                | ✅ `serviceUrl` attribute   | ✅ `serviceUrl` attribute    | ✅ Aligned |

---

## 4. Detailed Comparison by Area

### 4.1 Extension Entry Point

| Aspect | Google Calendar V3 | Teams-Transcribe |
|--------|-------------------|-----------------|
| **Class** | `GoogleCalendarExtension` | `MicrosoftTeamsCalendarExtension` |
| **Annotations** | `@Extension`, `@Java(JAVA_21)` | `@Extension`, `@Java(JAVA_21)` |
| **`invokerLoaded()`** | Logs a message | Full initialization: auto-subscribe users, start ESB consumers via `ApplicationInitializer.init()` |
| **`invokerUnloaded()`** | Logs a message | Cleanup ESB consumers |
| **Custom Tabs** | Authentication + Documentation | Authentication + Documentation |

**Key Difference:** Teams-Transcribe's `invokerLoaded()` is the bootstrap point for ESB consumers and auto-subscription. Google Calendar V3 has no background processes to start.

### 4.2 Authentication

| Aspect | Google Calendar V3 | Teams-Transcribe |
|--------|-------------------|-----------------|
| **OAuth Provider** | Google (ScribeJava) | Microsoft Azure AD (ScribeJava) |
| **Authenticator** | `GoogleCalendarAuthenticator` | `MicrosoftTeamsCalendarRequestAuthenticator` |
| **Token Management** | `GoogleAttributeStore` with `OAuth20Service` | `TokenService` with `AccessTokenStore` + `RefreshTokenStore` |
| **Token Refresh** | Handled by Google client library | Manual refresh via OkHttp to Microsoft token endpoint |
| **Multi-User Auth** | `GoogleCalendarAuthenticationService` (KeyValueStore list) | `AuthenticationService` (supports both legacy global and workspace-auth keys) |
| **Auth Context** | `state = userId` or `userId#authContextId` | `state = invokerId#routingCallbackUrl` |
| **ESB Auth Initiate** | ❌ | ✅ `KipAuthInitiateHandler` handles remote auth requests |
| **Workspace-scoped Auth** | ❌ | ✅ `workspace_auth:{workspaceId}:{email}` key pattern |

**Key Difference:** Teams-Transcribe supports **workspace-scoped authentication** where an AI assistant account can be authenticated per workspace. Google Calendar V3 only supports simple user-level auth with a flat list in KeyValueStore.

### 4.3 Calendar Sync to MA/CA Service

This is the **most aligned area** between the two extensions.

| Aspect | Google Calendar V3 | Teams-Transcribe |
|--------|-------------------|-----------------|
| **Service Class** | `SendEventsToMAService` | `SendEventsToCAService` |
| **Central Service** | MA (Meeting Agent) | CA (Conversation Agent) |
| **Batch Endpoint** | `POST /api/v1/meetings/batch` | `POST /api/v1/meetings/batch` |
| **Batch Size** | 25 | 25 |
| **Delete Endpoint** | `DELETE /api/v1/meetings/{key}` | `DELETE /api/v1/meetings/{key}` |
| **API Key Header** | ❌ None | ✅ `x-api-key` on all requests |
| **Event Fetcher** | `GoogleCalendarEventFetcher.fetchEventsForDays()` | `EventFetcher.fetchEventsForOneMonth()` |
| **Event Mapper** | `GoogleEventToMeetingDtoMapper` | Inline mapping in `SendEventsToCAService` |
| **Concurrency** | Virtual threads, `Semaphore(5)` | Virtual threads, `Semaphore(10)` |
| **Tracked Store** | `TrackMeetingEventStore` | `TrackMeetingEventStore` |
| **Deletion Detection** | iCalUID comparison within fetch window | iCalUID comparison within fetch window |
| **Cancelled Event Handling** | ✅ Detects `"cancelled"` status → deletes from MA | ✅ Detects `"cancelled"` status → deletes from CA |
| **ThreadLocal Context** | Not needed (simpler stack) | ✅ Required (KSDK ThreadLocalProxy) |

**Sync Flow (Both):**
1. Get authenticated users list
2. For each user (parallel with virtual threads + semaphore):
   a. Create API client
   b. Fetch events for N days
   c. Load tracked event metadata
   d. Detect deletions (tracked iCalUIDs missing from current fetch)
   e. Delete from central service
   f. Map remaining events to `CreateMeetingRequestDto`
   g. Batch POST to central service
   h. Store tracking metadata with meetingKey + iCalUID + startTime

**Key Differences in Sync:**
- **Google Calendar V3** has a **dedicated mapper class** (`GoogleEventToMeetingDtoMapper`) with clean separation. Teams-Transcribe maps inline.
- **Teams-Transcribe** adds `x-api-key` header to all CA API calls. Google Calendar V3 does not.
- **Teams-Transcribe** requires `ThreadLocalProxy` context propagation for KSDK. Google Calendar V3 doesn't need this.
- **Teams-Transcribe** has `MAX_CONCURRENT_USERS = 10` vs Google Calendar V3's `MAX_CONCURRENT_USERS = 5`.

### 4.4 ESB / Event-Driven Architecture

| Aspect | Google Calendar V3 | Teams-Transcribe |
|--------|-------------------|-----------------|
| **ESB Framework** | ❌ None | ✅ `krista-infra` ESB (Apache Artemis) |
| **Service Bus Manager** | ❌ | ✅ `KipServiceBusManager` |
| **Consumer Count** | 0 | 5 inbound consumers |
| **Publisher Count** | 0 | 4 outbound event publishers |
| **Inbound Queues** | — | `ext.schedule.microsoft_teams`, `ext.cancel.microsoft_teams`, `ext.transcript.request.microsoft_teams`, `ext.meeting.status.microsoft_teams`, `ext.auth.microsoft_teams` |
| **Outbound Queues** | — | `events.scheduling.completed`, `events.cancel.completed`, `events.transcript.delivered`, `events.meeting.status.completed` |
| **ESB Config** | — | `KipExtensionConfig` with `TenantFilterMode.FIXED` per workspace |
| **ESB Properties** | — | Loaded from `esb_default.properties` + API discovery |
| **Recovery** | — | ✅ `ConsumerThreadMonitor` with auto-recovery |
| **Context Preservation** | — | ✅ `ContextSnapshotHolder` + `ContextRestoringServiceDefinition` |

**This is the single largest architectural gap.** Teams-Transcribe has a full event-driven architecture where CA Service sends commands (schedule, cancel, transcript request, meeting status query, auth initiate) via ESB, and the extension publishes completion events back. Google Calendar V3 has **no ESB integration** — everything is done via catalog handlers triggered by the Krista platform.

### 4.5 Meeting Scheduling (ESB Commands)

| Aspect | Google Calendar V3 | Teams-Transcribe |
|--------|-------------------|-----------------|
| **Trigger** | ❌ Not available | ESB command: `ScheduleMeetingCommand` on `ext.schedule.microsoft_teams` |
| **Handler** | — | `KipScheduleMeetingHandler` → `KipExtensionEsbAdapter.processScheduleRequest()` |
| **Flow** | — | Receive command → resolve user → `EventSubscriptionFacade.subscribeToSingleMeeting()` → publish `SchedulingCompletedEvent` |
| **Response** | — | Async event: `events.scheduling.completed` |

### 4.6 Transcript Handling

| Aspect | Google Calendar V3 | Teams-Transcribe |
|--------|-------------------|-----------------|
| **Trigger** | ❌ Not available | ESB command: `TranscriptRequestCommand` on `ext.transcript.request.microsoft_teams` |
| **Handler** | — | `KipTranscriptRequestHandler` → `KipExtensionEsbAdapter.processTranscriptFetchRequest()` |
| **Service** | — | `TranscriptService` (fetches from Microsoft Graph API) |
| **Fallback** | — | `TranscriptFallbackService` for retry/alternative paths |
| **Response** | — | Async event: `events.transcript.delivered` |

### 4.7 Meeting Status & Cancellation

| Aspect | Google Calendar V3 | Teams-Transcribe |
|--------|-------------------|-----------------|
| **Cancel Trigger** | Catalog handler: `cancelEvent()` in CalendarService | ESB command: `CancelMeetingCommand` on `ext.cancel.microsoft_teams` |
| **Cancel Response** | Synchronous ExtensionResponse | Async event: `events.cancel.completed` |
| **Status Query** | ❌ Not available | ESB command on `ext.meeting.status.microsoft_teams` → `events.meeting.status.completed` |
| **Update Status** | ❌ | `SendEventsToCAService.updateMeetingScheduledStatus()` via `PUT /api/v1/meetings/{key}` |

### 4.8 Event Subscription & Webhooks

| Aspect | Google Calendar V3 | Teams-Transcribe |
|--------|-------------------|-----------------|
| **Calendar Webhooks** | ❌ | ✅ `CalendarSubscriptionService` (Microsoft Graph `/subscriptions` for `/me/events`) |
| **Chat Webhooks** | ❌ | ✅ Chat message subscriptions for meeting recording notifications |
| **Auto-Subscribe on Load** | ❌ | ✅ `autoSubscribeAuthenticatedUsers()` in `invokerLoaded()` |
| **Single Meeting Subscribe** | ❌ | ✅ `EventSubscriptionFacade.subscribeToSingleMeeting()` |
| **Bulk Meeting Subscribe** | ❌ | ✅ `EventSubscriptionFacade.subscribeToMeetingEvents()` |

### 4.9 Meeting Recording

| Aspect | Google Calendar V3 | Teams-Transcribe |
|--------|-------------------|-----------------|
| **Auto-Record** | ❌ N/A (Google doesn't expose this API) | ✅ `MeetingProcessor.enableRecordingAndSubscribeForMeeting()` |
| **Manual Record Prompt** | ❌ | ✅ Sends chat message prompting user to start recording |
| **Recording State** | ❌ | ✅ `RecordingStateStore` |

### 4.10 Telemetry

Both extensions share a nearly identical `CalendarSyncTelemetryService`:

| Aspect | Google Calendar V3 | Teams-Transcribe |
|--------|-------------------|-----------------|
| **Class** | `CalendarSyncTelemetryService` | `CalendarSyncTelemetryService` |
| **Methods** | `recordSyncStart()`, `recordSyncSuccess()`, `recordSyncFailure()`, `recordUserProcessing()`, `recordEventsProcessed()`, `recordApiCall()` | Same |

**Status:** ✅ Well aligned.

### 4.11 Shared Models

| Model | Google Calendar V3 | Teams-Transcribe | Alignment |
|-------|-------------------|-----------------|-----------|
| `CreateMeetingRequestDto` | Gson `@SerializedName` annotations | javax `@NotBlank`/`@NotNull`/`@Pattern` validation | ⚠️ Same fields, different annotation style |
| `Person` | Simple (email, displayName) | Simple (email, displayName) | ✅ Aligned |
| `TrackMeetingEventStore` | KeyValueStore-backed | KeyValueStore-backed | ✅ Aligned |
| `MeetingKeyResolver` | HTTP lookup from central service | HTTP lookup from central service | ✅ Aligned |

**Key Difference:** Teams-Transcribe's DTO uses **javax validation annotations** (`@NotBlank`, `@Pattern`) for input validation. Google Calendar V3 uses **Gson serialization annotations** (`@SerializedName`). The actual field set is identical.

### 4.12 Catalog / Sub-Catalog Handlers

| Handler | Google Calendar V3 | Teams-Transcribe |
|---------|-------------------|-----------------|
| **AdaptiveCalendarSyncHandler** | ✅ Calls `sendEventsToMAService.sendEventsToMA(days)` | ✅ Calls `sendEventsToCAService.sendEventsToCA(days)` |
| **CalendarSyncByUserHandler** | ✅ Calls `sendEventsToMAService.sendEventsToMAByUser(userId, days)` | ✅ Calls `sendEventsToCAService.sendEventsToCAByUser(userId, days)` |
| **Calendar CRUD (accept/decline/schedule/cancel/get)** | ✅ Full set via `CalendarService` → `CalendarAttendeeService` + `CalendarEventService` | ❌ Not present as catalog handlers |
| **AuthenticateHandler** | ❌ (auth via separate REST resource) | ✅ Catalog handler for triggering auth |
| **CalendarSubscriptionHandler** | ❌ | ✅ Creates webhook subscriptions |
| **MeetingSubscriptionHandler** | ❌ | ✅ Subscribes to specific meetings |
| **DisconnectAccountHandler** | ❌ | ✅ Removes user authentication |
| **GetAuthenticationUsersHandler** | ❌ | ✅ Lists authenticated users |

---

## 5. Code Structure Comparison

### Google Calendar V3 (22 Java files)

```
google_calendar3/src/main/java/.../google_calendar3/
├── GoogleCalendarExtension.java          # Entry point
├── GoogleCalendarAuthenticator.java      # OAuth authenticator
├── GoogleAttributes.java                 # OAuth config holder
├── api/
│   ├── AuthHelper.java                   # Auth utility
│   ├── AuthenticationResource.java       # REST auth endpoints
│   └── GoogleCalendarApplication.java    # JAX-RS app
├── catalog/
│   ├── CatalogTypes.java                 # Type definitions
│   ├── MeetingsArea.java                 # Main catalog entry
│   ├── MeetingsAreaSubCatalogRequests.java
│   ├── entity/EventDetails.java          # Event entity
│   ├── errorhandlers/...                 # Error handling
│   ├── extresp/...                       # Extension response builders
│   └── handler/
│       ├── AdaptiveCalendarSyncHandler.java
│       └── CalendarSyncByUserHandler.java
├── impl/
│   ├── connectors/GoogleProviderFactory.java
│   ├── model/CreateMeetingRequestDto.java, Person.java
│   ├── store/GoogleAttributeStore.java, TrackMeetingEventStore.java
│   └── util/Constants.java, MeetingKeyResolver.java
├── repository/
│   └── impl/GoogleCalendarRepositoryImpl.java
├── service/
│   ├── CalendarService.java              # Service interface
│   ├── impl/
│   │   ├── CalendarServiceImpl.java      # Facade
│   │   ├── CalendarAttendeeService.java  # Attendee ops
│   │   └── CalendarEventService.java     # Event ops
│   └── sync/
│       ├── CalendarSyncTelemetryService.java
│       ├── GoogleCalendarAuthenticationService.java
│       ├── GoogleCalendarEventFetcher.java
│       ├── GoogleEventToMeetingDtoMapper.java
│       ├── MeetingKeyResolver.java
│       └── SendEventsToMAService.java
```

### Teams-Transcribe (88+ Java files)

```
teams-transcribe/src/main/java/
├── app/krista/conversationagent/module/   # Shared ESB command/event classes
│   ├── aiassistant/esb/AuthInitiateCommand.java
│   └── shared/
│       ├── commands/                      # ScheduleMeeting, TranscriptRequest, etc.
│       └── events/external/              # SchedulingCompleted, TranscriptDelivered, etc.
├── app/krista/global/
│   ├── MicrosoftTeamsCalendarExtension.java
│   ├── MicrosoftTeamsCalendarAttributes.java
│   ├── MicrosoftTeamsCalendarRequestAuthenticator.java
│   ├── api/                              # REST resources + models
│   ├── catalog/
│   │   ├── TeamsPostMeetingArea.java
│   │   ├── entity/Meeting.java
│   │   ├── handlers/                     # 8+ catalog handlers
│   │   └── spi/CatalogRequestHandler.java
│   ├── config/KipExtensionConfig.java    # ESB queue configs
│   ├── esb/
│   │   ├── KipExtensionEsbAdapter.java   # Central ESB adapter (1011 lines)
│   │   └── dto/                          # ESB DTOs
│   ├── impl/
│   │   ├── connectors/GraphServiceClientProvider.java
│   │   ├── model/CreateMeetingRequestDto.java, Person.java
│   │   ├── stores/                       # 10+ KV stores
│   │   └── util/...
│   └── service/
│       ├── ApplicationInitializer.java
│       ├── KipServiceBusManager.java     # ESB lifecycle
│       ├── ConsumerThreadMonitor.java    # Health monitoring
│       ├── ContextSnapshotHolder.java    # HK2 context
│       ├── auth/KipAuthInitiateHandler.java
│       ├── cancel/KipCancelHandler.java
│       ├── schedule/KipScheduleMeetingHandler.java
│       ├── status/KipMeetingStatusQueryHandler.java
│       ├── transcript/KipTranscriptRequestHandler.java
│       └── teams/
│           ├── AuthenticationService.java      # (694 lines)
│           ├── TokenService.java
│           ├── SendEventsToCAService.java      # (1072 lines)
│           ├── MeetingDetailsHandler.java      # (314 lines)
│           ├── CalendarSyncTelemetryService.java
│           ├── calendar/CalendarSubscriptionService.java
│           ├── events/
│           │   ├── EventSubscriptionFacade.java # (2035 lines)
│           │   ├── MeetingProcessor.java        # (498 lines)
│           │   └── EventFetcher.java
│           ├── meetings/MeetingRecordService.java
│           └── transcript/TranscriptService.java
```

**Size difference:** ~22 files vs ~88+ files. Teams-Transcribe is roughly **4x larger**.

---

## 6. What Is Aligned Today

These areas are already closely aligned between the two extensions:

1. **Calendar Sync Core Flow** — Both follow the same fetch → diff → batch-create → track pattern
2. **CreateMeetingRequestDto** — Identical field set (title, startTime, endTime, timezone, meetingUrl, organizer, attendees, calendarSystem, recurring, recurringSeriesId, body, iCalUId)
3. **Person model** — email + displayName
4. **TrackMeetingEventStore** — Same KV store pattern for tracking synced events with metadata
5. **MeetingKeyResolver** — Same HTTP-based lookup pattern
6. **CalendarSyncTelemetryService** — Nearly identical API surface
7. **Deletion Detection** — Both use iCalUID-based comparison with fetch-window filtering
8. **Batch API** — Both use `POST /api/v1/meetings/batch` with batch size 25
9. **Delete API** — Both use `DELETE /api/v1/meetings/{key}`
10. **AdaptiveCalendarSyncHandler** + **CalendarSyncByUserHandler** — Same catalog handler pattern

---

## 7. Key Differences Summary

### Architectural Differences

| # | Difference | Impact |
|---|-----------|--------|
| 1 | **ESB Integration** — Teams has full ESB; Google has none | Google Calendar cannot receive scheduling/transcript/cancel commands from CA Service |
| 2 | **Webhook Subscriptions** — Teams subscribes to real-time Graph events; Google does not | Google Calendar only syncs on-demand (catalog trigger), no real-time updates |
| 3 | **Workspace-scoped Auth** — Teams supports `workspace_auth:{wsId}:{email}`; Google has flat user list | Google Calendar can't support AI assistant per-workspace authentication |
| 4 | **ThreadLocal Context** — Teams must propagate KSDK ThreadLocalProxy; Google doesn't need it | When Google Calendar adds ESB, it will need context propagation too |
| 5 | **API Key on REST calls** — Teams uses `x-api-key` header; Google doesn't | Security inconsistency — should align |

### Implementation Differences

| # | Difference | Which is Better |
|---|-----------|----------------|
| 1 | **Event Mapper** — Google has dedicated `GoogleEventToMeetingDtoMapper` class; Teams maps inline in `SendEventsToCAService` | ✅ Google's approach (separation of concerns) |
| 2 | **Concurrency Limit** — Google: 5 users; Teams: 10 users | Depends on deployment resources |
| 3 | **DTO Validation** — Teams uses javax validation annotations; Google uses Gson serialization | ✅ Teams' approach (proper validation) |
| 4 | **Auth Service Complexity** — Google: simple 95-line class; Teams: 694-line class with workspace-auth | Teams is more capable but more complex |
| 5 | **Calendar CRUD** — Google has full accept/decline/tentative/schedule/cancel; Teams doesn't | ✅ Google's approach (complete API coverage) |
| 6 | **Service Layer Pattern** — Google has `CalendarService` interface → `CalendarServiceImpl` → `CalendarAttendeeService` + `CalendarEventService`; Teams has no service-layer for CRUD | ✅ Google's clean layering |

---

## 8. Alignment Roadmap

### 8.1 Align Now (High Priority)

These should be addressed in the current development cycle:

1. **Add `x-api-key` header to Google Calendar V3 MA API calls**
   - Teams-Transcribe sends `x-api-key` on every CA REST call
   - Google Calendar V3 sends none
   - Risk: If MA enforces API keys, Google Calendar sync will break
   - **Effort:** Small (add header to `SendEventsToMAService`)

2. **Add javax validation annotations to Google Calendar V3's `CreateMeetingRequestDto`**
   - Teams has `@NotBlank`, `@NotNull`, `@Pattern` on DTO fields
   - Google Calendar has only `@SerializedName`
   - **Effort:** Small

3. **Align naming convention: MA vs CA**
   - Google Calendar calls the central service "MA" (Meeting Agent)
   - Teams calls it "CA" (Conversation Agent)
   - These appear to be the **same service** — clarify and unify naming
   - **Effort:** Documentation/naming only

4. **Extract meeting status update capability for Google Calendar**
   - Teams has `updateMeetingScheduledStatus()` to mark meetings as scheduled in CA
   - Google Calendar should have the equivalent if needed
   - **Effort:** Small

### 8.2 Align Next (Medium Priority)

These should be planned for the next development cycle:

5. **ESB Integration for Google Calendar V3**
   - Add `KipServiceBusManager` equivalent
   - Add `KipExtensionConfig` for ESB queue configuration
   - Implement ESB command handlers:
     - `KipScheduleMeetingHandler` (platform: `google_calendar`)
     - `KipCancelHandler`
     - `KipTranscriptRequestHandler` (even if Google transcript support is limited)
     - `KipMeetingStatusQueryHandler`
     - `KipAuthInitiateHandler`
   - Add ESB event publishers:
     - `SchedulingCompletedEventPublisher`
     - `CancelCompletedEventPublisher`
     - `TranscriptDeliveredEventPublisher`
     - `MeetingStatusQueryCompletedEventPublisher`
   - **Effort:** Large — this is the biggest alignment task

6. **Add workspace-scoped authentication to Google Calendar V3**
   - Support `workspace_auth:{workspaceId}:{email}` key pattern
   - Enable AI assistant per-workspace auth
   - **Effort:** Medium

7. **Add `ApplicationInitializer` pattern to Google Calendar V3**
   - Bootstrap ESB consumers on `invokerLoaded()`
   - Clean shutdown on `invokerUnloaded()`
   - **Effort:** Medium (depends on #5)

8. **Add real-time webhook subscriptions for Google Calendar**
   - Google Calendar API supports push notifications via `/watch` endpoint
   - Implement equivalent of `CalendarSubscriptionService`
   - Handle subscription renewal
   - **Effort:** Medium

### 8.3 Future / Low Priority

9. **Extract Teams-Transcribe event mapping into dedicated mapper class**
   - Follow Google Calendar V3's clean `GoogleEventToMeetingDtoMapper` pattern
   - **Effort:** Small refactor in teams-transcribe

10. **Unify `ConsumerThreadMonitor` and context preservation as shared library**
    - Both extensions will need this once Google Calendar has ESB
    - Could be a shared module in krista-infra
    - **Effort:** Medium

11. **Add catalog CRUD handlers to Teams-Transcribe**
    - Teams-Transcribe lacks direct calendar CRUD (accept/decline/schedule/cancel)
    - Google Calendar V3 has a full `CalendarService` layer
    - **Effort:** Medium

12. **Shared base class / module for common sync logic**
    - Both `SendEventsToMAService` and `SendEventsToCAService` are ~80% identical
    - Could extract an abstract `SendEventsToService` with platform-specific hooks
    - **Effort:** Large (cross-repo refactor)

---

## 9. Risk Areas

| Risk | Severity | Mitigation |
|------|----------|------------|
| **API Key enforcement on MA** — If MA starts requiring `x-api-key`, Google Calendar sync will fail silently | High | Add API key support now (item #1) |
| **ESB schema changes** — If CA Service changes ESB command/event schemas, only Teams is affected today. When Google Calendar adds ESB, schema drift could occur | Medium | Share command/event classes from a common module |
| **Central service divergence (MA vs CA)** — If these are the same service, no risk. If they diverge, both extensions will need updates | Medium | Clarify whether MA and CA are the same service |
| **ThreadLocal context** — Google Calendar V3 doesn't propagate ThreadLocal. If it needs KSDK features in sync threads, it will fail | Low (today) | Will need to add when ESB is integrated |
| **Store compatibility** — Both use `TrackMeetingEventStore` but with potentially different KV key prefixes | Low | Verify store key patterns are compatible |

---

*This is a living document. Update it as implementations change or new alignment decisions are made.*
