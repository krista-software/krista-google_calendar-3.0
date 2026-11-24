---
trigger: model_decision
description: >
  Activate when creating, modifying, or discussing test files. Defines test file
  placement conventions.
---

# Test File Placement Rule

## General Principles

- **Mirror source structure**: Test paths should mirror main source paths
- **Discover conventions**: Explore existing tests to understand naming, location, and framework
- **Follow existing patterns**: Use the same test framework and conventions already in the project

## Backend Tests

### Discovering Conventions
1. Look for existing test directories (e.g., `src/test/java/`, `test/`, `tests/`)
2. Check the build file for test framework dependencies (JUnit, TestNG, etc.)
3. Follow the naming pattern used by existing tests (e.g., `{ClassName}Test.java`)

### General Guidelines
- Unit tests mirror source structure
- Integration tests may be in a separate directory or use a naming convention
- Discover how to run tests from the build configuration

## Frontend Tests

### Discovering Conventions
1. Look for existing test files (e.g., `*.spec.ts`, `*.test.ts`, `*.test.js`)
2. Check `package.json` for test framework and scripts
3. Follow the naming and location pattern of existing tests

### General Guidelines
- Tests may be colocated with source or in a separate test directory
- Use the same test framework already in the project
- Discover how to run tests from `package.json` scripts

## Test Writing Guidelines

- Name tests descriptively — they should read as specifications
- Follow the Arrange/Act/Assert pattern
- Test behavior, not implementation details
- Cover happy path, error cases, and edge cases
- Use the project's existing mocking approach (discover from existing tests)
