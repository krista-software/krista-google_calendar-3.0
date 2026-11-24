# Tentative Invitation

## Overview

The **Tentative Invitation** catalog request marks a meeting invitation as tentative for a specific event by providing the event ID and attendee email address. This action updates the attendee's response status to "tentative", indicating uncertainty about attendance.

## Request Details

- **Area**: Meetings
- **Type**: CHANGE_SYSTEM
- **MCP Tool**: ✅ Yes (exposed to the Krista AI agent)
- **Retry Support**: ✅ Yes (when Allow Retry = true)

## Input Parameters

| Parameter Name | Type | Required | Description | Example |
|----------------|------|----------|-------------|---------|
| **Event ID** | Text | Yes | Unique identifier of the meeting or event | "abc123xyz789" |
| **Attendee Email** | Email | Yes | Email address of the attendee marking as tentative | "john@example.com" |

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
| **Event Details** | Entity(Event Details) | Updated event details with tentative status |

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
**Error Message**: "Failed to process tentative invitation: Please enter valid inputs and try again with a new request"
**Common Scenarios**: Event canceled, email not in attendee list, calendar service unavailable

### System Errors (SYSTEM_ERROR)
**Error Message**: "Error occurred while processing tentative invitation: {errorMessage}"
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
        "responseStatus": "tentative"
      }
    ]
  }
}
```

## Business Rules

1. **Attendee Only**: Only invited attendees can mark as tentative
2. **Organizer Notification**: Organizer receives notification of tentative response
3. **Calendar Update**: Event marked as "tentative" or "maybe" on attendee's calendar
4. **Response Status**: Changes to "tentative"
5. **Provisional Acceptance**: Indicates possible attendance but not confirmed
6. **Can Change**: Can later accept or decline the invitation

## When to Use Tentative

### Appropriate Scenarios
- **Uncertain Availability**: Not sure if you can attend
- **Pending Confirmation**: Waiting for approval or other commitments
- **Scheduling Conflicts**: Potential conflict with another event
- **Need More Information**: Require additional details before committing
- **Conditional Attendance**: Attendance depends on other factors

### Best Practices
1. **Communicate**: Follow up with organizer about your uncertainty
2. **Update Promptly**: Change to accept/decline once you know
3. **Check Details**: Use [Get Event](pages/GetEvent.md) to review event information
4. **Set Reminder**: Remind yourself to confirm attendance later
5. **Consider Organizer**: Help organizer plan by confirming as soon as possible

## Common Use Cases

### 1. Mark as Tentative
```
Scenario: User might attend but not certain
Action: Use Tentative Invitation with event ID and user's email
Result: Organizer notified of tentative response
```

### 2. Conditional Attendance
```
Scenario: Attendance depends on another meeting
Action:
  1. Mark as Tentative
  2. Wait for other meeting confirmation
  3. Update to Accept or Decline
Result: Organizer aware of uncertainty
```

### 3. Need More Information
```
Scenario: Need agenda or details before committing
Action:
  1. Mark as Tentative
  2. Request more information from organizer
  3. Update response once informed
Result: Placeholder response while gathering information
```

## Response Status Flow

### Before Marking Tentative
```
Attendee Response Status: "needsAction"
Calendar Display: "Not responded"
```

### After Marking Tentative
```
Attendee Response Status: "tentative"
Calendar Display: "Tentative" or "Maybe"
Organizer Notification: Sent
Event Visibility: Remains on calendar (marked as tentative)
```

### Possible Next Steps
- **Accept**: Change to "accepted" using [Accept Invitation](pages/AcceptInvitation.md)
- **Decline**: Change to "declined" using [Decline Invitation](pages/DeclineInvitation.md)
- **Keep Tentative**: Leave as tentative until event time

## Comparison with Other Responses

| Response | Meaning | Calendar Display | Commitment Level |
|----------|---------|------------------|------------------|
| **needsAction** | No response yet | Not responded | None |
| **tentative** | Maybe attending | Maybe / Tentative | Low |
| **accepted** | Will attend | Yes / Accepted | High |
| **declined** | Won't attend | No / Declined | Definite No |

## Limitations

1. **Attendee List**: Email must be in the event's attendee list
2. **Organizer**: Organizer cannot mark their own event as tentative
3. **Canceled Events**: Cannot mark canceled events as tentative
4. **Rate Limits**: Subject to Google Calendar API rate limits

## Related Catalog Requests

- [Accept Invitation](pages/AcceptInvitation.md) - Accept the invitation (upgrade from tentative)
- [Decline Invitation](pages/DeclineInvitation.md) - Decline the invitation
- [Get All Unaccepted Events](pages/GetAllUnacceptedEvents.md) - Find invitations to respond to
- [Get Event](pages/GetEvent.md) - Review event details before responding

## Technical Implementation

### Helper Class
- **Class**: `TentativeInvitationHelper`
- **Package**: `app.kristasoft.extensions.essentials.collaboration.google_calendar.catalog.helper`
- **Validation**: Uses `ValidationOrchestrator` for event ID and email validation
- **Service**: Delegates to `CalendarService.tentativeInvitation()`

### Telemetry Metrics
- `TENTATIVE_INVITATION_REQUEST`: Total tentative attempts
- `TENTATIVE_INVITATION_REQUEST_SUCCESS`: Successful tentative responses
- `TENTATIVE_INVITATION_REQUEST_FAILURE`: Failed tentative responses
- `TENTATIVE_INVITATION_RETRY_ATTEMPT`: Validation retry attempts

## Troubleshooting

### "Email Not in Attendee List" Error
**Solution**: Verify email is invited to the event using Get Event

### "Event Not Found" Error
**Solution**: Verify event ID is correct and event hasn't been deleted

### "Already Tentative" Scenario
**Solution**: No action needed if already marked as tentative

## Organizer Perspective

### What Organizers See
- **Tentative Count**: Number of tentative responses
- **Uncertainty**: Helps with planning but not a firm commitment
- **Follow-up**: May prompt organizer to follow up for confirmation

### Planning Implications
- **Headcount**: Tentative responses complicate headcount planning
- **Resources**: Difficult to allocate resources with tentative responses
- **Confirmation**: Organizers may request confirmation closer to event

## See Also

- [Extension Configuration](pages/ExtensionConfiguration.md) - Configure retry mechanism
- [Authentication](pages/Authentication.md) - Required for responding to invitations
- [Accept Invitation](pages/AcceptInvitation.md) - Confirm attendance
- [Decline Invitation](pages/DeclineInvitation.md) - Decline attendance

