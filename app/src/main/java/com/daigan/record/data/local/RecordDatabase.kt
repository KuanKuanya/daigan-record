package com.daigan.record.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.daigan.record.data.local.converter.InstantConverters
import com.daigan.record.data.local.converter.LoginMethodConverters
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.entity.GameAccountEntity
import com.daigan.record.data.local.entity.OwnerEntity
import com.daigan.record.data.local.entity.OwnerNumberSequenceEntity

/**
 * Current schema version, defined next to the migrations so tests do not hard code it.
 *
 * 1 -> 2 added the archive batch provenance columns.
 */
const val RECORD_DATABASE_VERSION = 2

/**
 * The single source of truth for business data.
 *
 * Version 1 contains only the owner and game-account tables that Phase 1 needs.
 * Version 2 adds the explicit archive batch provenance (`archiveBatchId`) so an
 * owner archive never has to be inferred from timestamp equality.
 *
 * Orders, hosting periods, task occurrences, receipts and attachments are described
 * in docs/data-model.md and will be added with real migrations in later phases.
 */
@Database(
    entities = [
        OwnerEntity::class,
        GameAccountEntity::class,
        OwnerNumberSequenceEntity::class,
    ],
    version = RECORD_DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(InstantConverters::class, LoginMethodConverters::class)
abstract class RecordDatabase : RoomDatabase() {
    abstract fun ownerDao(): OwnerDao

    abstract fun gameAccountDao(): GameAccountDao

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

        /** Every migration the app must run; the list is also used by tests. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
