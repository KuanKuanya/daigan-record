package com.kuankuan.daigan.data.local.di

import android.content.Context
import androidx.room.Room
import com.kuankuan.daigan.data.local.RecordDatabase
import com.kuankuan.daigan.data.local.dao.AttachmentDao
import com.kuankuan.daigan.data.local.dao.CompletionBatchDao
import com.kuankuan.daigan.data.local.dao.CreationOperationDao
import com.kuankuan.daigan.data.local.dao.GameAccountDao
import com.kuankuan.daigan.data.local.dao.OccurrenceDao
import com.kuankuan.daigan.data.local.dao.OrderDao
import com.kuankuan.daigan.data.local.dao.OwnerDao
import com.kuankuan.daigan.data.local.dao.ReceiptDao
import com.kuankuan.daigan.data.local.dao.ReminderDeliveryDao
import com.kuankuan.daigan.data.local.dao.ReminderSettingsDao
import com.kuankuan.daigan.data.local.dao.AppSettingsDao
import com.kuankuan.daigan.data.local.dao.TaskDao
import com.kuankuan.daigan.data.local.dao.TemplateDao
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

    @Provides
    fun provideReminderSettingsDao(database: RecordDatabase): ReminderSettingsDao =
        database.reminderSettingsDao()

    @Provides
    fun provideAppSettingsDao(database: RecordDatabase): AppSettingsDao =
        database.appSettingsDao()

    @Provides
    fun provideReminderDeliveryDao(database: RecordDatabase): ReminderDeliveryDao =
        database.reminderDeliveryDao()
}
