# Phase 4 — Repair Prompt 1

Work in the existing repository. Read AGENTS.md, the project plan, docs/handoffs/phase-4.md, and docs/handoffs/phase-4-review.md first. Phase 4 is NOT accepted yet. Fix the three reproduced defects below; do not start Phase 5.

## 1. Restore backward compatibility for pending order commands

PendingOrderCommandCodec treats newly introduced optional template provenance keys as required. A valid Phase 3 v1 payload without sourceTemplateId/sourceTemplateName/sourceTemplateVersion currently decodes to null.

Accept valid legacy payloads with absent new optional fields, without relaxing validation of existing required fields or accepting malformed data. Preserve the original creation operation ID, initial-payment operation ID, timestamps, amounts, tasks, and retry semantics. Do not regenerate identities to hide decoding failures. Add genuine legacy-payload fixtures and verify ViewModel restoration and retry behavior, including already-completed operations where applicable.

## 2. Persist the selected calendar for short orders

NewOrderViewModel displays the selected template calendar, but ShortOrderDraft does not carry it and OrderRepository.createShortOrder uses provisional device-local rules instead. A 05:00 template is persisted as 04:00.

Carry an explicit immutable calendar snapshot through the short-order creation path. Ensure order detail, game-day boundaries, and deadline/overdue calculations use that snapshot. Preserve both time zone and refresh settings. Retrying a frozen command must not read the current editable template. Editing, deleting, or importing over a template must not rewrite existing orders.

Carefully preserve compatibility with legacy pending commands and already-persisted creation operations/fingerprints. Do not silently invalidate previously valid idempotent retries. If serialization or fingerprint semantics need evolution, define and test their backward-compatible behavior explicitly. Preserve historical schemas 1–6; introduce a new migration only if the chosen fix actually requires one.

Test a short order at 04:30 Asia/Shanghai with a 05:00 refresh and an Oct 17 deadline: the persisted game day must remain Oct 17. Cover different device and template time zones, both sides of refresh, database reopen, and template changes after creation.

## 3. Prevent obsolete asynchronous template results from changing the form

A pending A read can overwrite a later B selection. A pending read can also restore a template after the user clears it and edits tasks. selectType's automatic reapplication checks edit state only before suspending.

Implement explicit request invalidation/cancellation or equivalent latest-intent safeguards across all template-loading paths. Revalidate selection, order type, task-edit revision, and saving/created state when an asynchronous result returns. Clearing a template must preserve the user's current editable tasks and invalidate older work. Automatic reapplication must not overwrite edits made while loading. Submission must freeze a coherent command, and later reads must not mutate it or its terminal state. Avoid timing-based workarounds.

Keep deterministic tests for A/B out-of-order completion, clear while loading, edits during type-switch reapplication, and in-flight submission.

## Required evidence and scope

Retain the independent review tests under app/src/test/java/com/daigan/record/review and app/src/androidTest/java/com/daigan/record/review. Their failing assertions describe the required behavior; do not delete or weaken them. Extend meaningful coverage where necessary.

Run all JVM tests, all connected device tests, lint, and the debug build. Report exact executed counts, failures, skips, emulator identity, and which tasks were cached/up-to-date. Include the independent regressions in the final full-suite run. Recheck accepted payment/undo/retry/migration behavior and template import atomicity. Do not claim success from the old green suite alone.

Review docs/game-template-sources.md and bundled-template confidence labels. Distinguish directly verified facts from provisional editable examples. A maintenance start time alone is not evidence of a daily refresh rule. Do not invent game rules.

Update the handoff with changes, verification evidence, compatibility decisions, and remaining limitations. Stop after making Phase 4 ready for independent acceptance. Do not implement backups, reminders, cloud sync, release signing, or other Phase 5 work. Do not push or merge unless separately authorized.
