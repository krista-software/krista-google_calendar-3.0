# Jira Management Skill

## Purpose
Manage Jira tickets with a review-first workflow. All tickets are drafted in markdown files for user approval before creation in Jira.

## When to Use
- User explicitly requests Jira ticket creation
- During feature planning when tickets need to be tracked
- When bugs or tasks need formal tracking
- ANY time Jira interaction is needed — always route through this skill

## Core Principles

### 1. Review-First Workflow
**NEVER create Jira tickets directly without user approval.**

All tickets follow this flow:
1. Draft ticket in `.md` file under appropriate feature folder in `docs-specs/`
2. User reviews the draft
3. User explicitly approves
4. Only then create in Jira with exact same details

### 2. File Location Strategy
**CRITICAL: Always use `docs-specs/` folder, NEVER `docs/` folder.**

- Feature-specific tickets: `docs-specs/<feature-name>/jira/<ticket-type>-<short-name>.md`
- General project tickets: `docs-specs/jira/<ticket-type>-<short-name>.md`
- Examples:
  - `docs-specs/external-storage/jira/story-feature-hardening.md`
  - `docs-specs/jira/epic-production-readiness.md`

**Why:** The `docs/` folder is for user-created documentation only. All AI agent work goes in `docs-specs/`.

### 3. Ticket Draft Format

#### For Story / Improvement
```markdown
# [DRAFT] Story: <Title>

**Status:** DRAFT — Awaiting Approval  
**Project:** TBD (to be selected after approval)  
**Type:** Story | Improvement

---

## Title
<Concise, user-facing title>

## Description
<Write as if someone in higher management is filing this story to request this feature.>
<Describe WHAT the feature is, WHY it's needed for the product/users, and WHAT it should do.>
<Sound like a feature requirement, NOT a status report or progress update.>
<Keep it brief, precise, and non-technical. No implementation details.>

**Figma Link:** <URL if applicable, or "N/A">
<Always check memory for Figma links. If not found, ask user before drafting.>

## Sub-tasks

### 1. Architecture & Design Specs
**Status:** ✅ Complete | ⏳ Pending  
**Description:** Design and specification work for this feature.

**Spec Files:**
- `docs-specs/<feature>/specification.md`
- `docs-specs/<feature>/phase-X-Y.md`
- (list all relevant spec files)

<If specs exist, mark as Complete. If not, mark as Pending.>

### 2. <Actual Implementation Work Item>
**Status:** ✅ Complete | ⚠️ Partially Complete | ⏳ Pending  
**Description:** <Brief but detailed description of this concrete work item>

### 3. <Actual Implementation Work Item>
**Status:** ✅ Complete | ⚠️ Partially Complete | ⏳ Pending  
**Description:** <Brief but detailed description of this concrete work item>

<Continue for all sub-tasks...>

**IMPORTANT: Sub-tasks are real implementation work items — e.g., "Create Folder Tree Component",
"Create REST API endpoints", "Create Database Migration". They are NOT abstract process steps
like "Validation Pass" or "Bug Fix Bucket". Each sub-task should represent a concrete deliverable
that someone builds or creates.

Statuses:
- ✅ Complete — Work is fully done → transition to code-complete status in Jira (discovered per §4.3, with completion date)
- ⚠️ Partially Complete — Work started but not finished → transition to in-progress status in Jira (discovered per §4.3, with completion date)
- ⏳ Pending — Work not started → leave in default status**

---

## Jira Creation Payload
<This section is auto-generated when user approves>
```

#### For Task / Bug
```markdown
# [DRAFT] Task: <Title>

**Status:** DRAFT — Awaiting Approval  
**Project:** TBD (to be selected after approval)  
**Type:** Task | Bug  
**Parent:** <parent-story-key> (if applicable)

---

## Title
<Concise title>

## Description
<Detailed technical description for tasks, or reproduction steps for bugs>

## Acceptance Criteria
- [ ] Criterion 1
- [ ] Criterion 2

---

## Jira Creation Payload
<This section is auto-generated when user approves>
```

