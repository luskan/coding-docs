package com.example.myapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.example.myapp.core.WordManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltViewModel
class WordViewModel @Inject constructor(
    private val wordManager: WordManager,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val instanceId: Int = nextInstanceId.incrementAndGet()

    var currentWord: String by mutableStateOf(
        savedStateHandle[KEY_WORD] ?: wordManager.nextWord().also {
            savedStateHandle[KEY_WORD] = it
        },
    )
        private set

    var savedChanges: Int by mutableIntStateOf(savedStateHandle[KEY_CHANGES] ?: 0)
        private set

    fun chooseAndSaveAnotherWord() {
        val nextWord = wordManager.nextWord(excluding = currentWord)
        currentWord = nextWord
        savedStateHandle[KEY_WORD] = nextWord
        savedChanges++
        savedStateHandle[KEY_CHANGES] = savedChanges
    }

    private companion object {
        const val KEY_CHANGES = "changes"
        const val KEY_WORD = "word"
        val nextInstanceId = AtomicInteger()
    }
}
