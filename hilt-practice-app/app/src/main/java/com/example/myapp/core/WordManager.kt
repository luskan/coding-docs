package com.example.myapp.core

import com.example.myapp.core.di.FancyWords
import javax.inject.Inject

class WordManager @Inject constructor(
    @param:FancyWords private val wordsRepository: WordsRepository,
) {
    fun nextWord(): String = wordsRepository.getWords().random()
}
