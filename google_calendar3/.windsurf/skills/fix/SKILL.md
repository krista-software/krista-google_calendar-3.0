---
name: fix
description: >
  Relentlessly fixes ALL failures in builds or compilation. Zero tolerance for leaving
  any error unresolved. Identifies root causes instead of applying band-aids. Iterates
  until everything passes. Use when you have build errors, compilation issues, or
  runtime failures that need to be systematically resolved.
---

# Relentless Fixer Skill

## Philosophy

```
ZERO TOLERANCE FOR FAILURE

- Every build MUST succeed
- Every type error MUST be fixed
- No error may be suppressed or swallowed
- Root causes are found and fixed, not symptoms
```

## Forbidden Actions

**NEVER do any of the following:**
- Add `@ts-ignore` or `@ts-expect-error` to suppress type errors
- Use `as any` to bypass type checking
- Add `try/catch` that swallows errors to make builds pass
- Remove code that's causing errors without understanding why
- Comment out problematic code

---

## Execution Process

### Step 1: Execute and Inventory

Run the failing process and capture ALL failures:

Discover the project's build and test commands from build configuration files
(e.g., `build.gradle`, `package.json`, `Makefile`, `pom.xml`) and run them:

- Backend compilation
- Backend tests
- Frontend type checking (if applicable)
- Frontend tests (if applicable)
- Full build

Create a complete failure inventory:

```markdown
## Failure Inventory

| # | Type | Location | Error Message | Root Cause | Status |
|---|------|----------|---------------|------------|--------|
| 1 | Type Error | path/file.ts:42 | Property 'x' does not exist | Missing interface property | ⏳ |
| 2 | Compile Error | path/File.java:8 | Cannot find symbol | Missing import | ⏳ |
```

### Step 2: Classify and Prioritize

Group failures by root cause — many failures often share the same root cause.

```
PRIORITY ORDER:
1. Compilation errors — Java or TypeScript (fixing these often fixes downstream failures)
2. Import/Module errors (missing dependencies cascade)
3. Service/Logic errors (business logic bugs)
4. Template errors (Angular template compilation)
5. Test failures
6. Style/SCSS errors
```

### Step 3: Fix Root Causes

For each root cause (NOT each individual failure):

1. **Understand the root cause** — Read the relevant code, understand the intent
2. **Fix at the source** — Change the code that's wrong, not the code that's reporting the error
3. **Verify the fix** — Run the specific failing area to confirm
4. **Check for collateral** — Ensure the fix doesn't break other things

### Step 4: Verify Complete Resolution

After fixing all identified root causes:

1. **Run the full build again** — NOT just the files you fixed
2. **Check for NEW failures** — Your fixes may have revealed hidden issues
3. **If new failures exist** → Return to Step 1 with the new failures
4. **If all pass** → Move to Step 5

### Step 5: Report

```markdown
## Fix Report

**Initial Failures:** [N]
**Root Causes Found:** [M]
**Fixes Applied:** [K]
**Final Status:** ALL PASSING ✅

### Root Causes and Fixes

#### Root Cause 1: [Description]
**Affected:** [N] failures
**Fix:** [What was changed and why]
**Files Modified:**
- `path/to/file.ts` — [what changed]

### Iterations Required
[How many fix-verify cycles were needed]
```

---

## Common Failure Patterns

### Backend
- **Cannot find symbol** → Missing import, wrong package, or typo
- **Incompatible types** → Check generics, casting, or return types
- **NullPointerException / NPE** → Null checks, Optional usage
- **SQL syntax error** → Check migration files
- **Dependency injection error** → Missing binding or annotation
- **Test infrastructure** → Integration tests may need running services

### Frontend
- **Type errors** → Missing interface properties, wrong types
- **Module/import errors** → Missing imports or incorrect module configuration
- **Provider not found** → Missing dependency injection configuration
- **Template errors** → Check binding names and component imports

---

## Iteration Protocol

```
LOOP:
  1. Run failing process
  2. Count failures
  3. If 0 failures → EXIT with success
  4. Fix root causes (NOT symptoms)
  5. Run again
  6. If fewer failures → GOTO 1 (making progress)
  7. If same or more failures → Re-analyze approach, try different fix
  8. If stuck after 3 attempts on same issue → Report blocker to user
```

**Maximum iterations before escalation: 5**

After 5 iterations without reaching zero failures, present a detailed report to the user
with remaining issues and your analysis of why they're proving difficult.

---

## Post-Fix Validation (MANDATORY)

After all fixes are applied and the original failures are resolved, run the full validation:

Discover and run **all** of the project's build and test commands.

If ANY check fails, return to Step 1 with the new failures.

## Project References

When fixing code, ensure fixes follow the project's standards.
Discover standards by checking for `CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING.md`,
and documentation in `docs/`.
