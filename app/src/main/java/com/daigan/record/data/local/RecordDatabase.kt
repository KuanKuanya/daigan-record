package com.daigan.record.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.daigan.record.data.local.converter.InstantConverters
import com.daigan.record.data.local.converter.LoginMethodConverters
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.local.entity.OwnerNumberSequenceEntity

/**
 * The single source of truth for business data.
 *
 * Version 1 contains only the owner and game-account tables that Phase 1 needs.
 * Orders, hosting periods, task occurrences, receipts and attachments are described
 * in docs/data-model.md and will be added with real migrations in later phases.
 */
@Database(
    entities = [
        OwnerEntity::class,
        GameAccountEntity::class,
        OwnerNumberSequenceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(InstantConverters::class, LoginMethodConverters::class)
abstract class RecordDatabase : RoomDatabase() {
    abstract fun ownerDao(): OwnerDao

    abstract fun gameAccountDao(): GameAccountDao

    companion object {
        const val NAME = "daigan-record.db"
    }
}
