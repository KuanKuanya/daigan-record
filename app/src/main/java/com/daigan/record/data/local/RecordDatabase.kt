package com.daigan.record.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.daigan.record.data.local.converter.BigDecimalConverters
import com.daigan.record.data.local.converter.InstantConverters
import com.daigan.record.data.local.converter.LocalDateConverters
import com.daigan.record.data.local.converter.LoginMethodConverters
import com.daigan.record.data.local.converter.OrderEnumConverters
import com.daigan.record.data.local.dao.AttachmentDao
import com.daigan.record.data.local.dao.CompletionBatchDao
import com.daigan.record.data.local.dao.CreationOperationDao
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.OccurrenceDao
import com.daigan.record.data.local.dao.OrderDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.dao.ReceiptDao
import com.daigan.record.data.local.dao.TaskDao
import com.daigan.record.data.local.dao.TemplateDao
import com.daigan.record.data.local.entity.AttachmentEntity
import com.daigan.record.data.local.entity.CompletionBatchEntity
import com.daigan.record.data.local.entity.CompletionBatchItemEntity
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.local.entity.GameTemplateEntity
import com.daigan.record.data.local.entity.HostingPeriodEntity
import com.daigan.record.data.local.entity.OrderCreationOperationEntity
import com.daigan.record.data.local.entity.OrderEntity
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.local.entity.OwnerNumberSequenceEntity
import com.daigan.record.data.local.entity.ReceiptCorrectionEntity
import com.daigan.record.data.local.entity.ReceiptEntity
import com.daigan.record.data.local.entity.TaskDefinitionEntity
import com.daigan.record.data.local.entity.TaskOccurrenceEntity
import com.daigan.record.data.local.entity.TemplateCategoryEntity
import com.daigan.record.data.local.entity.TemplateSeedStateEntity
import com.daigan.record.data.local.entity.TemplateTaskEntity

/**
 * Current schema version, defined next to the migrations so tests do not hard code it.
 *
 * 1 -> 2 added the archive batch provenance columns.
 * 2 -> 3 adds orders, hosting periods, task snapshots, occurrences, completion batch
 *       audit rows and screenshot attachment metadata. No existing table is rewritten.
 * 3 -> 4 adds receipts and receipt corrections. Existing orders and periods keep no
 *       receipt rows: a paid amount is never inferred from completion or from a price.
 * 4 -> 5 adds `order_creation_operations`, the stored result of a whole create-order
 *       command. Existing orders keep no operation row: they were created before the
 *       command was idempotent, and inventing identities for them would be dishonest.
 * 5 -> 6 adds the editable game-template library, the built-in seed markers, an
 *       optional account default template and nullable order provenance columns.
 *       Existing orders, periods, tasks, occurrences, receipts, corrections and
 *       creation-operation rows are untouched: legacy rows keep NULL provenance and
 *       no template is invented for them.
 */
const val RECORD_DATABASE_VERSION = 6

/**
 * The single source of truth for business data.
 *
 * Version 6 is Phase 4: a persistent, editable template library plus honest creation
 * provenance. Templates are creation defaults only; orders copy their own task and
 * refresh snapshots and hold no foreign key to a template.
 */
