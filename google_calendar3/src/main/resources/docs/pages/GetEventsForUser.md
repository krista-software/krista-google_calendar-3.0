# Get Events For User

## Overview

The **Get Events For User** catalog request retrieves all calendar events for a specific user within a specified date range. This operation requires service account authentication with domain-wide delegation to access another user's calendar.

## Request Details

- **Area**: Meetings
- **Type**: CHANGE_SYSTEM
- **Retry Support**: ✅ Yes (when Allow Retry = true)

## ⚠️ Important Requirements

### Service Account Required
- **Authentication Type**: Must use **Service Account** authentication
- **Domain-Wide Delegation**: Must be enabled in Google Workspace
- **Admin Setup**: Requires Google Workspace admin configuration
- **Cannot Use**: User account authentication will not work

See [Authentication](pages/Authentication.md) for service account setup details.

## Input Parameters

| Parameter Name | Type | Required | Description | Example |
|----------------|------|----------|-------------|---------|
| **User Email** | Email | Yes | Email address of the user whose events to retrieve | "john@example.com" |
| **From Date** | Date/Time | Yes | Start date/time of the range (Unix timestamp in milliseconds) | 1705395600000 |
| **To Date** | Date/Time | Yes | End date/time of the range (Unix timestamp in milliseconds) | 1705482000000 |

### Parameter Details

#### User Email
- **Format**: Valid email address (RFC 5322 compliant)
- **Validation**: Cannot be null or empty, must be valid email format
- **Domain**: Must be in the same Google Workspace domain (for domain-wide delegation)
- **Example**: `"john@example.com"`

#### From Date
- **Format**: Unix timestamp in milliseconds
- **Validation**: Cannot be null, must be a valid timestamp
- **Example**: `1705395600000` (January 16, 2024 09:00:00 GMT)

#### To Date
- **Format**: Unix timestamp in milliseconds
- **Validation**: Cannot be null, must be a valid timestamp, must be after From Date
- **Example**: `1705482000000` (January 17, 2024 09:00:00 GMT)

## Output Parameters

| Parameter Name | Type | Description |
|----------------|------|-------------|
| **Event Details** | List<Entity(Event Details)> | List of events for the user within the date range |

### Event Details List

Each Event Details entity contains:
- **Event ID**: Unique identifier
- **Title**: Meeting title
- **Location**: Meeting location
- **Start Time**: Meeting start date/time
- **End Time**: Meeting end date/time
- **Description**: Meeting details
- **Attendees**: List of attendees with response status
- **Organizer**: Meeting organizer
- **HTML Link**: Link to view in Google Calendar
- **Hangout Link**: Google Meet link (if available)

## Validation Rules

When **Allow Retry = true**:

### User Email Validation
- Empty or null → "Please enter a valid User Email."
- Invalid email format → "The provided User Email: {email} is invalid. Please provide a valid User Email."

### From Date Validation
- Empty or null → "Please enter a valid From Date."
- Invalid timestamp → "The provided From Date is invalid. Please provide a valid timestamp."

### To Date Validation
- Empty or null → "Please enter a valid To Date."
- Invalid timestamp → "The provided To Date is invalid. Please provide a valid timestamp."
- Before From Date → "To Date must be after From Date. Please provide a valid date range."

## Error Handling

### Input Errors (INPUT_ERROR)
**Cause**: Invalid user email or date range
**Resolution**: Provide valid email and date timestamps

### Logic Errors (LOGIC_ERROR)
**Error Message**: "Failed to get events for user: Please enter valid inputs and try again with a new request"
**Common Scenarios**: 
- User's calendar not found (404 error)
- Insufficient permissions to access calendar (403 error)
- User not in the same domain
- Calendar access denied

### System Errors (SYSTEM_ERROR)
**Error Message**: "Error occurred in getEventsForUser: {errorMessage}"
**Resolution**: Check network connectivity, verify service account setup

