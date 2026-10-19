package com.daigan.record.backup

import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.entity.AttachmentEntity
import com.daigan.record.data.local.entity.CompletionBatchEntity
import com.daigan.record.data.local.entity.CompletionBatchItemEntity
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.local.entity.GameTemplateEntity
import com.daigan.record.data.local.entity.HostingPeriodEntity
import com.daigan.record.data.local.entity.OrderCreationOperationEntity
import com.daigan.record.data.local.entity.OrderEntity
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.local.entity.ReceiptCorrectionEntity
import com.daigan.record.data.local.entity.ReceiptEntity
import com.daigan.record.data.local.entity.TaskDefinitionEntity
import com.daigan.record.data.local.entity.TaskOccurrenceEntity
import com.daigan.record.data.model.BatchItemType
import com.daigan.record.data.model.BatchScope
import com.daigan.record.data.model.LoginMethod
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.ReceiptCorrectionKind
import com.daigan.record.data.model.ReceiptStatus
import com.daigan.record.data.model.RecurrenceType
import java.io.File
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Business validation of a staged restore.
 *
 * Structural validation (types, enums, dates, decimals) already happened while the rows
 * were written into the isolated staging database: Room's converters throw on an unknown
 * enum, an unparseable date or an invalid decimal. This validator adds the **relational
 * and financial contracts** the rest of the app relies on, so a restored dataset cannot
 * contain an order without an account, a receipt belonging to another order's period, a
 * negative amount, or an attachment whose declared size does not match its file.
 */
@Singleton
class BackupValidator @Inject constructor() {

    suspend fun validate(
        database: RecordDatabase,
        dataSet: BackupDataSet,
        extracted: ExtractedArchive,
    ) {
        verifyCounts(dataSet, extracted.manifest)
        val (owners, accounts, orders, periods, tasks, occurrences, batches, items) =
            readStructured(database)
        val (attachments, receipts, corrections, operations) = readFinancial(database)
        val (templates, categories, templateTasks, seeds) = readTemplates(database)
        val reminder = database.reminderSettingsDao().find()

        val ownerById = owners.associateBy { it.id }
        val accountById = accounts.associateBy { it.id }
        val orderById = orders.associateBy { it.id }
        val periodById = periods.associateBy { it.id }
        val taskById = tasks.associateBy { it.id }
        val occurrenceById = occurrences.associateBy { it.id }
        val batchById = batches.associateBy { it.id }
        val receiptById = receipts.associateBy { it.id }
        val templateById = templates.associateBy { it.id }
        val categoryById = categories.associateBy { it.id }

        validateOwners(owners, dataSet)
        validateAccounts(accounts, ownerById)
        validateOrders(orders, accountById)
        validatePeriods(periods, orderById)
        validateTasks(tasks, orderById, periodById)
        validateOccurrences(occurrences, taskById, orderById)
        validateBatches(batches, items, taskById, occurrenceById)
        validateReceipts(receipts, orders, orderById, periodById, receiptById)
        validateCorrections(corrections, receiptById)
        validateOperations(operations, orderById, receiptById)
        validateTemplates(templates, categories, templateTasks, templateById, categoryById, seeds)
        validateAttachments(attachments, taskById, occurrenceById, extracted)
        validateReminderSettings(reminder)

        // A batch of writes can only be trusted if SQLite itself sees no dangling foreign
        // key after the staging insert.
        val raw = database.openHelper.writableDatabase
        raw.query("PRAGMA foreign_key_check").use { cursor ->
            if (cursor.moveToNext()) {
                throw BackupException("备份中的记录关系不完整，无法恢复（存在无对应记录的数据）。")
            }
        }
        raw.query("PRAGMA integrity_check").use { cursor ->
            if (cursor.moveToFirst() && cursor.getString(0) != "ok") {
                throw BackupException("备份中的数据库结构校验失败，无法恢复。")
            }
        }
        // A single batch may not reference rows of another dataset.
        if (batchById.isEmpty() && items.isNotEmpty()) {
            throw BackupException("备份中的批量完成记录缺少归属，无法恢复。")
        }
    }

