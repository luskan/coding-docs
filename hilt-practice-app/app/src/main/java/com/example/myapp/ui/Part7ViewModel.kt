package com.example.myapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.myapp.WordsProviderClient
import com.example.myapp.core.RoundManager
import com.example.myapp.core.WordDefiner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class Part7ViewModel @Inject constructor(
    private val providerClient: WordsProviderClient,
    private val wordDefiner: WordDefiner,
    private val roundManager: RoundManager,
) : ViewModel() {

    var providerWord: String? by mutableStateOf(null)
        private set

    var definitions: Int by mutableIntStateOf(0)
        private set

    var definitionText: String? by mutableStateOf(null)
        private set

    var dictionaryInstanceId: Int? by mutableStateOf(null)
        private set

    val dictionaryConstructionCount: Int
        get() = wordDefiner.constructionCount

    var roundIds: Pair<Int, Int>? by mutableStateOf(null)
        private set

    var freshRoundInstances: Boolean? by mutableStateOf(null)
        private set

    fun queryContentProvider() {
        providerWord = providerClient.nextWord()
    }

    fun define(word: String) {
        val result = wordDefiner.define(word)
        definitions++
        definitionText = result.text
        dictionaryInstanceId = result.dictionaryInstanceId
    }

    fun createTwoRounds() {
        val first = roundManager.startRound()
        val second = roundManager.startRound()
        roundIds = first.instanceId to second.instanceId
        freshRoundInstances = first !== second
    }
}
