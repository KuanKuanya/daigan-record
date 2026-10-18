package com.daigan.record.data.di

import com.daigan.record.core.time.BoundaryWaiter
import com.daigan.record.core.time.RealBoundaryWaiter
import com.daigan.record.core.time.ReportingTimeZoneProvider
import com.daigan.record.core.time.SystemReportingTimeZoneProvider
import com.daigan.record.core.time.SystemTimeProvider
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.repository.AttachmentRepository
import com.daigan.record.data.repository.DefaultAttachmentRepository
import com.daigan.record.data.repository.DefaultGameAccountRepository
import com.daigan.record.data.repository.DefaultGameTemplateRepository
import com.daigan.record.data.repository.DefaultOrderRepository
import com.daigan.record.data.repository.DefaultOwnerRepository
import com.daigan.record.data.repository.DefaultReceiptRepository
import com.daigan.record.data.repository.DefaultStatisticsRepository
import com.daigan.record.data.repository.GameAccountRepository
import com.daigan.record.data.repository.GameTemplateRepository
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.data.repository.OwnerRepository
import com.daigan.record.data.repository.ReceiptRepository
import com.daigan.record.data.repository.StatisticsRepository
import com.daigan.record.data.template.AssetBuiltInTemplateSource
import com.daigan.record.data.template.BuiltInTemplateSource
import com.daigan.record.data.template.ContentResolverTemplateFileRepository
import com.daigan.record.data.template.TemplateFileRepository
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
}
