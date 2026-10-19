package com.daigan.record.backup

/**
 * The durable tables of the current schema, in dependency order (parents first).
 *
 * The list is explicit instead of derived from `sqlite_master`: a backup must contain a
 * known, reviewable set of tables, and a restore must reject a file that does not match
 * it exactly. Adding a table in a later phase is therefore a visible change here.
 *
 * `app_settings` (dataset identity and restore commit marker) and `reminder_deliveries`
 * (scheduling de-duplication log) are **not** part of a backup: both are reconstructible
 * local state, and restoring them would let an old installation identity or an old
 * "already notified" marker leak into the restored dataset. Everything the user can
 * observe — including reminder preferences — is included.
 */
object BackupTableCatalog {

    enum class ColumnType { TEXT, INTEGER }

    data class Column(val name: String, val type: ColumnType)

    data class Table(
        val name: String,
        val columns: List<Column>,
        /** Whether rows of this table are written to a backup archive. */
        val included: Boolean = true,
        /**
         * True for the two single-row settings tables. Their primary key is a fixed id,
         * so a restored file may not smuggle in a second row under another id.
         */
        val singleton: Boolean = false,
    ) {
        val columnNames: List<String> get() = columns.map { it.name }
    }

    private fun text(name: String) = Column(name, ColumnType.TEXT)
    private fun integer(name: String) = Column(name, ColumnType.INTEGER)

