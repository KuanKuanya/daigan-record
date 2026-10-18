package com.daigan.record.data.local.di

import android.content.Context
import androidx.room.Room
import com.daigan.record.data.local.RecordDatabase
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
            .addMigrations(*RecordDatabase.ALL_MIGRATIONS)
            .build()

    @Provides
    fun provideOwnerDao(database: RecordDatabase): OwnerDao = database.ownerDao()

    @Provides
    fun provideGameAccountDao(database: RecordDatabase): GameAccountDao = database.gameAccountDao()

    @Provides
    fun provideOrderDao(database: RecordDatabase): OrderDao = database.orderDao()

    @Provides
    fun provideTaskDao(database: RecordDatabase): TaskDao = database.taskDao()

    @Provides
    fun provideOccurrenceDao(database: RecordDatabase): OccurrenceDao = database.occurrenceDao()

    @Provides
    fun provideCompletionBatchDao(database: RecordDatabase): CompletionBatchDao =
        database.completionBatchDao()

    @Provides
    fun provideAttachmentDao(database: RecordDatabase): AttachmentDao = database.attachmentDao()

    @Provides
    fun provideReceiptDao(database: RecordDatabase): ReceiptDao = database.receiptDao()

    @Provides
    fun provideCreationOperationDao(database: RecordDatabase): CreationOperationDao =
        database.creationOperationDao()

    @Provides
    fun provideTemplateDao(database: RecordDatabase): TemplateDao = database.templateDao()
}
