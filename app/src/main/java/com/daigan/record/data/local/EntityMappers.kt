package com.daigan.record.data.local

import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.data.local.entity.AttachmentEntity
import com.daigan.record.data.local.entity.HostingPeriodEntity
import com.daigan.record.data.local.entity.OrderEntity
import com.daigan.record.data.local.entity.ReceiptCorrectionEntity
import com.daigan.record.data.local.entity.ReceiptEntity
import com.daigan.record.data.local.entity.TaskDefinitionEntity
import com.daigan.record.data.local.entity.TaskOccurrenceEntity
import com.daigan.record.data.model.Attachment
import com.daigan.record.data.model.GameRefreshRulesDomain
import com.daigan.record.data.model.HostingPeriod
import com.daigan.record.data.model.Order
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.Receipt
import com.daigan.record.data.model.ReceiptCorrectionRecord
import com.daigan.record.data.model.TaskOccurrence
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

fun parseWeekdays(raw: String?): Set<DayOfWeek> =
    raw?.split(',')
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.filter { it in 1..7 }
        ?.map(DayOfWeek::of)
        ?.toSet()
        ?: emptySet()

fun encodeWeekdays(days: Set<DayOfWeek>): String? =
    days.takeIf { it.isNotEmpty() }?.map { it.value }?.sorted()?.joinToString(",")

fun GameRefreshRules.toDomain(): GameRefreshRulesDomain = GameRefreshRulesDomain(
    zoneId = zoneId,
    dailyRefresh = dailyRefresh,
    weeklyRefreshDay = weeklyRefreshDay,
    weeklyRefreshTime = weeklyRefreshTime,
)

fun GameRefreshRulesDomain.toCore(): GameRefreshRules = GameRefreshRules(
    zoneId = zoneId,
    dailyRefresh = dailyRefresh,
    weeklyRefreshDay = weeklyRefreshDay,
    weeklyRefreshTime = weeklyRefreshTime,
)

fun GameRefreshRulesDomain.zone(): ZoneId = zoneId

fun GameRefreshRulesDomain.dailyTime(): LocalTime = dailyRefresh

fun OrderEntity.toModel(): Order = Order(
    id = id,
    accountId = accountId,
    type = type,
    title = title,
    priceCents = priceCents,
    paymentMode = paymentMode,
    deadlineDate = deadlineDate,
    notes = notes,
    refresh = GameRefreshRulesDomain(
        zoneId = ZoneId.of(gameZoneId),
        dailyRefresh = LocalTime.of(dailyRefreshMinutes / 60, dailyRefreshMinutes % 60),
        weeklyRefreshDay = DayOfWeek.of(weeklyRefreshDay.coerceIn(1, 7)),
        weeklyRefreshTime = LocalTime.of(weeklyRefreshMinutes / 60, weeklyRefreshMinutes % 60),
    ),
    createdAt = createdAt,
    completedAt = completedAt,
)

fun HostingPeriodEntity.toModel(): HostingPeriod = HostingPeriod(
    id = id,
    orderId = orderId,
    periodIndex = periodIndex,
    startDate = startDate,
    endDate = endDate,
    priceCents = priceCents,
    refresh = GameRefreshRulesDomain(
        zoneId = ZoneId.of(gameZoneId),
        dailyRefresh = LocalTime.of(dailyRefreshMinutes / 60, dailyRefreshMinutes % 60),
        weeklyRefreshDay = DayOfWeek.of(weeklyRefreshDay.coerceIn(1, 7)),
        weeklyRefreshTime = LocalTime.of(weeklyRefreshMinutes / 60, weeklyRefreshMinutes % 60),
    ),
    createdAt = createdAt,
    renewalOperationId = renewalOperationId,
)

fun TaskDefinitionEntity.toModel(): OrderTask = OrderTask(
    id = id,
    orderId = orderId,
    periodId = periodId,
    title = title,
    progressType = progressType,
    startValue = startValue,
    targetValue = targetValue,
    currentValue = currentValue,
    unit = unit,
    recurrence = recurrence,
    weekdays = parseWeekdays(weekdays),
    everyNDays = everyNDays,
    sortOrder = sortOrder,
    completedAt = completedAt,
)

fun TaskOccurrenceEntity.toModel(): TaskOccurrence = TaskOccurrence(
    id = id,
    taskDefinitionId = taskDefinitionId,
    occurrenceKey = occurrenceKey,
    attributedDate = gameDate,
    windowStart = windowStart,
    windowEnd = windowEnd,
    completedAt = completedAt,
    recordedAt = recordedAt,
    backfilled = backfilled,
    completionBatchId = completionBatchId,
    value = value,
    createdAt = createdAt,
)

fun AttachmentEntity.toModel(): Attachment = Attachment(
    id = id,
    taskDefinitionId = taskDefinitionId,
    occurrenceId = occurrenceId,
    relativePath = relativePath,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    createdAt = createdAt,
)

fun ReceiptEntity.toModel(): Receipt = Receipt(
    id = id,
    operationId = operationId,
    orderId = orderId,
    periodId = periodId,
    amountCents = amountCents,
    receivedAt = receivedAt,
    channel = channel,
    note = note,
    status = status,
    replacedByReceiptId = replacedByReceiptId,
    createdAt = createdAt,
)

fun ReceiptCorrectionEntity.toModel(): ReceiptCorrectionRecord = ReceiptCorrectionRecord(
    id = id,
    operationId = operationId,
    receiptId = receiptId,
    kind = kind,
    reason = reason,
    previousAmountCents = previousAmountCents,
    previousReceivedAt = previousReceivedAt,
    previousChannel = previousChannel,
    previousNote = previousNote,
    newReceiptId = newReceiptId,
    newAmountCents = newAmountCents,
    newReceivedAt = newReceivedAt,
    newChannel = newChannel,
    newNote = newNote,
    createdAt = createdAt,
)
