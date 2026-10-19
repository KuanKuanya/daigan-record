package com.daigan.record.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction

/** Acquire the dataset permit before Room's transaction lock, never the other way round. */
suspend fun <T> RoomDatabase.withDatasetTransaction(block: suspend () -> T): T =
    DatasetGate.withWrite { withTransaction { block() } }
