package com.example.myapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapp.core.AsyncWordsLoader
import com.example.myapp.core.WordSyncManager
import com.example.myapp.core.WordSyncStatus
import com.example.myapp.core.di.ApplicationScope
import com.example.myapp.core.di.DefaultDispatcher
import com.example.myapp.core.di.IoDispatcher
import com.example.myapp.core.di.MainDispatcher
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
class CoroutinesGraphTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var loader: AsyncWordsLoader
    @Inject lateinit var syncManager: WordSyncManager
    @Inject lateinit var scheduler: TestCoroutineScheduler
    @Inject lateinit var testDispatcher: TestDispatcher

    @field:IoDispatcher
    @Inject
    lateinit var ioDispatcher: CoroutineDispatcher

    @field:DefaultDispatcher
    @Inject
    lateinit var defaultDispatcher: CoroutineDispatcher

    @field:MainDispatcher
    @Inject
    lateinit var mainDispatcher: CoroutineDispatcher

    @field:ApplicationScope
    @Inject
    lateinit var appScope: CoroutineScope

    @field:ApplicationScope
    @Inject
    lateinit var sameAppScope: CoroutineScope

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @After
    fun tearDown() {
        if (::appScope.isInitialized) appScope.cancel()
    }

    @Test
    fun qualifiedDispatchersShareTheInjectedScheduler() {
        assertSame(testDispatcher, ioDispatcher)
        assertSame(testDispatcher, defaultDispatcher)
        assertSame(testDispatcher, mainDispatcher)
        assertSame(scheduler, testDispatcher.scheduler)
    }

    @Test
    fun loaderIsQueuedAndUsesTheFancyRepository() = runTest(testDispatcher) {
        assertSame(scheduler, testScheduler)

        val words = async { loader.load() }

        assertFalse(words.isCompleted)
        runCurrent()
        assertEquals(listOf("dragonfruit", "kumquat", "persimmon"), words.await())
    }

    @Test
    fun applicationScopeRunsOnTheSameControlledScheduler() = runTest(testDispatcher) {
        assertSame(appScope, sameAppScope)

        val job = syncManager.syncInBackground()
        val repeatedRequest = syncManager.syncInBackground()

        assertSame(job, repeatedRequest)
        assertFalse(job.isCompleted)
        assertEquals(WordSyncStatus.IDLE, syncManager.state.value.status)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(WordSyncStatus.COMPLETE, syncManager.state.value.status)
        assertEquals(1, syncManager.state.value.completedSyncs)
        assertEquals(
            listOf("dragonfruit", "kumquat", "persimmon"),
            syncManager.state.value.words,
        )
    }
}
