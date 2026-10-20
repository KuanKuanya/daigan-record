package com.kuankuan.daigan.data.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money is always stored as integer cents. Parsing accepts at most two decimal places
 * and never rounds silently, so a typo cannot become a different negotiated price.
 *
 * Receipts additionally reject zero and negative amounts: a receipt is a real money
 * movement, not an adjustment. Corrections use the same positive-amount rule.
 */
object Money {
    /** Hard ceiling per single amount; keeps every total far away from Long overflow. */
    const val MAX_CENTS: Long = 9_999_999_999L

    private val MAX_CENTS_DECIMAL = BigDecimal.valueOf(MAX_CENTS)

    /** Returns cents for a yuan string, or throws with a Chinese user facing message. */
    fun parseYuanToCents(raw: String): Long {
        val text = raw.trim()
        require(text.isNotEmpty()) { "请填写金额" }
        val value = try {
            BigDecimal(text)
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("金额格式不正确")
        }
        require(value.signum() >= 0) { "金额不能为负数" }
        require(value.scale() <= 2) { "金额最多保留两位小数" }
        val cents = value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY)
        require(cents <= MAX_CENTS_DECIMAL) { "金额过大" }
        return cents.longValueExact()
    }

    fun parseYuanToCentsOrNull(raw: String): Long? = runCatching { parseYuanToCents(raw) }.getOrNull()

    /** A receipt must move a strictly positive amount; zero is not a payment. */
    fun parsePositiveYuanToCents(raw: String): Long {
        val cents = parseYuanToCents(raw)
        require(cents > 0) { "收款金额必须大于 0" }
        return cents
    }

    fun parsePositiveYuanToCentsOrNull(raw: String): Long? =
        runCatching { parsePositiveYuanToCents(raw) }.getOrNull()

    /** Exact checked sum. A total that would overflow is rejected, never truncated. */
    fun sumCents(amounts: Iterable<Long>): Long {
        var total = 0L
        amounts.forEach { amount ->
            require(amount >= 0) { "金额不能为负数" }
            total = try {
                Math.addExact(total, amount)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("金额合计超出可记录范围")
            }
        }
        return total
    }

    /**
     * Remaining balance for one payable item. Both inputs are cents; a negative result
     * is impossible for valid data but is reported instead of silently clamped.
     */
    fun remainingCents(priceCents: Long, paidCents: Long): Long {
        require(priceCents >= 0) { "约定价格不能为负数" }
        require(paidCents >= 0) { "已收金额不能为负数" }
        require(paidCents <= priceCents) {
            "已收金额超出台账价格，请检查收款记录"
        }
        return priceCents - paidCents
    }

    /** "¥100.00" — always two decimals so integer cents stay visible. */
    fun formatCents(cents: Long): String =
        "¥" + BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString()

    /** Editable text without the currency prefix, e.g. "100.00". */
    fun centsToYuanText(cents: Long): String =
        BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString()
}
