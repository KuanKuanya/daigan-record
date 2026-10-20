package com.kuankuan.daigan.data.model

import com.kuankuan.daigan.core.time.GameRefreshRules
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The fingerprint is the conflict detector for a reused create-operation id. It must
 * ignore irrelevant formatting and unstable wall-clock values, while every meaningful
 * financial field changes it.
 */
class OrderCommandFingerprintTest {

    private val rules = GameRefreshRules(
        zoneId = ZoneId.of("Asia/Shanghai"),
        dailyRefresh = LocalTime.of(4, 0),
        weeklyRefreshDay = DayOfWeek.MONDAY,
        weeklyRefreshTime = LocalTime.of(5, 0),
    )

    private fun short(
        title: String = "  日常  ",
        amount: String = "100.00",
        taskTitle: String = " 主线 ",
        start: String = "0.0",
        creationId: String = "create-1",
    ) = ShortOrderDraft(
        ownerId = "owner",
        accountId = "account",
        title = title,
        priceYuan = amount,
        tasks = listOf(TaskDraft(title = taskTitle, startValue = start)),
        creationOperationId = creationId,
    )

    @Test
    fun monthlyDayChangesCommandButIrrelevantDayDoesNotChangeLegacyCommand() {
        fun fingerprint(task: TaskDraft) = OrderCommandFingerprint.short(short().copy(tasks = listOf(task)))
        val task = TaskDraft(title = "月常", recurrence = RecurrenceType.MONTHLY, monthDay = "1")
        assertNotEquals(fingerprint(task), fingerprint(task.copy(monthDay = "31")))
        assertEquals(fingerprint(task.copy(monthDay = " 1 ")), fingerprint(task))
        assertEquals(fingerprint(task.copy(recurrence = RecurrenceType.DAILY)), fingerprint(task.copy(recurrence = RecurrenceType.DAILY, monthDay = "31")))
    }

    @Test
    fun whitespaceAndDecimalFormattingDoNotChangeTheCommand() {
        val a = short()
        val b = short(title = "日常", taskTitle = "主线", start = "0.00")
        assertEquals(OrderCommandFingerprint.short(a), OrderCommandFingerprint.short(b))
    }

    @Test
    fun creationOperationIdIsNotPartOfThePayloadFingerprint() {
        val a = short(creationId = "create-1")
        val b = short(creationId = "create-2")
        assertEquals(OrderCommandFingerprint.short(a), OrderCommandFingerprint.short(b))
    }

    @Test
    fun meaningfulFieldChangesProduceADifferentFingerprint() {
        val base = OrderCommandFingerprint.short(short())
        assertNotEquals(base, OrderCommandFingerprint.short(short(title = "别的名称")))
        assertNotEquals(base, OrderCommandFingerprint.short(short(amount = "101.00")))
        assertNotEquals(base, OrderCommandFingerprint.short(short(taskTitle = "副本")))
        assertNotEquals(base, OrderCommandFingerprint.short(short(start = "1")))
    }

    @Test
    fun receiptTimeUsesPersistedMillisecondPrecision() {
        val instant = Instant.parse("2026-09-15T03:30:00.123456Z")
        val withSubMillis = short().copy(
            initialPayment = InitialPaymentDraft(
                "100.00",
                operationId = "pay-1",
                receivedAt = instant,
            ),
        )
        val truncated = short().copy(
            initialPayment = InitialPaymentDraft(
                "100.00",
                operationId = "pay-1",
                receivedAt = Instant.ofEpochMilli(instant.toEpochMilli()),
            ),
        )
        assertEquals(
            OrderCommandFingerprint.short(withSubMillis),
            OrderCommandFingerprint.short(truncated),
        )

        val anotherChannel = withSubMillis.copy(
            initialPayment = withSubMillis.initialPayment!!.copy(channel = PaymentChannel.ALIPAY),
        )
        assertNotEquals(
            OrderCommandFingerprint.short(withSubMillis),
            OrderCommandFingerprint.short(anotherChannel),
        )
        val anotherNote = withSubMillis.copy(
            initialPayment = withSubMillis.initialPayment!!.copy(
                channel = PaymentChannel.OTHER,
                note = "现金",
            ),
        )
        assertNotEquals(
            OrderCommandFingerprint.short(withSubMillis),
            OrderCommandFingerprint.short(anotherNote),
        )
    }

    @Test
    fun implicitAndExplicitReceiptTimesAreDifferentCommands() {
        val implicit = short().copy(
            initialPayment = InitialPaymentDraft("100.00", operationId = "pay-1"),
        )
        val explicit = short().copy(
            initialPayment = InitialPaymentDraft(
                "100.00",
                operationId = "pay-1",
                receivedAt = Instant.parse("2026-09-15T03:30:00Z"),
            ),
        )
        assertNotEquals(
            OrderCommandFingerprint.short(implicit),
            OrderCommandFingerprint.short(explicit),
        )
    }

    @Test
    fun hostingFingerprintIncludesDatesAndRefreshRules() {
        val base = HostingOrderDraft(
            ownerId = "owner",
            accountId = "account",
            startDate = LocalDate.of(2026, 10, 17),
            endDate = LocalDate.of(2026, 11, 15),
            priceYuan = "300.00",
            refresh = rules,
            creationOperationId = "create-hosting",
        )
        val same = base.copy(creationOperationId = "another-id")
        assertEquals(OrderCommandFingerprint.hosting(base), OrderCommandFingerprint.hosting(same))
        assertNotEquals(
            OrderCommandFingerprint.hosting(base),
            OrderCommandFingerprint.hosting(base.copy(endDate = LocalDate.of(2026, 11, 16))),
        )
        assertNotEquals(
            OrderCommandFingerprint.hosting(base),
            OrderCommandFingerprint.hosting(
                base.copy(refresh = rules.copy(zoneId = ZoneId.of("UTC"))),
            ),
        )
    }
    @Test fun shortCalendarChangesAreDistinctButLegacyFingerprintRemainsStable() {
        val legacy = short()
        // Golden canonical hash from the pre-calendar short command format.
        assertEquals("43090fac24c13be965a90e3ea77751b5eb40dc6c74f2824c726435eddd6ff3b2",
            OrderCommandFingerprint.short(legacy))
        val explicit = legacy.copy(refresh = rules)
        assertNotEquals(OrderCommandFingerprint.short(legacy), OrderCommandFingerprint.short(explicit))
        assertNotEquals(OrderCommandFingerprint.short(explicit), OrderCommandFingerprint.short(
            explicit.copy(refresh = rules.copy(dailyRefresh = LocalTime.of(5, 0)))))
        assertNotEquals(OrderCommandFingerprint.short(explicit), OrderCommandFingerprint.short(
            explicit.copy(refresh = rules.copy(zoneId = ZoneId.of("UTC")))))
        assertEquals(OrderCommandFingerprint.short(legacy), OrderCommandFingerprint.short(legacy.copy(refresh = null)))
    }

}
