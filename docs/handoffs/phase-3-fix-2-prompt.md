# Phase 3 repair 2 — retain a committed creation result until navigation is acknowledged

Continue in `/Users/francis/Documents/Project/代肝记录软件`. Review baseline: implementation `9aa4cf4`, handoff `510396a`. Phase 3 remains pending independent acceptance because of one newly reproduced submission-lifecycle defect. Do not begin Phase 4.

Read applicable repository instructions, `docs/handoffs/phase-3-fix-1-review.md`, the original Phase 3 requirements, and existing Phase 3 handoffs. Inspect Git status and preserve all existing changes and untracked reviewer artifacts. Do not reset, replace the application, alter historical schemas, or weaken accepted tests.

## Reproduced defect

In `NewOrderViewModel.submit()` success handling, lines 352–364 currently clear the pending command, rotate both operation IDs, and set `saving = false` before publishing `createdOrderId`. That flow has no replay. If the form has not left yet, or the success occurs during a gap without a navigation subscriber, another submission of the same visible form receives new creation and receipt IDs. The repository correctly treats those IDs as a different command, so its idempotency protection cannot prevent a duplicate order and initial receipt.

The independent regression is:

`com.daigan.record.review.Phase3SubmissionReviewTest.repeatedTapBeforeLeavingSuccessfulFormMustNotCreateANewCommand`

Actual result: expected one distinct creation identity, observed two. The test exercises the real ViewModel with a recording repository; it does not claim to simulate Android process death or prove a particular physical tap timing.

## Required repair

Model successful creation as a terminal state for that form instance. Keep the committed order ID, stable command identity and sufficient recovery state until navigation is acknowledged or the user explicitly starts a new creation flow. A repeated tap on the existing successful form must never allocate fresh IDs or insert another order/initial receipt.

Make the successful result observable when a navigation collector attaches or reattaches after completion. A replay-zero transient event alone is insufficient. Coordinate navigation consumption with screen disposal/restoration so configuration changes and a restored successful form can recover the original destination without issuing another create command. Prevent repeated navigation loops with an explicit acknowledgment contract or an equivalent state-based design.

Preserve existing behavior:

- Acquire the in-flight gate synchronously before launching work.
- An identical pending/failed command retries with its original IDs and exact submitted receipt time.
- Conflicting reuse is rejected by the repository.
- A genuinely new creation flow can create another otherwise identical order with new IDs.
- Keep the transactional creation-operation table and all accepted financial/migration guarantees.

Do not solve this with timing delays, a longer debounce, disabling the independent regression, or merely setting SharedFlow replay without addressing command rotation and navigation acknowledgment.

## Verification

Keep the independent regression and make it pass. Add meaningful ViewModel tests for in-flight repeated taps, success before a collector subscribes, collector reattachment, successful-state restoration, failure followed by an identical retry, and explicit new-form creation. Use real Room integration where needed to verify that the successful-form retry leaves exactly one order, one creation-operation row, and at most one initial receipt.

Run and inspect all JVM tests, all instrumentation tests, lintDebug, assembleDebug, and git diff --check. Use JDK 21 and the existing Android SDK without committing machine paths. Preserve all accepted Phase 1/2/3 assertions.

Also investigate `NavigationTest.switchingToShortTaskTabShowsItsOwnEmptyState`: it failed once in the full 144-test run but passed alone afterward. The cause is not established. Check asynchronous readiness and cross-test state; use condition-based synchronization where justified, preserve the empty-state assertion, and do not hide the issue with sleeps or test removal. Record full-suite and targeted results separately.

Correct the handoff statement that order detail derives from `LedgerSnapshotSource`: its current implementation still combines its own DAO flows. Either accurately document that boundary and demonstrate its continuous balance consistency, or extend consistent snapshot reading where justified. Do not claim an implementation path the code does not use.

Record exact commands, test counts, device/system, limitations, implementation commit, and APK path/SHA-256 in `docs/handoffs/phase-3-fix-2.md`. Update prior deliverable descriptions only where the final implementation changed. Complete a focused device check of successful creation and navigation/recreation, keeping the limitation explicit if a process-death scenario was not actually exercised.

Commit only the repair and its intended deliverables, preserving unrelated independent reports. Stop for independent acceptance; do not implement templates, reminders, backups, cloud services, signing, or publication.
