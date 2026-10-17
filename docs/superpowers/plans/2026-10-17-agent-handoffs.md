# 分阶段 Agent 实施与验收

本方案替代原计划的细任务分派方式；原设计、架构补充及开发计划继续作为需求依据。每阶段一个实现 agent，完成后由本对话验收，通过后再下发下一阶段。不得并行实现共享数据库阶段。

## 通用工作规则

- 共用当前项目目录，使用 Git 记录已验收基线。每阶段开始读取仓库指令、设计与上阶段交付；不重置已有改动。
- 实现者可以在自己阶段内拆步骤，但不能把未完成项移交下一阶段或自行扩大范围。
- 状态：待执行 → 实现完成待验收 → 验收通过／退回修改。实现者的“完成”不等于验收通过。
- 阶段报告保存 docs/handoffs/phase-N.md，包含提交 SHA、修改摘要、准确验证命令和结果、未运行项及原因、后续接口。不提交密码或私有账号资料。
- 每阶段结束给出可审查 diff 和提交，检查工作区状态；若工具阻止提交，保留文件并明确说明。禁止为通过检查降低测试、清空用户数据库或伪造报告。
- 下一阶段仅在本对话验收后启动；若需要改变已验收架构，先说明证据与影响，不偷偷替换底座。

## 阶段与交付

| 阶段 | 工作 | 验收关口 |
|---|---|---|
| 1 | 官方底座、真实数据库、老板与账号、导航 | 上游可追溯、能构建、真实存储、迁移演练 |
| 2 | 双类型订单、今日任务、历史补录、附件、续期 | 游戏日正确、事务幂等、历史保留、重启不丢 |
| 3 | 收款、欠款、统计和明细 | 金额精确、跨期正确、重复提交不重收、页面一致 |
| 4 | 可编辑模板、两款游戏内容、文件导入导出 | 模板可复用、来源可核实、更新不改已有订单 |
| 5 | 备份恢复、提醒、整体加固与 APK | 中断恢复、迁移、真实完整流程、交付可安装包 |

阶段 1、2、3、4 各交付 debug APK（可构建时）；首个覆盖日常工作闭环的测试版在阶段 3。阶段 5 才是完整 V1 候选。

## Phase 1 — English implementation prompt

You are implementing Phase 1 of an offline Android app for individual game-service workers. Work in `/Users/francis/Documents/Project/代肝记录软件`. Implement this phase; do not merely propose a plan.

Before editing, read applicable AGENTS.md instructions and these project documents:
- `docs/superpowers/specs/2026-10-17-game-work-manager-design.md`
- `docs/superpowers/plans/2026-10-17-android-v1.md`
- `docs/superpowers/plans/2026-10-17-architecture-foundation.md` (takes precedence for architecture)
- `docs/superpowers/plans/2026-10-17-agent-handoffs.md`

Goal: deliver a buildable, persistent foundation with the approved navigation and owner/game-account management. Use Google's `android/architecture-templates` repository, `base` branch, as actual source provenance, not just conceptual inspiration. Select and pin a compatible upstream commit, inspect its build configuration, preserve its license/notices, and record the SHA, source URL, toolchain and modifications in `docs/upstream-foundation.md`. The latest branch inspected required SDK 37 while this machine had SDK 36.1; verify the current environment and choose a coherent compatible upstream toolchain, preferably a suitable historical commit. Do not mix dependency catalogs from unrelated samples. Establish an upstream baseline build before business changes. Preserve existing project documents. Initialize Git if necessary without replacing the project directory.

Keep the template's Compose, ViewModel, Flow, Room, Hilt and navigation patterns. Use a single app module with feature packages. Centralize business data in Room behind repositories; ViewModels must not call DAOs directly. Use integer cents for money, stable IDs, explicit foreign keys and uniqueness constraints. Small display preferences may use DataStore. Export Room schemas and prohibit destructive migration fallback. Define and document the relational model for owners, game accounts, orders, hosting periods, task definitions/occurrences, receipts and attachments now, but implement only owner/account workflows in this phase. Coordinate migration fixtures with the initial schema without shipping a fake version upgrade.

