# Get Next Upcoming Meetings

## Overview

The **Get Next Upcoming Meetings** catalog request retrieves a specified number of upcoming meetings and events from the calendar. Provide the number of meetings to fetch, and the system will return the next scheduled events in chronological order.

## Request Details

- **Area**: Meetings
- **Type**: CHANGE_SYSTEM
- **MCP Tool**: ✅ Yes (exposed to the Krista AI agent)
- **Retry Support**: ✅ Yes (when Allow Retry = true)

## Input Parameters

| Parameter Name | Type | Required | Description | Example |
|----------------|------|----------|-------------|---------|
| **Number of meeting** | Number | Yes | Number of upcoming meetings to retrieve from the calendar | 5 |

### Parameter Details

#### Number of meeting
- **Format**: Positive whole number (integer)
- **Validation**: 
  - Cannot be null or empty
  - Must be a positive number (> 0)
  - Must be a whole number (no decimals)
  - Range: 1 to 50 meetings
- **Example**: `5`, `10`, `25`
- **Default Behavior**: Returns events starting from current time

## Output Parameters

| Parameter Name | Type | Description |
|----------------|------|-------------|
| **Event Details** | List<Entity(Event Details)> | List of upcoming event details in chronological order |

### Event Details List

Each Event Details entity in the list contains:
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

### Number of Meetings Validation

| Validation | Error Message | Resolution |
|------------|---------------|------------|
| Empty or null | "Number of meetings is required." | Provide a number |
| Not a number | "The provided number of meetings ({value}) is not a valid number. Please provide a valid number." | Use numeric value |
| Negative or zero | "The number of meetings must be a positive number. Please provide a valid number." | Use positive number |
| Out of range | "The number of meetings must be between 1 and 50. Please provide a valid number." | Use value between 1-50 |
| Decimal value | "The number of meetings must be a whole number. Please provide a valid number." | Use integer value |

## Error Handling

### Input Errors (INPUT_ERROR)
**Cause**: Invalid number of meetings parameter
**Resolution**: Provide a valid positive integer between 1 and 50

### Logic Errors (LOGIC_ERROR)
**Error Message**: "Failed to get next upcoming meetings: Please enter valid inputs and try again with a new request"
**Common Scenarios**: Calendar service unavailable, no upcoming events

### System Errors (SYSTEM_ERROR)
**Error Message**: "Error occurred in getNextUpcomingMeetings: {errorMessage}"
**Resolution**: Check network connectivity, retry after delay

### Authorization Errors
**Resolution**: Re-authenticate via Authentication tab

## Usage Examples

### Example 1: Get Next 5 Meetings

**Input**:
```
Number of meeting: 5
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
      "location": "Conference Room A"
    },
    {
      "eventId": "event2",
      "title": "Client Meeting",
      "startTime": 1705482000000,
      "endTime": 1705485600000,
      "location": "Zoom"
    },
    ...
  ]
}
```

### Example 2: Get Next 10 Meetings

**Input**:
```
Number of meeting: 10
```

**Result**: Returns up to 10 upcoming events (may return fewer if less than 10 events exist)

### Example 3: Validation Retry

**Scenario**: User provides invalid input

**Flow**:
1. User provides: `0`
2. Validation fails: "The number of meetings must be a positive number"
3. User is prompted to re-enter
4. User provides: `5`
5. Successfully retrieves 5 upcoming meetings

## Business Rules

1. **Chronological Order**: Events returned in chronological order (earliest first)
2. **Future Events Only**: Only returns events starting from current time or later
3. **All Calendars**: Includes events from all calendars the user has access to
4. **Maximum Limit**: Cannot request more than 50 events
5. **Actual Count**: May return fewer events than requested if not enough upcoming events exist
6. **All Event Types**: Includes all event types (single, recurring, all-day, etc.)

## Limitations

1. **Maximum Count**: Cannot retrieve more than 50 events in one request
2. **Future Only**: Only returns future events (not past events)
3. **No Filtering**: Returns all upcoming events (cannot filter by type, calendar, etc.)
4. **Rate Limits**: Subject to Google Calendar API rate limits
5. **Time Range**: Searches within a reasonable future time range (typically several months)

## Best Practices

### 1. Request Appropriate Count
- Request only the number of events you need
- Smaller requests are faster and more efficient
- Consider pagination for large lists

### 2. Handle Empty Results
- Check if the returned list is empty
- Provide appropriate feedback when no upcoming events
- Handle cases where fewer events are returned than requested

### 3. Display Chronologically
- Events are already sorted chronologically
- Display in the order received
- Show clear date/time information

