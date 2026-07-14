package com.example.myapp.core

import com.example.myapp.core.di.FancyWords
import javax.inject.Inject

class WordManager @Inject constructor(
    @param:FancyWords private val wordsRepository: WordsRepository,
) {
    fun nextWord(excluding: String? = null): String {
        val words = wordsRepository.getWords()
        return words.filterNot { it == excluding }.ifEmpty { words }.random()
    }

    fun usesRepository(repository: WordsRepository): Boolean = wordsRepository === repository
}
