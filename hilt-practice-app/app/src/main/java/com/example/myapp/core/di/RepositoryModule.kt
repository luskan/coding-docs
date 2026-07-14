package com.example.myapp.core.di

import com.example.myapp.core.WordsRepository
import com.example.myapp.core.WordsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindWordsRepository(impl: WordsRepositoryImpl): WordsRepository
}
