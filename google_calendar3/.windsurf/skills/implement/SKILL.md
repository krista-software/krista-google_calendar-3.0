---
name: implement
description: >
  End-to-end implementation orchestration for specification phases. Reads a phase document,
  implements it following Angular best practices, performs self-review, and tracks progress.
  Use when you have a phase-breakdown ready and want to implement one or more phases
  systematically. Handles errors autonomously — only escalates strategic decisions.
---

# Implementation Orchestration Skill

## Purpose

Systematically implement specification phases with quality gates at every step.
This skill acts as a **senior engineering team** — it implements, reviews, and
fixes issues autonomously.

## Core Principles

```
1. AUTONOMOUS EXECUTION — Fix tactical problems without asking. Only escalate strategic decisions.
2. QUALITY GATES — Every phase must pass review before being marked complete.
3. PROGRESS TRACKING — Maintain a progress report for continuity across sessions.
4. PROJECT STANDARDS — Always discover and reference the project's standards docs before writing any code.
```

## Input

One of:
- **Phase document path**: `docs-specs/{feature-name}/phase-1-foundation.md`
- **Specification path**: `docs-specs/{feature-name}/specification.md` (implements all phases in order)
- **Progress report path**: `docs-specs/{feature-name}/progress-report.md` (resumes where we left off)

## Output

- Implemented code following project standards
- `docs-specs/{feature-name}/progress-report.md` — updated after each phase

---

## Execution Flow

### Step 0: Read Context

Before any implementation:

1. **Discover project standards** — Check for `CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING.md`, and `docs/` folder
2. **Read the phase document** — understand scope, tasks
3. **Read or create progress-report.md** — in `docs-specs/{feature-name}/`, check current status
4. **Check existing specs** — Read `docs-specs/{feature-name}/specification.md` if available
5. **Explore the codebase** — understand existing patterns, services, components in the area

### Step 1: Plan Implementation

For each task in the phase:
1. Identify files to create or modify
2. Determine the implementation order (respect dependencies)
3. Identify reusable patterns from existing codebase
4. Note any potential issues or decisions needed

Create a todo list with all tasks.

### Step 2: Implement

For each task, implement following these rules:

#### Backend
- Follow project standards docs (DO NOT VIOLATE established rules)
- Proper input validation on all endpoints
- Proper HTTP status codes
- Follow existing patterns for API documentation, data access, and logging
- Use the project's migration tool for schema changes

#### Frontend
- Follow the project's component conventions
- Proper types — NEVER `any`
- Handle ALL UI states: loading, error, empty, success, overflow
- Respect existing code style and patterns

#### For All Code
- Pass specific values, not entire objects
- Make illegal states unrepresentable
- Never swallow errors
- Accessibility: ARIA, keyboard nav, focus management

### Step 3: Self-Review (Code Reviewer lens)

Before marking the phase complete, review your own work:

1. **Requirements Traceability** — Every task addressed?
2. **Code Quality** — Follows coding standards? Proper types? No anti-patterns?
3. **Error Handling** — All failure modes handled?
4. **Accessibility** — Keyboard nav, screen reader support?
5. **UI States** — All states (loading, error, empty, etc.) implemented?
6. **Data isolation** — Proper scoping maintained (if applicable)?

### Step 4: Update Progress Report

```markdown
# Progress Report: [Project Name]

**Specification:** [path]
**Last Updated:** [date]

## Phase Status

| Phase | Name | Status | Notes |
|-------|------|--------|-------|
| 1 | Foundation | ✅ DONE | Completed [date] |
| 2 | [Feature] | 🔄 IN PROGRESS | Task 3 of 5 |
| 3 | [Feature] | ⏳ NOT STARTED | |

## Current Phase: Phase [N] — [Name]

### Tasks
| Task | Description | Status | Files |
|------|-------------|--------|-------|
| TASK-001 | [desc] | ✅ Done | path/to/file.ts |
| TASK-002 | [desc] | 🔄 In Progress | path/to/file.ts |

### Issues Encountered & Resolved
1. [Issue] → [Resolution]

### Decisions Made
1. [Decision] — [Rationale]

### Blockers (if any)
- [Blocker requiring user input]
```

---

## Autonomous Problem Resolution

### Fix Immediately (99% of issues)

| Problem | Action |
|---------|--------|
| Compilation errors | Fix immediately |
| Type errors | Fix immediately |
| Import/dependency errors | Fix immediately |
| Linting errors | Fix immediately |
| Missing error handling | Add immediately |
| Accessibility issues | Fix immediately |
| Missing UI states | Implement immediately |

### Escalate to User (1% of issues)

| Problem | Action |
|---------|--------|
| Breaking API changes | Present options, wait for decision |
| Architecture pivot needed | Present analysis, wait for decision |
| Scope expansion beyond spec | Flag and ask |
| Ambiguous requirements | Ask for clarification |
| New dependency needed | Justify and ask |

---

## Communication Style

### DON'T
```
"I found a TypeScript error. Should I fix it?"
"Should I add error handling to this service?"
```

### DO
```
"Phase 2 implementation complete. Fixed 3 type errors, added missing error
handling to UserService. Moving to self-review."

"Encountered ambiguous requirement in FR-005 — the spec says 'real-time updates'
but doesn't specify the mechanism. Options:
1. WebSocket (real-time, more complex)
2. Polling (simpler, slight delay)
Which approach do you prefer?"
```

---

## Phase Completion Criteria

A phase is DONE when:
- [ ] All tasks implemented
- [ ] All UI states handled
- [ ] No compilation errors (Java + TypeScript)
- [ ] Code follows project standards
- [ ] Self-review completed with no outstanding issues
- [ ] Progress report updated in `docs-specs/{feature-name}/progress-report.md`
- [ ] Project builds and all tests pass (discover and run the project's build/test commands)
- [ ] For visual fixes: screenshot verification (before/after)
