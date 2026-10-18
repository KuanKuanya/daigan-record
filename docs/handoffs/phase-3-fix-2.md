# Phase 3 fix 2 — a committed creation stays terminal until navigation is acknowledged

Date: 2026-10-18. Baseline reviewed: implementation `9aa4cf4`, handoff `510396a`.
Branch: `codex/phase-2-undo-fix` (local; no merge, push or publication).
Implementation commit: `70b9730` (`fix(phase-3): keep a committed creation terminal
until navigation is acknowledged`); this document and the screenshots are its follow-up
documentation commit.
Independent reports (`docs/handoffs/2026-10-18-phase-3-review.md`,
`docs/handoffs/phase-3-fix-1-review.md`, the `*-review.md` files and the review prompt
files) remain untracked and untouched.

This repair addresses the submission-lifecycle blocker and the documentation/verification
items in `docs/handoffs/phase-3-fix-1-review.md`. Phase 3 remains pending independent
acceptance; Phase 4 was not started.

## 1. Defect and root cause

`NewOrderViewModel.submit()` treated "the database committed" as "the user started
another order". Its success branch removed the pending command, rotated the create and
initial-receipt operation ids, set `saving = false` and then emitted an event on a
replay-zero `SharedFlow`.

Consequences:

- a second tap on the still-visible successful form (or a tap during a gap with no
  navigation collector) built a command with fresh operation ids, so the repository's
  idempotency table saw a *different* command and inserted a second order and a second
  initial receipt;
- the success destination was a transient event: a collector that attached or
  reattached after completion could not recover it;
- the rotated ids made the exact submitted command unrecoverable even though the form
  was still on screen.

The independent regression
`Phase3SubmissionReviewTest.repeatedTapBeforeLeavingSuccessfulFormMustNotCreateANewCommand`
failed before this change (1 test, 1 failure, `expected 1 identity, was 2`) and passes
after it.

## 2. Repair: successful creation is a terminal form state

**State, not an event.** `NewOrderUiState` now carries `createdOrderId` and
`createdNavigationPending`. Both are persisted in `SavedStateHandle`
(`new-order.created-order-id`, `new-order.created-navigation-pending`). A committed
form is observable to any StateFlow collector, including one that subscribes after the
commit, after a reattachment or after process restoration.

**No identity rotation on success.** The committed order id, the create-operation id,
the initial-payment operation id, the normalized fingerprint, the exact submitted
receipt time and the encoded payload all stay until navigation is acknowledged. The
`submit()` gate checks `isCreated` *before* it reads the form, so a repeated tap cannot
allocate ids or reach the repository at all. This is still a synchronous gate taken on
the main thread before any coroutine starts.

**Navigation acknowledgment contract.** The screen observes
`createdOrderId` + `createdNavigationPending`; when both are set it calls
`acknowledgeCreatedNavigation(orderId)` and then `onCreated(orderId)`. Acknowledging is
idempotent and strictly matched by order id: it only stops the navigation signal; the
form stays terminal and keeps its order id. A configuration change or recomposition
therefore cannot navigate to the same order twice.

**Restoration.** `restoreCommittedCreation()` rehydrates the committed order id and the
pending flag before the pending command is restored. A restored successful form with a
pending navigation reopens the original destination without any create command; a form
whose navigation was already acknowledged shows the terminal card with an explicit
`查看订单` action, so the destination is still recoverable and never re-creates.

**Explicit new creation.** `startNewCreation()` (UI: `再建一张`) is the only path out of
the terminal state. It clears the committed id, the pending flag and the completed
command, and allocates new operation ids, so a genuinely new — possibly identical —
order is a new command with new ids.

**Screen.** A committed form no longer renders the editable form. It shows a success
card (`订单已创建`) with `查看订单` (navigation only, no create) and `再建一张`
(explicit new flow). Test tags: `new-order-created`,
`new-order-created-title`, `new-order-created-open`, `new-order-created-new`.

## 3. Preserved behaviour

- The in-flight gate is acquired synchronously before launching work; a queued tap
  during a slow commit starts nothing (`repeatedTapWhileSubmissionIsInFlightStartsOneCommand`).
