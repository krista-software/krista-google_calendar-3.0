# Schedule Meeting

## Overview

The **Schedule Meeting** catalog request creates and schedules a new calendar event with comprehensive details including title, location, start/end times, description, and attendee list. This action creates the event in the organizer's calendar and sends invitations to all specified attendees.

## Request Details

- **Area**: Meetings
- **Type**: CHANGE_SYSTEM
- **MCP Tool**: ✅ Yes (exposed to the Krista AI agent)
- **Retry Support**: ✅ Yes (when Allow Retry = true)

## Input Parameters

| Parameter Name | Type | Required | Description | Example |
|----------------|------|----------|-------------|---------|
| **Title** | Text | Yes | Title or subject of the meeting | "Q1 Planning Meeting" |
| **Location** | Text | No | Physical or virtual location of the meeting | "Conference Room A" or "https://meet.google.com/abc-defg-hij" |
| **Date From** | Date | Yes | Start date and time of the meeting | "15 Jan 2025 14:00" |
| **Date To** | Date | Yes | End date and time of the meeting | "15 Jan 2025 15:30" |
| **Details** | Rich Text | No | Additional description or agenda for the meeting | "Discuss Q1 objectives and KPIs" |
| **Attendee Email** | List<Email> | Yes | List of attendee email addresses (comma-separated) | ["john@example.com", "jane@example.com"] |

### Parameter Details

#### Title
- **Maximum Length**: 255 characters
- **Restrictions**: 
  - Cannot contain newline characters (`\n`, `\r`)
  - Cannot contain control characters (ASCII < 32)
  - Cannot be empty or null
- **Best Practice**: Use clear, descriptive titles that convey the meeting purpose

#### Location
- **Optional**: Can be null or empty
- **Supports**: 
  - Physical locations (e.g., "Building 2, Room 301")
  - Virtual meeting links (e.g., Google Meet, Zoom URLs)
  - Hybrid format (e.g., "Room 301 / https://meet.google.com/xyz")
- **Maximum Length**: 1024 characters

#### Date From (Start Time)
- **Format**: Unix timestamp in milliseconds or date string
- **Validation**: 
  - Must be a future date/time (not in the past)
  - Must be a valid timestamp
  - Cannot be null
- **Time Zone**: Uses the calendar's default time zone
- **Example**: `1705327200000` (milliseconds) or "15 Jan 2025 14:00"

#### Date To (End Time)
- **Format**: Unix timestamp in milliseconds or date string
- **Validation**: 
  - Must be after Date From
  - Must be a valid timestamp
  - Cannot be null
- **Minimum Duration**: No minimum, but should be reasonable
- **Example**: `1705332600000` (milliseconds) or "15 Jan 2025 15:30"

#### Details
- **Optional**: Can be null or empty
- **Supports**: Rich text formatting (HTML)
- **Use Cases**: 
  - Meeting agenda
  - Preparation materials
  - Links to documents
  - Meeting objectives
- **Maximum Length**: 8192 characters

#### Attendee Email
- **Format**: List of email addresses
- **Validation**: Each email must be RFC 5322 compliant
- **Behavior**: 
  - All attendees receive calendar invitations
  - Attendees can accept, decline, or mark as tentative
  - Organizer is automatically added as an attendee
- **Limitations**: Google Calendar has a limit of 200 attendees per event

## Output Parameters

| Parameter Name | Type | Description |
|----------------|------|-------------|
| **Event Details** | Entity(Event Details) | Complete details of the created event |

### Event Details Entity

The returned Event Details entity contains:

- **Event ID**: Unique identifier for the created event
- **Title**: Meeting title
- **Location**: Meeting location
- **Start Time**: Meeting start date/time
- **End Time**: Meeting end date/time
- **Description**: Meeting details/agenda
- **Attendees**: List of attendee information including:
  - Email address
  - Response status (needsAction, accepted, declined, tentative)
  - Organizer flag
- **Organizer**: Email of the meeting organizer
- **Created**: Timestamp when event was created
- **Updated**: Timestamp when event was last updated
- **HTML Link**: Link to view the event in Google Calendar
- **Hangout Link**: Google Meet link (if auto-generated)

## Validation Rules

When **Allow Retry = true**, the following validations are performed:

### Title Validation

