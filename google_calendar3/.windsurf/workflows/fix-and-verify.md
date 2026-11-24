---
description: User-triggered autonomous fix-test-verify cycle for code changes. Invoke with /fix-and-verify.
---

# Fix and Verify Workflow

**TRIGGER:** User-initiated only. This workflow runs ONLY when the user explicitly
invokes `/fix-and-verify` or asks for it. NEVER start this workflow automatically.

After the user triggers this workflow, Cascade autonomously restarts servers,
runs tests, fixes issues, and iterates until confident — only escalating to
the user for complex problems.

## Critical Rules — Read Before Every Run

### Rule 1: New Features MUST Have New E2E Tests
**NEVER claim a feature is "done" by only running existing tests.**
If you added or changed UI behavior (new button, new dialog, new menu item, new flow),
you MUST write E2E tests that exercise that specific behavior before declaring completion.

- Ask: "Can I click the new thing and see the expected result in my E2E test?"
- If the answer is no → you are not done.

### Rule 2: Console Errors = Test Failure
The base E2E fixture (`base.fixture.ts`) automatically fails any test that produces
unexpected console errors. This catches crashes like `TypeError`, `ExpressionChangedAfterItHasBeenCheckedError`, 
signal input lifecycle errors, etc. Do NOT suppress or weaken this check.

### Rule 3: Never Ship Untested Dialogs/Components
If you create a new component (dialog, panel, form), you MUST:
1. Verify it renders without console errors (covered by Rule 2)
2. Write at least one E2E test that opens and interacts with it
3. Verify inputs are correctly wired (pre-populated values, correct labels)

## Steps

1. Determine what changed:
   - **Backend only**: Restart backend if needed
   - **Frontend only**: Frontend may auto-reload (no restart needed for most changes)
   - **Both**: Restart backend, frontend auto-reloads

2. If backend changes were made, restart backend using the project's restart mechanism.

3. Wait for servers to be ready (check the project for startup scripts or health endpoints).

4. **If new UI behavior was added**, write or update E2E tests BEFORE running them:
   - Add page object helpers for new elements (dialogs, menus, buttons)
   - Write test cases that exercise each new interaction
   - Update existing tests if element counts or labels changed

5. Run targeted tests for the affected feature (discover test commands from the project's configuration).

6. Check test results:
   - Read the console output for pass/fail
   - If tests FAILED: Check for failure screenshots or logs
   - Diagnose the issue and fix the code
   - Go back to Step 1

7. Run broader tests to verify no regressions.

8. If all tests pass, visually confirm correctness via screenshots if applicable.

9. **Pre-completion checklist** — all must be true before declaring done:
   - [ ] Every new UI element has at least one test exercising it
   - [ ] No console errors in any test
   - [ ] Existing tests updated if element counts/labels changed
   - [ ] Targeted tests pass ✅
   - [ ] Broader regression tests pass ✅

10. Generate completion report — see Report Rules below.

## Escalation Rules

- After **5 failed fix attempts** on the same issue: Stop and ask the user for help
- If the issue involves **real OAuth redirect**: Cannot test with dummy adapter, ask user to verify manually
- If the issue is **visual/aesthetic** and tests pass: Take screenshot and ask user to review

## Report Rules

At the end of the spree, report what was done. Choose format based on scope:

### Extensive Changes (3+ files modified OR 50+ lines changed)
Generate a structured report at `/tmp/e2e-results/change-report.md` with:
- Summary (files modified, lines changed, iterations, test results, duration)
- Per-file change descriptions (what, why, lines)
- Test results (pass/fail per spec file)
- Screenshot references
- Issues encountered

Tell the user: "Change report generated at `/tmp/e2e-results/change-report.md` — please review."

### Minimal Changes (fewer than 3 files AND fewer than 50 lines)
No report file. Summarize directly in chat:
- What was changed (1-2 sentences)
- What tests passed
- Any notable observations
