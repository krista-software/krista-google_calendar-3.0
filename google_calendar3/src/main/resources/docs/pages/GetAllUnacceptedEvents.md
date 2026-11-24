# Get All Unaccepted Events

## Overview

The **Get All Unaccepted Events** catalog request retrieves a list of all meeting invitations that have not yet been accepted, declined, or responded to. This provides an overview of pending meeting invitations requiring attention.

## Request Details

- **Area**: Meetings
- **Type**: CHANGE_SYSTEM
- **MCP Tool**: ✅ Yes (exposed to the Krista AI agent)
- **Retry Support**: ❌ No (no input parameters to validate)

## Input Parameters

**None** - This request requires no input parameters.

## Output Parameters

| Parameter Name | Type | Description |
|----------------|------|-------------|
| **Event Details** | List<Entity(Event Details)> | List of unaccepted event details |

### Event Details List

Each Event Details entity in the list contains:
- **Event ID**: Unique identifier (use for accepting/declining)
- **Title**: Meeting title
- **Location**: Meeting location
- **Start Time**: Meeting start date/time
- **End Time**: Meeting end date/time
- **Description**: Meeting details
- **Attendees**: List of attendees with response status
  - Your status will be "needsAction"
- **Organizer**: Meeting organizer
- **HTML Link**: Link to view in Google Calendar
- **Hangout Link**: Google Meet link (if available)

## What Events Are Included

### Included Events
- **needsAction Status**: Events where your response status is "needsAction"
- **No Response**: Events you haven't responded to yet
- **Future Events**: Upcoming events requiring response
- **All Calendars**: From all calendars you have access to

### Excluded Events
- **Accepted**: Events you've already accepted
- **Declined**: Events you've already declined
- **Tentative**: Events you've marked as tentative
- **Past Events**: Events that have already occurred
- **Canceled**: Events that have been canceled

## Error Handling

### System Errors (SYSTEM_ERROR)
**Error Message**: "Error occurred in getAllUnacceptedEvents: {errorMessage}"
**Common Scenarios**: 
- Network connectivity issues
- Google API service disruption
- Calendar access problems

**Resolution**: 
- Check network connectivity
- Retry after a brief delay
- Verify calendar permissions

### Authorization Errors
**Error Message**: "You must authorize to perform this operation"
**Resolution**: Re-authenticate via Authentication tab

## Usage Example

**Input**: None (no parameters required)

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
          "email": "you@example.com",
          "responseStatus": "needsAction"
        }
      ]
    },
    {
      "eventId": "event2",
      "title": "Project Review",
      "startTime": 1705482000000,
      "endTime": 1705485600000,
      "organizer": "lead@example.com",
      "attendees": [
        {
          "email": "you@example.com",
          "responseStatus": "needsAction"
        }
      ]
    }
  ]
}
```

## Business Rules

1. **Response Status**: Only returns events with "needsAction" status
2. **Future Events**: Typically only includes future events
3. **All Calendars**: Searches across all accessible calendars
4. **No Limit**: Returns all unaccepted events (no pagination)
5. **Chronological Order**: Events returned in chronological order
6. **Read-Only**: Does not modify any events

## Limitations

1. **No Filtering**: Cannot filter by date range, organizer, or other criteria
2. **No Pagination**: Returns all results at once (may be large)
3. **No Retry Support**: No input validation or retry mechanism
4. **Rate Limits**: Subject to Google Calendar API rate limits

## Best Practices

### 1. Regular Polling
- Check for unaccepted events periodically
- Don't poll too frequently (respect rate limits)
- Consider caching results

### 2. Handle Empty Results
- Empty list means no pending invitations
- Provide appropriate user feedback
- Don't treat as an error

### 3. Process Responses
- Use returned Event IDs to accept/decline
- Process events in chronological order
- Prioritize urgent or near-term events

### 4. Display Clearly
- Show event details clearly
- Highlight urgent or soon-starting events
- Provide easy accept/decline actions

### 5. Error Handling
- Handle network failures gracefully
- Retry on transient errors
- Log failures for debugging

## Common Use Cases

### 1. Pending Invitations Dashboard
```
Scenario: Show all pending meeting invitations
Action: Get All Unaccepted Events
Result: Display list of invitations requiring response
```

### 2. Automated Response Workflow
```
Scenario: Automatically process invitations based on rules
Action:
  1. Get All Unaccepted Events
  2. Apply business rules
  3. Accept or Decline based on rules
Result: Automated invitation management
```

### 3. Reminder System
```
Scenario: Remind user of pending invitations
Action:
  1. Get All Unaccepted Events
  2. Check count
  3. Send reminder if count > 0
