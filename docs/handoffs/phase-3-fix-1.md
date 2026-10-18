# Phase 3 fix 1 — executable baseline, command idempotency and consistent money

Date: 2026-10-18. Baseline: `f485b7e`. Branch: `codex/phase-2-undo-fix` (local).
Implementation commit: `9aa4cf4`. Independent acceptance report
(`docs/handoffs/2026-10-18-phase-3-review.md`) is unchanged and remains untracked.

This repair addresses every blocker in that report without rewriting Phase 1/2 work,
without changing the historical `1.json`/`2.json`/`3.json` schemas and without weakening
any accepted test.

## 1. Executable regression baseline

- `Phase2UndoReviewTest` now constructs `OrderDetailViewModel` with `FakeReceiptRepository`
  and `FixedTimeProvider`; all undo, repeated-submission, failure-recovery, occurrence
  identity, attachment and archive assertions are retained.
- `Phase2ManualProbeTest` now supplies the real `ReceiptLedger`, `receiptDao` and
  `creationOperationDao` to `DefaultOrderRepository`.
- `NavigationTest` no longer asserts a stale literal: the income label is located by the
  `stats-income-label` test tag and checked for “实收” + “月” as substrings after waiting
  for the first snapshot. The month-specific title and the two honest ¥0.00 amounts are
  still asserted.
- `OrderDetailScreenTest` scopes the two duplicated “分批收款”/“¥300.00” texts to the
  `order-detail-summary` and `order-detail-payment-section` regions instead of accepting
  “any one node”. The short-order summary card now carries the summary tag (this was a
  real gap: only the hosting summary had it).

The repaired tree compiles and runs both complete suites with no temporary constructor
adaptations.

## 2. Idempotent order creation as one command

**Model.** `ShortOrderDraft`/`HostingOrderDraft` carry `creationOperationId`. A normalized
fingerprint (`OrderCommandFingerprint`) covers the payload that actually creates the
order: type, owner/account, trimmed title, integer-cent price, payment mode, dates,
notes, refresh rules, task snapshot and the normalized initial receipt (including its
`operationId` and millisecond `receivedAt`).

**Persistence (v5).** `order_creation_operations(operationId PK, orderType, fingerprint,
orderId FK RESTRICT, receiptId, createdAt)`. `MIGRATION_4_5` creates only this table;
existing orders get no row, because inventing an identity would misclassify them as a
retry.

**Transaction.** `createOnce` runs inside `database.withTransaction`: it returns the
stored order for an identical fingerprint, rejects a different fingerprint
(`CREATION_CONFLICT_MESSAGE`), otherwise inserts the order, tasks, hosting period and
optional initial receipt, then stores the operation result. Any validation or receipt
failure rolls the whole transaction back. A concurrent loser is rolled back by the
unique key and re-reads the winner's committed row.

**UI.** `NewOrderViewModel.submit()` acquires the `saving` gate synchronously before
launching the coroutine. The create/receipt operation ids, the fingerprint and the exact
form payload live in `SavedStateHandle` through `PendingOrderCommandCodec`; an identical
retry after failure or process restoration reuses them, while a changed payload is
treated as a new command with new ids.

**Correction (Phase 3 fix 2).** Successful submission used to clear the pending command,
rotate both ids and re-enable the button immediately, which let a repeated tap on the
still-visible success form become a second order. Success is now a terminal state for
that form instance (`NewOrderUiState.createdOrderId`): the committed order id, the
operation ids and the payload are kept until the navigation is acknowledged, and only
the explicit “再建一张” action (`startNewCreation()`) allocates new ids.

## 3. One timestamp precision

`PersistedInstant.normalize` truncates to epoch milliseconds. It is applied to
`createdAt`, `receivedAt` at write time, the normalized payment/correction command, the
creation fingerprint, retry comparisons and returned results. Sub-millisecond retries
therefore compare equal after a Room round trip. Covered by
`Phase3FinancialContractTest.timestampRetrySurvivesDatabaseReopenForPaymentAndAmendment`
(database closed and reopened on disk) and `OrderCommandFingerprintTest`.

## 4. Full correction payload

`normalizedCorrection()` includes target receipt, kind, amount, `receivedAt`
(milliseconds), channel, normalized note **and audit reason** for both the request and
the stored row. An identical retry returns the original audit with `alreadyApplied =
true` and adds no row; any differing field, including a different reason, is rejected
atomically. Tested for amend and void, including after a database reopen.

## 5. Actual initial-receipt time

`InitialPaymentDraft.receivedAt` (nullable = creation time) is validated against the
recording clock, normalized to milliseconds and stored in the creation transaction and
fingerprint. The new-order screen exposes 收款日期/收款时间 (shared `InstantFields`,
defaults to now), and the ViewModel keeps the value in `SavedStateHandle`. Test
`initialReceiptKeepsItsActualMonthWhileTheOrderCountsInOctober` proves a September
receipt on an October order counts in September income, not October income, while the
October order count still includes the order; retries preserve the submitted time.

