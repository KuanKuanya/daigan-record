# Phase 2 final undo repair

Date: 2026-10-18. Baseline: `61bc8ec`. Branch: `codex/phase-2-undo-fix`.

## Scope and implementation

Repair the remaining acceptance blocker from `2026-10-18-phase-2-final-review.md`: an empty second completion batch replaced the effective undo target. Today all-hosting, Today whole-order, detail hosting and detail short-order now update the undo target only when the repository reports actual completed changes. No-op and failed completion preserve the last useful batch.

Both ViewModels acquire a Main-thread action gate synchronously before launching completion/undo work. Repeated in-flight taps are ignored; `finally` releases the gate after success, failure or cancellation. Completion and undo use the same gate so an older undo response cannot clear a newer completion token. Individual edits retain the existing repository conflict protection.

The Room schema remains v3. Repositories, migrations, idempotent empty batches, attachment handling and task/period rules are unchanged. Existing pre-completed work and edits made after a batch remain protected.

## Execution decisions

- Ruling: use an ordinary `codex/` repair branch in the existing checkout rather than a new worktree, preserving the user's current untracked review artifacts and avoiding duplicated environments. Cost if wrong: the user must switch branches for unrelated work; no accepted files are reset.
- Ruling: retain persisted empty batches for repository idempotency; select only effective batches for the UI undo entry. Cost if wrong: no-op submissions may still add empty internal audit rows, but they cannot replace the useful undo target.

## Verification ledger

- Applied Superpowers systematic debugging, test-driven development, inline execution and verification-before-completion.
- RED: existing reviewer regression failed (expected `effective-batch`, actual `empty-batch`). Expanded suite then had 6 tests, 5 failures: four completion entry points plus overlapping submission. Failure-preserves-undo/retry characterization already passed.
- GREEN: first full JVM run passed 73 tests, 0 failures/errors/skips. Final verification is recorded below.
- Real Room tests now exercise effective completion → a different-operation no-op → undo for all-hosting, one hosting order and a short order; they assert preserved earlier work and restored batch changes. Existing independent-edit conflict tests remain.
- Fresh read-only reviewer examined the complete repair and regression coverage: no actionable findings, no deferred minors. The review did not run tests; execution evidence is independently recorded here. Phase 3 finances were explicitly outside its scope.

## Product status

Phase 1 foundation/accounts is accepted. Phase 2 order/check-in/history/screenshots/renewal implementation has this final repair applied. Phase 3 receipts, financial debt, statistics and receipt history must be implemented next; templates, backups and reminders remain later phases. The debug APK is a test artifact, not a finished V1 or signed public release.

## Final acceptance evidence

- `./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain`: BUILD SUCCESSFUL in 2m 20s. Parsed 14 unit XML suites: 73 tests, 0 failures, 0 errors, 0 skips. Lint XML: 0 errors/fatals and 28 dependency-version warnings (same baseline category/count).
- `./gradlew connectedDebugAndroidTest --console=plain`: BUILD SUCCESSFUL in 5m 27s. Parsed device XML: 117 tests, 0 failures, 0 errors, 0 skips. Device: Medium_Phone_API_36.1, Android 16, arm64 emulator.
- `git diff --check`: passed.
- Fresh reviewer: no actionable findings and no deferred minors. No separate manual visual walkthrough was performed for this small repair; prior Phase 2 walkthrough screenshots remain available and the full existing UI instrumentation suite passed.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- APK SHA-256: `dd3cc3b8ec5af93bb72d530c8238f78e0dd5c53d1432cc751ef9d41611530e15`.

**Acceptance: Phase 2 passes.** Phase 3 can begin using `docs/handoffs/phase-3-prompt.md`. The repair remains on the local `codex/phase-2-undo-fix` branch; no merge or remote publication was requested. Earlier untracked review reports are preserved.
