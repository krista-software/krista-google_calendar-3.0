# Cancel Event

## Overview

The **Cancel Event** catalog request cancels a scheduled meeting or event by providing the event ID. This action removes the event from all attendees' calendars and sends cancellation notifications to all participants.

> **⚠️ Warning**: This is a destructive operation that cannot be undone. The event will be permanently removed from all calendars.

## Request Details

- **Area**: Meetings
- **Type**: CHANGE_SYSTEM
- **MCP Tool**: ✅ Yes (exposed to the Krista AI agent)
- **Retry Support**: ✅ Yes (when Allow Retry = true)

## Input Parameters

| Parameter Name | Type | Required | Description | Example |
|----------------|------|----------|-------------|---------|
| **Event ID** | Text | Yes | Unique identifier of the meeting or event to cancel | "abc123xyz789" |

### Parameter Details

#### Event ID
- **Format**: Google Calendar event identifier string
- **Source**: Obtained from:
  - [Schedule Meeting](pages/ScheduleMeeting.md) response
  - [Get Event](pages/GetEvent.md) response
  - [Get Next Upcoming Meetings](pages/GetNextUpcomingMeetings.md) response
  - Google Calendar URL (the `eid` parameter)
- **Validation**: 
  - Cannot be null or empty
  - Must be a valid, existing event ID
  - User must have permission to cancel the event (typically the organizer)
- **Example**: `"abc123xyz789"` or `"eventid_1234567890"`

## Output Parameters

| Parameter Name | Type | Description |
|----------------|------|-------------|
| **Event Details** | Entity(Event Details) | Details of the canceled event |

### Event Details Entity

The returned Event Details contains information about the canceled event:

- **Event ID**: The ID of the canceled event
- **Title**: Meeting title
- **Location**: Meeting location
- **Start Time**: Original start date/time
- **End Time**: Original end date/time
- **Description**: Meeting details
- **Attendees**: List of attendees who were notified
- **Organizer**: Email of the meeting organizer
- **Status**: "cancelled"

## Validation Rules

When **Allow Retry = true**, the following validations are performed:

### Event ID Validation

| Validation | Error Message | Resolution |
|------------|---------------|------------|
| Empty or null | "Please enter a valid Event Id." | Provide a non-empty event ID |
| Invalid format | "The provided Event Id: {eventId} is invalid. Please provide a valid Event Id." | Verify the event ID is correct |
| Event not found | "The provided Event Id: {eventId} is invalid. Please provide a valid Event Id." | Check that the event exists |
| No permission | Authorization error | Ensure you have permission to cancel the event |

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
- Event already canceled

**Resolution**: Provide a valid event ID from an existing event

### Logic Errors (LOGIC_ERROR)

**Cause**: Business logic errors during cancellation

**Error Message**: "Failed to cancel event: Please enter valid inputs and try again with a new request"

**Common Scenarios**:
- Event already canceled
- User lacks permission to cancel (not the organizer)
- Event is in the past and cannot be modified
- Calendar service unavailable

**Resolution**: 
- Verify you are the event organizer
- Check event hasn't already been canceled
- Ensure calendar access permissions

### System Errors (SYSTEM_ERROR)

**Cause**: Unexpected system-level errors

**Error Message**: "Error occurred in cancelEvent: {errorMessage}"

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
- Not the event organizer

**Resolution**: 
- Re-authenticate via Authentication tab
- Verify you are the event organizer
- Check calendar access permissions

## Usage Examples

### Example 1: Cancel a Meeting

**Input**:
```
Event ID: "abc123xyz789"
```

**Output**:
```json
{
  "Event Details": {
    "eventId": "abc123xyz789",
    "title": "Team Standup",
    "location": "Conference Room B",
    "startTime": 1705395600000,
    "endTime": 1705397400000,
    "status": "cancelled",
    "attendees": [
      {
        "email": "team@example.com",
        "responseStatus": "needsAction"
      }
    ],
    "organizer": "admin@example.com"
  }
}
```

**Result**: Event is canceled, all attendees receive cancellation notifications

### Example 2: Cancel with Retry

**Scenario**: User provides invalid event ID

**Flow**:
1. User provides: `"invalid_id"`
2. Validation fails: "The provided Event Id: invalid_id is invalid"
3. User is prompted to re-enter
4. User provides correct ID: `"abc123xyz789"`
5. Event is successfully canceled

## Business Rules

1. **Organizer Only**: Only the event organizer can cancel the event
2. **Notifications**: All attendees receive cancellation notifications via email
3. **Calendar Removal**: Event is removed from all attendees' calendars immediately
4. **Irreversible**: Canceled events cannot be restored (must create a new event)
5. **Status Update**: Event status is set to "cancelled" in Google Calendar
6. **Recurring Events**: Canceling a recurring event may require additional parameters (not currently supported)

## Limitations