| Validation | Error Message | Resolution |
|------------|---------------|------------|
| Empty or null | "The title is required. Please provide a valid title." | Provide a non-empty title |
| Exceeds 255 characters | "The provided title exceeds the maximum length of 255 characters ({actualLength} characters provided). Please provide a shorter title." | Shorten the title |
| Contains newlines | "The provided title contains newline characters which are not allowed. Please provide a title without newlines." | Remove newline characters |
| Contains control characters | "The provided title contains invalid control characters. Please provide a valid title." | Remove control characters |

### Date From Validation

| Validation | Error Message | Resolution |
|------------|---------------|------------|
| Empty or null | "The start date and time is required. Please provide a valid start date and time." | Provide a start date |
| Past date | "The provided start date ({date}) is in the past. Please provide a future date." | Use a future date/time |
| Invalid format | "The provided start date ({date}) is invalid. Please provide a valid date." | Use correct date format |

### Date To Validation

| Validation | Error Message | Resolution |
|------------|---------------|------------|
| Empty or null | "The end date and time is required. Please provide a valid end date and time." | Provide an end date |
| Before start date | "The provided end date ({endDate}) is not after the start date ({startDate}). Please provide an end date after the start date." | Ensure end time is after start time |
| Invalid format | "The provided end date ({date}) is invalid. Please provide a valid date." | Use correct date format |

### Attendee Email List Validation

| Validation | Error Message | Resolution |
|------------|---------------|------------|
| Empty or null | "Please enter valid attendee email addresses (comma-separated)." | Provide at least one email |
| Invalid email format | "The provided attendee email list: {emailList} contains invalid emails. Please provide valid attendee emails." | Correct email format |
| Too many attendees | System error if exceeds Google's limit | Reduce number of attendees |

## Error Handling

### Input Errors (INPUT_ERROR)

**Cause**: Invalid input parameters that fail validation

**Behavior**:
- When **Allow Retry = true**: User is prompted to re-enter invalid parameters
- When **Allow Retry = false**: Returns error immediately

**Common Scenarios**:
- Invalid date formats or ranges
- Title exceeds character limits
- Malformed email addresses
- Past dates for meeting start time

**Resolution**: Correct the invalid parameters and retry

### Logic Errors (LOGIC_ERROR)

**Cause**: Business logic errors during operation execution

**Error Message**: "Failed to schedule meeting: Please enter valid inputs and try again with a new request"

**Common Scenarios**:
- Calendar service unavailable
- Insufficient permissions to create events
- Conflicting calendar settings

**Resolution**: 
- Verify calendar access permissions
- Check calendar settings
- Retry the operation

### System Errors (SYSTEM_ERROR)

**Cause**: Unexpected system-level errors

**Error Message**: "Error occurred in scheduleMeeting: {errorMessage}"

**Common Scenarios**:
- Network connectivity issues
- Google API service disruption
- Internal server errors

**Resolution**: 
- Check network connectivity
- Verify Google Calendar API status
- Retry after a brief delay
- Contact support if persists

### Authorization Errors (AUTHORIZATION_ERROR)

**Cause**: Authentication or authorization failures

**Error Message**: "You must authorize to perform this operation"

**Common Scenarios**:
- User not authenticated
- Refresh token expired or revoked
- Insufficient calendar permissions

**Resolution**: 
- Re-authenticate via Authentication tab
- Grant necessary calendar permissions
- Verify account has calendar access

## Usage Examples

### Example 1: Basic Meeting

**Input**:
```
Title: "Team Standup"
Location: "Conference Room B"
Date From: "16 Jan 2025 09:00"
Date To: "16 Jan 2025 09:30"
Details: "Daily team sync"
Attendee Email: ["team@example.com"]
```

**Output**:
```json
{
  "Event Details": {
    "eventId": "abc123xyz",
    "title": "Team Standup",
    "location": "Conference Room B",
    "startTime": 1705395600000,
    "endTime": 1705397400000,
    "description": "Daily team sync",
    "attendees": [
      {
        "email": "team@example.com",
        "responseStatus": "needsAction"
      }
    ],
    "organizer": "admin@example.com",
    "htmlLink": "https://calendar.google.com/event?eid=..."
  }
}
```

### Example 2: Virtual Meeting with Multiple Attendees

