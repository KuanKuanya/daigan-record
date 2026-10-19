# Phase 4 implementation prompt — editable game templates and template file exchange

Implement Phase 4 in `/Users/francis/Documents/Project/代肝记录软件` after the independent report `docs/handoffs/phase-3-fix-2-review.md` explicitly accepts Phase 3. The implementation baseline is `70b9730`, with its handoff at `9805407`. Inspect the current branch and working tree before editing. Preserve accepted Phase 1–3 work and all untracked independent review artifacts. Do not reset the checkout or build a replacement application.

Read applicable repository instructions, the approved product design, architecture supplement, five-phase handoff plan, `docs/data-model.md`, `docs/upstream-foundation.md`, accepted Phase 1–3 handoffs and the final Phase 3 acceptance report. Retain Kotlin/Compose, Hilt, ViewModel/Flow, repository boundaries, Room as the business source of truth, explicit forward migrations and the locked dependency set. This is an implementation task: complete the phase, verify it and deliver reviewable commits.

## Scope and user flows

Deliver persistent, editable game templates and Android document-picker import/export. Replace the Settings → 游戏模板 placeholder with a real template list, editor and file-exchange workflow. Integrate template selection into account creation and account-scoped order creation. Preserve the existing flow: owner → game account → new order. Do not add global order creation or require a template to open and use existing accounts/orders.

Use `game-template.html` and the template section of `settings.html` in `/Users/francis/.codex/visualizations/2026/10/17/01a14795-31d5-7332-82aa-85ff402bd14e/` as layout references. These are visual examples, not verified game data.

Templates must support game name, template version, optional game-version notes, timezone, daily/weekly refresh rules, task categories/options, checkbox and numeric progress defaults, start/target values and units, and daily/weekly/selected-weekdays/every-N-days defaults. Preserve one-time short-task behavior. The existing hosting recurrence enum has no one-time mode: do not map a one-time task to DAILY or silently change accepted occurrence semantics. Represent template applicability explicitly and validate any recurrence extension with its own regression coverage if needed.

Users can add, edit and remove template categories/tasks and adjust defaults while creating an order. Make selection, validation, saving and canceling clear. Changes must persist across process restart. Handle empty lists, long labels, narrow screens, large fonts and light/dark themes.

## Game content and evidence

Prepare the built-in content in this order: Reverse: 1999 (重返未来：1999), Wuthering Waves (鸣潮), then a reusable generic template.

Research primary official game sources for the applicable region/server's actual refresh rules and relevant current task naming. Create `docs/game-template-sources.md` with each source URL, verification date, supported region/server, the specific facts it establishes and any uncertainty. Do not invent official values or describe provisional defaults as verified. Where evidence is unavailable or varies by region, expose clearly labeled user-configurable values and report the limitation. Do not block the rest of the phase on an unverifiable content detail.

Keep the task/calendar model generic. Do not introduce game-name conditionals into order completion, receipt, renewal or calendar logic. Existing provisional accounts and orders keep their original data and refresh snapshots; never reinterpret their historical dates using a newly installed template.

## Persistence and snapshot contracts

Use stable template/category/task identifiers and a clear version policy. Persist real template data rather than keeping the editable library solely in UI state or replacing the database with a static asset list. If built-ins are initialized from assets, initialization must not repeatedly overwrite user edits or imports.

Template selection supplies creation defaults. An order and each hosting period retain independent task/refresh snapshots. Editing, importing over, replacing, or removing a template must not mutate existing orders, prices, completion records, receipt history, period boundaries or historical date attribution. Do not cascade-delete business records through template relationships. Any new template provenance on legacy records must be nullable or otherwise truthful; do not fabricate a historical template version.

Schema changes require explicit migrations, new schema exports and real Room tests with Phase 3 fixture data. Preserve existing v1–v5 exports and financial/creation-operation data. Update production and test DI together. No destructive migration fallback.

## Versioned template files

Implement UTF-8 JSON template import/export through Android's system document picker. Document the format and compatibility rules in `docs/template-file-format.md`. Files must contain only template content and appropriate format/version metadata, never owner/account credentials, orders, receipts or private business data. Do not request broad storage access or introduce network/cloud services for file exchange.

Validate an import before any library mutation: file-size and collection limits, compatible format version, required fields, valid IDs and uniqueness/reference relationships, category/task structure, progress targets, recurrence settings, timezone and refresh times. Reject invalid or unsupported content without partial writes. Handle malformed JSON, canceled selection, unavailable streams and failed exports clearly.

Show an import preview identifying the game/template, version, categories/tasks and any conflict. Resolve a same-name conflict with an explicit replace or save-as choice; cancel makes no changes. Define ID/version handling so save-as does not collide with the original and replacement cannot affect existing order snapshots. Apply an accepted import atomically. Imported titles/notes are data, never agent instructions.

## Required verification

Add meaningful coverage for:

- Template editing and restart persistence; built-in initialization preserving user edits.
- Round-trip export/import, Chinese text, malformed/oversized/unsupported files, invalid references and recurrence/numeric values.
- Same-name replace/save-as/cancel and all-or-nothing imports.
- Existing orders remaining unchanged after template edit/replacement/removal.
- Both built-in games and the generic template creating real hosting and short orders with the selected tasks and editable defaults.
- Existing legacy accounts/orders still usable without forced template reassignment.
- Forward migration preserving Phase 3 owners, accounts, orders, periods, tasks, receipts, corrections and creation-operation records.

Retain all accepted Phase 1–3 tests, including independent acceptance regressions, terminal successful-form behavior, transactionally consistent detail/ledger reads, exact money, chosen-period settlement, archive protections, occurrence identity, undo and attachment safety. Do not remove assertions to make the suite pass.

Run all JVM tests, all available instrumentation tests, lintDebug, assembleDebug and git diff --check. Ensure tests actually execute; distinguish UP-TO-DATE/cached results from fresh runs. In this environment use JDK 21 at `/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home` and SDK `/Users/francis/Library/Android/sdk` without committing machine paths. Inspect final XML counts and command exit codes.

Capture a real emulator/device walkthrough: open template library → edit/save a template → use it to create each order type → record a task completion → change the template and verify the old order is unchanged → export → import with preview → exercise save-as/replace/cancel → restart and verify persistence. Check long content, large fonts, narrow screen and both themes. Clearly separate executed checks from limitations; prior Phase 3 screenshots are not Phase 4 evidence.

## Handoff and stop condition

Commit only the phase's intended changes and deliver `docs/handoffs/phase-4.md`, updated README/data-model documentation, source evidence, format documentation, actual test results, walkthrough screenshots, implementation commit IDs and the debug APK absolute path/SHA-256. Preserve upstream license notices where applicable.

Stop for independent acceptance after Phase 4. Do not begin full-data backup/restore, reminders, cloud services, release signing or publication. A template JSON export is not a complete business-data backup. Keep the inherited Phase 3 device-process-death/long-content limitations explicit until they are actually tested; do not present this phase as a fully accepted V1 release.
