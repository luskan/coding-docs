package com.example.myapp.core

import javax.inject.Inject

class WordManager @Inject constructor(
    private val wordsRepository: WordsRepository,
) {
    fun nextWord(): String = wordsRepository.getWords().random()
}
