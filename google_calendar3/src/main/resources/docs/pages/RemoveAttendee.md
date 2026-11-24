# Remove Attendee

## Overview

The **Remove Attendee** catalog request removes a specific attendee from an existing scheduled meeting by providing the event ID and attendee email address. This action removes the attendee from the event and sends them a cancellation notification.

## Request Details

- **Area**: Meetings
- **Type**: CHANGE_SYSTEM
- **MCP Tool**: ✅ Yes (exposed to the Krista AI agent)
- **Retry Support**: ✅ Yes (when Allow Retry = true)

## Input Parameters

| Parameter Name | Type | Required | Description | Example |
|----------------|------|----------|-------------|---------|
| **Event ID** | Text | Yes | Unique identifier of the meeting or event | "abc123xyz789" |
| **Attendee Email** | Email | Yes | Email address of the attendee to remove | "person@example.com" |

### Parameter Details

#### Event ID
- **Format**: Google Calendar event identifier string
- **Validation**: Cannot be null or empty, must be a valid existing event ID
- **Permission**: User must be the event organizer or have edit permissions
- **Example**: `"abc123xyz789"`

#### Attendee Email
- **Format**: Valid email address (RFC 5322 compliant)
- **Validation**: 
  - Cannot be null or empty
  - Must be valid email format
  - Must be an existing attendee in the event
  - Cannot be the organizer's email
- **Example**: `"person@example.com"`

## Output Parameters

| Parameter Name | Type | Description |
|----------------|------|-------------|
| **Event Details** | Entity(Event Details) | Updated event details without the removed attendee |

## Validation Rules

When **Allow Retry = true**:

### Event ID Validation
- Empty or null → "Please enter a valid Event Id."
- Invalid format → "The provided Event Id: {eventId} is invalid. Please provide a valid Event Id."

### Attendee Email Validation
- Empty or null → "Please enter a valid Attendee Email."
- Invalid email format → "The provided Attendee Email: {email} is invalid. Please provide a valid Attendee Email."
- Email not in event → "The provided Attendee Email: {email} is invalid. Please provide a valid Attendee Email."
- Organizer email → Cannot remove the organizer

## Error Handling

### Input Errors (INPUT_ERROR)
**Cause**: Invalid event ID or email
**Resolution**: Provide valid event ID and attendee email

### Logic Errors (LOGIC_ERROR)
**Error Message**: "Failed to remove attendee: Please enter valid inputs and try again with a new request"
**Common Scenarios**: 
- Email not in attendee list
- Attempting to remove organizer
- User lacks permission to modify event
- Event has been canceled

### System Errors (SYSTEM_ERROR)
**Error Message**: "Error occurred in removeAttendee: {errorMessage}"
**Resolution**: Check network connectivity, retry after delay

### Authorization Errors
**Resolution**: Re-authenticate via Authentication tab, verify you're the organizer

## Usage Example

**Input**:
```
Event ID: "abc123xyz789"
Attendee Email: "person@example.com"
```

**Output**:
```json
{
  "Event Details": {
    "eventId": "abc123xyz789",
    "title": "Team Standup",
    "attendees": [
      {
        "email": "remaining@example.com",
        "responseStatus": "accepted"
      }
    ]
  }
}
```

**Result**: Removed attendee receives cancellation notification

## Business Rules

1. **Organizer Permission**: Only the event organizer can remove attendees
2. **Cancellation Notification**: Removed attendee receives cancellation email
3. **Event Removal**: Event is removed from the attendee's calendar
4. **Cannot Remove Organizer**: The organizer cannot be removed
5. **Event Update**: Remaining attendees see the updated attendee list
6. **No Error if Not Found**: Removing a non-existent attendee may not error (idempotent)

## Limitations

1. **Organizer Only**: Only organizer can remove attendees
2. **Cannot Remove Organizer**: Organizer must remain on the event
3. **Canceled Events**: Cannot remove attendees from canceled events
4. **Rate Limits**: Subject to Google Calendar API rate limits

