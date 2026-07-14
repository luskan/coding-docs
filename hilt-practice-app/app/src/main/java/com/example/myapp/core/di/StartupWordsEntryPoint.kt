package com.example.myapp.core.di

import com.example.myapp.core.WordManager
import dagger.hilt.InstallIn
import dagger.hilt.android.EarlyEntryPoint
import dagger.hilt.components.SingletonComponent

@EarlyEntryPoint
@InstallIn(SingletonComponent::class)
interface StartupWordsEntryPoint {
    fun wordManager(): WordManager
}
