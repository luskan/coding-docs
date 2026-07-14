package com.example.myapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapp.core.WordComparison
import com.example.myapp.core.WordManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WordManagerInstrumentedTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var wordManager: WordManager
    @Inject lateinit var wordComparison: WordComparison

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun suiteReplacementRunsUnderTheHiltTestApplication() {
        val sample = wordComparison.nextSample()

        assertTrue(composeRule.activity.application is HiltTestApplication)
        assertEquals("device-fancy-word", wordManager.nextWord())
        assertEquals("device-basic-word", sample.basic)
        assertEquals("device-fancy-word", sample.fancy)
        composeRule.onNodeWithText("device-fancy-word").assertIsDisplayed()
    }
}
