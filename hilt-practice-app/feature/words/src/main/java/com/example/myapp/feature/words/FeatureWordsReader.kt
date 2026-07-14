package com.example.myapp.feature.words

import com.example.myapp.core.WordManager
import com.example.myapp.core.WordsRepository
import javax.inject.Inject

class FeatureWordsReader @Inject constructor(
    private val wordManager: WordManager,
) {
    fun nextWord(): String = wordManager.nextWord()

    fun usesRepository(repository: WordsRepository): Boolean =
        wordManager.usesRepository(repository)
}
