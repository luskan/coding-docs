package com.example.myapp.ui

import androidx.lifecycle.ViewModel
import com.example.myapp.core.WordsRepository
import com.example.myapp.core.di.FancyWords
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel

@HiltViewModel(assistedFactory = WordDetailViewModel.Factory::class)
class WordDetailViewModel @AssistedInject constructor(
    @Assisted val wordId: Int,
    @FancyWords repository: WordsRepository,
) : ViewModel() {

    val description: String = repository.getWords().let { words ->
        val word = words[wordId % words.size]
        "Assisted word #$wordId -> $word"
    }

    @AssistedFactory
    interface Factory {
        fun create(wordId: Int): WordDetailViewModel
    }
}