    private fun verifyCounts(dataSet: BackupDataSet, manifest: BackupManifest) {
        BackupTableCatalog.includedTables.forEach { table ->
            val declared = manifest.tableCounts[table.name] ?: 0
            val actual = dataSet.count(table.name)
            if (declared != actual) {
                throw BackupException("备份中的「${table.name}」记录数量与清单不一致，无法恢复。")
            }
        }
    }

    private data class StructuredRows(
        val owners: List<OwnerEntity>,
        val accounts: List<GameAccountEntity>,
        val orders: List<OrderEntity>,
        val periods: List<HostingPeriodEntity>,
        val tasks: List<TaskDefinitionEntity>,
        val occurrences: List<TaskOccurrenceEntity>,
        val batches: List<CompletionBatchEntity>,
        val items: List<CompletionBatchItemEntity>,
    )

    private suspend fun readStructured(database: RecordDatabase): StructuredRows = wrapStructural {
        StructuredRows(
            owners = database.ownerDao().findAll(),
            accounts = database.gameAccountDao().findAll(),
            orders = database.orderDao().findAllOrders(),
            periods = database.orderDao().findAllPeriods(),
            tasks = database.orderDao().findAllTasks(),
            occurrences = database.orderDao().findAllOccurrences(),
            batches = database.completionBatchDao().findAllBatches(),
            items = database.completionBatchDao().findAllItems(),
        )
    }

    private data class FinancialRows(
        val attachments: List<AttachmentEntity>,
        val receipts: List<ReceiptEntity>,
        val corrections: List<ReceiptCorrectionEntity>,
        val operations: List<OrderCreationOperationEntity>,
    )

    private suspend fun readFinancial(database: RecordDatabase): FinancialRows = wrapStructural {
        FinancialRows(
            attachments = database.orderDao().findAllAttachments(),
            receipts = database.receiptDao().findAll(),
            corrections = database.receiptDao().findAllCorrections(),
            operations = database.creationOperationDao().findAll(),
        )
    }

    private data class TemplateRows(
        val templates: List<GameTemplateEntity>,
        val categories: List<com.daigan.record.data.local.entity.TemplateCategoryEntity>,
        val tasks: List<com.daigan.record.data.local.entity.TemplateTaskEntity>,
        val seeds: List<com.daigan.record.data.local.entity.TemplateSeedStateEntity>,
    )

    private suspend fun readTemplates(database: RecordDatabase): TemplateRows = wrapStructural {
        val dao = database.templateDao()
        TemplateRows(
            templates = dao.findAllTemplates(),
            categories = dao.findAllCategories(),
            tasks = dao.findAllTemplateTasks(),
            seeds = dao.findAllSeedStates(),
        )
    }

    /** Room converters throw on unknown enums / invalid dates / invalid decimals. */
    private inline fun <T> wrapStructural(block: () -> T): T = try {
        block()
    } catch (error: BackupException) {
        throw error
    } catch (error: RuntimeException) {
        throw BackupException("备份包含无效或无法识别的业务数据，无法恢复。", error)
    }

