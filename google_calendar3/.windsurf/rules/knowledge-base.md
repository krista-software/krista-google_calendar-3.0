---
trigger: model_decision
description: >
  Activate when modifying code related to a documented feature, or when creating/updating
  documentation. Requires agents to consult project docs before making changes and verify
  docs remain accurate after changes.
---

# Knowledge Base Consultation Rule

## Before Making Code Changes

When modifying code in this project, you MUST:

1. **Read project standards** — Check for `CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING.md`, or similar
2. **Read relevant docs** — Explore `docs/` for architecture, security, testing strategy docs
3. **Check existing specs** — Check `docs-specs/` for any existing specifications related to the feature
4. **Understand before changing** — Use docs to understand behavior, invariants, and edge cases

### Key Reference Documents

Explore the project root and `docs/` folder to discover relevant documentation.
Always read project standards documents and any documentation related to the area you're modifying.

## After Making Code Changes

If your code change alters documented behavior:

1. **Verify doc accuracy** — Re-read relevant docs and confirm they match the code
2. **Update docs-specs if needed** — If a spec in `docs-specs/` no longer reflects reality, update it
3. **Do NOT modify `docs/`** — Unless the user explicitly asks. The `docs/` folder is human-curated

## Documentation Locations

### `docs/` — Human-Curated (Read-Only for Agents)

Explore `docs/` to discover existing project documentation. Common documents include
architecture guides, testing strategies, security docs, and API collections.
**Do NOT modify** these unless the user explicitly requests it.

### `docs-specs/` — AI Agent Workspace

| Folder | Contents |
|--------|----------|
| `docs-specs/{feature-name}/` | Feature specs, reviews, progress reports |
| `docs-specs/chats/` | Ad-hoc planning documents |

## What NOT To Do

- Do NOT skip reading project standards docs before changes — they have rules that prevent regressions
- Do NOT make changes that violate documented invariants without discussing with the user
- Do NOT write to `docs/` unless the user explicitly asks — use `docs-specs/` instead
- Do NOT skip reading existing specs in `docs-specs/` when working on a feature that has one