@Database(
    entities = [
        OwnerEntity::class,
        GameAccountEntity::class,
        OwnerNumberSequenceEntity::class,
        OrderEntity::class,
        HostingPeriodEntity::class,
        TaskDefinitionEntity::class,
        TaskOccurrenceEntity::class,
        CompletionBatchEntity::class,
        CompletionBatchItemEntity::class,
        AttachmentEntity::class,
        ReceiptEntity::class,
        ReceiptCorrectionEntity::class,
        OrderCreationOperationEntity::class,
        GameTemplateEntity::class,
        TemplateCategoryEntity::class,
        TemplateTaskEntity::class,
        TemplateSeedStateEntity::class,
    ],
    version = RECORD_DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(
    InstantConverters::class,
    LoginMethodConverters::class,
    LocalDateConverters::class,
    BigDecimalConverters::class,
    OrderEnumConverters::class,
)
abstract class RecordDatabase : RoomDatabase() {
    abstract fun ownerDao(): OwnerDao

    abstract fun gameAccountDao(): GameAccountDao

    abstract fun orderDao(): OrderDao

    abstract fun taskDao(): TaskDao

    abstract fun occurrenceDao(): OccurrenceDao

    abstract fun completionBatchDao(): CompletionBatchDao

    abstract fun attachmentDao(): AttachmentDao

    abstract fun receiptDao(): ReceiptDao

    abstract fun creationOperationDao(): CreationOperationDao

    abstract fun templateDao(): TemplateDao

    companion object {
        const val NAME = "daigan-record.db"

        /**
         * Adds the archive batch columns introduced by the phase 1 fix.
         *
         * Both columns are nullable and stay NULL for rows written by version 1. That
         * is deliberate: the old schema cannot tell an account archived together with
         * its owner apart from an account archived on its own at the same millisecond,
         * so no provenance is invented during the migration. The conservative policy
         * for such legacy rows is documented in docs/data-model.md.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE owners ADD COLUMN archiveBatchId TEXT")
                db.execSQL("ALTER TABLE game_accounts ADD COLUMN archiveBatchId TEXT")
            }
        }

        /**
         * Phase 2 tables. Existing owner/account rows and every archive column are
         * untouched. The DDL mirrors the Room entities exactly (names, nullability,
         * foreign keys and explicitly named indices) so schema validation is strict.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `orders` (
                        `id` TEXT NOT NULL,
                        `accountId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `priceCents` INTEGER,
                        `paymentMode` TEXT NOT NULL,
                        `deadlineDate` TEXT,
                        `notes` TEXT,
                        `gameZoneId` TEXT NOT NULL,
                        `dailyRefreshMinutes` INTEGER NOT NULL,
                        `weeklyRefreshDay` INTEGER NOT NULL,
                        `weeklyRefreshMinutes` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`accountId`) REFERENCES `game_accounts`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_orders_accountId` ON `orders` (`accountId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_orders_accountId_type` " +
                        "ON `orders` (`accountId`, `type`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_orders_type_completedAt` " +
                        "ON `orders` (`type`, `completedAt`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hosting_periods` (
                        `id` TEXT NOT NULL,
                        `orderId` TEXT NOT NULL,
                        `periodIndex` INTEGER NOT NULL,
                        `startDate` TEXT NOT NULL,
                        `endDate` TEXT NOT NULL,
                        `priceCents` INTEGER NOT NULL,
                        `gameZoneId` TEXT NOT NULL,
                        `dailyRefreshMinutes` INTEGER NOT NULL,
                        `weeklyRefreshDay` INTEGER NOT NULL,
                        `weeklyRefreshMinutes` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `renewalOperationId` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_hosting_periods_orderId` " +
                        "ON `hosting_periods` (`orderId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_hosting_periods_orderId_periodIndex` " +
                        "ON `hosting_periods` (`orderId`, `periodIndex`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_hosting_periods_orderId_startDate` " +
                        "ON `hosting_periods` (`orderId`, `startDate`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_hosting_periods_renewalOperationId` " +
                        "ON `hosting_periods` (`renewalOperationId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `completion_batches` (
                        `id` TEXT NOT NULL,
                        `operationId` TEXT NOT NULL,
                        `scope` TEXT NOT NULL,
                        `targetId` TEXT,
                        `gameDate` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `undoneAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_completion_batches_operationId` " +
                        "ON `completion_batches` (`operationId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `task_definitions` (
                        `id` TEXT NOT NULL,
                        `orderId` TEXT NOT NULL,
                        `periodId` TEXT,
                        `title` TEXT NOT NULL,
                        `progressType` TEXT NOT NULL,
                        `startValue` TEXT NOT NULL,
                        `targetValue` TEXT NOT NULL,
                        `currentValue` TEXT NOT NULL,
                        `unit` TEXT,
                        `recurrence` TEXT NOT NULL,
                        `weekdays` TEXT,
                        `everyNDays` INTEGER,
                        `sortOrder` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `completionBatchId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`periodId`) REFERENCES `hosting_periods`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`completionBatchId`) REFERENCES `completion_batches`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_definitions_orderId` " +
                        "ON `task_definitions` (`orderId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_definitions_periodId` " +
                        "ON `task_definitions` (`periodId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_definitions_completionBatchId` " +
                        "ON `task_definitions` (`completionBatchId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `task_occurrences` (
                        `id` TEXT NOT NULL,
                        `taskDefinitionId` TEXT NOT NULL,
                        `occurrenceKey` TEXT NOT NULL,
                        `gameDate` TEXT NOT NULL,
                        `windowStart` TEXT,
                        `windowEnd` TEXT,
                        `completedAt` INTEGER,
                        `recordedAt` INTEGER,
                        `backfilled` INTEGER NOT NULL,
                        `completionBatchId` TEXT,
                        `value` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`taskDefinitionId`) REFERENCES `task_definitions`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`completionBatchId`) REFERENCES `completion_batches`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_task_occurrences_taskDefinitionId` " +
                        "ON `task_occurrences` (`taskDefinitionId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_task_occurrences_taskDefinitionId_occurrenceKey` " +
                        "ON `task_occurrences` (`taskDefinitionId`, `occurrenceKey`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_occurrences_gameDate` " +
                        "ON `task_occurrences` (`gameDate`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_occurrences_completionBatchId` " +
                        "ON `task_occurrences` (`completionBatchId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `completion_batch_items` (
                        `id` TEXT NOT NULL,
                        `batchId` TEXT NOT NULL,
                        `itemType` TEXT NOT NULL,
                        `occurrenceId` TEXT,
                        `taskDefinitionId` TEXT,
                        `previousCompletedAt` INTEGER,
                        `previousRecordedAt` INTEGER,
                        `previousBackfilled` INTEGER,
                        `previousValue` TEXT,
                        `previousCompletionBatchId` TEXT,
                        `appliedCompletedAt` INTEGER,
                        `appliedRecordedAt` INTEGER,
                        `appliedBackfilled` INTEGER,
                        `appliedValue` TEXT,
                        `appliedCompletionBatchId` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`batchId`) REFERENCES `completion_batches`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`occurrenceId`) REFERENCES `task_occurrences`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`taskDefinitionId`) REFERENCES `task_definitions`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_completion_batch_items_batchId` " +
                        "ON `completion_batch_items` (`batchId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_completion_batch_items_occurrenceId` " +
                        "ON `completion_batch_items` (`occurrenceId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_completion_batch_items_taskDefinitionId` " +
                        "ON `completion_batch_items` (`taskDefinitionId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `attachments` (
                        `id` TEXT NOT NULL,
                        `taskDefinitionId` TEXT,
                        `occurrenceId` TEXT,
                        `relativePath` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`taskDefinitionId`) REFERENCES `task_definitions`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`occurrenceId`) REFERENCES `task_occurrences`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_attachments_taskDefinitionId` " +
                        "ON `attachments` (`taskDefinitionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_attachments_occurrenceId` " +
                        "ON `attachments` (`occurrenceId`)",
                )
            }
        }

        /**
         * Phase 3 receipts. Only two new tables are created; every Phase 2 row stays
         * exactly as it was. No receipt is fabricated for an existing order or period:
         * a price is a promise, not a payment.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `receipts` (
                        `id` TEXT NOT NULL,
                        `operationId` TEXT NOT NULL,
                        `orderId` TEXT NOT NULL,
                        `periodId` TEXT,
                        `amountCents` INTEGER NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        `channel` TEXT NOT NULL,
                        `note` TEXT,
                        `status` TEXT NOT NULL,
                        `replacedByReceiptId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`periodId`) REFERENCES `hosting_periods`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_receipts_operationId` " +
                        "ON `receipts` (`operationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_receipts_orderId` " +
                        "ON `receipts` (`orderId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_receipts_periodId` " +
                        "ON `receipts` (`periodId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_receipts_receivedAt` " +
                        "ON `receipts` (`receivedAt`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `receipt_corrections` (
                        `id` TEXT NOT NULL,
                        `operationId` TEXT NOT NULL,
                        `receiptId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `reason` TEXT,
                        `previousAmountCents` INTEGER NOT NULL,
                        `previousReceivedAt` INTEGER NOT NULL,
                        `previousChannel` TEXT NOT NULL,
                        `previousNote` TEXT,
                        `newReceiptId` TEXT,
                        `newAmountCents` INTEGER,
                        `newReceivedAt` INTEGER,
                        `newChannel` TEXT,
                        `newNote` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`receiptId`) REFERENCES `receipts`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`newReceiptId`) REFERENCES `receipts`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_receipt_corrections_operationId` " +
                        "ON `receipt_corrections` (`operationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_receipt_corrections_receiptId` " +
                        "ON `receipt_corrections` (`receiptId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_receipt_corrections_newReceiptId` " +
                        "ON `receipt_corrections` (`newReceiptId`)",
                )
            }
        }

        /**
         * Phase 3 repair: persistent identity of a whole create-order command.
         *
         * Only one new table is created; orders, periods, receipts and every Phase 2
         * row stay untouched. Existing orders get no synthetic operation row, so their
         * historical creation cannot be mistaken for a retry of a new command.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `order_creation_operations` (
                        `operationId` TEXT NOT NULL,
                        `orderType` TEXT NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `orderId` TEXT NOT NULL,
                        `receiptId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`operationId`),
                        FOREIGN KEY(`orderId`) REFERENCES `orders`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_order_creation_operations_operationId` " +
                        "ON `order_creation_operations` (`operationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_order_creation_operations_orderId` " +
                        "ON `order_creation_operations` (`orderId`)",
                )
            }
        }

        /**
         * Phase 4 template library. Only new tables and nullable columns are added.
         *
         * No template content is inserted by the migration: built-ins are seeded through
         * the repository with a durable seed marker, so an upgrade never re-creates a
         * template the user already deleted and never overwrites an edited one. Existing
         * orders keep NULL provenance and existing accounts keep no default template;
         * no historical template version is fabricated.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `game_templates` (
                        `id` TEXT NOT NULL,
                        `gameName` TEXT NOT NULL,
                        `templateVersion` TEXT NOT NULL,
                        `gameVersionNotes` TEXT,
                        `zoneId` TEXT NOT NULL,
                        `dailyRefreshMinutes` INTEGER NOT NULL,
                        `weeklyRefreshDay` INTEGER NOT NULL,
                        `weeklyRefreshMinutes` INTEGER NOT NULL,
                        `origin` TEXT NOT NULL,
                        `builtInKey` TEXT,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_game_templates_builtInKey` ON `game_templates` (`builtInKey`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `template_categories` (
                        `id` TEXT NOT NULL,
                        `templateId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`templateId`) REFERENCES `game_templates`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_template_categories_templateId` " +
                        "ON `template_categories` (`templateId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `template_tasks` (
                        `id` TEXT NOT NULL,
                        `templateId` TEXT NOT NULL,
                        `categoryId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `progressType` TEXT NOT NULL,
                        `startValue` TEXT NOT NULL,
                        `targetValue` TEXT NOT NULL,
                        `unit` TEXT,
                        `recurrence` TEXT NOT NULL,
                        `weekdays` TEXT,
                        `everyNDays` INTEGER,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`templateId`) REFERENCES `game_templates`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT,
                        FOREIGN KEY(`categoryId`) REFERENCES `template_categories`(`id`)
                            ON UPDATE CASCADE ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_template_tasks_templateId` " +
                        "ON `template_tasks` (`templateId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_template_tasks_categoryId` " +
                        "ON `template_tasks` (`categoryId`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `template_seed_state` (
                        `seedKey` TEXT NOT NULL,
                        `appliedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`seedKey`)
                    )
                    """.trimIndent(),
                )

                db.execSQL("ALTER TABLE `game_accounts` ADD COLUMN `defaultTemplateId` TEXT")
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `sourceTemplateId` TEXT")
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `sourceTemplateName` TEXT")
                db.execSQL("ALTER TABLE `orders` ADD COLUMN `sourceTemplateVersion` TEXT")
            }
        }

        /** Every migration the app must run; the list is also used by tests. */
        val ALL_MIGRATIONS: Array<Migration> =
            arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
    }
}
