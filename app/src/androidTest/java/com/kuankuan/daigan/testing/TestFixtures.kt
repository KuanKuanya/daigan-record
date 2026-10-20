package com.kuankuan.daigan.testing

import com.kuankuan.daigan.data.model.GameAccount
import com.kuankuan.daigan.data.model.LoginMethod
import com.kuankuan.daigan.data.model.Owner
import com.kuankuan.daigan.data.model.OwnerDetail
import java.time.Instant

val fixedInstant: Instant = Instant.parse("2026-10-17T02:00:00Z")

fun owner(
    id: String = "owner-1",
    number: Int = 1,
    nickname: String = "小满",
    archived: Boolean = false,
): Owner = Owner(
    id = id,
    number = number,
    nickname = nickname,
    createdAt = fixedInstant,
    archived = archived,
)

fun account(
    id: String = "account-1",
    ownerId: String = "owner-1",
    gameName: String = "重返未来：1999",
    server: String = "官服",
    uid: String = "10000628",
    loginAccount: String = "demo-xiaoman",
    loginMethod: LoginMethod = LoginMethod.PASSWORD,
    password: String? = "demo1999",
    archived: Boolean = false,
    archivedWithOwner: Boolean = false,
): GameAccount = GameAccount(
    id = id,
    ownerId = ownerId,
    gameName = gameName,
    server = server,
    uid = uid,
    loginAccount = loginAccount,
    loginMethod = loginMethod,
    password = password,
    createdAt = fixedInstant,
    archived = archived,
    archivedWithOwner = archivedWithOwner,
)

fun ownerDetail(
    owner: Owner = owner(),
    activeAccounts: List<GameAccount> = listOf(account()),
    archivedAccounts: List<GameAccount> = emptyList(),
): OwnerDetail = OwnerDetail(
    owner = owner,
    activeAccounts = activeAccounts,
    archivedAccounts = archivedAccounts,
)

fun shortOrderSummary(
    orderId: String = "order-1",
    accountId: String = "account-1",
    title: String = "短期任务",
    completed: Boolean = false,
    ownerId: String = "owner-1",
): com.kuankuan.daigan.data.model.OrderSummary {
    val now = fixedInstant
    return com.kuankuan.daigan.data.model.OrderSummary(
        order = com.kuankuan.daigan.data.model.Order(
            id = orderId,
            accountId = accountId,
            type = com.kuankuan.daigan.data.model.OrderType.SHORT_TASK,
            title = title,
            priceCents = 10000,
            paymentMode = com.kuankuan.daigan.data.model.PaymentMode.ONE_TIME,
            deadlineDate = java.time.LocalDate.of(2026, 10, 20),
            notes = null,
            refresh = com.kuankuan.daigan.data.model.GameRefreshRulesDomain(
                zoneId = java.time.ZoneId.of("Asia/Shanghai"),
                dailyRefresh = java.time.LocalTime.of(4, 0),
                weeklyRefreshDay = java.time.DayOfWeek.MONDAY,
                weeklyRefreshTime = java.time.LocalTime.of(5, 0),
            ),
            createdAt = now,
            completedAt = if (completed) now else null,
        ),
        ownerId = ownerId,
        ownerNumber = "B001",
        ownerNickname = "小满",
        accountId = accountId,
        gameName = "重返未来：1999",
        server = "官服",
        currentPeriod = null,
        taskCount = 1,
        completedTaskCount = if (completed) 1 else 0,
    )
}

