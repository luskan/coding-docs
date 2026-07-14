package com.example.myapp.core

import dagger.Lazy
import javax.inject.Inject

class WordDefiner @Inject constructor(
    private val dictionary: Lazy<ExpensiveDictionary>,
    private val tracker: DictionaryConstructionTracker,
) {
    val constructionCount: Int
        get() = tracker.constructionCount

    fun define(word: String): DefinitionResult {
        val resolvedDictionary = dictionary.get()
        return DefinitionResult(
            text = resolvedDictionary.lookup(word),
            dictionaryInstanceId = resolvedDictionary.instanceId,
        )
    }
}

data class DefinitionResult(
    val text: String,
    val dictionaryInstanceId: Int,
)
