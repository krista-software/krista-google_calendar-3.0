---
description: Run lint, test, and build validation on all affected projects
---

# Validate Workflow

Run the full validation suite to ensure code quality before committing or marking a task complete.

## Steps

1. **Discover build/test commands** — Explore the project's build files (`build.gradle`, `package.json`, `pom.xml`, `Makefile`, etc.) to identify compilation, test, lint, and formatting commands.

2. Run backend compilation (if applicable):
// turbo
```bash
# Discover and run the project's backend compile command
```

3. Run backend tests (if applicable):
// turbo
```bash
# Discover and run the project's backend test command
```

4. Run frontend type checking (if applicable):
// turbo
```bash
# Discover and run the project's frontend type-check command
```

5. Run frontend tests (if applicable):
// turbo
```bash
# Discover and run the project's frontend test command
```

6. Report results:
   - If ALL checks pass → ✅ Validation successful
   - If ANY check fails → List failures with file:line references and suggest fixes
