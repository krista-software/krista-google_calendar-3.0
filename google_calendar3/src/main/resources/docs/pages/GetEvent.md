# Get Event

## Overview

The **Get Event** catalog request retrieves comprehensive details of a specific meeting or event by providing its unique event ID. This returns all event information including title, location, time, attendees, and description.

## Request Details

- **Area**: Meetings
- **Type**: QUERY_SYSTEM
- **MCP Tool**: ✅ Yes (exposed to the Krista AI agent)
- **Retry Support**: ✅ Yes (when Allow Retry = true)

## Input Parameters

| Parameter Name | Type | Required | Description | Example |
|----------------|------|----------|-------------|---------|
| **Event ID** | Text | Yes | Unique identifier of the meeting or event to retrieve | "abc123xyz789" |

### Parameter Details

#### Event ID
- **Format**: Google Calendar event identifier string
- **Source**: Obtained from:
  - [Schedule Meeting](pages/ScheduleMeeting.md) response
  - [Get Next Upcoming Meetings](pages/GetNextUpcomingMeetings.md) response
  - [Get All Unaccepted Events](pages/GetAllUnacceptedEvents.md) response
  - Google Calendar URL (the `eid` parameter)
  - Email invitations
- **Validation**: 
  - Cannot be null or empty
  - Must be a valid, existing event ID
  - User must have access to the event
- **Example**: `"abc123xyz789"` or `"eventid_1234567890"`

## Output Parameters

| Parameter Name | Type | Description |
|----------------|------|-------------|
| **Event Details** | Entity(Event Details) | Complete details of the requested event |

### Event Details Entity

The returned Event Details contains comprehensive event information:

- **Event ID**: Unique identifier for the event
- **Title**: Meeting title/subject
- **Location**: Physical or virtual location
- **Start Time**: Meeting start date/time (Unix timestamp in milliseconds)
- **End Time**: Meeting end date/time (Unix timestamp in milliseconds)
- **Description**: Meeting details, agenda, or notes
- **Attendees**: List of attendee information:
  - Email address
  - Display name
  - Response status (needsAction, accepted, declined, tentative)
  - Organizer flag
  - Optional flag
- **Organizer**: Email and name of the meeting organizer
- **Created**: Timestamp when event was created
- **Updated**: Timestamp when event was last updated
- **Status**: Event status (confirmed, tentative, cancelled)
- **HTML Link**: Direct link to view the event in Google Calendar
- **Hangout Link**: Google Meet link (if available)
- **Recurring Event ID**: ID of recurring event series (if applicable)
- **Visibility**: Event visibility (default, public, private, confidential)
- **Transparency**: Free/busy status (opaque, transparent)

## Validation Rules

When **Allow Retry = true**, the following validations are performed:

### Event ID Validation

| Validation | Error Message | Resolution |
|------------|---------------|------------|
| Empty or null | "Please enter a valid Event Id." | Provide a non-empty event ID |
| Invalid format | "The provided Event Id: {eventId} is invalid. Please provide a valid Event Id." | Verify the event ID is correct |
| Event not found | "The provided Event Id: {eventId} is invalid. Please provide a valid Event Id." | Check that the event exists and you have access |

## Error Handling

### Input Errors (INPUT_ERROR)

**Cause**: Invalid event ID parameter

**Behavior**:
- When **Allow Retry = true**: User is prompted to re-enter the event ID
- When **Allow Retry = false**: Returns error immediately

**Common Scenarios**:
- Empty or null event ID
- Malformed event ID
- Event ID doesn't exist
- User lacks access to the event

**Resolution**: Provide a valid event ID from an accessible event

### Logic Errors (LOGIC_ERROR)

**Cause**: Business logic errors during retrieval

**Error Message**: "Failed to get event: Please enter valid inputs and try again with a new request"

**Common Scenarios**:
- Event has been deleted
- Event is in a calendar user doesn't have access to
- Private event with restricted visibility
- Calendar service unavailable

**Resolution**: 
- Verify event exists and hasn't been deleted
- Check calendar access permissions
- Ensure you're an attendee or have calendar access

### System Errors (SYSTEM_ERROR)

**Cause**: Unexpected system-level errors

**Error Message**: "Error occurred in getEvent: {errorMessage}"

**Common Scenarios**:
- Network connectivity issues
- Google API service disruption
- Internal server errors
- Timeout errors

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
- Event is private and user is not an attendee

**Resolution**: 
- Re-authenticate via Authentication tab
- Verify calendar access permissions
- Ensure you're an attendee or have shared calendar access

## Usage Examples

### Example 1: Retrieve Meeting Details

**Input**:
```
Event ID: "abc123xyz789"
```

**Output**:
```json
{
  "Event Details": {
    "eventId": "abc123xyz789",
    "title": "Q1 Planning Meeting",
    "location": "Conference Room A",
    "startTime": 1705327200000,
    "endTime": 1705332600000,
    "description": "Discuss Q1 objectives and KPIs",
    "attendees": [
      {
        "email": "john@example.com",
        "displayName": "John Doe",
        "responseStatus": "accepted",
        "organizer": false
      },
      {
        "email": "jane@example.com",
        "displayName": "Jane Smith",
        "responseStatus": "needsAction",
        "organizer": false
      },
      {
        "email": "admin@example.com",
        "displayName": "Admin User",
        "responseStatus": "accepted",
        "organizer": true
      }
    ],
    "organizer": {
      "email": "admin@example.com",
      "displayName": "Admin User"
    },
    "status": "confirmed",
    "htmlLink": "https://calendar.google.com/event?eid=...",
    "hangoutLink": "https://meet.google.com/abc-defg-hij",
    "created": 1705240800000,
    "updated": 1705241000000
  }
}
```

