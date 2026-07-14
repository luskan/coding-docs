package com.example.myapp.core.di

import com.example.myapp.core.FakeWordsRepository
import com.example.myapp.core.WordsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RepositoryModule::class],
)
object DeviceFakeRepositoryModule {

    @BasicWords
    @Singleton
    @Provides
    fun provideBasicWordsRepository(): WordsRepository =
        FakeWordsRepository(listOf("device-basic-word"))

    @FancyWords
    @Singleton
    @Provides
    fun provideFancyWordsRepository(): WordsRepository =
        FakeWordsRepository(listOf("device-fancy-word"))
}
