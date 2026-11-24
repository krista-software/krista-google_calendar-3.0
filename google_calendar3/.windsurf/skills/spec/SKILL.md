---
name: spec
description: >
  Creates comprehensive specifications from requirements. Restates requirements completely,
  identifies gaps, applies architectural expertise to design decisions, and performs
  CTO-level review before completion. Never blindly follows requirements — always applies
  expert judgment. Use when you have requirements that need to be turned into an
  implementation-ready specification.
---

# Create Specification from Requirements

## Purpose

Transform raw requirements into a comprehensive, production-ready specification document
by applying multiple thinking lenses in sequence:

1. **System Architect** lens: Creates the specification with deep technical expertise
2. **UX Designer** lens (for UI work): Designs user journeys and creates visual mockups
3. **CTO Reviewer** lens: Meticulously validates the specification for gaps and issues

**CRITICAL PRINCIPLE**: Never blindly implement what requirements say. Apply expert judgment
to design the *best* solution that satisfies the *intent* of the requirements.

## Input Requirements

1. **Requirements**: Requirements file path, inline requirements, or external reference
2. **Output Location**: `docs-specs/{feature-name}/`
3. **Context** (optional): Existing system architecture, related specs, constraints

## Output Artifacts

```
docs-specs/{feature-name}/
├── requirements-analysis.md   # Gap analysis and clarifications
├── specification.md           # Complete specification document
├── ui-ux-design.md           # UI/UX design (if UI components present)
└── cto-review.md             # CTO reviewer's certification
```

---

## Phase 1: Requirements Analysis

**Thinking as: System Architect**

### 1.1 Complete Requirements Restatement

Read the input requirements and produce a complete, unambiguous restatement.
This is NOT a summary — it must capture EVERY requirement, explicit and implicit.

```markdown
# Requirements Analysis

## Restated Requirements

### Functional Requirements

#### FR-001: [Requirement Name]
**Original Text:** "[exact quote from requirements]"
**Interpretation:** [Clear, unambiguous restatement]
**Category:** [Core | Secondary | Optional]

### Non-Functional Requirements

#### NFR-001: [Requirement Name]
**Original Text:** "[exact quote or implied from context]"
**Interpretation:** [Clear, unambiguous restatement]
**Measurement:** [How this will be measured/verified]

### Constraints

#### CON-001: [Constraint Name]
**Source:** [Requirements | Technical | Business | Regulatory]
**Description:** [What is constrained and why]
```

### 1.2 Impact Analysis

Before designing the specification, explore the codebase to understand the system.
Check for project standards docs (e.g., `CLAUDE.md`, `AGENTS.md`), architecture docs in `docs/`,
and existing specs in `docs-specs/`.

```markdown
## Impact Analysis

### System Areas Affected by This Change

| Area | Layer | Impact | Action Required |
|------|-------|--------|-----------------|
| [Area] | Backend/Frontend/Both | [How this change affects them] | [What must be updated/verified] |

### Backend Areas Affected

| Module | Impact | Key Classes/Endpoints |
|--------|--------|----------------------|
| [Module] | [What changes] | [Classes/endpoints] |

### Frontend Areas Affected

| Feature | Impact | Key Components |
|---------|--------|----------------|
| [Feature] | [What changes] | [Components/services] |

### New Contracts Introduced
(Any new REST endpoints, data structures, or behaviors)

| Contract | Type | Consumers |
|----------|------|-----------|
| [Name] | [REST endpoint / DTO / Event] | [Who will use this] |
```

This section ensures the spec accounts for ALL downstream impact, not just the primary feature.

### 1.3 Gap Identification

```markdown
## Gap Analysis

### Critical Gaps (MUST be resolved before specification)
#### GAP-001: [Gap Name]
**Category:** [Missing Requirement | Ambiguous Requirement | Conflicting Requirements]
**Impact:** [What cannot be designed without this]
**Suggested Resolution:** [How to resolve]
**Question for Stakeholder:** "[Specific question to ask]"

### Important Gaps (SHOULD be resolved, can make assumptions)
#### GAP-002: [Gap Name]
**Default Assumption:** [What we'll assume if not clarified]
**Risk of Assumption:** [What could go wrong]
```

### 1.3 Design Hints vs Hard Constraints

Distinguish between:
- **Hard Constraints**: Non-negotiable technical or business constraints
- **Design Hints**: Suggestions to evaluate, not mandates
- **Requirements to Challenge**: Requirements that may not serve the actual goal

### Decision Point
- **If CRITICAL GAPS exist**: PAUSE and present gaps to the user for clarification
- **If no CRITICAL GAPS**: Proceed, documenting assumptions for Important Gaps

Create `requirements-analysis.md`.

---

## Phase 1.5: UI/UX Design (Conditional)

**Thinking as: UX Designer**
**Condition**: Invoke when the project has user-facing components

### Detection Criteria
Invoke if requirements mention: components, screens, pages, views, forms, dialogs,
user interaction, navigation, workflows, Angular components, Material Design,
dashboards, visualizations, wizards, or step-by-step flows.

### Process

