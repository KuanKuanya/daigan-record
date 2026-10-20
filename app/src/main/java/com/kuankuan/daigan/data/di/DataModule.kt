package com.kuankuan.daigan.data.di

import com.kuankuan.daigan.core.time.BoundaryWaiter
import com.kuankuan.daigan.core.time.RealBoundaryWaiter
import com.kuankuan.daigan.core.time.ReportingTimeZoneProvider
import com.kuankuan.daigan.core.time.SystemReportingTimeZoneProvider
import com.kuankuan.daigan.core.time.SystemTimeProvider
import com.kuankuan.daigan.core.time.TimeProvider
import com.kuankuan.daigan.data.repository.AttachmentRepository
import com.kuankuan.daigan.data.repository.DefaultAttachmentRepository
import com.kuankuan.daigan.data.repository.DefaultGameAccountRepository
import com.kuankuan.daigan.data.repository.DefaultGameTemplateRepository
import com.kuankuan.daigan.data.repository.DefaultOrderRepository
import com.kuankuan.daigan.data.repository.DefaultOwnerRepository
import com.kuankuan.daigan.data.repository.DefaultReceiptRepository
import com.kuankuan.daigan.data.repository.DefaultStatisticsRepository
import com.kuankuan.daigan.data.repository.GameAccountRepository
import com.kuankuan.daigan.data.repository.GameTemplateRepository
import com.kuankuan.daigan.data.repository.OrderRepository
import com.kuankuan.daigan.data.repository.OwnerRepository
import com.kuankuan.daigan.data.repository.ReceiptRepository
import com.kuankuan.daigan.data.repository.StatisticsRepository
import com.kuankuan.daigan.data.template.AssetBuiltInTemplateSource
import com.kuankuan.daigan.data.template.BuiltInTemplateSource
import com.kuankuan.daigan.data.template.ContentResolverTemplateFileRepository
import com.kuankuan.daigan.data.template.TemplateFileRepository
import com.kuankuan.daigan.backup.BackupDocumentStore
import com.kuankuan.daigan.backup.ContentResolverBackupDocumentStore
import com.kuankuan.daigan.notifications.AndroidReminderNotifier
import com.kuankuan.daigan.notifications.DefaultReminderSettingsRepository
import com.kuankuan.daigan.notifications.ReminderNotifier
import com.kuankuan.daigan.notifications.ReminderScheduler
import com.kuankuan.daigan.notifications.ReminderSettingsRepository
import com.kuankuan.daigan.notifications.WorkManagerReminderScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Singleton
    @Binds
    fun bindsOwnerRepository(repository: DefaultOwnerRepository): OwnerRepository

    @Singleton
    @Binds
    fun bindsGameAccountRepository(repository: DefaultGameAccountRepository): GameAccountRepository

    @Singleton
    @Binds
    fun bindsOrderRepository(repository: DefaultOrderRepository): OrderRepository

    @Singleton
    @Binds
    fun bindsAttachmentRepository(repository: DefaultAttachmentRepository): AttachmentRepository

    @Singleton
    @Binds
    fun bindsReceiptRepository(repository: DefaultReceiptRepository): ReceiptRepository

    @Singleton
    @Binds
    fun bindsStatisticsRepository(repository: DefaultStatisticsRepository): StatisticsRepository

    @Binds
    fun bindsTimeProvider(timeProvider: SystemTimeProvider): TimeProvider

    @Binds
    fun bindsReportingTimeZoneProvider(
        provider: SystemReportingTimeZoneProvider,
    ): ReportingTimeZoneProvider

    @Singleton
    @Binds
    fun bindsBoundaryWaiter(waiter: RealBoundaryWaiter): BoundaryWaiter

    @Singleton
    @Binds
    fun bindsGameTemplateRepository(
        repository: DefaultGameTemplateRepository,
    ): GameTemplateRepository

    @Singleton
    @Binds
    fun bindsTemplateFileRepository(
        repository: ContentResolverTemplateFileRepository,
    ): TemplateFileRepository

    @Singleton
    @Binds
    fun bindsBuiltInTemplateSource(source: AssetBuiltInTemplateSource): BuiltInTemplateSource

    @Singleton
    @Binds
    fun bindsReminderSettingsRepository(
        repository: DefaultReminderSettingsRepository,
    ): ReminderSettingsRepository

    @Singleton
    @Binds
    fun bindsReminderScheduler(scheduler: WorkManagerReminderScheduler): ReminderScheduler

    @Singleton
    @Binds
    fun bindsReminderNotifier(notifier: AndroidReminderNotifier): ReminderNotifier

    @Singleton
    @Binds
    fun bindsBackupDocumentStore(store: ContentResolverBackupDocumentStore): BackupDocumentStore
}
