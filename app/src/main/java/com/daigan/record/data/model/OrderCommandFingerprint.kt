package com.daigan.record.data.model

import com.daigan.record.core.time.PersistedInstant
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/**
 * Stable, normalized fingerprint of a whole create-order command.
 *
 * The fingerprint is the conflict detector for creation idempotency: the same
 * `creationOperationId` may only be replayed with the same normalized command. It
 * deliberately excludes `createdAt`/`now` (a retry happens at a later wall-clock time)
 * and normalizes:
 *
 * - user text (`trim`, blank note -> null),
 * - money (integer cents, never the raw text),
 * - task progress decimals (`stripTrailingZeros`, so `1.0` and `1.00` are the same),
 * - the optional initial receipt time to the persisted millisecond precision,
 * - the optional initial receipt's own operation id.
 *
 * Phase 4 template provenance is deliberately **excluded**: it is a descriptive label
 * ("defaults came from this template"), not part of the price, tasks or terms. A retry
 * that reopens a pending form after process death therefore stays the same command even
 * if the restored form can no longer name the template it originally used.
 *
 * Length-prefixed tokens make the encoding unambiguous (`a` + `b` never collides with
 * `ab`).
 */
object OrderCommandFingerprint {

    fun short(draft: ShortOrderDraft): String = digest {
        token("kind", "SHORT_TASK")
        common(draft.ownerId, draft.accountId, draft.title, draft.priceYuan, draft.paymentMode)
        token("notes", normalizedText(draft.notes))
        token("deadline", draft.deadlineDate?.toString())
        // Omit entirely for legacy commands, preserving their persisted fingerprints.
        draft.refresh?.let { refresh ->
            token("zone", refresh.zoneId.id)
            token("daily", refresh.dailyRefresh.toString())
            token("weeklyDay", refresh.weeklyRefreshDay.name)
            token("weeklyTime", refresh.weeklyRefreshTime.toString())
        }
        tasks(draft.tasks)
        initialPayment(draft.initialPayment)
    }

    fun hosting(draft: HostingOrderDraft): String = digest {
        token("kind", "HOSTING")
        common(draft.ownerId, draft.accountId, draft.title, draft.priceYuan, draft.paymentMode)
        token("notes", normalizedText(draft.notes))
        token("start", draft.startDate.toString())
        token("end", draft.endDate.toString())
        token("zone", draft.refresh.zoneId.id)
        token("daily", draft.refresh.dailyRefresh.toString())
        token("weeklyDay", draft.refresh.weeklyRefreshDay.name)
        token("weeklyTime", draft.refresh.weeklyRefreshTime.toString())
        tasks(draft.tasks)
        initialPayment(draft.initialPayment)
    }

    private fun Canonical.common(
        ownerId: String,
        accountId: String,
        title: String,
        priceYuan: String,
        paymentMode: PaymentMode,
    ) {
        token("owner", ownerId)
        token("account", accountId)
        token("title", title.trim())
        token("priceCents", Money.parseYuanToCentsOrNull(priceYuan)?.toString())
        token("paymentMode", paymentMode.name)
    }

    private fun Canonical.tasks(tasks: List<TaskDraft>) {
        token("taskCount", tasks.size.toString())
        tasks.forEachIndexed { index, task ->
            token("task.$index.title", task.title.trim())
            token("task.$index.progress", task.progressType.name)
            token("task.$index.start", decimal(task.startDecimal()))
            token("task.$index.target", decimal(task.targetDecimal()))
            token("task.$index.unit", normalizedText(task.unit))
            token("task.$index.recurrence", task.recurrence.name)
            token(
                "task.$index.weekdays",
                task.weekdays.sortedBy { it.value }.joinToString(",") { it.name },
            )
            if (task.recurrence == RecurrenceType.MONTHLY) token("task.$index.monthDay", task.monthDayOrNull())
            token(
                "task.$index.everyN",
                if (task.recurrence == RecurrenceType.EVERY_N_DAYS) {
                    task.everyNOrNull()?.toString()
                } else {
                    null
                },
            )
        }
    }

    private fun Canonical.initialPayment(draft: InitialPaymentDraft?) {
        token("initial", draft != null)
        if (draft == null) return
        token("initial.amountCents", Money.parsePositiveYuanToCentsOrNull(draft.amountYuan)?.toString())
        token("initial.channel", draft.channel.name)
        token("initial.note", normalizedReceiptNote(draft.channel, draft.note))
        token("initial.receivedAtMillis", receivedAtMillis(draft.receivedAt)?.toString())
        token("initial.operationId", draft.operationId)
    }

    /**
     * Explicit receipt moments are part of the command; `null` means "use the creation
     * time" and stays null so an identical retry cannot conflict merely because the
     * wall clock moved.
     */
    fun receivedAtMillis(instant: Instant?): Long? =
        instant?.let { PersistedInstant.normalizedMillis(it) }

    /** Notes are only meaningful for the "其他" channel; other channels store none. */
    fun normalizedReceiptNote(channel: PaymentChannel, raw: String?): String? {
        val note = normalizedText(raw) ?: return null
        return if (channel == PaymentChannel.OTHER) note else null
    }

    fun normalizedText(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }

    private fun decimal(value: BigDecimal?): String? =
        value?.stripTrailingZeros()?.toPlainString()

    private fun digest(block: Canonical.() -> Unit): String {
        val canonical = Canonical().apply(block)
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(canonical.text().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private class Canonical {
        private val builder = StringBuilder()

        fun token(name: String, value: Any?) {
            builder.append(name).append('=')
            if (value == null) {
                builder.append('-')
            } else {
                val text = value.toString()
                builder.append(text.length).append(':').append(text)
            }
            builder.append(';')
        }

        fun text(): String = builder.toString()
    }
}
