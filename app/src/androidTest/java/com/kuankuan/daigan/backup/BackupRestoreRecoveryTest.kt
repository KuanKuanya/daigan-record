package com.kuankuan.daigan.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuankuan.daigan.core.time.ReportingTimeZoneProvider
import com.kuankuan.daigan.core.time.TimeProvider
import com.kuankuan.daigan.data.local.RecordDatabase
import com.kuankuan.daigan.data.local.entity.OwnerEntity
import com.kuankuan.daigan.data.local.entity.*
import com.kuankuan.daigan.data.model.*
import java.math.BigDecimal
import java.time.LocalDate
import com.kuankuan.daigan.data.repository.AttachmentSync
import com.kuankuan.daigan.data.repository.DatasetGate
import com.kuankuan.daigan.data.repository.ApplicationReadiness
import com.kuankuan.daigan.notifications.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room, private files, real ZIP/crypto and real journal; only document/scheduling I/O is fake. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreRecoveryTest {
    private val checkpoints = listOf("PREPARED", "ROLLBACK_READY", "ORIGINAL_FILES_MOVED",
        "FILES_SWAPPED", "DATABASE_COMMITTED", "DB_COMMITTED")

    private class PrivateContext(base: Context, val directory: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(directory, "databases/$name").apply {
            parentFile!!.mkdirs()
        }
        override fun deleteDatabase(name: String): Boolean = getDatabasePath(name).delete()
    }

    private class Fixture(val context: PrivateContext) : AutoCloseable {
        val clock = TimeProvider { Instant.ofEpochMilli(1000) }
        val db = Room.databaseBuilder(context, RecordDatabase::class.java, "live.db").build()
        val storage = BackupStorage(context, clock)
        val access = BackupDatabaseAccess(context, db, clock)
        val builder = BackupSnapshotBuilder(storage, access)
        val sync = AttachmentSync()
        val document = object : BackupDocumentStore {
            override suspend fun copyDocumentToFile(uri: Uri, target: File, maxBytes: Long): Long {
                val file = File(checkNotNull(uri.path))
                check(file.length() <= maxBytes)
                file.copyTo(target, overwrite = true)
                return file.length()
            }
            override suspend fun writeFileToDocument(uri: Uri, source: File) {
                source.copyTo(File(checkNotNull(uri.path)), overwrite = true)
            }
            override fun hasRoomFor(bytes: Long) = true
        }
        var cancelledSchedules = 0
        val scheduledSettings = mutableListOf<ReminderSettings>()
        var failScheduling = false
        private val scheduler = object : ReminderScheduler {
            override suspend fun scheduleAll(settings: ReminderSettings, now: Instant) {
                if (failScheduling) throw java.io.IOException("Injected scheduler failure")
                scheduledSettings += settings
            }
            override suspend fun scheduleCategory(category: ReminderCategory, minutes: Int, now: Instant) = Unit
            override suspend fun cancel(category: ReminderCategory) = Unit
            override suspend fun cancelAll() { cancelledSchedules++ }
        }
        private val notifier = object : ReminderNotifier {
            override fun canNotify() = false
            override fun post(message: ReminderMessage) = false
            override fun notificationSettingsIntent() = Intent()
        }
        val coordinator = ReminderCoordinator(DefaultReminderSettingsRepository(db.reminderSettingsDao(), clock),
            ReminderRepository(db, db.orderDao(), db.gameAccountDao(), db.ownerDao()), scheduler,
            notifier, clock, ReportingTimeZoneProvider { ZoneId.of("Asia/Shanghai") })
        fun service() = BackupRestoreService(storage, access, builder, BackupValidator(), sync,
            document, clock, coordinator)

        suspend fun seed(name: String) {
            access.deleteAll(db.openHelper.writableDatabase)
            db.ownerDao().insert(OwnerEntity(name, 1, name, clock.now(), clock.now(), null))
            db.openHelper.writableDatabase.execSQL("INSERT INTO owner_number_sequence VALUES (0, 2)")
            db.reminderSettingsDao().upsert(ReminderSettingsEntity(0, name == "new", 123,
                false, 456, false, 789, clock.now()))
            if (name == "new") {
                db.gameAccountDao().insert(GameAccountEntity("account", name, "游戏", "官服", "uid",
                    "login", LoginMethod.PASSWORD, "private-secret", clock.now(), clock.now(), null))
                db.orderDao().insert(OrderEntity("order", "account", OrderType.SHORT_TASK, "截图任务", 1000,
                    PaymentMode.INSTALLMENTS, LocalDate.of(2026, 10, 20), null, "Asia/Shanghai", 240,
                    1, 240, clock.now(), clock.now(), null))
                db.taskDao().insert(TaskDefinitionEntity("task", "order", null, "截图", ProgressType.CHECKBOX,
                    BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, null, RecurrenceType.ONE_TIME,
                    null, null, 0, null, null, clock.now(), clock.now()))
                storage.attachmentsDirectory.mkdirs()
                val image = File(storage.attachmentsDirectory, "new-image.jpg")
                image.writeBytes(byteArrayOf(1, 3, 5, 7, 9))
                db.attachmentDao().insert(AttachmentEntity("image", "task", null, image.name,
                    "image/jpeg", image.length(), clock.now()))
            }
        }

        suspend fun richHistory() {
            val day = LocalDate.of(2026, 10, 1)
            val now = clock.now()
            db.templateDao().insertTemplate(GameTemplateEntity("template", "自定义游戏", "v2", "已更新",
                "Asia/Shanghai", 300, 1, 300, TemplateOrigin.USER, null, 0, now, now))
            db.templateDao().insertCategories(listOf(TemplateCategoryEntity("category", "template", "日常", 0)))
            db.templateDao().insertTasks(listOf(TemplateTaskEntity("template-task", "template", "category", "新版任务",
                ProgressType.CHECKBOX, BigDecimal.ZERO, BigDecimal.ONE, null, RecurrenceType.DAILY, null, null, 0)))
            db.templateDao().insertSeedState(TemplateSeedStateEntity("deleted-built-in", now))
            db.orderDao().insert(OrderEntity("hosting", "account", OrderType.HOSTING, "历史托管", null,
                PaymentMode.INSTALLMENTS, null, "保留旧版快照", "Asia/Shanghai", 240, 1, 240,
                now, now, null, "template", "旧版名称", "v1"))
            db.orderDao().insertPeriod(HostingPeriodEntity("period-old", "hosting", 1, day, day.plusDays(6),
                1000, "Asia/Shanghai", 240, 1, 240, now, null))
            db.orderDao().insertPeriod(HostingPeriodEntity("period-new", "hosting", 2, day.plusDays(7), day.plusDays(13),
                2000, "Asia/Shanghai", 240, 1, 240, now, "renew-once"))
            db.taskDao().insert(TaskDefinitionEntity("hosting-task-old", "hosting", "period-old", "旧任务快照",
                ProgressType.CHECKBOX, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, null,
                RecurrenceType.DAILY, null, null, 0, null, null, now, now))
            db.taskDao().insert(TaskDefinitionEntity("hosting-task-new", "hosting", "period-new", "续期任务快照",
                ProgressType.NUMERIC, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO, "次",
                RecurrenceType.EVERY_N_DAYS, null, 2, 0, null, null, now, now))
            db.completionBatchDao().insertBatch(CompletionBatchEntity("batch", "batch-operation", BatchScope.HOSTING_ORDER,
                "hosting", day, now, now.plusSeconds(5)))
            db.occurrenceDao().insertIgnore(TaskOccurrenceEntity("occurrence", "hosting-task-old", "D:$day", day,
                day, day, now, now.plusSeconds(60), true, null, null, now))
            db.completionBatchDao().insertItems(listOf(CompletionBatchItemEntity("batch-item", "batch",
                BatchItemType.OCCURRENCE, "occurrence", null, null, null, false, null, null,
                now, now, false, null, "batch")))
            db.receiptDao().insert(ReceiptEntity("short-receipt", "short-payment", "order", null, 123, now,
                PaymentChannel.WECHAT, null, ReceiptStatus.ACTIVE, null, now))
            db.receiptDao().insert(ReceiptEntity("old-receipt", "old-payment", "hosting", "period-old", 250, now,
                PaymentChannel.ALIPAY, "被更正", ReceiptStatus.REPLACED, "corrected-receipt", now))
            db.receiptDao().insert(ReceiptEntity("corrected-receipt", "correct-payment", "hosting", "period-old", 300,
                now.plusSeconds(1), PaymentChannel.OTHER, "历史欠款分期", ReceiptStatus.ACTIVE, null, now))
            db.receiptDao().insert(ReceiptEntity("period-payment", "new-period-payment", "hosting", "period-new", 500,
                now.plusSeconds(2), PaymentChannel.WECHAT, "本期分期", ReceiptStatus.ACTIVE, null, now))
            db.receiptDao().insertCorrection(ReceiptCorrectionEntity("correction", "correction-operation", "old-receipt",
                ReceiptCorrectionKind.AMEND, "修正金额", 250, now, PaymentChannel.ALIPAY, "被更正",
                "corrected-receipt", 300, now.plusSeconds(1), PaymentChannel.OTHER, "历史欠款分期", now))
            db.creationOperationDao().insert(OrderCreationOperationEntity("create-order", OrderType.SHORT_TASK,
                "persisted-fingerprint", "order", "short-receipt", now))
        }

        suspend fun prepare(service: BackupRestoreService) {
            seed("new")
            val export = storage.newWorkDirectory("fixture-export")
            val snapshot = builder.build(export, clock.now(), null)
            val archive = File(context.directory, "incoming.dgbk")
            snapshot.file.copyTo(archive, overwrite = true)
            seed("original")
            storage.attachmentsDirectory.deleteRecursively()
            storage.attachmentsDirectory.mkdirs()
            File(storage.attachmentsDirectory, "original-proof.jpg").writeText("original")
            service.inspect(Uri.fromFile(archive))
            val summary = service.prepare(null)
            assertEquals(1, summary.owners)
        }

        suspend fun assertDataset(expected: String) {
            assertEquals(listOf(expected), db.ownerDao().findAll().map { it.id })
            assertEquals(expected == "original", File(storage.attachmentsDirectory, "original-proof.jpg").exists())
            assertEquals(expected == "new", db.reminderSettingsDao().find()!!.shortDeadlineEnabled)
            if (expected == "new") {
                assertEquals("private-secret", db.gameAccountDao().findAll().single().password)
                assertEquals("image", db.orderDao().findAllAttachments().single().id)
                assertArrayEquals(byteArrayOf(1, 3, 5, 7, 9),
                    File(storage.attachmentsDirectory, "new-image.jpg").readBytes())
            }
        }
        override fun close() = db.close()
    }

    private fun context(name: String) = PrivateContext(
        InstrumentationRegistry.getInstrumentation().targetContext,
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "backup-tests/$name"),
    )

    @Test fun catalogCoversEveryProductionTableAndColumn() = runBlocking<Unit> {
        val context = context("catalog")
        context.directory.deleteRecursively()
        try {
            Fixture(context).use { fixture ->
                val raw = fixture.db.openHelper.writableDatabase
                val actual = mutableSetOf<String>()
                raw.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        if (!name.startsWith("sqlite_") && name !in setOf("room_master_table", "android_metadata")) actual += name
                    }
                }
                assertEquals(BackupTableCatalog.tables.map { it.name }.toSet(), actual)
                BackupTableCatalog.tables.forEach { table ->
                    val columns = mutableListOf<String>()
                    raw.query("PRAGMA table_info(`${table.name}`)").use { cursor ->
                        while (cursor.moveToNext()) columns += cursor.getString(1)
                    }
                    assertEquals(table.name, table.columnNames, columns)
                }
            }
        } finally { context.directory.deleteRecursively() }
    }

    @Test fun plainAndEncryptedArchivesRestoreCredentialsScreenshotsAndSettingsIntoFreshDatabase() = runBlocking<Unit> {
        listOf(false, true).forEach { encrypted ->
            val sourceContext = context("roundtrip-source-$encrypted")
            val targetContext = context("roundtrip-target-$encrypted")
            sourceContext.directory.deleteRecursively(); targetContext.directory.deleteRecursively()
            try {
                val archive = File(sourceContext.directory, "export.dgbk")
                var expected: Map<String, List<String>> = emptyMap()
                Fixture(sourceContext).use { source ->
                    source.seed("new")
                    source.richHistory()
                    expected = source.access.dump().tables.associate { it.table.name to it.rows.map { row -> row.toString() }.sorted() }
                    assertTrue(expected.values.all { it.isNotEmpty() })
                    val snapshot = source.builder.build(source.storage.newWorkDirectory("export"),
                        source.clock.now(), if (encrypted) "恢复 password".toCharArray() else null)
                    snapshot.file.copyTo(archive, overwrite = true)
                }
                Fixture(targetContext).use { target ->
                    val service = target.service()
                    assertEquals(encrypted, service.inspect(Uri.fromFile(archive)).encrypted)
                    val preview = service.prepare(if (encrypted) "恢复 password".toCharArray() else null)
                    assertEquals(1, preview.attachments)
                    assertEquals(1, preview.remindersEnabled)
                    service.applyConfirmed()
                    target.assertDataset("new")
                    assertEquals(expected, target.access.dump().tables.associate {
                        it.table.name to it.rows.map { row -> row.toString() }.sorted()
                    })
                    assertEquals(2, target.db.orderDao().findAllPeriods().size)
                    assertEquals("v1", target.db.orderDao().findAllOrders().first { it.id == "hosting" }.sourceTemplateVersion)
                }
                Fixture(targetContext).use { target -> target.service().recoverIfNeeded(); target.assertDataset("new") }
            } finally { sourceContext.directory.deleteRecursively(); targetContext.directory.deleteRecursively() }
        }
    }

    @Test fun everyRestoreFailureLeavesOneCompleteDatasetAndRecoveryIsIdempotent() = runBlocking<Unit> {
        checkpoints.forEach { phase ->
            val context = context("failure-$phase")
            context.directory.deleteRecursively()
            try {
                Fixture(context).use { fixture ->
                    val service = fixture.service()
                    fixture.prepare(service)
                    service.transitionHook = { if (it == phase) throw java.io.IOException("injected") }
                    try { service.applyConfirmed(); fail("Failure hook not reached at $phase") }
                    catch (_: java.io.IOException) { }
                    val expected = if (phase in listOf("DATABASE_COMMITTED", "DB_COMMITTED")) "new" else "original"
                    fixture.assertDataset(expected)
                }
                Fixture(context).use { reopened ->
                    reopened.service().recoverIfNeeded()
                    reopened.service().recoverIfNeeded()
                    reopened.assertDataset(if (phase in listOf("DATABASE_COMMITTED", "DB_COMMITTED")) "new" else "original")
                }
            } finally { context.directory.deleteRecursively() }
        }
    }

    @Test fun cancellationAtDatabaseCommitStillRebuildsRemindersFromRestoredPreferences() = runBlocking<Unit> {
        val context = context("cancel-after-commit")
        context.directory.deleteRecursively()
        try {
            Fixture(context).use { fixture ->
                val service = fixture.service()
                fixture.prepare(service)
                val restore = launch(start = CoroutineStart.LAZY) { service.applyConfirmed() }
                service.transitionHook = { if (it == "DATABASE_COMMITTED") restore.cancel() }
                restore.start()
                restore.join()
                assertTrue(restore.isCancelled)
                fixture.assertDataset("new")
                assertEquals(1, fixture.cancelledSchedules)
                assertEquals(1, fixture.scheduledSettings.size)
                assertTrue(fixture.scheduledSettings.single().shortDeadlineEnabled)
                assertEquals(123, fixture.scheduledSettings.single().shortDeadlineMinutes)
                assertFalse(fixture.storage.hasJournalFile())
                assertNull(fixture.access.currentRestoreMarker())
            }
        } finally { context.directory.deleteRecursively() }
    }

    @Test fun failedReminderReconciliationRetainsCommitEvidenceUntilSuccessfulRecovery() = runBlocking<Unit> {
        val context = context("schedule-retry")
        context.directory.deleteRecursively()
        try {
            Fixture(context).use { fixture ->
                val service = fixture.service()
                fixture.prepare(service)
                fixture.failScheduling = true
                try { service.applyConfirmed(); fail("Expected scheduler failure") }
                catch (_: BackupException) { }
                fixture.assertDataset("new")
                assertTrue(fixture.storage.hasJournalFile())
                assertNotNull(fixture.access.currentRestoreMarker())
                // Startup recovery also must retain the marker while scheduling fails.
                try { fixture.service().recoverIfNeeded(); fail("Expected recovery scheduler failure") }
                catch (_: java.io.IOException) { }
                assertTrue(fixture.storage.hasJournalFile())
                assertNotNull(fixture.access.currentRestoreMarker())
            }
            Fixture(context).use { reopened ->
                assertEquals(RecoveryOutcome.COMPLETED_RESTORE, reopened.service().recoverIfNeeded())
                reopened.assertDataset("new")
                assertEquals(1, reopened.cancelledSchedules)
                assertTrue(reopened.scheduledSettings.single().shortDeadlineEnabled)
                assertEquals(123, reopened.scheduledSettings.single().shortDeadlineMinutes)
                assertFalse(reopened.storage.hasJournalFile())
                assertNull(reopened.access.currentRestoreMarker())
            }
        } finally {
            context.directory.deleteRecursively()
            DatasetGate.recoveryFinished()
            ApplicationReadiness.ready()
        }
    }

    @Test fun invalidCounterAndCrossOrderReceiptLinksFailBeforeReplacement() = runBlocking<Unit> {
        listOf("counter", "replacement", "correction", "replacement-period", "correction-period").forEach { invalid ->
            val sourceContext = context("invalid-source-$invalid")
            val targetContext = context("invalid-target-$invalid")
            sourceContext.directory.deleteRecursively(); targetContext.directory.deleteRecursively()
            try {
                val archive = File(sourceContext.directory, "invalid.dgbk")
                Fixture(sourceContext).use { source ->
                    if (invalid == "counter") {
                        source.db.openHelper.writableDatabase.execSQL("INSERT INTO owner_number_sequence VALUES (0, -1)")
                    } else {
                        source.seed("new"); source.richHistory()
                        val targetId = if (invalid.endsWith("-period")) "period-payment" else "short-receipt"
                        source.db.openHelper.writableDatabase.execSQL(if (invalid.startsWith("replacement"))
                            "UPDATE receipts SET replacedByReceiptId='$targetId' WHERE id='old-receipt'"
                            else "UPDATE receipt_corrections SET newReceiptId='$targetId' WHERE id='correction'")
                    }
                    source.builder.build(source.storage.newWorkDirectory("invalid"), source.clock.now(), null)
                        .file.copyTo(archive, overwrite = true)
                }
                Fixture(targetContext).use { target ->
                    target.seed("original")
                    target.storage.attachmentsDirectory.mkdirs()
                    File(target.storage.attachmentsDirectory, "original-proof.jpg").writeText("original")
                    val service = target.service()
                    service.inspect(Uri.fromFile(archive))
                    try { service.prepare(null); fail("Invalid $invalid imported") }
                    catch (_: BackupException) { }
                    target.assertDataset("original")
                    assertFalse(target.storage.hasJournalFile())
                }
            } finally { sourceContext.directory.deleteRecursively(); targetContext.directory.deleteRecursively() }
        }
    }

    @Test fun emptyOriginalAttachmentDirectoryIsRestoredOnSwapFailure() = runBlocking<Unit> {
        val context = context("empty-original")
        context.directory.deleteRecursively()
        try {
            Fixture(context).use { fixture ->
                val service = fixture.service()
                fixture.prepare(service)
                fixture.storage.attachmentsDirectory.deleteRecursively()
                service.transitionHook = { if (it == "FILES_SWAPPED") throw java.io.IOException("injected") }
                try { service.applyConfirmed(); fail("Expected failure") } catch (_: java.io.IOException) { }
                assertEquals("original", fixture.db.ownerDao().findAll().single().id)
                assertTrue(fixture.storage.attachmentsDirectory.isDirectory)
                assertFalse(fixture.storage.hasJournalFile())
            }
        } finally { context.directory.deleteRecursively() }
    }

    @Test fun corruptJournalBlocksAccessAndPreservesEvidence() = runBlocking<Unit> {
        val context = context("corrupt")
        context.directory.deleteRecursively()
        try {
            Fixture(context).use { fixture ->
                fixture.seed("original")
                val journal = File(context.filesDir, "backup/restore-journal.json")
                journal.parentFile!!.mkdirs(); journal.writeText("broken")
                try { fixture.service().recoverIfNeeded(); fail("Must fail closed") }
                catch (_: BackupException) { }
                assertEquals("broken", journal.readText())
                assertEquals("original", fixture.db.ownerDao().findAll().single().id)
            }
        } finally {
            context.directory.deleteRecursively()
            DatasetGate.recoveryFinished()
            ApplicationReadiness.ready()
        }
    }

    /** Run separately with -e crashAt CHECKPOINT. This deliberately kills instrumentation. */
    @Test fun processDeathPrepare() = runBlocking<Unit> {
        val phase = InstrumentationRegistry.getArguments().getString("crashAt") ?: run {
            org.junit.Assume.assumeTrue("Dedicated process-kill probe requires crashAt argument", false)
            return@runBlocking
        }
        require(phase in checkpoints)
        val context = context("process-death")
        context.directory.deleteRecursively()
        Fixture(context).use { fixture ->
            val service = fixture.service()
            fixture.prepare(service)
            File(context.directory, "expected").writeText(
                if (phase in listOf("DATABASE_COMMITTED", "DB_COMMITTED")) "new" else "original")
            service.transitionHook = { if (it == phase) android.os.Process.killProcess(android.os.Process.myPid()) }
            service.applyConfirmed()
            fail("Process kill checkpoint not reached")
        }
    }

    /** Run after processDeathPrepare in a fresh instrumentation process. */
    @Test fun processDeathRecover() = runBlocking<Unit> {
        val context = context("process-death")
        val expectation = File(context.directory, "expected")
        org.junit.Assume.assumeTrue("Run processDeathPrepare in a preceding process first", expectation.isFile)
        val expected = expectation.readText()
        Fixture(context).use { fixture ->
            fixture.service().recoverIfNeeded()
            fixture.service().recoverIfNeeded()
            fixture.assertDataset(expected)
            assertFalse(fixture.storage.hasJournalFile())
        }
        context.directory.deleteRecursively()
    }
}
