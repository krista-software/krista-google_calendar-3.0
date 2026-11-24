---
description: >
  Core orchestration rule. Defines persona routing, quality gates, and validation routines.
  Always active for all development work in this workspace.
---

# Development Orchestration Guide

## Persona Routing

Adopt the appropriate specialist persona based on the task type. Each persona has a
dedicated rule file that activates automatically via model decision.

| Task Type | Persona | Rule File |
|-----------|---------|----------|
| Frontend components, services, UI features, frontend bugs | **Frontend Engineer** | `rules/frontend-engineer.md` |
| Backend services, REST endpoints, DB migrations, backend bugs | **Backend Engineer** | `rules/backend-engineer.md` |
| Architecture & system design | **System Architect** | `rules/system-architect.md` |
| Architecture/design review | **CTO Reviewer** | `rules/cto-reviewer.md` |
| Code review against requirements | **Code Reviewer** | `rules/code-reviewer.md` |
| Documentation | **Docs Writer** | `rules/docs-writer.md` |
| UX evaluation & fixes | **UX Critic** | `rules/ux-critic.md` |

When multiple personas are relevant, adopt the primary and draw from secondary ones as needed.

## Key Project References — Read Before Code Changes

Before making code changes, **explore the codebase** to discover:
- Project README, build files (e.g., `build.gradle`, `package.json`, `pom.xml`)
- Any project-level standards doc (e.g., `CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING.md`)
- Architecture and testing documentation in `docs/` if present
- Existing code patterns and conventions

## Documentation Folder Policy

| Folder | Purpose | Who Writes |
|--------|---------|-----------|
| `docs/` | Authoritative project documentation | Humans — agents read only (unless user asks) |
| `docs-specs/` | AI-generated artifacts (specs, reviews, brainstorms, progress) | AI agents via skills |

**All skill outputs** (brainstorms, specs, phase breakdowns, progress reports) go to `docs-specs/{feature-name}/`.

## Quality Gates (Non-Negotiable)

Before claiming any task complete:
1. All requirements addressed
2. Edge cases handled
3. Error handling is comprehensive
4. Code compiles without errors
5. All existing tests still pass
6. For UI work: all UI states handled (loading, error, empty, success, overflow)

## Definition of Done

Every assignment is only complete when **ALL** of the following are true:
1. Code compiles without errors
2. All existing tests still pass
3. No regressions — if any test was passing before and now fails, YOU must fix it
4. New code has appropriate test coverage

Do NOT say "these tests were already failing" — if tests fail, fix them.

**Discover the project's build and test commands** from build files (`build.gradle`, `package.json`, `Makefile`, etc.) and run them.

## Post-Task Validation (MANDATORY)

After completing ALL code changes, validate by running the project's build and test commands.
Discover these commands from the project's build configuration files.

If any check fails, fix before marking the task complete.

## Skill & Workflow Routing

### Skills (`@skill-name`)

| Skill | Purpose |
|-------|---------|
| `@brainstorm` | Creative ideation |
| `@spec` | Requirements → specification |
| `@360-review` | Adversarial analysis |
| `@phase-breakdown` | Break specs into implementation phases |
| `@implement` | End-to-end implementation |
| `@fix` | Fix ALL build/test failures |
| `@ux-fix` | Fix UI/UX to showroom quality |
| `@test` | Test writing |
| `@docs` | Documentation creation/updates |

### Workflows (`/slash-command`)

| Workflow | Purpose |
|----------|---------|
| `/review` | Code review for bugs, security, improvements |
| `/validate` | Run lint + test + build validation |
