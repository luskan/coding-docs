package com.example.myapp.core.di

import com.example.myapp.core.FancyWordsRepositoryImpl
import com.example.myapp.core.WordsRepository
import com.example.myapp.core.WordsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @BasicWords
    @Binds
    abstract fun bindBasicWordsRepository(impl: WordsRepositoryImpl): WordsRepository

    @FancyWords
    @Binds
    abstract fun bindFancyWordsRepository(impl: FancyWordsRepositoryImpl): WordsRepository
}
