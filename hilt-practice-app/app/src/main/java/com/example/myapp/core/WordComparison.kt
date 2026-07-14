package com.example.myapp.core

import com.example.myapp.core.di.BasicWords
import com.example.myapp.core.di.FancyWords
import javax.inject.Inject

class WordComparison @Inject constructor(
    @param:BasicWords private val basicRepository: WordsRepository,
    @param:FancyWords private val fancyRepository: WordsRepository,
) {
    fun nextSample(): WordSample = WordSample(
        basic = basicRepository.getWords().random(),
        fancy = fancyRepository.getWords().random(),
    )
}

data class WordSample(
    val basic: String,
    val fancy: String,
)