**Input**:
```
Title: "Product Review"
Location: "https://meet.google.com/abc-defg-hij"
Date From: "17 Jan 2025 14:00"
Date To: "17 Jan 2025 15:00"
Details: "Review Q1 product roadmap and priorities"
Attendee Email: ["john@example.com", "jane@example.com", "bob@example.com"]
```

**Output**: Event created with Google Meet link and all attendees invited

### Example 3: All-Day Event

**Input**:
```
Title: "Company Offsite"
Location: "Mountain View Campus"
Date From: "20 Jan 2025 00:00"
Date To: "20 Jan 2025 23:59"
Details: "Annual company offsite and team building"
Attendee Email: ["all-staff@example.com"]
```

**Output**: All-day event created on specified date

## Business Rules

1. **Organizer**: The authenticated user (or service account) becomes the event organizer
2. **Calendar**: Event is created in the organizer's primary calendar
3. **Invitations**: All attendees receive email invitations automatically
4. **Visibility**: Event appears on all attendees' calendars (pending acceptance)
5. **Time Zone**: Uses the calendar's default time zone setting
6. **Reminders**: Default calendar reminders are applied
7. **Google Meet**: If location is not specified, Google may auto-generate a Meet link (depending on calendar settings)

## Limitations

1. **Attendee Limit**: Maximum 200 attendees per event (Google Calendar limitation)
2. **Title Length**: Maximum 255 characters
3. **Details Length**: Maximum 8192 characters
4. **Location Length**: Maximum 1024 characters
5. **Past Dates**: Cannot schedule meetings in the past
6. **Rate Limits**: Subject to Google Calendar API rate limits (20,000 requests per 100 seconds per user)

## Best Practices

### 1. Provide Clear Titles
- Use descriptive, concise titles
- Include meeting type or purpose
- Avoid special characters that may cause issues

### 2. Specify Location
- For virtual meetings, include full meeting link
- For physical meetings, include room number and building
- Consider hybrid format for flexibility

### 3. Add Detailed Agenda
- Use the Details field for meeting agenda
- Include preparation materials or links
- Set clear objectives and expected outcomes

### 4. Validate Attendee Lists
- Verify email addresses before submission
- Consider using distribution lists for large groups
- Remove duplicate emails

### 5. Set Appropriate Times
- Allow buffer time between meetings
- Consider time zones for distributed teams
- Set realistic meeting durations

### 6. Handle Errors Gracefully
- Enable retry mechanism for user-facing applications
- Provide clear error messages to users
- Log errors for troubleshooting

## Related Catalog Requests

- [Add Attendees](pages/AddAttendees.md) - Add attendees to the created meeting
- [Cancel Event](pages/CancelEvent.md) - Cancel the scheduled meeting
- [Get Event](pages/GetEvent.md) - Retrieve details of the created event
- [Get Next Upcoming Meetings](pages/GetNextUpcomingMeetings.md) - View upcoming scheduled meetings

## Technical Implementation

### Helper Class
- **Class**: `ScheduleMeetingHelper`
- **Package**: `app.kristasoft.extensions.essentials.collaboration.google_calendar.catalog.helper`
- **Validation**: Uses `ValidationOrchestrator` with specific validators for each parameter
- **Service**: Delegates to `CalendarService.scheduleMeeting()`

### Validation Flow
1. Input parameters are collected
2. `ValidationOrchestrator.validate()` is called with validation parameters
3. If validation fails and Allow Retry = true:
   - `SubCatalogRequestImpl.handleScheduleMeeting()` is invoked
   - User is prompted to correct invalid inputs
   - Process repeats until valid input or user cancels
4. If validation passes:
   - `CalendarService.scheduleMeeting()` is called
   - Event is created in Google Calendar
   - Event Details are returned

### Telemetry Metrics
- `SCHEDULE_MEETING_REQUEST`: Total requests
- `SCHEDULE_MEETING_REQUEST_SUCCESS`: Successful operations
- `SCHEDULE_MEETING_REQUEST_FAILURE`: Failed operations
- `SCHEDULE_MEETING_RETRY_ATTEMPT`: Validation retry attempts

## See Also

- [Extension Configuration](pages/ExtensionConfiguration.md) - Configure retry mechanism
- [Authentication](pages/Authentication.md) - Required for creating events
- [Creating Google App](pages/CreatingGoogleApp.md) - Setup Google Calendar API access