## Best Practices

### 1. Verify Before Removing
- Use [Get Event](pages/GetEvent.md) to check current attendee list
- Confirm the correct email address
- Verify you're the organizer

### 2. Communicate Removal
- Consider notifying the attendee separately
- Provide context for the removal
- Offer alternative arrangements if appropriate

### 3. Handle Errors Gracefully
- Check for "not an attendee" errors
- Provide clear feedback to users
- Log removal attempts for audit trails

### 4. Alternative to Removal
- Consider if the attendee should decline instead
- Evaluate if event should be canceled entirely
- Check if a new event should be created for subset

## Common Use Cases

### 1. Remove Declined Attendee
```
Scenario: Attendee declined, remove from event
Action: Use Remove Attendee with their email
Result: Attendee removed, receives cancellation notice
```

### 2. Correct Invitation Mistake
```
Scenario: Wrong person was invited
Action: Remove incorrect attendee
Result: Incorrect person removed from event
```

### 3. Reduce Meeting Size
```
Scenario: Meeting scope reduced, fewer participants needed
Action: Remove attendees no longer required
Result: Smaller, more focused meeting
```

### 4. Replace Attendee
```
Scenario: Replace one attendee with another
Action:
  1. Remove original attendee
  2. Add replacement (see Add Attendees)
Result: Attendee list updated
```

## Important Notes

### 📧 Automatic Cancellation
- **Email Sent**: Removed attendee receives cancellation notification
- **Cannot Suppress**: Notification cannot be disabled
- **Event Removed**: Event removed from attendee's calendar

### 👥 Visibility
- **Remaining Attendees**: See updated attendee list
- **No Notification**: Other attendees may not be notified of removal
- **Organizer Aware**: Organizer sees the change

### ⚠️ Cannot Remove Organizer
- **Organizer Required**: Every event must have an organizer
- **Transfer Ownership**: To change organizer, must create new event
- **Validation**: System prevents organizer removal

## Related Catalog Requests

- [Add Attendees](pages/AddAttendees.md) - Add attendees to the event
- [Cancel Event](pages/CancelEvent.md) - Cancel the entire event
- [Get Event](pages/GetEvent.md) - View current attendee list
- [Schedule Meeting](pages/ScheduleMeeting.md) - Create new event

## Technical Implementation

### Helper Class
- **Class**: `RemoveAttendeeHelper`
- **Package**: `app.kristasoft.extensions.essentials.collaboration.google_calendar.catalog.helper`
- **Validation**: Uses `ValidationOrchestrator` for event ID and email validation
- **Service**: Delegates to `CalendarService.removeAttendee()`

### Telemetry Metrics
- `REMOVE_ATTENDEE_REQUEST`: Total removal attempts
- `REMOVE_ATTENDEE_REQUEST_SUCCESS`: Successful removals
- `REMOVE_ATTENDEE_REQUEST_FAILURE`: Failed removals
- `REMOVE_ATTENDEE_RETRY_ATTEMPT`: Validation retry attempts

## Troubleshooting

### "Not an Attendee" Error
**Cause**: Email is not in the attendee list

**Solution**: 
- Use Get Event to check current attendees
- Verify the email address is correct
- Check if attendee was already removed

### "Cannot Remove Organizer" Error
**Cause**: Attempting to remove the event organizer

**Solution**: 
- Organizer cannot be removed
- To change organizer, create a new event
- Cancel event if organizer should not attend

### "Permission Denied" Error
**Cause**: User is not the event organizer

**Solution**: 
- Only the organizer can remove attendees
- Verify you're the organizer with Get Event
- Contact organizer to remove attendees

## See Also

- [Extension Configuration](pages/ExtensionConfiguration.md) - Configure retry mechanism
- [Authentication](pages/Authentication.md) - Required for modifying events
- [Add Attendees](pages/AddAttendees.md) - Add attendees to events
- [Cancel Event](pages/CancelEvent.md) - Cancel entire event

