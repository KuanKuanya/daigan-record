package com.daigan.record.testdi

import android.content.Context
import androidx.room.Room
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.AppSettingsDao
import com.daigan.record.data.local.dao.ReminderSettingsDao
import com.daigan.record.data.local.dao.ReminderDeliveryDao
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
    @Provides fun provideAppSettingsDao(database: RecordDatabase): AppSettingsDao = database.appSettingsDao()
    @Provides fun provideReminderSettingsDao(database: RecordDatabase): ReminderSettingsDao = database.reminderSettingsDao()
    @Provides fun provideReminderDeliveryDao(database: RecordDatabase): ReminderDeliveryDao = database.reminderDeliveryDao()

}