## 6. Consistent financial snapshots

`LedgerSnapshotSource` now reads orders, periods, receipts, corrections, accounts and
owners inside **one** `database.withTransaction`; invalidation is the merge of the six
Room table observations. `StatisticsRepository` and receipt history derive from that
one snapshot. No debounce/delay was added.

**Correction (Phase 3 fix 2).** The original text also claimed order detail derived
from that snapshot. It did not: `OrderRepository.observeOrderDetail` combined its own
owner/tasks/occurrences/attachments/periods/receipts/corrections DAO flows, so one
commit could be published as a mix of two database versions. Order detail now performs
its own per-order read inside one `database.withTransaction`
(`OrderRepository.readOrderDetail`), invalidated by the same Room table observations.
That is a separate transaction from the global ledger snapshot: the two read models
agree on committed data, but the detail is not literally the same snapshot instance.
`OrderDetailConsistencyTest` keeps a detail subscriber alive through
create-with-receipt → payment → amendment → void and fails on a receipt without its
period, a balance that disagrees with its own active receipts, or a lost history entry.

`LedgerSnapshotConsistencyTest` keeps three subscribers alive while creating an order
with an initial receipt, renewing, paying, amending and voiding. Every emission must
satisfy referential integrity and must not lose previously seen receipts/history; the
subscriptions must stay alive and the final financial values must agree across paths.

## 7. Coverage added

- `Phase3FinancialContractTest` — 17 real-Room cases (settlement, cents, idempotency,
  concurrency, relationships, rollback, correction audit, cross-month/timezone,
  archive/completed/expired debt, renewal, historical period, cross-screen, reopen).
- `LedgerSnapshotConsistencyTest` — continuous-subscription consistency.
- `MigrationV3ToV5Test` — v3 real data → v5 and v4 → v5 (no fabricated rows).
- `OrderCommandFingerprintTest`, `PendingOrderCommandCodecTest` — JVM.
- Accepted `Phase3AcceptanceReviewTest` remains unchanged and passes all five cases.

## Verification ledger

Environment: JDK 21, Android SDK `/Users/francis/Library/Android/sdk`, emulator
`Medium_Phone_API_36.1` (Android 16 / API 36, arm64).

- `./gradlew :app:testDebugUnitTest --offline --no-build-cache --console=plain` (clean
  build after deleting stale kapt output) → all JVM suites green.
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline
  --console=plain` → BUILD SUCCESSFUL. Parsed JVM XML: **81 tests, 0 failures,
  0 errors, 0 skips**. Lint: **0 errors, 2 warnings** (`ObsoleteSdkInt`),
  2 hints (`AutoboxingStateCreation`).
- Instrumentation (all 24 classes; the single full-suite invocation was interrupted by
  the user, so the same classes were executed in three batches):
  - data/local/repository: **86 tests, 0 failed**;
  - `review.*` + `ManualBoundarySchedulerTest`: **25 tests, 0 failed**;
  - UI: **33 tests, 0 failed**.
  Total **144 instrumentation tests, 0 failures/errors/skips** on the emulator above.
  (The earlier review measured 123 tests with 7 failures on the unmodified tree.)
- `git diff --check` → passed.
- APK: `/Users/francis/Documents/Project/代肝记录软件/app/build/outputs/apk/debug/app-debug.apk`
  SHA-256 `4d24a795c0b63bf5c33fe0a4b46ab7b9c32380e00849f93d48aba3f074cb20c1`.

### Walkthrough

The device walkthrough in `docs/handoffs/phase-3.md` (screenshots
`docs/handoffs/screenshots/phase-3/`, 19 images) covers order creation with and without
an initial receipt, task completion, partial payment, amendment, voiding, month/all-time
stats, Settings receipt history, restart persistence, expired-period payment, renewal
with preserved old debt, explicit historical-period settlement, dark theme, 1.3× font
and a 720×1600 screen.

## Unverified items and limitations

- Owner archiving with outstanding debt was not reproduced in the UI walkthrough because
  an unexpired hosting period correctly blocks archiving; the contract is covered by
  `Phase3FinancialContractTest`.
- Killing the process mid-submission was not forced; restoration is unit-tested through
  the codec and retries-after-reopen are tested on device.
- Long free-text fields were not exercised on device beyond normal-length data.
- Emulator only; no physical device, no release signing, no export/cloud features.
- `Phase3AcceptanceReviewTest` was committed unchanged with the implementation; the
  reviewer reports remain untracked so the independent record stays untouched.