1. **Organizer Permission**: Only the organizer can cancel events
2. **Irreversible**: Cannot undo cancellation
3. **Recurring Events**: Limited support for recurring event series
4. **Deleted Events**: Already deleted events cannot be canceled
5. **Past Events**: Some restrictions may apply to canceling past events
6. **Rate Limits**: Subject to Google Calendar API rate limits

## Best Practices

### 1. Verify Event ID
- Always verify the event ID before canceling
- Use [Get Event](pages/GetEvent.md) to confirm event details
- Double-check you have the correct event

### 2. Confirm Before Canceling
- Implement confirmation prompts in user-facing applications
- Display event details before cancellation
- Warn users that the action is irreversible

### 3. Handle Errors Gracefully
- Provide clear error messages to users
- Log cancellation attempts for audit trails
- Handle "event not found" scenarios appropriately

### 4. Notify Stakeholders
- Consider additional notifications beyond automatic emails
- Update related systems or databases
- Document the cancellation reason (if applicable)

### 5. Alternative to Canceling
- Consider rescheduling instead of canceling
- Use [Schedule Meeting](pages/ScheduleMeeting.md) to create a new event
- Preserve event history when possible

## Common Use Cases

### 1. Meeting Canceled by Organizer
```
Scenario: Organizer needs to cancel a scheduled meeting
Action: Use Cancel Event with the event ID
Result: All attendees notified, event removed from calendars
```

### 2. Duplicate Event Cleanup
```
Scenario: Duplicate events were created accidentally
Action: Cancel the duplicate event(s)
Result: Only the correct event remains
```

### 3. Event No Longer Needed
```
Scenario: Project canceled, meeting no longer necessary
Action: Cancel the event
Result: Resources freed, attendees notified
```

### 4. Rescheduling (Two-Step Process)
```
Scenario: Meeting needs to be rescheduled
Action: 
  1. Cancel the original event
  2. Create a new event with Schedule Meeting
Result: New event at different time, attendees notified
```

## Important Warnings

### ⚠️ Destructive Operation
- **Cannot be undone**: Once canceled, the event is permanently removed
- **No recovery**: Canceled events cannot be restored
- **Must recreate**: To reschedule, you must create a new event

### 📧 Automatic Notifications
- **All attendees notified**: Cancellation emails sent automatically
- **Cannot suppress**: Notifications cannot be disabled
- **Email content**: Standard Google Calendar cancellation message

### 🔒 Permission Requirements
- **Organizer only**: Only the event organizer can cancel
- **Shared calendars**: May require additional permissions
- **Delegate access**: Delegates may be able to cancel on behalf of organizer

## Related Catalog Requests

- [Schedule Meeting](pages/ScheduleMeeting.md) - Create a new event to replace the canceled one
- [Get Event](pages/GetEvent.md) - Verify event details before canceling
- [Get Next Upcoming Meetings](pages/GetNextUpcomingMeetings.md) - Find events to cancel
- [Remove Attendee](pages/RemoveAttendee.md) - Alternative: remove specific attendees instead of canceling

## Technical Implementation

### Helper Class
- **Class**: `CancelEventHelper`
- **Package**: `app.kristasoft.extensions.essentials.collaboration.google_calendar.catalog.helper`
- **Validation**: Uses `ValidationOrchestrator` for event ID validation
- **Service**: Delegates to `CalendarService.cancelEvent()`

### Validation Flow
1. Event ID parameter is collected
2. `ValidationOrchestrator.validate()` is called
3. If validation fails and Allow Retry = true:
   - `SubCatalogRequestImpl.handleCancelEvent()` is invoked
   - User is prompted to correct the event ID
   - Process repeats until valid input or user cancels
4. If validation passes:
   - `CalendarService.cancelEvent()` is called
   - Event is canceled in Google Calendar
   - Event Details are returned with status "cancelled"

### Telemetry Metrics
- `CANCEL_EVENT_REQUEST_ATTEMPT`: Total cancellation attempts
- `CANCEL_EVENT_REQUEST_SUCCESS`: Successful cancellations
- `CANCEL_EVENT_REQUEST_FAILURE`: Failed cancellations
- `CANCEL_EVENT_RETRY_ATTEMPT`: Validation retry attempts

## Troubleshooting

### "Event Not Found" Error
**Cause**: Event ID doesn't exist or event already deleted

**Solution**: 
- Verify the event ID is correct
- Check if event was already canceled
- Use Get Next Upcoming Meetings to find valid event IDs

### "Permission Denied" Error
**Cause**: User is not the event organizer

**Solution**: 
- Only the organizer can cancel events
- Contact the organizer to cancel
- Use Decline Invitation if you're an attendee

### "Event Already Canceled" Error
**Cause**: Attempting to cancel an already canceled event

**Solution**: 
- No action needed, event is already canceled
- Verify event status with Get Event

## See Also

- [Extension Configuration](pages/ExtensionConfiguration.md) - Configure retry mechanism
- [Authentication](pages/Authentication.md) - Required for canceling events
- [Schedule Meeting](pages/ScheduleMeeting.md) - Create replacement events

