# UI Polish, Hosting Calendar, Reminder Time, and Template Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved streamlined Android UI with a hosting completion calendar, direct reminder time entry, and only the generic built-in template.

**Architecture:** Keep the existing Compose → ViewModel → repository → Room boundaries and Material 3 theme. Derive calendar state from existing period, task, occurrence, refresh-window, and history models; do not introduce a second progress store. Reconcile obsolete built-in templates transactionally while leaving user templates and order snapshots intact.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Room, kotlinx.coroutines, existing Gradle JVM and Android instrumentation tests.

**Spec:** `docs/superpowers/specs/2026-10-19-ui-polish-calendar-reminders-templates-design.md`

## Global Constraints

- Target typography: page title approximately 22sp; section/card title 16sp; body 14–15sp; supporting text 12–13sp.
- Preserve user system font scaling and accessible control sizes.
- Completed calendar dates use a warm yellow accent; current day uses an outline; both states can coexist.
- Calendar dates and occurrence attribution use the hosting period's saved game timezone and refresh rules.
- Keep one generic built-in template and the existing validated JSON import flow.
- Preserve user-created/imported templates, accounts, orders, period/task snapshots, financial history, attachment data, and existing schema/migration contracts.
- Remove repetitive explanatory copy while retaining input labels, user-entered notes, empty/error states, and short safety confirmations.
- Do not add UI frameworks or duplicate calendar persistence.

## Review Focus

- A weekly refresh window crosses a month boundary or hosting-period edge; its occurrence must remain associated with the saved game date and appear exactly once. Test: month summary for a weekly window crossing month and clipped by period boundaries.
- A calendar date has multiple recurring tasks with only some completed; it must show partial progress and remain unlit. Test: mixed completed/incomplete occurrences on the same game date.
- A date has no due occurrence, is outside the selected period, or is in a later period. Test: empty/nonexistent dates are not marked complete and are not writable.
- A removed built-in ID is still an account default, while an imported/user template shares its display name. Test: transactional cleanup clears stale defaults, preserves the user template and all order snapshots.
- Time input is blank, partial, malformed, or outside 00:00–23:59; it must not persist an invalid value. Test: parser boundary and cancel/save behavior.

---

### Task 1: Keep the generic template and remove the two obsolete built-ins

**Files:**
- Modify: `app/src/main/java/com/daigan/record/data/template/BuiltInTemplates.kt`
- Modify: `app/src/main/java/com/daigan/record/data/repository/GameTemplateRepository.kt`
- Modify: `app/src/main/java/com/daigan/record/data/local/dao/TemplateDao.kt`
- Modify: `app/src/main/java/com/daigan/record/feature/templates/TemplateLibraryViewModel.kt`
- Modify: `app/src/main/java/com/daigan/record/feature/templates/TemplateLibraryScreen.kt`
- Test: `app/src/androidTest/java/com/daigan/record/data/repository/GameTemplateRepositoryTest.kt`, `app/src/androidTest/java/com/daigan/record/data/repository/TemplateOrderSnapshotTest.kt`, and `app/src/androidTest/java/com/daigan/record/ui/TemplateLibraryUiTest.kt`

**Interfaces:**
- Consumes: existing `BuiltInTemplateDescriptor`, template provenance, account default IDs, validated template importer.
- Produces: idempotent generic-only initialization and cleanup of the legacy `reverse-1999` and `wuthering-waves` built-in rows, clearing only their account defaults.

