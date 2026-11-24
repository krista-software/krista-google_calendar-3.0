---
name: 360-review
description: >
  Comprehensive adversarial analysis of ideas, requirements, or code designs from multiple
  perspectives. Use when evaluating whether an idea is worth pursuing, finding simpler
  alternatives, assessing feasibility, or getting a strategic perspective on technical
  decisions. The goal is NOT validation — it's to surface weaknesses, find better ideas,
  and prevent complexity sprawl.
---

# 360° Review Skill

You are conducting a **360° review** to analyze the user's idea, requirement, or design.

## Your Mission

This is NOT a validation exercise. Your job is to:
1. **Find weaknesses and risks** the proposer hasn't considered
2. **Surface better, simpler alternatives** — this is REQUIRED, not optional
3. **Ensure we build the right thing the right way**
4. **Prevent unnecessary complexity** and architectural sprawl

## Technology Constraints (CRITICAL)

**Discover the project's technology stack** by exploring the codebase and build files.
Understand the existing patterns, frameworks, and libraries before evaluating proposals.

Any proposal introducing NEW dependencies or patterns requires STRONG justification.
Default answer to "can we add [new library]" is **NO** unless there's overwhelming benefit.

---

## Phase 1: Initial Analysis

Analyze the topic from three perspectives sequentially. Research the codebase and web
as needed for each.

### Perspective 1: System Architect

**Your lens: DESIGN ELEGANCE AND SIMPLICITY**

Research the codebase, then evaluate:
1. Can this be done with LESS? What's the minimum viable design?
2. Does it fit existing patterns in this codebase or fight against them?
3. What's the simplest possible implementation?
4. What complexity is being hidden or underestimated?
5. Does this introduce unnecessary new patterns or dependencies?
6. Could an existing service, component, or pattern be extended instead?

You MUST propose at least one simpler alternative.

**Output:**
- **Simplicity Score:** [1-5, where 5 is simplest] — reasoning
- **Fits Existing Patterns:** [Yes/No] — which patterns or what's different
- **Hidden Complexities:** [list]
- **Dependency Risk:** [none/low/medium/high] — details
- **Better Ideas:** At least one alternative approach with pros/cons

### Perspective 2: CTO Reviewer

**Your lens: STRATEGIC VALUE AND OPPORTUNITY COST**

Evaluate:
1. Is this problem worth solving AT ALL? What's the real value?
2. Are we solving the right problem, or a symptom?
3. What's the opportunity cost? What else could this effort achieve?
4. Is this the right time, or should it wait?
5. Would you approve this from a limited engineering budget?

Challenge the premise. The best outcome might be "don't do this."

**Output:**
- **Problem Worth Solving?** [Yes/No/Partially] — reasoning
- **Right Approach?** [Yes/No/Partially] — reasoning
- **Effort vs. Payoff:** assessment
- **Opportunity Cost:** what else this effort could achieve
- **Strategic Alternatives:** different ways to achieve the goal

### Perspective 3: UX Critic

**Your lens: USER EXPERIENCE AND PRACTICAL USABILITY**

Evaluate:
1. How does this affect the end user? Will they notice? Will they care?
2. What's the user's mental model? Does this design match it?
3. What are the failure modes from the user's perspective?
4. Are all UI states handled (loading, error, empty, readonly, overflow)?
5. Is this accessible (keyboard, screen reader, contrast)?
6. What edge cases would a real user encounter?

**Output:**
- **User Impact:** [High/Medium/Low/None] — reasoning
- **UX Risks:** what could go wrong for users
- **Missing States:** UI states not accounted for
- **Accessibility Gaps:** potential issues
- **Edge Cases:** boundary conditions from user perspective

---

## Phase 2: Synthesize Report

Write the report to `docs-specs/{feature-name}/360-review.md`.

If the target feature doesn't have a folder in `docs-specs/` yet, create it.

```markdown
# 360° Review: [Topic Title]

**Date:** [date]

## Verdict
[Worth pursuing | Needs refinement | Consider alternatives | Not recommended]

## Executive Summary
[2-3 sentences synthesizing key findings]

## Value Analysis (CTO Perspective)
- **Problem Worth Solving?** [Yes/No/Partially] — reasoning
- **Right Approach?** [Yes/No/Partially] — reasoning
- **Effort vs. Payoff:** assessment
- **Opportunity Cost:** what else this effort could achieve

## Design Assessment (Architect Perspective)
- **Simplicity Score:** [1-5] — reasoning
- **Fits Existing Patterns:** [Yes/No] — details
- **Hidden Complexities:** list
- **Dependency Risk:** [none/low/medium/high] — details

## UX Assessment (UX Critic Perspective)
- **User Impact:** [High/Medium/Low/None] — reasoning
- **UX Risks:** what could go wrong
- **Missing States:** UI states not handled
- **Edge Cases:** boundary conditions

## Better Ideas
[REQUIRED — alternatives that achieve same/better goals with less complexity]

1. **[Alternative Name]**
   - Description: brief explanation
   - Pros: advantages
   - Cons: trade-offs
   - Complexity Delta: [simpler/same/more complex]

## Open Questions
[What needs clarification before proceeding]

## Recommendations
1. [Prioritized next steps]
```

Present to the user and invite follow-up.

---

## Follow-up Handling

### Directed Questions
When user says `@architect`, `@cto`, or `@ux`:
- Route primarily to that perspective

### Refinement Requests
When user modifies the proposal:
1. Re-run relevant portions of the analysis
2. Update the verdict and recommendations
3. Note which concerns are now addressed vs. still open

---

## Anti-Patterns to Avoid

1. **Rubber-stamping** — "This looks good" without deep analysis
2. **Nihilism** — Shooting everything down without alternatives
3. **Scope creep** — Adding requirements instead of evaluating what's proposed
4. **Analysis paralysis** — Endless questions without recommendations
5. **Technology advocacy** — Pushing favorites instead of evaluating fit
6. **Ignoring constraints** — Suggesting solutions outside the project's established tech stack
