package com.daigan.record.data.local.di

import android.content.Context
import androidx.room.Room
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.OwnerDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideRecordDatabase(@ApplicationContext context: Context): RecordDatabase =
        Room.databaseBuilder(context, RecordDatabase::class.java, RecordDatabase.NAME)
            // No fallbackToDestructiveMigration: every schema change needs a real
            // migration that is covered by the migration test infrastructure.
            .build()

    @Provides
    fun provideOwnerDao(database: RecordDatabase): OwnerDao = database.ownerDao()

    @Provides
    fun provideGameAccountDao(database: RecordDatabase): GameAccountDao = database.gameAccountDao()
}
