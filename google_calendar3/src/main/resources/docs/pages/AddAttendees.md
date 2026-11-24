# Add Attendees

## Overview

The **Add Attendees** catalog request adds one or more attendees to an existing scheduled meeting by providing the event ID and a list of attendee email addresses. This action sends invitations to the new attendees and updates the event.

## Request Details

- **Area**: Meetings
- **Type**: CHANGE_SYSTEM
- **MCP Tool**: ✅ Yes (exposed to the Krista AI agent)
- **Retry Support**: ✅ Yes (when Allow Retry = true)

## Input Parameters

| Parameter Name | Type | Required | Description | Example |
|----------------|------|----------|-------------|---------|
| **Event ID** | Text | Yes | Unique identifier of the meeting or event | "abc123xyz789" |
| **Attendee Email** | List<Email> | Yes | List of attendee email addresses to add (comma-separated) | ["new1@example.com", "new2@example.com"] |

### Parameter Details

#### Event ID
- **Format**: Google Calendar event identifier string
- **Validation**: Cannot be null or empty, must be a valid existing event ID
- **Permission**: User must be the event organizer or have edit permissions
- **Example**: `"abc123xyz789"`

#### Attendee Email
- **Format**: List of valid email addresses (RFC 5322 compliant)
- **Input**: Comma-separated email addresses
- **Validation**: 
  - Cannot be null or empty
  - Each email must be valid format
  - Emails must not already be attendees
  - Maximum 200 total attendees per event (Google Calendar limit)
- **Example**: `["john@example.com", "jane@example.com"]`

## Output Parameters

| Parameter Name | Type | Description |
|----------------|------|-------------|
| **Event Details** | Entity(Event Details) | Updated event details with new attendees |

### Event Details Entity

The returned Event Details contains:
- **Event ID**: The ID of the updated event
- **Title**: Meeting title
- **Attendees**: Complete list of attendees including newly added ones
  - New attendees will have response status "needsAction"
- **Organizer**: Email of the meeting organizer
- All other event details (location, time, description, etc.)

## Validation Rules

When **Allow Retry = true**:

### Event ID Validation
- Empty or null → "Please enter a valid Event Id."
- Invalid format → "The provided Event Id: {eventId} is invalid. Please provide a valid Event Id."
- Event not found → "The provided Event Id: {eventId} is invalid. Please provide a valid Event Id."

### Attendee Email List Validation
- Empty or null → "Please enter valid attendee email addresses (comma-separated)."
- Invalid email format → "The provided attendee email list: {emailList} contains invalid emails or emails that are already attendees. Please provide valid attendee emails."
- Already attendee → "The provided attendee email list: {emailList} contains invalid emails or emails that are already attendees. Please provide valid attendee emails."
- Too many attendees → System error if total exceeds 200

## Error Handling

### Input Errors (INPUT_ERROR)
**Cause**: Invalid event ID or email list
**Resolution**: Provide valid event ID and email addresses

### Logic Errors (LOGIC_ERROR)
**Error Message**: "Failed to add attendees: Please enter valid inputs and try again with a new request"
**Common Scenarios**: 
- Email already in attendee list
- User lacks permission to modify event
- Event has been canceled
- Maximum attendee limit reached

### System Errors (SYSTEM_ERROR)
**Error Message**: "Error occurred in addAttendees: {errorMessage}"
**Resolution**: Check network connectivity, retry after delay

### Authorization Errors
**Resolution**: Re-authenticate via Authentication tab, verify you're the organizer

## Usage Examples

### Example 1: Add Single Attendee

**Input**:
```
Event ID: "abc123xyz789"
Attendee Email: ["newperson@example.com"]
```

**Output**:
```json
{
  "Event Details": {
    "eventId": "abc123xyz789",
    "title": "Team Standup",
    "attendees": [
      {
        "email": "existing@example.com",
        "responseStatus": "accepted"
      },
      {
        "email": "newperson@example.com",
        "responseStatus": "needsAction"
      }
    ]
  }
}
```

**Result**: New attendee receives invitation email

### Example 2: Add Multiple Attendees

**Input**:
```
Event ID: "abc123xyz789"
Attendee Email: ["person1@example.com", "person2@example.com", "person3@example.com"]
```

**Result**: All three attendees receive invitations

### Example 3: Add with Retry

**Scenario**: User includes an email that's already an attendee

**Flow**:
1. User provides: Event ID + ["existing@example.com", "new@example.com"]
2. Validation fails: "contains emails that are already attendees"
3. User is prompted to re-enter
4. User provides: ["new@example.com"]
5. Attendee is successfully added

## Business Rules

1. **Organizer Permission**: Only the event organizer can add attendees (or users with edit permissions)
2. **Invitations Sent**: New attendees automatically receive email invitations
3. **Response Status**: New attendees start with "needsAction" status
4. **Event Update**: All existing attendees see the updated attendee list
5. **No Duplicates**: Cannot add emails that are already attendees
6. **Attendee Limit**: Maximum 200 attendees per event (Google Calendar limitation)
7. **Notification**: Existing attendees may receive update notifications

## Limitations

