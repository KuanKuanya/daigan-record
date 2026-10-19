package com.daigan.record.ui.navigation

import android.content.Intent

/**
 * Notification navigation contract.
 *
 * The extra is only an order id. The destination screen validates that the order still
 * exists, so a notification that survived a dataset restore (or a deleted order) opens a
 * clear "找不到这笔订单" state instead of a stale or invented record.
 */
object DeepLink {

    const val EXTRA_ORDER_ID = "com.daigan.record.extra.ORDER_ID"

    fun extractOrderId(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_ORDER_ID)?.takeIf { it.isNotBlank() }

    /** Removes the extra so a configuration change does not navigate twice. */
    fun consume(intent: Intent?) {
        intent?.removeExtra(EXTRA_ORDER_ID)
    }
}
