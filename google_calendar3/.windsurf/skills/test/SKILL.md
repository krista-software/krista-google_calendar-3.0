---
name: test
description: >
  Creates or updates tests for a given library, component, or service. Follows the project's
  testing guide for patterns, coverage targets, and naming conventions. Generates real-world
  test scenarios, not artificial stubs. Use when you need to add tests for existing code
  or verify behavior after changes.
---

# Test Writing Skill

## Philosophy

```
TESTS ARE LIVING DOCUMENTATION

- Every test tells a story about real-world usage
- Tests prove behavior, not implementation details
- Tests catch regressions before users do
- Tests serve as examples for future developers
```

## Project References

**Before writing ANY tests:**
- Explore existing tests to discover the testing framework, patterns, and conventions
- Check for testing documentation in `docs/`
- Check project standards docs (e.g., `CLAUDE.md`, `AGENTS.md`) for testing constraints

## Test Organization

**Discover the project's test organization** by exploring existing test files.
Note the framework, naming convention, and directory structure in use.

---

## Execution Process

### Step 1: Identify the Target

Determine what to test:
- A specific service, component, or utility
- A library (all public API surface)
- Changed files (affected by recent modifications)

### Step 2: Analyze the Code

1. **Read the source code** — Understand all public methods, inputs, outputs, and edge cases
2. **Identify the layer** and discover the appropriate testing approach from existing tests
3. **List all scenarios** — Happy path, error cases, edge cases, boundary conditions

### Step 3: Write Tests

Follow these patterns based on library type:

Follow the patterns discovered from existing tests in the project.
Use the same test framework, assertions, and mocking approach.

**Key principles:**
- Use the Arrange/Act/Assert pattern
- Mock dependencies following the project's existing approach
- Test behavior, not implementation details

### Step 4: Verify Tests Pass

Discover and run the project's test commands from build configuration files.

### Step 5: Report

```markdown
## Test Report

**Target:** [what was tested]
**Tests Written:** [count]
**Coverage:**
| Metric | Before | After | Target |
|--------|--------|-------|--------|
| Branches | N% | N% | N% |
| Functions | N% | N% | N% |
| Lines | N% | N% | N% |

### Test Summary
| Describe Block | Tests | Scenarios Covered |
|---------------|-------|-------------------|
| [class/method] | N | [list of scenarios] |

### Uncovered Areas (if any)
[Code paths that are difficult to test and why]
```

---

## Test Naming Convention

```typescript
// ✅ Good — reads as a specification
it('should return invalid when email is missing', () => {});
it('should emit userSelected event when card is clicked', () => {});
it('should display loading spinner while data is fetching', () => {});

// ❌ Bad — vague
it('should work', () => {});
it('test validation', () => {});
it('error case', () => {});
```

## What To Test

### DO Test
- Business logic (validation, calculations, transformations)
- Component behavior (user interactions, output emissions, state changes)
- Edge cases (empty arrays, null values, boundary conditions)
- Error paths (what happens when things fail)
- Public API surface of services

### DON'T Test
- Framework internals (Angular's `@if` works)
- Implementation details (private methods)
- Third-party libraries (RxJS operators work)
- Trivial getters/setters with no logic

## Post-Test Validation (MANDATORY)

After writing tests, discover and run the project's build and test commands to ensure:
- Test code compiles without errors
- All tests pass (new and existing)
- No type errors
