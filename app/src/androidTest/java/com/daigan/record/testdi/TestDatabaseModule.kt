package com.daigan.record.testdi

import android.content.Context
import androidx.room.Room
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.GameAccountDao
import com.daigan.record.data.local.dao.OwnerDao
import com.daigan.record.data.local.di.DatabaseModule
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * UI tests use an in-memory database so they never touch the real app database.
 * Database invariant tests build their own Room instance instead.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DatabaseModule::class],
)
object TestDatabaseModule {

    @Provides
    @Singleton
    fun provideInMemoryDatabase(@ApplicationContext context: Context): RecordDatabase =
        Room.inMemoryDatabaseBuilder(context, RecordDatabase::class.java).build()

    @Provides
    fun provideOwnerDao(database: RecordDatabase): OwnerDao = database.ownerDao()

    @Provides
    fun provideGameAccountDao(database: RecordDatabase): GameAccountDao = database.gameAccountDao()
}
