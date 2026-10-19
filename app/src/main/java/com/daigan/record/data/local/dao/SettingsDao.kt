package com.daigan.record.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.daigan.record.data.local.entity.AppSettingsEntity
import com.daigan.record.data.local.entity.ReminderDeliveryEntity
import com.daigan.record.data.local.entity.ReminderSettingsEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderSettingsDao {

    @Query("SELECT * FROM reminder_settings WHERE id = 0")
    suspend fun find(): ReminderSettingsEntity?

    @Query("SELECT * FROM reminder_settings WHERE id = 0")
    fun observe(): Flow<ReminderSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReminderSettingsEntity)

    @Query("DELETE FROM reminder_settings")
    suspend fun deleteAll()
}

@Dao
interface AppSettingsDao {

    @Query("SELECT * FROM app_settings WHERE id = 0")
    suspend fun find(): AppSettingsEntity?

    @Query("SELECT datasetInstanceId FROM app_settings WHERE id = 0")
    suspend fun datasetInstanceId(): String?

    @Query("SELECT datasetInstanceId FROM app_settings WHERE id = 0")
    fun observeDatasetInstanceId(): Flow<String?>

    @Query("SELECT lastRestoreId FROM app_settings WHERE id = 0")
    suspend fun lastRestoreId(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppSettingsEntity)

    /** Used only by the restore commit transaction. */
    @Query("UPDATE app_settings SET lastRestoreId = :restoreId WHERE id = 0")
    suspend fun setLastRestoreId(restoreId: String?)

    /** Used by the isolated staging database, never by the live dataset. */
    @Query("DELETE FROM app_settings")
    suspend fun deleteAll()
}

@Dao
interface ReminderDeliveryDao {

    @Query(
        "SELECT COUNT(*) FROM reminder_deliveries WHERE category = :category AND day = :day",
    )
    suspend fun count(category: String, day: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ReminderDeliveryEntity)

    @Query("DELETE FROM reminder_deliveries WHERE day < :day")
    suspend fun deleteOlderThan(day: String)

    @Query("DELETE FROM reminder_deliveries")
    suspend fun deleteAll()

    @Query("SELECT deliveredAt FROM reminder_deliveries WHERE category = :category AND day = :day")
    suspend fun deliveredAt(category: String, day: String): Instant?

    @Query("SELECT * FROM reminder_deliveries")
    suspend fun findAll(): List<ReminderDeliveryEntity>
}
