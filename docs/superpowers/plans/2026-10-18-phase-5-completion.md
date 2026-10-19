# Phase 5 Takeover and Completion Plan

Goal: finish backup/restore, reminders and a verified V1 APK from CodeBuddy's existing dirty working tree.

Spec: docs/superpowers/specs/2026-10-17-game-work-manager-design.md, approved Phase 5 prompt in the conversation, and docs/superpowers/plans/2026-10-17-agent-handoffs.md.

Architecture: preserve Compose → ViewModel → Repository → Room. Full logical archives include all durable business tables and attachment bytes. Restore uses staging, a durable journal and DB commit marker; startup fails closed until recovery finishes. Reminders use unique WorkManager jobs and current persisted state. Custom WorkerFactory avoids the reproduced AndroidX Hilt processor/Kotlin metadata incompatibility without changing the accepted Kotlin toolchain.

- [x] Unblock compilation and verify existing partial code.
- [x] Serialize export/restore and coordinate every business write; cancellation must release barriers. Reset obsolete navigation/pending forms after dataset replacement.
- [x] Complete bounded plain/encrypted backup and validated staged restore, with process-death recovery probes.
- [x] Complete persisted reminder switches/time, current-state eligibility, permission/channel handling, unique scheduling and navigation.
- [x] Test migrations 1–6 to 7 and preservation of every existing durable table/column.
- [x] Verify full JVM/device suites, lint/build, fresh-install roundtrip, real interrupted recovery, settings UI, notification navigation and offline operation.
- [x] Deliver APK, checksums, backup/reminder docs, testing evidence and phase-5 handoff.

Execution: direct implementation in the existing workspace; separate agents own backup and reminders, root owns integration, DatasetGate, startup and acceptance. Preserve unrelated/uncommitted changes; no push/merge/publish.

Review focus: two simultaneous exclusive operations; cancellation/restore interleaving; startup reading mismatched data; stale form replay after whole-dataset replacement; archive file/checksum/resource failures; reminder self-cancellation and stale jobs; financial operation identities across restore.