Implement the approved three main navigation labels: 今日事项 (default, with 托管打卡 / 短期任务 tabs), 账号信息, 数据统计. Settings opens as an independent destination with back navigation and four entries: 收款明细, 游戏模板, 提醒设置, 备份与恢复. Unimplemented destinations must show honest empty/pending states, never fake financial or work data. The account section groups by owner. Its only top-level creation button is 新增. There must be no global create-order action. Each owner has an immutable automatically assigned human-readable number and editable nickname, multiple game accounts, and an owner detail screen. Account fields: game, server, UID, login account, password or verification-code login. Clear obsolete passwords when switching to code login. Support creating/editing owners and accounts, viewing/copying credentials without authentication gates, and archive rather than destructive deletion of referenced records. Provide the account-scoped order entry for the next phase without pretending orders already work. Do not log credentials.

Use the approved mockups in `/Users/francis/.codex/visualizations/2026/10/17/01a14795-31d5-7332-82aa-85ff402bd14e/` as layout references, especially `account-info.html`, `today-work.html`, and `settings.html`; the old embedded settings inside today-work is superseded. Mockup state/scripts are not production architecture. Chinese UI, system light/dark themes, minSdk 26 unless compatibility evidence requires an explicitly documented adjustment; working app name 代肝记录, applicationId com.daigan.record.

Verify meaningful owner/account tests, real Room persistence/constraints, concurrent number allocation, password-mode switching, a migration test fixture, empty/long-content states, and navigation. Run unit tests, Android lint and debug APK build. Run instrumented tests if a device is available; otherwise report them as NOT RUN, not passed. Reopen/relaunch persistence must be verified on a device when possible. Local ADB was previously sandbox-blocked; report the actual limitation if it persists.

Do not implement tasks, renewal, financial flows, backup or reminders yet. Commit the phase when possible, leave unrelated changes intact, and write `docs/handoffs/phase-1.md` including upstream SHA, project commit, schema/interfaces, exact commands/results, APK location, and known limitations. Stop for external review; do not start Phase 2. Build or network blockers require a precise report, not invented success.

## Phase 2 — English implementation prompt

Implement Phase 2 in `/Users/francis/Documents/Project/代肝记录软件`, only after Phase 1 has been accepted. Read repository instructions, the approved design, architecture supplement, implementation plan, this handoff document, and `docs/handoffs/phase-1.md`. Inspect the actual accepted code and interfaces before editing. Preserve the pinned upstream architecture, Hilt, repository boundaries and persisted data. Do not regenerate the app.

Deliver real account-scoped short-term orders and recurring hosting orders, order details, today's work, historical check-in correction, screenshots, and hosting renewal. Layout references are `new-order.html`, `today-work.html`, and `order-detail.html` in the approved visualization directory. Replace their mock behavior with Room-backed state. Orders belong to exactly one owner's game account; concurrent hosting and short-term orders on the same account are allowed. Multiple tasks share one negotiated order price. Task progress is checkbox or start/current/target/unit. Deadline sorting puts overdue short tasks first. Creation must capture payment-mode choice but must not offer a nonfunctional initial-receipt control before Phase 3 integrates real payment recording.

Implement explicit, testable game-calendar rules using injected time and per-order snapshots of timezone/daily/weekly refresh settings. Daily, weekly, selected weekdays and every-N-days recurrence must work; every-N-days is anchored to the hosting-period start. Store unambiguous task occurrence keys and recording timestamps. Hosting dates include both first and last game dates. Expired orders generate no new work. Do not seed unverified official game schedules: provisional editable settings must be labeled as such until Phase 4 supplies verified templates.

Support individual, whole-order and all-current-hosting completion. Persist exact batch changes for undo so pre-existing completed tasks remain unchanged. Repeated submissions must be idempotent. Completed hosting cards fold at the bottom; preserve historical misses. Allow browsing past dates and correcting/backfilling with an explicit backfill timestamp. Attach actual selected screenshots to the relevant task/check-in using durable private files, with view/replace/delete and failure cleanup.