- [ ] **Step 1: Write the failing repository tests** for fresh generic-only seeding; cleanup of both pre-existing built-in seed IDs; idempotent repeated startup; stale account-default clearing; preservation of a user/imported same-name template; unchanged order/period/task snapshots.
- [ ] **Step 2: Run the focused tests and confirm they fail** because both game-specific templates are still seeded/present.

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.data.repository.GameTemplateRepositoryTest --offline`

Expected: failures identify the extra seeds and missing legacy cleanup.

- [ ] **Step 3: Implement generic-only seeding and transactional legacy cleanup**; retain seed markers so removed IDs cannot reappear; keep import preview/conflict resolution and generic editing available.
- [ ] **Step 4: Add/adjust the library UI regression test** to show one generic built-in row and a working import action, with neither removed built-in source entry displayed, while a same-name user/imported entry remains visible.
- [ ] **Step 5: Run focused repository and template UI tests.**

Run the repository and UI instrumentation classes separately:

`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.data.repository.GameTemplateRepositoryTest --offline`

`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.ui.TemplateLibraryUiTest --offline`

Expected: both pass; existing-order snapshot assertions remain unchanged.

### Task 2: Establish a consistent type scale and shorten persistent copy

**Files:**
- Modify: `app/src/main/java/com/daigan/record/ui/theme/Type.kt`
- Audit UI under `app/src/main/java/com/daigan/record/feature/**` and update shared typography consumers. Likely concise-copy edits: `feature/today/TodayScreen.kt`, `feature/accounts/AccountsScreen.kt`, `feature/accounts/OwnerDetailScreen.kt`, `feature/orders/OrderDetailScreen.kt`, `feature/settings/SettingsScreen.kt`, `feature/settings/ReminderSettingsScreen.kt`, `feature/settings/BackupScreen.kt`, `feature/templates/TemplateLibraryScreen.kt`, and `feature/templates/TemplateEditorScreen.kt`; add a file to this list only when the audit finds a repeated explanatory paragraph.
- Test: Compose UI tests for the affected screens, especially `OrderDetailScreenTest`, `ReminderSettingsUiTest` (create if absent), and `TemplateLibraryUiTest`.

**Interfaces:**
- Consumes: the approved Material 3 scale and copy policy in the spec.
- Produces: consistent semantic text roles; short task-focused screens with essential field labels, state, error and safety copy retained.

- [ ] **Step 1: Add failing UI assertions** for the specified concise page headings/actions and for removal of redundant explanatory paragraphs from backup, reminders, templates, and order detail; retain assertions for restore replacement and unencrypted-password safety notices.
- [ ] **Step 2: Run the focused UI tests** and confirm the new concise-copy assertions fail against current content.
- [ ] **Step 3: Update the shared typography tokens** to the approved sizes/line heights and remove repeated explanatory paragraphs, preserving user notes, critical safety copy, accessibility labels, and meaningful empty/error states.
- [ ] **Step 4: Run UI tests at default font scale and with a 1.4x system font scale**; verify scroll access to bottom actions and readable labels.

Run the dedicated UI classes separately with `-Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.ui.OrderDetailScreenTest` and `...class=com.daigan.record.ui.TemplateLibraryUiTest`; run the dedicated reminder UI test created in Task 4.

Expected: concise-copy checks pass; safety and user-entered content remain visible and UI controls remain reachable with large text.

### Task 3: Add the period-scoped hosting completion calendar

**Files:**
- Create: `app/src/main/java/com/daigan/record/feature/orders/HostingCalendarModel.kt`
- Modify: `app/src/main/java/com/daigan/record/feature/orders/OrderDetailScreen.kt`
- Modify: `app/src/main/java/com/daigan/record/feature/orders/OrderDetailViewModel.kt` only if selection needs observable state.
- Test: `app/src/test/java/com/daigan/record/feature/orders/HostingCalendarModelTest.kt` and `app/src/androidTest/java/com/daigan/record/ui/OrderDetailScreenTest.kt`.

**Interfaces:**
- Consumes: `OrderDetail`, `HostingPeriod`, `OrderTask`, `TaskOccurrence`, `OccurrenceRules.slotsForDate(...)`, and existing task completion/backfill callbacks.
- Produces: `buildHostingCalendarMonth(detail: OrderDetail, period: HostingPeriod, month: YearMonth, today: LocalDate): HostingCalendarMonth`; each date provides scheduled occurrence rows (task ID, occurrence key, title, completion state), status `NONE | OPEN | PARTIAL | COMPLETE`, and a period completed-day count. Derived only; no persisted calendar state.

- [ ] **Step 1: Write failing model tests** for daily, weekly, selected-weekday and every-N-day recurrence; all/partial/no due tasks; backfill and undo; month boundaries; today/completed overlap; and period boundaries.
- [ ] **Step 2: Run `HostingCalendarModelTest` and verify** failures expose the missing model/status behavior.
- [ ] **Step 3: Implement the pure calendar model** using each period's stored timezone and refresh snapshot; assign weekly-window occurrences according to their existing attributed game date; count a completed day only when it had due work and all such occurrences are complete.
- [ ] **Step 4: Add the month grid to hosting order detail** with period-aware month navigation with a brief state transition, disabled out-of-period dates, an animated warm-yellow completed state, a today outline, partial-progress marker, and a selected-date task panel wired to current completion/backfill actions.
- [ ] **Step 5: Add Compose tests** for month selection, date status, period switching and task panel updates after completion/undo.
- [ ] **Step 6: Run model and order-detail UI tests.**

Run: `./gradlew :app:testDebugUnitTest --tests '*HostingCalendarModelTest' :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.ui.OrderDetailScreenTest --offline`

Expected: all model/UI tests pass; no schema changes; existing historical records and financial controls still render.

### Task 4: Replace reminder time wheel with direct numeric time entry

**Files:**
- Create: `app/src/main/java/com/daigan/record/feature/settings/ReminderTimeInput.kt`
- Modify: `app/src/main/java/com/daigan/record/feature/settings/ReminderSettingsScreen.kt`
- Test: `app/src/test/java/com/daigan/record/feature/settings/ReminderTimeInputTest.kt` and `app/src/androidTest/java/com/daigan/record/feature/settings/ReminderSettingsUiTest.kt`.

**Interfaces:**
- Consumes: existing `ReminderSettingsViewModel.setTime(category, minutesSinceMidnight)` and settings state.
- Produces: `parseReminderTimeInput(input: String): Int?`, returning minutes since midnight only for valid `HH:mm`; an accessible 24-hour numeric dialog with cancel and save. Invalid values do not call persistence.

- [ ] **Step 1: Write failing parser and UI tests** for `00:00`, `23:59`, partial/malformed input, out-of-range hours/minutes, cancel without a write, and save updating only the chosen category.
- [ ] **Step 2: Run the focused tests** and verify failure because the wheel picker cannot be typed or validated as requested.
- [ ] **Step 3: Implement numeric input and parsing** with explicit 24-hour validation and existing minute persistence; remove `TimePickerDialog` from reminder settings only.
- [ ] **Step 4: Verify accessibility labels, keyboard behavior and cancel/save semantics** with Compose UI tests.

Run: `./gradlew :app:testDebugUnitTest --tests '*ReminderTimeInputTest' --offline` and `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.daigan.record.feature.settings.ReminderSettingsUiTest --offline`.

Expected: boundary/invalid/cancel tests and UI tests pass; reminder scheduling behavior tests remain green.

### Task 5: Integrated visual and data regression, APK, and handoff

**Files:**
- Update: `docs/testing.md`, `docs/handoffs/ui-polish-calendar-templates.md`, and screenshots under `docs/handoffs/screenshots/ui-polish/`.
- Build: `artifacts/ui-polish/daigan-record-v1.1-debug.apk` and SHA-256.

- [x] **Step 1: Run full unit tests, device tests, lint and debug build.**

Run: `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug --offline --no-build-cache --continue`

Expected: zero test failures/errors; lint has zero errors; APK assembles. Record existing warnings without broad dependency changes.

- [x] **Step 2: Review final screens** at default and 1.4x text scale, light/dark themes, long Chinese labels, empty and populated calendar months, previous periods, and permission-denied reminders.
- [x] **Step 3: Compare final database migration/schema artifacts** and confirm this UI/template cleanup adds no new schema unless a separately reviewed need appears.
- [x] **Step 4: Copy the tested APK, record its SHA-256, summarize UI changes, test counts and known limits in the handoff, and run `git diff --check`.**

Expected: deliverable is reproducible from the final build; the report clearly distinguishes emulator checks from physical-device work. Do not publish or push.