### 4. Cache Results
- Cache results to reduce API calls
- Refresh cache periodically
- Invalidate cache when events are modified

### 5. Error Handling
- Enable retry mechanism for user-facing applications
- Provide clear error messages
- Handle network failures gracefully

## Common Use Cases

### 1. Dashboard Display
```
Scenario: Show upcoming meetings on dashboard
Action: Get Next 5-10 Meetings
Result: Display upcoming schedule
```

### 2. Daily Agenda
```
Scenario: Show today's and tomorrow's meetings
Action: Get Next Upcoming Meetings (appropriate count)
Result: Daily/weekly agenda view
```

### 3. Meeting Preparation
```
Scenario: Prepare for upcoming meetings
Action: Get Next 3 Meetings
Result: Review and prepare for immediate meetings
```

### 4. Schedule Overview
```
Scenario: View overall schedule
Action: Get Next 20-50 Meetings
Result: Comprehensive schedule view
```

## Response Characteristics

### Event Ordering
- **Primary Sort**: Start time (ascending)
- **Secondary Sort**: Creation time (for events at same time)
- **Consistent**: Order is deterministic and consistent

### Event Types Included
- **Single Events**: One-time meetings
- **Recurring Events**: Individual instances of recurring series
- **All-Day Events**: Full-day events
- **Multi-Day Events**: Events spanning multiple days

### What's Excluded
- **Past Events**: Events that have already ended
- **Canceled Events**: Events with status "cancelled"
- **Declined Events**: Events you've declined (may be included depending on calendar settings)

## Related Catalog Requests

- [Get Event](pages/GetEvent.md) - Get details of a specific event from the list
- [Get All Unaccepted Events](pages/GetAllUnacceptedEvents.md) - Get only pending invitations
- [Accept Invitation](pages/AcceptInvitation.md) - Accept events from the list
- [Cancel Event](pages/CancelEvent.md) - Cancel events from the list

## Technical Implementation

### Helper Class
- **Class**: `GetNextUpcomingMeetingsHelper`
- **Package**: `app.kristasoft.extensions.essentials.collaboration.google_calendar.catalog.helper`
- **Validation**: Uses `ValidationOrchestrator` for number validation
- **Service**: Delegates to `CalendarService.getNextUpcomingMeetings()`

### Validation Flow
1. Number of meetings parameter is collected
2. `ValidationOrchestrator.validate()` is called
3. Validates:
   - Value is a number
   - Value is positive
   - Value is a whole number
   - Value is within range (1-50)
4. If validation fails and Allow Retry = true:
   - User is prompted to correct the input
5. If validation passes:
   - `CalendarService.getNextUpcomingMeetings()` is called
   - Events are retrieved from Google Calendar
   - List of Event Details is returned

### Telemetry Metrics
- `GET_NEXT_UPCOMING_MEETINGS_REQUEST`: Total requests
- `GET_NEXT_UPCOMING_MEETINGS_REQUEST_SUCCESS`: Successful retrievals
- `GET_NEXT_UPCOMING_MEETINGS_REQUEST_FAILURE`: Failed retrievals
- `GET_NEXT_UPCOMING_MEETINGS_RETRY_ATTEMPT`: Validation retry attempts
- Event count is tracked for analytics

## Troubleshooting

### "Invalid Number" Error
**Cause**: Input is not a valid number

**Solution**: 
- Provide a numeric value
- Avoid text or special characters
- Use whole numbers only

### "Out of Range" Error
**Cause**: Number is less than 1 or greater than 50

**Solution**: 
- Use a value between 1 and 50
- For more events, make multiple requests

### "No Events Found"
**Cause**: No upcoming events in calendar

**Solution**: 
- This is not an error, just no events scheduled
- Check calendar for future events
- Verify calendar access permissions

### Empty or Fewer Results
**Cause**: Fewer upcoming events than requested

**Solution**: 
- This is normal behavior
- Check the count of returned events
- Handle variable-length results

## Performance Considerations

### Request Size
- **Small Requests (1-10)**: Very fast, minimal overhead
- **Medium Requests (11-25)**: Good performance
- **Large Requests (26-50)**: Slower, more data transfer

### Optimization Tips
- Request only what you need
- Cache results when appropriate
- Use pagination for large lists
- Consider time-based filtering (if available)

## See Also

- [Extension Configuration](pages/ExtensionConfiguration.md) - Configure retry mechanism
- [Authentication](pages/Authentication.md) - Required for accessing calendar
- [Get Event](pages/GetEvent.md) - Get details of specific events
- [Get All Unaccepted Events](pages/GetAllUnacceptedEvents.md) - Get pending invitations only

