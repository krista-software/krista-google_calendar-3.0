---
name: ux-fix
description: >
  Evaluates and fixes UI/UX issues to achieve showroom-ready quality. Adopts real user
  personas to find friction, visual inconsistencies, and interaction problems. Systematically
  fixes every issue found. Use when the UI works but doesn't feel polished, or when you
  want a thorough UX audit of a feature.
---

# Relentless UX Fixer Skill

## Philosophy

```
SHOWROOM-READY QUALITY

The UI must look and feel like it was built by a team that cares deeply.
No rough edges. No "it works but looks off." No "we'll fix it later."
Every interaction should feel intentional and polished.
```

## What "Showroom-Ready" Means

- **Visual Consistency** — Spacing, alignment, colors, typography are consistent
- **Interaction Quality** — Transitions are smooth, feedback is immediate, states are clear
- **Error Grace** — Errors are helpful, not cryptic. Recovery is obvious.
- **Empty States** — Never a blank screen. Always guidance on what to do next.
- **Loading States** — Skeleton screens or shimmer, never a frozen UI
- **Responsive** — Works at all reasonable viewport sizes
- **Accessible** — Keyboard navigable, screen reader friendly, sufficient contrast

---

## Execution Process

### Step 1: Identify the Target

Determine what UI to evaluate:
- A specific component or page
- A user flow or journey
- A feature area
- The entire application (broad audit)

### Step 2: Adopt User Personas

Evaluate from multiple user perspectives:

#### Persona 1: The New User
- **Mindset:** "I've never seen this before. What do I do?"
- **Looks for:** Clarity, discoverability, helpful guidance, intuitive flow
- **Red flags:** Jargon, hidden features, unclear actions, no empty state guidance

#### Persona 2: The Power User
- **Mindset:** "I use this every day. It needs to be fast and efficient."
- **Looks for:** Keyboard shortcuts, batch operations, customization, speed
- **Red flags:** Too many clicks, forced workflows, slow interactions, no shortcuts

#### Persona 3: The Edge Case User
- **Mindset:** "What if I have 1000 items? What if my name is 200 characters?"
- **Looks for:** Overflow handling, extreme data, boundary conditions
- **Red flags:** Broken layouts, truncation without tooltips, performance degradation

#### Persona 4: The Accessibility User
- **Mindset:** "I navigate with keyboard/screen reader."
- **Looks for:** Focus management, ARIA labels, tab order, contrast, skip links
- **Red flags:** Trapped focus, missing labels, low contrast, broken tab order

### Step 3: Systematic Evaluation

For each target area, evaluate against this checklist:

#### Visual Quality
- [ ] Consistent spacing (multiples of 4px or 8px)
- [ ] Proper alignment (no misaligned elements)
- [ ] Correct Material Design colors and typography
- [ ] Icons are consistent size and style
- [ ] No visual artifacts or rendering issues

#### Interaction Quality
- [ ] Buttons have hover/active/focus states
- [ ] Clickable elements have cursor: pointer
- [ ] Form inputs have proper focus styles
- [ ] Transitions are smooth (200-300ms)
- [ ] No layout shifts during interactions
- [ ] Immediate feedback for user actions

#### State Handling
- [ ] Loading state with skeleton/shimmer
- [ ] Error state with helpful message and retry
- [ ] Empty state with guidance/CTA
- [ ] Success state with confirmation
- [ ] Overflow state with scroll/pagination

#### Content Quality
- [ ] Labels are clear and concise
- [ ] Error messages explain what went wrong AND how to fix it
- [ ] Placeholder text is helpful, not redundant
- [ ] Tooltips for truncated text

#### Accessibility
- [ ] All interactive elements keyboard accessible
- [ ] Logical tab order
- [ ] ARIA labels on icon-only buttons
- [ ] Sufficient color contrast (4.5:1 minimum)
- [ ] Focus visible on all interactive elements
- [ ] No keyboard traps

### Step 4: Create Issue Inventory

```markdown
## UX Issue Inventory

### Critical (Breaks usability)
| # | Issue | Location | Persona | Fix |

### Major (Degrades experience)
| # | Issue | Location | Persona | Fix |

### Minor (Polish)
| # | Issue | Location | Persona | Fix |
```

### Step 5: Fix Everything

Fix ALL issues found, in priority order (Critical → Major → Minor).

For each fix:
1. Make the code change
2. Verify the fix visually and functionally
3. Check that the fix doesn't break anything else
4. Mark the issue as resolved

### Step 6: Report

```markdown
## UX Fix Report

**Target:** [what was evaluated]
**Issues Found:** [total]
**Issues Fixed:** [total]

### Summary by Category
| Category | Found | Fixed |
|----------|-------|-------|
| Visual | N | N |
| Interaction | N | N |
| States | N | N |
| Content | N | N |
| Accessibility | N | N |

### Notable Fixes
1. [Most impactful fix and why]
2. [Second most impactful fix]

### Remaining Issues (if any)
[Issues that require architectural changes or user decisions]
```

---

## Post-Fix Validation (MANDATORY)

After all UX fixes are applied, run the full validation:

Discover and run the project's frontend build, type-check, and test commands.

If ANY check fails, fix before marking complete.

## Visual Verification (MANDATORY for visual fixes)

**Never claim a visual fix is complete without Playwright verification.**

Use Playwright or similar screenshot tools to capture before/after screenshots.
Adjust the URL and viewport to match the project's dev server configuration.

Workflow: screenshot BEFORE → make change → screenshot AFTER → visually verify fix.
If the issue is still visible in the screenshot, **the fix did not work** — iterate until it does.

## Project References

When fixing UX issues, ensure fixes follow the project's standards.
Discover standards by checking for project standards docs (e.g., `CLAUDE.md`, `AGENTS.md`)
and exploring existing components for patterns and conventions.
