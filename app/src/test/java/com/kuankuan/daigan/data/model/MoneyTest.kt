package com.kuankuan.daigan.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {

    @Test
    fun `yuan text becomes exact integer cents`() {
        assertEquals(10050L, Money.parseYuanToCents("100.5"))
        assertEquals(10050L, Money.parseYuanToCents("100.50"))
        assertEquals(0L, Money.parseYuanToCents("0"))
        assertEquals(1L, Money.parseYuanToCents("0.01"))
    }

    @Test
    fun `more than two decimals is rejected instead of rounded`() {
        assertThrows(IllegalArgumentException::class.java) {
            Money.parseYuanToCents("100.555")
        }
        assertNull(Money.parseYuanToCentsOrNull("abc"))
        assertThrows(IllegalArgumentException::class.java) {
            Money.parseYuanToCents("-1")
        }
    }

    @Test
    fun `formatting always exposes cents`() {
        assertEquals("¥100.50", Money.formatCents(10050))
        assertEquals("100.50", Money.centsToYuanText(10050))
    }
}