    val tables: List<Table> = listOf(
        Table("app_settings", listOf(integer("id"), text("datasetInstanceId"),
            text("lastRestoreId"), integer("createdAt")), included = false, singleton = true),
        Table("reminder_deliveries", listOf(text("category"), text("day"),
            integer("deliveredAt")), included = false),
        Table(
            name = "owners",
            columns = listOf(
                text("id"),
                integer("number"),
                text("nickname"),
                integer("createdAt"),
                integer("updatedAt"),
                integer("archivedAt"),
                text("archiveBatchId"),
            ),
        ),
        Table(
            name = "owner_number_sequence",
            columns = listOf(integer("id"), integer("nextNumber")),
            singleton = true,
        ),
        Table(
            name = "game_accounts",
            columns = listOf(
                text("id"),
                text("ownerId"),
                text("gameName"),
                text("server"),
                text("uid"),
                text("loginAccount"),
                text("loginMethod"),
                text("password"),
                integer("createdAt"),
                integer("updatedAt"),
                integer("archivedAt"),
                text("archiveBatchId"),
                text("defaultTemplateId"),
            ),
        ),
        Table(
            name = "game_templates",
            columns = listOf(
                text("id"),
                text("gameName"),
                text("templateVersion"),
                text("gameVersionNotes"),
                text("zoneId"),
                integer("dailyRefreshMinutes"),
                integer("weeklyRefreshDay"),
                integer("weeklyRefreshMinutes"),
                text("origin"),
                text("builtInKey"),
                integer("sortOrder"),
                integer("createdAt"),
                integer("updatedAt"),
            ),
        ),
        Table(
            name = "template_categories",
            columns = listOf(
                text("id"),
                text("templateId"),
                text("name"),
                integer("sortOrder"),
            ),
        ),
        Table(
            name = "template_tasks",
            columns = listOf(
                text("id"),
                text("templateId"),
                text("categoryId"),
                text("title"),
                text("progressType"),
                text("startValue"),
                text("targetValue"),
                text("unit"),
                text("recurrence"),
                text("weekdays"),
                integer("everyNDays"),
                integer("sortOrder"),
                integer("monthDay"),
            ),
        ),
        Table(
            name = "template_seed_state",
            columns = listOf(text("seedKey"), integer("appliedAt")),
        ),
        Table(
            name = "completion_batches",
            columns = listOf(
                text("id"),
                text("operationId"),
                text("scope"),
                text("targetId"),
                text("gameDate"),
                integer("createdAt"),
                integer("undoneAt"),
            ),
        ),
        Table(
            name = "orders",
            columns = listOf(
                text("id"),
                text("accountId"),
                text("type"),
                text("title"),
                integer("priceCents"),
                text("paymentMode"),
                text("deadlineDate"),
                text("notes"),
                text("gameZoneId"),
                integer("dailyRefreshMinutes"),
                integer("weeklyRefreshDay"),
                integer("weeklyRefreshMinutes"),
                integer("createdAt"),
                integer("updatedAt"),
                integer("completedAt"),
                text("sourceTemplateId"),
                text("sourceTemplateName"),
                text("sourceTemplateVersion"),
                integer("cancelledAt"),
            ),
        ),
        Table(
            name = "hosting_periods",
            columns = listOf(
                text("id"),
                text("orderId"),
                integer("periodIndex"),
                text("startDate"),
                text("endDate"),
                integer("priceCents"),
                text("gameZoneId"),
                integer("dailyRefreshMinutes"),
                integer("weeklyRefreshDay"),
                integer("weeklyRefreshMinutes"),
                integer("createdAt"),
                text("renewalOperationId"),
            ),
        ),
        Table(
            name = "task_definitions",
            columns = listOf(
                text("id"),
                text("orderId"),
                text("periodId"),
                text("title"),
                text("progressType"),
                text("startValue"),
                text("targetValue"),
                text("currentValue"),
                text("unit"),
                text("recurrence"),
                text("weekdays"),
                integer("everyNDays"),
                integer("sortOrder"),
                integer("completedAt"),
                text("completionBatchId"),
                integer("createdAt"),
                integer("updatedAt"),
                integer("monthDay"),
            ),
        ),
        Table(
            name = "task_occurrences",
            columns = listOf(
                text("id"),
                text("taskDefinitionId"),
                text("occurrenceKey"),
                text("gameDate"),
                text("windowStart"),
                text("windowEnd"),
                integer("completedAt"),
                integer("recordedAt"),
                integer("backfilled"),
                text("completionBatchId"),
                text("value"),
                integer("createdAt"),
            ),
        ),
        Table(
            name = "completion_batch_items",
            columns = listOf(
                text("id"),
                text("batchId"),
                text("itemType"),
                text("occurrenceId"),
                text("taskDefinitionId"),
                integer("previousCompletedAt"),
                integer("previousRecordedAt"),
                integer("previousBackfilled"),
                text("previousValue"),
                text("previousCompletionBatchId"),
                integer("appliedCompletedAt"),
                integer("appliedRecordedAt"),
                integer("appliedBackfilled"),
                text("appliedValue"),
                text("appliedCompletionBatchId"),
            ),
        ),
        Table(
            name = "attachments",
            columns = listOf(
                text("id"),
                text("taskDefinitionId"),
                text("occurrenceId"),
                text("relativePath"),
                text("mimeType"),
                integer("sizeBytes"),
                integer("createdAt"),
            ),
        ),
        Table(
            name = "receipts",
            columns = listOf(
                text("id"),
                text("operationId"),
                text("orderId"),
                text("periodId"),
                integer("amountCents"),
                integer("receivedAt"),
                text("channel"),
                text("note"),
                text("status"),
                text("replacedByReceiptId"),
                integer("createdAt"),
            ),
        ),
        Table(
            name = "receipt_corrections",
            columns = listOf(
                text("id"),
                text("operationId"),
                text("receiptId"),
                text("kind"),
                text("reason"),
                integer("previousAmountCents"),
                integer("previousReceivedAt"),
                text("previousChannel"),
                text("previousNote"),
                text("newReceiptId"),
                integer("newAmountCents"),
                integer("newReceivedAt"),
                text("newChannel"),
                text("newNote"),
                integer("createdAt"),
            ),
        ),
        Table(
            name = "order_creation_operations",
            columns = listOf(
                text("operationId"),
                text("orderType"),
                text("fingerprint"),
                text("orderId"),
                text("receiptId"),
                integer("createdAt"),
            ),
        ),
        Table(
            name = "reminder_settings",
            columns = listOf(
                integer("id"),
                integer("shortDeadlineEnabled"),
                integer("shortDeadlineMinutes"),
                integer("hostingExpiryEnabled"),
                integer("hostingExpiryMinutes"),
                integer("unfinishedTasksEnabled"),
                integer("unfinishedTasksMinutes"),
                integer("updatedAt"),
            ),
            singleton = true,
        ),
    )

    /** Tables in the archive, parents first. */
    val includedTables: List<Table> = tables.filter { it.included }

    /** Tables that exist in the schema but are intentionally local-only. */
    val localOnlyTables: List<Table> = tables.filterNot { it.included }

    /** Delete order: children before parents, so `RESTRICT` foreign keys stay valid. */
    val deletionOrder: List<String> = includedTables.asReversed().map { it.name }

    fun table(name: String): Table? = tables.firstOrNull { it.name == name }
}
