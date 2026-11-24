# Always On — Registered Meeting Agent Users

For the current Setup fields, authorization screens, and a verification checklist, start with the
[step-by-step setup guide](pages/AlwaysOnSetup.md).

## Overview

By default, Meeting Agent captures meetings only for users who have **individually connected their
own Google Calendar** through this extension. Each of those users clicked through Google's consent
screen, and the extension holds a token that lets it read *their* calendar and nothing else.

**Always On** removes that step. When it is enabled, this extension reads the calendars of **the
people you have registered in Meeting Agent** — everyone with an active, licensed Meeting Agent seat
on this workspace — whether or not they have ever opened Krista or connected anything.

> **Always On does not scan your whole Google Workspace account.** It scans the mailboxes of your
> registered, licensed Meeting Agent users, and only those. There is no tenant-wide mode and no
> setting that enables one. Somebody who is not registered in Meeting Agent is not read, however
> active their mailbox is.
>
> Enabling it also means some people **stop** being captured. Read
> [Important: who stops being captured](#important-who-stops-being-captured) before you enable it.

## Who gets captured

| | Captured with Always On **off** | Captured with Always On **on** |
|---|---|---|
| Registered, licensed Meeting Agent user who connected their own calendar | Yes | Yes |
| Registered, licensed Meeting Agent user who never connected anything | No | **Yes** |
| Someone with a Meeting Agent seat that is suspended or deregistered | No | No |
| An Observer or Delegate Admin (registered, but no licensed seat) | No | No |
| Anyone with no Meeting Agent registration at all | No | No |
| A user on a personal Google account who connected their own calendar | Yes | **No** |

The roster comes from Meeting Agent, which is where your users are registered and licensed. This
extension reads it and reconciles registered-user watches at startup and every 15 minutes thereafter,
and scans exactly that set. To add or remove somebody from Always On capture, register or deregister
them — or grant or remove their licensed seat — in Meeting Agent. There is nothing to configure here.

---

## Why Google needs a second credential

This is the question most administrators ask first, usually after being told that Microsoft 365
needs nothing beyond the app registration they already have.

The extension already holds an **OAuth client ID and secret**. Those credentials are structurally
incapable of reading somebody else's calendar: an OAuth client can only ever obtain a token *for a
user who has personally consented*. There is no Google setting that upgrades it — the limitation is
in what an OAuth client is.

Reading a colleague's calendar without asking them to consent requires a **service account with
domain-wide delegation**. That is a different kind of credential, and a Workspace super-admin has to
authorise it explicitly, naming the exact permissions it may use.

The approval step will feel familiar if you have done the Microsoft equivalent — an administrator
consenting on behalf of the organisation. The credential itself is different, which is why there are
extra fields to fill in.

Note that the permissions below are still tenant-scoped even though the scan is not: domain-wide
delegation is granted for your whole Workspace account, and this extension narrows what it reads to
your registered Meeting Agent users.

---

## What you will need

- A **Google Cloud project** (the same one that holds your OAuth client is fine).
- **Super administrator** access to the Google Workspace Admin console.
- Permission to create a service account and download its key.
- At least one **registered, licensed Meeting Agent user** whose address is an active mailbox in
  your Workspace account.

Budget about ten minutes.

---

## Step 1 — Create a service account and download its key

1. Open the [Google Cloud Console](https://console.cloud.google.com/) and select your project.
2. Go to **IAM & Admin → Service Accounts** and click **Create service account**.
3. Give it a recognisable name, for example `krista-meeting-agent`. No project roles are needed —
   leave the "Grant this service account access to project" step empty and click **Done**.
4. Open the new service account, go to the **Keys** tab, and choose **Add key → Create new key →
   JSON**. A `.json` file downloads.
5. Record the numeric **OAuth Client ID** from the service account's details or Advanced settings,
   or the JSON key's `client_id`. You need it in the next step.

> Treat the JSON file the way you would treat a password. It contains a private key. Store it in your
> password manager and delete the download once it is pasted into Krista.

### Enable the two APIs

Still in the Cloud Console, under **APIs & Services → Library**, make sure both of these are enabled
for the project:

- **Google Calendar API**
- **Admin SDK API**

---

## Step 2 — Authorise the service account (super-admin action)

This is the approval step, and it can only be done by a Google Workspace **super administrator**.

1. Open the [Google Admin console](https://admin.google.com/).
2. Go to **Security → Access and data control → API controls**.
3. Under *Domain-wide delegation*, click **Manage Domain Wide Delegation**, then **Add new**.
4. In **Client ID**, paste the service account's numeric OAuth Client ID from Step 1.
5. In **OAuth scopes**, paste exactly these three, comma-separated:

   ```
   https://www.googleapis.com/auth/calendar.readonly,https://www.googleapis.com/auth/admin.directory.user.readonly,https://www.googleapis.com/auth/admin.directory.group.readonly
   ```

6. Click **Authorise**.

> **Already set this up before?** The third scope,
> `https://www.googleapis.com/auth/admin.directory.group.readonly`, is new. If you configured Always
> On previously you authorised only the first two — **edit the existing Domain-wide delegation entry
> and add the third**, rather than creating a second one. Until you do, everything you have today
> keeps working exactly as it does; the only thing missing is
> [expanding distribution lists](#distribution-lists-and-google-groups), and the extension log says
> so once, naming the scope.

### What those scopes allow

| Scope | What it permits | What it does **not** permit |
|---|---|---|
| `calendar.readonly` | Reading calendar events, and subscribing to change notifications | Creating, editing, or deleting any event |
| `admin.directory.user.readonly` | Listing the user accounts in your Workspace account | Reading passwords or security settings; changing anything |
| `admin.directory.group.readonly` | Reading your groups and who is in them, so a meeting invited to a distribution list can be attributed to the people on it | Creating or changing a group, or changing anybody's membership |

All three are **read-only** by design. Always On never writes to a calendar or to your directory.
Actions that do write — scheduling a meeting, cancelling one, responding to an invitation — continue
to run under the individual user's own connected account, exactly as they do today.

**Why the directory scope is still needed, when the scan is not tenant-wide.** A delegated credential
can only ever act as a member of your Workspace account, so before reading a registered user's
calendar the extension confirms that their address is an active mailbox in your directory. A
registered user the directory does not list is skipped, and named in the log (see below).

> Changes to domain-wide delegation can take up to 24 hours to propagate, although they usually
> take effect sooner. If the first attempt reports that the client is not authorised, confirm the
> Client ID and scope strings and retry after propagation.

---

## Step 3 — Configure the extension in Krista

Open the Google Calendar extension's invoker in Krista Studio and fill in:

| Field | Value |
|---|---|
| **alwaysOn** | Tick it on. |
| **serviceAccountKey** | The **entire contents** of the JSON file from Step 1, pasted unmodified — from the opening `{` to the closing `}`. The field is masked; the value is stored securely and is never written to logs. |
| **tenantAdminEmail** | The email address of a Workspace administrator, for example `admin@yourcompany.com`. The service account borrows this identity to look your users up. |
| **tenantDomain** | *Optional.* Leave blank to look users up across every domain in your Workspace account. Set it to a single domain (for example `yourcompany.com`) if your registered users are all on one. |
| **serviceUrl** / **serviceApiKey** | Already set — this is how the extension reaches Meeting Agent. Always On also reads your registered users over this connection, so both must be correct. |

Validate and save the invoker. Saving initiates registered-user watch reconciliation in the
background; no restart is required. If authorization is needed, complete the
[administrator setup flow](pages/AlwaysOnSetup.md#step-3-validate-and-save-in-krista), return to
Krista, and confirm saving succeeded. Then verify discovery as described below.

---

## Verifying it worked

Open the extension's log panel and look for a line beginning `[GCAL-DISCOVERY]` after the next
sweep:

```
[GCAL-DISCOVERY] sweep=full_sync mode=ENROLLED_USERS users=42 seats=44 seatsNotInDirectory=2
alwaysOn=true
```

- `mode=ENROLLED_USERS` confirms Always On is active.
- `seats` is how many registered, licensed Meeting Agent users this workspace has.
- `users` is how many of them are being scanned.
- `seatsNotInDirectory` is how many are **not** active mailboxes in your Workspace account; each is
  named in a separate warning.

The roster itself is logged when it is read:

```
[GCAL-SEATS] workspace=ws-acme seats=44 skippedNoSeat=3 skippedNotActive=1 pages=1
```

`skippedNoSeat` counts registered users without a licensed seat (Observers and Delegate Admins);
`skippedNotActive` counts suspended or deregistered users. Neither is captured, by design.

If the discovery line instead reads `users=0 ... degraded=true`, **no** calendar was scanned on that
sweep, and the same line explains exactly what is missing and what to do about it. The most common
causes:

| What the log says | What to do |
|---|---|
| `the 'serviceAccountKey' service-account key is not configured` | Step 3 — paste the JSON key. |
| `the 'tenantAdminEmail' ... is not configured` | Step 3 — add an administrator's address. |
| `the service account's Client ID is not authorised for the required scopes` | Step 2 — check the numeric Client ID and both scope strings, then wait a few minutes for Google to propagate the change. |
| `registered-seats-unavailable` | The extension has not been able to read your registered users from Meeting Agent. Check **serviceUrl** and **serviceApiKey**, and that Meeting Agent is reachable. The next sweep retries. |
| `the Google Workspace account reported no active users` | Check **tenantDomain** — a typo, or a domain with no active accounts. Clear the field to search the whole account. |

If the log says `Always On is ON but this sweep resolved NO calendars to scan`, the extension is
working and there is simply nobody to scan: either no user on this workspace has an active, licensed
Meeting Agent seat, or none of their addresses is an active mailbox in your Workspace directory. The
same line reports both numbers.

---

## Distribution lists and Google Groups

When a meeting is sent to `engineering@acme.example`, the calendar records exactly one attendee: the
group. Nobody on that list is named. Meeting Agent decides who can see a meeting, who receives its
report and who gets an enrolment prompt from the attendee list, so without help a person invited
through a distribution list would not see the meeting at all.

With Always On configured, this extension **expands Google Groups as it reads each meeting**. Every
member is sent to Meeting Agent as an attendee in their own right, tagged with the group they were
reached through, so Meeting Agent can both include them and say *"invited via
engineering@acme.example"* rather than implying they were named personally. The group address itself
stays on the attendee list, exactly as the calendar has it.

| | |
|---|---|
| **Which addresses are expanded** | Groups on your own domain only — the `tenantDomain` you configured, or your admin's domain when that field is blank. An external address is never looked up in your directory. |
| **Who is included** | Active members with an email address, whatever their role in the group. Nested groups are flattened automatically, so a group inside a group contributes its people, not its address. |
| **Suspended members** | Excluded. |
| **Size limit** | 500 members. A larger list is truncated and the extension logs a warning naming the group; a meeting invited to a list that big is better modelled by inviting the people who actually attend. |
| **Refresh** | Group membership is cached for six hours, so a busy sweep does not re-read the same list for every meeting. |
| **A person invited both ways** | Named on the invitation *and* in the list? They stay an ordinary attendee, untagged — they really were invited personally. |

> **What is recorded is who was invited at the time.** The expansion is re-sent every time the
> meeting changes, right up until it happens, so the attendee list is a record of who was invited
> *when the meeting took place*. It does not change retroactively as a distribution list gains and
> loses people afterwards — which is the correct behaviour for something that also decides who may
> read the transcript. Asking about *current* membership is a different question, and Krista still
> answers that live when someone asks a question about the meeting.

**If the third scope is not authorised**, expansion is skipped: meetings are sent with the group
address as their only attendee, exactly as they were before this feature, and everything else
continues to work. The extension log says so once, naming the scope to add:

```
[GCAL-GROUPS] Google refused the delegated credential for reading groups ... A Google Workspace
super-admin must ADD this scope to the service account's existing Domain-wide delegation entry,
alongside the two already authorised: https://www.googleapis.com/auth/admin.directory.group.readonly
```

It retries automatically within six hours of that message, so a re-grant takes effect without a
restart.

**Without domain-wide delegation** — an Opt-In workspace that has not configured Always On — there
is no administrator identity that can be asked who is in a group, so no expansion happens. The log
says so at INFO. This is the only part of the extension that needs Always On's credentials in order
to function at all.

A successful expansion is logged per group:

```
[GCAL-GROUPS] group=engineering@acme.example members=14 skippedNested=1 skippedInactive=2 pages=1
[GCAL-GROUPS] event=abc123 groups=[engineering@acme.example] membersAdded=13 — distribution-list
members added as attendees tagged with the group they were invited through
```

---

## Important: who stops being captured

**Read this before enabling Always On.** Always On **replaces** the set of individually connected
users — it does not add to it.

When Always On is on and the roster resolves successfully, the extension scans **your registered,
licensed Meeting Agent users, and only those**. Anyone who had connected their own calendar but is
not one of them stops being captured, silently and immediately. Nothing fails and no error is raised
— their meetings simply stop appearing in Meeting Agent.

Three groups are affected, and none is unusual:

- **People on a personal Google account** (a `@gmail.com` address, or any account outside your
  Workspace domains) who connected their calendar individually. Domain-wide delegation can only ever
  act as a member of *your* Workspace account, so there is no credential that can reach them once
  Always On is on.
- **Client users** — the ones the extension tracks as `wsContact_…` keys. These are not email
  addresses at all, so there is nothing for a delegated credential to impersonate.
- **People with no licensed Meeting Agent seat**, including Observers and Delegate Admins, even
  where they connected their own calendar and are perfectly ordinary members of your Workspace
  account.

Their personal sign-ins are not used for anything while Always On is on, and in Meeting Agent their
calendars stop showing as Connected. The sign-ins are not deleted: switching Always On off picks
these people up again without anyone signing in a second time.

**If any of those calendars must keep being captured**, register and license the user in Meeting
Agent (which is also what makes their meetings billable and visible on the dashboard), or turn
Always On off.

Separately, a registered user whose address the Workspace directory does not list is reported on its
own line:

```
[GCAL-DISCOVERY] sweep=full_sync 2 registered Meeting Agent user(s) are not active mailboxes in the
Google Workspace directory and are therefore NOT scanned: [alias@acme.example, exdev@acme.example].
```

The usual causes are an address registered in Meeting Agent as an **alias** rather than the Google
account's primary email, a **suspended or archived** mailbox, and a user on a domain excluded by
**tenantDomain**.

> Always On and personal sign-ins are either/or. If Always On is *not working* — no service-account
> key, an unauthorised delegation, a Directory error, or an unreadable roster — the extension does
> **not** fall back to the people who connected their own calendar. It scans nothing on that sweep,
> says why in the log, and tells Meeting Agent the reason so it shows in the Console.

---

## What happens while it is not yet configured

Nothing is captured until it is. If Always On is switched on before the Google side is finished, the
extension scans no calendars — personal sign-ins are not used as a stand-in — and logs the reason on
every sweep until the configuration is complete. Watch channels it already holds keep being renewed,
so nothing has to be rebuilt once the configuration works. Finish the Google-side setup before
ticking Always On on a workspace that captures meetings today.

Transient failures behave the same way. If Google is briefly unreachable, or Meeting Agent is,
that sweep scans nothing and the next one retries. A roster that cannot be refreshed keeps its
previous contents rather than shrinking, so a brief Meeting Agent outage never narrows what is
captured. A single inaccessible mailbox costs that mailbox, never the rest of the workspace.

---

## Turning it off

Untick **alwaysOn**. The next sweep returns to capturing only the users who connected their own
calendars — including the ones listed in
[Important: who stops being captured](#important-who-stops-being-captured), who are picked up again.

The service-account fields can be left in place — they are ignored while Always On is off. If you
want to remove delegated access entirely, also delete the service account's entry from **Manage
Domain Wide Delegation** in the Admin console, and delete the service account key in Google Cloud.
