package com.example.myapp.core

import com.example.myapp.core.di.FancyWords
import com.example.myapp.core.di.IoDispatcher
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class AsyncWordsLoader @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:FancyWords private val repository: WordsRepository,
) {
    suspend fun load(): List<String> = withContext(ioDispatcher) {
        repository.getWords()
    }
}