Result: User notified of pending responses
```

### 4. Bulk Accept/Decline
```
Scenario: Respond to multiple invitations at once
Action:
  1. Get All Unaccepted Events
  2. Display to user
  3. User selects accept/decline for each
  4. Process responses
Result: Efficient bulk response handling
```

## Response Characteristics

### Event Ordering
- **Chronological**: Ordered by start time (earliest first)
- **Consistent**: Order is deterministic

### Response Status
- **All "needsAction"**: Every event in the list has "needsAction" status
- **Your Status**: Shows your response status, not other attendees'

### Count Variability
- **Zero Events**: Possible if no pending invitations
- **Many Events**: Could return large list if many pending
- **Dynamic**: Count changes as you respond to invitations

## Integration with Other Requests

### Accept Invitations
```
1. Get All Unaccepted Events
2. For each event:
   - Use Accept Invitation with Event ID
3. Result: All invitations accepted
```

### Decline Invitations
```
1. Get All Unaccepted Events
2. For each event:
   - Use Decline Invitation with Event ID
3. Result: All invitations declined
```

### Mark as Tentative
```
1. Get All Unaccepted Events
2. For each event:
   - Use Tentative Invitation with Event ID
3. Result: All invitations marked tentative
```

### Get Event Details
```
1. Get All Unaccepted Events
2. For specific event:
   - Use Get Event with Event ID
3. Result: Detailed information for decision-making
```

## Related Catalog Requests

- [Accept Invitation](pages/AcceptInvitation.md) - Accept events from the list
- [Decline Invitation](pages/DeclineInvitation.md) - Decline events from the list
- [Tentative Invitation](pages/TentativeInvitation.md) - Mark events as tentative
- [Get Event](pages/GetEvent.md) - Get details of specific events
- [Get Next Upcoming Meetings](pages/GetNextUpcomingMeetings.md) - Get all upcoming events (not just unaccepted)

## Technical Implementation

### Catalog Request Method
- **Class**: `MeetingsArea`
- **Method**: `getAllUnacceptedEvents()`
- **Package**: `app.kristasoft.extensions.essentials.collaboration.google_calendar.catalog`
- **Service**: Delegates directly to `CalendarService.getAllUnacceptedEvents()`
- **No Helper**: No separate helper class (no validation needed)

### Implementation Flow
1. Method is called (no parameters)
2. `CalendarService.getAllUnacceptedEvents()` is called directly
3. Service queries Google Calendar API for events with "needsAction" status
4. List of Event Details is returned
5. Telemetry metrics are recorded

### Telemetry Metrics
- `GET_ALL_UNACCEPTED_EVENTS_REQUEST`: Total requests
- `GET_ALL_UNACCEPTED_EVENTS_REQUEST_SUCCESS`: Successful retrievals
- `GET_ALL_UNACCEPTED_EVENTS_REQUEST_FAILURE`: Failed retrievals
- Event count is tracked for analytics

### No Validation
- **No Input Parameters**: Nothing to validate
- **No Retry Support**: No SubCatalogRequest flow
- **Direct Execution**: Calls service directly

## Troubleshooting

### Empty Results
**Cause**: No pending invitations

**Solution**: 
- This is normal, not an error
- Means all invitations have been responded to
- Check calendar for accepted/declined events

### "Authorization Error"
**Cause**: Not authenticated or token expired

**Solution**: 
- Re-authenticate via Authentication tab
- Grant necessary calendar permissions
- Refresh access token

### "Calendar Access Error"
**Cause**: Cannot access calendar

**Solution**: 
- Verify calendar permissions
- Check if calendar exists
- Ensure proper authentication setup

### Large Result Set
**Cause**: Many pending invitations

**Solution**: 
- This is normal behavior
- Process results in batches
- Consider pagination in your application
- Respond to invitations to reduce count

## Performance Considerations

### Response Time
- **Few Events**: Very fast
- **Many Events**: Slower with large result sets
- **Network Dependent**: Depends on API response time

### Optimization Tips
- Cache results for short periods
- Don't poll too frequently
- Process results asynchronously
- Consider pagination in UI

## See Also

- [Authentication](pages/Authentication.md) - Required for accessing calendar
- [Accept Invitation](pages/AcceptInvitation.md) - Accept pending invitations
- [Decline Invitation](pages/DeclineInvitation.md) - Decline pending invitations
- [Get Next Upcoming Meetings](pages/GetNextUpcomingMeetings.md) - Get all upcoming events