1. **Organizer Only**: Only organizer can add attendees
2. **Attendee Limit**: Maximum 200 total attendees per event
3. **No Duplicates**: Cannot add existing attendees
4. **Canceled Events**: Cannot add attendees to canceled events
5. **Rate Limits**: Subject to Google Calendar API rate limits

## Best Practices

### 1. Verify Event First
- Use [Get Event](pages/GetEvent.md) to check current attendee list
- Verify you're the organizer before attempting to add
- Check current attendee count against limit

### 2. Validate Email Addresses
- Verify email addresses are correct before adding
- Remove duplicates from your list
- Check against existing attendees

### 3. Batch Add Attendees
- Add multiple attendees in one request when possible
- Reduces API calls and improves performance
- All attendees receive invitations simultaneously

### 4. Handle Errors Gracefully
- Check for "already attendee" errors
- Provide clear feedback to users
- Log addition attempts for audit trails

### 5. Communicate Changes
- Inform existing attendees about new participants (if appropriate)
- Provide context for why attendees were added
- Consider sending a follow-up message

## Common Use Cases

### 1. Add Late Invitees
```
Scenario: Need to add someone who was missed initially
Action: Use Add Attendees with their email
Result: New attendee receives invitation
```

### 2. Expand Meeting Scope
```
Scenario: Meeting scope expanded, need more participants
Action: Add multiple attendees at once
Result: All new attendees invited
```

### 3. Add Stakeholders
```
Scenario: Additional stakeholders need to be included
Action: Get current attendees, add new ones
Result: Expanded attendee list
```

### 4. Replace Attendee
```
Scenario: One person can't attend, add replacement
Action:
  1. Remove original attendee (see Remove Attendee)
  2. Add replacement attendee
Result: Attendee list updated
```

## Important Notes

### 📧 Automatic Invitations
- **Email Sent**: New attendees automatically receive calendar invitations
- **Cannot Suppress**: Invitations cannot be disabled
- **Standard Format**: Uses Google Calendar's standard invitation template

### 👥 Attendee Visibility
- **All See Updates**: Existing attendees see the updated attendee list
- **Privacy**: All attendees can see who else is invited
- **Transparency**: Changes are visible to all participants

### ⚠️ Permission Requirements
- **Organizer**: Must be the event organizer
- **Edit Access**: Or have edit permissions on the calendar
- **Shared Calendars**: May require additional permissions

## Related Catalog Requests

- [Remove Attendee](pages/RemoveAttendee.md) - Remove attendees from the event
- [Schedule Meeting](pages/ScheduleMeeting.md) - Create new event with attendees
- [Get Event](pages/GetEvent.md) - View current attendee list
- [Cancel Event](pages/CancelEvent.md) - Cancel the entire event

## Technical Implementation

### Helper Class
- **Class**: `AddAttendeesHelper`
- **Package**: `app.kristasoft.extensions.essentials.collaboration.google_calendar.catalog.helper`
- **Validation**: Uses `ValidationOrchestrator` for event ID and email list validation
- **Service**: Delegates to `CalendarService.addAttendees()`

### Validation Flow
1. Event ID and Attendee Email List parameters are collected
2. Email list is converted to comma-separated string for validation
3. `ValidationOrchestrator.validate()` is called
4. Validates:
   - Event ID exists and is valid
   - Each email is valid format
   - Emails are not already attendees
5. If validation fails and Allow Retry = true:
   - User is prompted to correct invalid inputs
6. If validation passes:
   - `CalendarService.addAttendees()` is called
   - Attendees are added to the event in Google Calendar
   - Invitations are sent automatically
   - Updated Event Details are returned

### Telemetry Metrics
- `ADD_ATTENDEES_REQUEST`: Total add attendee attempts
- `ADD_ATTENDEES_REQUEST_SUCCESS`: Successful additions
- `ADD_ATTENDEES_REQUEST_FAILURE`: Failed additions
- `ADD_ATTENDEES_RETRY_ATTEMPT`: Validation retry attempts

## Troubleshooting

### "Already Attendee" Error
**Cause**: One or more emails are already in the attendee list

**Solution**: 
- Use Get Event to check current attendees
- Remove duplicate emails from your list
- Only add new attendees

### "Permission Denied" Error
**Cause**: User is not the event organizer

**Solution**: 
- Only the organizer can add attendees
- Verify you're the organizer with Get Event
- Contact organizer to add attendees

### "Attendee Limit Exceeded" Error
**Cause**: Total attendees would exceed 200

**Solution**: 
- Check current attendee count
- Remove some attendees before adding new ones
- Consider creating a separate event

### "Event Not Found" Error
**Cause**: Event ID doesn't exist or event was deleted

**Solution**: 
- Verify the event ID is correct
- Check if event was canceled or deleted
- Use Get Next Upcoming Meetings to find valid events

## See Also

- [Extension Configuration](pages/ExtensionConfiguration.md) - Configure retry mechanism
- [Authentication](pages/Authentication.md) - Required for modifying events
- [Remove Attendee](pages/RemoveAttendee.md) - Remove attendees from events
- [Schedule Meeting](pages/ScheduleMeeting.md) - Create events with initial attendees

