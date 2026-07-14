package com.example.myapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapp.core.WordComparison
import com.example.myapp.core.WordManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class WordManagerRealGraphTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var wordManager: WordManager
    @Inject lateinit var wordComparison: WordComparison

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun productionGraphUsesBothQualifiedRepositories() {
        val sample = wordComparison.nextSample()

        assertTrue(wordManager.nextWord() in FANCY_WORDS)
        assertTrue(sample.basic in BASIC_WORDS)
        assertTrue(sample.fancy in FANCY_WORDS)
    }

    private companion object {
        val BASIC_WORDS = setOf("apple", "banana", "cherry")
        val FANCY_WORDS = setOf("dragonfruit", "kumquat", "persimmon")
    }
}
