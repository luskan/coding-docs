package com.example.myapp.core.di

import com.example.myapp.core.WordManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WordsEntryPoint {
    fun wordManager(): WordManager
}
