---
name: docs
description: >
  Creates or updates documentation for features, components, or architectural decisions.
  Focuses on end-user and developer documentation that explains WHY, teaches HOW, and
  proves with EXAMPLES. Use when you need to document a new feature, update existing docs
  after changes, or create architectural decision records.
---

# Documentation Skill

## Purpose

Create or update documentation that serves its readers — whether they're developers
using a component, architects reviewing decisions, or users learning a feature.

## Core Principles

```
1. LEAD WITH WHY — Why does this exist? What problem does it solve?
2. TEACH WITH HOW — Step-by-step, practical guidance
3. PROVE WITH EXAMPLES — Real, runnable examples from the codebase
4. RESPECT THE READER — Don't waste their time with obvious things
5. STAY CURRENT — Documentation that's wrong is worse than no documentation
```

## Input

One of:
- **Specification directory**: Path to spec docs → create feature documentation
- **Code reference**: Path to component/service → create API documentation
- **Topic**: A concept or decision to document
- **Update request**: Existing docs that need updating after code changes

## Output Location

This project uses a **two-folder documentation model**:

| Folder | Purpose | Who Writes | When to Modify |
|--------|---------|-----------|----------------|
| `docs/` | Authoritative project docs | Humans | Only when user explicitly asks |
| `docs-specs/` | AI-generated artifacts | AI agents | Default output location for all skills |

**RULE**: Never write to `docs/` unless the user explicitly requests it.

### AI-Generated Docs (`docs-specs/{feature-name}/`)

```
docs-specs/{feature-name}/
├── brainstorm.md              # @brainstorm output
├── 360-review.md              # @360-review output
├── requirements-analysis.md   # @spec Phase 1 output
├── specification.md           # @spec Phase 2 output
├── ui-ux-design.md            # @spec Phase 1.5 output (if UI)
├── cto-review.md              # CTO review output
├── phase-overview.md          # @phase-breakdown overview
├── phase-N-*.md               # @phase-breakdown individual phases
├── progress-report.md         # @implement progress tracking
└── test-plan.md               # @test plan output
```

### Human-Curated Docs (`docs/`) — Read-Only for Agents

Explore `docs/` to discover existing project documentation. Common documents include
architecture guides, testing strategies, security docs, and runbooks.
Also check for project standards docs in the root (e.g., `CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING.md`).

---

## Process

### Step 1: Understand the Subject

Before writing anything:
1. Read the relevant source code, specs, or existing docs
2. Understand WHO will read this document
3. Determine WHAT they need to know
4. Identify the appropriate document type

### Step 2: Choose Document Type

| Type | When | Location |
|------|------|----------|
| **Feature Spec** | New feature specification | `docs-specs/{feature-name}/specification.md` |
| **Requirements Analysis** | Analyzing requirements | `docs-specs/{feature-name}/requirements-analysis.md` |
| **UI/UX Design** | UI design documentation | `docs-specs/{feature-name}/ui-ux-design.md` |
| **CTO Review** | Architecture review | `docs-specs/{feature-name}/cto-review.md` |
| **Progress Report** | Implementation tracking | `docs-specs/{feature-name}/progress-report.md` |
| **Test Plan** | Test strategy for a feature | `docs-specs/{feature-name}/test-plan.md` |
| **Architecture Doc** | System-level docs (user-requested) | `docs/` (only if user asks) |
| **Update** | Existing docs need changes | Read → Modify → Verify |

### Step 3: Write Using the Appropriate Template

#### Feature Knowledge Doc Template (`knowledge/*.md`)

These are the docs AI agents read **before** making changes. Every knowledge doc must include:

```markdown
# Document Title

| Field | Value |
|-------|-------|
| **Feature** | {Feature Name} |
| **Type** | Knowledge |
| **Status** | Current |
| **Last Verified** | YYYY-MM-DD |
| **Owner** | @person-or-team |
| **AI Retrieval Tags** | tag1, tag2, tag3 |

## Summary
[2-3 sentence overview]

## Key Concepts
[Definitions and domain terms]

## How It Works
[Behavior description with diagrams where helpful]

## Invariants & Rules
[Things that must ALWAYS or NEVER be true]

## Edge Cases
[Known tricky scenarios]

## Code Locations
[File paths to key implementations]

## What This Touches
[Other features/modules affected by changes here]

## Common Change Scenarios
["If you need to do X, here's how to do it safely"]
```

#### Architecture Decision Record (ADR) Template

```markdown
# ADR-[NNN]: [Decision Title]

**Date:** [date]
**Status:** [Proposed | Accepted | Deprecated | Superseded by ADR-NNN]
**Deciders:** [who was involved]

## Context
[What is the issue we're addressing? What forces are at play?]

## Decision
[What is the change we're making?]

## Alternatives Considered

### Alternative 1: [Name]
[Description, pros, cons]

### Alternative 2: [Name]
[Description, pros, cons]

## Consequences

### Positive
- [benefit]

### Negative
- [trade-off]
```

#### Developer Guide Template

```markdown
# How to [Task]

## Prerequisites
- [What you need before starting]

## Steps

### 1. [First Step]
[Explanation with code example]

### 2. [Second Step]
[Explanation with code example]

## Common Pitfalls
- **[Pitfall]**: [How to avoid it]

## Related
- [Links to related docs]
```

### Step 4: Verify Documentation Quality

Before completing, verify:

- [ ] **Accurate** — Does the documentation match the current code?
- [ ] **Complete** — Are all public APIs, options, and behaviors documented?
- [ ] **Clear** — Can a developer new to this area understand it?
- [ ] **Actionable** — Are there runnable examples?
- [ ] **Consistent** — Does it follow existing documentation style in this project?
- [ ] **Up to date** — No references to old patterns, deprecated APIs, or removed features

### Step 5: Update Related Documentation

When creating new docs, check if existing docs need updates:
- Do related specs in `docs-specs/` need cross-references?
- Are there stale docs that should be updated or removed?
- Does the feature's progress report need updating?
- **Remember**: Only modify `docs/` if the user explicitly requests it

---

## Style Guidelines

### DO
- Use present tense: "The component renders..." not "The component will render..."
- Use active voice: "Call `service.getData()`" not "The data can be gotten by calling..."
- Include code examples for every public API
- Link to related documentation
- Use consistent heading hierarchy

### DON'T
- Document obvious things: `// Sets the title` for `setTitle(title: string)`
- Use jargon without explanation
- Leave TODO comments in documentation
- Include implementation details that aren't relevant to the reader
- Write documentation that's longer than the code it documents