fun hostingOrderForToday(
    orderId: String = "hosting-1",
    title: String = "日常托管",
    status: com.kuankuan.daigan.core.time.HostingStatus =
        com.kuankuan.daigan.core.time.HostingStatus.ACTIVE,
    allCompleted: Boolean = false,
    taskTitle: String = "每日签到",
): com.kuankuan.daigan.data.model.TodayHostingOrder {
    val today = java.time.LocalDate.of(2026, 10, 17)
    val period = com.kuankuan.daigan.data.model.HostingPeriod(
        id = "period-1",
        orderId = orderId,
        periodIndex = 0,
        startDate = today.minusDays(5),
        endDate = today.plusDays(5),
        priceCents = 30000,
        refresh = com.kuankuan.daigan.data.model.GameRefreshRulesDomain(
            zoneId = java.time.ZoneId.of("Asia/Shanghai"),
            dailyRefresh = java.time.LocalTime.of(4, 0),
            weeklyRefreshDay = java.time.DayOfWeek.MONDAY,
            weeklyRefreshTime = java.time.LocalTime.of(5, 0),
        ),
        createdAt = fixedInstant,
        renewalOperationId = null,
    )
    val task = com.kuankuan.daigan.data.model.OrderTask(
        id = "task-1",
        orderId = orderId,
        periodId = period.id,
        title = taskTitle,
        progressType = com.kuankuan.daigan.data.model.ProgressType.CHECKBOX,
        startValue = java.math.BigDecimal.ZERO,
        targetValue = java.math.BigDecimal.ONE,
        currentValue = java.math.BigDecimal.ZERO,
        unit = null,
        recurrence = com.kuankuan.daigan.data.model.RecurrenceType.DAILY,
        weekdays = emptySet(),
        everyNDays = null,
        sortOrder = 0,
        completedAt = null,
    )
    return com.kuankuan.daigan.data.model.TodayHostingOrder(
        order = com.kuankuan.daigan.data.model.Order(
            id = orderId,
            accountId = "account-1",
            type = com.kuankuan.daigan.data.model.OrderType.HOSTING,
            title = title,
            priceCents = null,
            paymentMode = com.kuankuan.daigan.data.model.PaymentMode.ONE_TIME,
            deadlineDate = null,
            notes = null,
            refresh = period.refresh,
            createdAt = fixedInstant,
            completedAt = null,
        ),
        ownerId = "owner-1",
        ownerNumber = "B001",
        ownerNickname = "小满",
        accountId = "account-1",
        gameName = "重返未来：1999",
        server = "官服",
        period = period,
        today = today,
        status = status,
        tasks = if (status == com.kuankuan.daigan.core.time.HostingStatus.ACTIVE) {
            listOf(
                com.kuankuan.daigan.data.model.TodayHostingTask(
                    task = task,
                    occurrenceId = null,
                    slot = com.kuankuan.daigan.data.model.OccurrenceSlot(
                        key = "D:$today",
                        attributedDate = today,
                        windowStart = today,
                        windowEnd = today,
                    ),
                    completed = allCompleted,
                    value = null,
                    windowLabel = null,
                ),
            )
        } else {
            emptyList()
        },
    )
}

fun shortWorkOrderForToday(
    orderId: String = "short-1",
    deadline: java.time.LocalDate? = java.time.LocalDate.of(2026, 10, 15),
): com.kuankuan.daigan.data.model.ShortWorkOrder {
    val today = java.time.LocalDate.of(2026, 10, 17)
    val task = com.kuankuan.daigan.data.model.OrderTask(
        id = "task-1",
        orderId = orderId,
        periodId = null,
        title = "推进主线",
        progressType = com.kuankuan.daigan.data.model.ProgressType.CHECKBOX,
        startValue = java.math.BigDecimal.ZERO,
        targetValue = java.math.BigDecimal.ONE,
        currentValue = java.math.BigDecimal.ZERO,
        unit = null,
        recurrence = com.kuankuan.daigan.data.model.RecurrenceType.DAILY,
        weekdays = emptySet(),
        everyNDays = null,
        sortOrder = 0,
        completedAt = null,
    )
    return com.kuankuan.daigan.data.model.ShortWorkOrder(
        order = com.kuankuan.daigan.data.model.Order(
            id = orderId,
            accountId = "account-1",
            type = com.kuankuan.daigan.data.model.OrderType.SHORT_TASK,
            title = "短期任务",
            priceCents = 10000,
            paymentMode = com.kuankuan.daigan.data.model.PaymentMode.ONE_TIME,
            deadlineDate = deadline,
            notes = null,
            refresh = com.kuankuan.daigan.data.model.GameRefreshRulesDomain(
                zoneId = java.time.ZoneId.of("Asia/Shanghai"),
                dailyRefresh = java.time.LocalTime.of(4, 0),
                weeklyRefreshDay = java.time.DayOfWeek.MONDAY,
                weeklyRefreshTime = java.time.LocalTime.of(5, 0),
            ),
            createdAt = fixedInstant,
            completedAt = null,
        ),
        ownerId = "owner-1",
        ownerNumber = "B001",
        ownerNickname = "小满",
        accountId = "account-1",
        gameName = "重返未来：1999",
        server = "官服",
        tasks = listOf(
            com.kuankuan.daigan.data.model.ShortWorkTask(
                task = task,
                completed = false,
                currentValue = java.math.BigDecimal.ZERO,
            ),
        ),
        today = today,
    )
}

