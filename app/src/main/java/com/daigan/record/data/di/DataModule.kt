package com.daigan.record.data.di

import com.daigan.record.core.time.SystemTimeProvider
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.repository.DefaultGameAccountRepository
import com.daigan.record.data.repository.DefaultOwnerRepository
import com.daigan.record.data.repository.GameAccountRepository
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

    @Binds
    fun bindsTimeProvider(timeProvider: SystemTimeProvider): TimeProvider
}
