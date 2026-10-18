package com.daigan.record.feature.orders

import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TaskDraft
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64

/** The part of a pending create-order command that must survive process restoration. */
internal data class RestoredOrderCommand(
    val form: RestoredOrderForm,
    val creationOperationId: String,
    val initialPaymentOperationId: String,
    val initialReceivedAtMillis: Long,
)

internal data class RestoredOrderForm(
    val isShort: Boolean,
    val title: String,
    val priceYuan: String,
    val paymentMode: PaymentMode,
    val deadlineDate: LocalDate?,
    val notes: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val durationText: String,
    val zoneText: String,
    val dailyRefresh: LocalTime,
    val weeklyRefreshDay: DayOfWeek,
    val weeklyRefreshTime: LocalTime,
    val tasks: List<TaskDraft>,
    val initialPaymentEnabled: Boolean,
    val initialAmountYuan: String,
    val initialChannel: PaymentChannel,
    val initialNote: String,
    /**
     * Template provenance as it was shown when the command was submitted. Optional and
     * absent in payloads written before Phase 4, so decoding stays backward compatible.
     */
    val sourceTemplateId: String? = null,
    val sourceTemplateName: String? = null,
    val sourceTemplateVersion: String? = null,
)

/**
 * Stores the exact submitted command in [androidx.lifecycle.SavedStateHandle].
 *
 * The create-order command must stay identical across a pending request, an in-app
 * retry and process restoration; otherwise a retry after process death would allocate
 * a new order. The payload is therefore encoded as a small, versioned text value with
 * length-safe Base64 fields (titles may contain any character). Decoding never throws:
 * a malformed value simply falls back to the visible form defaults.
 */
internal object PendingOrderCommandCodec {

    private const val VERSION = "v1"
    private const val FIELD_SEPARATOR = '\u001F'

    fun encode(
        form: RestoredOrderForm,
        creationOperationId: String,
        initialPaymentOperationId: String,
        initialReceivedAtMillis: Long,
    ): String {
        val lines = mutableListOf<String>()
        fun put(key: String, value: String?) {
            lines += key + "=" + encodeText(value.orEmpty())
        }
        lines += "version=" + VERSION
        put("isShort", form.isShort.toString())
        put("title", form.title)
        put("priceYuan", form.priceYuan)
        put("paymentMode", form.paymentMode.name)
        put("deadlineDate", form.deadlineDate?.toString())
        put("notes", form.notes)
        put("startDate", form.startDate.toString())
        put("endDate", form.endDate.toString())
        put("durationText", form.durationText)
        put("zoneText", form.zoneText)
        put("dailyRefresh", form.dailyRefresh.toString())
        put("weeklyRefreshDay", form.weeklyRefreshDay.name)
        put("weeklyRefreshTime", form.weeklyRefreshTime.toString())
        put("taskCount", form.tasks.size.toString())
        form.tasks.forEachIndexed { index, task ->
            put(
                "task.$index",
                listOf(
                    task.title,
                    task.progressType.name,
                    task.startValue,
                    task.targetValue,
                    task.unit,
                    task.recurrence.name,
                    task.weekdays.sortedBy { it.value }.joinToString(",") { it.name },
                    task.everyNDays,
                ).joinToString(FIELD_SEPARATOR.toString()),
            )
        }
        put("initialPaymentEnabled", form.initialPaymentEnabled.toString())
        put("initialAmountYuan", form.initialAmountYuan)
        put("initialChannel", form.initialChannel.name)
        put("initialNote", form.initialNote)
        put("sourceTemplateId", form.sourceTemplateId)
        put("sourceTemplateName", form.sourceTemplateName)
        put("sourceTemplateVersion", form.sourceTemplateVersion)
        put("creationOperationId", creationOperationId)
        put("initialPaymentOperationId", initialPaymentOperationId)
        put("initialReceivedAtMillis", initialReceivedAtMillis.toString())
        return lines.joinToString("\n")
    }

    fun decode(raw: String): RestoredOrderCommand? = runCatching {
        val values = raw.lineSequence()
            .filter { it.isNotBlank() }
            .associate { line ->
                val index = line.indexOf('=')
                check(index > 0) { "malformed line" }
                line.substring(0, index) to line.substring(index + 1)
            }
        check(values["version"] == VERSION) { "unsupported version" }
        fun text(key: String): String = decodeText(checkNotNull(values[key]) { "missing $key" })
        fun optional(key: String): String? = text(key).takeIf { it.isNotEmpty() }
        val taskCount = text("taskCount").toInt()
        val tasks = (0 until taskCount).map { index ->
            val fields = text("task.$index").split(FIELD_SEPARATOR)
            check(fields.size == 8) { "malformed task" }
            TaskDraft(
                title = fields[0],
                progressType = ProgressType.valueOf(fields[1]),
                startValue = fields[2],
                targetValue = fields[3],
                unit = fields[4],
                recurrence = RecurrenceType.valueOf(fields[5]),
                weekdays = fields[6].split(",")
                    .filter { it.isNotBlank() }
                    .map(DayOfWeek::valueOf)
                    .toSet(),
                everyNDays = fields[7],
            )
        }
        RestoredOrderCommand(
            form = RestoredOrderForm(
                isShort = text("isShort").toBooleanStrict(),
                title = text("title"),
                priceYuan = text("priceYuan"),
                paymentMode = PaymentMode.valueOf(text("paymentMode")),
                deadlineDate = optional("deadlineDate")?.let(LocalDate::parse),
                notes = text("notes"),
                startDate = LocalDate.parse(text("startDate")),
                endDate = LocalDate.parse(text("endDate")),
                durationText = text("durationText"),
                zoneText = text("zoneText"),
                dailyRefresh = LocalTime.parse(text("dailyRefresh")),
                weeklyRefreshDay = DayOfWeek.valueOf(text("weeklyRefreshDay")),
                weeklyRefreshTime = LocalTime.parse(text("weeklyRefreshTime")),
                tasks = tasks,
                initialPaymentEnabled = text("initialPaymentEnabled").toBooleanStrict(),
                initialAmountYuan = text("initialAmountYuan"),
                initialChannel = PaymentChannel.valueOf(text("initialChannel")),
                initialNote = text("initialNote"),
                // Optional keys: a v1 payload written before Phase 4 simply has none.
                sourceTemplateId = optional("sourceTemplateId"),
                sourceTemplateName = optional("sourceTemplateName"),
                sourceTemplateVersion = optional("sourceTemplateVersion"),
            ),
            creationOperationId = text("creationOperationId"),
            initialPaymentOperationId = text("initialPaymentOperationId"),
            initialReceivedAtMillis = text("initialReceivedAtMillis").toLong(),
        )
    }.getOrNull()

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
