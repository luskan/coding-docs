package com.example.myapp.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [CoroutinesModule::class],
)
object TestCoroutinesModule {

    @Provides
    @Singleton
    fun provideTestCoroutineScheduler(): TestCoroutineScheduler = TestCoroutineScheduler()

    @Provides
    @Singleton
    fun provideTestDispatcher(
        scheduler: TestCoroutineScheduler,
    ): TestDispatcher = StandardTestDispatcher(scheduler)

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(dispatcher: TestDispatcher): CoroutineDispatcher = dispatcher

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(dispatcher: TestDispatcher): CoroutineDispatcher = dispatcher

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(dispatcher: TestDispatcher): CoroutineDispatcher = dispatcher
}
