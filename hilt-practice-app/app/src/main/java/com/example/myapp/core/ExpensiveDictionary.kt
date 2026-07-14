package com.example.myapp.core

import javax.inject.Inject

class ExpensiveDictionary @Inject constructor(
    tracker: DictionaryConstructionTracker,
) {
    val instanceId: Int = tracker.recordConstruction()

    fun lookup(word: String): String = "definition of $word"
}