#### For Epic
```markdown
# [DRAFT] Epic: <Title>

**Status:** DRAFT — Awaiting Approval  
**Project:** TBD (to be selected after approval)  
**Type:** Epic

---

## Title
<High-level epic title>

## Description
<Strategic overview of this body of work>
<Why it matters, what business value it delivers>

## Child Stories
- Story 1: <title>
- Story 2: <title>
- Story 3: <title>

---

## Jira Creation Payload
<This section is auto-generated when user approves>
```

### 4. Dynamic Project Discovery

**Fields and statuses vary across Jira projects.** NEVER assume hardcoded field IDs, transition IDs, or status names. Always discover them dynamically for the target project.

#### 4.1 When to Run Discovery
Run discovery **once** when you first interact with a project in a session. Cache the results in memory for reuse within the same session. Re-discover if the user switches to a different project.

#### 4.2 Field Discovery
After creating the first issue in a project (or when updating an existing one), discover available fields:

1. Call `mcp0_getJiraIssue` on any issue in the project with `expand=names,schema`
2. From the response, build a field map: `{ displayName → fieldKey }`
3. Use intent-based lookup to find the right field:

| Intent | Search for (in priority order) | Fallback |
|---|---|---|
| Completion date | "Dev Complete" → "Development Complete" → "Due Date" → "Due date" | `duedate` (built-in) |
| Start date | "Dev Start" → "Development Start" → "Start Date" → "Start date" | Skip if not found |

**Rules:**
- Walk the priority list top-to-bottom. Use the **first match** found.
- If no custom field matches any name in the list, fall back to the built-in field if one exists.
- If no field is found at all, **skip that field silently** — do NOT error, do NOT ask the user.
- Store the discovered field key (e.g., `customfield_10233` or `duedate`) and reuse it for all issues in that project.

#### 4.3 Status & Transition Discovery
Statuses and transition IDs differ between projects. Discover them dynamically:

1. Call `mcp0_getTransitionsForJiraIssue` on the issue you need to transition
2. From the response, get the list of available transitions: `{ transitionName, transitionId, targetStatus }`
3. Use **intent-based matching** to find the correct transition:

| Intent | Search for transition names (in priority order) | Category constraint |
|---|---|---|
| Code complete (✅ Complete) | "Development Complete" → "Dev Complete" → "Resolved" → "Done" | Must NOT be in "Done" statusCategory (see §4.4) |
| In progress (⚠️ Partially Complete) | "Implementation" → "In Development" → "In Progress" | Must be in "In Progress" statusCategory |

**Rules:**
- Walk the priority list top-to-bottom. Use the **first match** found among available transitions.
- **Always verify the category constraint** — see §4.4 below.
- If "Done" is the only match for code-complete intent AND it falls in the "Done" statusCategory, **still use it** as last resort — but prefer any "In Progress" category match first.
- If no transition matches at all, **log a warning and skip** — do NOT guess.

#### 4.4 Developer Scope — Status Category Boundaries

**This skill is for developers. The developer's responsibility ends at code completion.**

Allowed status categories for transitions made by this skill:
- **To Do** (statusCategory: `new`) — default after creation, no action needed
- **In Progress** (statusCategory: `indeterminate`) — for active development work

Forbidden status categories — **NEVER transition into these:**
- **Done** (statusCategory: `done`) — this includes "Closed", "Verified", "Released"

**Why:** Statuses in the "Done" category (like "Closed", "Verified") are owned by QA teams after they complete testing. Developers mark work as code-complete, which is typically an "In Progress" category status (e.g., "Development Complete", "Resolved").

**Exception:** If the ONLY available transition matching the code-complete intent is in the "Done" category (e.g., a simple workflow with only "Done"), use it — but prefer "In Progress" category alternatives first.

