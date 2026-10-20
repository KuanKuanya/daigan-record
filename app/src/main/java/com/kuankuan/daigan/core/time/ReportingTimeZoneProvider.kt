package com.kuankuan.daigan.core.time

import java.time.ZoneId
import javax.inject.Inject

/**
 * The device's current reporting timezone, used only for statistics calendar months.
 * It is deliberately separate from each order's game refresh rules.
 */
fun interface ReportingTimeZoneProvider {
    fun zone(): ZoneId
}

class SystemReportingTimeZoneProvider @Inject constructor() : ReportingTimeZoneProvider {
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
