package com.daigan.record.data.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money is always stored as integer cents. Parsing accepts at most two decimal places
 * and never rounds silently, so a typo cannot become a different negotiated price.
 */
object Money {
    private val MAX_CENTS = BigDecimal("9999999999")

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
        require(cents <= MAX_CENTS) { "金额过大" }
        return cents.longValueExact()
    }

    fun parseYuanToCentsOrNull(raw: String): Long? = runCatching { parseYuanToCents(raw) }.getOrNull()

    /** "¥100.00" — always two decimals so integer cents stay visible. */
    fun formatCents(cents: Long): String =
        "¥" + BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString()

    /** Editable text without the currency prefix, e.g. "100.00". */
    fun centsToYuanText(cents: Long): String =
        BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString()
}
