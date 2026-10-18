package com.daigan.record.ui.navigation

/** Navigation routes. Only the three main destinations show the bottom navigation. */
object Routes {
    const val TODAY = "today"
    const val ACCOUNTS = "accounts"
    const val STATS = "stats"

    const val SETTINGS = "settings"
    const val SETTINGS_PLACEHOLDER = "settings/pending/{section}"

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
    const val SECTION_ARG = "section"

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

    fun settingsSection(section: SettingsSection): String = "settings/pending/${section.key}"
}

/**
 * Settings entries that exist in the approved design but are implemented in later
 * phases. They are shown as clearly marked pending pages instead of fake content.
 */
enum class SettingsSection(
    val key: String,
    val title: String,
    val summary: String,
    val pendingMessage: String,
) {
    RECEIPTS(
        key = "receipts",
        title = "收款明细",
        summary = "查阅已收款记录与归属期次",
        pendingMessage = "收款与期次功能将在第三阶段实现，本版本不会显示任何示例流水。",
    ),
    TEMPLATES(
        key = "templates",
        title = "游戏模板",
        summary = "编辑、导入与导出模板",
        pendingMessage = "游戏模板的编辑与文件导入导出将在第四阶段实现。",
    ),
    REMINDERS(
        key = "reminders",
        title = "提醒设置",
        summary = "管理截止、到期和未完成提醒",
        pendingMessage = "系统通知与提醒调度将在第五阶段实现，本版本不会申请通知权限。",
    ),
    BACKUP(
        key = "backup",
        title = "备份与恢复",
        summary = "导出全部资料，可选密码保护，导入恢复",
        pendingMessage = "备份与恢复将在第五阶段实现，本版本不会写出或读取任何备份文件。",
    ),
    ;

    companion object {
        fun fromKey(key: String?): SettingsSection? = entries.firstOrNull { it.key == key }
    }
}