### Authorization Errors
**Error Message**: "You must authorize to perform this operation" or "Insufficient permissions"
**Resolution**: 
- Verify service account authentication is configured
- Enable domain-wide delegation
- Grant necessary calendar scopes

## Usage Examples

### Example 1: Get User's Events for One Day

**Input**:
```
User Email: "john@example.com"
From Date: 1705395600000  (Jan 16, 2024 00:00:00)
To Date: 1705482000000    (Jan 17, 2024 00:00:00)
```

**Output**:
```json
{
  "Event Details": [
    {
      "eventId": "event1",
      "title": "Team Standup",
      "startTime": 1705395600000,
      "endTime": 1705397400000,
      "organizer": "manager@example.com",
      "attendees": [
        {
          "email": "john@example.com",
          "responseStatus": "accepted"
        }
      ]
    },
    {
      "eventId": "event2",
      "title": "Client Meeting",
      "startTime": 1705410000000,
      "endTime": 1705413600000,
      "organizer": "john@example.com"
    }
  ]
}
```

### Example 2: Get User's Events for One Week

**Input**:
```
User Email: "jane@example.com"
From Date: 1705395600000  (Jan 16, 2024)
To Date: 1706000400000    (Jan 23, 2024)
```

**Result**: Returns all events for the user during that week

### Example 3: Validation Retry

**Scenario**: User provides invalid date range

**Flow**:
1. User provides: To Date before From Date
2. Validation fails: "To Date must be after From Date"
3. User is prompted to re-enter
4. User provides correct date range
5. Events are successfully retrieved

## Business Rules

1. **Service Account Only**: Requires service account with domain-wide delegation
2. **Date Range Inclusive**: Includes events that start or end within the range
3. **Chronological Order**: Events returned in chronological order
4. **All Event Types**: Includes meetings, appointments, all-day events
5. **Recurring Events**: Includes individual instances of recurring events
6. **Privacy Respected**: Respects user's calendar privacy settings
7. **Same Domain**: User must be in the same Google Workspace domain

## Limitations

1. **Service Account Required**: Cannot use user account authentication
2. **Domain-Wide Delegation**: Must be enabled by Google Workspace admin
3. **Same Domain Only**: Can only access users in the same Google Workspace domain
4. **Calendar Access**: User's calendar must be accessible
5. **Rate Limits**: Subject to Google Calendar API rate limits
6. **Date Range**: Very large date ranges may be slow or timeout

## Best Practices

### 1. Verify Service Account Setup
- Ensure service account is properly configured
- Verify domain-wide delegation is enabled
- Test with a known user first

### 2. Use Reasonable Date Ranges
- Don't request excessively large date ranges
- Consider pagination for large results
- Use specific date ranges for better performance

### 3. Handle Calendar Access Errors
- Check for 404 (calendar not found) errors
- Handle 403 (permission denied) errors
- Provide clear error messages to users

### 4. Respect Privacy
- Only access calendars when necessary
- Follow organizational privacy policies
- Log access for audit purposes

### 5. Error Handling
- Enable retry mechanism for user-facing applications
- Handle domain-wide delegation errors gracefully
- Provide clear feedback on permission issues

## Common Use Cases

### 1. Check User Availability
```
Scenario: Check if user is available for a meeting
Action: Get Events For User for specific date/time range
Result: View user's schedule to find free slots
```

### 2. Schedule Coordination
```
Scenario: Coordinate meeting across multiple users
Action:
  1. Get Events For User for each participant
  2. Find common free time
  3. Schedule meeting
Result: Efficient meeting scheduling
```

### 3. Calendar Reporting
```
Scenario: Generate report on user's calendar activities
Action: Get Events For User for reporting period
Result: Calendar activity report
```

### 4. Delegate Calendar Management
```
Scenario: Admin manages calendars for team
Action: Get Events For User to view team schedules
Result: Centralized calendar management
```

