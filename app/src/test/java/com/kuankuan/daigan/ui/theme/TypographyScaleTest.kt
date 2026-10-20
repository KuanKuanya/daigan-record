package com.kuankuan.daigan.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class TypographyScaleTest {
    @Test
    fun pageTitleAndBodyUseTheApprovedComfortableScale() {
        assertEquals(22f, Typography.headlineSmall.fontSize.value)
        assertEquals(15f, Typography.bodyMedium.fontSize.value)
        assertEquals(13f, Typography.bodySmall.fontSize.value)
        assertEquals(16f, Typography.titleMedium.fontSize.value)
    }
}
