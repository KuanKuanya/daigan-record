# Phase 3 — receipts, balances and statistics

Date: 2026-10-18. Baseline: `f485b7e`. Branch: `codex/phase-2-undo-fix` (local; no merge or
remote publication requested). Implementation commit: `9aa4cf4`
(`feat(phase-3): receipts, corrections and idempotent order creation`).

The independent Phase 3 acceptance found blockers; the repair pass is recorded separately
in `docs/handoffs/phase-3-fix-1.md`. This document describes what Phase 3 delivers and
which evidence was produced for the delivered state.

## Delivered behaviour

**Money and receipts.** `receipts` stores actual money received. Amounts are integer
cents (`Money.MAX_CENTS = 9_999_999_999`, ¥99,999,999.99). A receipt has an explicit
`receivedAt` (actual arrival, epoch milliseconds) separate from `createdAt` (recording
time) and belongs either to a hosting period or to a short order — never both, never
neither. Only user-entered money counts; a price never becomes a receipt.

**Balances.** `Balances`/`OutstandingItem` derive, per receivable item:

- price = short-order `priceCents`, or the hosting period's `priceCents`;
- paid = Σ `ACTIVE` receipts for that item (`REPLACED`/`VOIDED` count as zero);
- outstanding = `max(0, price - paid)`;
- only items with outstanding > 0 appear in the receivable list.

`ONE_TIME` accepts only a payment that settles the item exactly; `INSTALLMENTS` accepts
any amount up to the remaining balance. Overpayment, zero, negative, sub-cent and
over-limit amounts are rejected before any row is written.

**Corrections.** `receipt_corrections` is an audit log. `VOID` marks the original
`VOIDED` and stores a before-snapshot. `AMEND` inserts a new `ACTIVE` row with the new
amount/time/channel/note, marks the old row `REPLACED`, and stores both before and after
snapshots. History is never deleted; a voided or replaced row remains visible with its
reason. The amendment ceiling is the item price minus other effective receipts.

**Screens.**

- Order detail shows per-period and total receivable / paid / outstanding, the payment
  mode, a `记录收款` action with an explicit period selector, and the receipt list
  (including replaced/voided rows with audit reasons and `更正`/`作废` actions).
- New order can record an optional first receipt, including its actual arrival
  date/time (defaults to now, historical values allowed).
- Stats shows calendar-month or all-time income (`receivedAt`), all-time outstanding
  (independent of the range), order counts by `createdAt` (hosting vs short), a weekly
  or six-month income trend, and tappable outstanding items.
- Settings → 收款明细 is an independent page listing every receipt (effective,
  replaced, voided) with the correction audit.

**Semantics.** Income is attributed by actual receipt time in the reporting timezone;
order counts by creation time; renewal never increases the order count; archived,
completed and expired debt stays collectible while archived tasks remain read-only.

## Schema and migrations

- v3 → v4 (`MIGRATION_3_4`): creates `receipts` and `receipt_corrections` only.
  No receipt is inferred from historical prices.
- v4 → v5 (`MIGRATION_4_5`, repair pass): creates `order_creation_operations` only.
- `1.json`/`2.json`/`3.json` are unchanged read-only fixtures; `4.json` is the Phase 3
  first schema and `5.json` the current one. Details: `docs/data-model.md`.

## Verification