Renew an expired hosting order in place: same order ID, a new period beginning on the renewal date in the order's game timezone, new duration/end and price, no new order count, fresh current-period check-ins and zero current-period receipts. Preserve all previous periods/occurrences and any existing debt data. Use transactions and an idempotency key. Prevent overlapping early-renewal periods in this version. Task configuration is copied into the new period; do not mutate old snapshots.

Implement unit tests for refresh boundaries/timezones/weekly/custom cycles, expiry, undo, double taps, historical corrections, same-day period keys, date inclusivity and renewal history; use real Room integration tests for persisted invariants. Verify screenshots survive process restart and attachment failures do not create broken successful records. Add explicit migrations if Phase 1 schema changes; retain schema history. Run unit tests, lint, APK build and available instrumentation. Clearly mark unrun checks.

Commit and write `docs/handoffs/phase-2.md` with actual interfaces, migration changes, command evidence, APK location and limitations. Stop for external review before financial work.

## Phase 3 — English implementation prompt

Implement Phase 3 in the existing project at `/Users/francis/Documents/Project/代肝记录软件`, only after Phase 2 acceptance. Read the approved design/architecture/plan and both accepted phase reports. Inspect existing schema and repository contracts; extend them instead of replacing them.

Deliver all real payment, debt and statistics flows. References: `income-stats.html`, the payment portions of `new-order.html`, `order-detail.html`, and the receipt-history destination in `settings.html`. Payment channels are exactly 微信, 支付宝, 其他, with optional notes for other. Orders support one-time settlement and installments. Order creation may record the initial payment atomically with order creation; unpaid orders remain possible in either payment mode. When using one-time settlement, the recorded payment settles the remaining balance; installment mode allows partial payments. Collect actual receipt date/time and use integer cents, never floating-point balances.

Each hosting receipt is explicitly assigned to a period selected by the worker; do not automatically pay oldest debt. Short orders do not need hosting-period selection. Preserve prior-period debts across renewal. Calculate totals from period prices and actual receipts; do not persist independent page-local balances. Use transactions and operation IDs to prevent double receipts and overpayment on concurrent submits. Preserve correction history rather than silently deleting money records.

Statistics default to current month with an all-time option. Revenue is based on actual receipt timestamps; order counts on initial order creation, excluding renewals. Current financial reports use the device's current reporting timezone consistently, separate from game refresh rules. All outstanding debt, including old periods/months, is always visible regardless of period selection. Partial receipt keeps the remaining debt visible; fully paid items leave the outstanding list and remain in settings receipt history. Charts must show actual queried data and correctly handle zero/empty amounts. Cross-screen updates must agree immediately through repository flows.

Test exact cents, partial/full payments, concurrent duplicate submission, chosen period allocation, renewal debt, atomic create-with-receipt, receipt-date month boundaries, renewals excluded from counts, old debt under monthly filters, and real Room aggregate consistency. Verify end-to-end creation → tasks → partial receipt → renewal → historical debt payment → receipt history. Run unit tests/lint/debug build and available device tests. Preserve migrations and existing data.

Commit and write `docs/handoffs/phase-3.md` with evidence, APK and limitations. This phase should produce the first coherent core-workflow test APK. Stop for review; do not start templates.

## Phase 4 — English implementation prompt

Implement Phase 4 in the accepted existing Android app at `/Users/francis/Documents/Project/代肝记录软件`. Read all applicable instructions, the approved spec, architecture supplement, plan and accepted phase reports 1–3. Preserve the working app and data model contracts.

Deliver real editable game templates and integrate them into account creation and order task selection. Follow `game-template.html` and the template destination of `settings.html`. Templates include name, template version, optional game-version notes, timezone, daily/weekly refresh rules, task categories/options, checkbox or numeric defaults, and daily/weekly/selected-weekdays/every-N-days/one-time default recurrence. Users can edit all these values and add/remove categories/tasks. Template updates never mutate existing order/period snapshots or reinterpret historical dates. New orders can adjust template-derived defaults.