#### 4.5 Discovery Cache Format
After discovery, mentally cache the following for the project:
```
Project: <KEY>
  completionDateField: <fieldKey or "duedate" or null>
  codeCompleteTransition: { id: "<id>", name: "<name>" }
  inProgressTransition: { id: "<id>", name: "<name>" }
```

### 5. Field Restrictions
**NEVER modify any Jira fields that are not explicitly defined in this skill.**

Allowed fields for ticket creation:
- `summary` (title)
- `description`
- `issueTypeName`
- `projectKey`
- `parent` (for sub-tasks)

Allowed fields for post-creation updates:
- Completion date field (discovered per §4.2) — **ONLY for sub-tasks marked ✅ Complete or ⚠️ Partially Complete in draft**
- Code-complete transition (discovered per §4.3) — for ✅ Complete sub-tasks, **ONLY after completion date is set**
- In-progress transition (discovered per §4.3) — for ⚠️ Partially Complete sub-tasks, **ONLY after completion date is set**

**DO NOT touch:** labels, priority, assignee, sprint, fix version, components, or any other fields unless explicitly added to this skill by the user.

### 6. Sub-task Status Transitions

**For sub-tasks marked ✅ Complete in the draft:**
1. After creating the sub-task, discover transitions (§4.3) if not already cached
2. Check if the completion date field (§4.2) is already set
3. If NOT set, set it to today's date
4. Transition the sub-task using the code-complete transition

**For sub-tasks marked ⚠️ Partially Complete in the draft:**
1. Set the completion date field to today's date
2. Transition the sub-task using the in-progress transition

**For sub-tasks marked ⏳ Pending in the draft:**
- Do nothing — leave in default/open status

### 7. Approval & Creation Workflow
1. Agent creates draft file
2. Agent tells user: "Draft ticket created at `<path>`. Review and reply 'approved' to create in Jira."
3. User reviews file, may request changes
4. User says "approved" or "create it"
5. **Agent asks user: "Is there an existing Jira story for this feature? If yes, share the link. If not, I'll create a new one."**
6a. **If existing story:** User provides link. Agent updates that story's description and creates sub-tasks under it. Preserve any existing content in the story (e.g., Figma links, requirement docs) — merge, don't replace.
6b. **If no existing story:** Agent fetches available Jira projects and asks user to select target project. User selects project. Agent creates new story.
7. Agent creates sub-tasks under the story (new or existing)
8. Agent runs project discovery (§4) then marks completed sub-tasks using discovered transitions (see §6)
9. **Agent deletes the draft file** after all Jira operations succeed

### 8. Project Mapping
**Discover available Jira projects dynamically** using the `getVisibleJiraProjects` tool.
Ask the user which project to use if multiple are available and it's not obvious from context.

## Skill Workflow

### Step 1: Understand Request
- What needs to be tracked?
- Is it Epic → Story → Tasks, or standalone?
- Which feature/area does it belong to?

### Step 2: Draft Ticket(s)
- Create `.md` file(s) in appropriate location under `docs-specs/`
- Use format specific to ticket type (Story/Improvement, Task/Bug, Epic)
- **For Stories/Improvements:**
  - Check memory for Figma links related to this feature
  - If no Figma link found in memory, ask user before drafting
  - Description must be requirements-style (WHAT/WHY), not technical (HOW)
  - Keep description brief and precise, not essay-style
  - First sub-task is always "Architecture & Design Specs"
  - If specs exist in `docs-specs/<feature>/`, list them and mark sub-task as Complete
  - If no specs exist, mark sub-task as Pending
  - All other sub-tasks must have meaningful, detailed descriptions
- Break down into Epic → Story → Tasks if needed

### Step 3: Present for Review
- Show user the file path(s)
- Summarize what was drafted
- Wait for approval

