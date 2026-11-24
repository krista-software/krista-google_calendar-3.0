---
name: phase-breakdown
description: >
  Breaks large specification documents into focused, modular phase documents optimized
  for AI-assisted implementation. Each phase becomes a self-contained unit with clear
  scope, dependencies, and implementation guidance. Use after creating a specification
  to prepare it for phased implementation via @implement skill.
---

# Phase Breakdown Skill

## Purpose

Transform a large specification into focused, modular phase documents that can be
implemented one at a time. Each phase document should be self-contained — an AI agent
should be able to implement it without reading the full specification.

## Input

- **Specification path**: Path to the specification document (typically in `docs-specs/{feature-name}/`)
- **Output path**: Same folder as the specification: `docs-specs/{feature-name}/`

## Process

### Step 1: Read and Understand the Specification

Read the entire specification and related documents:
- `specification.md` — the main design
- `ui-ux-design.md` — UI designs (if present)
- `requirements-analysis.md` — requirements traceability

Build a mental model of:
1. All components and their dependencies
2. All features and their relationships
3. Natural boundaries and seams in the design

### Step 2: Determine Partitioning Strategy

Choose the best partitioning strategy based on the specification:

| Strategy | When to Use |
|----------|------------|
| **By Feature** | Distinct user-facing features that can ship independently |
| **By Component** | Clear component boundaries with limited cross-cutting concerns |
| **By Layer** | Strong separation between data, logic, and UI layers |
| **By User Journey** | Features best understood through user workflows |
| **By Risk** | High-risk items first to de-risk early |
| **Hybrid** | Combine strategies — e.g., foundational phase first, then by feature |

**Default strategy for this full-stack project:**
1. **Phase 1: Foundation** — DB migrations, shared types/DTOs, base services (backend + frontend)
2. **Phase 2+: By Feature/Journey** — Each feature as a self-contained phase (backend API + frontend UI)
3. **Final Phase: Integration & Polish** — Cross-cutting concerns, final polish, tests

### Step 3: Define Phase Boundaries

For each phase, determine:
- **Scope**: Exactly what's included (and explicitly what's NOT)
- **Dependencies**: What must exist before this phase starts
- **Deliverables**: What artifacts this phase produces
- **Estimated Complexity**: Simple / Medium / Complex

### Step 4: Create Phase Documents

Create each phase document in the output directory:

```
output-path/
├── phase-overview.md          # Summary of all phases
├── phase-1-foundation.md      # First phase
├── phase-2-[feature].md       # Subsequent phases
├── phase-3-[feature].md
└── phase-N-integration.md     # Final phase
```

### Phase Overview Template

```markdown
# Phase Overview: [Project Name]

**Source Specification:** [path to specification.md]
**Partitioning Strategy:** [strategy chosen and why]
**Total Phases:** [N]

## Phase Dependency Graph

Phase 1: Foundation
    ↓
Phase 2: [Feature A]  ──┐
Phase 3: [Feature B]  ──┤  (parallel)
Phase 4: [Feature C]  ──┘
    ↓
Phase 5: Integration & Polish

## Phase Summary

| Phase | Name | Complexity | Dependencies | Status |
|-------|------|-----------|--------------|--------|
| 1 | Foundation | Medium | None | Not Started |
| 2 | [Feature A] | Complex | Phase 1 | Not Started |
| N | Integration | Medium | All prior | Not Started |
```

### Individual Phase Template

```markdown
# Phase [N]: [Phase Name]

**Parent Specification:** [path]
**Dependencies:** [Phase X, Phase Y]
**Complexity:** [Simple | Medium | Complex]
**Status:** Not Started

## Scope

### What's Included
- [Specific components, services, features]

### What's NOT Included (Explicitly)
- [Things that belong to other phases]
- [Things out of scope entirely]

## Context from Specification

[Extract and include ONLY the relevant sections from the main specification.
The implementer should NOT need to read the full spec.]

### Relevant Architecture
[Component diagram, data flow — only what's relevant to this phase]

### Relevant Design Decisions
[Only decisions that affect this phase]

### Relevant Interfaces/Types
[Java classes, TypeScript interfaces needed for this phase]

## Tasks

### TASK-001: [Task Name]
**Layer:** [Backend / Frontend / Full-stack]
**Component:** [Which Java class or Angular component/service]
**Description:** [What to implement]
**Files to Create/Modify:**
- `path/to/file` — [what to do]

## UI Design Reference (if applicable)

[Extract relevant wireframes and state documentation from ui-ux-design.md]

## Implementation Notes

### Backend (if applicable)
- Follow project standards docs (DO NOT VIOLATE established rules)
- Follow existing patterns for API registration, data access, and migrations

### Frontend (if applicable)
- Follow the project's component conventions
- Types: strict typing, no `any`
- Follow existing patterns for state management and styling

## Definition of Done

- [ ] All tasks completed
- [ ] All UI states handled (loading, error, empty, overflow)
- [ ] No compilation errors
- [ ] Code follows project standards
- [ ] Project builds and all tests pass (discover and run the project's build/test commands)
```

### Step 5: Validate Completeness

After creating all phase documents, verify:
1. **Full Coverage**: Every task in the specification appears in exactly one phase
2. **No Orphans**: Every phase has clear dependencies and is reachable
3. **Self-Contained**: Each phase document has enough context to implement standalone
4. **Ordered**: Dependencies form a valid DAG (no circular dependencies)

Present the phase overview to the user for approval before proceeding.

---

## Anti-Patterns to Avoid

1. **Too Many Phases** — Prefer fewer, larger phases over many tiny ones
2. **Leaky Boundaries** — If two phases constantly reference each other, merge them
3. **Missing Context** — Each phase must stand alone; don't force reading the full spec
4. **Vague Scope** — "Implement remaining features" is not a valid phase
5. **Ignoring Dependencies** — Phases that depend on unfinished work will fail
6. **Over-Specification** — Don't rewrite the spec; extract and reference relevant parts