fun shortOrderDetailForUi(): com.kuankuan.daigan.data.model.OrderDetail {
    val today = java.time.LocalDate.of(2026, 10, 17)
    val order = com.kuankuan.daigan.data.model.Order(
        id = "order-short",
        accountId = "account-1",
        type = com.kuankuan.daigan.data.model.OrderType.SHORT_TASK,
        title = "短期任务",
        priceCents = 10000,
        paymentMode = com.kuankuan.daigan.data.model.PaymentMode.INSTALLMENTS,
        deadlineDate = today.plusDays(2),
        notes = "备注内容",
        refresh = com.kuankuan.daigan.data.model.GameRefreshRulesDomain(
            zoneId = java.time.ZoneId.of("Asia/Shanghai"),
            dailyRefresh = java.time.LocalTime.of(4, 0),
            weeklyRefreshDay = java.time.DayOfWeek.MONDAY,
            weeklyRefreshTime = java.time.LocalTime.of(5, 0),
        ),
        createdAt = fixedInstant,
        completedAt = null,
    )
    val task = com.kuankuan.daigan.data.model.OrderTask(
        id = "task-short",
        orderId = order.id,
        periodId = null,
        title = "推进主线",
        progressType = com.kuankuan.daigan.data.model.ProgressType.CHECKBOX,
        startValue = java.math.BigDecimal.ZERO,
        targetValue = java.math.BigDecimal.ONE,
        currentValue = java.math.BigDecimal.ZERO,
        unit = null,
        recurrence = com.kuankuan.daigan.data.model.RecurrenceType.DAILY,
        weekdays = emptySet(),
        everyNDays = null,
        sortOrder = 0,
        completedAt = null,
    )
    return com.kuankuan.daigan.data.model.OrderDetail(
        order = order,
        owner = owner(),
        account = account(),
        tasks = listOf(task),
        periods = emptyList(),
        occurrences = emptyList(),
        attachments = emptyList(),
        receipts = emptyList(),
        receiptCorrections = emptyList(),
        today = today,
        now = fixedInstant,
    )
}

fun expiredHostingDetailForUi(): com.kuankuan.daigan.data.model.OrderDetail =
    hostingDetailForUi(
        periodId = "period-expired",
        startOffsetDays = -10,
        endOffsetDays = -2,
    )

/**
 * Running period: the 2026-10-17 game day is inside `[startDate, endDate]`, so the detail
 * page renders live, toggleable task rows. The expired fixture cannot exercise a toggle —
 * its history is read-only by design.
 */
fun activeHostingDetailForUi(): com.kuankuan.daigan.data.model.OrderDetail =
    hostingDetailForUi(
        periodId = "period-active",
        startOffsetDays = -10,
        endOffsetDays = 3,
    )

private fun hostingDetailForUi(
    periodId: String,
    startOffsetDays: Long,
    endOffsetDays: Long,
): com.kuankuan.daigan.data.model.OrderDetail {
    val today = java.time.LocalDate.of(2026, 10, 17)
    val order = com.kuankuan.daigan.data.model.Order(
        id = "order-hosting",
        accountId = "account-1",
        type = com.kuankuan.daigan.data.model.OrderType.HOSTING,
        title = "日常托管",
        priceCents = null,
        paymentMode = com.kuankuan.daigan.data.model.PaymentMode.ONE_TIME,
        deadlineDate = null,
        notes = null,
        refresh = com.kuankuan.daigan.data.model.GameRefreshRulesDomain(
            zoneId = java.time.ZoneId.of("Asia/Shanghai"),
            dailyRefresh = java.time.LocalTime.of(4, 0),
            weeklyRefreshDay = java.time.DayOfWeek.MONDAY,
            weeklyRefreshTime = java.time.LocalTime.of(5, 0),
        ),
        createdAt = fixedInstant,
        completedAt = null,
    )
    val period = com.kuankuan.daigan.data.model.HostingPeriod(
        id = periodId,
        orderId = order.id,
        periodIndex = 0,
        startDate = today.plusDays(startOffsetDays),
        endDate = today.plusDays(endOffsetDays),
        priceCents = 30000,
        refresh = order.refresh,
        createdAt = fixedInstant,
        renewalOperationId = null,
    )
    val task = com.kuankuan.daigan.data.model.OrderTask(
        id = "task-hosting",
        orderId = order.id,
        periodId = period.id,
        title = "每日签到",
        progressType = com.kuankuan.daigan.data.model.ProgressType.CHECKBOX,
        startValue = java.math.BigDecimal.ZERO,
        targetValue = java.math.BigDecimal.ONE,
        currentValue = java.math.BigDecimal.ZERO,
        unit = null,
        recurrence = com.kuankuan.daigan.data.model.RecurrenceType.DAILY,
        weekdays = emptySet(),
        everyNDays = null,
        sortOrder = 0,
        completedAt = null,
    )
    return com.kuankuan.daigan.data.model.OrderDetail(
        order = order,
        owner = owner(),
        account = account(),
        tasks = listOf(task),
        periods = listOf(period),
        occurrences = emptyList(),
        attachments = emptyList(),
        receipts = emptyList(),
        receiptCorrections = emptyList(),
        today = today,
        now = fixedInstant,
    )
}