## Special Error Scenarios

### Calendar Not Found (404)
**Error**: "Calendar not found for user: {email}"
**Cause**: 
- User doesn't exist
- User's calendar is not accessible
- Email address is incorrect

**Solution**:
- Verify user email is correct
- Check if user exists in Google Workspace
- Ensure user has a calendar

### Insufficient Permissions (403)
**Error**: "Insufficient permissions to access calendar"
**Cause**:
- Domain-wide delegation not enabled
- Service account lacks necessary scopes
- Calendar access is restricted

**Solution**:
- Enable domain-wide delegation in Google Workspace Admin
- Grant `https://www.googleapis.com/auth/calendar.readonly` scope
- Verify service account configuration

### Domain Mismatch
**Error**: "Cannot access calendar outside domain"
**Cause**: User email is not in the same Google Workspace domain

**Solution**:
- Only access users in the same domain
- Verify user email domain matches

## Related Catalog Requests

- [Get Next Upcoming Meetings](pages/GetNextUpcomingMeetings.md) - Get your own upcoming meetings
- [Get Event](pages/GetEvent.md) - Get details of specific events
- [Schedule Meeting](pages/ScheduleMeeting.md) - Schedule meeting for the user
- [Get All Unaccepted Events](pages/GetAllUnacceptedEvents.md) - Get your own pending invitations

## Technical Implementation

### Helper Class
- **Class**: `GetEventsForUserHelper`
- **Package**: `app.kristasoft.extensions.essentials.collaboration.google_calendar.catalog.helper`
- **Validation**: Uses `ValidationOrchestrator` for email and date validation
- **Service**: Delegates to `CalendarService.getEventsForUser()`

### Validation Flow
1. User Email, From Date, and To Date parameters are collected
2. `ValidationOrchestrator.validate()` is called
3. Validates:
   - User Email is valid format
   - From Date is valid timestamp
   - To Date is valid timestamp
   - To Date is after From Date
4. If validation fails and Allow Retry = true:
   - User is prompted to correct invalid inputs
5. If validation passes:
   - `CalendarService.getEventsForUser()` is called
   - Service uses service account with domain-wide delegation
   - Events are retrieved from user's calendar
   - List of Event Details is returned

### Telemetry Metrics
- `GET_EVENTS_FOR_USER_REQUEST`: Total requests
- `GET_EVENTS_FOR_USER_REQUEST_SUCCESS`: Successful retrievals
- `GET_EVENTS_FOR_USER_REQUEST_FAILURE`: Failed retrievals
- `GET_EVENTS_FOR_USER_RETRY_ATTEMPT`: Validation retry attempts
- Event count is tracked for analytics

## Troubleshooting

### "Service Account Required" Error
**Cause**: Using user account authentication instead of service account

**Solution**: 
- Configure service account authentication
- See [Authentication](pages/Authentication.md) for setup
- Cannot use user account for this operation

### "Domain-Wide Delegation Not Enabled" Error
**Cause**: Domain-wide delegation is not configured

**Solution**: 
- Contact Google Workspace admin
- Enable domain-wide delegation for the service account
- Grant necessary calendar scopes

### "Calendar Not Found" Error
**Cause**: User's calendar doesn't exist or is inaccessible

**Solution**: 
- Verify user email is correct
- Check if user exists in Google Workspace
- Ensure user has a calendar created

### Empty Results
**Cause**: No events in the specified date range

**Solution**: 
- This is normal, not an error
- Verify date range is correct
- Check if user has events in that period

## See Also

- [Extension Configuration](pages/ExtensionConfiguration.md) - Configure service account
- [Authentication](pages/Authentication.md) - Service account setup and domain-wide delegation
- [Get Next Upcoming Meetings](pages/GetNextUpcomingMeetings.md) - Get your own meetings
- [Schedule Meeting](pages/ScheduleMeeting.md) - Schedule meetings for users

