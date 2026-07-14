package com.example.myapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.myapp.core.WordComparison
import com.example.myapp.core.WordManager
import com.example.myapp.core.WordsRepository
import com.example.myapp.core.di.FancyWords
import com.example.myapp.core.di.StartupWordsEntryPoint
import dagger.hilt.android.EarlyEntryPoints
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
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
    @Inject lateinit var providerClient: WordsProviderClient
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @field:FancyWords
    @Inject
    lateinit var fancyRepository: WordsRepository

    @Before
    fun setUp() {
        hiltRule.inject()

        val executor = SynchronousExecutor()
        val configuration = Configuration.Builder()
            .setExecutor(executor)
            .setTaskExecutor(executor)
            .setWorkerFactory(workerFactory)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            composeRule.activity.applicationContext,
            configuration,
        )
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun suiteReplacementRunsUnderTheHiltTestApplication() {
        val sample = wordComparison.nextSample()

        assertTrue(composeRule.activity.application is HiltTestApplication)
        assertEquals("device-fancy-word", wordManager.nextWord())
        assertEquals("device-basic-word", sample.basic)
        assertEquals("device-fancy-word", sample.fancy)
        assertEquals("device-fancy-word", providerClient.nextWord())

        val earlyWordManager = EarlyEntryPoints
            .get(
                composeRule.activity.applicationContext,
                StartupWordsEntryPoint::class.java,
            )
            .wordManager()
        assertEquals("device-fancy-word", earlyWordManager.nextWord())
        assertFalse(earlyWordManager.usesRepository(fancyRepository))

        composeRule.onNodeWithText("device-fancy-word").assertIsDisplayed()

        composeRule.onNodeWithText("Query content provider")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Provider returned: device-fancy-word")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithText("Dictionary constructions: 0")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Define current word")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Definitions: 1").assertExists()
        composeRule.onNodeWithText("Dictionary constructions: 1").assertExists()
        composeRule.onNodeWithText("Define current word").performClick()
        composeRule.onNodeWithText("Definitions: 2").assertExists()
        composeRule.onNodeWithText("Dictionary constructions: 1").assertExists()

        composeRule.onNodeWithText("Create two rounds")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Fresh unscoped instances: true")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithText("Load on injected IO dispatcher")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Async words: device-fancy-word")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Async words: device-fancy-word")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithText("Start application-scope sync")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Application sync: complete")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Synced words: device-fancy-word")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithText("Enqueue injected worker")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Worker state: succeeded")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Worker words: device-fancy-word")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
