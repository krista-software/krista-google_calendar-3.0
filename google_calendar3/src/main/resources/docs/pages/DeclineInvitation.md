# Decline Invitation

## Overview

The **Decline Invitation** catalog request declines a meeting invitation for a specific event by providing the event ID and attendee email address. This action updates the attendee's response status to "declined" and notifies the organizer.

## Request Details

- **Area**: Meetings
- **Type**: CHANGE_SYSTEM
- **MCP Tool**: ✅ Yes (exposed to the Krista AI agent)
- **Retry Support**: ✅ Yes (when Allow Retry = true)

## Input Parameters

| Parameter Name | Type | Required | Description | Example |
|----------------|------|----------|-------------|---------|
| **Event ID** | Text | Yes | Unique identifier of the meeting or event to decline | "abc123xyz789" |
| **Attendee Email** | Email | Yes | Email address of the attendee declining the invitation | "john@example.com" |

### Parameter Details

#### Event ID
- **Format**: Google Calendar event identifier string
- **Validation**: Cannot be null or empty, must be a valid existing event ID
- **Example**: `"abc123xyz789"`

#### Attendee Email
- **Format**: Valid email address (RFC 5322 compliant)
- **Validation**: Cannot be null or empty, must be a valid email format, must match an attendee in the event
- **Example**: `"john@example.com"`

## Output Parameters

| Parameter Name | Type | Description |
|----------------|------|-------------|
| **Event Details** | Entity(Event Details) | Updated event details with declined status |

## Validation Rules

When **Allow Retry = true**:

### Event ID Validation
- Empty or null → "Please enter a valid Event Id."
- Invalid format → "The provided Event Id: {eventId} is invalid. Please provide a valid Event Id."

### Attendee Email Validation
- Empty or null → "Please enter a valid Attendee Email."
- Invalid email format → "The provided Attendee Email: {email} is invalid. Please provide a valid Attendee Email."
- Email not in event → "The provided Attendee Email: {email} is invalid. Please provide a valid Attendee Email."

## Error Handling

### Input Errors (INPUT_ERROR)
**Cause**: Invalid event ID or email
**Resolution**: Provide valid event ID and attendee email

### Logic Errors (LOGIC_ERROR)
**Error Message**: "Failed to decline invitation: Please enter valid inputs and try again with a new request"
**Common Scenarios**: Event canceled, email not in attendee list, calendar service unavailable

### System Errors (SYSTEM_ERROR)
**Error Message**: "Error occurred while declining invitation: {errorMessage}"
**Resolution**: Check network connectivity, retry after delay

### Authorization Errors
**Resolution**: Re-authenticate via Authentication tab

## Usage Example

**Input**:
```
Event ID: "abc123xyz789"
Attendee Email: "john@example.com"
```

**Output**:
```json
{
  "Event Details": {
    "eventId": "abc123xyz789",
    "title": "Team Standup",
    "attendees": [
      {
        "email": "john@example.com",
        "responseStatus": "declined"
      }
    ]
  }
}
```

## Business Rules

1. **Attendee Only**: Only invited attendees can decline invitations
2. **Organizer Notification**: Organizer receives notification of declination
3. **Calendar Update**: Event marked as "declined" on attendee's calendar
4. **Response Status**: Changes from "needsAction" to "declined"
5. **Event Remains**: Event stays on calendar but marked as declined
6. **Reversible**: Can later accept the same invitation

## Best Practices

1. **Verify Before Declining**: Use [Get Event](pages/GetEvent.md) to review details first
2. **Provide Reason**: Consider adding a comment when declining (if supported by your workflow)
3. **Timely Response**: Decline promptly to help organizer plan
4. **Check Alternatives**: Consider [Tentative Invitation](pages/TentativeInvitation.md) if unsure
5. **Handle Gracefully**: Don't treat already-declined as an error

## Common Use Cases

### 1. Decline Meeting Invitation
```
Scenario: User cannot attend meeting
Action: Use Decline Invitation with event ID and user's email
Result: Organizer notified, calendar updated
```

### 2. Bulk Decline
```
Scenario: Decline multiple conflicting invitations
Action: For each event, use Decline Invitation
Result: All invitations declined
```

### 3. Conditional Decline
```
Scenario: Decline if conflicts exist
Action:
  1. Get Event to check time
  2. Check for conflicts
  3. If conflicts, Decline Invitation
Result: Smart declination based on availability
```

## Response Status Flow

### Before Declination
```
Attendee Response Status: "needsAction" or "tentative"
Calendar Display: "Not responded" or "Maybe"
```

### After Declination
```
Attendee Response Status: "declined"
Calendar Display: "Declined" or "No"
Organizer Notification: Sent
Event Visibility: Remains on calendar (marked as declined)
```

## Related Catalog Requests

- [Accept Invitation](pages/AcceptInvitation.md) - Accept a meeting invitation
- [Tentative Invitation](pages/TentativeInvitation.md) - Mark invitation as tentative
- [Get All Unaccepted Events](pages/GetAllUnacceptedEvents.md) - Find invitations to decline
- [Get Event](pages/GetEvent.md) - Review event details before declining

## Technical Implementation

### Helper Class
- **Class**: `DeclineInvitationHelper`
- **Package**: `app.kristasoft.extensions.essentials.collaboration.google_calendar.catalog.helper`
- **Validation**: Uses `ValidationOrchestrator` for event ID and email validation
- **Service**: Delegates to `CalendarService.declineInvitation()`

### Telemetry Metrics
- `DECLINE_INVITATION_REQUEST`: Total declination attempts
- `DECLINE_INVITATION_REQUEST_SUCCESS`: Successful declinations
- `DECLINE_INVITATION_REQUEST_FAILURE`: Failed declinations
- `DECLINE_INVITATION_RETRY_ATTEMPT`: Validation retry attempts

## Troubleshooting

### "Email Not in Attendee List" Error
**Solution**: Verify email is invited to the event using Get Event

### "Event Not Found" Error
**Solution**: Verify event ID is correct and event hasn't been deleted

### "Already Declined" Scenario
**Solution**: No action needed if already declined

## See Also

- [Extension Configuration](pages/ExtensionConfiguration.md) - Configure retry mechanism
- [Authentication](pages/Authentication.md) - Required for declining invitations
- [Accept Invitation](pages/AcceptInvitation.md) - Accept instead of declining