### Example 2: Check Event Before Accepting

**Scenario**: User wants to review event details before accepting invitation

**Flow**:
1. User receives event ID from invitation
2. Use Get Event to retrieve full details
3. Review attendees, time, location
4. Use [Accept Invitation](pages/AcceptInvitation.md) if appropriate

### Example 3: Verify Event Exists

**Scenario**: Verify an event exists before performing operations

**Flow**:
1. Use Get Event with the event ID
2. If successful, event exists and is accessible
3. Proceed with other operations (cancel, add attendees, etc.)

## Business Rules

1. **Access Control**: User must have access to the event:
   - Event organizer
   - Event attendee
   - Has access to the calendar containing the event
   - Calendar is shared with the user

2. **Read-Only**: This is a query operation that doesn't modify data

3. **Deleted Events**: Deleted or permanently removed events cannot be retrieved

4. **Private Events**: Private events may have limited information visible to non-attendees

5. **Recurring Events**: Returns details for a specific instance or the series

## Limitations

1. **Access Required**: Can only retrieve events you have access to
2. **Deleted Events**: Cannot retrieve permanently deleted events
3. **Private Events**: Limited information for private events (if not an attendee)
4. **Rate Limits**: Subject to Google Calendar API rate limits
5. **Canceled Events**: May have limited information or may not be retrievable

## Best Practices

### 1. Verify Event Access
- Ensure you have permission to access the event
- Check if you're an attendee or have calendar access
- Handle "event not found" errors gracefully

### 2. Use for Validation
- Verify event exists before performing operations
- Check event details before accepting/declining
- Validate event ID before canceling or modifying

### 3. Cache Event Details
- Cache event details to reduce API calls
- Refresh cache when event is updated
- Consider cache expiration policies

### 4. Handle Missing Fields
- Not all events have all fields populated
- Check for null/empty values before using
- Provide defaults for missing optional fields

### 5. Display Comprehensive Information
- Show all relevant event details to users
- Format dates/times in user's timezone
- Display attendee response statuses

## Common Use Cases

### 1. View Meeting Details
```
Scenario: User wants to see full meeting information
Action: Use Get Event with event ID
Result: Display comprehensive event details
```

### 2. Pre-Accept Validation
```
Scenario: Review event before accepting invitation
Action: Get Event → Review details → Accept/Decline
Result: Informed decision on invitation
```

### 3. Event Verification
```
Scenario: Verify event exists before modification
Action: Get Event to confirm existence
Result: Proceed with modification or handle not found
```

### 4. Attendee List Review
```
Scenario: Check who's invited to a meeting
Action: Get Event and extract attendee list
Result: View all attendees and their response status
```

### 5. Meeting Link Retrieval
```
Scenario: Need to join a virtual meeting
Action: Get Event and extract hangoutLink
Result: Access Google Meet or other meeting link
```

## Response Status Values

### Attendee Response Status
- **needsAction**: Invitation sent, no response yet
- **accepted**: Attendee accepted the invitation
- **declined**: Attendee declined the invitation
- **tentative**: Attendee marked as tentative

### Event Status
- **confirmed**: Event is confirmed
- **tentative**: Event is tentative
- **cancelled**: Event has been cancelled

## Related Catalog Requests

- [Get Next Upcoming Meetings](pages/GetNextUpcomingMeetings.md) - Get list of upcoming events
- [Get All Unaccepted Events](pages/GetAllUnacceptedEvents.md) - Get pending invitations
- [Accept Invitation](pages/AcceptInvitation.md) - Accept after reviewing details
- [Decline Invitation](pages/DeclineInvitation.md) - Decline after reviewing details
- [Cancel Event](pages/CancelEvent.md) - Cancel after verifying details

## Technical Implementation

### Helper Class
- **Class**: `GetEventHelper`
- **Package**: `app.kristasoft.extensions.essentials.collaboration.google_calendar.catalog.helper`
- **Validation**: Uses `ValidationOrchestrator` for event ID validation
- **Service**: Delegates to `CalendarService.getEvent()`

### Validation Flow
1. Event ID parameter is collected
2. `ValidationOrchestrator.validate()` is called
3. If validation fails and Allow Retry = true:
   - `SubCatalogRequestImpl.handleGetEvent()` is invoked
   - User is prompted to correct the event ID
   - Process repeats until valid input or user cancels
4. If validation passes:
   - `CalendarService.getEvent()` is called
   - Event details are retrieved from Google Calendar
   - Complete Event Details entity is returned

### Telemetry Metrics
- `GET_EVENT_REQUEST_ATTEMPT`: Total retrieval attempts
- `GET_EVENT_REQUEST_SUCCESS`: Successful retrievals
- `GET_EVENT_REQUEST_FAILURE`: Failed retrievals
- `GET_EVENT_RETRY_ATTEMPT`: Validation retry attempts

## Troubleshooting

### "Event Not Found" Error
**Cause**: Event ID doesn't exist or user lacks access

**Solution**: 
- Verify the event ID is correct
- Check if event was deleted
- Ensure you have access to the calendar
- Verify you're an attendee or have shared access

### "Invalid Event ID" Error
**Cause**: Malformed or incorrect event ID format

**Solution**: 
- Copy event ID directly from source
- Avoid manual typing of event IDs
- Check for extra spaces or characters

### "Access Denied" Error
**Cause**: User doesn't have permission to view the event

**Solution**: 
- Verify you're an attendee
- Check calendar sharing settings
- Request access from event organizer
- Ensure proper authentication

## See Also

- [Extension Configuration](pages/ExtensionConfiguration.md) - Configure retry mechanism
- [Authentication](pages/Authentication.md) - Required for accessing events
- [Schedule Meeting](pages/ScheduleMeeting.md) - Create new events