    private fun validateOwners(owners: List<OwnerEntity>, dataSet: BackupDataSet) {
        owners.forEach { owner ->
            ensure(owner.id.isNotBlank()) { "备份中的老板记录缺少编号，无法恢复。" }
            ensure(owner.number > 0) { "备份中的老板编号无效，无法恢复。" }
            ensure(owner.nickname.isNotBlank()) { "备份中的老板昵称无效，无法恢复。" }
            ensure(owner.createdAt.toEpochMilli() > 0) { "备份中的老板创建时间无效，无法恢复。" }
        }
        val sequenceRows = dataSet.rows("owner_number_sequence")
        ensure(sequenceRows.size <= 1) { "备份中的老板编号计数器重复，无法恢复。" }
        sequenceRows.firstOrNull()?.let { row ->
            val nextNumber = (row.getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            ensure(nextNumber != null && nextNumber > 0) { "备份中的老板编号计数器无效，无法恢复。" }
        }
        if (owners.isNotEmpty()) {
            ensure(sequenceRows.size == 1) { "备份缺少老板编号计数器，无法恢复。" }
            val maxNumber = owners.maxOf { it.number }
            val nextNumber = (sequenceRows.first().getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toLongOrNull()
                ?: throw BackupException("备份中的老板编号计数器无效，无法恢复。")
            ensure(nextNumber > maxNumber) { "备份中的老板编号计数器与老板编号不一致，无法恢复。" }
        }
    }

    private fun validateAccounts(
        accounts: List<GameAccountEntity>,
        ownerById: Map<String, OwnerEntity>,
    ) {
        accounts.forEach { account ->
            ensure(ownerById.containsKey(account.ownerId)) { "备份中的游戏账号没有对应老板，无法恢复。" }
            ensure(account.gameName.isNotBlank()) { "备份中的游戏账号缺少游戏名称，无法恢复。" }
            ensure(account.server.isNotBlank()) { "备份中的游戏账号缺少区服，无法恢复。" }
            ensure(account.uid.isNotBlank()) { "备份中的游戏账号缺少 UID，无法恢复。" }
            ensure(account.loginAccount.isNotBlank()) { "备份中的游戏账号缺少登录账号，无法恢复。" }
            if (account.loginMethod == LoginMethod.PASSWORD) {
                ensure(account.password != null) { "备份中的密码登录账号缺少密码，无法恢复。" }
            } else {
                ensure(account.password == null) {
                    "备份中的验证码登录账号保存了密码，与登录方式不一致，无法恢复。"
                }
            }
        }
    }

    private fun validateOrders(
        orders: List<OrderEntity>,
        accountById: Map<String, GameAccountEntity>,
    ) {
        orders.forEach { order ->
            ensure(accountById.containsKey(order.accountId)) {
                "备份中的订单没有对应游戏账号，无法恢复。"
            }
            ensure(order.title.isNotBlank()) { "备份中的订单缺少标题，无法恢复。" }
            ensureRefresh(order.gameZoneId, order.dailyRefreshMinutes, order.weeklyRefreshDay,
                order.weeklyRefreshMinutes)
            when (order.type) {
                OrderType.SHORT_TASK -> {
                    val price = order.priceCents
                    ensure(price != null && price in 1..Money.MAX_CENTS) {
                        "备份中的短期订单金额无效，无法恢复。"
                    }
                }

                OrderType.HOSTING -> ensure(order.priceCents == null) {
                    "备份中的托管订单不应保存整单金额，无法恢复。"
                }
            }
        }
    }

    private fun validatePeriods(
        periods: List<HostingPeriodEntity>,
        orderById: Map<String, OrderEntity>,
    ) {
        periods.forEach { period ->
            val order = orderById[period.orderId]
            ensure(order != null && order.type == OrderType.HOSTING) {
                "备份中的托管期次没有对应托管订单，无法恢复。"
            }
            ensure(period.periodIndex >= 0) { "备份中的托管期次序号无效，无法恢复。" }
            ensure(!period.endDate.isBefore(period.startDate)) {
                "备份中的托管期次结束日期早于开始日期，无法恢复。"
            }
            ensure(period.priceCents in 1..Money.MAX_CENTS) {
                "备份中的托管期次金额无效，无法恢复。"
            }
            ensureRefresh(period.gameZoneId, period.dailyRefreshMinutes, period.weeklyRefreshDay,
                period.weeklyRefreshMinutes)
        }
    }

    private fun validateTasks(
        tasks: List<TaskDefinitionEntity>,
        orderById: Map<String, OrderEntity>,
        periodById: Map<String, HostingPeriodEntity>,
    ) {
        tasks.forEach { task ->
            val order = orderById[task.orderId]
            ensure(order != null) { "备份中的任务没有对应订单，无法恢复。" }
            ensure(task.title.isNotBlank()) { "备份中的任务缺少标题，无法恢复。" }
            ensure(!task.targetValue.toString().isBlank()) {
                "备份中的任务数值无效，无法恢复。"
            }
            if (order.type == OrderType.SHORT_TASK) {
                ensure(task.periodId == null) {
                    "备份中的短期订单任务关联了托管期次，无法恢复。"
                }
                ensure(task.completedAt == null || task.completedAt.toEpochMilli() > 0) {
                    "备份中的短期任务完成时间无效，无法恢复。"
                }
            } else {
                val period = task.periodId?.let(periodById::get)
                ensure(period != null && period.orderId == order.id) {
                    "备份中的托管任务未正确归属于本期，无法恢复。"
                }
                ensure(task.recurrence != RecurrenceType.ONE_TIME) {
                    "备份中的托管任务使用了一次性周期，无法恢复。"
                }
                ensure(task.completedAt == null) {
                    "备份中的托管任务不应保存任务级完成时间，无法恢复。"
                }
            }
            if (task.progressType == ProgressType.NUMERIC) {
                ensure(task.targetValue > task.startValue) {
                    "备份中的数值任务目标不大于起始值，无法恢复。"
                }
            }
            if (task.recurrence == RecurrenceType.MONTHLY) {
                ensure(task.monthDay in 1..31) { "备份中的每月日号无效，无法恢复。" }
            }
            if (task.recurrence == RecurrenceType.EVERY_N_DAYS) {
                ensure((task.everyNDays ?: 0) >= 1) {
                    "备份中的任务间隔天数无效，无法恢复。"
                }
            }
        }
    }

    private fun validateOccurrences(
        occurrences: List<TaskOccurrenceEntity>,
        taskById: Map<String, TaskDefinitionEntity>,
        orderById: Map<String, OrderEntity>,
    ) {
        occurrences.forEach { occurrence ->
            val task = taskById[occurrence.taskDefinitionId]
            ensure(task != null) { "备份中的打卡记录没有对应任务，无法恢复。" }
            val order = orderById[task.orderId]
            ensure(order != null && order.type == OrderType.HOSTING) {
                "备份中的打卡记录不属于托管订单，无法恢复。"
            }
            val expectedPrefix = when (task.recurrence) {
                RecurrenceType.MONTHLY -> "M:"
                RecurrenceType.DAILY -> "D:"
                RecurrenceType.SELECTED_WEEKDAYS -> "S:"
                RecurrenceType.EVERY_N_DAYS -> "E:"
                RecurrenceType.WEEKLY -> "W:"
                RecurrenceType.ONE_TIME -> throw BackupException(
                    "备份中存在一次性任务的打卡记录，无法恢复。",
                )
            }
            ensure(occurrence.occurrenceKey.startsWith(expectedPrefix)) {
                "备份中的打卡记录键与任务周期不一致，无法恢复。"
            }
            val datePart = occurrence.occurrenceKey.removePrefix(expectedPrefix)
            ensure(runCatching { java.time.LocalDate.parse(datePart) }.isSuccess) {
                "备份中的打卡记录日期无效，无法恢复。"
            }
            if (task.recurrence == RecurrenceType.MONTHLY) {
                val date = java.time.LocalDate.parse(datePart)
                ensure(date.dayOfMonth == task.monthDay && date == occurrence.gameDate) {
                    "备份中的每月打卡日期与固定日号不一致，无法恢复。"
                }
            }
            ensure((occurrence.windowStart == null) == (occurrence.windowEnd == null)) {
                "备份中的打卡记录时间窗口不完整，无法恢复。"
            }
            if (occurrence.completedAt != null) {
                ensure(occurrence.recordedAt != null) {
                    "备份中的已完成打卡缺少记录时间，无法恢复。"
                }
            }
        }
    }

    private fun validateBatches(
        batches: List<CompletionBatchEntity>,
        items: List<CompletionBatchItemEntity>,
        taskById: Map<String, TaskDefinitionEntity>,
        occurrenceById: Map<String, TaskOccurrenceEntity>,
    ) {
        val batchById = batches.associateBy { it.id }
        batches.forEach { batch ->
            ensure(batch.operationId.isNotBlank()) {
                "备份中的批量完成记录缺少操作编号，无法恢复。"
            }
            if (batch.undoneAt != null) {
                ensure(!batch.undoneAt.isBefore(batch.createdAt)) {
                    "备份中的批量完成撤销时间无效，无法恢复。"
                }
            }
        }
        items.forEach { item ->
            val batch = batchById[item.batchId]
            ensure(batch != null) { "备份中的批量完成明细没有对应批次，无法恢复。" }
            when (item.itemType) {
                BatchItemType.OCCURRENCE -> {
                    ensure(item.occurrenceId != null && item.taskDefinitionId == null) {
                        "备份中的批量完成明细归属不唯一，无法恢复。"
                    }
                    ensure(occurrenceById.containsKey(item.occurrenceId)) {
                        "备份中的批量完成明细没有对应打卡记录，无法恢复。"
                    }
                }

                BatchItemType.SHORT_TASK -> {
                    ensure(item.taskDefinitionId != null && item.occurrenceId == null) {
                        "备份中的批量完成明细归属不唯一，无法恢复。"
                    }
                    ensure(taskById.containsKey(item.taskDefinitionId)) {
                        "备份中的批量完成明细没有对应任务，无法恢复。"
                    }
                }
            }
            ensure(batch.scope != BatchScope.SHORT_ORDER || item.itemType == BatchItemType.SHORT_TASK) {
                "备份中的批量完成范围与明细不一致，无法恢复。"
            }
        }
    }

    private fun validateReceipts(
        receipts: List<ReceiptEntity>,
        orders: List<OrderEntity>,
        orderById: Map<String, OrderEntity>,
        periodById: Map<String, HostingPeriodEntity>,
        receiptById: Map<String, ReceiptEntity>,
    ) {
        val activeTotals = receipts.filter { it.status == ReceiptStatus.ACTIVE }
            .groupBy { it.orderId to it.periodId }
        activeTotals.forEach { (key, rows) ->
            val limit = key.second?.let { periodById[it]?.priceCents }
                ?: orderById[key.first]?.priceCents
            val total = try { Math.addExact(0L, rows.fold(0L) { sum, row -> Math.addExact(sum, row.amountCents) }) }
            catch (error: ArithmeticException) {
                throw BackupException("备份中的收款总额超出范围，无法恢复。", error)
            }
            ensure(limit != null && total <= limit) { "备份中的有效收款超过对应订单或期次金额，无法恢复。" }
        }
        receipts.forEach { receipt ->
            ensure(receipt.amountCents in 1..Money.MAX_CENTS) {
                "备份中的收款金额无效，无法恢复。"
            }
            ensure(receipt.operationId.isNotBlank()) {
                "备份中的收款记录缺少操作编号，无法恢复。"
            }
            val order = orderById[receipt.orderId]
            ensure(order != null) { "备份中的收款没有对应订单，无法恢复。" }
            when (order.type) {
                OrderType.SHORT_TASK -> ensure(receipt.periodId == null) {
                    "备份中的短期订单收款关联了托管期次，无法恢复。"
                }

                OrderType.HOSTING -> {
                    val period = receipt.periodId?.let(periodById::get)
                    ensure(period != null && period.orderId == order.id) {
                        "备份中的托管收款没有归属到该订单的期次，无法恢复。"
                    }
                }
            }
            when (receipt.status) {
                ReceiptStatus.ACTIVE -> ensure(receipt.replacedByReceiptId == null) {
                    "备份中的有效收款指向了替换记录，无法恢复。"
                }

                ReceiptStatus.REPLACED -> {
                    val replacement = receipt.replacedByReceiptId?.let(receiptById::get)
                    ensure(replacement != null && replacement.id != receipt.id &&
                        replacement.orderId == receipt.orderId && replacement.periodId == receipt.periodId
                    ) {
                        "备份中的收款替换关系不完整或归属不一致，无法恢复。"
                    }
                }

                ReceiptStatus.VOIDED -> ensure(receipt.replacedByReceiptId == null) {
                    "备份中的作废收款不应指向替换记录，无法恢复。"
                }
            }
            ensure(receipt.receivedAt.toEpochMilli() > 0) {
                "备份中的实际到账时间无效，无法恢复。"
            }
        }
        // Every order in the backup must be readable through the map (cheap consistency).
        ensure(orders.size == orderById.size) { "备份中的订单编号重复，无法恢复。" }
    }

    private fun validateCorrections(
        corrections: List<ReceiptCorrectionEntity>,
        receiptById: Map<String, ReceiptEntity>,
    ) {
        corrections.forEach { correction ->
            ensure(correction.operationId.isNotBlank()) {
                "备份中的更正记录缺少操作编号，无法恢复。"
            }
            ensure(receiptById.containsKey(correction.receiptId)) {
                "备份中的更正记录没有对应收款，无法恢复。"
            }
            ensure(correction.previousAmountCents in 1..Money.MAX_CENTS) {
                "备份中的更正前金额无效，无法恢复。"
            }
            when (correction.kind) {
                ReceiptCorrectionKind.AMEND -> {
                    ensure(correction.newReceiptId != null) {
                        "备份中的金额更正缺少新收款记录，无法恢复。"
                    }
                    val previous = checkNotNull(receiptById[correction.receiptId])
                    val replacement = receiptById[correction.newReceiptId]
                    ensure(replacement != null && replacement.id != previous.id &&
                        replacement.orderId == previous.orderId && replacement.periodId == previous.periodId
                    ) {
                        "备份中的金额更正指向了不存在或归属不一致的收款，无法恢复。"
                    }
                    val newAmount = correction.newAmountCents
                    ensure(newAmount != null && newAmount in 1..Money.MAX_CENTS) {
                        "备份中的更正后金额无效，无法恢复。"
                    }
                    ensure(correction.newReceivedAt != null && correction.newChannel != null) {
                        "备份中的更正后信息不完整，无法恢复。"
                    }
                }

                ReceiptCorrectionKind.VOID -> {
                    ensure(correction.newReceiptId == null && correction.newAmountCents == null &&
                        correction.newReceivedAt == null && correction.newChannel == null &&
                        correction.newNote == null
                    ) {
                        "备份中的作废记录包含了新金额，无法恢复。"
                    }
                }
            }
        }
    }

    private fun validateOperations(
        operations: List<OrderCreationOperationEntity>,
        orderById: Map<String, OrderEntity>,
        receiptById: Map<String, ReceiptEntity>,
    ) {
        operations.forEach { operation ->
            val order = orderById[operation.orderId]
            ensure(order != null) { "备份中的建单记录没有对应订单，无法恢复。" }
            ensure(order.type == operation.orderType) {
                "备份中的建单记录类型与订单不一致，无法恢复。"
            }
            ensure(operation.fingerprint.isNotBlank()) {
                "备份中的建单记录缺少请求指纹，无法恢复。"
            }
            operation.receiptId?.let { receiptId ->
                val receipt = receiptById[receiptId]
                ensure(receipt != null && receipt.orderId == operation.orderId) {
                    "备份中的首笔收款与订单不一致，无法恢复。"
                }
            }
        }
    }

    private fun validateTemplates(
        templates: List<GameTemplateEntity>,
        categories: List<com.daigan.record.data.local.entity.TemplateCategoryEntity>,
        templateTasks: List<com.daigan.record.data.local.entity.TemplateTaskEntity>,
        templateById: Map<String, GameTemplateEntity>,
        categoryById: Map<String, com.daigan.record.data.local.entity.TemplateCategoryEntity>,
        seeds: List<com.daigan.record.data.local.entity.TemplateSeedStateEntity>,
    ) {
        val builtInKeys = HashSet<String>()
        templates.forEach { template ->
            ensure(template.gameName.isNotBlank()) { "备份中的模板缺少游戏名称，无法恢复。" }
            ensure(template.templateVersion.isNotBlank()) { "备份中的模板缺少版本，无法恢复。" }
            ensureRefresh(template.zoneId, template.dailyRefreshMinutes, template.weeklyRefreshDay,
                template.weeklyRefreshMinutes)
            template.builtInKey?.let {
                ensure(builtInKeys.add(it)) { "备份中的内置模板标识重复，无法恢复。" }
            }
        }
        categories.forEach { category ->
            ensure(templateById.containsKey(category.templateId)) {
                "备份中的模板分类没有对应模板，无法恢复。"
            }
            ensure(category.name.isNotBlank()) { "备份中的模板分类缺少名称，无法恢复。" }
        }
        templateTasks.forEach { task ->
            if (task.recurrence == RecurrenceType.MONTHLY) {
                ensure(task.monthDay in 1..31) { "备份中的模板每月日号无效，无法恢复。" }
            }
            ensure(templateById.containsKey(task.templateId)) {
                "备份中的模板任务没有对应模板，无法恢复。"
            }
            val category = categoryById[task.categoryId]
            ensure(category != null && category.templateId == task.templateId) {
                "备份中的模板任务分类归属不一致，无法恢复。"
            }
            ensure(task.title.isNotBlank()) { "备份中的模板任务缺少名称，无法恢复。" }
            if (task.progressType == ProgressType.NUMERIC) {
                ensure(task.targetValue > task.startValue) {
                    "备份中的模板数值任务目标不大于起始值，无法恢复。"
                }
            }
        }
        seeds.forEach { seed ->
            ensure(seed.seedKey.isNotBlank()) { "备份中的内置模板标记无效，无法恢复。" }
        }
    }

    private fun validateAttachments(
        attachments: List<AttachmentEntity>,
        taskById: Map<String, TaskDefinitionEntity>,
        occurrenceById: Map<String, TaskOccurrenceEntity>,
        extracted: ExtractedArchive,
    ) {
        val directory = extracted.attachmentsDirectory
        val manifestByPath = extracted.manifest.attachments.associateBy { it.relativePath }
        attachments.forEach { attachment ->
            ensure((attachment.taskDefinitionId == null) != (attachment.occurrenceId == null)) {
                "备份中的截图归属不唯一，无法恢复。"
            }
            attachment.taskDefinitionId?.let {
                ensure(taskById.containsKey(it)) { "备份中的截图没有对应任务，无法恢复。" }
            }
            attachment.occurrenceId?.let {
                ensure(occurrenceById.containsKey(it)) { "备份中的截图没有对应打卡记录，无法恢复。" }
            }
            ensure(SafeNames.isSafeAttachmentName(attachment.relativePath)) {
                "备份中的截图文件名不安全，无法恢复。"
            }
            ensure(attachment.sizeBytes in 1..BackupFormat.Limits.MAX_ATTACHMENT_BYTES) {
                "备份中的截图大小无效，无法恢复。"
            }
            ensure(attachment.mimeType.startsWith("image/")) {
                "备份中的截图类型无效，无法恢复。"
            }
            val manifestEntry = manifestByPath[attachment.relativePath]
                ?: throw BackupException("备份清单中没有截图「${attachment.relativePath}」，无法恢复。")
            ensure(manifestEntry.id == attachment.id && manifestEntry.mimeType == attachment.mimeType) {
                "备份中的截图编号或类型与清单不一致，无法恢复。"
            }
            ensure(manifestEntry.sizeBytes == attachment.sizeBytes) {
                "备份中的截图大小与清单不一致，无法恢复。"
            }
            val file = File(directory, attachment.relativePath)
            ensure(file.isFile) { "备份缺少截图文件「${attachment.relativePath}」，无法恢复。" }
            ensure(file.length() == attachment.sizeBytes) {
                "备份中的截图「${attachment.relativePath}」内容大小不一致，无法恢复。"
            }
        }
        val referenced = attachments.map { it.relativePath }.toSet()
        val declared = manifestByPath.keys
        ensure(referenced == declared) {
            "备份中的截图清单与截图记录不一致，无法恢复。"
        }
    }

    private fun validateReminderSettings(
        settings: com.daigan.record.data.local.entity.ReminderSettingsEntity?,
    ) {
        if (settings == null) return
        ensure(settings.id == 0) { "备份中的提醒设置重复，无法恢复。" }
        listOf(
            settings.shortDeadlineMinutes,
            settings.hostingExpiryMinutes,
            settings.unfinishedTasksMinutes,
        ).forEach { minutes ->
            ensure(minutes in 0..1439) { "备份中的提醒时间无效，无法恢复。" }
        }
    }

    private fun ensureRefresh(
        zoneId: String,
        dailyMinutes: Int,
        weeklyDay: Int,
        weeklyMinutes: Int,
    ) {
        ensure(runCatching { ZoneId.of(zoneId) }.isSuccess) {
            "备份中的时区无效，无法恢复。"
        }
        ensure(dailyMinutes in 0..1439) { "备份中的每日刷新时间无效，无法恢复。" }
        ensure(weeklyDay in 1..7) { "备份中的每周刷新日无效，无法恢复。" }
        ensure(weeklyMinutes in 0..1439) { "备份中的每周刷新时间无效，无法恢复。" }
    }

    @OptIn(kotlin.contracts.ExperimentalContracts::class)
    private inline fun ensure(condition: Boolean, message: () -> String) {
        kotlin.contracts.contract { returns() implies condition }
        if (!condition) throw BackupException(message())
    }
}