#### 1.5.1 User Journey Mapping
```markdown
### Journey 1: [Primary User Goal]
**Actor:** [User role]
**Goal:** [What they want to accomplish]
**Trigger:** [What initiates this journey]
**Flow:**
1. User arrives at [entry point]
2. User sees [initial state/information]
3. User takes action [action description]
4. System responds with [feedback/result]
**Success Criteria:** [How we know the journey succeeded]
```

#### 1.5.2 ASCII Wireframes
Create visual mockups for key screens:
```markdown
### Screen: [Screen Name]
**Purpose:** [What this screen accomplishes]
┌────────────────────────────────────────────────────────┐
│  [Header / Navigation]                                  │
├────────────────────────────────────────────────────────┤
│  [Main Content Area]                                    │
│  ┌──────────────┐  ┌──────────────┐                    │
│  │  Component A  │  │  Component B  │                   │
│  └──────────────┘  └──────────────┘                    │
│  [Actions / Buttons]                                    │
└────────────────────────────────────────────────────────┘
```

#### 1.5.3 UI State Documentation

| State | Visual | Behavior |
|-------|--------|----------|
| Empty | "No items yet" message + CTA | Show helpful prompt |
| Loading | Skeleton/shimmer animation | Disable interactions |
| Error | Error message + retry action | Explain what went wrong |
| Success | Populated with data | Full interactivity |
| Readonly | Disabled controls, muted colors | Visual feedback that editing is blocked |
| Overflow | Many items | Pagination/virtualization |

#### 1.5.4 Component Architecture
Map wireframes to Angular component hierarchy:
```markdown
## Component Tree
AppComponent
  └── FeaturePageComponent
        ├── FeatureHeaderComponent
        ├── FeatureListComponent
        │     └── FeatureItemComponent (repeated)
        └── FeatureDetailComponent

### Component Inputs/Outputs
- FeatureListComponent: input(items), output(selected)
- FeatureItemComponent: input(item), input(editable), output(action)
```

Create `ui-ux-design.md`.

---

## Phase 2: Specification Design

**Thinking as: System Architect**

Design the optimal technical solution. Do NOT simply implement what requirements say —
design what's BEST.

### Specification Structure

```markdown
# Specification: [Project Name]

**Version:** 1.0
**Status:** DRAFT - Awaiting Review

## Executive Summary
[2-3 paragraph overview]

## 1. Objectives and Success Criteria
### 1.1 Primary Objectives
### 1.2 Success Criteria
### 1.3 Non-Goals

## 2. System Architecture
### 2.1 High-Level Architecture
### 2.2 Component Overview
| Component | Responsibility | Type | Location |

### 2.3 Key Design Decisions
#### Decision 1: [Name]
**Context:** [Problem]
**Options Considered:**
1. [Option A]: [Pros/Cons]
2. [Option B]: [Pros/Cons]
**Decision:** [What we chose]
**Rationale:** [Why]

## 3. Detailed Design
### 3.1 [Component A]
#### Purpose
#### Interface (Inputs/Outputs/Signals)
#### Behavior
#### Dependencies

## 4. Data Model
### 4.1 Interfaces and Types
### 4.2 State Management
### 4.3 Data Flow

## 5. API Integration (if applicable)
### 5.1 Endpoints consumed
### 5.2 Request/Response formats

## 6. Accessibility
### 6.1 Keyboard navigation
### 6.2 Screen reader support
### 6.3 ARIA attributes

## 7. Implementation Plan
### 7.1 Phases
#### Phase 1: [Name]
**Tasks:**
- [ ] TASK-001: [Description]

### 7.2 Task Breakdown
| Task ID | Description | Component | Complexity | Dependencies |

### 7.3 Risk Assessment
| Risk | Likelihood | Impact | Mitigation |

## 8. Open Questions
| Question | Impact | Owner | Due |
```

Create `specification.md`.

---

## Phase 3: CTO Review

**Thinking as: CTO Reviewer**

Meticulous, comprehensive review of the specification.

### Review Checklist
1. **Requirements Coverage** — Every requirement traced to spec section
2. **Design Quality** — Architecture appropriate, decisions sound, alternatives considered
3. **UI/UX Quality** — User journeys complete, states documented, wireframes clear
4. **Frontend Best Practices** — Follows the project's coding standards and conventions
5. **Implementation Plan** — Tasks complete, dependencies correct, estimates reasonable

### Review Output
```markdown
# Review Report
**Status:** [APPROVED | REJECTED]

## Issues Summary
### Critical Issues (MUST FIX - Blocks Approval)
| ID | Issue | Section | Required Action |
### Major Issues (SHOULD FIX)
| ID | Issue | Section | Recommendation |
### Minor Issues (NICE TO FIX)
| ID | Issue | Section | Suggestion |

## Certification (if APPROVED)
I have conducted a meticulous review of this specification. All requirements
are addressed, design decisions are sound, and the implementation plan is actionable.
The team can proceed with confidence.
```

Create `review-report.md`.

### If REJECTED
Route back to the appropriate phase for revision. Iterate until approval.