### Step 4: Check for Existing Story (after approval)
- Ask user: "Is there an existing Jira story for this feature?"
- If user provides a link/key: use that story, preserve its existing content
- If no existing story: proceed to project selection

### Step 5: Project Selection (only if creating new story)
- Call `mcp0_getVisibleJiraProjects` with cloudId
- Present list of available projects to user
- Ask user to select target project
- Wait for user's project selection

### Step 6: Create/Update Story in Jira
- **New story:** Use `mcp0_createJiraIssue` with selected project
- **Existing story:** Use `mcp0_editJiraIssue` to update description (merge with existing content, don't replace)
- Create all sub-tasks under the story using `mcp0_createJiraIssue` with `parent` field

### Step 6.5: Run Project Discovery
- After creating the first issue, run field discovery (§4.2) and cache results
- Before transitioning any issue, run transition discovery (§4.3) on that issue

### Step 7: Transition Sub-tasks by Status
- For each sub-task marked ✅ Complete in draft:
  - Check if the discovered completion date field is already set
  - If not set, set it to today's date using the discovered field key
  - Transition using the discovered code-complete transition
- For each sub-task marked ⚠️ Partially Complete in draft:
  - Set the discovered completion date field to today's date
  - Transition using the discovered in-progress transition
- **Always respect developer scope boundaries (§4.4)**

### Step 8: Cleanup
- Delete the draft `.md` file after all Jira operations succeed
- Report summary to user: story key, sub-task keys, which transitions were applied, which fields were set

### Step 9: Link Tickets
- If Epic → Stories → Tasks hierarchy, link them using `mcp0_createIssueLink`

## Examples

### Example 1: Single Story
User: "Create a story for External Storage feature hardening"

Agent:
1. Creates `docs-specs/external-storage/jira/story-feature-hardening.md`
2. Populates with details from `docs-specs/external-storage/`
3. Replies: "Draft story created at `docs-specs/external-storage/jira/story-feature-hardening.md`. Review and approve to create in Jira."

### Example 2: Epic with Sub-tasks
User: "Create epic for test automation with tasks"

Agent:
1. Creates `docs-specs/jira/epic-test-automation.md`
2. Creates `docs-specs/jira/task-playwright-setup.md`
3. Creates `docs-specs/jira/task-smoke-suite.md`
4. Replies: "Draft epic + 2 tasks created. Review files in `docs-specs/jira/`. Approve to create in Jira."

## Anti-Patterns (DO NOT DO)
- ❌ Creating Jira tickets without draft review
- ❌ Skipping the approval step
- ❌ Putting drafts in `docs/` folder (must use `docs-specs/`)
- ❌ Putting drafts in random locations
- ❌ Using different format than specified
- ❌ Assuming user wants immediate Jira creation
- ❌ Modifying Jira fields not defined in this skill (labels, priority, assignee, etc.)
- ❌ Transitioning sub-tasks without setting completion date first
- ❌ Hardcoding field IDs or transition IDs — always discover dynamically (§4)
- ❌ Transitioning into "Done" category statuses (Closed, Verified, Released) — those belong to QA (§4.4)
- ❌ Assuming field names are the same across projects (e.g., "Dev Complete" may not exist everywhere)
- ❌ Including Labels, Story Points, Priority, or any non-allowed fields in drafts or Jira API calls
- ❌ Creating a new story without asking if one already exists
- ❌ Replacing existing story description — always merge/preserve
- ❌ Keeping draft files after successful Jira creation

## Success Criteria
- All Jira interactions go through this skill
- 100% of tickets have draft files before creation
- User explicitly approves before Jira API calls
- Existing stories checked before creating new ones
- Fields and transitions discovered dynamically per project (§4), never hardcoded
- Completed sub-tasks have completion date set and are transitioned using code-complete transition
- Developer scope respected — no QA-owned status transitions (§4.4)
- Draft files deleted after successful Jira creation
- No Jira fields modified outside what this skill defines
