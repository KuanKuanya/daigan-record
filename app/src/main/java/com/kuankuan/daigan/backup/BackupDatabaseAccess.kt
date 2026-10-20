package com.kuankuan.daigan.backup

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kuankuan.daigan.core.time.TimeProvider
import com.kuankuan.daigan.data.local.RecordDatabase
import com.kuankuan.daigan.data.local.entity.AppSettingsEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Raw, catalog-driven access to the durable tables.
 *
 * The backup path deliberately does not rely on hand-written per-entity mappers: the
 * catalog is the single list of durable columns, so a newly added table cannot silently
 * be left out of an export. Generic inserts are only ever executed after the whole
 * payload has been validated in an isolated staging database.
 */
@Singleton
class BackupDatabaseAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: RecordDatabase,
    private val timeProvider: TimeProvider,
) {

    /** One coherent read of every durable row, inside a single database transaction. */
    suspend fun dump(): BackupDataSet = database.withTransaction {
        dumpRaw(database.openHelper.writableDatabase)
    }

    fun dumpRaw(db: SupportSQLiteDatabase): BackupDataSet {
        val tables = BackupTableCatalog.includedTables.map { table ->
            val columns = table.columnNames.joinToString(", ") { "`$it`" }
            val rows = ArrayList<List<JsonElement?>>()
            db.query("SELECT $columns FROM `${table.name}`").use { cursor ->
                while (cursor.moveToNext()) {
                    val row = ArrayList<JsonElement?>(table.columns.size)
                    table.columns.forEachIndexed { index, column ->
                        row += when (cursor.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> {
                                if (column.type != BackupTableCatalog.ColumnType.INTEGER) {
                                    throw BackupException(
                                        "数据表「${table.name}」的「${column.name}」类型与预期不一致，" +
                                            "已停止导出。",
                                    )
                                }
                                JsonPrimitive(cursor.getLong(index))
                            }

                            android.database.Cursor.FIELD_TYPE_STRING -> {
                                if (column.type != BackupTableCatalog.ColumnType.TEXT) {
                                    throw BackupException(
                                        "数据表「${table.name}」的「${column.name}」类型与预期不一致，" +
                                            "已停止导出。",
                                    )
                                }
                                JsonPrimitive(cursor.getString(index))
                            }

                            else -> throw BackupException(
                                "数据表「${table.name}」包含无法备份的二进制或浮点值，已停止导出。",
                            )
                        }
                    }
                    rows += row
                }
            }
            BackupDataSet.TableRows(table, rows)
        }
        return BackupDataSet(tables)
    }

    fun deleteAll(db: SupportSQLiteDatabase) {
        BackupTableCatalog.deletionOrder.forEach { name ->
            db.execSQL("DELETE FROM `$name`")
        }
    }

    fun insertAll(db: SupportSQLiteDatabase, dataSet: BackupDataSet) {
        dataSet.tables.forEach { tableRows ->
            val table = tableRows.table
            if (tableRows.rows.isEmpty()) return@forEach
            val columns = table.columnNames.joinToString(", ") { "`$it`" }
            val placeholders = table.columns.joinToString(", ") { "?" }
            val statement = db.compileStatement(
                "INSERT INTO `${table.name}` ($columns) VALUES ($placeholders)",
            )
            try {
                tableRows.rows.forEach { row ->
                    statement.clearBindings()
                    row.forEachIndexed { index, value ->
                        val bindIndex = index + 1
                        when {
                            value == null -> statement.bindNull(bindIndex)
                            value is JsonPrimitive && value.isString ->
                                statement.bindString(bindIndex, value.content)

                            else -> statement.bindLong(
                                bindIndex,
                                (value as JsonPrimitive).content.toLongOrNull()
                                    ?: throw BackupException("备份包含无法写入的数值，无法恢复。"),
                            )
                        }
                    }
                    statement.executeInsert()
                }
            } finally {
                statement.close()
            }
        }
    }

    /**
     * The destructive step. One Room transaction deletes every durable row, inserts the
     * validated dataset, and writes the new dataset identity together with the restore
     * marker. `lastRestoreId` is the durable commit point: its presence proves on the
     * next start that the database replacement committed, regardless of which journal
     * phase was persisted last.
     *
     * If this transaction fails for any reason, SQLite rolls it back and the original
     * dataset remains intact.
     */
    suspend fun replaceAll(
        dataSet: BackupDataSet,
        restoreId: String,
        newDatasetInstanceId: String = UUID.randomUUID().toString(),
    ) = database.withTransaction {
        val raw = database.openHelper.writableDatabase
        deleteAll(raw)
        insertAll(raw, dataSet)
        database.appSettingsDao().upsert(
            AppSettingsEntity(
                id = AppSettingsEntity.SINGLETON_ID,
                datasetInstanceId = newDatasetInstanceId,
                lastRestoreId = restoreId,
                createdAt = timeProvider.now(),
            ),
        )
        database.reminderDeliveryDao().deleteAll()
        Unit
    }

    /**
     * Opens an isolated staging database with the production schema. It never touches
     * the live file; validation runs against this copy and the file is deleted afterwards.
     */
    fun openStagingDatabase(stagingName: String): RecordDatabase = Room.databaseBuilder(
        context,
        RecordDatabase::class.java,
        stagingName,
    ).build()

    /** The files Room creates for a staging database name (main, WAL and SHM). */
    fun stagingFiles(stagingName: String): List<File> {
        val base = context.getDatabasePath(stagingName)
        return listOf(base, File("${base.path}-wal"), File("${base.path}-shm"))
    }

    /** Ensures a local-only identity exists, creating one on first use. */
    suspend fun ensureDatasetIdentity(): String {
        val existing = database.appSettingsDao().datasetInstanceId()
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        database.withTransaction {
            if (database.appSettingsDao().datasetInstanceId() == null) {
                database.appSettingsDao().upsert(
                    AppSettingsEntity(
                        id = AppSettingsEntity.SINGLETON_ID,
                        datasetInstanceId = created,
                        lastRestoreId = null,
                        createdAt = timeProvider.now(),
                    ),
                )
            }
        }
        return database.appSettingsDao().datasetInstanceId() ?: created
    }

    suspend fun currentRestoreMarker(): String? = withContext(Dispatchers.IO) {
        database.appSettingsDao().lastRestoreId()
    }

    suspend fun clearRestoreMarker() {
        database.appSettingsDao().setLastRestoreId(null)
    }

    suspend fun now(): Instant = timeProvider.now()
}
