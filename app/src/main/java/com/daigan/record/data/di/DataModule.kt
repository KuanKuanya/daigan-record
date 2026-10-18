package com.daigan.record.data.di

import com.daigan.record.core.time.BoundaryWaiter
import com.daigan.record.core.time.RealBoundaryWaiter
import com.daigan.record.core.time.SystemTimeProvider
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.repository.AttachmentRepository
import com.daigan.record.data.repository.DefaultAttachmentRepository
import com.daigan.record.data.repository.DefaultGameAccountRepository
import com.daigan.record.data.repository.DefaultOrderRepository
import com.daigan.record.data.repository.DefaultOwnerRepository
import com.daigan.record.data.repository.GameAccountRepository
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.data.repository.OwnerRepository
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

    @Binds
    fun bindsTimeProvider(timeProvider: SystemTimeProvider): TimeProvider

    @Singleton
    @Binds
    fun bindsBoundaryWaiter(waiter: RealBoundaryWaiter): BoundaryWaiter
}
