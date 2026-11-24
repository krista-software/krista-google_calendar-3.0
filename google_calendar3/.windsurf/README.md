# AI Development Kit

A structured system of rules, skills, and workflows that guide AI-assisted development.
This kit is **project-agnostic** — it works with any codebase. The agent discovers
project-specific details (tech stack, build commands, folder structure) by exploring
the codebase at runtime.

## How It Works

| Mechanism | Location | Invocation | Purpose |
|-----------|----------|------------|---------|
| **Rules** | `.windsurf/rules/*.md` | Always active or model-decision | Behavioral guidelines for every conversation |
| **Skills** | `.windsurf/skills/<name>/SKILL.md` | Automatic or `@skill-name` | Complex multi-step processes |
| **Workflows** | `.windsurf/workflows/*.md` | `/slash-command` | Short, repeatable step sequences |

## Rules

### Orchestration (Always Active)

| Rule | Purpose |
|------|---------|
| `rules/orchestration.md` | Quality gates, persona routing, build commands, validation routines |

### Persona Rules (Model Decision — activate automatically)

| Rule | Persona | Activates When |
|------|---------|----------------|
| `rules/frontend-engineer.md` | **Frontend Engineer** | Implementing Angular components, services, UI features |
| `rules/backend-engineer.md` | **Backend Engineer** | Implementing Java services, REST endpoints, DB migrations |
| `rules/system-architect.md` | **System Architect** | Designing architecture, specifications, planning features |
| `rules/cto-reviewer.md` | **CTO Reviewer** | Reviewing architecture plans, specs, strategic decisions |
| `rules/code-reviewer.md` | **Code Reviewer** | Reviewing code changes, verifying requirements |
| `rules/docs-writer.md` | **Docs Writer** | Creating or updating documentation |
| `rules/ux-critic.md` | **UX Critic** | Evaluating UI/UX quality, fixing interaction issues |
| `rules/knowledge-base.md` | **Knowledge Base** | Consulting project docs before/after code changes |
| `rules/test-placement.md` | **Test Guide** | Creating or modifying tests |

## Skills

| Skill | Purpose | When to Use |
|-------|---------|-------------|
| `@brainstorm` | Creative ideation | Exploring new ideas or "what if" questions |
| `@spec` | Specification from requirements | Turning requirements into specs |
| `@360-review` | Adversarial analysis | Evaluating feasibility, finding alternatives |
| `@phase-breakdown` | Phase partitioning | Breaking specs into implementation phases |
| `@implement` | Implementation orchestration | Systematically implementing phases |
| `@fix` | Relentless fixer | Fixing ALL build/test failures |
| `@ux-fix` | UX polish | Fixing UI/UX to showroom quality |
| `@test` | Test writing | Creating or updating tests |
| `@docs` | Documentation | Creating/updating docs |

## Workflows

| Workflow | Purpose | When to Use |
|----------|---------|-------------|
| `/review` | Code review | Reviewing changes for bugs, security, standards |
| `/validate` | Validation | Running lint + test + build checks |

## Typical Flow

```
  Ideation:        @brainstorm → @360-review
  Specification:   @spec → @phase-breakdown
  Implementation:  @implement → @fix → @ux-fix
  Quality:         /review → @test → @docs → /validate
```

Shortcuts:
- **Quick feature** → `@implement`
- **Complex feature** → `@spec` → `@phase-breakdown` → `@implement`
- **Bug fix** → `@fix`
- **UI polish** → `@ux-fix`
- **Code review** → `/review`

## Documentation Structure

This kit uses a **two-folder documentation model**:

| Folder | Purpose | Maintained By |
|--------|---------|---------------|
| `docs/` | Pristine, authoritative project documentation | Humans (manually curated) |
| `docs-specs/` | AI-generated artifacts: specs, brainstorms, reviews, progress reports | AI agents via skills |

### `docs/` — Human-Curated Documentation

Project-specific documentation (architecture, testing, security, etc.).
Agents should **read** these but only **modify** them when explicitly asked by the user.

### `docs-specs/` — AI Agent Workspace

All skill outputs (brainstorms, specs, reviews, phase breakdowns, progress reports) go here.
Organized by feature or topic:

```
docs-specs/
├── {feature-name}/
│   ├── brainstorm.md              # @brainstorm output
│   ├── 360-review.md              # @360-review output
│   ├── requirements-analysis.md   # @spec Phase 1 output
│   ├── specification.md           # @spec Phase 2 output
│   ├── ui-ux-design.md            # @spec Phase 1.5 output (if UI)
│   ├── cto-review.md              # @spec Phase 3 / CTO review output
│   ├── phase-overview.md          # @phase-breakdown overview
│   ├── phase-N-*.md               # @phase-breakdown individual phases
│   ├── progress-report.md         # @implement progress tracking
│   └── test-plan.md               # @test plan output
├── {another-feature}/
│   └── ...
└── chats/                         # Ad-hoc planning docs from conversations
```

## Relationship to Project Standards

Rules and skills reference project documentation found in `docs/` but don't duplicate them.
The agent discovers project-specific standards by exploring `docs/` and the codebase.
If a doc is updated, all agents automatically follow the new standard.
