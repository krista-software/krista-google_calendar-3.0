[comment]: <> (Home page)

# Google Calendar Extension

## Overview

The **Google Calendar Extension** for Krista enables seamless integration with Google Calendar, allowing you to manage calendar events, meetings, and invitations directly from your Krista workflows. This extension provides comprehensive functionality for scheduling, updating, and managing calendar events with robust error handling and validation.

## Key Features

- **Event Management**: Create, update, cancel, and retrieve calendar events
- **Invitation Handling**: Accept, decline, or tentatively respond to meeting invitations
- **Attendee Management**: Add or remove attendees from scheduled meetings
- **Query Capabilities**: Retrieve upcoming meetings, unaccepted events, and user-specific calendars
- **Robust Validation**: Built-in input validation with retry mechanisms
- **Flexible Authentication**: Support for both service account and user-based authentication
- **Error Handling**: Comprehensive error handling with detailed feedback

## Quick Start Guide

### 1. Setup & Configuration
- [Extension Configuration](pages/ExtensionConfiguration.md) - Configure the extension with your Google Calendar credentials
- [Always On Setup Guide](pages/AlwaysOnSetup.md) - Updated Setup page, administrator authorization, screenshots, and verification
- [Creating Google App](pages/CreatingGoogleApp.md) - Step-by-step guide to create and configure your Google Cloud application

### 2. Authentication
- [Authentication Overview](pages/Authentication.md) - Understand authentication modes and setup
- [Service Account vs User Authentication](pages/Authentication.md#authentication-modes) - Choose the right authentication method

### 3. Catalog Requests

#### Event Management
- [Schedule Meeting](pages/ScheduleMeeting.md) - Create and schedule new meetings with attendees
- [Cancel Event](pages/CancelEvent.md) - Cancel scheduled meetings and notify attendees
- [Get Event](pages/GetEvent.md) - Retrieve detailed information about a specific event

#### Invitation Management
- [Accept Invitation](pages/AcceptInvitation.md) - Accept meeting invitations
- [Decline Invitation](pages/DeclineInvitation.md) - Decline meeting invitations
- [Tentative Invitation](pages/TentativeInvitation.md) - Mark invitation as tentative

#### Attendee Management
- [Add Attendees](pages/AddAttendees.md) - Add new attendees to existing meetings
- [Remove Attendee](pages/RemoveAttendee.md) - Remove attendees from scheduled meetings

#### Query Operations
- [Get Next Upcoming Meetings](pages/GetNextUpcomingMeetings.md) - Retrieve upcoming calendar events
- [Get All Unaccepted Events](pages/GetAllUnacceptedEvents.md) - List pending meeting invitations
- [Get Events for a Given User](pages/GetEventsForUser.md) - Retrieve calendar events for specific users

## Documentation Structure

This documentation is organized into the following sections:

1. **Configuration & Setup**
   - Extension configuration parameters
   - Google Cloud Console setup
   - OAuth credentials management

2. **Authentication**
   - Authentication modes (Service Account vs User)
   - Authorization flows
   - Token management

3. **Catalog Requests**
   - Individual pages for each catalog request
   - Input/output parameters
   - Error handling scenarios
   - Usage guidelines and limitations

4. **Advanced Topics**
   - Retry mechanism and validation
   - Error handling framework
   - Best practices

## Support & Resources

- **Version**: 3.1.3
- **Java Version**: Java 21
- **API Scopes**: Individual OAuth uses Google Calendar permissions; Always On uses the [three read-only delegation scopes](pages/AlwaysOnSetup.md#step-4-authorize-access-in-google-admin-console)

## Additional Resources

- [Release Notes](pages/release-notes.md) - Version history and updates
- [Google Calendar API Documentation](https://developers.google.com/calendar/api/guides/overview)
