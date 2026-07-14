package com.example.myapp

import androidx.lifecycle.SavedStateHandle
import com.example.myapp.core.FakeWordsRepository
import com.example.myapp.core.WordManager
import com.example.myapp.ui.WordViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class WordViewModelUnitTest {

    @Test
    fun constructorInjectionMakesAPlainUnitTestPossible() {
        val viewModel = WordViewModel(
            wordManager = WordManager(FakeWordsRepository()),
            savedStateHandle = SavedStateHandle(),
        )

        assertEquals("test-word", viewModel.currentWord)
        viewModel.chooseAndSaveAnotherWord()
        assertEquals(1, viewModel.savedChanges)
    }

    @Test
    fun savedStateWinsOverTheRepository() {
        val viewModel = WordViewModel(
            wordManager = WordManager(FakeWordsRepository()),
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "word" to "restored-word",
                    "changes" to 3,
                ),
            ),
        )

        assertEquals("restored-word", viewModel.currentWord)
        assertEquals(3, viewModel.savedChanges)
    }
}