Build verified templates in this sequence: Reverse: 1999 (重返未来：1999), Wuthering Waves (鸣潮), then a generic template. Research official game sources for actual refresh rules and current task/map naming; document URLs, verification date and facts in `docs/game-template-sources.md`. Do not invent official values. If a fact cannot be verified, make it explicitly user-configurable and report it. Ensure the generic model can express both game styles without hardcoding game-specific branching in order/check-in logic. Previously created provisional accounts/orders must keep their original snapshots.

Implement versioned UTF-8 JSON import/export via Android's system document picker. Template files contain no owner credentials or financial data. Validate structure, size, versions, stable task/category IDs, cycles, numeric targets and refresh settings before writes. Preview import and resolve same-name conflicts explicitly as replace or save-as; replace must still preserve old order snapshots. Unknown incompatible versions must not partially change the database. Preserve upstream license notices for any reused code.

Test file roundtrip, bad/unsupported imports, duplicate-name resolution, editing after an order exists, custom cycles, numeric task defaults, both built-in templates, and actual creation of both order types from templates. Run unit tests/lint/APK build and available instrumentation, including migration tests if schema changes.

Commit and write `docs/handoffs/phase-4.md` with template source evidence, formats, schema changes, actual check results, APK and limitations. Stop for review before backup/reminder implementation.

## Phase 5 — English implementation prompt

Implement Phase 5 in `/Users/francis/Documents/Project/代肝记录软件` only after Phase 4 acceptance. Read repository instructions, all approved design/architecture documents and accepted handoffs 1–4. This phase completes V1; do not replace the established architecture or simplify financial/history guarantees.

Deliver backup/restore, reminders, final settings integration and a fully verified installable APK. Backup password is OPTIONAL: blank means an unencrypted archive; a supplied password requires matching confirmation and authenticated encryption using standard platform primitives. Include all owners/accounts/credentials, templates and snapshots, orders/periods, tasks/check-ins, receipts, screenshots and relevant settings. Export a consistent logical snapshot with format/schema versions, attachment manifest and checksums; do not copy just an open SQLite main file. Never log secrets. Version the encrypted envelope and key-derivation parameters; use established AES-GCM and a standard password KDF, not custom cryptography.

Validate and stage imports before replacing data; show a summary and require explicit confirmation in the product. Restore is whole-dataset replacement, not implicit merge. Preserve a complete rollback snapshot. Database and attachment replacement must have a durable recovery marker/state machine: process death at each stage must recover to either the original complete dataset or the new complete dataset. Handle wrong passwords, missing/corrupt attachments, unsupported versions, insufficient storage, malicious archive paths and decompression limits without damaging current data. Settings restore must remain coherent with business data.

Implement separately configurable short-deadline, hosting-expiry and unfinished-task reminders. Use current persisted state and unique scheduled work; stop irrelevant reminders after completion/expiry/renewal. Handle timezone/settings changes and reboot; request notification permission when enabling reminders and keep the app functional when denied. WorkManager delivery may be delayed; do not promise exact minute delivery or require a permanently running background service.

Run complete unit, real Room, migration, backup roundtrip/failure/interruption and UI tests. Verify historical database upgrades with actual fixture data. Test an actual full workflow on an emulator or device: create owner/accounts, both orders, tasks and screenshots, undo/backfill, partial receipts, renewal and chosen-period debt repayment, template update/import, plain/encrypted export and restore into a fresh installation. Check offline operation, process death, long content, large fonts and light/dark themes. Tests that cannot run must be explicitly marked NOT RUN with reason; do not describe V1 as fully verified while essential device/recovery checks remain outstanding.

Run unit tests, Android lint, debug APK build and instrumentation where possible. Deliver a debug-signed test APK with an absolute path, installation notes, actual tested OS/device, known limitations, and separate public-release signing requirements. Do not commit signing secrets. Write `docs/handoffs/phase-5.md` with exact verification results and project commit. Stop for final external acceptance; do not publish, upload to a store, or claim production readiness without the evidence.