Environment: JDK 21 (`/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home`),
Android SDK `/Users/francis/Library/Android/sdk`, emulator `Medium_Phone_API_36.1`,
Android 16 (API 36), arm64.

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline
  --console=plain` → BUILD SUCCESSFUL. Parsed JVM XML: **81 tests, 0 failures,
  0 errors, 0 skips**. Lint: **0 errors, 2 warnings** (`ObsoleteSdkInt`),
  2 hints (`AutoboxingStateCreation`).
- Instrumentation, all 24 test classes in three class batches (the single full-suite
  invocation was interrupted by the user, so every class was re-run in batches):
  - data/local/repository classes → **86 tests, 0 failed**;
  - `review.*` + `ManualBoundarySchedulerTest` → **25 tests, 0 failed**
    (includes the five-case independent `Phase3AcceptanceReviewTest`);
  - six UI classes → **33 tests, 0 failed**.
  - Total **144 instrumentation tests, 0 failures, 0 errors, 0 skips**.
- `git diff --check` → passed.
- Debug APK: `/Users/francis/Documents/Project/代肝记录软件/app/build/outputs/apk/debug/app-debug.apk`
  SHA-256 `4d24a795c0b63bf5c33fe0a4b46ab7b9c32380e00849f93d48aba3f074cb20c1`.

### New automated coverage

- `Phase3FinancialContractTest` (real Room): partial/full settlement, `ONE_TIME`
  full-balance rule, cent precision/overflow/overpayment, creation idempotency and
  conflicting reuse, concurrent create/payment, invalid period relationships,
  creation-with-receipt rollback, amendment/void audit and balance recovery,
  September receipt in an October order, reporting-timezone month boundaries,
  archived/completed/expired debt collection with read-only archived tasks, renewal
  preserving old debt and receipts, explicit historical-period payment, cross-screen
  agreement and persistence after a database reopen.
- `LedgerSnapshotConsistencyTest`: continuous subscribers across create-with-receipt →
  renewal → payment → amendment → void never observe a receipt without its order/period,
  never lose a history entry and never terminate the subscription.
- `MigrationV3ToV5Test`: v3 with real owners/accounts/orders/periods/tasks/occurrences/
  completion batches/attachments upgrades field by field with empty Phase 3 tables;
  v4 → v5 invents no creation operation.
- `OrderCommandFingerprintTest`, `PendingOrderCommandCodecTest` (JVM): normalized
  fingerprints and process-restoration payload round trip.

## Manual walkthrough (emulator, debug build)

Screenshots: `docs/handoffs/screenshots/phase-3/`.

1. Created owner `BossChen` and account `Arknights · Official · 10000628`.
2. Created a ¥200 hosting order with a ¥80 initial receipt (`01`, `02`).
3. Recorded a ¥50 partial payment (`03`), amended it to ¥60 with reason `AmountFix`
   (`04`), then voided the ¥80 initial receipt with reason `Refund` (`05`);
   detail recovered to ¥200 / ¥60 / ¥140 and history kept all three rows.
4. Created a ¥100 short order with a ¥50 initial receipt and completed its task (`06`).
5. Stats showed ¥110.00 month income and ¥190.00 all-time debt with both outstanding
   items (`07`, `08`); Settings → 收款明细 listed all four rows with `已更正`/`已作废`
   (`09`); `全部累计` and restart persistence verified (`10`, `11`, `12`).
6. Created a ¥60 hosting order for 2026-10-01…10-05, paid ¥20 against that expired
   period, renewed for 10 days at ¥50: the new period started at zero receipts, the old
   ¥40 debt remained, then it was settled by explicitly selecting 第 1 期 (`13`–`16`).
7. Dark theme (`17`), 1.3× font (`18`) and a 720×1600 narrow screen (`19`) rendered the
   order detail without clipping.

## Unverified items and limitations

- Archiving an owner while debt remains was **not** performed in the walkthrough: both
  hosting orders had an unexpired period, which correctly blocks archiving. The
  archived-debt and read-only-task contracts are covered by
  `Phase3FinancialContractTest.archivedExpiredAndCompletedDebtRemainsCollectible...`.
- Process death in the middle of a create submission was not forced on the device; the
  pending-command payload is restored via `SavedStateHandle` and unit-tested through
  `PendingOrderCommandCodecTest`, while database-reopen retries are exercised on device.
- Very long free-text fields were not separately captured on device; the narrow-screen
  and large-font checks only cover normal-length data.
- Emulator only; no physical device, no release signing, no export or cloud sync.