- An identical pending/failed command retries with its original ids and the exact
  submitted `receivedAt` (`failedSubmissionRetriesWithIdenticalIdentitiesAndReceiptTime`);
  editing the payload after a failure is still a new command.
- Conflicting reuse under one operation id is still rejected by the repository
  (`Phase3FinancialContractTest` unchanged).
- A genuinely new creation flow can create another otherwise identical order with new
  ids, verified against real Room.
- `order_creation_operations` and every accepted financial/migration guarantee are
  unchanged: no schema change, no migration, no table removed.

## 4. Order detail read boundary (documentation correction)

`phase-3-fix-1.md` §6 claimed order detail derived from `LedgerSnapshotSource`. It did
not: `observeOrderDetail` combined seven independent DAO flows, so one commit could be
published as a mix of two database versions.

Order detail now reads its own per-order snapshot (order, account, owner, tasks,
occurrences, attachments, periods, receipts, corrections) inside one
`database.withTransaction` (`OrderRepository.readOrderDetail`), invalidated by the same
Room table observations as before. It is a separate transaction from the global ledger
snapshot: the two read models agree on committed data, but the detail is not literally
that snapshot instance. `docs/data-model.md` and `phase-3-fix-1.md` now state this
boundary explicitly.

`OrderDetailConsistencyTest` keeps a detail subscriber alive through
create-with-receipt → payment → amendment → void and fails on any emission containing a
receipt without its period, a correction without its receipt, a balance that disagrees
with its own active receipts, or a disappeared history row; it also cross-checks the
final detail balance against one consistent `LedgerSnapshotSource.load()`.

## 5. `NavigationTest.switchingToShortTaskTabShowsItsOwnEmptyState`

The test clicked the tab and asserted immediately. The tab content is backed by
asynchronous database flows: while `TodayUiState.loading` is true the screen renders a
progress indicator and the empty-state card does not exist yet. The earlier full-suite
failure is consistent with asserting inside that window (the full suite runs the device
under heavier load than a single targeted run); no product defect was observed and the
empty state itself remained correct in every run.

The test now waits for conditions only — first for the hosting empty state (the initial
snapshot), then for the short-task empty state — using `waitUntil` on real semantics
nodes. No sleep, no debounce, no production change, and the empty-state assertion plus an
exclusivity assertion (`还没有托管打卡` is not also present) are preserved. Full-suite and
targeted results are recorded separately below; the original full-suite failure is not
retroactively erased.

## 6. Coverage added

JVM (`app/src/test/.../feature/orders/NewOrderSubmissionLifecycleTest.kt`, 7 cases):

- in-flight repeated taps start one command and keep the gate held;
- success with no collector attached is still observable afterwards;
- collector reattachment keeps the committed identity; acknowledgment is idempotent and
  matched by order id; a post-acknowledgment tap still does not submit;
- a successful form restored over the same `SavedStateHandle` recovers the destination
  and never submits, with or without the pending flag;
- failure followed by an identical retry reuses both operation ids and the exact receipt
  time; an edited payload after failure is a new command;
- an explicit new creation flow allocates fresh ids for an otherwise identical order.

Instrumentation:

- `Phase3SubmissionReviewTest` (the independent regression) is unchanged and passes.
- `Phase3SubmissionRoomLifecycleTest` (real Room + the real Android main dispatcher):
  a repeated tap on the successful form — before and after acknowledgment — leaves
  exactly one order, one `order_creation_operations` row and one initial receipt; the
  explicit new creation flow then creates a second, otherwise identical order with a
  distinct operation row and its own receipt.
- `OrderDetailConsistencyTest` (real Room): continuous detail-subscription consistency as
  described in §4.

## 7. Verification

Environment: JDK 21.0.4 (Microsoft OpenJDK,
`/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home`), Android SDK
`/Users/francis/Library/Android/sdk`, Gradle 9.2.1, emulator `Medium_Phone_API_36.1`
(Android 16 / API 36, `sdk_gphone64_arm64`, arm64-v8a, 1080×2400 @ 420 dpi).

Commands and results:

```text
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --console=plain
-> BUILD SUCCESSFUL
   JVM XML: 18 suites, 89 tests, 0 failures, 0 errors, 0 skips
   (81 previously accepted + the 1 independent submission regression + 7 new lifecycle tests)
   Lint: 0 errors, 2 warnings (ObsoleteSdkInt), 2 hints (AutoboxingStateCreation)
   - unchanged from the pre-repair baseline, no new issue

./gradlew :app:connectedDebugAndroidTest --offline --console=plain
-> 147 tests, 0 failures, 0 errors, 0 skips (26 test classes); 5 of them are the independent Phase3AcceptanceReviewTest cases and 2 are the new Room lifecycle tests

"-Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.review.Phase3SubmissionRoomLifecycleTest"
-> 2 tests, 0 failures

"-Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.review.OrderDetailConsistencyTest"
-> 1 test, 0 failures

"-Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.ui.NavigationTest#switchingToShortTaskTabShowsItsOwnEmptyState"
-> targeted runs: 5 consecutive runs, 1 test each, 0 failures (BUILD SUCCESSFUL in 57s / 40s / 32s / 33s / 30s)

git diff --check
-> no output (passed)
```

The full-suite run is the authoritative device result for this handoff; targeted runs are
listed separately and are not used to overwrite it.

APK: `/Users/francis/Documents/Project/代肝记录软件/app/build/outputs/apk/debug/app-debug.apk`
SHA-256 `16a9bb2491686df12064d8cac04a016e0ab1ed59b94a95e8f2a3f6a72ba9675c`.

## 8. Device check (emulator, debug build)

The app data was cleared first (`adb shell pm clear com.daigan.record`), then driven
through the real UI:

1. Created owner `BossChen` (B001) and account `Arknights · Official · 10000628`.
2. Opened `为这个账号接新单`, switched to 短期任务, filled `PushMain`, `100.00`, enabled
   建单同时录入首笔实收 with `100.00` (一次结清).
3. Rotated to landscape and back: the filled form survived both recreations
   (`01-form-survives-rotation-landscape.png`, `02-form-restored-after-rotation.png`).
4. Tapped `创建订单`: the app navigated to the new order detail showing
   `已结清 · 约定总价 ¥100.00 / 已收 ¥100.00 / 待收 ¥0.00`
   (`03-detail-after-creation.png`).
5. Rotated on the detail screen: the same order detail was restored, no create command
   was issued (`05-detail-after-recreation.png`).
6. Back returned to the account page — the new-order destination was popped, the account
   lists exactly `订单 · 1` (`04-account-one-order-after-back.png`).
7. Pulled the app database from the device (`adb exec-out run-as com.daigan.record cat`
   for `daigan-record.db`, `-wal`, `-shm`) and queried it: `orders = 1`,
   `order_creation_operations = 1`, `receipts = 1` (10000 cents, `ACTIVE`),
   `task_definitions = 1`, and the operation row points at that order and that receipt.

Screenshots: `docs/handoffs/screenshots/phase-3-fix-2/` (5 images).

Limitations of this device check: it did not force process death in the middle of a
submission, did not reproduce a specific physical double-tap timing, and did not exercise
a restored *successful* form after process death. Those paths are covered only by the
ViewModel/room contract tests and the `SavedStateHandle` restoration test, which is what
they claim.

## 9. Unverified items and remaining risks

- Android process death during a create submission was still not forced on a device. The
  pending-command payload, the committed order id and the navigation-pending flag are all
  persisted and unit/room tested, but a real `am kill`/restart walkthrough was not
  performed.
- The success card is normally visible only for the frame before navigation; the
  acknowledged-restoration path (`查看订单` / `再建一张`) was not exercised end-to-end on
  the device.
- The `NavigationTest` flake is explained by asynchronous readiness, not reproduced
  deterministically; the fix removes the unconditional assertion but cannot prove that no
  other cross-test timing issue exists.
- Emulator only; no physical device, no release signing, no export or cloud features, no
  Phase 4 templates/reminders/backups.

## 10. Next step

Independent acceptance of Phase 3 (including this repair). Phase 4 remains unstarted.
