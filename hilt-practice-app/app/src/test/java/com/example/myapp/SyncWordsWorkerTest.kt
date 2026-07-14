package com.example.myapp

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.myapp.core.WordsRepository
import com.example.myapp.core.di.FancyWords
import com.example.myapp.work.SyncWordsWorker
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class SyncWordsWorkerTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var testDispatcher: TestDispatcher

    @field:FancyWords
    @Inject
    lateinit var fancyRepository: WordsRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun hiltFactoryBuildsWorkerAndInjectedLoaderProducesOutput() =
        runTest(testDispatcher) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val requestsBefore = fancyRepository.requestCount
            val worker = TestListenableWorkerBuilder<SyncWordsWorker>(context)
                .setWorkerFactory(workerFactory)
                .build()

            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            val success = result as ListenableWorker.Result.Success
            assertEquals(
                listOf("dragonfruit", "kumquat", "persimmon"),
                success.outputData
                    .getStringArray(SyncWordsWorker.OUTPUT_WORDS)
                    ?.toList(),
            )
            assertEquals(requestsBefore + 1, fancyRepository.requestCount)
        }
}
