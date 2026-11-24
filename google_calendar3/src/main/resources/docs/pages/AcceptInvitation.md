# Accept Invitation

## Overview

The **Accept Invitation** catalog request accepts a meeting invitation for a specific event by providing the event ID and attendee email address. This action confirms attendance and updates the event's attendee status to "accepted".

## Request Details

- **Area**: Meetings
- **Type**: CHANGE_SYSTEM
- **MCP Tool**: ✅ Yes (exposed to the Krista AI agent)
- **Retry Support**: ✅ Yes (when Allow Retry = true)

## Input Parameters

| Parameter Name | Type | Required | Description | Example |
|----------------|------|----------|-------------|---------|
| **Event ID** | Text | Yes | Unique identifier of the meeting or event to accept | "abc123xyz789" |
| **Attendee Email** | Email | Yes | Email address of the attendee accepting the invitation | "john@example.com" |

### Parameter Details

#### Event ID
- **Format**: Google Calendar event identifier string
- **Source**: Obtained from:
  - Email invitation - [Get All Unaccepted Events](pages/GetAllUnacceptedEvents.md) response
  - [Get Next Upcoming Meetings](pages/GetNextUpcomingMeetings.md) response
  - [Get Event](pages/GetEvent.md) response
- **Validation**: 
  - Cannot be null or empty
  - Must be a valid, existing event ID
  - Attendee must be invited to the event
- **Example**: `"abc123xyz789"`

#### Attendee Email
- **Format**: Valid email address (RFC 5322 compliant)
- **Validation**: 
  - Cannot be null or empty
  - Must be a valid email format
  - Must match an attendee in the event's attendee list
  - Cannot be the organizer's email (organizer is automatically accepted)
- **Example**: `"john@example.com"`
- **Note**: This should be the email of the person accepting the invitation

## Output Parameters

| Parameter Name | Type | Description |
|----------------|------|-------------|
| **Event Details** | Entity(Event Details) | Updated event details with acceptance status |

### Event Details Entity

The returned Event Details contains:

- **Event ID**: The ID of the event
- **Title**: Meeting title
- **Location**: Meeting location
- **Start Time**: Meeting start date/time
- **End Time**: Meeting end date/time
- **Description**: Meeting details
- **Attendees**: List of attendees with updated response status
  - The accepting attendee's status will be "accepted"
- **Organizer**: Email of the meeting organizer
- **HTML Link**: Link to view the event in Google Calendar

## Validation Rules

When **Allow Retry = true**, the following validations are performed:

### Event ID Validation

| Validation | Error Message | Resolution |
|------------|---------------|------------|
| Empty or null | "Please enter a valid Event Id." | Provide a non-empty event ID |
| Invalid format | "The provided Event Id: {eventId} is invalid. Please provide a valid Event Id." | Verify the event ID is correct |
| Event not found | "The provided Event Id: {eventId} is invalid. Please provide a valid Event Id." | Check that the event exists |

### Attendee Email Validation

| Validation | Error Message | Resolution |
|------------|---------------|------------|
| Empty or null | "Please enter a valid Attendee Email." | Provide a non-empty email address |
| Invalid email format | "The provided Attendee Email: {email} is invalid. Please provide a valid Attendee Email." | Use correct email format |
| Email not in event | "The provided Attendee Email: {email} is invalid. Please provide a valid Attendee Email." | Verify the email is invited to the event |

## Error Handling

### Input Errors (INPUT_ERROR)

**Cause**: Invalid input parameters

**Behavior**:
- When **Allow Retry = true**: User is prompted to re-enter invalid parameters
- When **Allow Retry = false**: Returns error immediately

**Common Scenarios**:
- Empty or null event ID or email
- Malformed email address
- Event ID doesn't exist
- Email not in attendee list

**Resolution**: Provide valid event ID and attendee email

### Logic Errors (LOGIC_ERROR)

**Cause**: Business logic errors during operation

**Error Message**: "Failed to accept invitation: Please enter valid inputs and try again with a new request"

**Common Scenarios**:
- Event already accepted
- Event has been canceled
- Attendee already responded
- Calendar service unavailable

**Resolution**: 
- Verify event status
- Check if already accepted
- Retry the operation

### System Errors (SYSTEM_ERROR)

**Cause**: Unexpected system-level errors

**Error Message**: "Error occurred while accepting invitation: {errorMessage}"

**Common Scenarios**:
- Network connectivity issues
- Google API service disruption
- Internal server errors

**Resolution**: 
- Check network connectivity
- Retry after a brief delay
- Contact support if persists

### Authorization Errors (AUTHORIZATION_ERROR)

**Cause**: Authentication or authorization failures

**Error Message**: "You must authorize to perform this operation"

**Common Scenarios**:
- User not authenticated
- Refresh token expired
- Insufficient calendar permissions

**Resolution**: 
- Re-authenticate via Authentication tab
- Grant necessary calendar permissions

## Usage Examples

