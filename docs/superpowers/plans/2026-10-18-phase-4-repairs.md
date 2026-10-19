# Phase 4 Repair Implementation Plan

Goal: repair the three independently reproduced Phase 4 defects without changing historical orders or starting Phase 5.

Architecture: optional explicit short-order calendar snapshots retain the legacy null-calendar fingerprint. Pending payloads carry an explicit calendar-mode marker; absent markers preserve legacy retry semantics. Template reads use a monotonically increasing intent generation, invalidated by edits and submission.

Spec: docs/handoffs/phase-4-review.md and phase-4-fix-1-prompt.md.

- [x] Restore optional provenance decoding while retaining required-field validation; run the existing failing compatibility test.
- [x] Carry nullable refresh through ShortOrderDraft, repository, fingerprint and pending restoration. Test explicit calendars and legacy retry fingerprints.
- [x] Guard every async template application with intent generation, type and saving/created checks; invalidate on edits/clear/type/submit/new creation. Retain and extend deterministic race regressions.
- [x] Run full JVM/device suites, lint and build; document exact results and limitations.

Review focus: legacy IDs and receipts; old fingerprint stability; refresh/time-zone boundaries; delayed account/type defaults; pending reads during submission. No database schema change is necessary because orders already store refresh columns.

Verification: 133 JVM tests and 177 device tests passed; lint 0 errors / 2 warnings / 2 hints; debug build passed. See docs/handoffs/phase-4-fix-1.md for logs and compatibility decisions.
