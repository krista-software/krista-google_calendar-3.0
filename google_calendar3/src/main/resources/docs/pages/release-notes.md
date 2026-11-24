### Release Notes - Google Calendar V3 Extension

## Version 3.1.3

- **Release Date**: November 10, 2026

### Always On reads calendars only through your workspace's delegated access

When **Always On** is ticked, the extension reads your users' Google calendars only through the
delegated access your Google Workspace administrator granted. It no longer uses anyone's personal
Google sign-in for any calendar operation. If someone connected their own calendar in the past,
their sign-in is kept but not used. When you turn Always On off, it is used again, and nobody has
to sign in a second time.

If the delegated access is not working, for example when the setup is incomplete or Google refuses
it, no calendars are read until it is fixed. The extension tells Meeting Agent why, and the reason
appears in the Meeting Agent Console. Finish the Google-side setup before you tick Always On.

### "Connected" now means the calendar can be read

In the Meeting Agent Console, a calendar shows as **Connected** only while the extension can actually
read it. It changes when that stops being true, for example when delegated access is removed for
that person, their account leaves your Google Workspace directory, or they are no longer a registered
Meeting Agent user.

The Console also shows:

- **Mailbox not found** when a registered Meeting Agent user does not exist in your Google Workspace
  directory, for example an alias or a suspended account.
- **Can't reach mailbox** when the extension tried and failed to read that calendar.

### No reconnect emails under Always On

Nobody signs in personally under Always On, so nobody is asked to reconnect a calendar. Reconnect
reminders apply only when Always On is off.

No configuration is needed. The Console updates after the next calendar check, which runs every
15 minutes.

---

## Version 3.0.7

### Added
- **"Save Configuration" catalog request.** New `Save Configuration Google Calendar` catalog request that validates the supplied connection attributes and, once valid, persists them to the invoker configuration. Accepts `clientId`, `clientSecret` (secured), `email`, `serviceUrl`, and `mode` (`Public` / `Private`); for `Private` mode both `clientId` and `clientSecret` are required, and `serviceUrl` is validated in all modes. Returns a `Response` field describing the outcome. Enables configuring the extension programmatically via the catalog instead of the settings UI.

---

## Version 3.0.6

- **Krista Infra**: 1.1.432

### Added
- **ESB namespace isolation (KIM-451).** The Conversation Agent invoker-discovery handshake (`GET /api/v1/extensions/invokers` → `connectionDetails.esb`) now carries an optional `esbNamespace`. When present, the extension sets the `broker.dest.namespace` system property during ESB bootstrap so destinations are derived as `{namespace}.{destName}-{version}-IN`, isolating a tenant's traffic on shared broker infrastructure. When absent, behavior is byte-identical to prior releases (property left unset). Activated by the krista-infra 1.1.432 bump, which ships the namespace-aware `ServiceBusConfig`.
- `GET /users` HTTP endpoint that returns every user with a currently-valid OAuth grant for the workspace this extension instance is bound to. Consumed by the conversation-agent service to seed its Meeting Agent user registry. Inherits the framework's existing inbound auth rules — no new auth header is introduced. Read-only: does not refresh tokens or mutate auth state. Filters out unknown Krista accounts via `AccountProvider.lookupAccount`. The `extensionId` field in the response is `Invoker.getInvokerId()`. See _Extension Contract — User Management & Multi-Tenancy_, §2.
- **Calendar reconnect / re-authentication flow.** When a user's stored OAuth grant becomes invalid (`invalid_grant` on token refresh), the extension now marks that user `REAUTH_REQUIRED` and publishes a `CalendarConnectionStatusChangedProto` event so the Conversation Agent can prompt the user to reconnect; a successful re-auth flips the user back to `CONNECTED`.
- **Per-user connection-status store and publisher.** New `CalendarConnectionStatusStore` persists each user's connection status (`CONNECTED` / `REAUTH_REQUIRED`, with last error code/message) and emits a `CalendarConnectionStatusChangedProto` ESB event whenever the status changes.
- **Inbound ESB consumers** (started/stopped with the invoker lifecycle via `GoogleServiceBusManager`):
  - `CalendarAuthInitiateCommandProto` → replies with `CalendarAuthInitiateReplyProto` carrying a freshly built OAuth `authUrl` (`GoogleOAuthUrlService`) so the Conversation Agent can drive the reconnect.
  - `CalendarReminderSendProto` → triggers a reminder send for the addressed user.
- Proto schema (`calendar-reconnect.proto`) registered in the Conversation Agent's wire-compatible package `app.krista.conversationagent.module.calendar.esb`.

---

## Version 3.0.5

- **Krista Service APIs Java**: 1.0.126

### What's New
- **MCP Enablement**: Set `tool = true` on all eligible catalog requests so they are exposed as tools to the Krista AI agent (WAIT_FOR_EVENT requests excluded).
- **Dependency Upgrade**: Bumped `krista-apis` and `extension-impl-anno-processors` to `1.0.126`.

---


## Version 3.0.1

- **Developer Name** : Vrushali Gaikwad
- **Krista Service APIs Java** : 1.0.120
- **Global Catalog Version** : GC-2026.02.02

## Improvements
- **Documentation Fixes**: Fixed 129 broken internal links across 14 documentation files
  - All internal markdown links now use proper `pages/` prefix for docsify navigation
  - Improved documentation navigation and user experience
- **Image Cleanup**: Removed 1 unused image from documentation (5 others already removed in previous commits)
  - setExpirationPeriod.png
  - Improved documentation load time and repository size
- **Build & Analysis**: Added Jacoco and SonarQube plugins for code quality and coverage analysis

---
