package com.daigan.record.ui.navigation

/** Navigation routes. Only the three main destinations show the bottom navigation. */
object Routes {
    const val TODAY = "today"
    const val ACCOUNTS = "accounts"
    const val STATS = "stats"

    const val SETTINGS = "settings"
    const val RECEIPTS = "settings/receipts"
    const val TEMPLATES = "settings/templates"
    const val TEMPLATE_EDITOR = "settings/templates/editor?templateId={templateId}"
    const val REMINDERS = "settings/reminders"
    const val BACKUP = "settings/backup"

    const val ARCHIVED = "accounts/archived"
    const val OWNER_DETAIL = "accounts/owner/{ownerId}"
    const val OWNER_FORM = "accounts/owner-form?ownerId={ownerId}"
    const val ACCOUNT_FORM = "accounts/{ownerId}/account-form?accountId={accountId}"

    const val ORDER_NEW = "orders/new?ownerId={ownerId}&accountId={accountId}"
    const val ORDER_DETAIL = "orders/{orderId}"
    const val ORDER_BACKFILL = "orders/{orderId}/backfill?periodId={periodId}"

    const val OWNER_ID_ARG = "ownerId"
    const val ACCOUNT_ID_ARG = "accountId"
    const val ORDER_ID_ARG = "orderId"
    const val PERIOD_ID_ARG = "periodId"
    const val TEMPLATE_ID_ARG = "templateId"

    val mainDestinations = setOf(TODAY, ACCOUNTS, STATS)

    fun ownerDetail(ownerId: String): String = "accounts/owner/$ownerId"

    fun ownerForm(ownerId: String? = null): String =
        "accounts/owner-form?ownerId=${ownerId.orEmpty()}"

    fun accountForm(ownerId: String, accountId: String? = null): String =
        "accounts/$ownerId/account-form?accountId=${accountId.orEmpty()}"

    fun orderNew(ownerId: String, accountId: String): String =
        "orders/new?ownerId=$ownerId&accountId=$accountId"

    fun orderDetail(orderId: String): String = "orders/$orderId"

    fun orderBackfill(orderId: String, periodId: String): String =
        "orders/$orderId/backfill?periodId=$periodId"

    fun templateEditor(templateId: String? = null): String =
        "settings/templates/editor?templateId=${templateId.orEmpty()}"
}

/** The four approved settings destinations. All of them are implemented. */
enum class SettingsSection(
    val key: String,
    val title: String,
) {
    RECEIPTS(
        key = "receipts",
        title = "收款明细",
    ),
    TEMPLATES(
        key = "templates",
        title = "游戏模板",
    ),
    REMINDERS(
        key = "reminders",
        title = "提醒设置",
    ),
    BACKUP(
        key = "backup",
        title = "备份与恢复",
    ),
    ;

    companion object {
        fun fromKey(key: String?): SettingsSection? = entries.firstOrNull { it.key == key }
    }
}