### Example 1: Accept Meeting Invitation

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
    "location": "Conference Room B",
    "startTime": 1705395600000,
    "endTime": 1705397400000,
    "attendees": [
      {
        "email": "john@example.com",
        "responseStatus": "accepted"
      },
      {
        "email": "jane@example.com",
        "responseStatus": "needsAction"
      }
    ],
    "organizer": "admin@example.com"
  }
}
```

**Result**: John's response status is updated to "accepted", organizer is notified

### Example 2: Accept with Retry

**Scenario**: User provides incorrect email

**Flow**:
1. User provides: Event ID: "abc123xyz789", Email: "wrong@example.com"
2. Validation fails: "The provided Attendee Email: wrong@example.com is invalid"
3. User is prompted to re-enter
4. User provides correct email: "john@example.com"
5. Invitation is successfully accepted

## Business Rules

1. **Attendee Only**: Only invited attendees can accept invitations
2. **Organizer Notification**: Organizer receives notification of acceptance
3. **Calendar Update**: Event appears as "accepted" on attendee's calendar
4. **Response Status**: Attendee's response status changes from "needsAction" to "accepted"
5. **Multiple Accepts**: Accepting an already-accepted invitation is idempotent (no error)
6. **Email Match**: Attendee email must exactly match an email in the attendee list

## Limitations

1. **Attendee List**: Email must be in the event's attendee list
2. **Organizer**: Organizer cannot accept their own event (automatically accepted)
3. **Canceled Events**: Cannot accept canceled events
4. **Past Events**: May have restrictions on accepting past events
5. **Rate Limits**: Subject to Google Calendar API rate limits

## Best Practices

### 1. Verify Event Details First
- Use [Get Event](pages/GetEvent.md) to review event details before accepting
- Check meeting time, location, and attendees
- Ensure no scheduling conflicts

### 2. Use Correct Email
- Use the email address that received the invitation
- Verify email matches the attendee list
- Handle email aliases appropriately

### 3. Handle Already Accepted
- Check current response status before accepting
- Handle "already accepted" scenarios gracefully
- Don't treat as an error if already accepted

### 4. Provide User Feedback
- Confirm successful acceptance to user
- Display updated event details
- Show acceptance notification

### 5. Error Handling
- Enable retry mechanism for user-facing applications
- Provide clear error messages
- Log acceptance attempts for audit trails

## Common Use Cases

### 1. Accept Meeting Invitation
```
Scenario: User receives meeting invitation and wants to accept
Action: Use Accept Invitation with event ID and user's email
Result: User's calendar updated, organizer notified
```

### 2. Bulk Accept Invitations
```
Scenario: Accept multiple pending invitations
Action: 
  1. Get All Unaccepted Events
  2. For each event, use Accept Invitation
Result: All invitations accepted
```

### 3. Conditional Accept
```
Scenario: Accept only if no conflicts
Action:
  1. Get Event to check time
  2. Check for conflicts
  3. If no conflicts, Accept Invitation
Result: Smart acceptance based on availability
```

### 4. Accept on Behalf Of
```
Scenario: Admin accepts invitation for another user
Action: Use service account with user's email
Result: Invitation accepted for the specified user
```

## Response Status Flow

### Before Acceptance
```
Attendee Response Status: "needsAction"
Calendar Display: "Not responded"
```

### After Acceptance
```
Attendee Response Status: "accepted"
Calendar Display: "Accepted" or "Yes"
Organizer Notification: Sent
```

### Other Possible Statuses
- **needsAction**: No response yet (default)
- **accepted**: Invitation accepted (this operation)
- **declined**: Invitation declined (see [Decline Invitation](pages/DeclineInvitation.md))
- **tentative**: Marked as tentative (see [Tentative Invitation](pages/TentativeInvitation.md))

## Related Catalog Requests

- [Decline Invitation](pages/DeclineInvitation.md) - Decline a meeting invitation
- [Tentative Invitation](pages/TentativeInvitation.md) - Mark invitation as tentative
- [Get All Unaccepted Events](pages/GetAllUnacceptedEvents.md) - Find invitations to accept
- [Get Event](pages/GetEvent.md) - Review event details before accepting

## Technical Implementation

### Helper Class
- **Class**: `AcceptInvitationHelper`
- **Package**: `app.kristasoft.extensions.essentials.collaboration.google_calendar.catalog.helper`
- **Validation**: Uses `ValidationOrchestrator` for event ID and email validation
- **Service**: Delegates to `CalendarService.acceptInvitation()`

### Validation Flow
1. Event ID and Attendee Email parameters are collected
2. `ValidationOrchestrator.validate()` is called
3. If validation fails and Allow Retry = true:
   - `SubCatalogRequestImpl.handleAcceptInvitation()` is invoked
   - User is prompted to correct invalid inputs
   - Process repeats until valid input or user cancels
4. If validation passes:
   - `CalendarService.acceptInvitation()` is called
   - Attendee response status is updated in Google Calendar
   - Updated Event Details are returned

### Telemetry Metrics
- `ACCEPT_INVITATION_REQUEST`: Total acceptance attempts
- `ACCEPT_INVITATION_REQUEST_SUCCESS`: Successful acceptances
- `ACCEPT_INVITATION_REQUEST_FAILURE`: Failed acceptances
- `ACCEPT_INVITATION_RETRY_ATTEMPT`: Validation retry attempts

## Troubleshooting

### "Email Not in Attendee List" Error
**Cause**: Provided email is not invited to the event

**Solution**: 
- Verify the email address is correct
- Check the event's attendee list with Get Event
- Ensure you're using the email that received the invitation

### "Event Not Found" Error
**Cause**: Event ID doesn't exist or event was deleted

**Solution**: 
- Verify the event ID is correct
- Check if event was canceled or deleted
- Use Get All Unaccepted Events to find valid event IDs

### "Already Accepted" Scenario
**Cause**: Invitation was already accepted

**Solution**: 
- This is typically not an error
- Check current response status with Get Event
- No action needed if already accepted

## See Also

- [Extension Configuration](pages/ExtensionConfiguration.md) - Configure retry mechanism
- [Authentication](pages/Authentication.md) - Required for accepting invitations
- [Get All Unaccepted Events](pages/GetAllUnacceptedEvents.md) - Find pending invitations

